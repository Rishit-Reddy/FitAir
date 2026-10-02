package com.fitair.app

import com.fitair.app.analytics.CardioLoad.Bucket
import com.fitair.app.analytics.WorkIntensity
import com.fitair.app.analytics.WorkIntensity.Win
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkIntensityTest {
    private val rest = 55.0
    private val max = 190.0
    private val t0 = 1_790_000_000_000L - 1_790_000_000_000L % 30_000L
    private fun bpm(h: Double) = rest + h * (max - rest)
    private fun run(from: Long, n: Int, v: Double) = (0 until n).map { Bucket(from + it * 30_000L, v) }
    private val hour = 3_600_000L

    @Test fun overlappingAndTouchingWindowsMerge() {
        val m = WorkIntensity.merge(listOf(Win(t0 + 5 * hour, t0 + 7 * hour, "B"), Win(t0, t0 + 3 * hour, "A"), Win(t0 + 2 * hour, t0 + 4 * hour, "A"),
            Win(t0 + 4 * hour, t0 + 5 * hour, "C"), Win(t0 + 20 * hour, t0 + 21 * hour, "D"), Win(t0 + 30 * hour, t0 + 30 * hour, "empty")))
        assertEquals(2, m.size)
        assertEquals(t0, m[0].startMs); assertEquals(t0 + 7 * hour, m[0].endMs); assertEquals("A + C + B", m[0].label)
        assertEquals("D", m[1].label)
    }

    @Test fun statsOnAFullyCoveredWindow() {
        val w = Win(t0, t0 + 2 * hour, "Shift")
        val b = run(t0, 240, bpm(0.5))
        val s = WorkIntensity.compute(listOf(w), b, rest, max).single()
        assertEquals(2.0, s.hours, 1e-9)
        assertEquals(1.0, s.coverage!!, 1e-9)
        assertEquals(Math.round(bpm(0.5)).toInt(), s.avgHr)
        assertEquals(50, s.avgPctHrr)
        assertEquals(120, s.minutesZone2Plus)
        val expect = 240 * 0.5 * 0.5 * 0.64 * Math.exp(1.92 * 0.5)
        assertEquals(Math.round(expect * 10) / 10.0, s.load!!, 1e-9)
    }

    @Test fun lowCoverageGivesNullStats() {
        val w = Win(t0, t0 + 2 * hour, "Shift")
        val s = WorkIntensity.compute(listOf(w), run(t0, 100, bpm(0.6)), rest, max).single()   // 100 of 240 buckets
        assertNull(s.avgHr); assertNull(s.avgPctHrr); assertNull(s.load); assertEquals(0, s.minutesZone2Plus)
        assertEquals(0.42, s.coverage!!, 0.005)
    }

    @Test fun exactlyHalfCoverageIsEnough() {
        val w = Win(t0, t0 + 2 * hour, "Shift")
        val s = WorkIntensity.compute(listOf(w), run(t0, 120, bpm(0.45)), rest, max).single()
        assertNotNull(s.avgHr); assertEquals(0.5, s.coverage!!, 1e-9)
    }

    @Test fun noDataAtAll() {
        val s = WorkIntensity.compute(listOf(Win(t0, t0 + hour, "x")), emptyList(), rest, max).single()
        assertNull(s.avgHr); assertEquals(0.0, s.coverage!!, 1e-9)
    }

    @Test fun dataOutsideTheWindowIsIgnored() {
        val w = Win(t0 + hour, t0 + 2 * hour, "x")
        val b = run(t0, 120, bpm(0.9)) + run(t0 + hour, 120, bpm(0.3)) + run(t0 + 2 * hour, 120, bpm(0.9))
        val s = WorkIntensity.compute(listOf(w), b, rest, max).single()
        assertEquals(30, s.avgPctHrr); assertEquals(1.0, s.coverage!!, 1e-9)
    }

    @Test fun shortSpikesDoNotCountAsLoadOrZoneMinutes() {
        val w = Win(t0, t0 + hour, "x")
        val b = run(t0, 120, bpm(0.1)).mapIndexed { i, x -> if (i % 10 == 0) Bucket(x.t30, bpm(0.9)) else x }
        val s = WorkIntensity.compute(listOf(w), b, rest, max).single()
        assertEquals(0.0, s.load!!, 1e-9); assertEquals(0, s.minutesZone2Plus)
        assertTrue(s.avgPctHrr!! > 10)
    }
}
