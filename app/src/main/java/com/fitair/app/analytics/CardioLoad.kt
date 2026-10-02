package com.fitair.app.analytics

import java.time.Instant
import java.time.ZoneId

/**
 * Whole-day, heart-rate based cardio load (docs/PLAN_081.md 3.1). Pure: no android.* imports.
 * A 30 s bucket counts only when hrr >= 0.30 and it belongs to a run of >= 4 consecutive counting buckets (2 min),
 * which drops stand-up spikes and startle. Increment = 0.5 min x hrr x 0.64 x e^(1.92 x hrr) (Banister, comparable with the old TRIMP).
 */
object CardioLoad {
    const val BUCKET_MS = 30_000L
    const val MIN_HRR = 0.30
    const val MIN_RUN = 4
    const val PARTIAL_BELOW = 0.60
    /** Waking window used for coverage. */
    const val COV_FROM_H = 7
    const val COV_TO_H = 22

    /** One 30 s bucket (all origins merged): start time and mean bpm. */
    class Bucket(val t30: Long, val mean: Double)

    class DayLoad(
        val cardio: Double, val zLight: Int, val zMod: Int, val zVig: Int, val zPeak: Int,
        /** Cumulative load at the END of each local hour 0..23. */
        val hourly: DoubleArray, val coverage: Double?,
    )

    fun hrr(bpm: Double, rest: Double, hrMax: Double): Double = (bpm - rest) / maxOf(hrMax - rest, 1.0)

    /** Banister increment for one 30 s bucket. */
    fun increment(hrr: Double): Double {
        val h = hrr.coerceIn(0.0, 1.0)
        return 0.5 * h * 0.64 * Math.exp(1.92 * h)
    }

    /** Which buckets count (hrr >= 0.30 inside a run of >= 4 consecutive 30 s buckets). */
    fun counting(buckets: List<Bucket>, rest: Double, hrMax: Double): BooleanArray {
        val ok = BooleanArray(buckets.size) { hrr(buckets[it].mean, rest, hrMax) >= MIN_HRR }
        val out = BooleanArray(buckets.size)
        var i = 0
        while (i < buckets.size) {
            if (!ok[i]) { i++; continue }
            var j = i
            while (j + 1 < buckets.size && ok[j + 1] && buckets[j + 1].t30 - buckets[j].t30 == BUCKET_MS) j++
            if (j - i + 1 >= MIN_RUN) for (k in i..j) out[k] = true
            i = j + 1
        }
        return out
    }

    /**
     * Load of one local day from [buckets] (sorted by time, already limited to the day and with excluded sessions removed).
     * [untilMs] = end of the coverage window (now / newest bucket for today; day end otherwise).
     */
    fun day(buckets: List<Bucket>, rest: Double, hrMax: Double, zone: ZoneId, dayStartMs: Long, untilMs: Long): DayLoad {
        val c = counting(buckets, rest, hrMax)
        val perHour = DoubleArray(24)
        var total = 0.0
        var zl = 0; var zm = 0; var zv = 0; var zp = 0   // in 30 s buckets
        for (i in buckets.indices) {
            if (!c[i]) continue
            val h = hrr(buckets[i].mean, rest, hrMax)
            val inc = increment(h)
            total += inc
            val hour = Instant.ofEpochMilli(buckets[i].t30).atZone(zone).hour
            perHour[hour] += inc
            when {
                h >= 0.85 -> zp++
                h >= 0.60 -> zv++
                h >= 0.40 -> zm++
                else -> zl++
            }
        }
        val cum = DoubleArray(24)
        var run = 0.0
        for (h in 0 until 24) { run += perHour[h]; cum[h] = run }
        return DayLoad(total, mins(zl), mins(zm), mins(zv), mins(zp), cum, coverage(buckets.map { it.t30 }, zone, dayStartMs, untilMs))
    }

    private fun mins(buckets30: Int) = Math.round(buckets30 * 0.5).toInt()

    /** Share of the 07:00-22:00 buckets (up to [untilMs]) that have a heart-rate bucket; null when the elapsed window is under 1 h. */
    fun coverage(times: List<Long>, zone: ZoneId, dayStartMs: Long, untilMs: Long): Double? {
        val date = Instant.ofEpochMilli(dayStartMs).atZone(zone).toLocalDate()
        val from = date.atTime(COV_FROM_H, 0).atZone(zone).toInstant().toEpochMilli()
        val to = minOf(date.atTime(COV_TO_H, 0).atZone(zone).toInstant().toEpochMilli(), untilMs)
        if (to - from < 3_600_000L) return null
        val expected = (to - from) / BUCKET_MS
        val have = times.asSequence().filter { it in from until to }.distinct().count()
        return (have.toDouble() / expected).coerceIn(0.0, 1.0)
    }

    fun isPartial(coverage: Double?): Boolean = coverage != null && coverage < PARTIAL_BELOW

    // ---------- HRmax ----------

    enum class HrMaxSource(val label: String) {
        Set("set by you"), Age("estimated from your age"), Observed("estimated from your data"), Default("default")
    }

    class HrMax(val value: Double, val source: HrMaxSource)

    /** Priority: your own value, then 208 - 0.7 x age (birth year), then observed P99.5 + 5 clamped 170..205, then 190. */
    fun hrMax(pref: Double?, birthYear: Int?, thisYear: Int, observedP995: Double?): HrMax {
        if (pref != null && pref in 120.0..230.0) return HrMax(pref, HrMaxSource.Set)
        if (birthYear != null) {
            val age = thisYear - birthYear
            if (age in 10..100) return HrMax(Math.rint(208 - 0.7 * age), HrMaxSource.Age)
        }
        if (observedP995 != null) return HrMax((observedP995 + 5).coerceIn(170.0, 205.0), HrMaxSource.Observed)
        return HrMax(190.0, HrMaxSource.Default)
    }

    /** Percentile [p] (0..1) of a histogram value -> count. */
    fun percentile(hist: Map<Int, Long>, p: Double): Double? {
        val n = hist.values.sum()
        if (n <= 0) return null
        val target = Math.ceil(p * n).toLong().coerceAtLeast(1)
        var run = 0L
        for (k in hist.keys.sorted()) { run += hist.getValue(k); if (run >= target) return k.toDouble() }
        return hist.keys.maxOrNull()?.toDouble()
    }

    // ---------- acute / chronic ----------

    class Ewma(val acute: Double, val chronic: Double, val history: Int) {
        /** acute/chronic, or null with < 14 days of history or a near-zero chronic load. */
        val ratio: Double? get() = if (history >= 14 && chronic >= 1.0) acute / chronic else null
    }

    /**
     * EWMA 7 d / 28 d over [daily] (index 0 = oldest). [hasData] marks days with any data; the series starts at the first such day,
     * earlier entries are null.
     */
    fun ewma(daily: List<Double>, hasData: List<Boolean>): List<Ewma?> {
        val a7 = 2.0 / 8; val a28 = 2.0 / 29
        val out = ArrayList<Ewma?>(daily.size)
        var acute = 0.0; var chronic = 0.0; var first = -1
        for (i in daily.indices) {
            val x = daily[i]
            if (first < 0 && (hasData[i] || x > 0)) { first = i; acute = x; chronic = x }
            else if (first >= 0) { acute += a7 * (x - acute); chronic += a28 * (x - chronic) }
            out.add(if (first >= 0) Ewma(acute, chronic, i - first + 1) else null)
        }
        return out
    }

    /** Plain verdict key for a ratio (the words live in Copy). */
    fun verdict(ratio: Double?): String? = when {
        ratio == null -> null
        ratio < 0.8 -> "lighter"
        ratio <= 1.3 -> "normal"
        ratio <= 1.5 -> "harder"
        else -> "much_harder"
    }

    /** Median of the 28-day cumulative-by-hour values at [hour] + fraction (linear between hour ends); null with < 7 days. */
    fun typicalByNow(history: List<DoubleArray>, hour: Int, minuteFrac: Double): Double? {
        if (history.size < 7) return null
        val v = history.map { h ->
            val prev = if (hour == 0) 0.0 else h[hour - 1]
            prev + (h[hour] - prev) * minuteFrac.coerceIn(0.0, 1.0)
        }.sorted()
        return if (v.size % 2 == 1) v[v.size / 2] else (v[v.size / 2 - 1] + v[v.size / 2]) / 2
    }
}
