package com.fitair.app.integrations.calendar

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

/** [syncing] = VISIBLE and SYNC_EVENTS on the phone. A subscribed (ICS) calendar that is not syncing has no events here. */
data class CalendarInfo(val id: Long, val name: String, val account: String, val color: Int, val syncing: Boolean = true)

data class CalEvent(
    val instanceId: Long, val calId: Long, val title: String, val begin: Instant, val end: Instant,
    val allDay: Boolean, val busy: Boolean, val location: String?,
    /** True for events of a subscribed feed flagged as work (shifts). */
    val work: Boolean = false,
    /** Set for events from a subscribed ICS feed (calId is then negative and unique per feed). */
    val feedId: Long? = null,
)

sealed interface AgendaItem {
    data class Event(val e: CalEvent) : AgendaItem
    data class Gap(val from: Instant, val to: Instant) : AgendaItem
}

/**
 * All-day events are stored as UTC midnight [beginUtcMs, endUtcMs) (end exclusive). Returns the local-zone instants
 * of the first and (exclusive) last local midnights of the same calendar dates, DST-safe.
 */
internal fun allDayToLocal(beginUtcMs: Long, endUtcMs: Long, zone: ZoneId): Pair<Instant, Instant> {
    val d0 = Instant.ofEpochMilli(beginUtcMs).atZone(ZoneOffset.UTC).toLocalDate()
    var d1 = Instant.ofEpochMilli(endUtcMs).atZone(ZoneOffset.UTC).toLocalDate()
    if (!d1.isAfter(d0)) d1 = d0.plusDays(1)
    return d0.atStartOfDay(zone).toInstant() to d1.atStartOfDay(zone).toInstant()
}

/** Pure agenda builder: all-day events first, then timed events and free gaps in chronological order. */
fun buildAgenda(
    events: List<CalEvent>, now: Instant, dayStart: Instant, dayEnd: Instant, minGapMin: Int = 45,
    workWindow: Pair<LocalTime, LocalTime> = LocalTime.of(7, 0) to LocalTime.of(22, 0), zone: ZoneId,
): List<AgendaItem> {
    val allDay = events.filter { it.allDay }.sortedWith(compareBy({ it.begin }, { it.title }))
    val timed = events.filter { !it.allDay }.sortedWith(compareBy({ it.begin }, { it.end }))
    val live = timed.filter { it.end > now || (it.end == it.begin && it.begin >= now) }

    val date: LocalDate = dayStart.atZone(zone).toLocalDate()
    val winStart = date.atTime(workWindow.first).atZone(zone).toInstant()
    val winEnd = date.atTime(workWindow.second).atZone(zone).toInstant()
    val lower = maxOf(winStart, now, dayStart)
    val upper = minOf(winEnd, dayEnd)

    // merge busy intervals
    val merged = ArrayList<Pair<Instant, Instant>>()
    for (e in timed) {
        if (!e.busy || e.end <= e.begin) continue
        val last = merged.lastOrNull()
        if (last != null && e.begin <= last.second) {
            if (e.end > last.second) merged[merged.size - 1] = last.first to e.end
        } else merged.add(e.begin to e.end)
    }
    val gaps = ArrayList<AgendaItem.Gap>()
    val minSec = minGapMin * 60L
    fun add(a: Instant, b: Instant) {
        if (b.epochSecond - a.epochSecond >= minSec) gaps.add(AgendaItem.Gap(a, b))
    }
    if (lower < upper) {
        var cur = lower
        for ((s, e) in merged) {
            if (e <= cur) continue
            if (s >= upper) break
            if (s > cur) add(cur, s)
            cur = maxOf(cur, e)
        }
        if (cur < upper) add(cur, upper)
    }

    val out = ArrayList<AgendaItem>()
    allDay.forEach { out.add(AgendaItem.Event(it)) }
    val rest = live.map { it.begin to (AgendaItem.Event(it) as AgendaItem) } + gaps.map { it.from to (it as AgendaItem) }
    rest.sortedBy { it.first }.forEach { out.add(it.second) }
    return out
}
