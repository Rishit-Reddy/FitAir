package com.fitair.app.ui.agenda

import com.fitair.app.core.Format
import com.fitair.app.integrations.calendar.AgendaItem
import com.fitair.app.integrations.calendar.CalEvent
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/** Pure helpers for the agenda UI (no Android types, JVM-testable). */
object AgendaFormat {
    /** Rows shown on Today before "Show all". */
    const val COLLAPSED_ROWS = 4
    val WORK_END: LocalTime = LocalTime.of(22, 0)

    private fun clock(i: Instant, z: ZoneId): String = i.atZone(z).let { Format.clock(it.hour, it.minute) }

    fun range(from: Instant, to: Instant, z: ZoneId): String = "${clock(from, z)}–${clock(to, z)}"

    /** Time column text: "All day", or "09:00" for Today rows, or the full range. */
    fun eventTime(e: CalEvent, z: ZoneId, withEnd: Boolean = false): String = when {
        e.allDay -> "All day"
        withEnd -> range(e.begin, e.end, z)
        else -> clock(e.begin, z)
    }

    /** All-day events first, otherwise the given order (stable). Gaps keep their place. */
    fun order(items: List<AgendaItem>): List<AgendaItem> {
        val allDay = items.filter { it is AgendaItem.Event && it.e.allDay }
        return allDay + items.filterNot { it in allDay }
    }

    class Collapsed(val visible: List<AgendaItem>, val eventCount: Int, val hidden: Boolean)

    fun collapse(items: List<AgendaItem>, max: Int = COLLAPSED_ROWS): Collapsed {
        val o = order(items)
        return Collapsed(o.take(max), o.count { it is AgendaItem.Event }, o.size > max)
    }

    fun emptyLine(): String = "Nothing scheduled · free until ${Format.clock(WORK_END.hour, WORK_END.minute)}"

    fun hasEvents(items: List<AgendaItem>): Boolean = items.any { it is AgendaItem.Event }
}
