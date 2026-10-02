package com.fitair.app

import com.fitair.app.analytics.SessionCheck
import org.junit.Assert.*
import org.junit.Test

class SessionCheckTest {
    private val rest = 55.0; private val max = 190.0
    private fun bpm(h: Double) = rest + h * (max - rest)

    @Test fun scooterRideAtRestPlus8IsFlagged() {
        val r = SessionCheck.check(40.0, 8 /* biking */, List(80) { rest + 8 }, rest, max)
        assertTrue(r.suspicious)
        assertEquals(8.0 / 135.0, r.meanHrr!!, 1e-9)
        assertEquals(0.0, r.shareHigh!!, 0.0)
    }

    @Test fun realWorkoutIsNotFlagged() {
        assertFalse(SessionCheck.check(60.0, 77, List(120) { bpm(0.6) }, rest, max).suspicious)
        assertFalse(SessionCheck.check(60.0, 8, List(120) { bpm(0.6) }, rest, max).suspicious)   // real bike ride
    }

    @Test fun table() = listOf(
        // meanHrr, shareHigh, type, suspicious
        Triple(0.20, 0.05, 77) to true,
        Triple(0.20, 0.20, 77) to false,     // a few hard efforts: keep
        Triple(0.26, 0.00, 77) to false,     // not below 0.25
        Triple(0.26, 0.00, 8) to true,       // biking below 0.30
        Triple(0.26, 0.00, 0) to true,       // "other" below 0.30
        Triple(0.31, 0.00, 8) to false,
    ).forEach { (i, e) -> assertEquals("$i", e, SessionCheck.isSuspicious(i.first, i.second, i.third)) }

    @Test fun shortOrEmptySessionsAreNeverFlagged() {
        assertFalse(SessionCheck.check(9.0, 8, List(18) { rest + 3 }, rest, max).suspicious)
        assertFalse(SessionCheck.check(30.0, 8, emptyList(), rest, max).suspicious)
    }

    @Test fun userVerdictWins() {
        assertTrue(SessionCheck.excluded(SessionCheck.NOT_EXERCISE, autoFlagged = false))
        assertFalse(SessionCheck.excluded(SessionCheck.EXERCISE, autoFlagged = true))
        assertTrue(SessionCheck.excluded(null, autoFlagged = true))
        assertFalse(SessionCheck.excluded(null, autoFlagged = false))
    }

    @Test fun autoActionNeverTouchesUserRows() {
        assertEquals("keep", SessionCheck.autoAction(null, existingUser = true, suspicious = true))
        assertEquals("keep", SessionCheck.autoAction(null, existingUser = true, suspicious = false))
        assertEquals("set", SessionCheck.autoAction(null, existingUser = false, suspicious = true))
        assertEquals("clear", SessionCheck.autoAction(true, existingUser = false, suspicious = false))
        assertEquals("keep", SessionCheck.autoAction(null, existingUser = false, suspicious = false))
    }
}
