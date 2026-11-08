package com.ozguryilmaz.navinavi

import kotlin.math.abs
import kotlin.math.roundToInt

/** Yeşili yakalamak için öneri (GLOSA). */
sealed interface SpeedAdvice {
    /** Mevcut hızla yeşile denk geliyor. */
    data object Keep : SpeedAdvice
    /** Bu hıza çık / yavaşla (km/sa). */
    data class Change(val kmh: Int, val faster: Boolean) : SpeedAdvice
    /** Hiçbir yasal hız yeşile yetişmiyor; tahmini bekleme (sn, bilinmiyorsa null). */
    data class Stop(val waitSec: Int?) : SpeedAdvice
}

/**
 * Işığa kalan mesafe, şimdiki hız ve tahmini yeşil aralıklarından hız önerisi çıkarır.
 * Kural: hız sınırının üstünde asla öneri yok; 20 km/sa'nin altına da yavaşlatmaz.
 */
object SpeedAdvisor {

    const val MIN_KMH = 20.0
    /** Öneri bu mesafe aralığında verilir (m). Daha yakında hızı ayarlamaya fırsat kalmaz. */
    const val MIN_DISTANCE_M = 40.0
    const val MAX_DISTANCE_M = 600.0
    /** Yeşil yandıktan en az bu kadar sonra varılsın (sn) — tahmin şaşabilir. */
    private const val START_MARGIN_S = 2.0
    /** Yeşil bitmeden en az bu kadar önce varılsın (sn). */
    private const val END_MARGIN_S = 3.0
    /** Duran araca öneri verilmez (m/sn). */
    private const val MOVING_MPS = 2.0

    /**
     * windows: yeşil aralıkları, şimdiden itibaren saniye olarak (başlangıç, bitiş).
     * Dönüş null: öneri verilecek durum değil (çok uzak/yakın, duruyor, veri yok).
     */
    fun advise(
        distanceM: Double,
        speedMps: Double,
        limitKmh: Double,
        windows: List<Pair<Double, Double>>,
    ): SpeedAdvice? {
        if (windows.isEmpty()) return null
        if (distanceM < MIN_DISTANCE_M || distanceM > MAX_DISTANCE_M) return null
        if (speedMps < MOVING_MPS) return null
        val vMax = limitKmh / 3.6
        val vMin = MIN_KMH / 3.6
        if (vMax <= vMin) return null

        // Varılabilir aralıklar (yeşilin güvenli kısmı).
        val safe = windows.map { (a, b) -> (a + START_MARGIN_S) to (b - END_MARGIN_S) }.filter { it.second > it.first }
        if (safe.isEmpty()) return null

        val arrival = distanceM / speedMps
        if (speedMps <= vMax + 0.5 && safe.any { arrival in it.first..it.second }) return SpeedAdvice.Keep

        // Her yeşil için o yeşilde varmayı sağlayan hız aralığı, yasal aralıkla kesişimi.
        var best: Double? = null
        for ((a, b) in safe) {
            if (b <= 0) continue
            val lo = distanceM / b
            val hi = if (a <= 0) Double.MAX_VALUE else distanceM / a
            val from = maxOf(lo, vMin)
            val to = minOf(hi, vMax)
            if (from > to) continue
            // Bu aralıkta şimdiki hıza en yakın hız.
            val v = speedMps.coerceIn(from, to)
            if (best == null || abs(v - speedMps) < abs(best - speedMps)) best = v
        }
        if (best != null) {
            val kmh = (best * 3.6).roundToInt()
            val now = (speedMps * 3.6).roundToInt()
            if (kmh == now) return SpeedAdvice.Keep
            return SpeedAdvice.Change(kmh, faster = kmh > now)
        }

        // Yetişilemiyor: şimdiki hızla (sınırı aşıyorsa sınırla) varınca ne kadar bekleneceği.
        val t = distanceM / minOf(speedMps, vMax)
        val next = windows.firstOrNull { it.first >= t }
        val wait = next?.let { (it.first - t).roundToInt() }
        return SpeedAdvice.Stop(wait?.takeIf { it > 0 })
    }

    fun text(a: SpeedAdvice): String = when (a) {
        SpeedAdvice.Keep -> "Bu hızla yeşile denk geliyorsun"
        is SpeedAdvice.Change ->
            if (a.faster) "~${a.kmh} km/sa'ye çık, yeşile denk gelirsin"
            else "~${a.kmh} km/sa'ye yavaşla, yeşile denk gelirsin"
        is SpeedAdvice.Stop ->
            if (a.waitSec != null) "Kırmızıya denk geleceksin, ~${a.waitSec} sn bekleme"
            else "Kırmızıya denk geleceksin"
    }

    /** Araba ekranı için kısa hali. */
    fun shortText(a: SpeedAdvice): String = when (a) {
        SpeedAdvice.Keep -> "hızın uygun"
        is SpeedAdvice.Change -> if (a.faster) "~${a.kmh} km/sa'ye çık" else "~${a.kmh} km/sa'ye yavaşla"
        is SpeedAdvice.Stop -> if (a.waitSec != null) "kırmızı, ~${a.waitSec} sn" else "kırmızı"
    }
}
