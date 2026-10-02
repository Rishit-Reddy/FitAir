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

    @Test fun parsesComponentRawFields() {
        val r = JSONObject("""{"days":[{"date":"2026-10-01","sleep_min":285,"sleep_score":58,"sleep_breakdown":{"need_min":452,"components":{
          "duration":{"score":63,"asleep_min":285,"need_min":452},
          "efficiency":{"score":79,"efficiency":0.85,"awake_min":50},
          "restorative":{"score":70,"deep_rem_fraction":0.346,"deep_min":45,"rem_min":70},
          "consistency":{"score":80,"midpoint_sd_min":25,"nights":7}}}}]}""")
        val n = SleepModel.parseNights(r, LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-01"))[0]
        assertEquals(50.0, n.awakeMin!!, 0.0); assertEquals(45.0, n.deepMin!!, 0.0); assertEquals(70.0, n.remMin!!, 0.0)
        assertEquals(0.346, n.deepRemFrac!!, 1e-9); assertEquals(25.0, n.midpointSdMin!!, 0.0); assertEquals(7, n.regNights)
        val c = SleepModel.componentCards(n)
        assertEquals(listOf("Duration", "Efficiency", "Deep + REM", "Regularity"), c.map { it.name })
        assertEquals(listOf("4h 45m", "85%", "35%", "±25m"), c.map { it.headline })
        assertEquals("2h 47m under need", c[0].reading)
        assertEquals("Awake 50m while in bed", c[1].reading)
        assertEquals("Deep 45m · REM 1h 10m", c[2].reading)
        assertEquals("Timing shift over 7 nights", c[3].reading)
        assertEquals(63.0, c[0].score!!, 0.0)
        assertEquals(listOf(Tone.Alert, Tone.Good, Tone.Good, Tone.Good), c.map { it.tone })
    }

    @Test fun missingComponentAndNeedReadings() {
        val n = Night(LocalDate.parse("2026-10-01"), 460.0, 80.0, 452.0, null, emptyMap(), null)
        val c = SleepModel.componentCards(n)
        assertEquals("Met your need", c[0].reading)
        assertEquals("—", c[3].headline); assertNull(c[3].score); assertEquals(Tone.Neutral, c[3].tone)
        assertEquals("Needs 4+ nights this week", c[3].reading); assertEquals("No stage data", c[1].reading)
        val over = Night(LocalDate.parse("2026-10-01"), 484.0, 80.0, 452.0, null, emptyMap(), null)
        assertEquals("32m over need", SleepModel.componentCards(over)[0].reading)
    }

    @Test fun durationTonesUseEachNightsNeed() {
        fun n(m: Double?, need: Double?) = Night(LocalDate.parse("2026-10-01"), m, m?.let { 70.0 }, need, null, emptyMap(), null)
        assertEquals(listOf(Tone.Good, Tone.Caution, Tone.Alert, Tone.Neutral),
            SleepModel.durationTones(listOf(n(440.0, 450.0), n(400.0, 450.0), n(300.0, 450.0), n(null, 450.0))))
        assertEquals(listOf(Tone.Good, Tone.Alert), SleepModel.durationTones(listOf(n(400.0, 400.0), n(400.0, 480.0))))
        assertEquals(listOf(Tone.Caution, Tone.Neutral), SleepModel.scoreTones(listOf(n(400.0, 400.0), n(null, 400.0))))
    }
}
