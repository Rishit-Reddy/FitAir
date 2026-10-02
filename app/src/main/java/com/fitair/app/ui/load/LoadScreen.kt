package com.fitair.app.ui.load

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.health.connect.client.records.ExerciseSessionRecord
import com.fitair.app.data.Rebuild
import com.fitair.app.data.dao.SessionRow
import com.fitair.app.data.dao.SessionState
import com.fitair.app.core.Format
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
private val WHEN_FMT = DateTimeFormatter.ofPattern("EEE HH:mm", Locale.ENGLISH)

/** Cardio load detail: today so far, zone minutes, 28-day bars, the week verdict, max heart rate and the session list with flags. */
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
    Page(modifier) {
        if (rebuilding) {
            Text(rebuildText.ifEmpty { "Rebuilding analytics" }, style = Type.bodySmall, color = dim)
            Spacer(Modifier.height(Spacing.m))
        }
        SectionHeader("Today so far")
        Spacer(Modifier.height(Spacing.xs))
        val v = Copy.loadSoFar(ui.today.soFar, ui.today.typicalByNow, ui.today.partial)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(Math.round(ui.today.soFar).toString(), style = Type.headline)
            Spacer(Modifier.width(Spacing.m))
            Text(v.headline, style = Type.body, modifier = Modifier.padding(bottom = 4.dp))
        }
        ui.today.coverage?.let {
            Text("Heart rate covers ${Math.round(it * 100)} % of your waking hours so far" + if (ui.today.partial) " · partial day" else "",
                style = Type.bodySmall, color = dim)
        } ?: Text("Not enough of the day has passed to judge coverage.", style = Type.bodySmall, color = dim)
        Spacer(Modifier.height(Spacing.m))
        StatRow("Light", "${ui.zones[0]} min")
        StatRow("Moderate", "${ui.zones[1]} min")
        StatRow("Vigorous", "${ui.zones[2]} min")
        StatRow("Peak", "${ui.zones[3]} min")
        TextButton(onClick = onExplain) { Text("What is this?", style = Type.label) }

        SectionBreak()
        SectionHeader("This week")
        Spacer(Modifier.height(Spacing.xs))
        val week = Copy.load(ui.ratio)
        Text(week.headline, style = Type.title)
        Text("Compared with your last four weeks.", style = Type.bodySmall, color = dim)

        SectionBreak()
        SectionHeader("Last 28 days")
        Spacer(Modifier.height(Spacing.s))
        val values = ui.days.map { (_, r) -> r?.cardio?.toFloat() }
        if (values.all { it == null }) Text("No load yet. It appears once your band's heart rate has synced.", style = Type.bodySmall, color = dim)
        else BarChart(values, xLabels = ui.days.map { it.first.format(DAY_FMT) })
        Text("Days with the band off count as partial and read low.", style = Type.bodySmall, color = dim)

        SectionBreak()
        MaxHr(vm, ui)

        SectionBreak()
        SectionHeader("Sessions · last 14 days")
        Spacer(Modifier.height(Spacing.s))
        if (ui.sessions.isEmpty()) Text("No workouts logged.", style = Type.bodySmall, color = dim)
        ui.sessions.forEach { s -> SessionItem(s, vm.busy) { ex -> vm.setVerdict(s, ex) }; Hairline() }
        Text("A flag only changes FitAir's numbers. Google's own record is not touched.", style = Type.bodySmall, color = dim, modifier = Modifier.padding(top = Spacing.s))
    }
}

@Composable
private fun MaxHr(vm: LoadVm, ui: LoadUi) {
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    var edit by rememberSaveable { mutableStateOf(false) }
    var text by rememberSaveable { mutableStateOf("") }
    SectionHeader("Max heart rate")
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

private fun title(s: SessionRow): String {
    if (s.title.isNotBlank()) return s.title
    val t = ExerciseSessionRecord.EXERCISE_TYPE_INT_TO_STRING_MAP[s.type]?.replace('_', ' ')?.lowercase() ?: "workout"
    return t.replaceFirstChar { it.uppercase() }
}

@Composable
private fun SessionItem(s: SessionRow, busy: Boolean, onVerdict: (exercise: Boolean) -> Unit) {
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    val whenText = Instant.ofEpochMilli(s.startMs).atZone(ZoneId.systemDefault()).format(WHEN_FMT)
    Column(Modifier.fillMaxWidth().padding(vertical = Spacing.s)) {
        Text(title(s), style = Type.body)
        Text("$whenText · ${Format.duration(Math.round(s.durationMin))}" + (s.meanHrr?.let { " · effort ${Math.round(it * 100)} %" } ?: ""),
            style = Type.bodySmall, color = dim)
        when (s.state) {
            SessionState.AutoFlagged -> {
                Text("Low effort: probably not exercise", style = Type.bodySmall)
                Row {
                    TextButton(enabled = !busy, onClick = { onVerdict(true) }) { Text("Keep it", style = Type.label) }
                    TextButton(enabled = !busy, onClick = { onVerdict(false) }) { Text("Not exercise", style = Type.label) }
                }
            }
            SessionState.UserNotExercise -> {
                Text("Not counted as exercise", style = Type.bodySmall)
                TextButton(enabled = !busy, onClick = { onVerdict(true) }) { Text("Restore", style = Type.label) }
            }
            SessionState.UserKept -> {
                Text("Kept as exercise", style = Type.bodySmall, color = dim)
                TextButton(enabled = !busy, onClick = { onVerdict(false) }) { Text("Not real exercise", style = Type.label) }
            }
            SessionState.Normal -> TextButton(enabled = !busy, onClick = { onVerdict(false) }) { Text("Not real exercise", style = Type.label) }
        }
    }
}
