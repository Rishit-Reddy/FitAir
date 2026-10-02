package com.fitair.app

import com.fitair.app.ui.copy.Copy
import com.fitair.app.ui.copy.Copy.ShiftEffort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class CalendarLinksCopyTest {
    @Test fun effortThresholds() {
        assertEquals(ShiftEffort.Easy, Copy.shiftEffort(0))
        assertEquals(ShiftEffort.Easy, Copy.shiftEffort(39))
        assertEquals(ShiftEffort.Steady, Copy.shiftEffort(40))
        assertEquals(ShiftEffort.Steady, Copy.shiftEffort(59))
        assertEquals(ShiftEffort.Hard, Copy.shiftEffort(60))
        assertEquals(ShiftEffort.Hard, Copy.shiftEffort(100))
    }

    @Test fun todayLine() {
        assertEquals("Shift 10:00–15:00 · hard (avg 71% of your range)", Copy.shiftTodayLine("10:00–15:00", 71))
        assertNull(Copy.shiftTodayLine("10:00–15:00", null))
    }

    @Test fun loadLine() {
        val r = "10:00–15:00"
        assertEquals("Shift $r · avg 128 bpm · 2 h 10 in the harder zones", Copy.shiftLoadLine(r, 128, 130, 0.9))
        assertEquals("Shift $r · avg 90 bpm · no time in the harder zones", Copy.shiftLoadLine(r, 90, 0, null))
        assertNull(Copy.shiftLoadLine(r, null, 30, 0.9))
        assertNull(Copy.shiftLoadLine(r, 120, 30, 0.1))
    }

    @Test fun hoursMinutes() {
        assertEquals("45 min", Copy.hoursMinutes(45))
        assertEquals("2 h", Copy.hoursMinutes(120))
        assertEquals("1 h 5", Copy.hoursMinutes(65))
    }

    @Test fun updatedAgo() {
        val now = 10_000_000_000L
        assertNull(Copy.updatedAgo(null, now))
        assertEquals("just now", Copy.updatedAgo(now - 20_000, now))
        assertEquals("12 min ago", Copy.updatedAgo(now - 12 * 60_000L, now))
        assertEquals("3 h ago", Copy.updatedAgo(now - 3 * 3_600_000L - 5, now))
        assertEquals("1 day ago", Copy.updatedAgo(now - 25 * 3_600_000L, now))
        assertEquals("3 days ago", Copy.updatedAgo(now - 72 * 3_600_000L, now))
    }

    @Test fun feedStatusNeverShowsUrl() {
        val now = 10_000_000_000L
        assertEquals("23 events · updated 12 min ago", Copy.feedStatus(23, now - 12 * 60_000L, null, now))
        assertEquals("1 event · updated just now", Copy.feedStatus(1, now, null, now))
        assertEquals("Not fetched yet", Copy.feedStatus(0, null, null, now))
        val s = Copy.feedStatus(5, now - 3_600_000L, "Could not reach https://calendar.google.com/calendar/ical/x%40y/private-abc/basic.ics", now)
        assertFalse(s.contains("https"))
        assertFalse(s.contains("abc"))
        assertTrue(s.contains("last worked 1 h ago"))
        assertEquals("Could not update: the link", Copy.feedStatus(0, null, "webcal://host/secret.ics", now))
    }

    @Test fun preview() {
        val z = ZoneId.of("UTC")
        val mon = Instant.parse("2026-10-05T10:00:00Z") // a Monday
        assertEquals("Mon 10:00", Copy.dayClock(mon, z))
        assertEquals("Found 23 events · next: Delivery Mon 10:00", Copy.previewLine(23, "Delivery", "Mon 10:00", null))
        assertEquals("Found 1 event · next: Busy Mon 10:00", Copy.previewLine(1, " ", "Mon 10:00", null))
        assertEquals("Found 4 events", Copy.previewLine(4, null, null, null))
        assertEquals("The link works, but it has no events yet.", Copy.previewLine(0, null, null, null))
        assertEquals("That link needs a sign-in.", Copy.previewLine(0, null, null, "That link needs a sign-in."))
        assertTrue(Copy.previewLine(2, "A".repeat(60), "Tue 09:00", null).contains("…"))
    }

    @Test fun linkInput() {
        assertEquals("https://x.com/a.ics", Copy.cleanLinkInput("  webcal://x.com/a.ics\n"))
        assertEquals("https://x.com/a.ics", Copy.cleanLinkInput("\"https://x.com/ a.ics\""))
        assertEquals("", Copy.cleanLinkInput("   "))
        assertNull(Copy.linkInputProblem(""))
        assertNull(Copy.linkInputProblem("https://calendar.google.com/x.ics"))
        assertTrue(Copy.linkInputProblem("http://x.com/a.ics")!!.contains("https://"))
        assertTrue(Copy.linkInputProblem("hello")!!.contains("calendar link"))
    }
}
