package com.fitair.app.core

import java.math.BigDecimal
import java.math.RoundingMode

/** Pure numeric helpers (no android.* imports, so they run in JVM unit tests). */
object Num {
    /** Exact-binary half-even rounding, like Python's round(). NaN/Infinity pass through. */
    fun r(x: Double?, n: Int = 1): Double? {
        if (x == null || x.isNaN() || x.isInfinite()) return x
        return BigDecimal(x).setScale(n, RoundingMode.HALF_EVEN).toDouble()
    }

    fun clip(x: Double) = maxOf(0.0, minOf(100.0, x))

    fun mean(v: List<Double>): Double? = if (v.isEmpty()) null else v.sum() / v.size

    /** Sample standard deviation (n-1); 0 for fewer than 2 values. */
    fun sd(v: List<Double>): Double {
        if (v.size < 2) return 0.0
        val m = v.average()
        return Math.sqrt(v.sumOf { (it - m) * (it - m) } / (v.size - 1))
    }
}
