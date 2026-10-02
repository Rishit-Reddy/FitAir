package com.fitair.app.ui.agenda

import com.fitair.app.integrations.calendar.AgendaItem
import com.fitair.app.integrations.calendar.CalEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class AllDayLineTest {
    private val t = Instant.parse("2026-10-03T00:00:00Z")
    private fun ev(title: String, allDay: Boolean) = CalEvent(1, 1, title, t, t.plusSeconds(if (allDay) 86_400 else 3_600), allDay, true, null)

    @Test fun todayListsAllDayTitles() =
        assertEquals("All day: Birthday, Holiday", AllDayLine.today(listOf(ev("Birthday", true), ev("Holiday", true), ev("Meeting", false)).map { AgendaItem.Event(it) }))

    @Test fun todayIsNullWithoutAllDay() = assertNull(AllDayLine.today(listOf(AgendaItem.Event(ev("Meeting", false)))))

    @Test fun longListsAreShortened() =
        assertEquals("All day: A, B, C +1", AllDayLine.today(listOf("A", "B", "C", "D").map { AgendaItem.Event(ev(it, true)) }))

    @Test fun tomorrowLineFallsBackToAllDay() =
        assertEquals("Tomorrow, all day: Conference", AgendaFormat.tomorrowLine(listOf(ev("Conference", true)), ZoneId.of("UTC")))

    @Test fun tomorrowLinePrefersTimedEvents() =
        assertEquals("Tomorrow 00:00 Shift", AgendaFormat.tomorrowLine(listOf(ev("Conference", true), ev("Shift", false)), ZoneId.of("UTC")))

    @Test fun tomorrowLineEmptyDayIsNull() = assertNull(AgendaFormat.tomorrowLine(emptyList(), ZoneId.of("UTC")))
}
