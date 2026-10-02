package com.fitair.app

import com.fitair.app.data.dao.LoadToday
import com.fitair.app.data.dao.Pace
import com.fitair.app.data.dao.WaterToday
import com.fitair.app.data.metrics.HeartDay
import com.fitair.app.data.metrics.MetricId
import com.fitair.app.data.metrics.MetricId.*
import com.fitair.app.data.metrics.MetricSnapshot
import com.fitair.app.data.metrics.NightBrief
import com.fitair.app.ui.components.CardSize
import com.fitair.app.ui.components.CardState
import com.fitair.app.ui.components.Tone
import com.fitair.app.ui.components.charts.Mini
import com.fitair.app.ui.metrics.MetricCards
import com.fitair.app.ui.sleep.StageMinutes
import com.fitair.app.ui.today.Mode
import com.fitair.app.ui.today.WindDown
import com.fitair.app.ui.trends.Band
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class MetricCardsTest {
    private val utc = ZoneOffset.UTC
    private val date = LocalDate.of(2026, 10, 2)
    private val now = date.atTime(14, 5).toInstant(utc).toEpochMilli()
    private val days = (6 downTo 0).map { date.minusDays(it.toLong()) }

    private fun week(vararg v: Double?) = v.toList()
    private val heart = HeartDay(
        FloatArray(288) { if (it < 170) 70f + it % 5 else Float.NaN }, 56f, 190f, floatArrayOf(100f, 120f, 150f, 170f),
        latestBpm = 84, latestMs = now - 5 * 60_000L, coverage = 0.9,
    )

    private fun snap(
        rhr: List<Double?> = week(55.0, 54.0, 56.0, 55.0, 53.0, 55.0, 54.0), hrv: List<Double?> = week(45.0, 47.0, 46.0, 44.0, 48.0, 46.0, 46.0),
        bandDays: Int = 20, lastDataMs: Long? = now - 5 * 60_000L, heart: HeartDay? = this.heart,
    ) = MetricSnapshot(
        date, lastDataMs, days,
        readiness = week(70.0, 72.0, 68.0, 74.0, 71.0, 69.0, 74.0), rhr = rhr, hrv = hrv, load = week(40.0, 55.0, 30.0, 60.0, 20.0, 45.0, 54.0),
        steps = week(7000.0, 8000.0, 9000.0, 6000.0, 8500.0, 7200.0, 8234.0), distanceM = week(5000.0, 6000.0, 7000.0, 4500.0, 6200.0, 5100.0, 6100.0),
        kcal = week(2300.0, 2400.0, 2200.0, 2500.0, 2300.0, 2250.0, 1420.0), waterMl = week(2000.0, 2200.0, null, 1800.0, 2500.0, 2100.0, 1250.0),
        rhrBand = if (bandDays >= 14) Band(55.0, 1.5) else null, hrvBand = if (bandDays >= 14) Band(46.0, 2.0) else null, bandDays = bandDays,
        nights = listOf(null, NightBrief(420.0, 457.0, 70.0), NightBrief(450.0, 457.0, 78.0), null, NightBrief(400.0, 457.0, 65.0), NightBrief(430.0, 457.0, 72.0), NightBrief(412.0, 457.0, 70.0)),
        lastStages = StageMinutes(30.0, 200.0, 90.0, 92.0), debtMin = 130.0,
        loadToday = LoadToday(54.0, 50.0, 1.1, 0.9, false),
        loadHourly = List(24) { if (it <= 14) it * 3.6 else null }, typicalHourly = List(24) { it * 3.0 }, ratio = 1.1, loadUsual = 40.0,
        heart = heart, water = WaterToday(1250, 2500, 0, Pace.OnPace, false), windDown = WindDown(450, 1395, false),
    )

    private fun card(id: MetricId, s: MetricSnapshot = snap(), size: CardSize = CardSize.Grid, mode: Mode? = null) = MetricCards.card(id, s, size, mode, now, utc)

    @Test fun heartRateShowsLatestReadingAndZone() {
        val c = card(Heart)
        assertEquals("84", c.value); assertEquals("bpm", c.unit); assertEquals("at 14:00", c.sub)
        assertEquals("Resting", c.chip!!.text); assertEquals(Tone.Neutral, c.chip!!.tone)
        assertTrue(c.mini is Mini.HrDay); assertEquals(CardState.Ready, c.state)
        assertTrue(c.a11y, c.a11y.startsWith("Heart rate, 84 beats per minute, resting"))
    }

    @Test fun staleHeartRateIsDimWithItsClock() {
        val old = HeartDay(heart.points, 56f, 190f, heart.zoneBpm, 80, now - 4 * 3_600_000L, 0.9)
        val c = card(Heart, snap(heart = old))
        assertEquals(CardState.Stale, c.state); assertEquals("at 10:05", c.sub); assertEquals("80", c.value)
    }

    @Test fun partialDayChipWinsOnHeartAndLoad() {
        val partial = HeartDay(heart.points, 56f, 190f, heart.zoneBpm, 84, now, 0.4)
        assertEquals("Partial day", card(Heart, snap(heart = partial)).chip!!.text)
        val s = MetricSnapshot(date, now, days, load = week(1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0), loadToday = LoadToday(7.0, 6.0, 1.0, 0.4, true), ratio = 1.0)
        assertEquals("Partial day", card(Load, s).chip!!.text)
    }

    @Test fun readinessCard() {
        val c = card(Readiness)
        assertEquals("74", c.value); assertEquals("/100", c.unit); assertEquals("Well recovered", c.chip!!.text); assertEquals(Tone.Good, c.chip!!.tone)
        assertTrue(c.mini is Mini.Spark); assertEquals(7, c.weekdays.size); assertEquals(6, c.todayIndex)
        assertTrue(c.a11y, c.a11y.contains("74 out of 100") && c.a11y.endsWith("Opens details."))
    }

    @Test fun sleepCardAgainstNeed() {
        val c = card(Sleep)
        assertEquals("6h 52m", c.value); assertEquals("45m short", c.chip!!.text); assertEquals(Tone.Caution, c.chip!!.tone)
        assertTrue(c.mini is Mini.Bars)
        assertTrue(card(Sleep, size = CardSize.Large).mini is Mini.Stages)
    }

    @Test fun restingHrInsideAboveAndLearning() {
        assertEquals("Usual for you", card(Resting()).chip!!.text)
        val high = snap(rhr = week(55.0, 54.0, 56.0, 55.0, 53.0, 55.0, 57.0))
        assertEquals("A bit high", card(RestingHr, high).chip!!.text); assertEquals(Tone.Caution, card(RestingHr, high).chip!!.tone)
        val learning = card(RestingHr, snap(bandDays = 9))
        assertEquals(CardState.Learning, learning.state); assertEquals("Learning your normal · 9/14", learning.chip!!.text)
        assertEquals("53", card(RestingHr, snap(rhr = week(null, null, null, null, null, null, 53.0))).value)
    }

    private fun Resting() = RestingHr

    @Test fun recoverySignalCard() {
        val c = card(Hrv)
        assertEquals("46", c.value); assertEquals("ms", c.unit); assertEquals("Usual for you", c.chip!!.text)
        assertTrue(c.mini is Mini.DotsOnBand); assertTrue(c.smallShowsMini)
        val low = card(Hrv, snap(hrv = week(45.0, 47.0, 46.0, 44.0, 48.0, 46.0, 43.0)))
        assertEquals("Lower than usual", low.chip!!.text); assertEquals(Tone.Caution, low.chip!!.tone)
    }

    @Test fun loadCardWeekAndLiveVariants() {
        val c = card(Load)
        assertEquals("54", c.value); assertEquals("Normal week", c.chip!!.text); assertTrue(c.mini is Mini.Bars)
        val live = card(Load, size = CardSize.Large, mode = Mode.Day)
        assertEquals("Load so far", live.title); assertEquals("About usual", live.chip!!.text); assertTrue(live.mini is Mini.LoadCurve)
        assertEquals("Load today", card(Load, size = CardSize.Large, mode = Mode.Evening).title)
    }

    @Test fun totalsAreNeutral() {
        val e = card(Energy)
        assertEquals("1,420", e.value); assertEquals("kcal", e.unit); assertEquals(Tone.Neutral, e.chip!!.tone); assertTrue(e.chip!!.text.startsWith("7-day avg "))
        val d = card(Distance)
        assertEquals("6.1", d.value); assertEquals("km", d.unit); assertEquals("This week 40 km", d.chip!!.text); assertEquals(Tone.Neutral, d.chip!!.tone)
        val st = card(Steps)
        assertEquals("8,234", st.value); assertEquals("This week 53.9k", st.chip!!.text); assertEquals(Tone.Neutral, st.chip!!.tone)
        assertNull((st.mini as Mini.Bars).ref)
        val w = card(Water)
        assertEquals("1.25", w.value); assertEquals("L", w.unit); assertEquals("On pace", w.chip!!.text); assertTrue(w.mini is Mini.Ring)
        assertEquals(0.5f, (w.mini as Mini.Ring).fraction, 0.001f)
    }

    @Test fun smallStepsShowKilometresAndNoChip() {
        val c = card(Steps, size = CardSize.Small)
        assertEquals("6.1 km", c.sub); assertNull(c.chip)
    }

    @Test fun staleTotalsAreDimWhenDataIsOld() {
        val c = card(Energy, snap(lastDataMs = now - 5 * 3_600_000L))
        assertEquals(CardState.Stale, c.state); assertEquals("at 09:05", c.sub)
    }

    @Test fun emptyCardsShowDashAndNoData() {
        val empty = MetricSnapshot(date, null, days, readiness = List(7) { null }, rhr = List(7) { null }, kcal = List(7) { null })
        listOf(Readiness, RestingHr, Energy).forEach {
            val c = card(it, empty)
            assertEquals(it.name, CardState.Empty, c.state); assertNull(c.value); assertEquals("No data", c.chip!!.text)
            assertTrue(c.a11y, c.a11y.contains("no data yet"))
        }
        assertEquals(CardState.Empty, card(Heart, MetricSnapshot(date, null, days)).state)
    }

    @Test fun waitingForSleepKeepsTheWeekButNoValue() {
        val s = snap().let { MetricSnapshot(date, it.lastDataMs, days, readiness = week(70.0, 72.0, 68.0, 74.0, 71.0, 69.0, null), nights = List(7) { null }) }
        val r = card(Readiness, s); assertEquals(CardState.Stale, r.state); assertNull(r.value); assertEquals("after your sleep syncs", r.sub)
        val sl = card(Sleep, MetricSnapshot(date, now, days, nights = listOf(NightBrief(400.0, 450.0, 60.0)) + List(6) { null }))
        assertEquals("Waiting for sleep data", sl.sub)
    }

    @Test fun bedtimeCard() {
        val c = card(Bedtime, size = CardSize.Small)
        assertEquals("23:15", c.value); assertEquals("for 7h 30m", c.sub); assertNull(c.chip)
        val short = card(Bedtime, snap().let { MetricSnapshot(date, now, days, windDown = WindDown(450, 1365, true)) }, CardSize.Small)
        assertEquals("Short on sleep", short.chip!!.text); assertEquals(Tone.Caution, short.chip!!.tone)
    }

    @Test fun weightCardWhenThereIsData() {
        val t = date.minusDays(21).atStartOfDay().toInstant(utc).toEpochMilli()
        val s = MetricSnapshot(date, now, days, weight = listOf(t to 82.4, t + 10 * 86_400_000L to 80.9, t + 21 * 86_400_000L to 79.4))
        val c = card(Weight, s)
        assertEquals("79.4", c.value); assertEquals("kg", c.unit); assertEquals("Down 3.0 kg since 11 Sep", c.chip!!.text)
    }

    @Test fun everyCardHasATitleAndAFullSentence() {
        MetricId.values().forEach {
            val c = card(it)
            assertEquals(MetricCards.title(it), c.title)
            assertTrue("${it.name}: ${c.a11y}", c.a11y.endsWith("Opens details.") && c.a11y.startsWith(c.title))
        }
    }
}
