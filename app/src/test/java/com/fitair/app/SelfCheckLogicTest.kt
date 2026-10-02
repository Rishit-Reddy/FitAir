package com.fitair.app

import com.fitair.app.analytics.SelfCheckLogic
import com.fitair.app.analytics.SelfCheckLogic.Sess
import org.junit.Assert.*
import org.junit.Test

class SelfCheckLogicTest {
    private val H = 3_600_000L

    @Test fun stepsWithinThreePercentPass() {
        assertFalse(SelfCheckLogic.stepsDiffer(10_000, 10_250))
        assertTrue(SelfCheckLogic.stepsDiffer(10_000, 10_400))
        assertTrue(SelfCheckLogic.stepsDiffer(20_000, 10_000)) // double counting across origins
        assertFalse(SelfCheckLogic.stepsDiffer(10, 0))          // tiny counts: 25 step floor
    }

    @Test fun gapsDetectsHolesAndEdges() {
        val t = listOf(0L, 30 * 60_000L, 60 * 60_000L, 5 * H, 5 * H + 60_000L)
        val g = SelfCheckLogic.gaps(t, 0, 6 * H, 2 * H)
        assertEquals(1, g.size)                    // 1h -> 5h; the 5h -> 6h tail is only 1h
        assertEquals(60 * 60_000L, g[0][0]); assertEquals(5 * H, g[0][1])
    }

    @Test fun gapsTreatsEmptyAsOneGap() {
        assertEquals(1, SelfCheckLogic.gaps(emptyList(), 0, 3 * H, 2 * H).size)
        assertTrue(SelfCheckLogic.gaps(emptyList(), 0, H, 2 * H).isEmpty())
        assertTrue(SelfCheckLogic.gaps(emptyList(), 5, 5, 1).isEmpty())
    }

    @Test fun gapsIgnoreSamplesOutsideWindow() {
        assertEquals(1, SelfCheckLogic.gaps(listOf(-5 * H, 50 * H), 0, 3 * H, 2 * H).size)
    }

    @Test fun outOfRange() {
        assertEquals(listOf(5.0, 300.0), SelfCheckLogic.outOfRange(listOf(5.0, 40.0, 300.0), 10.0, 250.0))
        assertTrue(SelfCheckLogic.outOfRange(listOf(30.0, 120.0), 30.0, 120.0).isEmpty())
    }

    @Test fun weightsOk() {
        assertTrue(SelfCheckLogic.weightsOk(0.30 + 0.25 + 0.15 + 0.15 + 0.15))
        assertFalse(SelfCheckLogic.weightsOk(1.1))
    }

    @Test fun sleepIssues() {
        val clean = SelfCheckLogic.sleepIssues(listOf(Sess(0, 8 * H, "a")))
        assertFalse(clean.crossOriginOverlap); assertFalse(clean.sameOriginMulti)
        val dup = SelfCheckLogic.sleepIssues(listOf(Sess(0, 8 * H, "a"), Sess(H, 8 * H, "b")))
        assertTrue(dup.crossOriginOverlap)
        val nap = SelfCheckLogic.sleepIssues(listOf(Sess(0, 8 * H, "a"), Sess(10 * H, 11 * H, "a")))
        assertFalse(nap.crossOriginOverlap); assertTrue(nap.sameOriginMulti)
        val apart = SelfCheckLogic.sleepIssues(listOf(Sess(0, 4 * H, "a"), Sess(4 * H, 8 * H, "b")))
        assertFalse(apart.crossOriginOverlap) // touching is not overlapping
    }
}
