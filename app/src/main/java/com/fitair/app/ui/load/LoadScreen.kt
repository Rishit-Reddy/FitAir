package com.fitair.app.ui.load

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.fitair.app.data.Rebuild
import com.fitair.app.data.metrics.MetricId
import com.fitair.app.ui.components.*
import com.fitair.app.ui.components.charts.BarChart
import com.fitair.app.ui.copy.Copy
import com.fitair.app.ui.theme.Spacing
import com.fitair.app.ui.theme.Type
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val DAY_FMT = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)

/** Cardio load detail: today's number and curve, zone minutes, the week verdict, 28-day bars, sessions with flags, max heart rate. */
@Composable
fun LoadScreen(onBack: () -> Unit) {
    val vm: LoadVm = viewModel()
    val rebuild by Rebuild.state.collectAsState()
    var explain by remember { mutableStateOf(false) }
    LaunchedEffect(rebuild.running) { if (!rebuild.running && rebuild.lastResult != null) vm.load() }
    Column(Modifier.fillMaxSize()) {
        DetailHeader("Cardio load", onBack)
        val ui = vm.ui
        when {
            ui == null && vm.error != null -> Column(Modifier.padding(horizontal = Spacing.gutter)) { InlineError("Could not load: ${vm.error}", onRetry = vm::load) }
            ui == null -> Text("Loading…", style = Type.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(Spacing.gutter))
            else -> Content(vm, ui, rebuild.running, rebuild.detail, onExplain = { explain = true }, Modifier.weight(1f))
        }
    }
    if (explain) ExplainSheet("load", onDismiss = { explain = false })
}

@Composable
private fun Content(vm: LoadVm, ui: LoadUi, rebuilding: Boolean, rebuildText: String, onExplain: () -> Unit, modifier: Modifier) {
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val accent = MetricAccent.of(MetricId.Load, dark) ?: dim
    val z = remember { ZoneId.systemDefault() }
    val nowHour = remember(ui) { ui.hourly.indexOfLast { it != null }.coerceAtLeast(0) }
    Page(modifier) {
        if (rebuilding) {
            Text(rebuildText.ifEmpty { "Rebuilding analytics" }, style = Type.bodySmall, color = dim)
            Spacer(Modifier.height(Spacing.m))
        }
        LoadHero(ui, nowHour)

        Spacer(Modifier.height(Spacing.gap))
        SurfaceCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Time in each zone today", style = Type.metricTitle, color = dim, modifier = Modifier.weight(1f))
                TextButton(onClick = onExplain) { Text("What is this?", style = Type.label) }
            }
            Spacer(Modifier.height(Spacing.s))
            ZoneBar(ui.zones, accent)
            ui.shifts.mapNotNull { w ->
                Copy.shiftLoadLine(com.fitair.app.ui.agenda.AgendaFormat.range(Instant.ofEpochMilli(w.startMs), Instant.ofEpochMilli(w.endMs), z), w.avgHr, w.minutesZone2Plus, w.coverage)
            }.forEach { Text(it, style = Type.bodySmall, color = dim, modifier = Modifier.padding(top = Spacing.s)) }
        }

        Spacer(Modifier.height(Spacing.gap))
        val week = Copy.load(ui.ratio)
        SurfaceCard {
            Text("This week", style = Type.metricTitle, color = dim)
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(toneColor(week.tone)); Spacer(Modifier.width(8.dp)); Text(week.headline, style = Type.title)
            }
            Text("Compared with your last four weeks.", style = Type.bodySmall, color = dim)
        }

        Spacer(Modifier.height(Spacing.gap))
        SurfaceCard {
            Text("Last 28 days", style = Type.metricTitle, color = dim)
            Spacer(Modifier.height(Spacing.s))
            val values = ui.days.map { (_, r) -> r?.cardio?.toFloat() }
            if (values.all { it == null }) Text("No load yet. It appears once your band's heart rate has synced.", style = Type.bodySmall, color = dim)
            else {
                val colors = values.mapIndexed { i, _ -> if (i == values.lastIndex) accent else accent.copy(alpha = 0.55f) }
                BarChart(values, baseline = ui.usual?.toFloat(), xLabels = ui.days.map { it.first.format(DAY_FMT) }, barColors = colors)
                Text("Dashed line: your usual day. Days with the band off read low.", style = Type.bodySmall, color = dim, modifier = Modifier.padding(top = Spacing.s))
            }
        }

        Spacer(Modifier.height(Spacing.gap))
        Text("Sessions · last 14 days", style = Type.metricTitle, color = dim, modifier = Modifier.padding(start = Spacing.xs, bottom = Spacing.s))
        if (ui.sessions.isEmpty()) SurfaceCard { Text("No workouts logged.", style = Type.bodySmall, color = dim) }
        ui.sessions.forEach { s -> SessionCard(s, vm.busy) { ex -> vm.setVerdict(s, ex) }; Spacer(Modifier.height(Spacing.s)) }
        Text("A flag only changes FitAir's numbers. Google's own record is not touched.", style = Type.bodySmall, color = dim, modifier = Modifier.padding(top = Spacing.xs))

        Spacer(Modifier.height(Spacing.gap))
        MaxHrCard(vm, ui)
    }
}

@Composable
private fun MaxHrCard(vm: LoadVm, ui: LoadUi) {
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    var edit by rememberSaveable { mutableStateOf(false) }
    var text by rememberSaveable { mutableStateOf("") }
    SurfaceCard {
        Text("Max heart rate", style = Type.metricTitle, color = dim)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${Math.round(ui.hrMax.value)} bpm · ${ui.hrMax.source.label}", style = Type.body, modifier = Modifier.weight(1f))
            TextButton(onClick = { edit = !edit; text = Math.round(ui.hrMax.value).toString() }) { Text(if (edit) "Cancel" else "Change", style = Type.label) }
        }
        if (edit) {
            OutlinedTextField(text, { v -> text = v.filter { it.isDigit() }.take(3) }, singleLine = true, label = { Text("Max heart rate (bpm)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
            Row {
                TextButton(onClick = { vm.saveHrMax(text.toIntOrNull()); edit = false }) { Text("Save", style = Type.label) }
                TextButton(onClick = { vm.saveHrMax(null); edit = false }) { Text("Use estimate", style = Type.label) }
            }
            Text("Saving recomputes every day in the background.", style = Type.bodySmall, color = dim)
        }
    }
}
