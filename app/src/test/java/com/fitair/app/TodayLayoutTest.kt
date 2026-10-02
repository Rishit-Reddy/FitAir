package com.fitair.app

import com.fitair.app.data.metrics.MetricId
import com.fitair.app.data.metrics.MetricId.*
import com.fitair.app.ui.today.Below
import com.fitair.app.ui.today.Mode
import com.fitair.app.ui.today.destFor
import com.fitair.app.ui.today.TodayDest
import com.fitair.app.ui.today.todayLayout
import org.junit.Assert.*
import org.junit.Test

class TodayLayoutTest {
    @Test fun morningHasReadinessAndSleepLarge() {
        val l = todayLayout(Mode.Morning)
        assertEquals(listOf(Readiness, Sleep), l.large)
        assertEquals(listOf(Hrv, RestingHr, Load), l.small)
    }

    @Test fun dayLeadsWithLoadAndHeart() {
        val l = todayLayout(Mode.Day)
        assertEquals(listOf(Load, Heart), l.large)
        assertEquals(listOf(Readiness, Sleep), l.small)
    }

    @Test fun eveningKeepsTheLargePairAndRotatesTheSmallPair() {
        val l = todayLayout(Mode.Evening)
        assertEquals(todayLayout(Mode.Day).large, l.large)
        assertEquals(listOf(Steps, Bedtime), l.small)
    }

    @Test fun everyModeHasTwoLargeAndNoRepeats() {
        Mode.values().forEach { m ->
            val l = todayLayout(m)
            assertEquals(2, l.large.size); assertTrue(l.small.size in 2..3)
            assertEquals(l.large.size + l.small.size, (l.large + l.small).toSet().size)
        }
    }

    @Test fun eveningHasNoReadinessSleepHrvOrRestingHr() {
        val all = todayLayout(Mode.Evening).let { it.large + it.small }
        listOf(Readiness, Sleep, Hrv, RestingHr).forEach { assertFalse(it.name, it in all) }
    }

    @Test fun bedtimeIsOnlyOnTodayInTheEvening() {
        assertFalse(Bedtime in todayLayout(Mode.Morning).let { it.large + it.small })
        assertFalse(Bedtime in todayLayout(Mode.Day).let { it.large + it.small })
        assertTrue(Bedtime in todayLayout(Mode.Evening).small)
    }

    @Test fun stripsFollowInOrderAndWaterLeavesWithItsWindow() {
        assertEquals(listOf(Below.NextUp, Below.Water), todayLayout(Mode.Day).below)
        assertEquals(listOf(Below.NextUp), todayLayout(Mode.Evening, waterOpen = false).below)
        assertEquals(listOf(Below.NextUp, Below.Water, Below.Alert), todayLayout(Mode.Morning, alert = true).below)
    }

    @Test fun everyCardOpensItsDetail() {
        assertEquals(TodayDest.Heart, destFor(Heart)); assertEquals(TodayDest.Readiness, destFor(Readiness))
        assertEquals(TodayDest.Sleep, destFor(Sleep)); assertEquals(TodayDest.Sleep, destFor(Bedtime))
        assertEquals(TodayDest.Steps, destFor(Steps)); assertEquals(TodayDest.Water, destFor(Water))
        assertNull(destFor(Weight))
        MetricId.values().filter { it != Weight }.forEach { assertNotNull(it.name, destFor(it)) }
    }
}
