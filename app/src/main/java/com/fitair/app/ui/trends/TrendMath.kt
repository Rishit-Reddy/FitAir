package com.fitair.app.ui.trends

import com.fitair.app.ui.components.Tone
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlin.math.sqrt

/** Personal usual range: mean and standard deviation of the recent history. */
class Band(val mean: Double, val sd: Double) {
    val lo: Double get() = mean - sd
    val hi: Double get() = mean + sd
}

/** Pure helpers for the trend screens (no Android types, unit tested). */
object TrendMath {
    const val BASELINE_DAYS = 28
    const val MIN_BASELINE_POINTS = 7

    /** Mean +- 1 sd of the non-null values; null when fewer than [MIN_BASELINE_POINTS]. */
    fun band(history: List<Double?>): Band? {
        val v = history.filterNotNull()
        if (v.size < MIN_BASELINE_POINTS) return null
        val m = v.average()
        val sd = sqrt(v.sumOf { (it - m) * (it - m) } / v.size)
        return Band(m, sd)
    }

    /** Band built from the [BASELINE_DAYS] entries before index [idx] (the entry itself is excluded). */
    fun baselineBefore(values: List<Double?>, idx: Int): Band? =
        band(values.subList(maxOf(0, idx - BASELINE_DAYS), idx.coerceIn(0, values.size)))

    fun lastIndex(values: List<Double?>): Int? = values.indexOfLast { it != null }.takeIf { it >= 0 }

    /** "+3 ms", "-2 bpm" (true minus sign), or "0 ms". */
    fun signed(d: Double, unit: String): String {
        val r = d.roundToLong()
        val u = if (unit.isEmpty()) "" else " $unit"
        return when { r > 0 -> "+$r$u"; r < 0 -> "−${abs(r)}$u"; else -> "0$u" }
    }

    /** "+3 ms vs your baseline"; "in line with your baseline" when the rounded gap is zero. */
    fun deltaText(latest: Double, band: Band?, unit: String): String {
        if (band == null) return "not enough history for a baseline yet"
        val r = (latest - band.mean).roundToLong()
        return if (r == 0L) "in line with your baseline" else "${signed(latest - band.mean, unit)} vs your baseline"
    }

    /** Side of the band: -1 below, 0 inside, +1 above. */
    fun side(v: Double, b: Band): Int = when { v < b.lo -> -1; v > b.hi -> 1; else -> 0 }

    /** Consecutive most-recent readings (nulls skipped) on the same side of the band, as (side, count). */
    fun streak(values: List<Double?>, b: Band): Pair<Int, Int> {
        val v = values.filterNotNull()
        if (v.isEmpty()) return 0 to 0
        val s = side(v.last(), b)
        var n = 0
        for (x in v.asReversed()) { if (side(x, b) == s) n++ else break }
        return s to n
    }

    /**
     * Tone of [value] for the headline dot. Readiness uses the 70/50 thresholds; HRV and resting HR are Neutral
     * inside the band, Good outside it in the good direction and Caution in the bad one; Load is never toned.
     */
    fun tone(metric: TrendMetric, value: Double?, band: Band?): Tone {
        if (value == null) return Tone.Neutral
        return when (metric) {
            TrendMetric.Load -> Tone.Neutral
            TrendMetric.Readiness -> when { value >= 70 -> Tone.Good; value >= 50 -> Tone.Caution; else -> Tone.Alert }
            else -> {
                if (band == null) return Tone.Neutral
                when (side(value, band)) {
                    0 -> Tone.Neutral
                    else -> if ((side(value, band) > 0) == metric.higherBetter) Tone.Good else Tone.Caution
                }
            }
        }
    }

    /** One plain-language line about where the latest value sits against the usual range. */
    fun interpret(name: String, values: List<Double?>, b: Band?): String {
        if (b == null || values.all { it == null }) return "Not enough history yet to say what is usual for you."
        val (s, n) = streak(values, b)
        val days = if (n == 1) "1 day" else "$n days"
        return when (s) {
            -1 -> if (n >= 2) "$name is below your usual range for $days." else "$name is below your usual range today."
            1 -> if (n >= 2) "$name is above your usual range for $days." else "$name is above your usual range today."
            else -> if (n >= 2) "$name has been within your usual range for $days." else "$name is within your usual range."
        }
    }

    /** Acute:chronic load ratio in words. */
    fun acwrText(acwr: Double?): String = when {
        acwr == null -> "Not enough training history for a load ratio yet."
        acwr < 0.8 -> "Recent load is lighter than your 4-week norm (ratio ${"%.2f".format(java.util.Locale.US, acwr)})."
        acwr <= 1.3 -> "Recent load is in line with your 4-week norm (ratio ${"%.2f".format(java.util.Locale.US, acwr)})."
        else -> "Recent load is well above your 4-week norm (ratio ${"%.2f".format(java.util.Locale.US, acwr)})."
    }
}
