package com.fitair.app.ui.agenda

import com.fitair.app.core.Format
import com.fitair.app.integrations.calendar.AgendaItem
import com.fitair.app.integrations.calendar.CalEvent
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/** Dot colours for event rows: device calendars by calendar id, subscribed links by link id (a link event never falls back to a calendar colour). */
class AgendaColors(val cal: Map<Long, Int> = emptyMap(), val feed: Map<Long, Int> = emptyMap()) {
    fun of(e: CalEvent): Int? = if (e.feedId != null) feed[e.feedId] else cal[e.calId]
}

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

    /** The running or next timed event. [minutes] = until it starts, or (when [running]) until it ends. */
    class NextUp(val e: CalEvent, val running: Boolean, val minutes: Long)

    /** First timed event that has not ended yet (all-day events never lead). Null when the day is done. */
    fun nextUp(items: List<AgendaItem>, now: Instant): NextUp? {
        val e = items.asSequence().filterIsInstance<AgendaItem.Event>().map { it.e }
            .filter { !it.allDay && it.end > now }.minByOrNull { it.begin } ?: return null
        val running = e.begin <= now
        val target = if (running) e.end else e.begin
        return NextUp(e, running, Math.max(0L, (target.epochSecond - now.epochSecond + 59) / 60))
    }

    /** "in 40 min" / "in 1h 05m" / "ends in 20 min". */
    fun inText(min: Long, running: Boolean): String {
        val t = if (min < 60) "$min min" else Format.hm(min)
        return (if (running) "ends in " else "in ") + t
    }

    /** "14:30–15:15 · in 40 min · location". */
    fun nextUpLine(n: NextUp, z: ZoneId): String = listOfNotNull(range(n.e.begin, n.e.end, z), inText(n.minutes, n.running), if (n.e.work) "shift" else null, n.e.location).joinToString(" \u00B7 ")

    /** First timed event of a day (or the all-day ones when there is none), e.g. "Tomorrow 08:00 Shift start"; null if none. */
    fun tomorrowLine(events: List<CalEvent>, z: ZoneId): String? =
        events.filter { !it.allDay }.minByOrNull { it.begin }?.let { "Tomorrow ${clock(it.begin, z)} ${it.title}" }
            ?: AllDayLine.tomorrow(events)

    /** "First event 08:00" for the Tomorrow block. */
    fun firstEventLine(events: List<CalEvent>, z: ZoneId): String? =
        events.filter { !it.allDay }.minByOrNull { it.begin }?.let { "First event ${clock(it.begin, z)}" }
            ?: AllDayLine.tomorrow(events)

    /** Default start for a new event: the next half hour when [day] is today, else 09:00. */
    fun insertStart(day: LocalDate, now: LocalDateTime): LocalDateTime {
        if (day != now.toLocalDate()) return day.atTime(9, 0)
        val base = now.withSecond(0).withNano(0)
        val add = if (base.minute < 30) 30 - base.minute else 60 - base.minute
        return base.plusMinutes(add.toLong())
    }

    /** The seven days of [day]'s week, starting at [first]. */
    fun weekDays(day: LocalDate, first: DayOfWeek): List<LocalDate> {
        val back = ((day.dayOfWeek.value - first.value) + 7) % 7
        val start = day.minusDays(back.toLong())
        return (0..6).map { start.plusDays(it.toLong()) }
    }
}
