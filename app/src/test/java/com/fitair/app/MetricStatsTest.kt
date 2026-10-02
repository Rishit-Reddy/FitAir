package com.fitair.app

import com.fitair.app.data.metrics.MetricStats
import com.fitair.app.data.metrics.MetricStats.HrRow
import com.fitair.app.ui.components.Tone
import com.fitair.app.ui.trends.Band
import org.junit.Assert.*
import org.junit.Test

class MetricStatsTest {
    @Test fun bandFromTwentyEightDays() {
        val h = List(28) { if (it % 2 == 0) 50.0 else 56.0 }
        val b = MetricStats.band(h)!!
        assertEquals(53.0, b.mean, 1e-9)
        assertEquals(3.0, b.sd, 1e-9)
        // only the last 28 entries count
        val longer = listOf(200.0, 200.0, 200.0) + h
        assertEquals(53.0, MetricStats.band(longer)!!.mean, 1e-9)
    }

    @Test fun learningBelowFourteenDays() {
        assertNull(MetricStats.band(List(13) { 55.0 }))
        assertNotNull(MetricStats.band(List(14) { 55.0 }))
        assertNull(MetricStats.band(List(28) { null }))
        assertTrue(MetricStats.isLearning(13)); assertFalse(MetricStats.isLearning(14))
        assertEquals(3, MetricStats.daysWithData(listOf(1.0, null, 2.0, null, 3.0)))
        // gaps still count only real readings
        assertNull(MetricStats.band(List(28) { if (it < 10) 55.0 else null }))
    }

    private val b = Band(mean = 55.0, sd = 3.0)

    @Test fun restingTonesAtTheEdges() {
        assertEquals(Tone.Good, MetricStats.rhrTone(40.0, b))          // low is fine
        assertEquals(Tone.Good, MetricStats.rhrTone(58.0, b))          // exactly +1 SD is still usual
        assertEquals(Tone.Caution, MetricStats.rhrTone(58.1, b))
        assertEquals(Tone.Caution, MetricStats.rhrTone(61.0, b))       // exactly +2 SD
        assertEquals(Tone.Alert, MetricStats.rhrTone(61.1, b))
        assertEquals(Tone.Neutral, MetricStats.rhrTone(58.0, null))
        assertEquals(Tone.Neutral, MetricStats.rhrTone(null, b))
    }

    @Test fun recoverySignalTonesAtTheEdges() {
        val h = Band(mean = 46.0, sd = 5.0)
        assertEquals(Tone.Good, MetricStats.hrvTone(80.0, h))          // higher is fine
        assertEquals(Tone.Good, MetricStats.hrvTone(41.0, h))          // exactly -1 SD
        assertEquals(Tone.Caution, MetricStats.hrvTone(40.9, h))
        assertEquals(Tone.Caution, MetricStats.hrvTone(36.0, h))       // exactly -2 SD
        assertEquals(Tone.Alert, MetricStats.hrvTone(35.9, h))
    }

    @Test fun veryStableHistoryDoesNotMakeOneBeatHigh() {
        val flat = Band(55.0, 0.2)
        assertEquals(Tone.Good, MetricStats.rhrTone(56.0, flat))
        assertEquals(Tone.Caution, MetricStats.rhrTone(57.5, flat))
    }

    @Test fun tonesPerDay() {
        assertEquals(listOf(Tone.Good, Tone.Caution, Tone.Neutral), MetricStats.tones(listOf(55.0, 60.0, null), b, hrv = false))
        assertEquals(listOf(Tone.Neutral, Tone.Neutral), MetricStats.tones(listOf(55.0, 60.0).take(2), null, hrv = true))
    }

    // ---- 5-minute downsample ----------------------------------------------------------------------------------

    private val day0 = 1_000_000_000_000L - 1_000_000_000_000L % 300_000L

    @Test fun downsampleAveragesByCountAndKeepsGaps() {
        val rows = listOf(
            HrRow(day0, 60.0, 58, 62, 1),
            HrRow(day0 + 30_000, 80.0, 70, 90, 3),         // same bin: (60*1 + 80*3) / 4 = 75
            HrRow(day0 + 600_000, 100.0, 99, 101, 2),      // bin 2; bin 1 stays a gap
        )
        val b = MetricStats.downsample(rows, day0)
        assertEquals(288, b.mean.size)
        assertEquals(75f, b.mean[0], 1e-4f)
        assertEquals(58f, b.lo[0], 0f); assertEquals(90f, b.hi[0], 0f)
        assertTrue(b.mean[1].isNaN())
        assertEquals(100f, b.mean[2], 1e-4f)
        assertEquals(285, b.mean.count { it.isNaN() })
    }

    @Test fun downsampleIgnoresRowsOutsideTheDay() {
        val rows = listOf(HrRow(day0 - 30_000, 70.0, 70, 70, 1), HrRow(day0 + 288 * 300_000L, 70.0, 70, 70, 1), HrRow(day0 + 287 * 300_000L, 90.0, 90, 90, 1))
        val b = MetricStats.downsample(rows, day0)
        assertEquals(287, b.mean.count { it.isNaN() })
        assertEquals(90f, b.mean[287], 0f)
    }

    @Test fun zonesFromReserve() {
        val z = MetricStats.zoneBpm(rest = 50.0, hrMax = 190.0)
        assertEquals(92f, z[0], 1e-3f); assertEquals(106f, z[1], 1e-3f); assertEquals(134f, z[2], 1e-3f); assertEquals(169f, z[3], 1e-3f)
        assertEquals(0, MetricStats.zoneIndex(60.0, z)); assertEquals(1, MetricStats.zoneIndex(92.0, z)); assertEquals(4, MetricStats.zoneIndex(170.0, z))
    }

    // ---- typical by hour, sums ---------------------------------------------------------------------------------

    @Test fun typicalByHourIsTheMedianAndNeedsSevenDays() {
        fun day(k: Double) = DoubleArray(24) { (it + 1) * k }
        val hist = (1..7).map { day(it.toDouble()) }            // medians: day with k=4
        val t = MetricStats.typicalHourly(hist)
        assertEquals(24, t.size)
        assertEquals(4.0, t[0]!!, 1e-9); assertEquals(96.0, t[23]!!, 1e-9)
        assertTrue(MetricStats.typicalHourly(hist.take(6)).all { it == null })
        assertEquals(3.5, MetricStats.median(listOf(1.0, 3.0, 4.0, 9.0))!!, 1e-9)
    }

    @Test fun totalsAndWeeklySums() {
        val v = listOf(1000.0, null, 2000.0, 3000.0)
        assertEquals(6000.0, MetricStats.total(v)!!, 1e-9)
        assertEquals(2000.0, MetricStats.average(v)!!, 1e-9)
        assertNull(MetricStats.total(listOf(null, null)))
        assertEquals(listOf(1000.0, 1000.0, 3000.0, 6000.0), MetricStats.weeklySums(v))
        val long = List(10) { 1.0 }
        assertEquals(7.0, MetricStats.weeklySums(long).last()!!, 1e-9)
        assertEquals(listOf(null, null, 1.0, 2.0), MetricStats.lastN(listOf(1.0, 2.0), 4))
    }

    @Test fun sinceComparesWithThePeriodBefore() {
        val s = MetricStats.since(listOf(2400.0, 2500.0, null), listOf(2300.0, 2300.0, 2300.0))
        assertEquals(2450.0, s.avg!!, 1e-9); assertEquals(150.0, s.diff!!, 1e-9); assertEquals(4900.0, s.total!!, 1e-9); assertEquals(2, s.days)
        assertNull(MetricStats.since(listOf(1.0), listOf(null, null)).diff)
    }

    @Test fun staleness() {
        assertTrue(MetricStats.stale(null, 1000, 10))
        assertTrue(MetricStats.stale(0, 1000, 10)); assertFalse(MetricStats.stale(995, 1000, 10))
    }
}
