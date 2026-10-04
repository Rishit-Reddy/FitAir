package com.fitair.app

import com.fitair.app.ui.sleep.SleepTiming
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class SleepTimingTest {
    private val z = ZoneId.of("Europe/Stockholm")

    @Test fun lanes() {
        assertEquals(0, SleepTiming.lane(1)); assertEquals(0, SleepTiming.lane(7)); assertEquals(1, SleepTiming.lane(6))
        assertEquals(2, SleepTiming.lane(4)); assertEquals(3, SleepTiming.lane(5)); assertNull(SleepTiming.lane(0))
    }

    @Test fun offsetFromSixPmBefore() {
        val night = LocalDate.of(2026, 10, 3)
        val at0208 = night.atTime(2, 8).atZone(z).toInstant().toEpochMilli()
        assertEquals(8 * 60 + 8.0, SleepTiming.offsetMin(at0208, night, z), 1e-9)
        assertEquals("02:08", SleepTiming.offsetClock(8 * 60 + 8.0))
        assertEquals("23:30", SleepTiming.offsetClock(330.0))
        assertEquals("18:00", SleepTiming.offsetClock(-1440.0))
    }

    @Test fun sdAndMedian() {
        assertNull(SleepTiming.sd(listOf(1.0, 2.0, 3.0)))
        assertEquals(1.118, SleepTiming.sd(listOf(1.0, 2.0, 3.0, 4.0))!!, 1e-3)
        assertEquals(2.5, SleepTiming.median(listOf(4.0, 1.0, 3.0, 2.0))!!, 1e-9)
        assertNull(SleepTiming.median(emptyList()))
    }
}
