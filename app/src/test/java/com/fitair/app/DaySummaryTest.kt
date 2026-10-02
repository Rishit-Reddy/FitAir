package com.fitair.app

import com.fitair.app.coach.DayFacts
import com.fitair.app.coach.DaySummaryLogic
import com.fitair.app.coach.DaySummaryLogic.Usual
import org.junit.Assert.*
import org.junit.Test

class DaySummaryTest {
    private val f = DayFacts("2026-10-01", steps = 8234, distanceM = 6120.0, cardio = 54.3, zoneMin = 22, waterMl = 2100, waterGoalMl = 2500,
        rhr = 56.0, hrAvg = 74.0, hrMax = 152.0, workouts = listOf("Pickleball 1h 10m"), partial = false)
    private val u = Usual(steps = 9100, cardio = 48.0)
    private val ok = DaySummaryLogic.allowed(f, u)
    private fun accept(t: String?) = DaySummaryLogic.accept(t, ok)

    @Test fun acceptsTextQuotingOnlyKnownNumbers() = listOf(
        "A steady day: 8,234 steps and 2.1 of 2.5 L of water.",
        "About 8.2k steps, a little under your usual.",
        "You covered 6.1 km and drank 2.1 L.",
        "Pickleball for 1h 10m and a load of 54, close to your usual 48.",
        "Resting 56, average 74, peak 152 today.",
        "A calm, steady day with water nearly there. An early night would help tomorrow.",   // no numbers at all
    ).forEach { assertTrue(it, accept(it)) }

    @Test fun rejectsInventedNumbers() = listOf(
        "You walked 12,000 steps today.",
        "Water reached 3.5 L.",
        "Your load was 99, much higher than usual.",
        "Resting heart rate 61 is fine.",
        "About 15k steps.",
        "Sleep by 22:30 tonight.",             // clock time quotes unknown numbers
        "You were 20% under.",
    ).forEach { assertFalse(it, accept(it)) }

    @Test fun rejectsWrongToneAndShape() {
        assertFalse(accept("Great job!"))
        assertFalse(accept("This may indicate an infection."))
        assertFalse(accept(""))
        assertFalse(accept(null))
        assertFalse(accept(List(90) { "word" }.joinToString(" ")))
    }

    @Test fun parseReplyFallsBackToNullOnSchemaErrors() {
        assertEquals("Hello there.", DaySummaryLogic.parseReply("""{"text":"Hello there."}"""))
        assertEquals("Hi.", DaySummaryLogic.parseReply("```json\n{\"text\":\"Hi.\"}\n```"))
        assertNull(DaySummaryLogic.parseReply("not json"))
        assertNull(DaySummaryLogic.parseReply("""{"other":"x"}"""))
        assertNull(DaySummaryLogic.parseReply(null))
        assertNull(DaySummaryLogic.parseReply("""{"text":"  "}"""))
        // a model error or garbage means the template is used: accept(parseReply(..)) is false
        assertFalse(accept(DaySummaryLogic.parseReply("<html>503</html>")))
    }

    @Test fun numberExtraction() {
        val n = DaySummaryLogic.numbersIn("8,234 steps, 2.1 L, 10.2k, 1h 05")
        assertEquals(listOf(8234.0, 2.1, 10200.0, 1.0, 5.0), n.map { it.value })
    }

    @Test fun factsJsonHasOnlyAggregates() {
        val j = DaySummaryLogic.factsJson(f, u)
        assertEquals(8234L, j.getLong("steps")); assertEquals(6.1, j.getDouble("distance_km"), 1e-9)
        assertEquals(2.1, j.getDouble("water_litres"), 1e-9); assertEquals(9100L, j.getJSONObject("usual").getLong("steps"))
        assertTrue(j.toString().length < 700)    // stays inside the ~700 input token budget by a wide margin
    }
}
