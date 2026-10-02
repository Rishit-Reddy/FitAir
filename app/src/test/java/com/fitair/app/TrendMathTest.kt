package com.fitair.app

import com.fitair.app.ui.trends.Band
import com.fitair.app.ui.trends.TrendMath
import org.junit.Assert.*
import org.junit.Test

class TrendMathTest {
    @Test fun bandNeedsSevenPoints() {
        assertNull(TrendMath.band(listOf(1.0, 2.0, null, 3.0)))
        val b = TrendMath.band(listOf(40.0, 50.0, 40.0, 50.0, 40.0, 50.0, 40.0, 50.0))!!
        assertEquals(45.0, b.mean, 1e-9); assertEquals(5.0, b.sd, 1e-9)
        assertEquals(40.0, b.lo, 1e-9); assertEquals(50.0, b.hi, 1e-9)
    }

    @Test fun baselineExcludesLatest() {
        val v = List(10) { 50.0 } + listOf<Double?>(80.0)
        assertEquals(50.0, TrendMath.baselineBefore(v, 10)!!.mean, 1e-9)
    }

    @Test fun deltaText() {
        val b = Band(40.0, 5.0)
        assertEquals("+3 ms vs your baseline", TrendMath.deltaText(43.2, b, "ms"))
        assertEquals("−2 bpm vs your baseline", TrendMath.deltaText(38.0, b, "bpm"))
        assertEquals("in line with your baseline", TrendMath.deltaText(40.3, b, "ms"))
        assertTrue(TrendMath.deltaText(40.0, null, "ms").contains("not enough"))
    }

    @Test fun streakAndInterpret() {
        val b = Band(40.0, 5.0)
        val v = listOf<Double?>(41.0, 30.0, null, 32.0, 33.0)
        assertEquals(-1 to 3, TrendMath.streak(v, b))
        assertEquals("HRV is below your usual range for 3 days.", TrendMath.interpret("HRV", v, b))
        assertEquals("HRV is within your usual range.", TrendMath.interpret("HRV", listOf(30.0, 41.0), b))
        assertEquals("HRV is above your usual range today.", TrendMath.interpret("HRV", listOf(41.0, 50.0), b))
        assertTrue(TrendMath.interpret("HRV", v, null).startsWith("Not enough"))
    }

    @Test fun acwr() {
        assertTrue(TrendMath.acwrText(0.6).contains("lighter"))
        assertTrue(TrendMath.acwrText(1.0).contains("in line"))
        assertTrue(TrendMath.acwrText(1.5).contains("above"))
        assertTrue(TrendMath.acwrText(null).startsWith("Not enough"))
    }
}
