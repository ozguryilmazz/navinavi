package com.ozguryilmaz.navinavi

import com.ozguryilmaz.navinavi.LightRecord.Kind.GREEN
import com.ozguryilmaz.navinavi.LightRecord.Kind.RED
import com.ozguryilmaz.navinavi.LightRecord.Source.AUTO
import com.ozguryilmaz.navinavi.LightRecord.Source.USER
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Random
import kotlin.math.abs

/**
 * Uydurma bir ışık: döngü 96 sn, kırmızı 60 sn, yeşil 36 sn, saate göre sabit program.
 * Kullanıcının basışları ±1,5 sn şaşar.
 */
class LightPredictorTest {

    private val zone = ZoneId.of("Europe/Istanbul")
    private val cycleMs = 96_000L
    private val redMs = 60_000L
    private val greenMs = 36_000L
    /** Programın bir yeşil başlangıcı. */
    private val phase = at(2026, 11, 1, 0, 0) + 13_000L

    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int, s: Int = 0): Long =
        LocalDateTime.of(y, mo, d, h, mi, s).atZone(zone).toInstant().toEpochMilli()

    /** t anından sonraki ilk yeşil başlangıcı (gerçek program). */
    private fun nextGreen(t: Long): Long = phase + Math.floorDiv(t - phase + cycleMs - 1, cycleMs) * cycleMs

    private fun jitter(r: Random) = ((r.nextDouble() * 2 - 1) * 1_500).toLong()

    private fun rec(kind: LightRecord.Kind, t: Long, src: LightRecord.Source = USER) = LightRecord("1:0", kind, t, src)

    /** Her gün sabah 08:xx'te bir geçiş: kırmızıya döndüğünü görüp 🔴, yeşil yanınca 🟢. */
    private fun redGreenVisits(days: Int, seed: Long = 1, synced: Boolean = true): List<LightRecord> {
        val r = Random(seed)
        val out = mutableListOf<LightRecord>()
        for (d in 0 until days) {
            val arrive = at(2026, 11, 2 + d, 8, 10 + r.nextInt(30))
            val shift = if (synced) 0L else r.nextInt(96_000).toLong()
            val g = nextGreen(arrive) + shift
            out += rec(RED, g - redMs + jitter(r))
            out += rec(GREEN, g + jitter(r))
        }
        return out
    }

    /** Bazı günler yeşili uzaktan görüp kırmızıya dönüşünü de görür: 🟢 sonra 🔴. */
    private fun greenRedVisits(days: Int, seed: Long = 2): List<LightRecord> {
        val r = Random(seed)
        val out = mutableListOf<LightRecord>()
        for (d in 0 until days) {
            val arrive = at(2026, 11, 2 + d, 9, 5 + r.nextInt(30))
            val g = nextGreen(arrive)
            out += rec(GREEN, g + jitter(r))
            out += rec(RED, g + greenMs + jitter(r))
        }
        return out
    }

    @Test
    fun learnsRedGreenAndCycle() {
        val records = redGreenVisits(6) + greenRedVisits(4)
        val now = at(2026, 11, 12, 8, 30)
        val t = LightPredictor.timing(records, now, zone)
        assertNotNull(t)
        t!!
        assertEquals(60.0, t.redSec!!, 2.0)
        assertEquals(36.0, t.greenSec!!, 2.0)
        assertEquals(96.0, t.cycleSec!!, 0.01)
        assertFalse(t.variable)
        assertNotNull("saat programı bulunmalı", t.phaseMs)
        assertTrue(t.fromBucket)
    }

    @Test
    fun liveForecastAfterRedPress() {
        val history = redGreenVisits(5) + greenRedVisits(3)
        val now = at(2026, 11, 12, 8, 40)
        // Işık 10 sn önce kırmızıya döndü, kullanıcı bastı.
        val redAt = nextGreen(now) - redMs
        val pressNow = redAt + 10_000
        val f = LightPredictor.forecast(history + rec(RED, redAt), pressNow, zone)
        assertNotNull(f)
        f!!
        assertTrue(f.live)
        assertTrue(f.confident)
        val start = f.windows.first().startMs
        assertTrue("yeşil ~50 sn sonra: ${(start - pressNow) / 1000.0}", abs(start - nextGreen(now)) <= 2_500)
        val text = LightPredictor.text(f, pressNow)!!
        val sec = Regex("Tahmini yeşil: ~(\\d+) sn").matchEntire(text)?.groupValues?.get(1)?.toInt()
        assertNotNull("yazı: $text", sec)
        assertTrue("yazı: $text", sec!! in 47..53)
    }

    @Test
    fun scheduleForecastWithoutPress() {
        val records = redGreenVisits(8) + greenRedVisits(4)
        val now = at(2026, 11, 14, 8, 21, 7)
        val f = LightPredictor.forecast(records, now, zone)
        assertNotNull(f)
        f!!
        assertFalse(f.live)
        // Şu an yanan ya da sıradaki yeşil gerçek programla 3 sn içinde tutmalı.
        val w = f.windows.first()
        val real = if (now - (nextGreen(now) - cycleMs) < greenMs) nextGreen(now) - cycleMs else nextGreen(now)
        assertTrue("tahmin ${w.startMs - real} ms şaştı", abs(w.startMs - real) <= 3_000)
        assertEquals(36_000.0, (w.endMs - w.startMs).toDouble(), 2_500.0)
    }

    @Test
    fun noScheduleWhenDaysAreNotSynced() {
        // Her gün program farklı saatte başlıyor: süreler öğrenilir ama saat programı yok.
        val records = redGreenVisits(8, synced = false)
        val now = at(2026, 11, 14, 8, 30)
        val t = LightPredictor.timing(records, now, zone)!!
        assertEquals(60.0, t.redSec!!, 2.0)
        assertNull(t.phaseMs)
        assertNull("basış yokken tahmin olmamalı", LightPredictor.forecast(records, now, zone))
        // Ama bu geçişte 🔴'ye basınca canlı tahmin çalışır.
        val press = now - 5_000
        val f = LightPredictor.forecast(records + rec(RED, press), now, zone)!!
        assertEquals(55.0, (f.windows.first().startMs - now) / 1000.0, 2.0)
    }

    @Test
    fun tooFewRecords() {
        val records = redGreenVisits(2)
        val now = at(2026, 11, 12, 8, 30)
        assertNull(LightPredictor.forecast(records + rec(RED, now - 1_000), now, zone))
    }

    @Test
    fun variableLightGetsNoForecast() {
        // Akıllı kavşak: kırmızı süresi her seferinde çok farklı.
        val r = Random(5)
        val records = (0 until 8).flatMap { d ->
            val t = at(2026, 11, 2 + d, 8, 15)
            val red = 25_000L + r.nextInt(70_000)
            listOf(rec(RED, t), rec(GREEN, t + red))
        }
        val now = at(2026, 11, 12, 8, 30)
        assertTrue(LightPredictor.timing(records, now, zone)!!.variable)
        assertNull(LightPredictor.forecast(records + rec(RED, now - 3_000), now, zone))
    }

    @Test
    fun otherTimeOfDayIsNotConfident() {
        // Kayıtlar sabah; şimdi akşam. Süre gösterilebilir ama hız önerisi için güvenilmez.
        val records = redGreenVisits(6)
        val now = at(2026, 11, 12, 18, 0)
        val f = LightPredictor.forecast(records + rec(RED, now - 5_000), now, zone)
        assertNotNull(f)
        assertFalse(f!!.confident)
    }

    @Test
    fun autoRecordsAloneAreNotConfident() {
        val r = Random(9)
        val records = (0 until 6).flatMap { d ->
            val g = nextGreen(at(2026, 11, 2 + d, 8, 20))
            listOf(rec(RED, g - redMs + jitter(r)), rec(GREEN, g - 3_000 + jitter(r), AUTO))
        }
        val now = at(2026, 11, 12, 8, 30)
        val f = LightPredictor.forecast(records + rec(RED, now - 5_000), now, zone)
        assertNotNull(f)
        assertFalse(f!!.confident)
    }

    @Test
    fun bucketsByHour() {
        assertEquals(0, LightPredictor.bucket(at(2026, 11, 2, 7, 59), zone))
        assertEquals(1, LightPredictor.bucket(at(2026, 11, 2, 12, 0), zone))
        assertEquals(2, LightPredictor.bucket(at(2026, 11, 2, 17, 30), zone))
        assertEquals(3, LightPredictor.bucket(at(2026, 11, 2, 23, 0), zone))
        assertEquals(3, LightPredictor.bucket(at(2026, 11, 2, 3, 0), zone))
    }
}
