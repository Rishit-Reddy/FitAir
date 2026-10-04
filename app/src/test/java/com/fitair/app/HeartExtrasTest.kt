package com.fitair.app

import com.fitair.app.data.metrics.HeartExtras
import com.fitair.app.data.metrics.MetricStats.HrRow
import org.junit.Assert.*
import org.junit.Test

class HeartExtrasTest {
    private fun row(t: Long, mean: Double, n: Int = 6) = HrRow(t, mean, mean.toInt(), mean.toInt(), n)

    @Test fun overnightLowOnlyInsideSleep() {
        val rows = listOf(row(0, 40.0), row(1_000, 52.0), row(2_000, 49.0), row(5_000, 45.0))
        val low = HeartExtras.overnightLow(rows, listOf(longArrayOf(1_000, 3_000)))!!
        assertEquals(49, low.bpm); assertEquals(2_000L, low.atMs)
        assertNull(HeartExtras.overnightLow(rows, emptyList()))
    }

    @Test fun dropAfterNeedsBothMinutes() {
        val end = 600_000L
        val rows = listOf(row(end - 60_000, 150.0), row(end - 30_000, 140.0), row(end + 60_000, 120.0), row(end + 90_000, 110.0))
        assertEquals(30, HeartExtras.dropAfter(rows, end))
        assertNull(HeartExtras.dropAfter(rows.take(2), end))
        assertNull(HeartExtras.dropAfter(rows.drop(2), end))
    }

    @Test fun weekChangeNeedsFourPerWeek() {
        val v = List(7) { 55.0 } + List(7) { 52.0 }
        assertEquals(-3.0, HeartExtras.weekChange(v)!!, 1e-9)
        assertNull(HeartExtras.weekChange(List(4) { null } + List(3) { 50.0 } + List(7) { 52.0 }))
        assertNull(HeartExtras.weekChange(List(10) { 50.0 }))
    }

    @Test fun meanOfMinN() {
        assertEquals(2.0, HeartExtras.meanOf(listOf(1.0, null, 3.0))!!, 1e-9)
        assertNull(HeartExtras.meanOf(listOf(1.0, null), 2))
        assertNull(HeartExtras.meanOf(emptyList()))
    }
}
