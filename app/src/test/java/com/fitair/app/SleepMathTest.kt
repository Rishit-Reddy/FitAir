package com.fitair.app

import com.fitair.app.analytics.SleepMath
import org.junit.Assert.*
import org.junit.Test

class SleepMathTest {
    @Test fun weightsSumToOne() = assertEquals(1.0, SleepMath.WEIGHTS.values.sum(), 1e-9)

    @Test fun needAdaptsAndClamps() {
        assertEquals(450.0, SleepMath.need(null), 0.0)
        assertEquals(420.0, SleepMath.need(390.0), 1e-9)
        assertEquals(360.0, SleepMath.need(100.0), 0.0)
        assertEquals(540.0, SleepMath.need(900.0), 0.0)
    }

    @Test fun durationScore() {
        assertEquals(100.0, SleepMath.durationScore(450.0, 450.0), 1e-9)
        assertEquals(100.0, SleepMath.durationScore(600.0, 450.0), 0.0)
        assertEquals(50.0, SleepMath.durationScore(225.0, 450.0), 1e-9)
    }

    @Test fun efficiencyScore() {
        val e = SleepMath.efficiency(420.0, 42.0)
        assertEquals(420.0 / 462.0, e, 1e-12)
        assertEquals(0.0, SleepMath.efficiencyScore(0.60), 0.0)
        assertEquals(100.0, SleepMath.efficiencyScore(0.95), 0.0)
        assertEquals(50.0, SleepMath.efficiencyScore(0.775), 1e-9)
    }

    @Test fun restorativeScore() {
        val p = SleepMath.restorativeFraction(60.0, 80.0, 400.0)
        assertEquals(0.35, p, 1e-12)
        assertEquals(0.35 / 0.38 * 100, SleepMath.restorativeScore(p), 1e-9)
        assertEquals(100.0, SleepMath.restorativeScore(0.5), 0.0)
    }

    @Test fun consistencyScore() {
        assertEquals(100.0, SleepMath.consistencyScore(10.0), 0.0)
        assertEquals(100.0, SleepMath.consistencyScore(20.0), 1e-9)
        assertEquals(50.0, SleepMath.consistencyScore(55.0), 1e-9)
        assertEquals(0.0, SleepMath.consistencyScore(120.0), 0.0)
    }

    @Test fun combineRenormalises() {
        assertNull(SleepMath.combine(emptyMap()))
        assertEquals(80.0, SleepMath.combine(mapOf("duration" to 80.0)))
        // duration .40 + efficiency .25 -> (0.4*100 + 0.25*40) / 0.65
        assertEquals(76.9, SleepMath.combine(mapOf("duration" to 100.0, "efficiency" to 40.0))!!, 1e-9)
    }
}
