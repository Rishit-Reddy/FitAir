package com.fitair.app

import com.fitair.app.integrations.calendar.AgendaItem
import com.fitair.app.integrations.calendar.CalEvent
import com.fitair.app.ui.agenda.AgendaFormat
import org.junit.Assert.*
import org.junit.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class AgendaNextUpTest {
    private val z: ZoneId = ZoneId.of("UTC")
    private fun t(h: Int, m: Int = 0) = LocalDateTime.of(2026, 10, 2, h, m).atZone(z).toInstant()
    private fun ev(title: String, b: Instant, e: Instant, allDay: Boolean = false, loc: String? = null) =
        AgendaItem.Event(CalEvent(1, 1, title, b, e, allDay, true, loc))

    @Test fun nextUpIsTheFirstEventNotYetEnded() {
        val items = listOf(ev("Holiday", t(0), t(23), allDay = true), ev("Shift", t(14, 30), t(15, 15), loc = "Depot"), ev("Call", t(16), t(16, 30)))
        val n = AgendaFormat.nextUp(items, t(13, 50))!!
        assertEquals("Shift", n.e.title); assertFalse(n.running); assertEquals(40L, n.minutes)
        assertEquals("14:30–15:15 · in 40 min · Depot", AgendaFormat.nextUpLine(n, z))
    }

    @Test fun runningEventShowsNowAndTimeLeft() {
        val n = AgendaFormat.nextUp(listOf(ev("Shift", t(14, 30), t(15, 15))), t(14, 55))!!
        assertTrue(n.running); assertEquals(20L, n.minutes)
        assertEquals("14:30–15:15 · ends in 20 min", AgendaFormat.nextUpLine(n, z))
    }

    @Test fun nothingLeftGivesNullAndTomorrowLine() {
        assertNull(AgendaFormat.nextUp(listOf(ev("Old", t(9), t(10))), t(12)))
        assertNull(AgendaFormat.nextUp(emptyList(), t(12)))
        val tomorrow = listOf(ev("Shift start", t(8).plusSeconds(86400), t(9).plusSeconds(86400)).e)
        assertEquals("Tomorrow 08:00 Shift start", AgendaFormat.tomorrowLine(tomorrow, z))
        assertEquals("First event 08:00", AgendaFormat.firstEventLine(tomorrow, z))
        assertNull(AgendaFormat.tomorrowLine(emptyList(), z))
    }

    @Test fun inText() {
        assertEquals("in 5 min", AgendaFormat.inText(5, false)); assertEquals("in 1h 05m", AgendaFormat.inText(65, false))
        assertEquals("ends in 59 min", AgendaFormat.inText(59, true))
    }

    @Test fun insertStartIsNextHalfHour() {
        val today = LocalDate.of(2026, 10, 2)
        assertEquals(LocalDateTime.of(2026, 10, 2, 14, 30), AgendaFormat.insertStart(today, LocalDateTime.of(2026, 10, 2, 14, 10)))
        assertEquals(LocalDateTime.of(2026, 10, 2, 15, 0), AgendaFormat.insertStart(today, LocalDateTime.of(2026, 10, 2, 14, 30)))
        assertEquals(LocalDateTime.of(2026, 10, 3, 9, 0), AgendaFormat.insertStart(today.plusDays(1), LocalDateTime.of(2026, 10, 2, 14, 10)))
    }

    @Test fun weekStripStartsOnTheLocaleFirstDay() {
        val d = LocalDate.of(2026, 10, 2) // a Friday
        assertEquals(LocalDate.of(2026, 9, 28), AgendaFormat.weekDays(d, DayOfWeek.MONDAY).first())
        assertEquals(LocalDate.of(2026, 9, 27), AgendaFormat.weekDays(d, DayOfWeek.SUNDAY).first())
        assertEquals(7, AgendaFormat.weekDays(d, DayOfWeek.MONDAY).size)
        assertTrue(d in AgendaFormat.weekDays(d, DayOfWeek.MONDAY))
    }
}
