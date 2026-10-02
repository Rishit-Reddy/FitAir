package com.fitair.app.analytics

/** Pure decision rules behind [SelfCheck] (no android.* imports; unit-tested). */
object SelfCheckLogic {
    /** True when DB and Health Connect step totals differ by more than 3 % (with a 25 step floor for tiny counts). */
    fun stepsDiffer(db: Long, hc: Long): Boolean {
        val tol = maxOf(25.0, 0.03 * hc)
        return Math.abs(db - hc) > tol
    }

    /** Gaps longer than [maxGapMs] inside [from, to], given sample times; window edges count as samples. Returns [start,end] pairs. */
    fun gaps(times: List<Long>, from: Long, to: Long, maxGapMs: Long): List<LongArray> {
        if (to <= from) return emptyList()
        val pts = ArrayList<Long>()
        pts.add(from)
        times.filter { it in from..to }.sorted().forEach { pts.add(it) }
        pts.add(to)
        val out = ArrayList<LongArray>()
        for (i in 1 until pts.size) if (pts[i] - pts[i - 1] > maxGapMs) out.add(longArrayOf(pts[i - 1], pts[i]))
        return out
    }

    /** Values outside [lo, hi]. */
    fun outOfRange(values: List<Double>, lo: Double, hi: Double): List<Double> = values.filter { it < lo || it > hi }

    fun weightsOk(sum: Double) = Math.abs(sum - 1.0) < 1e-9

    class Sess(val start: Long, val end: Long, val origin: String)

    /** Overlapping sessions from different origins (counted once by the app) vs several separate sessions of one origin (summed). */
    class SleepIssues(val crossOriginOverlap: Boolean, val sameOriginMulti: Boolean)

    /** [sessions] are all sleep sessions that end on one local date. */
    fun sleepIssues(sessions: List<Sess>): SleepIssues {
        var cross = false
        for (i in sessions.indices) for (j in i + 1 until sessions.size) {
            val a = sessions[i]; val b = sessions[j]
            if (a.origin != b.origin && minOf(a.end, b.end) > maxOf(a.start, b.start)) cross = true
        }
        val multi = sessions.groupBy { it.origin }.any { it.value.size > 1 }
        return SleepIssues(cross, multi)
    }
}
