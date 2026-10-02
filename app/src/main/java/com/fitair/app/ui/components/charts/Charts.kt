package com.fitair.app.ui.components.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.fitair.app.ui.theme.Type
import java.util.Locale

/*
 * Pure Compose Canvas charts. Nulls are gaps, at most two faint guides, min/max y labels only,
 * 3-4 x labels, tap or drag selects an index. Colours come from MaterialTheme (light and dark).
 */

private val LEFT = 30.dp      // y label gutter
private val BOTTOM = 18.dp    // x label gutter
private val DEFAULT_HEIGHT = 160.dp

/** Indices of the x labels to show: first, last and evenly spaced ones in between, at most [count]. */
internal fun labelIndices(n: Int, count: Int = 4): List<Int> {
    if (n <= 0) return emptyList()
    if (n <= count) return (0 until n).toList()
    return (0 until count).map { Math.round(it * (n - 1).toFloat() / (count - 1)) }.distinct()
}

/** Index under pixel x for [n] equal slots across [left, left + width]; null when n is 0. */
internal fun indexAt(x: Float, left: Float, width: Float, n: Int): Int? {
    if (n <= 0 || width <= 0f) return null
    return (((x - left) / width) * n).toInt().coerceIn(0, n - 1)
}

private fun fmt(v: Float): String =
    if (Math.abs(v) >= 100f || v == Math.rint(v.toDouble()).toFloat()) Math.round(v).toString() else String.format(Locale.US, "%.1f", v)

private fun describe(kind: String, values: List<Float?>, selected: Int?, xLabels: List<String>): String {
    val present = values.filterNotNull()
    if (present.isEmpty()) return "$kind chart, no data"
    val sel = selected?.takeIf { it in values.indices }?.let { i ->
        val label = xLabels.getOrNull(i)?.let { "$it: " } ?: "item ${i + 1}: "
        ". Selected $label${values[i]?.let { fmt(it) } ?: "no data"}"
    } ?: ""
    return "$kind chart, ${present.size} of ${values.size} values, from ${fmt(present.min())} to ${fmt(present.max())}, latest ${fmt(present.last())}$sel"
}

private fun DrawScope.guides(color: Color, left: Float, right: Float, top: Float, bottom: Float) {
    val sw = 0.5.dp.toPx()
    drawLine(color, Offset(left, bottom), Offset(right, bottom), sw)
    drawLine(color, Offset(left, top), Offset(right, top), sw)
}

private fun DrawScope.yLabel(m: TextMeasurer, text: String, color: Color, yCenter: Float, gutter: Float) {
    val r = m.measure(text, captionStyle)
    drawText(r, color, Offset(gutter - r.size.width - 6.dp.toPx(), (yCenter - r.size.height / 2f).coerceAtLeast(0f)))
}

private fun DrawScope.xLabels(m: TextMeasurer, labels: List<String>, n: Int, color: Color, left: Float, plotW: Float, top: Float) {
    if (labels.size != n || n == 0) return
    val slot = plotW / n
    for (i in labelIndices(n)) {
        val r = m.measure(labels[i], captionStyle)
        val cx = left + slot * (i + 0.5f)
        val x = (cx - r.size.width / 2f).coerceIn(left, (left + plotW - r.size.width).coerceAtLeast(left))
        drawText(r, color, Offset(x, top))
    }
}

private fun Modifier.selectable(n: Int, onSelect: (Int) -> Unit) = this
    .pointerInput(n) {
        val left = LEFT.toPx()
        detectTapGestures { p -> indexAt(p.x, left, size.width - left, n)?.let(onSelect) }
    }
    .pointerInput(n) {
        val left = LEFT.toPx()
        detectHorizontalDragGestures { change, _ ->
            change.consume()
            indexAt(change.position.x, left, size.width - left, n)?.let(onSelect)
        }
    }

private val captionStyle = Type.caption.copy(letterSpacing = androidx.compose.ui.unit.TextUnit.Unspecified)

@Composable
fun BarChart(
    values: List<Float?>,
    modifier: Modifier = Modifier,
    selectedIndex: Int? = null,
    onSelect: (Int) -> Unit = {},
    baseline: Float? = null,
    xLabels: List<String> = emptyList(),
) {
    val cs = MaterialTheme.colorScheme
    val measurer = rememberTextMeasurer()
    val select by rememberUpdatedState(onSelect)
    val n = values.size
    val desc = describe("Bar", values, selectedIndex, xLabels) + (baseline?.let { ", baseline ${fmt(it)}" } ?: "")
    val guide = cs.outlineVariant; val dim = cs.onSurfaceVariant; val primary = cs.primary
    Canvas(
        modifier.fillMaxWidth().height(DEFAULT_HEIGHT).semantics { contentDescription = desc }.selectable(n) { select(it) },
    ) {
        val left = LEFT.toPx(); val bottom = size.height - BOTTOM.toPx(); val top = 8.dp.toPx()
        val plotW = size.width - left
        if (n == 0) return@Canvas
        val top0 = maxOf(values.filterNotNull().maxOrNull() ?: 0f, baseline ?: 0f)
        val max = if (top0 <= 0f) 1f else top0 * 1.1f
        fun y(v: Float) = bottom - (v / max).coerceIn(0f, 1f) * (bottom - top)
        guides(guide, left, size.width, y(max / 1.1f).coerceAtLeast(top), bottom)
        yLabel(measurer, "0", dim, bottom, left)
        yLabel(measurer, fmt(max / 1.1f), dim, y(max / 1.1f), left)
        val slot = plotW / n
        val bw = (slot * 0.62f).coerceAtLeast(1.5.dp.toPx())
        selectedIndex?.takeIf { it in 0 until n }?.let { i ->
            drawRect(primary.copy(alpha = 0.10f), Offset(left + slot * i, top), Size(slot, bottom - top))
        }
        values.forEachIndexed { i, v ->
            if (v == null) return@forEachIndexed
            val base = if (selectedIndex == i) primary else dim
            val x = left + slot * i + (slot - bw) / 2f
            val yt = y(v).coerceAtMost(bottom - 1.dp.toPx())
            drawRoundRect(base, Offset(x, yt), Size(bw, bottom - yt), androidx.compose.ui.geometry.CornerRadius(minOf(2.dp.toPx(), bw / 2f)))
        }
        baseline?.let {
            val yb = y(it)
            drawLine(dim, Offset(left, yb), Offset(size.width, yb), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx())))
        }
        xLabels(measurer, xLabels, n, dim, left, plotW, bottom + 4.dp.toPx())
    }
}

@Composable
fun LineChart(
    values: List<Float?>,
    modifier: Modifier = Modifier,
    selectedIndex: Int? = null,
    onSelect: (Int) -> Unit = {},
    band: Pair<Float, Float>? = null,
    xLabels: List<String> = emptyList(),
    latestTone: Color? = null,
) {
    val cs = MaterialTheme.colorScheme
    val measurer = rememberTextMeasurer()
    val select by rememberUpdatedState(onSelect)
    val n = values.size
    val desc = describe("Line", values, selectedIndex, xLabels) + (band?.let { ", typical range ${fmt(it.first)} to ${fmt(it.second)}" } ?: "")
    val guide = cs.outlineVariant; val dim = cs.onSurfaceVariant; val primary = cs.primary
    Canvas(
        modifier.fillMaxWidth().height(DEFAULT_HEIGHT).semantics { contentDescription = desc }.selectable(n) { select(it) },
    ) {
        val left = LEFT.toPx(); val bottom = size.height - BOTTOM.toPx(); val top = 8.dp.toPx()
        val plotW = size.width - left
        val present = values.filterNotNull()
        if (n == 0 || present.isEmpty()) return@Canvas
        var lo = minOf(present.min(), band?.first ?: Float.MAX_VALUE)
        var hi = maxOf(present.max(), band?.second ?: -Float.MAX_VALUE)
        if (hi - lo < 1e-3f) { lo -= 1f; hi += 1f }
        val pad = (hi - lo) * 0.1f
        lo -= pad; hi += pad
        fun y(v: Float) = bottom - ((v - lo) / (hi - lo)).coerceIn(0f, 1f) * (bottom - top)
        val slot = plotW / n
        fun x(i: Int) = left + slot * (i + 0.5f)
        guides(guide, left, size.width, top, bottom)
        yLabel(measurer, fmt(hi - pad), dim, y(hi - pad), left)
        yLabel(measurer, fmt(lo + pad), dim, y(lo + pad), left)
        band?.let { (a, b) ->
            drawRect(cs.onSurface.copy(alpha = 0.06f), Offset(left, y(b)), Size(plotW, (y(a) - y(b)).coerceAtLeast(1f)))
        }
        selectedIndex?.takeIf { it in 0 until n }?.let { i ->
            drawRect(primary.copy(alpha = 0.10f), Offset(left + slot * i, top), Size(slot, bottom - top))
        }
        val stroke = Stroke(2.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round)
        var path = Path(); var run = 0
        fun flush() {
            if (run == 1) { /* single point drawn as a dot below */ } else if (run > 1) drawPath(path, dim, style = stroke)
            path = Path(); run = 0
        }
        values.forEachIndexed { i, v ->
            if (v == null) { flush(); return@forEachIndexed }
            if (run == 0) path.moveTo(x(i), y(v)) else path.lineTo(x(i), y(v))
            run++
            val prevNull = i == 0 || values[i - 1] == null
            val nextNull = i == n - 1 || values[i + 1] == null
            if (prevNull && nextNull) drawCircle(dim, 2.5.dp.toPx(), Offset(x(i), y(v)))
        }
        flush()
        if (latestTone != null) {
            val li = values.indexOfLast { it != null }
            if (li >= 0) drawCircle(latestTone, 4.dp.toPx(), Offset(x(li), y(values[li]!!)))
        }
        selectedIndex?.takeIf { it in 0 until n }?.let { i ->
            values[i]?.let { v -> drawCircle(primary, 4.dp.toPx(), Offset(x(i), y(v))); drawCircle(cs.background, 1.8.dp.toPx(), Offset(x(i), y(v))) }
        }
        xLabels(measurer, xLabels, n, dim, left, plotW, bottom + 4.dp.toPx())
    }
}
