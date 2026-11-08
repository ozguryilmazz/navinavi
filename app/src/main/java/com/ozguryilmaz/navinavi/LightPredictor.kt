package com.ozguryilmaz.navinavi

import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Tahmini bir yeşil aralığı (mutlak zaman, ms).
 * endAssumed: yeşilin süresi bilinmiyor, güvenli tarafta kalmak için kısa bir süre varsayıldı.
 */
data class GreenWindow(val startMs: Long, val endMs: Long, val endAssumed: Boolean = false)

/** Bir ışığın kayıtlarından öğrenilen süreleri. */
data class LightTiming(
    /** Kırmızının başından yeşilin başına (sn). Kullanıcı kırmızıyı gördüğü an, yeşili yandığı an basar. */
    val redSec: Double?,
    /** Yeşilin başından kırmızının başına (sn, sarı dahil). */
    val greenSec: Double?,
    /** Döngü (sn) = kırmızı + yeşil. */
    val cycleSec: Double?,
    /** Sadece kullanıcı kayıtlarından (kesin) gelen ölçüm sayısı. */
    val strongRed: Int,
    val strongGreen: Int,
    /** Ölçümler birbirini tutmuyor: akıllı/değişken kavşak, tahmin gösterilmez. */
    val variable: Boolean,
    /** Işık saate göre sabit çalışıyorsa herhangi bir yeşil başlangıcı (ms); yoksa null. */
    val phaseMs: Long?,
    /** Şimdiki saat dilimindeki kayıtlardan mı (sabah/gün/akşam/gece) öğrenildi. */
    val fromBucket: Boolean,
)

/** Önümüzdeki ışık için tahmin. */
data class LightForecast(
    /** Şimdiden itibaren sıradaki yeşiller (şu an yanan yeşil dahil), zamana göre sıralı. */
    val windows: List<GreenWindow>,
    /** Hız önerisi verilecek kadar güvenilir mi. Değilse sadece süre "tahmini" diye gösterilir. */
    val confident: Boolean,
    /** Bu geçişte basılan bir 🟢/🔴'den mi (canlı) yoksa ışığın saat programından mı. */
    val live: Boolean,
)

/**
 * Trafik ışığı tahmini. Her ışık kendi kayıtlarından ayrı öğrenilir.
 *
 * Kayıtlar "geçiş"lere ayrılır (aynı ışığa birkaç dakika içinde yapılan basışlar):
 *  - 🔴 sonra 🟢 → kırmızı süresi
 *  - 🟢 sonra 🔴 → yeşil süresi
 *  - 🟢 sonra 🟢 (ya da 🔴 sonra 🔴) → döngü
 * Süreler ağırlıklı ortanca ile bulunur; dağınıksa ışık "değişken" sayılır.
 *
 * İki tür tahmin var:
 *  - Canlı: bu geçişte kırmızıya döndüğünü gördünüz ve 🔴'ye bastınız → yeşil = basış + kırmızı süresi.
 *    En sağlam yol; ışığın saati bilinmese de çalışır.
 *  - Program: ışık her gün aynı saate göre çalışıyorsa, eski yeşil başlangıçları döngünün tam
 *    katlarıyla birbirini tutar. O zaman ışığa hiç bakmadan da sıradaki yeşil hesaplanır.
 */
object LightPredictor {

    /** Tahmin için en az bu kadar ölçüm gerekir. */
    const val MIN_SAMPLES = 3
    /** Aynı geçişin basışları arasında en fazla bu kadar süre olur. */
    private const val VISIT_GAP_MS = 200_000L
    private const val MAX_AGE_MS = 60L * 24 * 3600 * 1000
    private const val MIN_CYCLE_S = 40
    private const val MAX_CYCLE_S = 180
    /** Yeşil süresi bilinmiyorsa en az bu kadar sürdüğü varsayılır (güvenli taraf). */
    const val ASSUMED_GREEN_S = 8.0
    /** Canlı tahmin için son basış en fazla bu kadar eski olabilir. */
    private const val LIVE_MAX_AGE_MS = 180_000L
    /** Program tahmini için yeşil başlangıçlarının döngüye oturma ölçüsü (1 = kusursuz). */
    private const val PHASE_MIN_R = 0.93
    private const val AUTO_WEIGHT = 0.4

    /** Saat dilimi: 0 sabah (06–10), 1 gün (10–16), 2 akşam (16–20), 3 gece (20–06). */
    fun bucket(timeMs: Long, zone: ZoneId): Int = when (Instant.ofEpochMilli(timeMs).atZone(zone).hour) {
        in 6..9 -> 0
        in 10..15 -> 1
        in 16..19 -> 2
        else -> 3
    }

    /**
     * Önce şimdiki saat dilimindeki kayıtlardan öğrenir; orada yeterli veri yoksa bütün
     * kayıtlardan (o zaman güven düşük sayılır, hız önerisi verilmez).
     */
    fun timing(records: List<LightRecord>, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): LightTiming? {
        val recent = records.filter { nowMs - it.timeMs in 0..MAX_AGE_MS }.sortedBy { it.timeMs }
        if (recent.isEmpty()) return null
        val b = bucket(nowMs, zone)
        val inBucket = analyse(recent.filter { bucket(it.timeMs, zone) == b }, fromBucket = true)
        if (inBucket != null && (inBucket.redSec != null || inBucket.phaseMs != null)) return inBucket
        return analyse(recent, fromBucket = false) ?: inBucket
    }

    private class Sample(val valueMs: Long, val weight: Double, val strong: Boolean)

    private fun analyse(sorted: List<LightRecord>, fromBucket: Boolean): LightTiming? {
        if (sorted.size < 2) return null
        val red = mutableListOf<Sample>()
        val green = mutableListOf<Sample>()
        val cycle = mutableListOf<Sample>()
        for (i in 1 until sorted.size) {
            val a = sorted[i - 1]
            val b = sorted[i]
            val gap = b.timeMs - a.timeMs
            if (gap > VISIT_GAP_MS) continue
            val strong = a.source == LightRecord.Source.USER && b.source == LightRecord.Source.USER
            val s = Sample(gap, if (strong) 1.0 else AUTO_WEIGHT, strong)
            when {
                a.kind == LightRecord.Kind.RED && b.kind == LightRecord.Kind.GREEN && gap in 8_000L..170_000L -> red += s
                a.kind == LightRecord.Kind.GREEN && b.kind == LightRecord.Kind.RED && gap in 5_000L..140_000L -> green += s
                a.kind == b.kind && gap in MIN_CYCLE_S * 1000L..MAX_CYCLE_S * 1000L -> cycle += s
            }
        }
        val redMs = weightedMedian(red)
        val greenMs = weightedMedian(green)
        val cycleMs = weightedMedian(cycle)
        val variable = scattered(red, redMs) || scattered(green, greenMs) || scattered(cycle, cycleMs)

        // Döngü: kırmızı + yeşil biliniyorsa onların toplamı, yoksa doğrudan ölçülen döngü.
        val c0 = when {
            redMs != null && greenMs != null -> (redMs + greenMs) / 1000.0
            cycleMs != null -> cycleMs / 1000.0
            else -> null
        }
        val phase = if (variable) null else phaseFit(sorted, c0)
        val cycleSec = phase?.first?.toDouble() ?: c0
        if (redMs == null && greenMs == null && cycleSec == null) return null
        return LightTiming(
            redSec = redMs?.div(1000.0),
            greenSec = greenMs?.div(1000.0),
            cycleSec = cycleSec,
            strongRed = red.count { it.strong },
            strongGreen = green.count { it.strong },
            variable = variable,
            phaseMs = phase?.second,
            fromBucket = fromBucket,
        )
    }

    /**
     * Yeşil başlangıçları ışığın saat programına oturuyor mu? Işık kontrol cihazları döngüyü tam
     * saniye olarak çalıştırır; adaylar tam saniyelerdir. Rastgele uyumları elemek için bilinen
     * bir döngü yoksa çok daha fazla kayıt istenir.
     * Dönüş: (döngü sn, bir yeşil başlangıcı ms) ya da null.
     */
    private fun phaseFit(sorted: List<LightRecord>, c0: Double?): Pair<Int, Long>? {
        val greens = sorted.filter { it.kind == LightRecord.Kind.GREEN }
        val visits = countVisits(greens)
        val candidates: IntRange = if (c0 != null) {
            if (greens.size < 6 || visits < 3) return null
            val c = c0.roundToInt()
            (c - 3).coerceAtLeast(MIN_CYCLE_S)..(c + 3).coerceAtMost(MAX_CYCLE_S)
        } else {
            if (greens.size < 10 || visits < 5) return null
            MIN_CYCLE_S..MAX_CYCLE_S
        }
        var bestR = 0.0
        var bestC = 0
        var bestPhase = 0L
        // Kısa döngülerin katları da aynı uyumu verir (90 tutuyorsa 45 de tutar); eşitlikte uzun olan seçilir.
        for (c in candidates) {
            val cMs = c * 1000L
            var x = 0.0
            var y = 0.0
            var wSum = 0.0
            for (g in greens) {
                val w = if (g.source == LightRecord.Source.USER) 1.0 else AUTO_WEIGHT
                val th = 2 * Math.PI * Math.floorMod(g.timeMs, cMs) / cMs
                x += w * cos(th)
                y += w * sin(th)
                wSum += w
            }
            val r = sqrt(x * x + y * y) / wSum
            if (r > bestR + 0.005 || (r >= bestR - 0.005 && c > bestC)) {
                if (r > bestR) bestR = r
                bestC = c
                var th = atan2(y, x)
                if (th < 0) th += 2 * Math.PI
                bestPhase = (th / (2 * Math.PI) * cMs).roundToLong()
            }
        }
        if (bestR < PHASE_MIN_R) return null
        // Saniye cinsinden sapma (dairesel standart sapma) 5 sn'yi geçmesin.
        val spreadS = sqrt(-2 * ln(bestR)) * bestC / (2 * Math.PI)
        if (spreadS > 5.0) return null
        return bestC to bestPhase
    }

    private fun countVisits(rs: List<LightRecord>): Int {
        var n = 0
        var last = Long.MIN_VALUE / 2
        for (r in rs) {
            if (r.timeMs - last > VISIT_GAP_MS) n++
            last = r.timeMs
        }
        return n
    }

    private fun weightedMedian(s: List<Sample>): Long? {
        if (s.size < MIN_SAMPLES) return null
        val sorted = s.sortedBy { it.valueMs }
        val half = sorted.sumOf { it.weight } / 2
        var acc = 0.0
        for (x in sorted) {
            acc += x.weight
            if (acc >= half) return x.valueMs
        }
        return sorted.last().valueMs
    }

    /** Ölçümlerin yarısından fazlası ortancadan max(4 sn, %15) uzaktaysa dağınık. */
    private fun scattered(s: List<Sample>, median: Long?): Boolean {
        if (median == null) return false
        val tol = maxOf(4_000.0, median * 0.15)
        val far = s.count { abs(it.valueMs - median) > tol }
        return far * 2 > s.size
    }

    /**
     * Önümüzdeki ışık için tahmin.
     * records: bu ışığın bütün kayıtları (bu geçişte basılanlar dahil).
     */
    fun forecast(records: List<LightRecord>, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): LightForecast? {
        val t = timing(records, nowMs, zone) ?: return null
        if (t.variable) return null
        val last = records.filter { it.source == LightRecord.Source.USER && nowMs - it.timeMs in 0..LIVE_MAX_AGE_MS }
            .maxByOrNull { it.timeMs }
        live(t, last, nowMs)?.let { return it }
        return schedule(t, nowMs)
    }

    private fun live(t: LightTiming, last: LightRecord?, nowMs: Long): LightForecast? {
        if (last == null) return null
        val windows = mutableListOf<GreenWindow>()
        val confident: Boolean
        when (last.kind) {
            LightRecord.Kind.RED -> {
                val red = t.redSec ?: return null
                val start = last.timeMs + (red * 1000).toLong()
                windows += window(start, t.greenSec)
                t.cycleSec?.let { c -> windows += window(start + (c * 1000).toLong(), t.greenSec) }
                confident = t.strongRed >= MIN_SAMPLES && t.fromBucket
            }
            LightRecord.Kind.GREEN -> {
                val green = t.greenSec ?: return null
                windows += GreenWindow(last.timeMs, last.timeMs + (green * 1000).toLong())
                t.cycleSec?.let { c -> windows += window(last.timeMs + (c * 1000).toLong(), green) }
                confident = t.strongGreen >= MIN_SAMPLES && t.fromBucket
            }
        }
        val upcoming = windows.filter { it.endMs > nowMs }
        if (upcoming.isEmpty()) return null
        return LightForecast(upcoming, confident, live = true)
    }

    private fun schedule(t: LightTiming, nowMs: Long): LightForecast? {
        val phase = t.phaseMs ?: return null
        val c = t.cycleSec ?: return null
        val cMs = (c * 1000).toLong()
        val greenS = t.greenSec ?: t.redSec?.let { c - it }
        val greenMs = ((greenS ?: ASSUMED_GREEN_S) * 1000).toLong()
        // Şu an yanıyor olabilecek yeşilden başla.
        var k = Math.floorDiv(nowMs - greenMs - phase, cMs) + 1
        val windows = mutableListOf<GreenWindow>()
        while (windows.size < 3) {
            val start = phase + k * cMs
            windows += GreenWindow(start, start + greenMs, endAssumed = greenS == null)
            k++
        }
        val confident = greenS != null && t.fromBucket &&
            (t.strongRed >= MIN_SAMPLES || t.strongGreen >= MIN_SAMPLES)
        return LightForecast(windows, confident, live = false)
    }

    private fun window(startMs: Long, greenSec: Double?): GreenWindow =
        GreenWindow(startMs, startMs + ((greenSec ?: ASSUMED_GREEN_S) * 1000).toLong(), endAssumed = greenSec == null)

    /** Ekranda gösterilecek kısa yazı: "Tahmini yeşil: ~23 sn" ya da "Tahmini kırmızıya: ~9 sn". */
    fun text(f: LightForecast, nowMs: Long): String? {
        val w = f.windows.firstOrNull { it.endMs > nowMs } ?: return null
        return if (nowMs >= w.startMs) {
            if (w.endAssumed) "Tahmini: yeşil yanıyor"
            else "Tahmini kırmızıya: ~${secondsUntil(w.endMs, nowMs)} sn"
        } else {
            "Tahmini yeşil: ~${secondsUntil(w.startMs, nowMs)} sn"
        }
    }

    private fun secondsUntil(t: Long, now: Long): Int = ((t - now + 500) / 1000).toInt().coerceAtLeast(0)
}
