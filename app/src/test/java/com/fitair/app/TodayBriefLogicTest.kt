package com.fitair.app

import com.fitair.app.coach.BriefEvent
import com.fitair.app.coach.BriefFacts
import com.fitair.app.coach.TodayBriefLogic as L
import com.fitair.app.ui.today.Mode
import org.junit.Assert.*
import org.junit.Test

class TodayBriefLogicTest {
    private val base = BriefFacts(
        mode = Mode.Day, readiness = 74, verdictKey = "well", sleepAsleepMin = 412, sleepNeedMin = 450, sleepScore = 78,
        hrv = 46, hrvUsual = 44, rhr = 53, rhrUsual = 54, loadSoFar = 54, loadTypicalByNow = 50, waterMl = 1250, waterGoalMl = 2500,
        waterPace = "on_pace", nextEvent = BriefEvent("Shift", "15:00", 55), bedtime = "23:15", bedtimeForMin = 450, insightIds = listOf("sleep_debt"),
    )
    private val allowed = L.allowedNumbers(base)
    private val clocks = L.allowedClocks(base, "14:05")
    private fun problem(vararg b: String) = L.problem(b.toList(), allowed, clocks)

    // ---- hash -------------------------------------------------------------------------------------------------

    @Test fun hashIgnoresSmallMoves() {
        val h = L.hash(base)
        fun same(f: BriefFacts) = assertEquals(h, L.hash(f))
        same(BriefFactsCopy.with(base, readiness = 73))      // 73 and 74 are both bucket 14
        same(BriefFactsCopy.with(base, loadSoFar = 56))      // bucket 5 both
        same(BriefFactsCopy.with(base, waterMl = 1400))      // bucket 5 both
        same(BriefFactsCopy.with(base, sleepAsleepMin = 415)) // bucket 27 both
        same(BriefFactsCopy.with(base, nextEvent = BriefEvent("Shift", "15:00", 40))) // minutes away do not count
    }

    @Test fun hashChangesOnMaterialMoves() {
        val h = L.hash(base)
        fun diff(f: BriefFacts) = assertNotEquals(h, L.hash(f))
        diff(BriefFactsCopy.with(base, readiness = 80))
        diff(BriefFactsCopy.with(base, loadSoFar = 70))
        diff(BriefFactsCopy.with(base, waterMl = 1750))
        diff(BriefFactsCopy.with(base, sleepAsleepMin = 440))
        diff(BriefFactsCopy.with(base, nextEvent = BriefEvent("Gym", "17:00", 55)))
        diff(BriefFactsCopy.with(base, insightIds = emptyList()))
        diff(BriefFactsCopy.with(base, mode = Mode.Evening))
    }

    @Test fun hashIgnoresInsightOrder() {
        val a = BriefFactsCopy.with(base, insightIds = listOf("a", "b")); val b = BriefFactsCopy.with(base, insightIds = listOf("b", "a"))
        assertEquals(L.hash(a), L.hash(b))
    }

    // ---- validator ---------------------------------------------------------------------------------------------

    @Test fun acceptsThreeCleanBulletsWithKnownNumbers() {
        assertNull(problem("Load 54 so far, about usual for 14:00.", "Water 1.25 of 2.5 L, a glass would help.", "Next: Shift at 15:00, in 55 min."))
        assertNull(problem("You slept 6h 52m, a little under your need.", "Resting heart rate 53, close to your usual 54.", "A calm evening would suit you."))
    }

    @Test fun rejectsWrongBulletCount() {
        assertNotNull(L.problem(listOf("One.", "Two."), allowed, clocks))
        assertNotNull(L.problem(listOf("One.", "Two.", "Three.", "Four."), allowed, clocks))
        assertNotNull(L.problem(null, allowed, clocks))
    }

    @Test fun rejectsLongOrEmptyBullets() {
        assertNotNull(problem("a", "b", "x".repeat(91)))
        assertNull(problem("a", "b", "x".repeat(90)))
        assertNotNull(problem("a", " ", "c"))
    }

    @Test fun rejectsInventedNumbersAndClocks() {
        assertNotNull(problem("Load 99 so far.", "b", "c"))
        assertNotNull(problem("a", "Water 3.5 L today.", "c"))
        assertNotNull(problem("a", "b", "Next: Shift at 16:00."))
        assertNotNull(problem("a", "b", "Bed by 22:30."))
        assertNotNull(problem("a", "b", "That is 20% more."))
        assertNull(problem("a", "b", "Bed by 23:15."))
    }

    @Test fun rejectsExclamationForbiddenAndMedicalWords() {
        assertNotNull(problem("Great job!", "b", "c"))
        listOf("1.2 SD above your baseline", "your TRIMP is high", "ACWR looks fine", "z-score is low", "baseline is steady").forEach {
            assertNotNull(it, problem("a", it, "c"))
        }
        listOf("This may be an infection.", "See a doctor.", "A diagnosis is needed.", "Signs of disease.", "Your illness shows.").forEach {
            assertNotNull(it, problem("a", it, "c"))
        }
        assertNull(problem("a", "maybe illness, so keep it easy", "c"))
    }

    @Test fun replyParsing() {
        assertEquals(listOf("a", "b", "c"), L.parseReply("""{"bullets":["a","b","c"]}"""))
        assertEquals(listOf("a", "b", "c"), L.parseReply("```json\n{\"bullets\":[\"a\",\"b\",\"c\"]}\n```"))
        assertNull(L.parseReply("not json")); assertNull(L.parseReply(null)); assertNull(L.parseReply("""{"text":"x"}"""))
        assertTrue(L.accept(L.parseReply("""{"bullets":["Load 54 so far.","Water 1.25 L.","Shift at 15:00."]}"""), base, "14:05"))
    }

    @Test fun factsJsonHoldsOnlyKnownValues() {
        val j = L.factsJson(base, "14:05")
        assertEquals("day", j.getString("mode"))
        assertEquals(1.3, j.getJSONObject("water").getDouble("litres"), 1e-9)
        assertFalse(j.has("steps"))
        assertTrue(j.toString().length < 1800)
    }

    // ---- when to call -------------------------------------------------------------------------------------------

    private val now = 10_000_000L
    private fun cached(hash: String, src: String = "llm", age: Long = 60_000L) = L.Cached(hash, src, now - age)

    @Test fun planUsesTheCacheWhileTheHashMatches() {
        assertEquals(L.Plan.UseCache, L.plan(cached("h"), "h", hasKey = true, callsToday = 0, nowMs = now))
        assertEquals(L.Plan.UseCache, L.plan(cached("h"), "h", hasKey = false, callsToday = 9, nowMs = now))
    }

    @Test fun planGeneratesOnFirstOpenAndOnMaterialChange() {
        assertEquals(L.Plan.Generate, L.plan(null, "h", true, 0, now))
        assertEquals(L.Plan.Generate, L.plan(cached("old"), "h", true, 3, now))
    }

    @Test fun planFallsBackToRulesWithoutKeyOrAfterTheCap() {
        assertEquals(L.Plan.Template, L.plan(null, "h", false, 0, now))
        assertEquals(L.Plan.Template, L.plan(cached("old"), "h", true, L.MAX_CALLS_PER_DAY, now))
        assertEquals(L.Plan.Generate, L.plan(null, "h", true, L.MAX_CALLS_PER_DAY - 1, now))
        assertEquals(6, L.MAX_CALLS_PER_DAY)
    }

    @Test fun aFailedTemplateIsRetriedOnlyAfterThirtyMinutesAndWithinTheCap() {
        assertEquals(L.Plan.UseCache, L.plan(cached("h", "template", 10 * 60_000L), "h", true, 1, now))
        assertEquals(L.Plan.Generate, L.plan(cached("h", "template", 31 * 60_000L), "h", true, 1, now))
        assertEquals(L.Plan.UseCache, L.plan(cached("h", "template", 31 * 60_000L), "h", true, 6, now))
        assertEquals(L.Plan.UseCache, L.plan(cached("h", "template", 31 * 60_000L), "h", false, 0, now))
    }
}

/** Test helper: BriefFacts has no copy(), so rebuild it with a few fields changed. */
private object BriefFactsCopy {
    fun with(f: BriefFacts, mode: Mode = f.mode, readiness: Int? = f.readiness, sleepAsleepMin: Int? = f.sleepAsleepMin, loadSoFar: Int? = f.loadSoFar,
             waterMl: Int? = f.waterMl, nextEvent: BriefEvent? = f.nextEvent, insightIds: List<String> = f.insightIds) = BriefFacts(
        mode = mode, readiness = readiness, verdictKey = f.verdictKey, driverKey = f.driverKey, sleepAsleepMin = sleepAsleepMin, sleepNeedMin = f.sleepNeedMin,
        sleepScore = f.sleepScore, sleepDebtMin = f.sleepDebtMin, hrv = f.hrv, hrvUsual = f.hrvUsual, rhr = f.rhr, rhrUsual = f.rhrUsual, loadSoFar = loadSoFar,
        loadTypicalByNow = f.loadTypicalByNow, loadRatio = f.loadRatio, zoneMin = f.zoneMin, waterMl = waterMl, waterGoalMl = f.waterGoalMl, waterPace = f.waterPace,
        steps = f.steps, distanceKm = f.distanceKm, workouts = f.workouts, nextEvent = nextEvent, tomorrowFirst = f.tomorrowFirst, bedtime = f.bedtime,
        bedtimeForMin = f.bedtimeForMin, insightIds = insightIds, partial = f.partial)
}
