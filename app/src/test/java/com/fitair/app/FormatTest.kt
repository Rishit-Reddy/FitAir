package com.fitair.app

import com.fitair.app.core.Format
import com.fitair.app.core.Num
import org.junit.Assert.*
import org.junit.Test

class FormatTest {
    @Test fun duration() {
        assertEquals("7h 24m", Format.duration(444))
        assertEquals("0h 5m", Format.duration(5))
        assertEquals(Format.DASH, Format.duration(null))
    }

    @Test fun deltaMinutes() {
        assertEquals("+0:32", Format.deltaMinutes(32))
        assertEquals("−1:05", Format.deltaMinutes(-65))
        assertEquals("+0:00", Format.deltaMinutes(0))
    }

    @Test fun deltaShort() {
        assertEquals("+32m", Format.deltaShort(32))
        assertEquals("−1h 05m", Format.deltaShort(-65))
        assertEquals("0m", Format.deltaShort(0))
    }

    @Test fun band() {
        assertEquals("Well recovered", Format.band(70)); assertEquals("Partly recovered", Format.band(69))
        assertEquals("Partly recovered", Format.band(50)); assertEquals("Not recovered", Format.band(49))
        assertNull(Format.band(null))
    }

    @Test fun freshness() {
        val now = 10_000_000_000L
        assertEquals("never synced", Format.freshness(now, 0).text)
        assertEquals("synced just now", Format.freshness(now, now - 20_000).text)
        assertEquals("synced 12 min ago", Format.freshness(now, now - 12 * 60_000L).text)
        assertFalse(Format.freshness(now, now - 59 * 60_000L).stale)
        val s = Format.freshness(now, now - 3 * 3_600_000L)
        assertEquals("updated 3 h ago", s.text); assertTrue(s.stale)
        assertEquals("updated 3 d ago", Format.freshness(now, now - 3 * 86_400_000L).text)
        assertEquals("synced just now", Format.freshness(now, now + 5_000).text) // clock skew
    }

    @Test fun sdPhrase() {
        assertEquals("1.4 SD below baseline", Format.sdPhrase(-1.4))
        assertEquals("2.0 SD above baseline", Format.sdPhrase(2.04))
        assertEquals("near baseline", Format.sdPhrase(0.1))
    }

    @Test fun thousandsAndBaseline() {
        assertEquals("8,234", Format.thousands(8234))
        assertNull(Format.baseline(listOf(1.0, 2.0)))
        assertEquals(4.0, Format.baseline(listOf(1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0))!!, 1e-9)
    }

    @Test fun roundingIsHalfEven() {
        assertEquals(2.0, Num.r(2.5, 0)!!, 0.0)
        assertEquals(4.0, Num.r(3.5, 0)!!, 0.0)
        assertEquals(1.2, Num.r(1.25, 1)!!, 0.0) // 1.25 is exact in binary: half-even
        assertNull(Num.r(null))
        assertEquals(0.0, Num.sd(listOf(3.0)), 0.0)
        assertEquals(1.0, Num.sd(listOf(1.0, 2.0, 3.0)), 1e-12)
    }

    @Test fun compactCount() {
        assertEquals("8,234", Format.compactCount(8234))
        assertEquals("10.2k", Format.compactCount(10234))
        assertEquals("9,999", Format.compactCount(9999))
    }

    @Test fun stageAndLegendMinutes() {
        assertEquals("1h 05", Format.stageLong(65)); assertEquals("45m", Format.stageLong(45)); assertEquals("2h 00", Format.stageLong(120))
        assertEquals("65m", Format.stageShort(65)); assertEquals("6m", Format.stageShort(6))
        assertEquals("1h 05m", Format.hm(65)); assertEquals("45m", Format.hm(45)); assertEquals("0m", Format.hm(0))
    }
}
