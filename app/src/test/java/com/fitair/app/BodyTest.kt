package com.fitair.app

import com.fitair.app.data.Body
import org.junit.Assert.*
import org.junit.Test

class BodyTest {
    @Test fun bmiAndBands() {
        assertEquals(20.0, Body.bmi(57.8, 170.0)!!, 0.01)
        assertNull(Body.bmi(57.0, null)); assertNull(Body.bmi(57.0, 90.0)); assertNull(Body.bmi(null, 170.0))
        assertEquals("below 18.5", Body.band(18.4)); assertEquals("in the 18.5–25 range", Body.band(18.5))
        assertEquals("in the 25–30 range", Body.band(25.0)); assertEquals("30 or above", Body.band(30.0))
    }

    @Test fun rangeForHeight() {
        val (lo, hi) = Body.rangeKg(170.0)
        assertEquals(53.5, lo, 0.1); assertEquals(72.3, hi, 0.1)
    }
}
