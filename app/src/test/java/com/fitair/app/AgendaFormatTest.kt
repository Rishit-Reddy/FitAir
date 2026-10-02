package com.fitair.app

import com.fitair.app.integrations.calendar.AgendaItem
import com.fitair.app.integrations.calendar.CalEvent
import com.fitair.app.ui.agenda.AgendaFormat
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset

class AgendaFormatTest {
    private val z = ZoneOffset.UTC
    private fun at(h: Int, m: Int = 0) = Instant.parse("2026-10-02T00:00:00Z").plusSeconds(h * 3600L + m * 60L)
    private fun ev(id: Long, h: Int, allDay: Boolean = false) =
        AgendaItem.Event(CalEvent(id, 1, "e$id", at(h), at(h + 1), allDay, true, null))

    @Test fun ranges() {
        assertEquals("14:00–16:30", AgendaFormat.range(at(14), at(16, 30), z))
        assertEquals("All day", AgendaFormat.eventTime(ev(1, 0, true).e, z))
        assertEquals("09:00", AgendaFormat.eventTime(ev(1, 9).e, z))
        assertEquals("09:00–10:00", AgendaFormat.eventTime(ev(1, 9).e, z, withEnd = true))
    }

    @Test fun allDayFirst() {
        val o = AgendaFormat.order(listOf(ev(1, 9), ev(2, 0, true), ev(3, 11)))
        assertEquals(listOf(2L, 1L, 3L), o.map { (it as AgendaItem.Event).e.instanceId })
    }

    @Test fun collapseToFour() {
        val items = listOf(ev(1, 8), AgendaItem.Gap(at(9), at(10)), ev(2, 10), ev(3, 11), ev(4, 12), ev(5, 13))
        val c = AgendaFormat.collapse(items)
        assertEquals(4, c.visible.size); assertTrue(c.hidden); assertEquals(5, c.eventCount)
        assertFalse(AgendaFormat.collapse(items.take(4)).hidden)
    }

    @Test fun empty() {
        assertFalse(AgendaFormat.hasEvents(listOf(AgendaItem.Gap(at(9), at(10)))))
        assertEquals("Nothing scheduled · free until 22:00", AgendaFormat.emptyLine())
    }
}
