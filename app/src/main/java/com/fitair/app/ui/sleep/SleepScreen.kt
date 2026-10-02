package com.fitair.app.ui.sleep

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.viewmodel.compose.viewModel
import com.fitair.app.core.Format
import com.fitair.app.ui.components.DetailHeader
import com.fitair.app.ui.components.EmptyState
import com.fitair.app.ui.components.Hairline
import com.fitair.app.ui.components.InlineError
import com.fitair.app.ui.components.Page
import com.fitair.app.ui.components.SectionBreak
import com.fitair.app.ui.components.SectionHeader
import com.fitair.app.ui.components.StatRow
import com.fitair.app.ui.components.StatusDot
import com.fitair.app.ui.components.SubTabs
import com.fitair.app.ui.components.Tone
import com.fitair.app.ui.components.toneColor
import com.fitair.app.ui.components.charts.BarChart
import com.fitair.app.ui.components.charts.LineChart
import com.fitair.app.ui.theme.Shapes
import com.fitair.app.ui.theme.Spacing
import com.fitair.app.ui.theme.Type
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private fun hm(min: Double?): String = Format.duration(min?.let { Math.round(it) })

@Composable
fun SleepScreen(onBack: () -> Unit) {
    val vm: SleepVm = viewModel()
    Column(Modifier.fillMaxSize()) {
        DetailHeader("Sleep", onBack)
        when (val s = vm.state) {
            SleepState.Loading -> Text("Loading…", style = Type.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(Spacing.gutter))
            is SleepState.Error -> Column(Modifier.padding(horizontal = Spacing.gutter)) { InlineError("Could not load sleep: ${s.message}", onRetry = vm::load) }
            is SleepState.Ready -> Content(vm, s.nights, Modifier.weight(1f))
        }
    }
}

private val dateFmt = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)

@Composable
private fun Content(vm: SleepVm, all: List<Night>, modifier: Modifier) {
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val nights = remember(all, vm.range) { SleepModel.lastN(all, vm.range) }
    val withData = nights.count { it.hasData }
    val default = remember(all) { SleepModel.defaultNight(all) }
    Page(modifier, scroll) {
        if (default == null) {
            EmptyState("No sleep data yet", "Sync Health Connect, then check back.")
            return@Page
        }
        val sel = all.firstOrNull { it.date == vm.selected } ?: all.first { it.date == default }
        val isDefault = sel.date == default
        val caption = if (isDefault) "Last night · ${sel.date.format(dateFmt)}" else SleepModel.headerLabel(sel.date, LocalDate.now())
        SectionHeader(caption)
        if (!isDefault) TextButton(onClick = vm::selectDefault) { Text("Back to last night") }
        Spacer(Modifier.height(Spacing.s))
        Detail(sel, vm.detail)

        SectionBreak()
        SectionHeader("History")
        Spacer(Modifier.height(Spacing.s))
        SubTabs(listOf("14 nights", "30 nights"), if (vm.range == 14) 0 else 1, { vm.chooseRange(if (it == 0) 14 else 30) })
        Spacer(Modifier.height(Spacing.l))
        if (withData == 0) {
            EmptyState("No sleep data for this range", "Pick a longer range.")
            return@Page
        }
        Row(Modifier.fillMaxWidth()) {
            Stat("Avg sleep", hm(SleepModel.avgSleepMin(nights)), Modifier.weight(1f))
            Stat("Avg score", SleepModel.avgScore(nights)?.let { "${Math.round(it)}" } ?: Format.DASH, Modifier.weight(1f))
            Stat("7-day debt", SleepModel.latestDebtMin(nights)?.let { hm(it) } ?: Format.DASH, Modifier.weight(1f))
        }
        Spacer(Modifier.height(Spacing.l))

        val labels = remember(nights) { nights.map { SleepModel.xLabel(it.date) } }
        val selIdx = nights.indexOfFirst { it.date == sel.date }.takeIf { it >= 0 }
        val need = SleepModel.needMin(nights)
        val pick: (Int) -> Unit = { i ->
            vm.select(nights[i].date)
            scope.launch { scroll.animateScrollTo(0) }
        }
        SectionHeader("Time asleep")
        Spacer(Modifier.height(Spacing.s))
        BarChart(
            SleepModel.durationHours(nights), selectedIndex = selIdx, onSelect = pick,
            baseline = need?.let { (it / 60.0).toFloat() }, xLabels = labels,
        )
        if (need != null) {
            Text("Dashed line: your need, ${hm(need)}", style = Type.bodySmall, color = dim, modifier = Modifier.padding(top = Spacing.xs))
        }
        Spacer(Modifier.height(Spacing.l))
        SectionHeader("Sleep score")
        Spacer(Modifier.height(Spacing.s))
        LineChart(SleepModel.scores(nights), selectedIndex = selIdx, onSelect = pick, xLabels = labels)
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(value, style = MaterialTheme.typography.titleMedium)
        Text(label, style = Type.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Top block: duration, score, window, stages, components, efficiency. */
@Composable
private fun Detail(n: Night, d: NightDetail?) {
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    if (!n.hasData) {
        Text("No sleep recorded.", style = Type.bodySmall, color = dim)
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(hm(n.sleepMin), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
        val tone = SleepModel.tone(n.score)
        if (tone != Tone.Neutral) { StatusDot(toneColor(tone)); Spacer(Modifier.width(Spacing.s)) }
        Text(n.score?.let { "${Math.round(it)} / 100" } ?: Format.DASH, style = MaterialTheme.typography.bodyLarge)
    }
    if (d?.bedtime != null) Text("${d.bedtime} – ${d.wake}", style = Type.bodySmall, color = dim)
    Spacer(Modifier.height(Spacing.l))
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
    n.efficiency?.let {
        if (comps.isEmpty()) { Spacer(Modifier.height(Spacing.l)); Hairline() }
        StatRow("Efficiency", "${Math.round(it * 100)} %")
    }
}

/** Neutral depth ramp on onSurface, the only place a sleep stage gets a colour. */
private val stageAlpha = listOf("Awake" to 0.12f, "Light" to 0.30f, "REM" to 0.55f, "Deep" to 0.85f)

@Composable
private fun StageBar(st: StageMinutes) {
    val ink = MaterialTheme.colorScheme.onSurface
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    val mins = listOf(st.awake, st.light, st.rem, st.deep)
    val parts = stageAlpha.mapIndexed { i, (label, a) -> Triple(label, mins[i], ink.copy(alpha = a)) }
    Row(Modifier.fillMaxWidth().height(Spacing.s).clip(Shapes.chip)) {
        parts.filter { it.second > 0 }.forEach { (_, m, c) -> Box(Modifier.weight(m.toFloat()).fillMaxHeight().background(c)) }
    }
    Spacer(Modifier.height(Spacing.s))
    Row(Modifier.fillMaxWidth()) {
        parts.forEach { (label, m, c) ->
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(c)
                    Spacer(Modifier.width(Spacing.xs))
                    Text(label, style = Type.bodySmall, color = dim)
                }
                Text(hm(m), style = Type.bodySmall)
            }
        }
    }
}
