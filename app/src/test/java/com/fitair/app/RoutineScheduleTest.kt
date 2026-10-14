package com.fitair.app

import com.fitair.app.notify.RoutineSchedule as R
import org.junit.Assert.*
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId

class RoutineScheduleTest {
    private val z = ZoneId.of("Europe/Stockholm")
    private fun ms(d: LocalDate, h: Int, m: Int = 0) = d.atTime(h, m).atZone(z).toInstant().toEpochMilli()
    private val day = LocalDate.of(2026, 11, 4)
    private val open = R.SlotState(false, false, null)
    private val done = R.SlotState(true, false, null)

    private fun next(nowH: Int, nowM: Int = 0, am: R.SlotState = open, pm: R.SlotState = open, detected: Int? = null, amDone: Int? = null, bed: Int = 23 * 60) =
        R.next(ms(day, nowH, nowM), z, 7 * 60, bed, detected, R.DEFAULT_PM_MIN, am, pm, amDone)

    @Test fun morningFromUsualOrDetectedWakeWithClamps() {
        assertEquals(7 * 60 + 30, R.amMinute(7 * 60, null))
        assertEquals(8 * 60 + 30, R.amMinute(7 * 60, 8 * 60))
        assertEquals(5 * 60, R.amMinute(3 * 60, null))
        assertEquals(12 * 60, R.amMinute(13 * 60, 14 * 60))
    }

    @Test fun nextIsMorningThenEveningThenTomorrow() {
        val a = next(6); assertEquals(R.AM, a.slot); assertEquals(ms(day, 7, 30), a.atMs)
        val p = next(10, am = done); assertEquals(R.PM, p.slot); assertEquals(ms(day, 19), p.atMs)
        val t = next(20, am = done, pm = done); assertEquals(R.AM, t.slot); assertEquals(ms(day.plusDays(1), 7, 30), t.atMs)
    }

    @Test fun overdueFiresInOneMinuteButAlertedWaitsSilently() {
        assertEquals(ms(day, 9, 1), next(9).atMs)
        val afterAlert = next(9, am = R.SlotState(false, true, null))
        assertEquals(R.PM, afterAlert.slot)
    }

    @Test fun snoozeIsHonouredAndPastCutoffIsLeftAlone() {
        val s = next(8, am = R.SlotState(false, true, ms(day, 8, 15)))
        assertEquals(R.AM, s.slot); assertEquals(ms(day, 8, 15), s.atMs)
        assertEquals(R.PM, next(13, 30).slot)
    }

    @Test fun lateMorningDosePushesEveningOrDropsIt() {
        assertEquals(19 * 60 + 5, R.pmMinute(19 * 60, 23 * 60, 13 * 60 + 5))
        assertEquals(19 * 60, R.pmMinute(19 * 60, 23 * 60, 12 * 60 + 59))
        assertEquals(20 * 60 + 30, R.pmMinute(19 * 60, 23 * 60, 14 * 60 + 30))
        assertNull(R.pmMinute(19 * 60, 23 * 60, 17 * 60))
        assertEquals(19 * 60, R.pmMinute(19 * 60, 23 * 60, 9 * 60))
    }

    @Test fun bedAfterMidnightCountsPastTwentyFour() {
        assertEquals(24 * 60 + 90 - 30, R.pmCutoffMin(90))
        assertEquals(22 * 60 + 30, R.pmCutoffMin(23 * 60))
    }

    @Test fun ketoMaskByWeekday() {
        assertTrue(R.ketoToday(DayOfWeek.MONDAY, R.DEFAULT_KETO_MASK)); assertTrue(R.ketoToday(DayOfWeek.FRIDAY, R.DEFAULT_KETO_MASK))
        assertFalse(R.ketoToday(DayOfWeek.TUESDAY, R.DEFAULT_KETO_MASK)); assertFalse(R.ketoToday(DayOfWeek.SUNDAY, R.DEFAULT_KETO_MASK))
    }

    @Test fun shownStatusAndText() {
        assertEquals("done", R.shown("done", false)); assertEquals("not logged", R.shown(null, true)); assertNull(R.shown(null, false))
        assertTrue(R.text(R.AM, true).startsWith("Shampoo first")); assertEquals("Minoxidil, evening", R.text(R.PM, false))
    }

    @Test fun daylightSavingDayKeepsClockTimes() {
        val dst = LocalDate.of(2026, 10, 25) // clocks go back in Sweden
        val due = R.next(ms(dst, 6), z, 7 * 60, 23 * 60, null, R.DEFAULT_PM_MIN, open, open, null)
        assertEquals(ms(dst, 7, 30), due.atMs)
    }
}
