package com.fitair.app.ui.sleep

import java.time.LocalDate
import java.time.ZoneId

/** One stretch of a single stage group: 0 = awake, 1 = REM, 2 = light, 3 = deep (top to bottom on the timeline). */
class StageSeg(val startMs: Long, val endMs: Long, val lane: Int)

/** Pure timing helpers for the sleep page (JVM-testable). */
object SleepTiming {
    /** Health Connect stage code to timeline lane; null for unknown codes. */
    fun lane(stage: Int): Int? = when (stage) { 1, 3, 7 -> 0; 6 -> 1; 2, 4 -> 2; 5 -> 3; else -> null }

    /** Minutes from 18:00 the evening before [night] to [ms]; a night's bars on one axis whatever the date. */
    fun offsetMin(ms: Long, night: LocalDate, z: ZoneId): Double {
        val base = night.minusDays(1).atTime(18, 0).atZone(z).toInstant().toEpochMilli()
        return (ms - base) / 60_000.0
    }

    /** Clock label for an offset from [offsetMin], e.g. 360 -> "00:00". */
    fun offsetClock(min: Double): String {
        val m = ((Math.round(min) + 18 * 60) % (24 * 60) + 24 * 60) % (24 * 60)
        return "%02d:%02d".format(m / 60, m % 60)
    }

    /** Population standard deviation in minutes, or null with fewer than [minN] values. */
    fun sd(v: List<Double>, minN: Int = 4): Double? {
        if (v.size < minN) return null
        val m = v.average()
        return Math.sqrt(v.sumOf { (it - m) * (it - m) } / v.size)
    }

    /** Median, or null when empty. */
    fun median(v: List<Double>): Double? {
        if (v.isEmpty()) return null
        val s = v.sorted(); val n = s.size
        return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2.0
    }
}
