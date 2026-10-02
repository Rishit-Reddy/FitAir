package com.fitair.app.ui.components

/** Pure tier thresholds (docs/UI_REFINEMENT_075.md 1.4). Colour is applied by the screens via toneColor. */
object Tiers {
    fun readiness(score: Int?): Tone = when {
        score == null -> Tone.Neutral
        score >= 70 -> Tone.Good
        score >= 50 -> Tone.Caution
        else -> Tone.Alert
    }

    fun sleepScore(s: Double?): Tone = when {
        s == null -> Tone.Neutral
        s >= 75 -> Tone.Good
        s >= 60 -> Tone.Caution
        else -> Tone.Alert
    }

    /** Asleep vs the personal need: within 15 min under is Good, up to 60 min under Caution, beyond that Alert. */
    fun duration(asleepMin: Double?, needMin: Double?): Tone {
        if (asleepMin == null || needMin == null) return Tone.Neutral
        val diff = asleepMin - needMin
        return when {
            diff >= -15 -> Tone.Good
            diff >= -60 -> Tone.Caution
            else -> Tone.Alert
        }
    }

    fun efficiency(frac: Double?): Tone = when {
        frac == null -> Tone.Neutral
        frac >= 0.85 -> Tone.Good
        frac >= 0.75 -> Tone.Caution
        else -> Tone.Alert
    }

    fun deepRem(frac: Double?): Tone = when {
        frac == null -> Tone.Neutral
        frac >= 0.30 -> Tone.Good
        frac >= 0.20 -> Tone.Caution
        else -> Tone.Alert
    }

    fun regularity(sdMin: Double?): Tone = when {
        sdMin == null -> Tone.Neutral
        sdMin <= 30 -> Tone.Good
        sdMin <= 60 -> Tone.Caution
        else -> Tone.Alert
    }
}
