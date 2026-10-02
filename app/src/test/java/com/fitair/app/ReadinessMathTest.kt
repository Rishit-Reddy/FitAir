package com.fitair.app

import com.fitair.app.analytics.ReadinessMath
import org.junit.Assert.*
import org.junit.Test

class ReadinessMathTest {
    @Test fun weightsSumToOne_andAtMostFiveComponents() {
        assertEquals(1.0, ReadinessMath.weightsSum(), 1e-9)
        assertTrue(ReadinessMath.WEIGHTS.size <= 5)
    }

    @Test fun noDoubleCountedKeys() {
        // v1 counted sleep duration + sleep score and load ratio + ACWR; v2 has neither pair
        assertFalse("sleep_quality" in ReadinessMath.WEIGHTS)
        assertFalse("acwr" in ReadinessMath.WEIGHTS)
    }

    @Test fun combineRenormalisesMissingComponents() {
        val c = ReadinessMath.combine(mapOf("sleep" to 80.0, "hrv" to 60.0))!!
        // weights 0.30 / 0.25 renormalised to 6/11 and 5/11
        assertEquals(Math.rint(80.0 * 6 / 11 + 60.0 * 5 / 11).toInt(), c.score)
        assertEquals(1.0, c.weights.values.sum(), 1e-9)
    }

    @Test fun needsTwoComponents() {
        assertNull(ReadinessMath.combine(mapOf("sleep" to 80.0)))
        assertNull(ReadinessMath.combine(emptyMap()))
    }

    @Test fun allFiveGoldenScore() {
        val c = ReadinessMath.combine(mapOf("sleep" to 80.0, "hrv" to 70.0, "resting_hr" to 75.0, "load" to 100.0, "subjective" to 50.0))!!
        // 24 + 17.5 + 11.25 + 15 + 7.5 = 75.25
        assertEquals(75, c.score)
        assertEquals("load", c.ranked.first()) // 0.15 * 25 = 3.75 biggest absolute impact
    }

    @Test fun impactSignAndRanking() {
        val c = ReadinessMath.combine(mapOf("sleep" to 40.0, "hrv" to 90.0, "resting_hr" to 75.0))!!
        assertTrue(c.impact.getValue("sleep") < 0)
        assertTrue(c.impact.getValue("hrv") > 0)
        assertEquals(0.0, c.impact.getValue("resting_hr"), 1e-9)
        assertEquals("sleep", c.ranked.first())
    }

    @Test fun percentsAlwaysAddTo100() {
        for (keys in listOf(ReadinessMath.WEIGHTS.keys.toList(), listOf("sleep", "hrv"), listOf("hrv", "resting_hr", "load"), listOf("sleep", "hrv", "subjective"))) {
            val c = ReadinessMath.combine(keys.associateWith { 70.0 })!!
            assertEquals(keys.toString(), 100, ReadinessMath.percents(c.weights).values.sum())
        }
    }

    @Test fun zScoreAndDirection() {
        val z = ReadinessMath.z(38.0, 52.0, 10.0, 0.05)
        assertEquals(-1.4, z, 1e-9)
        assertEquals(40.0, ReadinessMath.zScore(z, 1), 1e-9)      // HRV below baseline hurts
        assertEquals(110.0.coerceAtMost(100.0), ReadinessMath.zScore(z, -1), 1e-9) // RHR below baseline is fine (clipped)
        assertEquals(0.0, ReadinessMath.zScore(-10.0, 1), 0.0)
    }

    @Test fun zUsesNoiseFloor() {
        // tiny sd: floor of max(1.0, 5% of mean) applies
        assertEquals((60.0 - 50.0) / 2.5, ReadinessMath.z(60.0, 50.0, 0.1, 0.05), 1e-9)
    }

    @Test fun acwrScore() {
        assertEquals(100.0, ReadinessMath.acwrScore(1.0), 0.0)
        assertEquals(100.0, ReadinessMath.acwrScore(1.3), 1e-9)
        assertEquals(70.0, ReadinessMath.acwrScore(1.45), 1e-9)
        assertEquals(90.0, ReadinessMath.acwrScore(0.7), 1e-9)
        assertEquals(0.0, ReadinessMath.acwrScore(2.5), 0.0)
    }

    @Test fun subjectiveScore() {
        assertEquals(0.0, ReadinessMath.subjectiveScore(1.0), 0.0)
        assertEquals(50.0, ReadinessMath.subjectiveScore(3.0), 0.0)
        assertEquals(100.0, ReadinessMath.subjectiveScore(5.0), 0.0)
    }

    @Test fun versionIsThreeWithWholeDayLoadDriver() {
        assertEquals(3, ReadinessMath.VERSION)
        assertEquals("load", ReadinessMath.WEIGHTS.keys.first { it == "load" })
    }

    @Test fun loadRatioScoreBands() = listOf(
        // ratio, score
        0.5 to 70.0, 0.8 to 100.0, 1.0 to 100.0, 1.3 to 100.0, 1.4 to 80.0, 1.5 to 60.0, 2.0 to 0.0, 0.0 to 20.0,
    ).forEach { (r, sc) -> assertEquals("ratio $r", sc, ReadinessMath.acwrScore(r), 1e-9) }
}
