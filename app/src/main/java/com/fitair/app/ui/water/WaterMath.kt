package com.fitair.app.ui.water

/** Pure helpers for the Water page (JVM-testable). */
object WaterMath {
    /**
     * What you had usually drunk by [nowMin] (minutes after midnight) on earlier days: the median of each day's total up to that
     * time. [days] holds each day's drinks as (minute of day, ml); days with nothing logged are skipped. Null with fewer than 3 days.
     */
    fun usualByNow(days: List<List<Pair<Int, Int>>>, nowMin: Int): Int? {
        val sums = days.filter { it.isNotEmpty() }.map { d -> d.filter { it.first <= nowMin }.sumOf { it.second } }.sorted()
        if (sums.size < 3) return null
        val n = sums.size
        return if (n % 2 == 1) sums[n / 2] else (sums[n / 2 - 1] + sums[n / 2]) / 2
    }

    /** Days whose total reached [goalMl], out of the days with anything logged. */
    fun goalDays(totalsMl: List<Int?>, goalMl: Int): Pair<Int, Int> {
        val logged = totalsMl.filterNotNull().filter { it > 0 }
        return logged.count { it >= goalMl } to logged.size
    }

    /** A typed amount, accepted from 50 to 2000 ml; null otherwise. */
    fun parseAmount(text: String): Int? = text.trim().toIntOrNull()?.takeIf { it in 50..2000 }
}
