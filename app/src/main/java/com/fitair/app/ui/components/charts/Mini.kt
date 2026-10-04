package com.fitair.app.ui.components.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.fitair.app.ui.components.StageBar
import com.fitair.app.ui.components.Tone
import com.fitair.app.ui.components.toneColor
import com.fitair.app.ui.theme.LocalZoneColors
import com.fitair.app.ui.theme.Type
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import com.fitair.app.ui.trends.Band

/** Reference line style of [Mini.Bars]: "usual" is a dim dashed line, "goal" is not used (no goals). */
enum class RefStyle { Usual, Goal }

/** What a metric card draws (docs/PLAN_090 section 3). Pure data; the Canvas code is in this package. */
sealed interface Mini {
    /** 7-30 points, optional neutral band; the last point is ringed. Null breaks the line. */
    class Spark(val values: List<Double?>, val band: Band? = null) : Mini
    /** 7 dots on a neutral "your usual" band; a dot outside the band takes its tone colour. */
    class DotsOnBand(val values: List<Double?>, val band: Band?, val tones: List<Tone>) : Mini
    /** Rounded bars with an optional dashed reference line; today's bar is full ink, the others 45 %. */
    class Bars(val values: List<Double?>, val ref: Double? = null, val refStyle: RefStyle = RefStyle.Usual, val todayIndex: Int = -1) : Mini
    /** Ring of [fraction] 0..1. */
    class Ring(val fraction: Float) : Mini
    /** 288 five-minute means (NaN = gap), the resting line, zone thresholds, and the zone thresholds. */
    class HrDay(val points: FloatArray, val rest: Float?, val zoneBpm: FloatArray?) : Mini
    /** Cumulative load per hour: solid to now, dashed "usual by this hour" over 24 h. */
    class LoadCurve(val today: List<Double?>, val typical: List<Double?>, val nowHour: Int) : Mini
    /** Sleep stage minutes (awake, light, REM, deep), drawn with the shared StageBar. */
    class Stages(val awake: Double, val light: Double, val rem: Double, val deep: Double) : Mini
}

// ---- drawing --------------------------------------------------------------------------------------------------------

/** Pure scale helper: maps [v] in [lo, hi] to a y between [bottom] (low) and [top] (high). */
internal fun scaleY(v: Double, lo: Double, hi: Double, top: Float, bottom: Float): Float {
    val span = if (hi - lo < 1e-9) 1.0 else hi - lo
    return (bottom - ((v - lo) / span).coerceIn(0.0, 1.0) * (bottom - top)).toFloat()
}

/** Value range of [values] and an optional [band], padded by 12 %; flat data gets a +-1 range. */
internal fun rangeOf(values: List<Double?>, band: Band?): Pair<Double, Double> {
    val all = values.filterNotNull() + listOfNotNull(band?.lo, band?.hi)
    if (all.isEmpty()) return 0.0 to 1.0
    var lo = all.min(); var hi = all.max()
    if (hi - lo < 1e-9) { lo -= 1.0; hi += 1.0 }
    val pad = (hi - lo) * 0.12
    return (lo - pad) to (hi + pad)
}

/** Chart colour of the metric being drawn (see MetricAccent); null = neutral ink. */
val LocalChartAccent = androidx.compose.runtime.compositionLocalOf<Color?> { null }

@Composable private fun ink() = LocalChartAccent.current ?: MaterialTheme.colorScheme.onSurface.copy(alpha = 0.80f)
@Composable private fun bandFill() = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f)
@Composable private fun faint() = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)

private fun DrawScope.polyline(xs: List<Float>, ys: List<Float?>, color: Color, widthPx: Float) {
    var path: Path? = null
    fun flush() { path?.let { drawPath(it, color, style = Stroke(widthPx, cap = StrokeCap.Round, join = StrokeJoin.Round)) }; path = null }
    for (i in xs.indices) {
        val y = ys[i]
        if (y == null) { flush(); continue }
        val p = path
        if (p == null) path = Path().also { it.moveTo(xs[i], y) } else p.lineTo(xs[i], y)
    }
    flush()
}

/** Dotted baseline for the Empty state. */
@Composable
fun EmptyBaseline(height: Dp, modifier: Modifier = Modifier) {
    val c = faint()
    Canvas(modifier.fillMaxWidth().height(height).clearAndSetSemantics { }) {
        val y = size.height * 0.6f
        drawLine(c, Offset(0f, y), Offset(size.width, y), 1.5.dp.toPx(), cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 5.dp.toPx())))
    }
}

/** Sparkline: 7 to 30 points on an optional neutral band, last point ringed. */
@Composable
fun Sparkline(values: List<Double?>, band: Band?, height: Dp, modifier: Modifier = Modifier, strokeDp: Float = 2f) {
    val ink = ink(); val fill = bandFill(); val bg = MaterialTheme.colorScheme.surfaceContainerHigh
    Canvas(modifier.fillMaxWidth().height(height).clearAndSetSemantics { }) {
        val n = values.size
        if (n == 0) return@Canvas
        val r = 3.dp.toPx(); val padY = 6.dp.toPx(); val padX = 6.dp.toPx()
        val (lo, hi) = rangeOf(values, band)
        val slot = (size.width - 2 * padX) / maxOf(n - 1, 1)
        val xs = List(n) { padX + slot * it }
        val ys = values.map { v -> v?.let { scaleY(it, lo, hi, padY, size.height - padY) } }
        if (band != null) {
            val top = scaleY(band.hi, lo, hi, padY, size.height - padY); val bot = scaleY(band.lo, lo, hi, padY, size.height - padY)
            drawRoundRect(fill, Offset(0f, top), Size(size.width, bot - top), CornerRadius(6.dp.toPx()))
        }
        polyline(xs, ys, ink, strokeDp.dp.toPx())
        val last = ys.indexOfLast { it != null }
        for (i in 0 until n) ys[i]?.let { drawCircle(ink, r, Offset(xs[i], it)) }
        if (last >= 0) {
            drawCircle(bg, r + 1.5.dp.toPx(), Offset(xs[last], ys[last]!!))
            drawCircle(ink, r + 1.5.dp.toPx(), Offset(xs[last], ys[last]!!), style = Stroke(1.5.dp.toPx()))
            drawCircle(ink, r, Offset(xs[last], ys[last]!!))
        }
    }
}

/** Seven dots joined by a 1.5dp line on a neutral "your usual" band; a dot outside the band takes its tone colour. */
@Composable
fun DotsOnBand(values: List<Double?>, band: Band?, tones: List<Tone>, height: Dp, modifier: Modifier = Modifier) {
    val ink = ink(); val fill = bandFill()
    val toneColors = tones.map { toneColor(it) }
    Canvas(modifier.fillMaxWidth().height(height).clearAndSetSemantics { }) {
        val n = values.size
        if (n == 0) return@Canvas
        val padY = 5.dp.toPx(); val r = (if (height < 40.dp) 2.5.dp else 3.dp).toPx()
        val (lo, hi) = rangeOf(values, band)
        val slot = size.width / n
        val xs = List(n) { slot * (it + 0.5f) }
        val ys = values.map { v -> v?.let { scaleY(it, lo, hi, padY, size.height - padY) } }
        if (band != null) {
            val top = scaleY(band.hi, lo, hi, padY, size.height - padY); val bot = scaleY(band.lo, lo, hi, padY, size.height - padY)
            drawRoundRect(fill, Offset(0f, top), Size(size.width, maxOf(bot - top, 2.dp.toPx())), CornerRadius(4.dp.toPx()))
        }
        polyline(xs, ys, ink, 1.5.dp.toPx())
        for (i in 0 until n) {
            val v = values[i] ?: continue
            val outside = band != null && (v < band.lo || v > band.hi)
            drawCircle(if (outside && i < toneColors.size) toneColors[i] else ink, r, Offset(xs[i], ys[i]!!))
        }
    }
}

/** Rounded bars (width 60 % of the slot) with an optional dashed reference line; today's bar full ink, others 45 %. */
@Composable
fun MiniBars(values: List<Double?>, ref: Double?, todayIndex: Int, height: Dp, modifier: Modifier = Modifier) {
    val full = ink(); val dim = LocalChartAccent.current?.copy(alpha = 0.35f) ?: faint(); val refColor = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(modifier.fillMaxWidth().height(height).clearAndSetSemantics { }) {
        val n = values.size
        if (n == 0) return@Canvas
        val today = if (todayIndex in 0 until n) todayIndex else n - 1
        val max = maxOf(values.filterNotNull().maxOrNull() ?: 0.0, ref ?: 0.0, 1e-9)
        val slot = size.width / n; val bw = slot * 0.6f
        val top = 3.dp.toPx(); val h = size.height - top
        for (i in 0 until n) {
            val v = values[i]
            val x = slot * i + (slot - bw) / 2f
            if (v == null) {
                drawCircle(dim.copy(alpha = 0.3f), 1.5.dp.toPx(), Offset(x + bw / 2f, size.height - 2.dp.toPx()))
                continue
            }
            val bh = maxOf((v / max).toFloat() * h, bw)
            drawRoundRect(if (i == today) full else dim, Offset(x, size.height - bh), Size(bw, bh), CornerRadius(bw / 2f))
        }
        if (ref != null) {
            val y = size.height - (ref / max).toFloat() * h
            drawLine(refColor, Offset(0f, y), Offset(size.width, y), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())))
        }
    }
}

/** Ring of [fraction]: 4dp track in outlineVariant, neutral ink arc. */
@Composable
fun Ring(fraction: Float, size: Dp, modifier: Modifier = Modifier) {
    val track = MaterialTheme.colorScheme.outlineVariant; val ink = ink()
    Canvas(modifier.size(size).clearAndSetSemantics { }) {
        val w = 4.dp.toPx()
        val d = this.size.minDimension - w
        val tl = Offset(w / 2f, w / 2f)
        drawArc(track, 0f, 360f, false, tl, Size(d, d), style = Stroke(w))
        if (fraction > 0f) drawArc(ink, -90f, 360f * fraction.coerceIn(0f, 1f), false, tl, Size(d, d), style = Stroke(w, cap = StrokeCap.Round))
    }
}

/** 288 five-minute means 00-24 h, zone thresholds as dotted hairlines, dashed resting line. NaN breaks the line. */
@Composable
fun HrDayLine(points: FloatArray, rest: Float?, zoneBpm: FloatArray?, height: Dp, modifier: Modifier = Modifier) {
    val ink = ink(); val dim = faint(); val zones = LocalZoneColors.current
    val measurer = rememberTextMeasurer(); val cap = Type.axis
    Canvas(modifier.fillMaxWidth().height(height).clearAndSetSemantics { }) {
        val n = points.size
        if (n == 0) return@Canvas
        val valid = points.filter { !it.isNaN() }
        val padY = 4.dp.toPx()
        var lo = minOf(valid.minOrNull() ?: 50f, rest ?: 999f) - 4f
        var hi = (valid.maxOrNull() ?: 100f) + 12f
        if (zoneBpm != null && zoneBpm.isNotEmpty()) hi = maxOf(hi, zoneBpm[0] + 8f)
        if (hi - lo < 20f) hi = lo + 20f
        fun y(v: Float) = scaleY(v.toDouble(), lo.toDouble(), hi.toDouble(), padY, size.height - padY)
        val dotted = PathEffect.dashPathEffect(floatArrayOf(1.dp.toPx(), 4.dp.toPx()))
        zoneBpm?.forEach { z -> if (z in lo..hi) drawLine(dim.copy(alpha = 0.5f), Offset(0f, y(z)), Offset(size.width, y(z)), 1.dp.toPx(), pathEffect = dotted) }
        if (rest != null) drawLine(dim, Offset(0f, y(rest)), Offset(size.width, y(rest)), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())))
        val xs = List(n) { size.width * it / (n - 1).coerceAtLeast(1) }
        val ys = points.map { if (it.isNaN()) null else y(it) }
        zoneLine(xs, ys, points, zoneBpm, zones, ink, 1.8.dp.toPx())
        // the day's highest and lowest reading as numbers, placed beside their point and kept inside the chart
        val iMax = points.indices.filter { !points[it].isNaN() }.maxByOrNull { points[it] }
        val iMin = points.indices.filter { !points[it].isNaN() }.minByOrNull { points[it] }
        for ((idx, up) in listOfNotNull(iMax?.let { it to true }, iMin?.takeIf { it != iMax }?.let { it to false })) {
            val r = measurer.measure(Math.round(points[idx]).toString(), cap)
            val tx = (xs[idx] - r.size.width / 2f).coerceIn(0f, size.width - r.size.width)
            val ty = if (up) y(points[idx]) - r.size.height - 2.dp.toPx() else y(points[idx]) + 2.dp.toPx()
            drawText(r, dim, Offset(tx, ty.coerceIn(0f, size.height - r.size.height)))
        }
    }
}

/** Cumulative load per hour: solid ink line up to [nowHour], dashed dim "usual by this hour" over 24 h. */
@Composable
fun LoadCurve(today: List<Double?>, typical: List<Double?>, nowHour: Int, height: Dp, modifier: Modifier = Modifier) {
    val ink = ink(); val dim = faint()
    Canvas(modifier.fillMaxWidth().height(height).clearAndSetSemantics { }) {
        val n = 24
        val max = maxOf((today + typical).filterNotNull().maxOrNull() ?: 1.0, 1.0)
        val padY = 4.dp.toPx()
        val xs = List(n) { size.width * it / (n - 1) }
        fun ys(l: List<Double?>, upTo: Int) = List(n) { i -> if (i <= upTo) l.getOrNull(i)?.let { scaleY(it, 0.0, max, padY, size.height - padY) } else null }
        drawLine(dim.copy(alpha = 0.3f), Offset(0f, size.height - padY), Offset(size.width, size.height - padY), 1.dp.toPx())
        val tp = Path(); var started = false
        ys(typical, n - 1).forEachIndexed { i, y -> if (y != null) { if (!started) { tp.moveTo(xs[i], y); started = true } else tp.lineTo(xs[i], y) } }
        drawPath(tp, dim, style = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))))
        val now = nowHour.coerceIn(0, n - 1)
        val ty = ys(today, now)
        polyline(xs, ty, ink, 2.dp.toPx())
        ty.getOrNull(ty.indexOfLast { it != null }.coerceAtLeast(0))?.let { y -> drawCircle(ink, 3.dp.toPx(), Offset(xs[ty.indexOfLast { it != null }], y)) }
    }
}

/** Weekday labels (11sp dim); today's label sits in an 18dp filled circle. */
@Composable
fun WeekdayLabels(labels: List<String>, todayIndex: Int, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().clearAndSetSemantics { }, horizontalArrangement = Arrangement.SpaceBetween) {
        labels.forEachIndexed { i, l ->
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                val on = i == todayIndex
                Box(Modifier.size(18.dp).clip(CircleShape).background(if (on) MaterialTheme.colorScheme.outlineVariant else Color.Transparent), contentAlignment = Alignment.Center) {
                    Text(l, style = Type.axis, color = if (on) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, maxLines = 1)
                }
            }
        }
    }
}

/** "00  12  24" under day charts. */
@Composable
fun DayAxisLabels(modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().clearAndSetSemantics { }, horizontalArrangement = Arrangement.SpaceBetween) {
        listOf("00", "12", "24").forEach { Text(it, style = Type.axis, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

/** Draws [mini] at [height]; [Mini.Stages] uses the shared 10dp StageBar. */
@Composable
fun MiniChart(mini: Mini, height: Dp, modifier: Modifier = Modifier) {
    when (mini) {
        is Mini.Spark -> Sparkline(mini.values, mini.band, height, modifier)
        is Mini.DotsOnBand -> DotsOnBand(mini.values, mini.band, mini.tones, height, modifier)
        is Mini.Bars -> MiniBars(mini.values, mini.ref, mini.todayIndex, height, modifier)
        is Mini.Ring -> Ring(mini.fraction, height, modifier)
        is Mini.HrDay -> HrDayLine(mini.points, mini.rest, mini.zoneBpm, height, modifier)
        is Mini.LoadCurve -> LoadCurve(mini.today, mini.typical, mini.nowHour, height, modifier)
        is Mini.Stages -> Box(modifier.height(height).fillMaxWidth().clearAndSetSemantics { }, contentAlignment = Alignment.CenterStart) {
            StageBar(mini.awake, mini.light, mini.rem, mini.deep, height = 10.dp, legend = false)
        }
    }
}
