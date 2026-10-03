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
    @Test fun aRecordEndingInTheFutureNeverStartsTheReadAfterNow() {
        val wm = Instant.parse("2026-10-03T07:15:00Z").toEpochMilli()      // the calorie record of the current quarter hour
        val from = SyncWindow.from(wm, now, 365, ov)
        assertEquals(Instant.parse("2026-10-03T06:51:00Z"), from)
        assert(from < now)
    }
}
