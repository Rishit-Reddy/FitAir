package com.fitair.app.ui.agenda

import com.fitair.app.integrations.calendar.CalEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class TimelineLayoutTest {
    private val base = Instant.parse("2026-10-03T08:00:00Z")
    private fun ev(startMin: Long, lenMin: Long, allDay: Boolean = false) =
        CalEvent(startMin, 1, "e$startMin", base.plusSeconds(startMin * 60), base.plusSeconds((startMin + lenMin) * 60), allDay, true, null)

    @Test fun separateEventsUseOneColumn() {
        val p = TimelineLayout.place(listOf(ev(0, 60), ev(90, 60)))
        assertTrue(p.all { it.col == 0 && it.cols == 1 })
    }

    @Test fun overlappingEventsSitSideBySide() {
        val p = TimelineLayout.place(listOf(ev(0, 90), ev(30, 60)))
        assertEquals(listOf(0, 1), p.map { it.col }); assertTrue(p.all { it.cols == 2 })
    }

    @Test fun aFreedColumnIsReused() {
        val p = TimelineLayout.place(listOf(ev(0, 120), ev(10, 30), ev(60, 30)))
        assertEquals(listOf(0, 1, 1), p.map { it.col }); assertTrue(p.all { it.cols == 2 })
    }

    @Test fun shortEventsCountAsThirtyMinutesForOverlap() {
        val p = TimelineLayout.place(listOf(ev(0, 5), ev(10, 20)))
        assertTrue(p.all { it.cols == 2 })
    }

    @Test fun allDayEventsAreLeftOut() = assertTrue(TimelineLayout.place(listOf(ev(0, 1440, allDay = true))).isEmpty())
}
