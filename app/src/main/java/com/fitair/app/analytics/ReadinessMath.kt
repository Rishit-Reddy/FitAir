package com.fitair.app.analytics

import com.fitair.app.core.Num

/**
 * Readiness v3 arithmetic (pure; v3 = the load component is the whole-day cardio load ratio). Five components, nominal weights sum to 1.0:
 *  sleep (the sleep score; it already contains duration) 0.30, hrv 0.25, resting_hr 0.15,
 *  load (whole-day cardio load, acute/chronic ratio) 0.15, subjective (check-in mean) 0.15.
 * Missing components are dropped and the rest renormalised. Needs at least 2 components.
 */
object ReadinessMath {
    const val VERSION = 3
    const val MIN_COMPONENTS = 2
    val WEIGHTS = linkedMapOf("sleep" to 0.30, "hrv" to 0.25, "resting_hr" to 0.15, "load" to 0.15, "subjective" to 0.15)
    val LABELS = mapOf("sleep" to "Sleep", "hrv" to "HRV", "resting_hr" to "Resting HR",
        "load" to "Training load", "subjective" to "How you feel")

    fun weightsSum(): Double = WEIGHTS.values.sum()

    /** z-score with the same noise floors as before: max(sd, 1.0, floorRel * mean). */
    fun z(x: Double, mean: Double, sd: Double, floorRel: Double) = (x - mean) / maxOf(sd, 1.0, floorRel * mean)

    /** sign +1: higher is better (HRV); -1: lower is better (resting HR). */
    fun zScore(z: Double, sign: Int) = Num.clip(75 + 25 * sign * z)

    /** 0.8-1.3 is the comfortable ACWR range. */
    fun acwrScore(a: Double) = Num.clip(if (a > 1.3) 100 - (a - 1.3) * 200 else if (a < 0.8) 100 - (0.8 - a) * 100 else 100.0)

    /** Check-in mean on 1..5 (5 = best) to 0..100. */
    fun subjectiveScore(mean15: Double) = Num.clip((mean15 - 1) / 4 * 100)

    class Combined(val score: Int, val weights: Map<String, Double>, val impact: Map<String, Double>) {
        /** Component keys, largest absolute impact first. */
        val ranked: List<String> get() = impact.keys.sortedBy { -Math.abs(impact.getValue(it)) }
    }

    /** Weighted mean of [scores] (renormalised); null with fewer than [MIN_COMPONENTS]. */
    fun combine(scores: Map<String, Double>): Combined? {
        if (scores.size < MIN_COMPONENTS) return null
        val wsum = scores.keys.sumOf { WEIGHTS.getValue(it) }
        val weights = scores.keys.associateWith { WEIGHTS.getValue(it) / wsum }
        val score = Math.rint(scores.entries.sumOf { weights.getValue(it.key) * it.value }).toInt()
        val impact = scores.mapValues { weights.getValue(it.key) * (it.value - 75) }
        return Combined(score, weights, impact)
    }

    /** Whole percents that always add up to exactly 100 (largest-remainder). */
    fun percents(weights: Map<String, Double>): Map<String, Int> {
        if (weights.isEmpty()) return emptyMap()
        val total = weights.values.sum()
        val raw = weights.mapValues { it.value / total * 100 }
        val out = raw.mapValues { Math.floor(it.value).toInt() }.toMutableMap()
        var left = 100 - out.values.sum()
        for (k in raw.keys.sortedByDescending { raw.getValue(it) - Math.floor(raw.getValue(it)) }) {
            if (left <= 0) break
            out[k] = out.getValue(k) + 1; left--
        }
        return out
    }
}
