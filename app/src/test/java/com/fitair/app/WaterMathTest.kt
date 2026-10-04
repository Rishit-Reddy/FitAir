package com.fitair.app

import com.fitair.app.ui.water.WaterMath
import org.junit.Assert.*
import org.junit.Test

class WaterMathTest {
    @Test fun usualByNowIsMedianOfEarlierDays() {
        val days = listOf(
            listOf(480 to 250, 720 to 500),   // 250 by 10:00
            listOf(540 to 300, 590 to 300),   // 600
            listOf(700 to 500),               // 0
            emptyList(),                      // skipped
        )
        assertEquals(250, WaterMath.usualByNow(days, 600))
        assertEquals(600, WaterMath.usualByNow(days.take(3), 24 * 60 - 1))
        assertNull(WaterMath.usualByNow(days.take(2), 600))
    }

    @Test fun goalDaysCountsLoggedOnly() {
        assertEquals(2 to 3, WaterMath.goalDays(listOf(2500, null, 3000, 1200, 0), 2500))
    }

    @Test fun parseAmount() {
        assertEquals(330, WaterMath.parseAmount(" 330 "))
        assertNull(WaterMath.parseAmount("20")); assertNull(WaterMath.parseAmount("abc")); assertNull(WaterMath.parseAmount("2500"))
    }
}
