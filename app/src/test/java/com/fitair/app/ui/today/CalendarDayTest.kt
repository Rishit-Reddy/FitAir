package com.fitair.app.ui.today

import com.fitair.app.integrations.calendar.CalEvent
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class CalendarDayTest {
    private val z = ZoneId.of("UTC")
    private fun at(h: Int, m: Int = 0) = LocalDateTime.of(2026, 10, 3, h, m)
    private fun ev(h: Int, allDay: Boolean = false) = LocalDateTime.of(2026, 10, 3, h, 0).atZone(z).toInstant().let {
        CalEvent(h.toLong(), 1, "e", it, it.plusSeconds(3600), allDay, true, null)
    }

    @Test fun justAfterMidnightIsStillToday() = assertEquals(false, CalendarDay.showTomorrow(at(0, 42), emptyList(), z))
    @Test fun before21IsToday() = assertEquals(false, CalendarDay.showTomorrow(at(20, 59), emptyList(), z))
    @Test fun after21WithNothingLeftIsTomorrow() = assertEquals(true, CalendarDay.showTomorrow(at(21), listOf(ev(18)), z))
    @Test fun after21WithAnEventLeftStaysToday() = assertEquals(false, CalendarDay.showTomorrow(at(21, 30), listOf(ev(22)), z))
    @Test fun allDayEventsDoNotKeepTheDayOpen() = assertEquals(true, CalendarDay.showTomorrow(at(22), listOf(ev(0, allDay = true)), z))
}
