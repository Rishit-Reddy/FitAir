package com.fitair.app.ui.metrics

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.fitair.app.core.Format
import com.fitair.app.data.metrics.HeartExtras
import com.fitair.app.ui.components.Hairline
import com.fitair.app.ui.components.SectionHeader
import com.fitair.app.ui.components.charts.LineChart
import com.fitair.app.ui.theme.LocalZoneColors
import com.fitair.app.ui.theme.Shapes
import com.fitair.app.ui.theme.Spacing
import com.fitair.app.ui.theme.Type
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Names of zones 1..4 (Light..Peak), matching the colour index + 1. */
internal val ZONE_ROW_NAMES = listOf("Light", "Moderate", "Vigorous", "Peak")

private fun clockOf(ms: Long): String = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).let { Format.clock(it.hour, it.minute) }

@Composable
internal fun HeartCard(title: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) =
    com.fitair.app.ui.components.SectionCard(title, modifier, content)

/** Small number tile: caption, value, optional dim line under it. */
@Composable
internal fun StatTile(label: String, value: String, modifier: Modifier = Modifier, sub: String? = null) {
    Column(modifier.clip(Shapes.card).background(MaterialTheme.colorScheme.surfaceVariant).padding(Spacing.m)) {
        Text(label.uppercase(), style = Type.caption, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        Spacer(Modifier.height(Spacing.xs))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, style = MaterialTheme.typography.headlineSmall)
            if (value != Format.DASH) {
                Spacer(Modifier.width(Spacing.xs))
                Text("bpm", style = Type.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 3.dp))
            }
        }
        if (sub != null) Text(sub, style = Type.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

/** Big number with its unit, then the zone name after a dot in the zone's colour. */
@Composable
internal fun ZoneReading(bpm: Int, zone: String, color: Color) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(bpm.toString(), style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.width(Spacing.xs))
        Text("bpm", style = Type.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 4.dp))
        Spacer(Modifier.width(Spacing.m))
        Box(Modifier.padding(bottom = 9.dp).size(10.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(Spacing.xs))
        Text(zone, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 2.dp))
    }
}

/** One horizontal bar split by minutes in Light..Peak, in zone colours; a faint track when there are none. */
@Composable
internal fun ZoneSplitBar(minutes: IntArray?, modifier: Modifier = Modifier) {
    val zc = LocalZoneColors.current
    val track = MaterialTheme.colorScheme.outlineVariant
    Canvas(modifier.fillMaxWidth().height(10.dp).clip(Shapes.chip).clearAndSetSemantics { }) {
        drawRect(track)
        val total = minutes?.sum() ?: 0
        if (total <= 0) return@Canvas
        var x = 0f
        minutes!!.forEachIndexed { i, m ->
            val w = size.width * m / total
            drawRect(zc[i + 1], Offset(x, 0f), Size(w, size.height)); x += w
        }
    }
}

/** Time in each zone for the shown day: split bar, then one row per zone with its colour dot, threshold and time. */
@Composable
internal fun ZoneTimeCard(zoneMin: IntArray?, zoneBpm: FloatArray, hrMax: Float, hrMaxSource: String) {
    val zc = LocalZoneColors.current
    HeartCard("Time in each zone") {
        ZoneSplitBar(zoneMin)
        Spacer(Modifier.height(Spacing.s))
        ZONE_ROW_NAMES.forEachIndexed { i, n ->
            Row(Modifier.fillMaxWidth().padding(vertical = Spacing.m), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(zc[i + 1]))
                Spacer(Modifier.width(Spacing.s))
                Text(n, style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.width(Spacing.s))
                Text("${Math.round(zoneBpm[i])}+ bpm", style = Type.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                Text(zoneMin?.get(i)?.let { Format.hm(it.toLong()) } ?: Format.DASH, style = MaterialTheme.typography.bodyLarge)
            }
            if (i < ZONE_ROW_NAMES.lastIndex) Hairline()
        }
        Spacer(Modifier.height(Spacing.s))
        Text("Zones use a maximum heart rate of ${Math.round(hrMax)} bpm ($hrMaxSource). You can change it in Settings.",
            style = Type.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** How far heart rate fell in the two minutes after each exercise session of the day. */
@Composable
internal fun RecoveryCard(items: List<Recovery>) {
    HeartCard("After exercise") {
        items.forEachIndexed { i, r ->
            Row(Modifier.fillMaxWidth().padding(vertical = Spacing.m), verticalAlignment = Alignment.CenterVertically) {
                Text("Ended ${clockOf(r.endMs)}", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Text(if (r.drop > 0) "−${r.drop} bpm in 2 min" else "no drop in 2 min", style = MaterialTheme.typography.bodyLarge)
            }
            if (i < items.lastIndex) Hairline()
        }
        Spacer(Modifier.height(Spacing.xs))
        Text("Compare with your own earlier sessions. Wrist readings in the minutes after stopping are approximate.",
            style = Type.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Resting heart rate over 30 days: last 7 days, 30-day average and the change from the week before, then the line. */
@Composable
internal fun RestingTrendCard(t: HeartTrendsUi) {
    HeartCard("Resting heart rate · 30 days") {
        val week = HeartExtras.meanOf(t.rest30.takeLast(7), 3)
        val month = HeartExtras.meanOf(t.rest30, 7)
        val change = HeartExtras.weekChange(t.rest30)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
            MiniStat("Last 7 days", week?.let { Math.round(it).toString() } ?: Format.DASH, Modifier.weight(1f))
            MiniStat("30 days", month?.let { Math.round(it).toString() } ?: Format.DASH, Modifier.weight(1f))
            MiniStat("vs week before", change?.let { val r = Math.round(it); if (r > 0) "+$r" else if (r < 0) "−${-r}" else "±0" } ?: Format.DASH, Modifier.weight(1f))
        }
        Spacer(Modifier.height(Spacing.m))
        if (t.rest30.count { it != null } < 2) {
            Text("Not enough resting readings yet.", style = Type.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            val fmt = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())
            LineChart(t.rest30.map { it?.toFloat() }, xLabels = t.days30.map { it.format(fmt) })
            Spacer(Modifier.height(Spacing.s))
            Text("A resting rate a few beats above your usual for several days is a pattern worth noticing, often after short sleep, hard days or illness.",
                style = Type.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun MiniStat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, style = Type.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        Text(value, style = MaterialTheme.typography.titleLarge)
    }
}

/** Light..Peak minutes of the last seven days as stacked bars, today last, with the week's moderate-or-harder total. */
@Composable
internal fun WeekZonesCard(t: HeartTrendsUi) {
    val zc = LocalZoneColors.current
    val track = MaterialTheme.colorScheme.outlineVariant
    HeartCard("Zones · last 7 days") {
        val modPlus = t.zones7.sumOf { z -> z?.let { it[1] + it[2] + it[3] } ?: 0 }
        Text("${Format.hm(modPlus.toLong())} moderate or harder", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(Spacing.m))
        val max = (t.zones7.maxOfOrNull { it?.sum() ?: 0 } ?: 0).coerceAtLeast(1)
        Canvas(Modifier.fillMaxWidth().height(120.dp).semantics { contentDescription = "Minutes in zones for the last seven days" }) {
            val slot = size.width / 7f
            val bw = slot * 0.5f
            t.zones7.forEachIndexed { d, z ->
                val x = slot * d + (slot - bw) / 2f
                if (z == null || z.sum() == 0) {
                    drawRoundRect(track, Offset(x, size.height - 2.dp.toPx()), Size(bw, 2.dp.toPx()), CornerRadius(1.dp.toPx()))
                    return@forEachIndexed
                }
                var y = size.height
                z.forEachIndexed { i, m ->
                    val h = size.height * m / max
                    if (h > 0f) drawRect(zc[i + 1], Offset(x, y - h), Size(bw, h))
                    y -= h
                }
            }
        }
        Spacer(Modifier.height(Spacing.xs))
        Row(Modifier.fillMaxWidth()) {
            val fmt = DateTimeFormatter.ofPattern("EEEEE", Locale.getDefault())
            t.days7.forEach { Text(it.format(fmt), style = Type.axis, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.weight(1f)) }
        }
        Spacer(Modifier.height(Spacing.s))
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.m)) {
            ZONE_ROW_NAMES.forEachIndexed { i, n ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(zc[i + 1]))
                    Spacer(Modifier.width(Spacing.xs))
                    Text(n, style = Type.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** Shift days vs off days: average heart rate over the day and resting rate the morning after. */
@Composable
internal fun ShiftCompareCard(s: ShiftCompare) {
    fun b(v: Double?) = v?.let { "${Math.round(it)} bpm" } ?: Format.DASH
    HeartCard("Shift days vs off days · 4 weeks") {
        Row(Modifier.fillMaxWidth()) {
            Spacer(Modifier.weight(1.4f))
            Text("Shift (${s.shiftDays})", style = Type.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
            Text("Off (${s.offDays})", style = Type.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
        }
        CompareRow("Average over the day", b(s.shiftAvg), b(s.offAvg))
        Hairline()
        CompareRow("Resting, next morning", b(s.restAfterShift), b(s.restAfterOff))
    }
}

@Composable
private fun CompareRow(label: String, a: String, b: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = Spacing.m), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1.4f))
        Text(a, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
        Text(b, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
    }
}

/** "Overnight low" tile text under the number: the clock time it happened. */
internal fun lowSub(low: HeartExtras.Low?): String? = low?.let { "at ${clockOf(it.atMs)}" }
