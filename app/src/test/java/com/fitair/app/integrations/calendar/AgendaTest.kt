package com.fitair.app.integrations.calendar

import org.junit.Assert.*
import org.junit.Test
import java.time.*

class AgendaTest {
    private val z = ZoneId.of("Europe/Stockholm")
    private fun at(d: String, h: Int, m: Int = 0) = LocalDate.parse(d).atTime(h, m).atZone(z).toInstant()
    private fun ev(id: Long, d: String, h1: Int, h2: Int, busy: Boolean = true, allDay: Boolean = false, m1: Int = 0, m2: Int = 0) =
        CalEvent(id, 1, "e$id", at(d, h1, m1), at(d, h2, m2), allDay, busy, null)
    private fun build(day: String, evs: List<CalEvent>, now: Instant, min: Int = 45): List<AgendaItem> {
        val ds = LocalDate.parse(day).atStartOfDay(z).toInstant()
        return buildAgenda(evs, now, ds, LocalDate.parse(day).plusDays(1).atStartOfDay(z).toInstant(), min, zone = z)
    }
    private fun gaps(l: List<AgendaItem>) = l.filterIsInstance<AgendaItem.Gap>()

    @Test fun emptyDayIsOneBigGap() {
        val r = build("2026-10-05", emptyList(), at("2026-10-05", 0))
        assertEquals(listOf(AgendaItem.Gap(at("2026-10-05", 7), at("2026-10-05", 22))), r)
    }

    @Test fun gapsBetweenEvents() {
        val r = build("2026-10-05", listOf(ev(1, "2026-10-05", 9, 10), ev(2, "2026-10-05", 10, 11, m1 = 20, m2 = 0)), at("2026-10-05", 6))
        assertEquals(listOf(at("2026-10-05", 7) to at("2026-10-05", 9), at("2026-10-05", 11) to at("2026-10-05", 22)),
            gaps(r).map { it.from to it.to })
        // 20-minute gap between events is dropped
        assertFalse(gaps(r).any { it.from == at("2026-10-05", 10) })
    }

    @Test fun overlapMerged() {
        val r = build("2026-10-05", listOf(ev(1, "2026-10-05", 9, 12), ev(2, "2026-10-05", 10, 11)), at("2026-10-05", 6))
        assertEquals(2, gaps(r).size)
        assertEquals(at("2026-10-05", 12), gaps(r)[1].from)
    }

    @Test fun freeEventDoesNotBlock() {
        val r = build("2026-10-05", listOf(ev(1, "2026-10-05", 9, 12, busy = false)), at("2026-10-05", 6))
        assertEquals(1, gaps(r).size)
        assertEquals(2, r.size)
    }

    @Test fun finishedDroppedOngoingKept() {
        val r = build("2026-10-05", listOf(ev(1, "2026-10-05", 8, 9), ev(2, "2026-10-05", 10, 12)), at("2026-10-05", 11))
        val events = r.filterIsInstance<AgendaItem.Event>().map { it.e.instanceId }
        assertEquals(listOf(2L), events)
        assertEquals(listOf(at("2026-10-05", 12) to at("2026-10-05", 22)), gaps(r).map { it.from to it.to })
    }

    @Test fun allDayFirstAndNoGapEffect() {
        val ad = CalEvent(9, 1, "holiday", at("2026-10-05", 0), at("2026-10-06", 0), true, true, null)
        val r = build("2026-10-05", listOf(ev(1, "2026-10-05", 9, 10), ad), at("2026-10-05", 6))
        assertTrue((r[0] as AgendaItem.Event).e.allDay)
        assertEquals(2, gaps(r).size)
    }

    @Test fun dstFallBackDay() {
        // 2026-10-25 has 25 hours; work window is still 07:00-22:00 local (15 h) so an empty day gives a 900 min gap
        val r = build("2026-10-25", emptyList(), at("2026-10-25", 0))
        val g = gaps(r).single()
        assertEquals(15 * 60L, Duration.between(g.from, g.to).toMinutes())
        assertEquals(at("2026-10-25", 7), g.from)
    }

    @Test fun allDayConversion() {
        val b = LocalDate.of(2026, 10, 25).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val e = LocalDate.of(2026, 10, 26).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val (s, t) = allDayToLocal(b, e, z)
        assertEquals(LocalDate.of(2026, 10, 25).atStartOfDay(z).toInstant(), s)
        assertEquals(LocalDate.of(2026, 10, 26).atStartOfDay(z).toInstant(), t)
        assertEquals(25L, Duration.between(s, t).toHours())
        // west-of-UTC zone must not slip to the previous date
        val ny = ZoneId.of("America/New_York")
        assertEquals(LocalDate.of(2026, 10, 25), allDayToLocal(b, e, ny).first.atZone(ny).toLocalDate())
        // zero/negative length end is treated as one day
        assertEquals(LocalDate.of(2026, 10, 26).atStartOfDay(z).toInstant(), allDayToLocal(b, b, z).second)
    }
}
