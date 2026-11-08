package com.ozguryilmaz.navinavi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeedAdvisorTest {

    private fun kmh(v: Double) = v / 3.6

    @Test
    fun keepWhenArrivingInGreen() {
        // 300 m, 50 km/sa → ~21,6 sn'de varış; yeşil 10–40 sn arası.
        val a = SpeedAdvisor.advise(300.0, kmh(50.0), 50.0, listOf(10.0 to 40.0))
        assertEquals(SpeedAdvice.Keep, a)
    }

    @Test
    fun slowDownToCatchGreen() {
        // 300 m, 50 km/sa ile 21,6 sn'de varır ama yeşil 35. sn'de yanıyor → yavaşla.
        val a = SpeedAdvisor.advise(300.0, kmh(50.0), 50.0, listOf(35.0 to 70.0))
        assertTrue(a is SpeedAdvice.Change)
        a as SpeedAdvice.Change
        assertTrue(!a.faster)
        // 300 m / 37 sn ≈ 29 km/sa
        assertEquals(29.0, a.kmh.toDouble(), 1.0)
    }

    @Test
    fun speedUpButNeverAboveLimit() {
        // 400 m, 40 km/sa → 36 sn; yeşil 25. sn'de bitiyor. 400/22 sn = 65 km/sa gerekir, sınır 50 → yetişilmez.
        val a = SpeedAdvisor.advise(400.0, kmh(40.0), 50.0, listOf(-5.0 to 25.0, 95.0 to 125.0))
        assertTrue("sınırı aşan öneri olmamalı: $a", a is SpeedAdvice.Stop)
        // Sınır 70 olsaydı çıkılabilirdi.
        val b = SpeedAdvisor.advise(400.0, kmh(40.0), 70.0, listOf(-5.0 to 25.0, 95.0 to 125.0))
        assertTrue(b is SpeedAdvice.Change)
        b as SpeedAdvice.Change
        assertTrue(b.faster)
        assertTrue(b.kmh <= 70)
    }

    @Test
    fun stopWithWaitTime() {
        // 100 m, 50 km/sa; yeşil 60 sn sonra. 20 km/sa ile bile 18 sn'de varılır → beklenir.
        val a = SpeedAdvisor.advise(100.0, kmh(50.0), 50.0, listOf(60.0 to 90.0))
        assertTrue(a is SpeedAdvice.Stop)
        assertEquals(53, (a as SpeedAdvice.Stop).waitSec)
    }

    @Test
    fun overLimitIsToldToSlowDown() {
        // 70 km/sa ile gidiyor, sınır 50; yeşile 50 ile de yetişiyor → 50'ye yavaşla, "uygun" deme.
        val a = SpeedAdvisor.advise(300.0, kmh(70.0), 50.0, listOf(0.0 to 60.0))
        assertTrue(a is SpeedAdvice.Change)
        assertEquals(50, (a as SpeedAdvice.Change).kmh)
    }

    @Test
    fun noAdviceWhenTooFarTooCloseOrStopped() {
        assertNull(SpeedAdvisor.advise(900.0, kmh(50.0), 50.0, listOf(10.0 to 40.0)))
        assertNull(SpeedAdvisor.advise(20.0, kmh(50.0), 50.0, listOf(10.0 to 40.0)))
        assertNull(SpeedAdvisor.advise(300.0, 0.5, 50.0, listOf(10.0 to 40.0)))
    }

    @Test
    fun texts() {
        assertEquals("~38 km/sa'ye yavaşla, yeşile denk gelirsin", SpeedAdvisor.text(SpeedAdvice.Change(38, false)))
        assertEquals("~52 km/sa'ye çık, yeşile denk gelirsin", SpeedAdvisor.text(SpeedAdvice.Change(52, true)))
        assertEquals("Kırmızıya denk geleceksin, ~25 sn bekleme", SpeedAdvisor.text(SpeedAdvice.Stop(25)))
    }
}
