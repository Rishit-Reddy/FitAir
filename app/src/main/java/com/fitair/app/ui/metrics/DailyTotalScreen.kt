package com.fitair.app.ui.metrics

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.fitair.app.core.Format
import com.fitair.app.data.dao.WaterDao
import com.fitair.app.data.metrics.MetricId
import com.fitair.app.ui.components.DetailHeader
import com.fitair.app.ui.components.EmptyState
import com.fitair.app.ui.components.InlineError
import com.fitair.app.ui.components.Page
import com.fitair.app.ui.components.SectionBreak
import com.fitair.app.ui.components.SectionHeader
import com.fitair.app.ui.components.SelectionCard
import com.fitair.app.ui.components.SubTabs
import com.fitair.app.ui.components.charts.BarChart
import com.fitair.app.ui.theme.Spacing
import com.fitair.app.ui.theme.Type
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val SPANS = listOf(7, 30, 90)
private val DAY_FMT = DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault())
private val SHORT_FMT = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())
private val WEEKDAY_FMT = DateTimeFormatter.ofPattern("EEE", Locale.getDefault())

private class Spec(val title: String, val unit: String, val noun: String, val fmt: (Double) -> String)

private fun spec(m: MetricId): Spec = when (m) {
    MetricId.Steps -> Spec("Steps", "steps", "steps") { Format.thousands(Math.round(it)) }
    MetricId.Energy -> Spec("Energy burned", "kcal", "kcal") { Format.thousands(Math.round(it)) }
    MetricId.Distance -> Spec("Distance", "km", "km") { String.format(Locale.US, "%.1f", it) }
    else -> Spec("Water", "L", "L") { String.format(Locale.US, "%.2f", it).trimEnd('0').trimEnd('.') }
}

/** "120 more", "1.5 less", or null when the rounded gap is zero. */
private fun gap(diff: Double, s: Spec): String? {
    val a = s.fmt(Math.abs(diff))
    if (a == "0" || a == "0.0") return null
    return "$a ${if (diff > 0) "more" else "less"}"
}

/**
 * Steps, energy, distance or water over 7, 30 or 90 days (docs/PLAN_090 5.1): bars with the period average, the tapped day under the
 * chart (the card keeps its height), and the average against the same number of days before. No goals, no judgement.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DailyTotalScreen(metric: MetricId, onBack: () -> Unit) {
    val vm: DailyTotalVm = viewModel(key = "daily-${metric.name}")
    val sp = spec(metric)
    var spanIdx by rememberSaveable { mutableIntStateOf(0) }
    var selected by remember(metric, spanIdx) { mutableStateOf<Int?>(null) }
    LaunchedEffect(metric, spanIdx) { vm.load(metric, SPANS[spanIdx]) }

    Column(Modifier.fillMaxSize()) {
        DetailHeader(sp.title, onBack)
        Page(Modifier.weight(1f)) {
            vm.error?.let { InlineError(it, onRetry = { vm.load(metric, SPANS[spanIdx]) }) }
            SubTabs(SPANS.map { "$it days" }, spanIdx, { spanIdx = it })
            Spacer(Modifier.height(Spacing.l))
            val ui = vm.ui
            if (ui == null || ui.metric != metric || ui.span != SPANS[spanIdx]) {
                if (vm.error == null) Box(Modifier.fillMaxWidth().padding(vertical = Spacing.xxl), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                return@Page
            }
            if (ui.values.all { it == null }) {
                EmptyState("Nothing recorded in these ${ui.span} days", "Sync from Health Connect on Today, then check back.")
                return@Page
            }
            val since = ui.since
            val xl = ui.days.mapIndexed { i, d ->
                if (ui.span == 7) d.format(WEEKDAY_FMT) else if (i == 0 || i == ui.days.lastIndex || i == ui.days.size / 2) d.format(SHORT_FMT) else ""
            }
            val baseline = (ui.waterGoalL ?: since.avg)?.toFloat()
            BarChart(ui.values.map { it?.toFloat() }, Modifier.fillMaxWidth(), selectedIndex = selected, onSelect = { selected = it }, baseline = baseline, xLabels = xl)
            Text(if (ui.waterGoalL != null) "Dashed line: your water goal" else "Dashed line: the average of the finished days",
                style = Type.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(Spacing.m))

            val idx = selected?.takeIf { it in ui.days.indices } ?: ui.days.lastIndex
            val v = ui.values[idx]
            val isToday = idx == ui.days.lastIndex
            SelectionCard(
                (if (isToday) "Today so far · " else "") + ui.days[idx].format(DAY_FMT),
                if (selected != null && !isToday) "Back to today" else null, if (selected != null && !isToday) ({ selected = null }) else null,
            ) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(v?.let { sp.fmt(it) } ?: Format.DASH, style = MaterialTheme.typography.headlineMedium)
                    Spacer(Modifier.width(Spacing.xs))
                    Text(sp.unit, style = Type.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = Spacing.xs))
                }
                val a = since.avg
                if (v != null && a != null && !isToday) gap(v - a, sp)?.let {
                    Text("$it than the average", style = Type.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (v == null) Text("Nothing recorded this day.", style = Type.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            SectionBreak()
            SectionHeader("Over ${ui.span} days")
            Spacer(Modifier.height(Spacing.s))
            val perDay = if (ui.waterGoalL != null) "a day on days you logged" else "a day"
            if (since.avg != null) {
                val cmp = since.diff?.let { d -> if (since.prevAvg != null && ui.prev.count { it != null } >= 3) (gap(d, sp)?.let { "$it than the ${ui.span} days before" } ?: "about the same as the ${ui.span} days before") else null }
                Text("Average ${sp.fmt(since.avg)} ${sp.unit} $perDay" + (cmp?.let { " · $it" } ?: ""), style = Type.body)
            } else Text("No finished day with a reading yet.", style = Type.body)
            com.fitair.app.data.metrics.MetricStats.total(ui.values)?.let {
                Spacer(Modifier.height(Spacing.xs))
                Text("Total ${sp.fmt(it)} ${sp.unit}", style = Type.body)
            }
            Spacer(Modifier.height(Spacing.xs))
            Text("Today is still filling up, so it is not in the average.", style = Type.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (metric == MetricId.Energy) {
                Spacer(Modifier.height(Spacing.s))
                Text("Includes the energy your body uses at rest; estimated by Google from heart rate and profile.",
                    style = Type.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            if (metric == MetricId.Water) {
                SectionBreak()
                SectionHeader("Today's drinks")
                Spacer(Modifier.height(Spacing.s))
                if (ui.entries.isEmpty()) Text("Nothing logged today.", style = Type.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                else {
                    var confirm by remember { mutableStateOf<WaterDao.Entry?>(null) }
                    ui.entries.forEach { e ->
                        val at = Instant.ofEpochMilli(e.t).atZone(ZoneId.systemDefault())
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = Spacing.minTouch)
                                .combinedClickable(onClick = { confirm = if (confirm == e) null else e }, onLongClick = { confirm = e }),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(Format.clock(at.hour, at.minute), style = Type.body, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(64.dp))
                            Text("${e.ml} ml", style = Type.body, modifier = Modifier.weight(1f))
                            if (confirm == e) androidx.compose.material3.TextButton(onClick = { vm.deleteWater(e, metric, SPANS[spanIdx]); confirm = null }) { Text("Delete", style = Type.label) }
                        }
                    }
                    Text("Press and hold a drink to delete it.", style = Type.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
