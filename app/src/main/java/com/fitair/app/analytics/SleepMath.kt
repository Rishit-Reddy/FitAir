package com.fitair.app.analytics

import com.fitair.app.core.Num

/**
 * Pure sleep-score arithmetic used by [com.fitair.app.DailyMetrics].
 *  duration 0.40, efficiency 0.25, restorative 0.20, consistency 0.15 (renormalised over available parts).
 */
object SleepMath {
    val WEIGHTS = linkedMapOf("duration" to 0.40, "efficiency" to 0.25, "restorative" to 0.20, "consistency" to 0.15)

    /** Personal need: 450 min moved halfway toward the 28-day typical, clamped to 420..540 (adults need 7-9 h); 450 without a typical. */
    fun need(typical: Double?): Double =
        if (typical != null) (450.0 + 0.5 * (typical - 450.0)).coerceIn(420.0, 540.0) else 450.0

    fun durationScore(asleepMin: Double, need: Double) = Num.clip(asleepMin / need * 100)

    fun efficiency(asleepMin: Double, awakeMin: Double) = asleepMin / (asleepMin + awakeMin)

    /** 65% -> 0, 90% -> 100. */
    fun efficiencyScore(eff: Double) = Num.clip((eff - 0.65) / 0.25 * 100)

    /** (deep+REM)/asleep; 38% -> 100. */
    fun restorativeFraction(deepMin: Double, remMin: Double, asleepMin: Double) = (deepMin + remMin) / asleepMin
    fun restorativeScore(prop: Double) = Num.clip(prop / 0.38 * 100)

    /** Std dev of sleep midpoints (min): <= 20 -> 100, >= 90 -> 0. */
    fun consistencyScore(sdMin: Double) = Num.clip(100 - (sdMin - 20) / 70 * 100)

    /** Weighted mean of the available (already clipped) component scores, 1 decimal; null when empty. */
    fun combine(scores: Map<String, Double>): Double? {
        if (scores.isEmpty()) return null
        val wsum = scores.keys.sumOf { WEIGHTS.getValue(it) }
        return Num.r(scores.entries.sumOf { WEIGHTS.getValue(it.key) * it.value } / wsum, 1)
    }
}
