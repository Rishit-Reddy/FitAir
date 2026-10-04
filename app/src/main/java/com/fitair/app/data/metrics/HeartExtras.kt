package com.fitair.app.data.metrics

/** Pure heart-rate summaries for the Heart rate page: overnight low, drop after exercise, period averages. No Android types. */
object HeartExtras {
    /** Lowest 30 s mean inside any [sleeps] window (start, end ms) and when it happened; null without data. */
    class Low(val bpm: Int, val atMs: Long)

    fun overnightLow(rows: List<MetricStats.HrRow>, sleeps: List<LongArray>): Low? =
        rows.filter { r -> sleeps.any { r.t30 >= it[0] && r.t30 < it[1] } }
            .minByOrNull { it.mean }?.let { Low(Math.round(it.mean).toInt(), it.t30) }

    /**
     * Heart rate drop after a session ending at [endMs]: weighted mean of the last minute of the session minus the weighted mean
     * of the second minute after it. Null unless both minutes have readings. 30 s means, so this is approximate.
     */
    fun dropAfter(rows: List<MetricStats.HrRow>, endMs: Long): Int? {
        fun mean(a: Long, b: Long): Double? {
            val w = rows.filter { it.t30 >= a && it.t30 < b }
            val n = w.sumOf { it.n }
            return if (n > 0) w.sumOf { it.mean * it.n } / n else null
        }
        val before = mean(endMs - 60_000L, endMs) ?: return null
        val after = mean(endMs + 60_000L, endMs + 120_000L) ?: return null
        return Math.round(before - after).toInt()
    }

    /** Mean of the non-null values, or null when fewer than [minN] are present. */
    fun meanOf(v: List<Double?>, minN: Int = 1): Double? {
        val p = v.filterNotNull()
        return if (p.size < minN || p.isEmpty()) null else p.average()
    }

    /** Last 7 values' mean minus the 7 before them; null when either week has fewer than 4 values. [v] is oldest first. */
    fun weekChange(v: List<Double?>): Double? {
        if (v.size < 14) return null
        val now = meanOf(v.takeLast(7), 4) ?: return null
        val prev = meanOf(v.dropLast(7).takeLast(7), 4) ?: return null
        return now - prev
    }
}
