package com.fitair.app

import com.fitair.app.analytics.CardioLoad
import com.fitair.app.analytics.CardioLoad.Bucket
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class CardioLoadTest {
    private val z = ZoneId.of("Europe/Stockholm")
    private val date = LocalDate.of(2026, 9, 30)
    private val day0 = date.atStartOfDay(z).toInstant().toEpochMilli()
    private val rest = 55.0
    private val max = 190.0

    private fun at(hour: Int, minute: Int = 0) = day0 + (hour * 60L + minute) * 60_000L
    /** [n] consecutive 30 s buckets from [start] at [bpm]. */
    private fun run(start: Long, n: Int, bpm: Double) = (0 until n).map { Bucket(start + it * 30_000L, bpm) }
    private fun hrrBpm(h: Double) = rest + h * (max - rest)
    private fun day(b: List<Bucket>, until: Long = day0 + 86_400_000L) = CardioLoad.day(b, rest, max, z, day0, until)

    @Test fun restingDayIsAboutZero() {
        val b = (0 until 2880).map { Bucket(day0 + it * 30_000L, rest + 4 + (it % 5)) }
        assertEquals(0.0, day(b).cardio, 1e-9)
    }

    @Test fun thirtyMinutesAt70PercentMatchesBanisterSum() {
        val b = run(at(10), 60, hrrBpm(0.70))
        val expect = 60 * 0.5 * 0.70 * 0.64 * Math.exp(1.92 * 0.70)
        val r = day(b)
        assertEquals(expect, r.cardio, 1e-6)
        assertEquals(30, r.zVig)             // 0.70 is in the Vigorous band 60-84 %
        assertEquals(0, r.zPeak)
    }

    @Test fun ninetySecondSpikeCountsZero() {
        val b = run(at(9), 20, rest + 5) + run(at(9, 10), 3, hrrBpm(0.8)) + run(at(9, 12), 20, rest + 5)
        assertEquals(0.0, day(b).cardio, 1e-9)
    }

    @Test fun twoMinutesIsTheMinimumRun() {
        assertEquals(0.0, day(run(at(9), 3, hrrBpm(0.5))).cardio, 1e-9)
        assertTrue(day(run(at(9), 4, hrrBpm(0.5))).cardio > 0)
    }

    @Test fun gapInTimeBreaksARun() {
        val b = run(at(9), 2, hrrBpm(0.5)) + run(at(9, 5), 2, hrrBpm(0.5))   // 2 + 2 with a gap, never 4 in a row
        assertEquals(0.0, day(b).cardio, 1e-9)
    }

    @Test fun zoneBandEdges() = listOf(
        0.29 to "none", 0.30 to "light", 0.39 to "light", 0.40 to "mod", 0.59 to "mod", 0.60 to "vig", 0.84 to "vig", 0.85 to "peak", 1.0 to "peak",
    ).forEach { (h, zone) ->
        val r = day(run(at(12), 20, hrrBpm(h) + 1e-6))   // 10 min
        val m = mapOf("light" to r.zLight, "mod" to r.zMod, "vig" to r.zVig, "peak" to r.zPeak)
        if (zone == "none") assertTrue("h=$h", m.values.all { it == 0 }) else m.forEach { (k, v) -> assertEquals("h=$h $k", if (k == zone) 10 else 0, v) }
    }

    @Test fun hourlyIsCumulative() {
        val r = day(run(at(10), 20, hrrBpm(0.5)) + run(at(14), 20, hrrBpm(0.5)))
        assertEquals(0.0, r.hourly[9], 1e-9)
        assertTrue(r.hourly[10] > 0)
        assertEquals(r.hourly[10], r.hourly[13], 1e-9)
        assertEquals(r.cardio, r.hourly[23], 1e-9)
        assertEquals(r.cardio, r.hourly[14] , 1e-9)
    }

    @Test fun coverageOfWakingHours() {
        val full = (0 until 1800).map { Bucket(at(7) + it * 30_000L, 70.0) }
        assertEquals(1.0, day(full).coverage!!, 1e-9)
        assertEquals(0.5, day(full.take(900)).coverage!!, 1e-9)
        assertTrue(CardioLoad.isPartial(day(full.take(900)).coverage))
        assertFalse(CardioLoad.isPartial(1.0))
        // only the first hour of the window has elapsed: too early to judge
        assertNull(CardioLoad.coverage(emptyList(), z, day0, at(7, 30)))
        // today: window ends at the newest bucket, not at 22:00
        assertEquals(1.0, CardioLoad.coverage(full.take(600).map { it.t30 }, z, day0, at(7) + 600 * 30_000L)!!, 1e-9)
    }

    @Test fun hrMaxPriority() {
        assertEquals(CardioLoad.HrMaxSource.Set, CardioLoad.hrMax(186.0, 1990, 2026, 199.0).source)
        assertEquals(186.0, CardioLoad.hrMax(186.0, 1990, 2026, 199.0).value, 0.0)
        val age = CardioLoad.hrMax(null, 1990, 2026, 199.0)          // 208 - 0.7 x 36 = 182.8 -> 183
        assertEquals(CardioLoad.HrMaxSource.Age, age.source); assertEquals(183.0, age.value, 0.0)
        val obs = CardioLoad.hrMax(null, null, 2026, 188.0)
        assertEquals(CardioLoad.HrMaxSource.Observed, obs.source); assertEquals(193.0, obs.value, 0.0)
        assertEquals(205.0, CardioLoad.hrMax(null, null, 2026, 230.0).value, 0.0)   // clamped
        assertEquals(170.0, CardioLoad.hrMax(null, null, 2026, 120.0).value, 0.0)
        assertEquals(CardioLoad.HrMaxSource.Default, CardioLoad.hrMax(null, null, 2026, null).source)
        assertEquals(CardioLoad.HrMaxSource.Observed, CardioLoad.hrMax(null, 1700, 2026, 190.0).source)   // absurd birth year ignored
        assertEquals(CardioLoad.HrMaxSource.Observed, CardioLoad.hrMax(999.0, null, 2026, 190.0).source)  // absurd pref ignored
    }

    @Test fun percentileFromHistogram() {
        val h = (100..199).associateWith { 10L } + (230 to 1L)        // n = 1001; the single 230 outlier is above P99.5
        assertEquals(199.0, CardioLoad.percentile(h, 0.995)!!, 0.0)
        assertNull(CardioLoad.percentile(emptyMap(), 0.995))
    }

    @Test fun ewmaAndRatio() {
        val flat = List(40) { 50.0 }
        val e = CardioLoad.ewma(flat, List(40) { true })
        assertEquals(1.0, e.last()!!.ratio!!, 1e-9)
        assertNull(e[5]!!.ratio)                        // < 14 days of history
        val spike = flat.take(39) + listOf(200.0)
        assertTrue(CardioLoad.ewma(spike, List(40) { true }).last()!!.ratio!! > 1.3)
        assertNull(CardioLoad.ewma(List(30) { 0.0 }, List(30) { true }).last()!!.ratio)   // chronic ~ 0
        assertNull(CardioLoad.ewma(listOf(0.0, 0.0), listOf(false, false)).last())
    }

    @Test fun verdictBands() = listOf(null to null, 0.5 to "lighter", 0.8 to "normal", 1.3 to "normal", 1.4 to "harder", 1.5 to "harder", 1.6 to "much_harder")
        .forEach { (r, v) -> assertEquals("ratio $r", v, CardioLoad.verdict(r)) }

    @Test fun typicalByNowInterpolatesAndNeedsSevenDays() {
        val h = DoubleArray(24) { (it + 1) * 10.0 }       // cumulative 10, 20, ...
        assertNull(CardioLoad.typicalByNow(List(6) { h }, 12, 0.5))
        assertEquals(125.0, CardioLoad.typicalByNow(List(7) { h }, 12, 0.5)!!, 1e-9)   // between 120 (hour 11) and 130
    }
}
