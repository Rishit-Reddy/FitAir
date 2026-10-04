package com.fitair.app.ui.sleep

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.fitair.app.core.Format
import com.fitair.app.ui.components.SectionCard
import com.fitair.app.ui.theme.LocalStageColors
import com.fitair.app.ui.theme.Spacing
import com.fitair.app.ui.theme.Type
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

private val LANE_NAMES = listOf("Awake", "REM", "Light", "Deep")
private val GUTTER = 44.dp

private fun clockOf(ms: Long, z: ZoneId = ZoneId.systemDefault()) = Instant.ofEpochMilli(ms).atZone(z).let { Format.clock(it.hour, it.minute) }

/** Hour marks to label between [start] and [end]: every hour, or every 2 h when the night is longer than 6 h. */
private fun hourMarks(start: Long, end: Long, z: ZoneId): List<Long> {
    val step = if (end - start > 6 * 3_600_000L) 2L else 1L
    var t = Instant.ofEpochMilli(start).atZone(z).withMinute(0).withSecond(0).withNano(0).plusHours(1)
    val out = ArrayList<Long>()
    while (t.toInstant().toEpochMilli() < end) {
        if (t.hour % step.toInt() == 0) out.add(t.toInstant().toEpochMilli())
        t = t.plusHours(1)
    }
    return out
}

/** The night's stages over time: four lanes (Awake at the top, Deep at the bottom) in the stage colours, hour labels under. */
@Composable
fun StageTimeline(segments: List<StageSeg>, startMs: Long, endMs: Long, modifier: Modifier = Modifier) {
    val sc = LocalStageColors.current
    val colors = listOf(sc.awake, sc.rem, sc.light, sc.deep)
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    val guide = MaterialTheme.colorScheme.outlineVariant
    val measurer = rememberTextMeasurer()
    val cap = Type.axis
    val z = ZoneId.systemDefault()
    val span = (endMs - startMs).coerceAtLeast(1L)
    Canvas(modifier.fillMaxWidth().height(140.dp).semantics { contentDescription = "Sleep stages over the night from ${clockOf(startMs)} to ${clockOf(endMs)}" }) {
        val left = GUTTER.toPx(); val plotW = size.width - left
        val bottom = size.height - 18.dp.toPx()
        val laneH = bottom / 4f
        fun x(ms: Long) = left + plotW * ((ms - startMs).toFloat() / span).coerceIn(0f, 1f)
        for (l in 0..3) {
            val r = measurer.measure(LANE_NAMES[l], cap)
            drawText(r, dim, Offset(0f, laneH * l + (laneH - r.size.height) / 2f))
            drawLine(guide, Offset(left, laneH * (l + 1)), Offset(size.width, laneH * (l + 1)), 0.5.dp.toPx())
        }
        // thin connectors between consecutive stretches, so the night reads as one line
        segments.zipWithNext().forEach { (a, b) ->
            if (b.startMs - a.endMs < 120_000L && a.lane != b.lane) {
                val xc = x(b.startMs)
                drawLine(guide, Offset(xc, laneH * a.lane + laneH / 2f), Offset(xc, laneH * b.lane + laneH / 2f), 1.dp.toPx())
            }
        }
        val h = laneH * 0.62f
        for (s in segments) {
            val a = x(s.startMs); val b = x(s.endMs)
            drawRoundRect(colors[s.lane], Offset(a, laneH * s.lane + (laneH - h) / 2f), Size((b - a).coerceAtLeast(1.5.dp.toPx()), h), CornerRadius(2.dp.toPx()))
        }
        val labels = listOf(startMs) + hourMarks(startMs, endMs, z) + listOf(endMs)
        var lastRight = -1f
        labels.forEachIndexed { i, t ->
            val r = measurer.measure(clockOf(t, z), cap)
            val cx = x(t)
            val tx = (cx - r.size.width / 2f).coerceIn(left, size.width - r.size.width)
            // keep start and end; drop an hour label that would collide with its neighbour
            val isEdge = i == 0 || i == labels.lastIndex
            if (!isEdge && (tx < lastRight + 6.dp.toPx() || tx + r.size.width > x(endMs) - 40.dp.toPx())) return@forEachIndexed
            drawText(r, dim, Offset(tx, bottom + 4.dp.toPx()))
            lastRight = tx + r.size.width
        }
    }
}

/** Heart rate while asleep: the five-minute line with the lowest point marked, then lowest, average, HRV and breathing. */
@Composable
fun SleepHeartCard(d: NightDetail) {
    val hr = d.hr
    val ink = MaterialTheme.colorScheme.primary
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    val measurer = rememberTextMeasurer()
    val cap = Type.axis
    SectionCard("Heart rate while asleep") {
        if (hr == null || hr.none { !it.isNaN() }) {
            Text("No heart rate recorded during this sleep.", style = Type.bodySmall, color = dim)
            return@SectionCard
        }
        Canvas(Modifier.fillMaxWidth().height(96.dp).semantics { contentDescription = "Heart rate during sleep, lowest ${d.hrLow ?: "unknown"} beats per minute" }) {
            val vals = hr.filter { !it.isNaN() }
            var lo = vals.min() - 4f; var hi = vals.max() + 10f
            if (hi - lo < 16f) hi = lo + 16f
            val n = hr.size
            fun x(i: Int) = if (n <= 1) size.width / 2f else size.width * i / (n - 1)
            fun y(v: Float) = size.height - (v - lo) / (hi - lo) * size.height
            val stroke = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
            var i = 0
            while (i < n) {
                if (hr[i].isNaN()) { i++; continue }
                var j = i
                while (j + 1 < n && !hr[j + 1].isNaN()) j++
                if (j == i) drawCircle(ink, 2.dp.toPx(), Offset(x(i), y(hr[i])))
                else { val p = Path(); p.moveTo(x(i), y(hr[i])); for (k in i + 1..j) p.lineTo(x(k), y(hr[k])); drawPath(p, ink, style = stroke) }
                i = j + 1
            }
            val iMin = hr.indices.filter { !hr[it].isNaN() }.minByOrNull { hr[it] }
            if (iMin != null) {
                val p = Offset(x(iMin), y(hr[iMin]))
                drawCircle(ink, 4.dp.toPx(), p)
                val r = measurer.measure(Math.round(hr[iMin]).toString(), cap)
                drawText(r, dim, Offset((p.x - r.size.width / 2f).coerceIn(0f, size.width - r.size.width), (p.y + 6.dp.toPx()).coerceAtMost(size.height - r.size.height)))
            }
            val avg = vals.average().toFloat()
            drawLine(dim.copy(alpha = 0.6f), Offset(0f, y(avg)), Offset(size.width, y(avg)), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx())))
        }
        Spacer(Modifier.height(Spacing.xs))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(d.bedtime ?: "", style = Type.axis, color = dim); Text(d.wake ?: "", style = Type.axis, color = dim)
        }
        Spacer(Modifier.height(Spacing.m))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
            NightStat("Lowest", d.hrLow?.let { "$it" } ?: Format.DASH, d.hrLowMs?.let { "at ${clockOf(it)}" }, Modifier.weight(1f))
            NightStat("Average", d.hrAvg?.let { "$it" } ?: Format.DASH, "bpm", Modifier.weight(1f))
            NightStat("HRV", d.hrvMs?.let { "${Math.round(it)}" } ?: Format.DASH, "ms", Modifier.weight(1f))
            NightStat("Breathing", d.breathsPerMin?.let { String.format(java.util.Locale.US, "%.1f", it) } ?: Format.DASH, "/min", Modifier.weight(1f))
        }
    }
}

@Composable
private fun NightStat(label: String, value: String, sub: String?, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, style = Type.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        Text(value, style = MaterialTheme.typography.titleLarge)
        if (sub != null) Text(sub, style = Type.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

/**
 * Bed and wake times: one bar per night from falling asleep to waking, on a clock axis that starts at 18:00 the evening before.
 * Tapping a bar selects that night, like the other history charts.
 */
@Composable
fun BedWakeCard(nights: List<Night>, windows: Map<LocalDate, LongArray>, selectedIndex: Int?, onSelect: (Int) -> Unit) {
    val z = ZoneId.systemDefault()
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    val guide = MaterialTheme.colorScheme.outlineVariant
    val bar = LocalStageColors.current.light
    val primary = MaterialTheme.colorScheme.primary
    val measurer = rememberTextMeasurer()
    val cap = Type.axis
    val select by rememberUpdatedState(onSelect)
    val spans = nights.map { n -> windows[n.date]?.let { w -> SleepTiming.offsetMin(w[0], n.date, z) to SleepTiming.offsetMin(w[1], n.date, z) } }
    val present = spans.filterNotNull()
    SectionCard("Bed and wake times") {
        if (present.size < 2) {
            Text("Needs a few more nights.", style = Type.bodySmall, color = dim)
            return@SectionCard
        }
        val bed = SleepTiming.median(present.map { it.first }); val wake = SleepTiming.median(present.map { it.second })
        val bedSd = SleepTiming.sd(present.map { it.first })
        Row(Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text("Usual bedtime", style = Type.bodySmall, color = dim)
                Text(bed?.let { SleepTiming.offsetClock(it) } ?: Format.DASH, style = MaterialTheme.typography.titleLarge)
            }
            Column(Modifier.weight(1f)) {
                Text("Usual wake", style = Type.bodySmall, color = dim)
                Text(wake?.let { SleepTiming.offsetClock(it) } ?: Format.DASH, style = MaterialTheme.typography.titleLarge)
            }
            Column(Modifier.weight(1f)) {
                Text("Bedtime varies", style = Type.bodySmall, color = dim)
                Text(bedSd?.let { "±${Format.hm(Math.round(it))}" } ?: Format.DASH, style = MaterialTheme.typography.titleLarge)
            }
        }
        Spacer(Modifier.height(Spacing.m))
        val lo = Math.floor(present.minOf { it.first } / 60.0) * 60.0
        val hi = Math.ceil(present.maxOf { it.second } / 60.0) * 60.0
        val n = nights.size
        Canvas(
            Modifier.fillMaxWidth().height(180.dp)
                .semantics { contentDescription = "Bedtime and wake time for the last $n nights" }
                .pointerInput(n) {
                    val left = GUTTER.toPx()
                    detectTapGestures { p -> if (p.x >= left) select((((p.x - left) / (size.width - left)) * n).toInt().coerceIn(0, n - 1)) }
                },
        ) {
            val left = GUTTER.toPx(); val plotW = size.width - left
            val top = 6.dp.toPx(); val bottom = size.height - 6.dp.toPx()
            fun y(m: Double) = top + ((m - lo) / (hi - lo).coerceAtLeast(60.0)).toFloat() * (bottom - top)
            // hour guides with labels every 2 h (every 3 h on a long axis)
            val step = if (hi - lo > 14 * 60) 180.0 else 120.0
            var m = lo
            while (m <= hi) {
                drawLine(guide, Offset(left, y(m)), Offset(size.width, y(m)), 0.5.dp.toPx())
                val r = measurer.measure(SleepTiming.offsetClock(m), cap)
                drawText(r, dim, Offset(0f, (y(m) - r.size.height / 2f).coerceIn(0f, size.height - r.size.height)))
                m += step
            }
            val slot = plotW / n
            val bw = (slot * 0.55f).coerceAtLeast(2.dp.toPx())
            selectedIndex?.takeIf { it in 0 until n }?.let { i -> drawRect(primary.copy(alpha = 0.10f), Offset(left + slot * i, top), Size(slot, bottom - top)) }
            spans.forEachIndexed { i, s ->
                if (s == null) return@forEachIndexed
                val x = left + slot * i + (slot - bw) / 2f
                drawRoundRect(bar, Offset(x, y(s.first)), Size(bw, (y(s.second) - y(s.first)).coerceAtLeast(2.dp.toPx())), CornerRadius(minOf(3.dp.toPx(), bw / 2f)))
            }
        }
        Spacer(Modifier.height(Spacing.xs))
        Row(Modifier.fillMaxWidth().padding(start = GUTTER), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(SleepModel.xLabel(nights.first().date), style = Type.axis, color = dim)
            Text(SleepModel.xLabel(nights.last().date), style = Type.axis, color = dim)
        }
        Spacer(Modifier.height(Spacing.s))
        Text("Each bar runs from falling asleep (top) to waking (bottom). Regular times usually make sleep come easier, even on shift weeks.",
            style = Type.bodySmall, color = dim)
    }
}
