package com.fitair.app.ui.today

import com.fitair.app.integrations.calendar.CalEvent
import com.fitair.app.ui.components.CardData
import com.fitair.app.ui.components.Chip
import com.fitair.app.ui.components.Tone
import com.fitair.app.data.metrics.MetricId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class TodayLinesTest {
    private val z = ZoneId.of("UTC")
    private val t = Instant.parse("2026-10-03T10:00:00Z")
    private fun ev(title: String, allDay: Boolean = false) = CalEvent(1, 1, title, t, t.plusSeconds(3600), allDay, true, null)

    @Test fun tasksShowCountFirstTitleAndRest() =
        assertEquals("4 tasks today · Dockerize the project +3", TodayLines.tasks(listOf("Dockerize the project", "B", "C", "D").map { ev(it, true) }))
    @Test fun oneTaskIsSingular() = assertEquals("1 task today · A", TodayLines.tasks(listOf(ev("A", true))))
    @Test fun noTasksIsNull() = assertNull(TodayLines.tasks(listOf(ev("Meeting"))))

    @Test fun tomorrowSummarisesEvents() =
        assertEquals("Tomorrow: first 10:00 · 2 events · 1 task", TodayLines.tomorrow(listOf(ev("a"), ev("b"), ev("c", true)), z))
    @Test fun emptyTomorrowIsNull() = assertNull(TodayLines.tomorrow(emptyList(), z))

    private fun card(id: MetricId, value: String?, sub: String? = null, chip: Chip? = null) = CardData(id, id.name, value, null, sub, null, chip)
    @Test fun dayVerdictIsReadiness() =
        assertEquals("Readiness 72 · Ready", TodayLines.verdict(false, card(MetricId.Readiness, "72", chip = Chip("Ready", Tone.Good)), null))
    @Test fun eveningVerdictIsBedtime() =
        assertEquals("Bed by 01:00 · for 7h 1m · Short on sleep", TodayLines.verdict(true, null, card(MetricId.Bedtime, "01:00", "for 7h 1m", Chip("Short on sleep", Tone.Caution))))
    @Test fun noValueNoVerdict() = assertNull(TodayLines.verdict(false, card(MetricId.Readiness, null), null))

    @Test fun shadeRisesWithIntensity() {
        assertEquals(0.4f, StripMath.shade(null)); assertEquals(0.35f, StripMath.shade(0)); assertEquals(1f, StripMath.shade(80))
    }
    @Test fun stripFractionIsClamped() {
        assertEquals(0.5f, StripMath.frac(12 * 3_600_000L, 0)); assertEquals(1f, StripMath.frac(99 * 3_600_000L, 0))
    }
}
