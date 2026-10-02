package com.fitair.app

import com.fitair.app.analytics.ReadinessView
import org.junit.Assert.*
import org.junit.Test

class ReadinessViewTest {
    private val json = """{"date":"2026-10-02","readiness_version":2,"score":54,
      "drivers":[{"key":"hrv","label":"HRV","score":40.0,"weight_pct":45,"impact":-3.1,"text":"HRV 38 ms, 1.4 SD below baseline"},
                 {"key":"sleep","label":"Sleep","score":70.0,"weight_pct":55,"impact":-1.0,"text":"Sleep score 70"}],
      "missing":{"subjective":"no check-in for this date"},"notes":[]}"""

    @Test fun parsesV2() {
        val v = ReadinessView.parse(json)!!
        assertEquals(54, v.score); assertEquals(2, v.version)
        assertEquals("HRV 38 ms, 1.4 SD below baseline", v.mainDriver)
        assertEquals(100, v.drivers.sumOf { it.weightPct })
        assertEquals("How you feel", v.missing.single().first)
    }

    @Test fun nullAndGarbage() {
        assertNull(ReadinessView.parse(null)); assertNull(ReadinessView.parse("null")); assertNull(ReadinessView.parse("{oops"))
    }

    @Test fun noScoreKeepsNote() {
        val v = ReadinessView.parse("""{"readiness_version":2,"score":null,"drivers":[],"notes":["Not enough data"]}""")!!
        assertNull(v.score); assertEquals("Not enough data", v.note); assertNull(v.mainDriver)
    }
}
