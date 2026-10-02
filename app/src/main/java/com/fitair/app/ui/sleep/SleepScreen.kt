package com.fitair.app.ui.sleep

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.fitair.app.core.Format
import com.fitair.app.ui.components.EmptyState
import com.fitair.app.ui.components.Hairline
import com.fitair.app.ui.components.InlineError
import com.fitair.app.ui.components.Page
import com.fitair.app.ui.components.SectionBreak
import com.fitair.app.ui.components.SectionHeader
import com.fitair.app.ui.components.StatRow
import com.fitair.app.ui.components.SubTabs
import com.fitair.app.ui.components.toneColor
import com.fitair.app.ui.components.charts.BarChart
import com.fitair.app.ui.components.charts.LineChart
import com.fitair.app.ui.theme.Shapes
import com.fitair.app.ui.theme.Spacing
import com.fitair.app.ui.theme.Type
import java.time.format.DateTimeFormatter
import java.util.Locale

private fun hm(min: Double?): String = min?.let { val m = Math.round(it); "${m / 60}h ${m % 60}m" } ?: Format.DASH

@Composable
fun SleepScreen(onBack: () -> Unit) {
    val vm: SleepVm = viewModel()
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.s), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("Back", style = Type.label) }
            Spacer(Modifier.weight(1f))
            Text("Sleep", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(end = Spacing.l))
        }
        when (val s = vm.state) {
            SleepState.Loading -> Text("Loading…", style = Type.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(Spacing.gutter))
            is SleepState.Error -> Column(Modifier.padding(horizontal = Spacing.gutter)) { InlineError("Could not load sleep: ${s.message}", onRetry = vm::load) }
            is SleepState.Ready -> Content(vm, s.nights, Modifier.weight(1f))
        }
    }
}

@Composable
private fun Content(vm: SleepVm, all: List<Night>, modifier: Modifier) {
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    val nights = remember(all, vm.range) { SleepModel.lastN(all, vm.range) }
    val withData = nights.count { it.hasData }
    Page(modifier) {
        SubTabs(listOf("14 nights", "30 nights"), if (vm.range == 14) 0 else 1, { vm.chooseRange(if (it == 0) 14 else 30) })
        Spacer(Modifier.height(Spacing.l))
        if (withData == 0) {
            EmptyState("No sleep data for this range", "Sync Health Connect, or pick a longer range.")
            return@Page
        }
        Row(Modifier.fillMaxWidth()) {
            Stat("Avg sleep", hm(SleepModel.avgSleepMin(nights)), Modifier.weight(1f))
            Stat("Avg score", SleepModel.avgScore(nights)?.let { "${Math.round(it)}" } ?: Format.DASH, Modifier.weight(1f))
            Stat("7-day debt", SleepModel.latestDebtMin(nights)?.let { hm(it) } ?: Format.DASH, Modifier.weight(1f))
        }
        Spacer(Modifier.height(Spacing.xl))

        val labels = remember(nights) { nights.map { SleepModel.xLabel(it.date) } }
        val selIdx = nights.indexOfFirst { it.date == vm.selected }.takeIf { it >= 0 }
        val need = SleepModel.needMin(nights)
        SectionHeader("Time asleep")
        Spacer(Modifier.height(Spacing.s))
        val tints = nights.map { toneColor(SleepModel.tone(it.score)) }
        BarChart(
            SleepModel.durationHours(nights), selectedIndex = selIdx, onSelect = { vm.select(nights[it].date) },
            baseline = need?.let { (it / 60.0).toFloat() }, barColors = tints, xLabels = labels,
        )
        Text(
            (if (need != null) "Dashed line: your need, ${hm(need)}. " else "") + "Bars: score 75+ teal, 60-74 amber, below 60 red.",
            style = Type.bodySmall, color = dim, modifier = Modifier.padding(top = Spacing.xs),
        )
        Spacer(Modifier.height(Spacing.xl))
        SectionHeader("Sleep score")
        Spacer(Modifier.height(Spacing.s))
        LineChart(SleepModel.scores(nights), selectedIndex = selIdx, onSelect = { vm.select(nights[it].date) }, xLabels = labels)

        SectionBreak()
        val sel = nights.firstOrNull { it.date == vm.selected }
        if (sel == null) {
            Text("Tap a night to see its details.", style = Type.bodySmall, color = dim)
        } else Detail(sel, vm.detail)
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(value, style = MaterialTheme.typography.headlineMedium)
        Text(label, style = Type.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private val dateFmt = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)

@Composable
private fun Detail(n: Night, d: NightDetail?) {
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    Text(n.date.format(dateFmt), style = MaterialTheme.typography.titleMedium)
    if (!n.hasData) {
        Text("No sleep recorded.", style = Type.bodySmall, color = dim, modifier = Modifier.padding(top = Spacing.xs))
        return
    }
    Spacer(Modifier.height(Spacing.s))
    Hairline()
    if (d?.bedtime != null) StatRow("Bedtime – wake", "${d.bedtime} – ${d.wake}")
    StatRow("Duration", hm(n.sleepMin))
    StatRow("Score", n.score?.let { "${Math.round(it)} / 100" } ?: Format.DASH)
    n.efficiency?.let { StatRow("Efficiency", "${Math.round(it * 100)} %") }
    Spacer(Modifier.height(Spacing.l))
    SectionHeader("Stages")
    Spacer(Modifier.height(Spacing.s))
    val st = d?.stages
    if (st == null) {
        Text(if (d == null) "Loading…" else "No stage data for this night.", style = Type.bodySmall, color = dim)
    } else StageBar(st)
    val comps = SleepModel.COMPONENTS.filter { n.components.containsKey(it.first) }
    if (comps.isNotEmpty()) {
        Spacer(Modifier.height(Spacing.l))
        SectionHeader("Score components")
        comps.forEach { (k, label) -> StatRow(label, "${Math.round(n.components.getValue(k))}") }
    }
}

@Composable
private fun StageBar(st: StageMinutes) {
    val p = MaterialTheme.colorScheme.primary
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    val parts = listOf(
        Triple("Awake", st.awake, dim.copy(alpha = 0.45f)),
        Triple("Light", st.light, p.copy(alpha = 0.35f)),
        Triple("REM", st.rem, p.copy(alpha = 0.65f)),
        Triple("Deep", st.deep, p),
    )
    Row(Modifier.fillMaxWidth().height(10.dp).clip(Shapes.chip)) {
        parts.filter { it.second > 0 }.forEach { (_, m, c) -> Box(Modifier.weight(m.toFloat()).fillMaxHeight().background(c)) }
    }
    Spacer(Modifier.height(Spacing.s))
    Row(Modifier.fillMaxWidth()) {
        parts.forEach { (label, m, c) ->
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(6.dp).clip(Shapes.chip).background(c))
                    Spacer(Modifier.width(Spacing.xs))
                    Text(label, style = Type.bodySmall, color = dim)
                }
                Text(hm(m), style = Type.bodySmall)
            }
        }
    }
}
