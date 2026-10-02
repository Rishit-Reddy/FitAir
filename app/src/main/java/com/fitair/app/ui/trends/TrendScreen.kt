package com.fitair.app.ui.trends

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.fitair.app.core.Format
import com.fitair.app.ui.components.*
import com.fitair.app.ui.components.charts.BarChart
import com.fitair.app.ui.components.charts.LineChart
import com.fitair.app.ui.theme.Shapes
import com.fitair.app.ui.theme.Spacing
import com.fitair.app.ui.theme.Type
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
    LaunchedEffect(metric, spanIdx) { vm.load(metric, SPANS[spanIdx]) }

    Page {
        TextButton(onClick = onBack) { Text("Back") }
        Text(metric.title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(Spacing.s))
        SubTabs(SPANS.map { "$it days" }, spanIdx, { spanIdx = it })
        Spacer(Modifier.height(Spacing.l))

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
        val shown = ui.days[sel ?: ui.days.indexOfLast { it.value != null }]
        val xl = ui.days.mapIndexed { i, d -> if (i == 0 || i == ui.days.lastIndex || i == ui.days.size / 2) d.date.format(SHORT_FMT) else "" }
        val floats = values.map { it?.toFloat() }

        // header: latest (or selected) value and change vs baseline
        SectionHeader(if (sel == null) "Latest" else shown.date.format(DAY_FMT))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(fmt(shown.value, metric), style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.width(Spacing.xs))
            Text(metric.unit, style = Type.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 5.dp))
        }
        if (metric != TrendMetric.Load && shown.value != null) {
            Text(TrendMath.deltaText(shown.value, ui.band, metric.unit), style = Type.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(Spacing.l))

        if (metric == TrendMetric.Load) {
            val chronic = ui.days.lastOrNull { it.chronic != null }?.chronic
            BarChart(floats, Modifier.fillMaxWidth().height(180.dp), selectedIndex = sel, onSelect = { selected = it },
                baseline = chronic?.toFloat(), xLabels = xl)
        } else {
            LineChart(floats, Modifier.fillMaxWidth().height(180.dp), selectedIndex = sel, onSelect = { selected = it },
                band = ui.band?.let { it.lo.toFloat() to it.hi.toFloat() }, xLabels = xl)
            if (ui.band != null) {
                Spacer(Modifier.height(Spacing.xs))
                Text("Shaded: your usual range, ${fmt(ui.band.lo, metric)}–${fmt(ui.band.hi, metric)} ${metric.unit} (28-day mean ± 1 SD)",
                    style = Type.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(Spacing.l))

        if (metric == TrendMetric.Load) {
            val last = ui.days.lastOrNull { it.acwr != null || it.acute != null }
            Text(TrendMath.acwrText(last?.acwr), style = Type.body)
            Spacer(Modifier.height(Spacing.m))
            StatRow("Acute load (7 days)", last?.acute?.let { Math.round(it).toString() } ?: Format.DASH)
            StatRow("Chronic load (28 days)", last?.chronic?.let { Math.round(it).toString() } ?: Format.DASH)
            StatRow("Acute : chronic", last?.acwr?.let { "%.2f".format(Locale.US, it) } ?: Format.DASH)
        } else {
            Text(TrendMath.interpret(metric.title, values, ui.band), style = Type.body)
        }

        if (sel != null && metric == TrendMetric.Readiness) {
            Spacer(Modifier.height(Spacing.l))
            Column(Modifier.fillMaxWidth().clip(Shapes.card).background(MaterialTheme.colorScheme.surfaceVariant).padding(Spacing.l)) {
                SectionHeader("Main drivers")
                Spacer(Modifier.height(Spacing.xs))
                if (shown.drivers.isEmpty()) Text("No breakdown stored for this day.", style = Type.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                else shown.drivers.forEach { Text(it, style = Type.body) }
            }
        }
    }
}
