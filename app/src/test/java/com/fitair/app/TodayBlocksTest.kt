package com.fitair.app

import com.fitair.app.coach.DayFacts
import com.fitair.app.ui.components.dayFactCells
import com.fitair.app.ui.today.EVENING_INSIGHTS
import com.fitair.app.ui.today.Insight
import com.fitair.app.ui.today.Mode
import com.fitair.app.ui.today.SleepNight
import com.fitair.app.ui.today.TodayBlock
import com.fitair.app.ui.today.TodayBlock.*
import com.fitair.app.ui.today.TodayUi
import com.fitair.app.ui.today.WindDown
import com.fitair.app.ui.today.todayBlocks
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class TodayBlocksTest {
    private val night = SleepNight(285, "23:41 – 04:26", 58.0, -167.0, null, null)
    private fun ui(night: SleepNight? = this.night, insights: List<Insight> = listOf(Insight("x", false, "rhr_elevated")), wind: WindDown? = WindDown(450, 1395, false)) =
        TodayUi(LocalDate.of(2026, 10, 2), null, 0L, emptyList(), night, insights, true, windDown = wind)

    @Test fun morning() = assertEquals(listOf(Readiness, Sleep, Vitals, Agenda, Water, Insights), todayBlocks(ui(), Mode.Morning))

    @Test fun morningWithoutNightShowsTheQuietSleepLine() =
        assertEquals(listOf(Readiness, SleepLine, Vitals, Agenda, Water, Insights), todayBlocks(ui(night = null), Mode.Morning))

    @Test fun morningWithoutInsights() = assertEquals(listOf(Readiness, Sleep, Vitals, Agenda, Water), todayBlocks(ui(insights = emptyList()), Mode.Morning))

    @Test fun day() = assertEquals(listOf(NextUp, Water, AgendaRest, ReadinessCompact, Load, SleepLine, Insights), todayBlocks(ui(), Mode.Day))

    @Test fun dayExpandsCollapsedBlocksInline() {
        assertEquals(listOf(NextUp, Water, AgendaRest, Readiness, Load, Sleep, Insights),
            todayBlocks(ui(), Mode.Day, setOf(ReadinessCompact, SleepLine)))
        // without a night there is nothing to expand: it stays the quiet line
        assertTrue(SleepLine in todayBlocks(ui(night = null), Mode.Day, setOf(SleepLine)))
    }

    @Test fun nextUpLeadsInTheDay() = assertEquals(NextUp, todayBlocks(ui(), Mode.Day).first())

    @Test fun eveningHasNoSleepHrvRestingHrOrReadiness() {
        val b = todayBlocks(ui(), Mode.Evening)
        assertEquals(listOf(DaySummary, Tomorrow, Water, WindDown, AgendaRest), b)
        listOf(Readiness, ReadinessCompact, Sleep, SleepLine, Vitals, NextUp, Load).forEach { assertFalse(it.name, it in b) }
    }

    @Test fun eveningTomorrowRightAfterTheCard() {
        val b = todayBlocks(ui(), Mode.Evening)
        assertEquals(DaySummary, b[0]); assertEquals(Tomorrow, b[1])
    }

    @Test fun eveningDropsRestOfTodayWhenDoneAndWindDownWhenUnknown() {
        assertEquals(listOf(DaySummary, Tomorrow, Water), todayBlocks(ui(wind = null), Mode.Evening, agendaLeft = false))
    }

    @Test fun eveningKeepsOnlyLoadAndDebtInsights() {
        val withDebt = ui(insights = listOf(Insight("a", false, "hrv_low"), Insight("b", false, "sleep_debt")))
        assertTrue(Insights in todayBlocks(withDebt, Mode.Evening))
        assertFalse(Insights in todayBlocks(ui(insights = listOf(Insight("a", false, "hrv_low"))), Mode.Evening))
        assertTrue("acwr_high" in EVENING_INSIGHTS && "sleep_debt" in EVENING_INSIGHTS)
    }

    @Test fun stepsAppearOnlyInTheDaySummaryCard() {
        val f = DayFacts("2026-10-02", 8234, 6500.0, 54.0, 32, 2100, 2500, 56.0, 74.0, 152.0, emptyList(), false)
        val cells = dayFactCells(f)
        assertEquals("8,234", cells.first { it.first == "Steps" }.second)
        assertEquals("6.5 km", cells.first { it.first == "Distance" }.second)
        assertEquals("2.1 of 2.5 L", cells.first { it.first == "Water" }.second)
        assertEquals("54 · 32 min hard", cells.first { it.first == "Cardio load" }.second)
    }

    @Test fun cardsAreTaggedForSeparators() {
        assertTrue(Sleep.card); assertFalse(Readiness.card); assertTrue(DaySummary.card)
    }
}
