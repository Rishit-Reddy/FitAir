package com.fitair.app

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Duration
import java.time.Instant

class SyncWindowTest {
    private val now = Instant.parse("2026-10-03T07:01:00Z")
    private val ov = Duration.ofMinutes(10)

    @Test fun firstSyncReadsTheDefaultWindow() = assertEquals(now.minus(Duration.ofDays(365)), SyncWindow.from(null, now, 365, ov))
    @Test fun laterSyncStartsOverlapBeforeTheNewestRow() {
        val wm = Instant.parse("2026-10-03T06:30:00Z").toEpochMilli()
        assertEquals(Instant.parse("2026-10-03T06:20:00Z"), SyncWindow.from(wm, now, 365, ov))
    }
    @Test fun sessionsAreReReadForTwoDays() {
        val wm = Instant.parse("2026-10-03T06:27:00Z").toEpochMilli()
        assertEquals(Instant.parse("2026-10-01T07:01:00Z"), SyncWindow.sessionFrom(wm, now, 365, ov))
        assertEquals(now.minus(Duration.ofDays(365)), SyncWindow.sessionFrom(null, now, 365, ov))
    }

    @Test fun replacedSessionsAreOrphansButOnlyInsideTheWindow() {
        val a = 2_000L to "fit"; val b = 1_000L to "fit"; val c = 9_000L to "fit"
        assertEquals(listOf(a), SessionPrune.orphans(listOf(a, b, c), setOf(c), 1_500L))
        assertEquals(emptyList<Pair<Long, String>>(), SessionPrune.orphans(listOf(a, b), emptySet(), 0L))   // nothing returned: never wipe
    }

    @Test fun aRecordEndingInTheFutureNeverStartsTheReadAfterNow() {
        val wm = Instant.parse("2026-10-03T07:15:00Z").toEpochMilli()      // the calorie record of the current quarter hour
        val from = SyncWindow.from(wm, now, 365, ov)
        assertEquals(Instant.parse("2026-10-03T06:51:00Z"), from)
        assert(from < now)
    }
}
