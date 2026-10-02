package com.fitair.app

import com.fitair.app.data.dao.WakeInfo
import com.fitair.app.ui.today.Mode
import com.fitair.app.ui.today.todayMode
import com.fitair.app.ui.today.windDown
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class TodayModeTest {
    private val z: ZoneId = ZoneId.of("UTC")
    private fun at(h: Int, m: Int = 0) = LocalDateTime.of(2026, 10, 2, h, m)
    private fun ms(h: Int, m: Int = 0) = at(h, m).atZone(z).toInstant().toEpochMilli()
    private fun w(wakeH: Int? = 7, wakeM: Int = 0, bedMin: Int = 23 * 60, last: Long? = null, waiting: Boolean = false) =
        WakeInfo(wakeH?.let { ms(it, wakeM) }, last, 7 * 60, bedMin, waiting)
    private fun mode(h: Int, m: Int, info: WakeInfo) = todayMode(at(h, m), info, z)

    @Test fun morningUntilThreeHoursAfterWake() {
        assertEquals(Mode.Morning, mode(8, 0, w())); assertEquals(Mode.Morning, mode(9, 59, w())); assertEquals(Mode.Day, mode(10, 0, w()))
    }

    @Test fun morningCappedAtNoonButAtLeastAnHour() {
        assertEquals(Mode.Morning, mode(11, 59, w(10, 30))); assertEquals(Mode.Day, mode(12, 0, w(10, 30)))
        assertEquals(Mode.Morning, mode(12, 29, w(11, 30))); assertEquals(Mode.Day, mode(12, 30, w(11, 30)))
    }

    @Test fun eveningStartsThreeHoursBeforeUsualBed() {
        assertEquals(Mode.Day, mode(19, 59, w())); assertEquals(Mode.Evening, mode(20, 0, w()))
    }

    @Test fun eveningNeverBefore1800() {
        val early = w(bedMin = 20 * 60)
        assertEquals(Mode.Day, mode(17, 59, early)); assertEquals(Mode.Evening, mode(18, 0, early))
    }

    @Test fun bedAfterMidnightPushesEvening() {
        val late = w(bedMin = 30)
        assertEquals(Mode.Day, mode(21, 29, late)); assertEquals(Mode.Evening, mode(21, 30, late))
    }

    @Test fun nightIsEveningUntil0400() {
        assertEquals(Mode.Evening, mode(0, 5, w(wakeH = null))); assertEquals(Mode.Evening, mode(3, 59, w(wakeH = null)))
        assertEquals(Mode.Morning, mode(4, 0, w(wakeH = null, waiting = true)))
    }

    @Test fun noSleepYetIsMorningWaitingBefore1000() {
        val none = w(wakeH = null, waiting = true)
        assertEquals(Mode.Morning, mode(7, 30, none)); assertEquals(Mode.Morning, mode(9, 59, none)); assertEquals(Mode.Day, mode(10, 0, none))
    }

    @Test fun noSleepYetEndsAtYesterdaysWakeClockPlusThree() {
        val yesterdayWake = ms(5, 0) - 86_400_000L
        val info = w(wakeH = null, last = yesterdayWake, waiting = true)
        assertEquals(Mode.Morning, mode(7, 59, info)); assertEquals(Mode.Day, mode(8, 0, info))
    }

    @Test fun windDownIsUsualWakeMinusNeedMinus15() {
        assertEquals(23 * 60 + 15, windDown(7 * 60, 450.0, 0.0)!!.bedMin)
        val short = windDown(7 * 60, 450.0, 60.0)!!
        assertEquals(22 * 60 + 45, short.bedMin); assertTrue(short.short)
        assertEquals(450, short.needMin)
        assertNull(windDown(7 * 60, null, 0.0)); assertNull(windDown(7 * 60, 30.0, 0.0))
    }
}
