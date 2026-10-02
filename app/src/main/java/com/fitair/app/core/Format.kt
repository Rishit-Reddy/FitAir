package com.fitair.app.core

import java.util.Locale

/** Pure display formatters shared by Today, the breakdown sheet and diagnostics. */
object Format {
    const val DASH = "—"

    /** 444 -> "7h 24m". */
    fun duration(min: Long?): String = if (min == null) DASH else "${min / 60}h ${min % 60}m"

    /** Signed difference in minutes: "+0:32" / "−1:05" (true minus sign). */
    fun deltaMinutes(min: Long): String {
        val a = Math.abs(min)
        return (if (min < 0) "−" else "+") + "${a / 60}:%02d".format(a % 60)
    }

    /** Short signed difference: "+32m", "−1h 05m", "0m" (true minus sign). */
    fun deltaShort(min: Long): String {
        if (min == 0L) return "0m"
        val a = Math.abs(min)
        val body = if (a >= 60) "${a / 60}h %02dm".format(a % 60) else "${a}m"
        return (if (min < 0) "−" else "+") + body
    }

    fun clock(h: Int, m: Int) = "%02d:%02d".format(h, m)

    /** 8234 -> "8,234". */
    fun thousands(n: Long): String = String.format(Locale.US, "%,d", n)

    /** Readiness band word. */
    fun band(score: Int?): String? = when {
        score == null -> null
        score >= 70 -> "Ready"
        score >= 50 -> "Steady"
        else -> "Recover"
    }

    /** "1.4 SD below baseline" / "near baseline". */
    fun sdPhrase(z: Double): String =
        if (Math.abs(z) < 0.3) "near baseline"
        else String.format(Locale.US, "%.1f SD %s baseline", Math.abs(z), if (z > 0) "above" else "below")

    class Freshness(val text: String, val stale: Boolean)

    /** "synced 12 min ago" below one hour, "stale 3 h" from one hour on. */
    fun freshness(nowMs: Long, lastMs: Long): Freshness {
        if (lastMs <= 0) return Freshness("never synced", true)
        val min = Math.max(0L, (nowMs - lastMs) / 60_000L)
        return when {
            min < 1 -> Freshness("synced just now", false)
            min < 60 -> Freshness("synced $min min ago", false)
            min < 48 * 60 -> Freshness("stale ${min / 60} h", true)
            else -> Freshness("stale ${min / 1440} d", true)
        }
    }

    /** Mean of the last-28-day history, or null with fewer than [minN] values. */
    fun baseline(history: List<Double>, minN: Int = 7): Double? =
        if (history.size < minN) null else history.average()
}
