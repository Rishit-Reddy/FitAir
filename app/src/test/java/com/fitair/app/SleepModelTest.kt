package com.fitair.app

import com.fitair.app.ui.components.Tone
import com.fitair.app.ui.sleep.Night
import com.fitair.app.ui.sleep.SleepModel
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class SleepModelTest {
    private val resp = JSONObject("""{"days":[
      {"date":"2026-10-01","sleep_min":420,"sleep_score":80,"sleep_breakdown":{"need_min":450,"sleep_debt_min":90,"score":80,
        "components":{"duration":{"score":90},"efficiency":{"score":70,"efficiency":0.91}}}},
      {"date":"2026-10-02","sleep_min":null,"sleep_score":null,"sleep_breakdown":null}]}""")

    @Test fun mapsAndFillsMissingDates() {
        val n = SleepModel.parseNights(resp, LocalDate.parse("2026-09-30"), LocalDate.parse("2026-10-02"))
        assertEquals(3, n.size)
        assertFalse(n[0].hasData)
        assertEquals(420.0, n[1].sleepMin!!, 0.0)
        assertEquals(90.0, n[1].components["duration"]!!, 0.0)
        assertEquals(0.91, n[1].efficiency!!, 1e-9)
        assertNull(n[2].sleepMin); assertNull(n[2].score)
        assertEquals(listOf(null, 7f, null), SleepModel.durationHours(n))
    }

    @Test fun aggregates() {
        val n = SleepModel.parseNights(resp, LocalDate.parse("2026-09-30"), LocalDate.parse("2026-10-02"))
        assertEquals(420.0, SleepModel.avgSleepMin(n)!!, 0.0)
        assertEquals(80.0, SleepModel.avgScore(n)!!, 0.0)
        assertEquals(90.0, SleepModel.latestDebtMin(n)!!, 0.0)
        assertEquals(450.0, SleepModel.needMin(n)!!, 0.0)
        assertNull(SleepModel.avgSleepMin(emptyList()))
        assertEquals(2, SleepModel.lastN(n, 2).size)
    }

    @Test fun toneThresholds() {
        assertEquals(Tone.Neutral, SleepModel.tone(null))
        assertEquals(Tone.Good, SleepModel.tone(75.0))
        assertEquals(Tone.Caution, SleepModel.tone(60.0))
        assertEquals(Tone.Alert, SleepModel.tone(59.0))
    }

    @Test fun stageGrouping() {
        val s = SleepModel.stageMinutes(mapOf(1 to 10.0, 7 to 5.0, 4 to 200.0, 2 to 20.0, 6 to 90.0, 5 to 60.0, 0 to 99.0))
        assertEquals(15.0, s.awake, 0.0); assertEquals(220.0, s.light, 0.0)
        assertEquals(90.0, s.rem, 0.0); assertEquals(60.0, s.deep, 0.0)
        assertTrue(SleepModel.stageMinutes(emptyMap()).isEmpty)
    }

    @Test fun defaultNightAndHeader() {
        fun n(d: String, min: Double?) = Night(LocalDate.parse(d), min, null, null, null, emptyMap(), null)
        val nights = listOf(n("2026-09-30", 400.0), n("2026-10-01", 420.0), n("2026-10-02", null))
        assertEquals(LocalDate.parse("2026-10-01"), SleepModel.defaultNight(nights))
        assertNull(SleepModel.defaultNight(listOf(n("2026-10-02", null))))
        assertNull(SleepModel.defaultNight(emptyList()))
        val today = LocalDate.parse("2026-10-02")
        assertEquals("Last night", SleepModel.headerLabel(today, today))
        assertEquals("Wed 30 Sep", SleepModel.headerLabel(today.minusDays(2), today))
    }
}
