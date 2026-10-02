package com.fitair.app.ui.today

import com.fitair.app.integrations.calendar.CalEvent
import com.fitair.app.ui.components.CardData
import java.time.Instant
import java.time.ZoneId

/** Pure one-liners for the Today snippets (no Android types). */
object TodayLines {
    private const val MAX_FIRST_TITLES = 1

    /** "4 tasks today · Dockerize the project +3" from today's all-day events; null when there are none. */
    fun tasks(allDay: List<CalEvent>): String? {
        val titles = allDay.filter { it.allDay }.map { it.title.ifBlank { "Busy" } }.distinct()
        if (titles.isEmpty()) return null
        val head = titles.take(MAX_FIRST_TITLES).joinToString(", ")
        val more = titles.size - MAX_FIRST_TITLES
        return "${titles.size} task${if (titles.size == 1) "" else "s"} today · $head" + if (more > 0) " +$more" else ""
    }

    /** "Tomorrow: first 10:00 · 2 events · 3 tasks"; null when tomorrow is empty. */
    fun tomorrow(events: List<CalEvent>, z: ZoneId): String? {
        val timed = events.filter { !it.allDay }
        val tasks = events.count { it.allDay }
        if (timed.isEmpty() && tasks == 0) return null
        val first = timed.minByOrNull { it.begin }?.let { e -> e.begin.atZone(z).let { "first %02d:%02d".format(it.hour, it.minute) } }
        val ev = timed.takeIf { it.isNotEmpty() }?.let { "${it.size} event${if (it.size == 1) "" else "s"}" }
        val tk = tasks.takeIf { it > 0 }?.let { "$it task${if (it == 1) "" else "s"}" }
        return "Tomorrow: " + listOfNotNull(first, ev, tk).joinToString(" · ")
    }

    /** One sentence for the top of Today from the Readiness card (day) or the Bedtime card (evening). */
    fun verdict(evening: Boolean, readiness: CardData?, bedtime: CardData?): String? =
        if (evening) bedtime?.takeIf { it.value != null }?.let { b ->
            listOfNotNull("Bed by ${b.value}", b.sub, b.chip?.text).joinToString(" · ")
        } else readiness?.takeIf { it.value != null }?.let { r ->
            listOfNotNull("Readiness ${r.value}", r.chip?.text).joinToString(" · ")
        }
}

/** Pure geometry of the 00-24 day strip: minutes of the day clamped to 0..1440. */
object StripMath {
    fun minute(ms: Long, dayStartMs: Long): Float = ((ms - dayStartMs) / 60_000f).coerceIn(0f, 1440f)
    fun frac(ms: Long, dayStartMs: Long): Float = minute(ms, dayStartMs) / 1440f
    /** Shift block shade: 35 % at rest up to 100 % at 60 %+ of heart-rate reserve; 40 % when unknown. */
    fun shade(avgPctHrr: Int?): Float = if (avgPctHrr == null) 0.4f else (0.35f + 0.65f * (avgPctHrr / 60f)).coerceIn(0.35f, 1f)
    fun dayStartMs(day: java.time.LocalDate, z: ZoneId): Long = day.atStartOfDay(z).toInstant().toEpochMilli()
    @Suppress("unused") fun instant(ms: Long): Instant = Instant.ofEpochMilli(ms)
}
