package com.fitair.app.ui.agenda

import com.fitair.app.integrations.calendar.AgendaItem
import com.fitair.app.integrations.calendar.CalEvent

/** All-day events as one line: "All day: Birthday, Holiday". Next up and the tomorrow line only look at timed events (issue #15). */
object AllDayLine {
    private const val MAX_TITLES = 3

    private fun titles(events: List<CalEvent>): List<String> =
        events.filter { it.allDay }.map { it.title.ifBlank { "Busy" } }.distinct()

    private fun join(t: List<String>): String =
        if (t.size <= MAX_TITLES) t.joinToString(", ") else t.take(MAX_TITLES).joinToString(", ") + " +${t.size - MAX_TITLES}"

    /** Today's all-day events, or null when there are none. */
    fun today(items: List<AgendaItem>): String? =
        titles(items.filterIsInstance<AgendaItem.Event>().map { it.e }).takeIf { it.isNotEmpty() }?.let { "All day: ${join(it)}" }

    /** Tomorrow's all-day events, or null when there are none. */
    fun tomorrow(events: List<CalEvent>): String? =
        titles(events).takeIf { it.isNotEmpty() }?.let { "Tomorrow, all day: ${join(it)}" }
}
