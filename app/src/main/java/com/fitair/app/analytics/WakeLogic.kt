package com.fitair.app.analytics

/**
 * Pure wake-time rules (docs/PLAN_081.md 7.1). Sessions are (startMs, endMs) sleep sessions.
 */
object WakeLogic {
    const val MIN_MAIN_MS = 90 * 60_000L
    const val LOOKBACK_MS = 18 * 3_600_000L
    const val DEFAULT_WAKE_MIN = 7 * 60
    const val DEFAULT_BED_MIN = 23 * 60

    class Sess(val start: Long, val end: Long)

    /**
     * wake = end of the longest session of at least 90 min that ended in [now - 18 h, now]; when another such session
     * ends later (split night, back to bed), that later end. Shorter sessions are naps and ignored. Null if none.
     */
    fun wake(sessions: List<Sess>, nowMs: Long): Long? {
        val s = sessions.filter { it.end in (nowMs - LOOKBACK_MS)..nowMs && it.end - it.start >= MIN_MAIN_MS }
        if (s.isEmpty()) return null
        val main = s.maxBy { it.end - it.start }
        val later = s.filter { it.end > main.end }.maxOfOrNull { it.end }
        return later ?: main.end
    }

    /** Median of minute-of-day values; [wrapNoon] shifts values below noon by +1440 first (bed times after midnight), result mod 1440. */
    fun medianMinutes(values: List<Int>, def: Int, wrapNoon: Boolean = false, minCount: Int = 5): Int {
        if (values.size < minCount) return def
        val v = (if (wrapNoon) values.map { if (it < 12 * 60) it + 1440 else it } else values).sorted()
        val m = if (v.size % 2 == 1) v[v.size / 2] else (v[v.size / 2 - 1] + v[v.size / 2]) / 2
        return ((m % 1440) + 1440) % 1440
    }
}
