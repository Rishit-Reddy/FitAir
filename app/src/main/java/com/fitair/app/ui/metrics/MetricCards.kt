package com.fitair.app.ui.metrics

import com.fitair.app.core.Format
import com.fitair.app.data.metrics.MetricId
import com.fitair.app.data.metrics.MetricSnapshot
import com.fitair.app.data.metrics.MetricStats
import com.fitair.app.ui.components.CardData
import com.fitair.app.ui.components.CardSize
import com.fitair.app.ui.components.CardState
import com.fitair.app.ui.components.Chip
import com.fitair.app.ui.components.Tone
import com.fitair.app.ui.components.charts.Mini
import com.fitair.app.ui.components.charts.RefStyle
import com.fitair.app.ui.copy.Copy
import com.fitair.app.ui.today.Mode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * Pure: one [CardData] per metric from a snapshot (docs/PLAN_090 section 5 table and 6.1). No Android types, unit tested.
 * [size] only changes what is dropped (Small has no sub-line charts, Steps and Bedtime use a sub-line); [mode] picks the Today variants.
 */
object MetricCards {
    /** HR and today's totals older than this are shown dim with "at 11:40". */
    const val STALE_MS = 3 * 60 * 60_000L
    private const val PARTIAL_BELOW = 0.6

    fun title(id: MetricId): String = when (id) {
        MetricId.Heart -> "Heart rate"; MetricId.Readiness -> "Readiness"; MetricId.Sleep -> "Sleep"
        MetricId.RestingHr -> "Resting heart rate"; MetricId.Hrv -> "Recovery signal"; MetricId.Load -> "Cardio load"
        MetricId.Energy -> "Energy burned"; MetricId.Distance -> "Distance"; MetricId.Steps -> "Steps"
        MetricId.Water -> "Water"; MetricId.Weight -> "Weight"; MetricId.Bedtime -> "Bedtime"
    }

    /** Default order of the Metrics tab. Weight is listed only when [hasWeight]. */
    val DEFAULT_ORDER = listOf(
        MetricId.Heart, MetricId.Readiness, MetricId.Sleep, MetricId.RestingHr, MetricId.Hrv, MetricId.Load,
        MetricId.Energy, MetricId.Distance, MetricId.Steps, MetricId.Water, MetricId.Weight,
    )

    private fun clock(ms: Long, zone: ZoneId): String = Instant.ofEpochMilli(ms).atZone(zone).let { Format.clock(it.hour, it.minute) }
    private fun r(v: Double) = Math.round(v).toInt()
    private fun grouped(n: Int) = Format.thousands(n.toLong())

    private fun weekdays(days: List<LocalDate>) = days.map { it.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.getDefault()) }

    /** Words for TalkBack: "6 hours 52 minutes". */
    fun durationWords(min: Long): String {
        val h = min / 60; val m = min % 60
        return listOfNotNull(if (h > 0) "$h hour${if (h == 1L) "" else "s"}" else null, if (m > 0 || h == 0L) "$m minute${if (m == 1L) "" else "s"}" else null).joinToString(" ")
    }

    private fun last(v: List<Double?>): Double? = v.lastOrNull()
    private fun anyData(v: List<Double?>) = v.any { it != null }

    /**
     * [value] null with data earlier in the week = waiting (Stale, "—"); no data in the whole week = Empty.
     * The returned state is Learning when [chip] says so.
     */
    private fun stateOf(value: Any?, series: List<Double?>, stale: Boolean = false, learning: Boolean = false): CardState = when {
        value == null && !anyData(series) -> CardState.Empty
        value == null || stale -> CardState.Stale
        learning -> CardState.Learning
        else -> CardState.Ready
    }

    fun card(
        id: MetricId, s: MetricSnapshot, size: CardSize = CardSize.Grid, mode: Mode? = null,
        nowMs: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault(),
    ): CardData {
        val title = title(id)
        val wd = weekdays(s.days)
        val ti = s.days.lastIndex
        val totalsStale = s.lastDataMs == null || nowMs - s.lastDataMs > STALE_MS
        fun build(
            value: String?, unit: String?, valueWords: String?, series: List<Double?>, mini: Mini?, chip: Chip?,
            sub: String? = null, rawValue: Any? = value, stale: Boolean = false, learning: Boolean = false,
            smallMini: Boolean = false, cardTitle: String = title, hasWeekdays: Boolean = true,
        ): CardData {
            val state = stateOf(rawValue, series, stale, learning)
            val shownChip = if (state == CardState.Empty) Copy.CHIP_NO_DATA else chip
            return CardData(
                id, cardTitle, if (state == CardState.Empty) null else value, unit, sub, mini, shownChip, state,
                Copy.cardA11y(cardTitle, if (state == CardState.Empty) null else valueWords, shownChip, state == CardState.Stale),
                if (hasWeekdays) wd else emptyList(), ti, smallMini,
            )
        }

        return when (id) {
            MetricId.Heart -> {
                val h = s.heart
                val bpm = h?.latestBpm
                val stale = h?.latestMs == null || nowMs - h.latestMs > STALE_MS
                val has = h != null && h.points.any { !it.isNaN() }
                val partial = h?.coverage?.let { it < PARTIAL_BELOW } == true
                val chip = if (partial) Copy.CHIP_PARTIAL else Copy.chipHeart(bpm, h?.zoneBpm)
                val series = if (has) listOf(1.0) else emptyList()
                build(
                    bpm?.toString(), "bpm", bpm?.let { "$it beats per minute" }, series,
                    h?.takeIf { has }?.let { Mini.HrDay(it.points, it.rest, it.zoneBpm) }, chip,
                    sub = h?.latestMs?.let { "at ${clock(it, zone)}" }, rawValue = bpm, stale = stale && bpm != null, hasWeekdays = false,
                )
            }
            MetricId.Readiness -> {
                val v = last(s.readiness)?.let { r(it) }
                build(
                    v?.toString(), "/100", v?.let { "$it out of 100" }, s.readiness, Mini.Spark(s.readiness), Copy.chipReadiness(v),
                    sub = if (v == null && anyData(s.readiness)) "after your sleep syncs" else null,
                )
            }
            MetricId.Sleep -> {
                val n = s.nights.lastOrNull()
                val asleep = n?.asleepMin
                val series = s.nights.map { it?.asleepMin }
                val bars = Mini.Bars(series, s.nights.lastOrNull { it?.needMin != null }?.needMin, RefStyle.Usual, ti)
                val stages = s.lastStages
                val mini: Mini = if (size == CardSize.Large && stages != null && !stages.isEmpty) Mini.Stages(stages.awake, stages.light, stages.rem, stages.deep) else bars
                build(
                    asleep?.let { Format.duration(Math.round(it)) }, null, asleep?.let { durationWords(Math.round(it)) }, series, mini,
                    Copy.chipSleep(asleep, n?.needMin),
                    sub = if (asleep == null && anyData(series)) "Waiting for sleep data" else null,
                    hasWeekdays = mini === bars,
                )
            }
            MetricId.RestingHr -> {
                val v = last(s.rhr)
                val chip = Copy.chipResting(v, s.rhrBand, s.bandDays)
                build(
                    v?.let { r(it).toString() }, "bpm", v?.let { "${r(it)} beats per minute" }, s.rhr,
                    Mini.DotsOnBand(s.rhr, s.rhrBand, MetricStats.tones(s.rhr, s.rhrBand, hrv = false)), chip,
                    learning = s.rhrBand == null && v != null, smallMini = true,
                )
            }
            MetricId.Hrv -> {
                val v = last(s.hrv)
                val chip = Copy.chipHrv(v, s.hrvBand, s.bandDays)
                build(
                    v?.let { r(it).toString() }, "ms", v?.let { "${r(it)} milliseconds" }, s.hrv,
                    Mini.DotsOnBand(s.hrv, s.hrvBand, MetricStats.tones(s.hrv, s.hrvBand, hrv = true)), chip,
                    learning = s.hrvBand == null && v != null, smallMini = true,
                )
            }
            MetricId.Load -> {
                val lt = s.loadToday
                val live = size == CardSize.Large && (mode == Mode.Day || mode == Mode.Evening) && lt != null
                if (live) {
                    val so = lt!!.soFar
                    val chip = Copy.chipLoadSoFar(so, lt.typicalByNow, lt.partial)
                    val nowHour = s.loadHourly.indexOfLast { it != null }.coerceAtLeast(0)
                    val name = if (mode == Mode.Evening) "Load today" else "Load so far"
                    build(
                        r(so).toString(), null, "${r(so)}", listOf(so),
                        Mini.LoadCurve(s.loadHourly, s.typicalHourly, nowHour), chip, cardTitle = name, hasWeekdays = false,
                    )
                } else {
                    val v = last(s.load)
                    val partial = lt?.coverage?.let { it < PARTIAL_BELOW } == true
                    val chip = if (partial) Copy.CHIP_PARTIAL else Copy.chipLoad(s.ratio)
                    build(
                        v?.let { r(it).toString() }, if (size == CardSize.Grid) "today" else null, v?.let { "${r(it)} today" }, s.load,
                        Mini.Bars(s.load, s.loadUsual, RefStyle.Usual, ti), chip,
                    )
                }
            }
            MetricId.Energy -> {
                val v = last(s.kcal)
                val avg = MetricStats.average(s.kcal)
                build(
                    v?.let { grouped(r(it)) }, "kcal", v?.let { "${grouped(r(it))} kilocalories" }, s.kcal,
                    Mini.Bars(s.kcal, avg, RefStyle.Usual, ti), Copy.chipNeutral(avg?.let { "7-day avg ${grouped(r(it))}" }),
                    stale = totalsStale && v != null, sub = if (totalsStale && v != null) s.lastDataMs?.let { "at ${clock(it, zone)}" } else null,
                    smallMini = true,
                )
            }
            MetricId.Distance -> {
                val km = last(s.distanceM)?.let { it / 1000.0 }
                val week = MetricStats.total(s.distanceM)?.let { it / 1000.0 }
                build(
                    km?.let { String.format(Locale.US, "%.1f", it) }, "km", km?.let { String.format(Locale.US, "%.1f kilometres", it) }, s.distanceM,
                    Mini.Bars(s.distanceM, null, RefStyle.Usual, ti), Copy.chipNeutral(week?.let { "This week ${r(it)} km" }),
                    stale = totalsStale && km != null,
                )
            }
            MetricId.Steps -> {
                val v = last(s.steps)
                val week = MetricStats.total(s.steps)
                val km = last(s.distanceM)?.let { String.format(Locale.US, "%.1f km", it / 1000.0) }
                build(
                    v?.let { Format.compactCount(r(it).toLong()) }, null, v?.let { "${grouped(r(it))} steps" }, s.steps,
                    Mini.Bars(s.steps, null, RefStyle.Usual, ti),
                    if (size == CardSize.Small) null else Copy.chipNeutral(week?.let { "This week ${Format.compactCount(r(it).toLong())}" }),
                    sub = if (size == CardSize.Small) km else null,
                    stale = totalsStale && v != null,
                )
            }
            MetricId.Water -> {
                val w = s.water
                val ml = w?.ml ?: last(s.waterMl)?.let { r(it) }
                val frac = if (w != null && w.goalMl > 0) (w.ml.toFloat() / w.goalMl).coerceIn(0f, 1f) else 0f
                build(
                    ml?.let { Copy.litres(it) }, "L", ml?.let { "${Copy.litres(it)} litres" }, if (ml != null) listOf(ml.toDouble()) else s.waterMl,
                    Mini.Ring(frac), w?.let { Copy.chipNeutral(Copy.waterPace(it.pace, it.extraMl)) },
                    smallMini = true, hasWeekdays = false,
                )
            }
            MetricId.Weight -> {
                val w = s.weight
                val v = w.lastOrNull()?.second
                val avg = MetricStats.rollingMean(w.map { it.second }, 7)
                val first = w.firstOrNull()
                val since = first?.let { Instant.ofEpochMilli(it.first).atZone(zone).format(DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())) }
                build(
                    v?.let { String.format(Locale.US, "%.1f", it) }, "kg", v?.let { String.format(Locale.US, "%.1f kilograms", it) }, w.map { it.second },
                    Mini.Spark(avg), if (v != null && first != null && since != null) Copy.chipWeight(v - first.second, since) else null,
                    hasWeekdays = false,
                )
            }
            MetricId.Bedtime -> {
                val wd0 = s.windDown
                val bed = wd0?.let { ((it.bedMin % 1440) + 1440) % 1440 }
                val value = bed?.let { Format.clock(it / 60, it % 60) }
                val chip = if (wd0?.short == true) Chip("Short on sleep", Tone.Caution, "Short") else null
                CardData(
                    id, title, value, null, wd0?.let { "for ${Format.duration(it.needMin.toLong())}" }, null, chip,
                    if (value == null) CardState.Empty else CardState.Ready,
                    Copy.cardA11y(title, value?.let { "in bed by $it for ${durationWords(wd0!!.needMin.toLong())}" }, chip),
                )
            }
        }
    }
}
