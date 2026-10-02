package com.fitair.app.data.metrics

import com.fitair.app.ui.components.Tone
import com.fitair.app.ui.trends.Band
import kotlin.math.sqrt

/** Pure helpers behind the metric snapshot and the detail screens (docs/PLAN_090 5, 5.1). No android.* imports. */
object MetricStats {
    /** A band needs this many readings in the 28 days before. Below it the card says "Learning your normal". */
    const val MIN_BAND_DAYS = 14
    const val BAND_WINDOW = 28
    /** Smallest spread used when judging a reading, so a very steady history does not turn 1 bpm into "high". */
    const val MIN_SD_RHR = 1.5
    const val MIN_SD_HRV = 2.0
    const val BINS = 288
    const val BIN_MS = 300_000L

    // ---- bands and tones ----------------------------------------------------------------------------------------

    /** Number of readings in [history]. */
    fun daysWithData(history: List<Double?>): Int = history.count { it != null }

    fun isLearning(days: Int): Boolean = days < MIN_BAND_DAYS

    /** Mean and standard deviation of the last [BAND_WINDOW] entries of [history28]; null with fewer than [MIN_BAND_DAYS] readings. */
    fun band(history28: List<Double?>): Band? {
        val v = history28.takeLast(BAND_WINDOW).filterNotNull()
        if (v.size < MIN_BAND_DAYS) return null
        val m = v.average()
        return Band(m, sqrt(v.sumOf { (it - m) * (it - m) } / v.size))
    }

    /** Resting heart rate: inside the band or low = Good, above +1 SD = Caution, above +2 SD = Alert. No band = Neutral. */
    fun rhrTone(v: Double?, band: Band?): Tone {
        if (v == null || band == null) return Tone.Neutral
        val sd = maxOf(band.sd, MIN_SD_RHR)
        return when {
            v > band.mean + 2 * sd -> Tone.Alert
            v > band.mean + sd -> Tone.Caution
            else -> Tone.Good
        }
    }

    /** Recovery signal (HRV): inside the band or above = Good, below -1 SD = Caution, below -2 SD = Alert. */
    fun hrvTone(v: Double?, band: Band?): Tone {
        if (v == null || band == null) return Tone.Neutral
        val sd = maxOf(band.sd, MIN_SD_HRV)
        return when {
            v < band.mean - 2 * sd -> Tone.Alert
            v < band.mean - sd -> Tone.Caution
            else -> Tone.Good
        }
    }

    /** Per-day tones for the dots of a 7-day mini chart. */
    fun tones(values: List<Double?>, band: Band?, hrv: Boolean): List<Tone> =
        values.map { if (hrv) hrvTone(it, band) else rhrTone(it, band) }

    // ---- heart rate ---------------------------------------------------------------------------------------------

    /** One stored 30 s heart-rate row (one origin). */
    class HrRow(val t30: Long, val mean: Double, val min: Int, val max: Int, val n: Int)

    /** Five-minute bins of a day: weighted [mean] plus the lowest [lo] and highest [hi] reading; NaN where nothing was recorded. */
    class Bins(val mean: FloatArray, val lo: FloatArray, val hi: FloatArray)

    /** Groups [rows] into [BINS] five-minute bins counted from [dayStartMs]; rows outside the day are ignored; gaps stay NaN. */
    fun downsample(rows: List<HrRow>, dayStartMs: Long): Bins {
        val sum = DoubleArray(BINS); val cnt = IntArray(BINS)
        val lo = FloatArray(BINS) { Float.NaN }; val hi = FloatArray(BINS) { Float.NaN }
        for (r in rows) {
            val i = ((r.t30 - dayStartMs) / BIN_MS).toInt()
            if (r.t30 < dayStartMs || i !in 0 until BINS || r.n <= 0) continue
            sum[i] += r.mean * r.n; cnt[i] += r.n
            if (lo[i].isNaN() || r.min < lo[i]) lo[i] = r.min.toFloat()
            if (hi[i].isNaN() || r.max > hi[i]) hi[i] = r.max.toFloat()
        }
        return Bins(FloatArray(BINS) { if (cnt[it] > 0) (sum[it] / cnt[it]).toFloat() else Float.NaN }, lo, hi)
    }

    /** Lower bpm bound of Light, Moderate, Vigorous, Peak at heart-rate-reserve 0.30 / 0.40 / 0.60 / 0.85 (same cut-offs as the load maths). */
    fun zoneBpm(rest: Double, hrMax: Double): FloatArray {
        val reserve = maxOf(hrMax - rest, 1.0)
        return doubleArrayOf(0.30, 0.40, 0.60, 0.85).map { (rest + it * reserve).toFloat() }.toFloatArray()
    }

    /** 0 = below Light ("Resting"), 1 = Light, 2 = Moderate, 3 = Vigorous, 4 = Peak. */
    fun zoneIndex(bpm: Double, zoneBpm: FloatArray): Int = zoneBpm.count { bpm >= it }

    /** The 5-minute bin of [ms] in a day starting at [dayStartMs]. */
    fun binOf(ms: Long, dayStartMs: Long): Int = ((ms - dayStartMs) / BIN_MS).toInt().coerceIn(0, BINS - 1)

    // ---- load ---------------------------------------------------------------------------------------------------

    fun median(v: List<Double>): Double? {
        if (v.isEmpty()) return null
        val s = v.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
    }

    /** Median cumulative load at the end of each hour over [history] (one 24-value array per day); all null with fewer than 7 days. */
    fun typicalHourly(history: List<DoubleArray>): List<Double?> {
        if (history.size < 7) return List(24) { null }
        return (0 until 24).map { h -> median(history.map { it.getOrElse(h) { 0.0 } }) }
    }

    /** Median of the daily values; null with fewer than 7. */
    fun usual(values: List<Double?>): Double? = values.filterNotNull().takeIf { it.size >= 7 }?.let { median(it) }

    // ---- totals -------------------------------------------------------------------------------------------------

    /** Sum of the readings (null when there are none): "This week 51.3k". */
    fun total(values: List<Double?>): Double? = values.filterNotNull().takeIf { it.isNotEmpty() }?.sum()

    /** Mean of the days that have a reading (null when there are none). */
    fun average(values: List<Double?>): Double? = values.filterNotNull().takeIf { it.isNotEmpty() }?.average()

    /** Seven-day sums ending at each index, for weekly totals (null where fewer than one reading in the window). */
    fun weeklySums(values: List<Double?>): List<Double?> =
        values.indices.map { i -> total(values.subList(maxOf(0, i - 6), i + 1)) }

    /** Rolling mean of [window] readings (null entries skipped), same length as [values]. */
    fun rollingMean(values: List<Double?>, window: Int): List<Double?> =
        values.indices.map { i -> average(values.subList(maxOf(0, i - window + 1), i + 1)) }

    /** Last [n] entries of [values]; shorter lists are padded with nulls at the front. */
    fun lastN(values: List<Double?>, n: Int): List<Double?> =
        if (values.size >= n) values.takeLast(n) else List(n - values.size) { null } + values

    /** Average of a period against the same-length period before it: (average, difference or null). */
    class Since(val avg: Double?, val prevAvg: Double?, val total: Double?, val days: Int) {
        val diff: Double? get() = if (avg != null && prevAvg != null) avg - prevAvg else null
    }

    /** [values] = the period followed by nothing; [prev] = the period before. Both oldest first. */
    fun since(values: List<Double?>, prev: List<Double?>): Since =
        Since(average(values), average(prev), total(values), values.count { it != null })

    // ---- freshness ----------------------------------------------------------------------------------------------

    /** True when the newest reading ([ms], null = none) is older than [windowMs] at [nowMs]. */
    fun stale(ms: Long?, nowMs: Long, windowMs: Long): Boolean = ms == null || nowMs - ms > windowMs
}
