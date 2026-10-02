package com.fitair.app

import com.fitair.app.coach.BriefEvent
import com.fitair.app.coach.BriefFacts
import com.fitair.app.coach.DayFacts
import com.fitair.app.ui.today.Mode
import com.fitair.app.ui.trends.Band
import com.fitair.app.data.dao.Pace
import com.fitair.app.ui.components.Tone
import com.fitair.app.ui.copy.Copy
import org.junit.Assert.*
import org.junit.Test

class CopyTest {
    private val forbidden = listOf(Regex("""\bSD\b"""), Regex("baseline", RegexOption.IGNORE_CASE), Regex("""\bz\b""", RegexOption.IGNORE_CASE),
        Regex("TRIMP", RegexOption.IGNORE_CASE), Regex("ACWR", RegexOption.IGNORE_CASE), Regex("""z-score""", RegexOption.IGNORE_CASE))

    private fun clean(s: String?) {
        if (s == null) return
        forbidden.forEach { assertFalse("'$s' contains ${it.pattern}", it.containsMatchIn(s)) }
    }

    @Test fun readinessBandEdges() {
        assertEquals("Well recovered", Copy.readiness(70).headline); assertEquals("Partly recovered", Copy.readiness(69).headline)
        assertEquals("Partly recovered", Copy.readiness(50).headline); assertEquals("Not recovered", Copy.readiness(49).headline)
        assertEquals("a hard day is fine", Copy.readiness(90).detail)
        assertEquals("No readiness yet", Copy.readiness(null).headline)
        assertEquals(Tone.Good, Copy.readiness(70).tone); assertEquals(Tone.Caution, Copy.readiness(55).tone); assertEquals(Tone.Alert, Copy.readiness(10).tone)
    }

    @Test fun driverBands() {
        assertEquals("Recovery signal is strong for you", Copy.driver("hrv", 80.0).headline)
        assertEquals("Recovery signal is normal for you", Copy.driver("hrv", 79.9).headline)
        assertEquals("Recovery signal is normal for you", Copy.driver("hrv", 60.0).headline)
        assertEquals("Recovery signal is low for you", Copy.driver("hrv", 59.9).headline)
        assertEquals("Resting heart rate is low for you", Copy.driver("resting_hr", 85.0).headline)
        assertEquals("Resting heart rate is high for you", Copy.driver("resting_hr", 40.0).headline)
        assertEquals("Good night", Copy.driver("sleep", 75.0).headline); assertEquals("OK night", Copy.driver("sleep", 74.0).headline)
        assertEquals("OK night", Copy.driver("sleep", 60.0).headline); assertEquals("Poor night", Copy.driver("sleep", 59.0).headline)
        assertEquals("You said you feel good", Copy.driver("subjective", 80.0).headline)
        assertEquals("You said you feel okay", Copy.driver("subjective", 62.5).headline)
        assertEquals("You said you feel rough", Copy.driver("subjective", 20.0).headline)
        assertEquals("Harder week than usual", Copy.driver("load", 60.0, "Training load ratio 1.42 (acute 7d vs chronic)").headline)
    }

    @Test fun loadVerdictEdges() {
        assertEquals("Lighter week than usual", Copy.load(0.79).headline)
        assertEquals("Normal week for you", Copy.load(0.8).headline); assertEquals("Normal week for you", Copy.load(1.3).headline)
        assertEquals("Harder week than usual", Copy.load(1.31).headline); assertEquals("Harder week than usual", Copy.load(1.5).headline)
        assertEquals("Much harder than usual: ease off", Copy.load(1.51).headline)
        assertEquals("Not enough history yet", Copy.load(null).headline)
    }

    @Test fun loadSoFar() {
        assertEquals("About usual for this time", Copy.loadSoFar(54.0, 50.0, false).headline)
        assertEquals("Lighter than usual so far", Copy.loadSoFar(20.0, 50.0, false).headline)
        assertEquals("Heavier than usual so far", Copy.loadSoFar(80.0, 50.0, false).headline)
        assertEquals("Quiet so far", Copy.loadSoFar(0.0, 0.0, false).headline)
        assertEquals("Building your usual", Copy.loadSoFar(10.0, null, false).headline)
        assertEquals("54 · partial day", Copy.loadSoFar(54.0, 50.0, true).detail)
    }

    @Test fun vitals() {
        val n = Copy.vital(true, 46.0, 52.0, 2.0)
        assertEquals("Lower than usual", n.headline); assertEquals("46 ms · usual 52", n.detail); assertEquals("▼", n.glyph); assertEquals(Tone.Caution, n.tone)
        assertEquals("Normal for you", Copy.vital(true, 53.0, 52.0, 2.0).headline)
        assertEquals("Higher than usual", Copy.vital(true, 60.0, 52.0, 2.0).headline); assertEquals(Tone.Good, Copy.vital(true, 60.0, 52.0, 2.0).tone)
        val r = Copy.vital(false, 58.0, 55.0, 1.0)
        assertEquals("A bit high", r.headline); assertEquals("58 bpm · usual 55", r.detail); assertEquals(Tone.Caution, r.tone)
        assertEquals("Lower than usual", Copy.vital(false, 50.0, 55.0, 1.0).headline)
        assertEquals("Not enough history yet", Copy.vital(true, 50.0, null, 2.0).headline)
    }

    @Test fun sleepWords() {
        assertEquals("2h 47m less than you need", Copy.needDiff(-167)); assertEquals("Met your need", Copy.needDiff(-15)); assertEquals("Met your need", Copy.needDiff(15))
        assertEquals("32m more than you need", Copy.needDiff(32)); assertEquals("45m less than you need", Copy.needDiff(-45)); assertNull(Copy.needDiff(null))
        assertNull(Copy.sleepDebt(59.0)); assertEquals("Short on sleep", Copy.sleepDebt(130.0)!!.headline)
        assertEquals("2h 10m over 7 nights", Copy.sleepDebt(130.0)!!.detail)
        assertEquals("Slept 7h 12m · Good night", Copy.sleepLine(432, 80.0)); assertEquals("Slept 7h 12m", Copy.sleepLine(432, null))
        assertEquals(listOf("Enough sleep", "Restful", "Deep + dream sleep", "Same-time sleep"), Copy.SLEEP_COMPONENTS.map { it.second })
    }

    @Test fun waterWords() {
        assertEquals("1.25 of 2.5 L", Copy.waterLine(1250, 2500)); assertEquals("0 of 2.5 L", Copy.waterLine(0, 2500))
        assertEquals("On pace", Copy.waterPace(Pace.OnPace, 0)); assertEquals("A glass would help", Copy.waterPace(Pace.Behind, 0))
        assertEquals("Goal reached", Copy.waterPace(Pace.Reached, 0)); assertEquals("+0.5 L for a hard day", Copy.waterPace(Pace.OnPace, 500))
    }

    @Test fun insights() {
        assertEquals("Sleep is catching up on you", Copy.insight("sleep_debt", "Sleep debt building up"))
        assertEquals("Much harder than usual: ease off", Copy.insight("acwr_high", "x"))
        assertEquals("Your body looks under strain", Copy.insight("strain_pattern", "x"))
        assertEquals("Unknown title", Copy.insight("nope", "Unknown title"))
    }

    @Test fun explainersAreShortAndPlain() {
        for (k in listOf("readiness", "hrv", "resting_hr", "sleep", "sleep_need", "load", "subjective")) {
            val t = Copy.explain(k)!!
            assertTrue("$k: ${t.length}", t.split(Regex("""(?<=[.!?])\s+""")).size <= 2)
            clean(t); clean(Copy.explainMore(k))
        }
        assertTrue(Copy.explain("hrv")!!.contains("milliseconds"))
        assertNull(Copy.explain("nope"))
    }

    private fun facts(steps: Long = 8000, cardio: Double = 54.0, water: Int = 2100, workouts: List<String> = emptyList(), hasData: Boolean = true) =
        DayFacts("2026-10-02", steps, 6500.0, cardio, 32, water, 2500, 56.0, 74.0, 152.0, workouts, false, hasData)

    @Test fun daySummaryTemplateHasNoInventedNumbers() {
        val variants = listOf(facts(), facts(water = 2500), facts(water = 500), facts(workouts = listOf("Pickleball 1h 10m")), facts(cardio = 0.0, water = 0),
            facts(workouts = listOf("Run"), water = 2600))
        for (f in variants) {
            val t = Copy.daySummary(f)
            assertFalse("digits in '$t'", t.any { it.isDigit() }); assertFalse(t.contains('!'))
            assertTrue(t.split(Regex("""(?<=[.])\s+""")).size in 2..3)
            clean(t)
        }
        assertEquals("Not enough data from today yet.", Copy.daySummary(facts(hasData = false)))
        assertTrue(Copy.daySummary(facts()).endsWith("An early night would help tomorrow."))
    }

    @Test fun windDownLine() {
        assertEquals("For your usual 7h 30m, be in bed by 23:15", Copy.windDown(450, 23 * 60 + 15, false))
        assertTrue(Copy.windDown(450, 22 * 60 + 45, true).contains("short on sleep"))
    }

    @Test fun nothingForbiddenAndHeadlinesShort() {
        val all = ArrayList<String?>()
        fun v(x: com.fitair.app.ui.copy.Verdict) { all += x.headline; all += x.detail; assertTrue(x.headline, x.headline.length <= 48) }
        for (s in listOf(null) + (0..100 step 5).toList()) v(Copy.readiness(s))
        val raw = "HRV 70 ms, 0.5 SD below baseline; Training load ratio 1.42; z=-1.2 TRIMP ACWR"
        for (k in listOf("hrv", "resting_hr", "sleep", "load", "subjective", "other")) for (sc in 0..100 step 5) v(Copy.driver(k, sc.toDouble(), raw))
        for (r in listOf(null, 0.5, 0.8, 1.0, 1.3, 1.4, 1.6, 2.5)) v(Copy.load(r))
        for (a in listOf(0.0, 10.0, 60.0)) for (b in listOf(null, 0.0, 50.0)) v(Copy.loadSoFar(a, b, true))
        for (h in listOf(true, false)) for (x in listOf(30.0, 50.0, 80.0)) v(Copy.vital(h, x, 50.0, 2.0))
        for (s in listOf(null, 30.0, 65.0, 90.0)) v(Copy.sleepNight(s))
        for (id in listOf("rhr_elevated", "hrv_low", "sleep_debt", "acwr_high", "acwr_low", "strain_pattern", "sleep_timing_drift")) all += Copy.insight(id, "raw")
        all += Copy.OUT_OF_100; all += Copy.BAND_NOTE; all += Copy.SLEEP_WAITING; all += Copy.SLEEP_NONE
        all += Copy.trendVerdict("Recovery signal", 0, true); all += Copy.trendVerdict("Recovery signal", -1, true); all += Copy.trendVerdict("Resting heart rate", 1, false)
        all.forEach { clean(it) }
    }

    // ---- 0.9.0: summary bullets and chips ------------------------------------------------------------------------

    private val medical = Regex("diagnos|disease|infection|doctor", RegexOption.IGNORE_CASE)

    private fun briefFixtures(): List<BriefFacts> = listOf(
        BriefFacts(Mode.Morning), BriefFacts(Mode.Day), BriefFacts(Mode.Evening),
        BriefFacts(Mode.Morning, readiness = 74, verdictKey = "well", driverKey = "sleep", sleepAsleepMin = 412, sleepNeedMin = 457, sleepDebtMin = 130,
            nextEvent = BriefEvent("Shift", "15:00", 55)),
        BriefFacts(Mode.Morning, readiness = 41, verdictKey = "not", driverKey = "hrv", sleepAsleepMin = 300, sleepNeedMin = 300,
            nextEvent = BriefEvent("A very long meeting title with many words", "08:00", null)),
        BriefFacts(Mode.Day, loadSoFar = 54, loadTypicalByNow = 50, waterMl = 1250, waterGoalMl = 2500, waterPace = "behind", nextEvent = BriefEvent("Shift", "15:00", 55)),
        BriefFacts(Mode.Day, loadSoFar = 5, loadTypicalByNow = null, waterMl = 2500, waterGoalMl = 2500, waterPace = "reached"),
        BriefFacts(Mode.Evening, loadSoFar = 80, loadTypicalByNow = 50, workouts = 1, waterMl = 2100, waterGoalMl = 2500, steps = 8234,
            tomorrowFirst = BriefEvent("Shift", "08:00", null), bedtime = "23:15", bedtimeForMin = 450),
        BriefFacts(Mode.Evening, loadSoFar = 20, loadTypicalByNow = 50, bedtime = "23:15", bedtimeForMin = 450),
    )

    @Test fun briefIsAlwaysThreeShortCleanBullets() {
        briefFixtures().forEach { f ->
            val b = Copy.brief(f.mode, f)
            assertEquals("${f.mode}: $b", 3, b.size)
            b.forEach {
                assertTrue("empty bullet in $b", it.isNotBlank())
                assertTrue("'$it' is ${it.length} chars", it.length <= 90)
                assertFalse("'$it' has !", it.contains('!'))
                assertFalse("'$it' is medical", medical.containsMatchIn(it))
                clean(it)
            }
        }
    }

    @Test fun briefMentionsOnlyFactsItWasGiven() {
        val b = Copy.brief(Mode.Morning, briefFixtures()[3])
        assertTrue(b[0], b[0].startsWith("Well recovered at 74"))
        assertTrue(b[1], b[1].contains("6h 52m") && b[1].contains("45m short"))
        assertTrue(b[2], b[2].contains("Shift at 15:00"))
        val e = Copy.brief(Mode.Evening, briefFixtures()[7])
        assertTrue(e[2], e[2].contains("08:00") && e[2].contains("23:15"))
    }

    @Test fun chipsUseOwnNormalWithDotAndWords() {
        assertEquals("Well recovered", Copy.chipReadiness(74)!!.text); assertEquals(Tone.Good, Copy.chipReadiness(74)!!.tone)
        assertEquals("Partly recovered", Copy.chipReadiness(60)!!.text); assertEquals(Tone.Alert, Copy.chipReadiness(30)!!.tone)
        assertNull(Copy.chipReadiness(null))
        assertEquals("Need met", Copy.chipSleep(450.0, 457.0)!!.text)
        assertEquals("45m short", Copy.chipSleep(412.0, 457.0)!!.text); assertEquals(Tone.Caution, Copy.chipSleep(412.0, 457.0)!!.tone)
        assertEquals("1h 30m short", Copy.chipSleep(360.0, 450.0)!!.text); assertEquals(Tone.Alert, Copy.chipSleep(360.0, 450.0)!!.tone)
        assertEquals("Learning your normal · 9/14", Copy.chipResting(53.0, null, 9)!!.text)
        val b = Band(55.0, 2.0)
        assertEquals("Usual for you", Copy.chipResting(56.0, b, 20)!!.text)
        assertEquals("A bit high", Copy.chipResting(58.0, b, 20)!!.text); assertEquals("High for you", Copy.chipResting(60.0, b, 20)!!.text)
        assertEquals("Low", Copy.chipResting(50.0, b, 20)!!.text); assertEquals(Tone.Good, Copy.chipResting(50.0, b, 20)!!.tone)
        val h = Band(46.0, 5.0)
        assertEquals("Usual for you", Copy.chipHrv(46.0, h, 20)!!.text); assertEquals("Lower than usual", Copy.chipHrv(40.0, h, 20)!!.text)
        assertEquals("Low for you", Copy.chipHrv(30.0, h, 20)!!.text); assertEquals("Strong", Copy.chipHrv(60.0, h, 20)!!.text)
    }

    @Test fun loadChipEdges() {
        assertEquals("Lighter week", Copy.chipLoad(0.79)!!.text); assertEquals(Tone.Neutral, Copy.chipLoad(0.79)!!.tone)
        assertEquals("Normal week", Copy.chipLoad(0.8)!!.text); assertEquals("Normal week", Copy.chipLoad(1.3)!!.text)
        assertEquals("Harder week", Copy.chipLoad(1.31)!!.text); assertEquals("Harder week", Copy.chipLoad(1.5)!!.text)
        assertEquals("Much harder", Copy.chipLoad(1.51)!!.text); assertEquals(Tone.Alert, Copy.chipLoad(1.51)!!.tone)
        assertNull(Copy.chipLoad(null))
    }

    @Test fun heartZoneNamesAndAccessibleSentence() {
        val z = floatArrayOf(100f, 120f, 150f, 170f)
        assertEquals("Resting", Copy.heartZone(84, z)); assertEquals("Light", Copy.heartZone(112, z)); assertEquals("Peak", Copy.heartZone(171, z))
        assertNull(Copy.heartZone(null, z)); assertNull(Copy.heartZone(80, null))
        assertEquals("Resting heart rate, 53 beats per minute, usual for you. Opens details.",
            Copy.cardA11y("Resting heart rate", "53 beats per minute", Copy.chipResting(56.0, Band(55.0, 2.0), 20)))
        assertEquals("Weight, no data yet. Opens details.", Copy.cardA11y("Weight", null, null))
    }

    @Test fun chipsAndBulletsNeverUseJargon() {
        listOf(Copy.chipLearning(3), Copy.CHIP_PARTIAL, Copy.CHIP_NO_DATA, Copy.chipWeight(-3.0, "12 Sep")!!, Copy.chipLoadSoFar(50.0, 40.0, false)).forEach { clean(it.text) }
        assertEquals("Down 3.0 kg since 12 Sep", Copy.chipWeight(-3.0, "12 Sep")!!.text)
    }
}
