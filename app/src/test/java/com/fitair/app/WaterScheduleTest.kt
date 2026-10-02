package com.fitair.app

import com.fitair.app.data.dao.Pace
import com.fitair.app.notify.WaterSchedule
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class WaterScheduleTest {
    private val z = ZoneId.of("Europe/Stockholm")
    private val date = LocalDate.of(2026, 10, 1)
    private val day0 = date.atStartOfDay(z).toInstant().toEpochMilli()
    private val M = 60_000L
    private fun at(h: Int, m: Int = 0) = day0 + (h * 60L + m) * M
    private val w = WaterSchedule.window(date, z, at(7, 0), 7 * 60, 23 * 60)

    @Test fun windowIsWakePlus30UntilBedMinus60() {
        assertEquals(at(7, 30), w.startMs); assertEquals(at(22, 0), w.endMs)
    }

    @Test fun windowUsesUsualWakeWithoutSleepAndHandlesPastMidnightBedtime() {
        val n = WaterSchedule.window(date, z, null, 8 * 60, 30)   // usual bed 00:30
        assertEquals(at(8, 30), n.startMs); assertEquals(at(23, 30), n.endMs)
        // yesterday's wake is ignored
        assertEquals(at(7, 30), WaterSchedule.window(date, z, at(-5, 0), 7 * 60, 23 * 60).startMs)
    }

    @Test fun nothingBeforeWindowOrAfterIt() {
        assertEquals(w.startMs, WaterSchedule.next(at(6), w, null, null, 0, 2500, 90))
        assertNull(WaterSchedule.next(at(21, 40), w, at(20, 30), null, 800, 2500, 90))   // 20:30 + 90 min is after 22:00
        assertFalse(WaterSchedule.insideWindow(at(23), w)); assertFalse(WaterSchedule.insideWindow(at(7), w))
    }

    @Test fun loggedDrinkRestartsTheClock() {
        // logging at 14:00 pushes the next reminder to about 15:30
        assertEquals(at(15, 30), WaterSchedule.next(at(14), w, at(14), at(13, 0), 1500, 2500, 90))
    }

    @Test fun reminderAlsoCountsAsLastEvent() =
        assertEquals(at(12, 30), WaterSchedule.next(at(11), w, null, at(11, 0), 600, 2500, 90))

    @Test fun behindPaceUsesSixtyMinutes() {
        assertEquals(Pace.Behind, WaterSchedule.pace(0, 2500, at(15), w))        // expected ~1.3 L, drank 0
        assertEquals(at(16), WaterSchedule.next(at(15), w, at(15), null, 0, 2500, 90))
        assertEquals(Pace.OnPace, WaterSchedule.pace(1200, 2500, at(15), w))
    }

    @Test fun goalReachedStopsReminders() {
        assertEquals(Pace.Reached, WaterSchedule.pace(2500, 2500, at(12), w))
        assertNull(WaterSchedule.next(at(12), w, at(11), null, 2600, 2500, 90))
    }

    @Test fun missedAlarmFiresSoonNotInThePast() =
        assertEquals(at(13, 1), WaterSchedule.next(at(13), w, at(8), null, 250, 2500, 90))

    @Test fun enablingMidDayWaitsOneInterval() =
        assertEquals(at(15, 30), WaterSchedule.next(at(14), w, null, null, 0, 2500, 90).let { it?.coerceAtLeast(0) }.let { if (it == at(14) + 90 * M) at(15, 30) else it })

    @Test fun goalAndIntervalAreClamped() {
        assertEquals(1500, WaterSchedule.clampGoal(500)); assertEquals(4000, WaterSchedule.clampGoal(9000))
        assertEquals(45, WaterSchedule.clampInterval(10)); assertEquals(180, WaterSchedule.clampInterval(999))
        assertEquals(2500, WaterSchedule.goalFor(2500, 29)); assertEquals(3000, WaterSchedule.goalFor(2500, 30))
        assertEquals(4500, WaterSchedule.goalFor(4000, 45))
    }

    @Test fun textAfterTwoUnansweredNamesTheTime() {
        assertEquals("1.0 of 2.5 L so far", WaterSchedule.text(1000, 2500, 1, "11:00"))
        assertEquals("Nothing logged since 11:00. 1.0 of 2.5 L so far", WaterSchedule.text(1000, 2500, 2, "11:00"))
    }
}
