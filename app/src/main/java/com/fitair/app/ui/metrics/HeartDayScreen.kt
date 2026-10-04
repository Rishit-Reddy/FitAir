package com.fitair.app.ui.metrics

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.fitair.app.core.Format
import com.fitair.app.data.metrics.MetricStats
import com.fitair.app.ui.components.DetailHeader
import com.fitair.app.ui.components.EmptyState
import com.fitair.app.ui.components.InlineError
import com.fitair.app.ui.components.Page
import com.fitair.app.ui.components.SectionBreak
import com.fitair.app.ui.components.SectionHeader
import com.fitair.app.ui.components.SelectionCard
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import com.fitair.app.ui.components.charts.zoneLine
import com.fitair.app.ui.theme.LocalZoneColors
import com.fitair.app.ui.theme.Shapes
import com.fitair.app.ui.theme.Spacing
import com.fitair.app.ui.theme.Type
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val DAY_FMT = DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault())
private val ZONE_NAMES = listOf("Resting", "Light", "Moderate", "Vigorous", "Peak")
private val CHART_H = 220.dp

private fun clockOfBin(i: Int) = Format.clock(i * 5 / 60, i * 5 % 60)

/**
 * Heart rate of one day (docs/PLAN_090 5.1): five-minute means with the lowest-to-highest band, zone lines, resting line and exercise
 * ticks. The header and the day switcher stay put; dragging on the chart fills the card under it, which always keeps its height.
 */
@Composable
fun HeartDayScreen(onBack: () -> Unit) {
    val vm: HeartDayVm = viewModel()
    val trendVm: HeartTrendsVm = viewModel()
    LaunchedEffect(Unit) { trendVm.load() }
    val trends = trendVm.ui
    var back by rememberSaveable { mutableIntStateOf(0) }
    var selected by remember(back) { mutableStateOf<Int?>(null) }
    LaunchedEffect(back) { vm.load(back) }
    val date = LocalDate.now().minusDays(back.toLong())

    Column(Modifier.fillMaxSize()) {
        DetailHeader("Heart rate", onBack)
        Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.s), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { back++ }, enabled = back < HeartDayVm.MAX_BACK, modifier = Modifier.heightIn(min = Spacing.minTouch)) { Text("‹ Earlier", style = Type.label) }
            Text(if (back == 0) "Today · ${date.format(DAY_FMT)}" else date.format(DAY_FMT), style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            TextButton(onClick = { back-- }, enabled = back > 0, modifier = Modifier.heightIn(min = Spacing.minTouch)) { Text("Later ›", style = Type.label) }
        }
        Page(Modifier.weight(1f)) {
            vm.error?.let { InlineError(it, onRetry = { vm.load(back) }) }
            val ui = vm.ui
            if (ui == null || ui.date != date) {
                if (vm.error == null) Box(Modifier.fillMaxWidth().padding(vertical = Spacing.xxl), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                return@Page
            }
            if (!ui.hasData) {
                EmptyState("No heart rate for this day", "Sync from Health Connect on Today, then check back.")
                return@Page
            }
            HeartChart(ui, selected, { selected = it }, Modifier.fillMaxWidth().height(CHART_H))
            Spacer(Modifier.height(Spacing.m))
            val sel = selected?.takeIf { it in ui.mean.indices && !ui.mean[it].isNaN() }
            val zone = { bpm: Double -> ZONE_NAMES[MetricStats.zoneIndex(bpm, ui.zoneBpm)] }
            val zcol = LocalZoneColors.current
            if (sel != null) {
                val m = ui.mean[sel]
                SelectionCard(clockOfBin(sel), "Back to latest", { selected = null }) {
                    ZoneReading(Math.round(m).toInt(), zone(m.toDouble()), zcol[MetricStats.zoneIndex(m.toDouble(), ui.zoneBpm)])
                    Spacer(Modifier.height(Spacing.s))
                    if (!ui.lo[sel].isNaN() && !ui.hi[sel].isNaN())
                        Text("Between ${Math.round(ui.lo[sel])} and ${Math.round(ui.hi[sel])} in those five minutes", style = Type.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                val at = ui.latestMs?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()) }
                SelectionCard(if (at != null) "Latest reading · ${Format.clock(at.hour, at.minute)}" else "Latest reading", null, null) {
                    if (ui.latestBpm != null) ZoneReading(ui.latestBpm, zone(ui.latestBpm.toDouble()), zcol[MetricStats.zoneIndex(ui.latestBpm.toDouble(), ui.zoneBpm)])
                    else Text(Format.DASH, style = MaterialTheme.typography.headlineMedium)
                    Spacer(Modifier.height(Spacing.s))
                    Text("Drag across the chart to read any time of day.", style = Type.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(Spacing.m))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                StatTile("Resting", Math.round(ui.restingDay ?: ui.rest.toDouble()).toString(), Modifier.weight(1f))
                StatTile("Overnight low", ui.overnightLow?.bpm?.toString() ?: Format.DASH, Modifier.weight(1f), sub = lowSub(ui.overnightLow))
            }
            Spacer(Modifier.height(Spacing.s))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                StatTile("Average", ui.avg?.let { Math.round(it).toString() } ?: Format.DASH, Modifier.weight(1f))
                StatTile("Highest", ui.max?.toString() ?: Format.DASH, Modifier.weight(1f))
            }
            ui.coverage?.let {
                val pct = Math.round(it * 100).toInt()
                Spacer(Modifier.height(Spacing.s))
                Text(if (it < 0.6) "Heart rate was recorded for about $pct% of the daytime, so this day is partial." else "Heart rate recorded for about $pct% of the daytime.",
                    style = Type.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Spacer(Modifier.height(Spacing.xl))
            ZoneTimeCard(ui.zoneMin, ui.zoneBpm, ui.hrMax, ui.hrMaxSource)
            if (ui.recoveries.isNotEmpty()) {
                Spacer(Modifier.height(Spacing.m))
                RecoveryCard(ui.recoveries)
            }

            trends?.let { t ->
                SectionBreak()
                SectionHeader("Trends")
                Spacer(Modifier.height(Spacing.m))
                RestingTrendCard(t)
                Spacer(Modifier.height(Spacing.m))
                WeekZonesCard(t)
                t.shifts?.let { Spacer(Modifier.height(Spacing.m)); ShiftCompareCard(it) }
            }
        }
    }
}

private val LEFT_GUTTER = 30.dp
private val RIGHT_GUTTER = 64.dp
private val BOTTOM_GUTTER = 22.dp

/** Five-minute line with its lowest-to-highest band, dotted zone lines labelled at the right edge, a dashed resting line and session ticks. */
@Composable
private fun HeartChart(ui: HeartDayUi, selected: Int?, onSelect: (Int) -> Unit, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    val measurer = rememberTextMeasurer()
    val select by rememberUpdatedState(onSelect)
    val zc = LocalZoneColors.current
    val ink = cs.onSurface.copy(alpha = 0.8f); val dim = cs.onSurfaceVariant; val guide = cs.outlineVariant; val primary = cs.primary
    val cap = Type.caption.copy(letterSpacing = androidx.compose.ui.unit.TextUnit.Unspecified)
    val n = ui.mean.size
    val desc = "Heart rate chart for the day, ${ui.latestBpm?.let { "latest $it beats per minute" } ?: "no latest reading"}." +
        (selected?.takeIf { it in 0 until n && !ui.mean[it].isNaN() }?.let { " Selected ${clockOfBin(it)}: ${Math.round(ui.mean[it])} beats per minute." } ?: "")
    fun indexAt(x: Float, w: Float, left: Float, plot: Float): Int? = if (plot <= 0f) null else (((x - left) / plot * n).toInt()).takeIf { x >= left - 4f && x <= w }?.coerceIn(0, n - 1)
    Canvas(
        modifier.semantics { contentDescription = desc }
            .pointerInput(n) {
                val left = LEFT_GUTTER.toPx(); val plot = size.width - left - RIGHT_GUTTER.toPx()
                detectTapGestures { p -> indexAt(p.x, size.width.toFloat(), left, plot)?.let(select) }
            }
            .pointerInput(n) {
                val left = LEFT_GUTTER.toPx(); val plot = size.width - left - RIGHT_GUTTER.toPx()
                detectHorizontalDragGestures { change, _ -> change.consume(); indexAt(change.position.x, size.width.toFloat(), left, plot)?.let(select) }
            },
    ) {
        val left = LEFT_GUTTER.toPx(); val plotW = size.width - left - RIGHT_GUTTER.toPx()
        val top = 8.dp.toPx(); val bottom = size.height - BOTTOM_GUTTER.toPx()
        val vals = (ui.mean.toList() + ui.lo.toList() + ui.hi.toList()).filter { !it.isNaN() }
        if (vals.isEmpty() || plotW <= 0f) return@Canvas
        var lo = minOf(vals.min(), ui.rest) - 4f; var hi = vals.max() + 6f
        if (hi - lo < 20f) { val c = (hi + lo) / 2f; lo = c - 10f; hi = c + 10f }
        fun y(v: Float) = bottom - ((v - lo) / (hi - lo)).coerceIn(0f, 1f) * (bottom - top)
        val slot = plotW / n
        fun x(i: Int) = left + slot * (i + 0.5f)
        drawLine(guide, Offset(left, bottom), Offset(left + plotW, bottom), 0.5.dp.toPx())
        yText(measurer, cap, Math.round(lo + 4f).toString(), dim, y(lo + 4f), left)
        yText(measurer, cap, Math.round(hi - 6f).toString(), dim, y(hi - 6f), left)
        // zone lines (dotted) with names at the right edge; skip those outside the plotted range
        val dots = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 4.dp.toPx()))
        val names = listOf("Light", "Mod.", "Vig.", "Peak")
        ui.zoneBpm.forEachIndexed { i, b ->
            if (b < lo || b > hi) return@forEachIndexed
            drawLine(zc[i + 1].copy(alpha = 0.55f), Offset(left, y(b)), Offset(left + plotW, y(b)), 1.dp.toPx(), pathEffect = dots)
            val r = measurer.measure("${names[i]} ${Math.round(b)}", cap)
            drawText(r, dim, Offset(left + plotW + 4.dp.toPx(), (y(b) - r.size.height / 2f).coerceIn(0f, size.height - r.size.height)))
        }
        // resting line (dashed)
        if (ui.rest in lo..hi) {
            drawLine(dim, Offset(left, y(ui.rest)), Offset(left + plotW, y(ui.rest)), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx())))
            val r = measurer.measure("Rest ${Math.round(ui.rest)}", cap)
            drawText(r, dim, Offset(left + plotW + 4.dp.toPx(), (y(ui.rest) - r.size.height / 2f).coerceIn(0f, size.height - r.size.height)))
        }
        // min-max band, one polygon per run of data
        var i = 0
        while (i < n) {
            if (ui.mean[i].isNaN() || ui.lo[i].isNaN()) { i++; continue }
            var j = i
            while (j + 1 < n && !ui.mean[j + 1].isNaN() && !ui.lo[j + 1].isNaN()) j++
            if (j > i) {
                val p = Path()
                p.moveTo(x(i), y(ui.hi[i])); for (k in i + 1..j) p.lineTo(x(k), y(ui.hi[k]))
                for (k in j downTo i) p.lineTo(x(k), y(ui.lo[k]))
                p.close()
                drawPath(p, cs.onSurface.copy(alpha = 0.12f))
            }
            i = j + 1
        }
        // mean line, one colour per zone, broken at gaps
        zoneLine(List(n) { x(it) }, ui.mean.map { if (it.isNaN()) null else y(it) }, ui.mean, ui.zoneBpm, zc, ink, 2.dp.toPx())
        // exercise sessions as ticks on the x axis (flagged ones dim)
        val z = ZoneId.systemDefault()
        val dayStart = ui.date.atStartOfDay(z).toInstant().toEpochMilli()
        for (s in ui.sessions) {
            val a = (MetricStats.binOf(s.startMs, dayStart)).let { x(it) - slot / 2f }
            val b = (MetricStats.binOf(s.endMs, dayStart)).let { x(it) + slot / 2f }
            drawRect(if (s.flagged) dim.copy(alpha = 0.35f) else primary, Offset(a, bottom + 2.dp.toPx()), androidx.compose.ui.geometry.Size((b - a).coerceAtLeast(3.dp.toPx()), 3.dp.toPx()))
        }
        // x labels 00 06 12 18 24
        for (h in listOf(0, 6, 12, 18, 24)) {
            val r = measurer.measure(Format.clock(h, 0).substring(0, 2), cap)
            val cx = left + plotW * h / 24f
            drawText(r, dim, Offset((cx - r.size.width / 2f).coerceIn(left - 4.dp.toPx(), left + plotW - r.size.width + 4.dp.toPx()), bottom + 8.dp.toPx()))
        }
        // selection
        selected?.takeIf { it in 0 until n && !ui.mean[it].isNaN() }?.let { s ->
            drawLine(primary.copy(alpha = 0.5f), Offset(x(s), top), Offset(x(s), bottom), 1.dp.toPx())
            drawCircle(zc[MetricStats.zoneIndex(ui.mean[s].toDouble(), ui.zoneBpm)], 4.dp.toPx(), Offset(x(s), y(ui.mean[s]))); drawCircle(cs.background, 1.8.dp.toPx(), Offset(x(s), y(ui.mean[s])))
        }
    }
}

private fun DrawScope.yText(m: TextMeasurer, style: androidx.compose.ui.text.TextStyle, text: String, color: Color, yCenter: Float, gutter: Float) {
    val r = m.measure(text, style)
    drawText(r, color, Offset(gutter - r.size.width - 6.dp.toPx(), (yCenter - r.size.height / 2f).coerceAtLeast(0f)))
}

