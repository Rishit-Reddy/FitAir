package com.fitair.app

import com.fitair.app.ui.today.Insight
import com.fitair.app.ui.today.SleepNight
import com.fitair.app.ui.today.TodayBlock
import com.fitair.app.ui.today.TodayUi
import com.fitair.app.ui.today.todayBlocks
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class TodayBlocksTest {
    private val night = SleepNight(285, "23:41 – 04:26", 58.0, -167.0, null, null)
    private fun ui(night: SleepNight? = this.night, insights: List<Insight> = listOf(Insight("x", false))) =
        TodayUi(LocalDate.of(2026, 10, 2), null, 0L, emptyList(), night, insights, true)

    @Test fun full() = assertEquals(
        listOf(TodayBlock.Readiness, TodayBlock.Agenda, TodayBlock.Sleep, TodayBlock.Vitals, TodayBlock.Insights), todayBlocks(ui()))

    @Test fun noNightDropsSleep() = assertEquals(
        listOf(TodayBlock.Readiness, TodayBlock.Agenda, TodayBlock.Vitals, TodayBlock.Insights), todayBlocks(ui(night = null)))

    @Test fun noInsightsDropsInsights() = assertEquals(
        listOf(TodayBlock.Readiness, TodayBlock.Agenda, TodayBlock.Sleep, TodayBlock.Vitals), todayBlocks(ui(insights = emptyList())))

    @Test fun readinessFirst() {
        assertEquals(TodayBlock.Readiness, todayBlocks(ui(null, emptyList())).first())
    }
}
