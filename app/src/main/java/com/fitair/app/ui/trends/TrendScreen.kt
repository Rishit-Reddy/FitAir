package com.fitair.app.ui.trends

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.lifecycle.viewmodel.compose.viewModel
import com.fitair.app.core.Format
import com.fitair.app.ui.copy.Copy
import androidx.compose.material3.TextButton
import com.fitair.app.ui.components.*
import com.fitair.app.ui.components.charts.BarChart
import com.fitair.app.ui.components.charts.LineChart
import com.fitair.app.ui.theme.Shapes
import com.fitair.app.ui.theme.Spacing
import com.fitair.app.ui.theme.Type
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private val SPANS = listOf(14, 30, 90)
private val DAY_FMT = DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault())
private val SHORT_FMT = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())

private fun fmt(v: Double?, metric: TrendMetric): String = v?.let { Math.round(it).toString() } ?: Format.DASH

/** 14/30/90-day trend for one metric. Tapping a day shows its value (and readiness drivers). */
@Composable
fun TrendScreen(metric: TrendMetric, onBack: () -> Unit) {
    val vm: TrendVm = viewModel(key = "trend-${metric.name}")
    var spanIdx by rememberSaveable { mutableIntStateOf(1) }
    var selected by remember(metric, spanIdx) { mutableStateOf<Int?>(null) }
    val deltaUnit = if (metric == TrendMetric.Readiness) "" else metric.unit
    var details by rememberSaveable { mutableStateOf(false) }
    var explain by remember { mutableStateOf(false) }
    val explainKey = when (metric) { TrendMetric.Readiness -> "readiness"; TrendMetric.Hrv -> "hrv"; TrendMetric.RestingHr -> "resting_hr"; TrendMetric.Load -> "load" }
    if (explain) ExplainSheet(explainKey, onDismiss = { explain = false })
    LaunchedEffect(metric, spanIdx) { vm.load(metric, SPANS[spanIdx]) }

    Column(Modifier.fillMaxSize()) {
    DetailHeader(metric.title, onBack)
    Page(Modifier.weight(1f)) {
        val ui = vm.ui
        vm.error?.let { InlineError(it, onRetry = { vm.load(metric, SPANS[spanIdx]) }) }
        if (ui == null || ui.metric != metric) {
            if (vm.error == null) Box(Modifier.fillMaxWidth().padding(vertical = Spacing.xxl), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Page
        }
        val values = ui.days.map { it.value }
        if (values.all { it == null }) {
            EmptyState("No data in this period", "Sync from Health Connect on Today, then check back.")
            return@Page
        }
        val sel = selected?.takeIf { it in ui.days.indices }
        // The header always shows today / the last reading; a selection only fills the card under the chart.
        val shown = ui.days[ui.days.indexOfLast { it.value != null }]
        val xl = ui.days.mapIndexed { i, d -> if (i == 0 || i == ui.days.lastIndex || i == ui.days.size / 2) d.date.format(SHORT_FMT) else "" }
        val floats = values.map { it?.toFloat() }
        val tone = TrendMath.tone(metric, shown.value, ui.band)

        // today's value, what it means, then the history
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionHeader(
                when {
                    shown.date == LocalDate.now() -> "Today"
                    else -> "Last reading · ${shown.date.format(DAY_FMT)}"
                },
                Modifier.weight(1f),
            )
            TextButton(onClick = { explain = true }) { Text("What is this?", style = Type.label) }
        }
        // the verdict comes first, the number second
        val verdict = when (metric) {
            TrendMetric.Readiness -> Copy.readiness(shown.value?.let { Math.round(it).toInt() }).let { v -> v.headline + (v.detail?.let { " \u2014 $it" } ?: "") }
            TrendMetric.Load -> TrendMath.acwrText(ui.days.lastOrNull { it.acwr != null }?.acwr)
            else -> Copy.trendVerdict(metric.title, shown.value?.let { v -> ui.band?.let { TrendMath.side(v, it) } }, metric.higherBetter)
        }
        Text(verdict, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(Spacing.s))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(fmt(shown.value, metric), style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.width(Spacing.xs))
            Text(metric.unit, style = Type.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = Spacing.xs))
            if (tone != Tone.Neutral) {
                Spacer(Modifier.width(Spacing.s))
                StatusDot(toneColor(tone), Modifier.align(Alignment.CenterVertically))
            }
        }
        if (metric != TrendMetric.Load && shown.value != null) {
            Text(TrendMath.deltaText(shown.value, ui.band, deltaUnit), style = Type.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(Spacing.s))
        if (metric != TrendMetric.Load) Text(TrendMath.interpret(metric.title, values, ui.band), style = Type.body)

        if (metric == TrendMetric.Readiness) {
            Spacer(Modifier.height(Spacing.l))
            Column(Modifier.fillMaxWidth().clip(Shapes.card).background(MaterialTheme.colorScheme.surfaceVariant).padding(Spacing.l)) {
                SectionHeader("Main drivers")
                Spacer(Modifier.height(Spacing.xs))
                if (shown.drivers.isEmpty()) Text("No breakdown stored for this day.", style = Type.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                else shown.drivers.forEach { Text(it, style = Type.body) }
            }
        }

        SectionBreak()
        SectionHeader("Trend")
        Spacer(Modifier.height(Spacing.s))
        SubTabs(SPANS.map { "$it days" }, spanIdx, { spanIdx = it })
        Spacer(Modifier.height(Spacing.l))

        if (metric == TrendMetric.Load) {
            val chronic = ui.days.lastOrNull { it.chronic != null }?.chronic
            BarChart(floats, Modifier.fillMaxWidth(), selectedIndex = sel, onSelect = { selected = it },
                baseline = chronic?.toFloat(), xLabels = xl)
        } else {
            val pointColors = TrendMath.pointTones(metric, values, ui.band).map { if (it == Tone.Neutral) null else toneColor(it) }
            LineChart(floats, Modifier.fillMaxWidth(), selectedIndex = sel, onSelect = { selected = it },
                band = ui.band?.let { it.lo.toFloat() to it.hi.toFloat() }, xLabels = xl, pointColors = pointColors)
            if (ui.band != null) {
                Spacer(Modifier.height(Spacing.xs))
                Text("${Copy.BAND_NOTE}, ${fmt(ui.band.lo, metric)}–${fmt(ui.band.hi, metric)} $deltaUnit",
                    style = Type.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        if (sel != null) {
            Spacer(Modifier.height(Spacing.m))
            val day = ui.days[sel]
            val dtone = TrendMath.tone(metric, day.value, ui.band)
            SelectionCard(day.date.format(DAY_FMT), "Back to today", { selected = null }) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(fmt(day.value, metric), style = MaterialTheme.typography.headlineMedium)
                    Spacer(Modifier.width(Spacing.xs))
                    Text(metric.unit, style = Type.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = Spacing.xs))
                    if (dtone != Tone.Neutral) {
                        Spacer(Modifier.width(Spacing.s))
                        StatusDot(toneColor(dtone), Modifier.align(Alignment.CenterVertically))
                    }
                }
                if (metric != TrendMetric.Load && day.value != null) {
                    Text(TrendMath.deltaText(day.value, ui.band, deltaUnit), style = Type.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (metric == TrendMetric.Readiness) {
                    day.drivers.take(2).forEach { Text(it, style = Type.body) }
                }
            }
        }

        Spacer(Modifier.height(Spacing.s))
        TextButton(onClick = { details = !details }) { Text(if (details) "Hide details" else "Details", style = Type.label) }
        if (details) {
            if (metric == TrendMetric.Load) {
                val last = ui.days.lastOrNull { it.acwr != null || it.acute != null }
                StatRow("Last 7 days", last?.acute?.let { Math.round(it).toString() } ?: Format.DASH)
                StatRow("Last 28 days", last?.chronic?.let { Math.round(it).toString() } ?: Format.DASH)
                StatRow("Ratio", last?.acwr?.let { "%.2f".format(Locale.US, it) } ?: Format.DASH)
            } else if (ui.band != null) {
                Text(Copy.BAND_NOTE_DETAIL.trim('(', ')') + ": " + "${fmt(ui.band.lo, metric)}–${fmt(ui.band.hi, metric)} $deltaUnit", style = Type.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    }
}
