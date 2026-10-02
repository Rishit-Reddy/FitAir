package com.fitair.app

import com.fitair.app.analytics.WakeLogic
import com.fitair.app.analytics.WakeLogic.Sess
import org.junit.Assert.*
import org.junit.Test

class WakeTest {
    private val H = 3_600_000L
    private val now = 1_000 * H   // arbitrary "now"
    private fun s(startH: Double, endH: Double) = Sess(now + (startH * H).toLong(), now + (endH * H).toLong())

    @Test fun napIsIgnored() {
        val main = s(-10.0, -3.0)                      // 7 h night ending 3 h ago
        assertEquals(main.end, WakeLogic.wake(listOf(main, s(-1.5, -0.8)), now))   // 42 min nap
        assertNull(WakeLogic.wake(listOf(s(-1.5, -0.5)), now))                      // only a nap: no wake yet
    }

    @Test fun splitNightUsesTheLaterLongSession() {
        val first = s(-9.0, -5.0); val back = s(-4.5, -2.5)   // 4 h, then back to bed for 2 h
        assertEquals(back.end, WakeLogic.wake(listOf(first, back), now))
    }

    @Test fun noSessionYet() {
        assertNull(WakeLogic.wake(emptyList(), now))
        assertNull(WakeLogic.wake(listOf(s(-30.0, -22.0)), now))      // ended more than 18 h ago
        assertNull(WakeLogic.wake(listOf(s(1.0, 9.0)), now))          // in the future
    }

    @Test fun exactlyNinetyMinutesCounts() =
        assertNotNull(WakeLogic.wake(listOf(s(-5.0, -3.5)), now))

    @Test fun longestWinsWhenNothingEndsLater() {
        val a = s(-12.0, -4.0); val b = s(-11.0, -9.0)
        assertEquals(a.end, WakeLogic.wake(listOf(b, a), now))
    }

    @Test fun usualTimesAreMediansWithDefaults() {
        assertEquals(WakeLogic.DEFAULT_WAKE_MIN, WakeLogic.medianMinutes(listOf(400, 410), WakeLogic.DEFAULT_WAKE_MIN))   // < 5 nights
        assertEquals(420, WakeLogic.medianMinutes(listOf(400, 410, 420, 430, 440), 0))
        // bed times around midnight: 23:30, 00:10, 23:50, 00:30, 23:40 -> 23:50
        assertEquals(23 * 60 + 50, WakeLogic.medianMinutes(listOf(1410, 10, 1430, 30, 1420), 0, wrapNoon = true))
    }
}
