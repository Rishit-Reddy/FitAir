package com.fitair.app

import com.fitair.app.ui.components.Tiers
import com.fitair.app.ui.components.Tone
import org.junit.Assert.assertEquals
import org.junit.Test

class TiersTest {
    @Test fun readiness() {
        assertEquals(Tone.Good, Tiers.readiness(70)); assertEquals(Tone.Caution, Tiers.readiness(69))
        assertEquals(Tone.Caution, Tiers.readiness(50)); assertEquals(Tone.Alert, Tiers.readiness(49))
        assertEquals(Tone.Neutral, Tiers.readiness(null))
    }

    @Test fun sleepScore() {
        assertEquals(Tone.Good, Tiers.sleepScore(75.0)); assertEquals(Tone.Caution, Tiers.sleepScore(74.9))
        assertEquals(Tone.Caution, Tiers.sleepScore(60.0)); assertEquals(Tone.Alert, Tiers.sleepScore(59.9))
        assertEquals(Tone.Neutral, Tiers.sleepScore(null))
    }

    @Test fun duration() {
        assertEquals(Tone.Good, Tiers.duration(437.0, 452.0))      // -15
        assertEquals(Tone.Caution, Tiers.duration(436.0, 452.0))   // -16
        assertEquals(Tone.Caution, Tiers.duration(392.0, 452.0))   // -60
        assertEquals(Tone.Alert, Tiers.duration(391.0, 452.0))     // -61
        assertEquals(Tone.Good, Tiers.duration(500.0, 452.0))
        assertEquals(Tone.Neutral, Tiers.duration(null, 452.0)); assertEquals(Tone.Neutral, Tiers.duration(400.0, null))
    }

    @Test fun efficiency() {
        assertEquals(Tone.Good, Tiers.efficiency(0.85)); assertEquals(Tone.Caution, Tiers.efficiency(0.849))
        assertEquals(Tone.Caution, Tiers.efficiency(0.75)); assertEquals(Tone.Alert, Tiers.efficiency(0.749))
        assertEquals(Tone.Neutral, Tiers.efficiency(null))
    }

    @Test fun deepRem() {
        assertEquals(Tone.Good, Tiers.deepRem(0.30)); assertEquals(Tone.Caution, Tiers.deepRem(0.299))
        assertEquals(Tone.Caution, Tiers.deepRem(0.20)); assertEquals(Tone.Alert, Tiers.deepRem(0.199))
        assertEquals(Tone.Neutral, Tiers.deepRem(null))
    }

    @Test fun regularity() {
        assertEquals(Tone.Good, Tiers.regularity(30.0)); assertEquals(Tone.Caution, Tiers.regularity(31.0))
        assertEquals(Tone.Caution, Tiers.regularity(60.0)); assertEquals(Tone.Alert, Tiers.regularity(61.0))
        assertEquals(Tone.Neutral, Tiers.regularity(null))
    }
}
