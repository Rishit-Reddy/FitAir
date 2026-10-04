package com.fitair.app.ui.water

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.fitair.app.core.Format
import com.fitair.app.data.dao.WaterDao
import com.fitair.app.ui.components.InlineError
import com.fitair.app.ui.components.Page
import com.fitair.app.ui.components.SectionCard
import com.fitair.app.ui.components.SubTabs
import com.fitair.app.ui.components.charts.BarChart
import com.fitair.app.ui.copy.Copy
import com.fitair.app.ui.settings.WaterSection
import com.fitair.app.ui.theme.Shapes
import com.fitair.app.ui.theme.Spacing
import com.fitair.app.ui.theme.Type
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val SPANS = listOf(7, 30, 90)
private val SHORT_FMT = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())
private val WEEKDAY_FMT = DateTimeFormatter.ofPattern("EEE", Locale.getDefault())
private fun clockOf(ms: Long) = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).let { Format.clock(it.hour, it.minute) }
private fun l(ml: Int) = Copy.litres(ml)

/** Water: log today, see pace and drinks, history, and all the reminder settings in a sheet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WaterScreen(onBack: () -> Unit) {
    val vm: WaterVm = viewModel()
    var settings by remember { mutableStateOf(false) }
    var other by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { vm.load() }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = Spacing.s), contentAlignment = Alignment.Center) {
            TextButton(onClick = onBack, modifier = Modifier.align(Alignment.CenterStart)) { Text("Back", style = Type.label) }
            Text("Water", style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = { settings = true }, modifier = Modifier.align(Alignment.CenterEnd)) { Text("Settings", style = Type.label) }
        }
        Page(Modifier.weight(1f)) {
            vm.error?.let { InlineError(it, onRetry = vm::load) }
            val ui = vm.ui ?: run {
                if (vm.error == null) Box(Modifier.fillMaxWidth().padding(vertical = Spacing.xxl), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                return@Page
            }
            TodayCard(ui, vm.justLogged?.second, onAdd = vm::add, onOther = { other = true }, onUndo = vm::undo)
            Spacer(Modifier.height(Spacing.m))
            DrinksCard(ui.entries, onDelete = vm::delete)
            Spacer(Modifier.height(Spacing.m))
            HistoryCard(ui, onSpan = vm::chooseSpan)
        }
    }

    if (other) OtherAmountDialog(onDismiss = { other = false }, onAdd = { other = false; vm.add(it) })
    if (settings) {
        ModalBottomSheet(onDismissRequest = { settings = false; vm.load() }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = Spacing.gutter).padding(bottom = Spacing.xxl)) {
                WaterSection()
            }
        }
    }
}

@Composable
private fun TodayCard(ui: WaterUi, justLogged: Int?, onAdd: (Int) -> Unit, onOther: () -> Unit, onUndo: () -> Unit) {
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    val t = ui.today
    SectionCard("Today") {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(l(t.ml), style = MaterialTheme.typography.displaySmall)
            Spacer(Modifier.width(Spacing.xs))
            Text("of ${l(t.goalMl)} L", style = Type.body, color = dim, modifier = Modifier.padding(bottom = 6.dp))
        }
        Spacer(Modifier.height(Spacing.s))
        val frac = if (t.goalMl > 0) (t.ml.toFloat() / t.goalMl).coerceIn(0f, 1f) else 0f
        Box(Modifier.fillMaxWidth().height(8.dp).clip(Shapes.pill).background(MaterialTheme.colorScheme.outlineVariant)) {
            if (frac > 0f) Box(Modifier.fillMaxWidth(frac).fillMaxHeight().clip(Shapes.pill).background(MaterialTheme.colorScheme.primary))
        }
        Spacer(Modifier.height(Spacing.s))
        val lines = listOfNotNull(
            Copy.waterPace(t.pace, t.extraMl),
            ui.usualByNow?.let { "usually ${l(it)} L by now" },
            ui.nextReminderMs?.takeIf { t.ml < t.goalMl }?.let { "next reminder about ${clockOf(it)}" },
        )
        Text(lines.joinToString(" · "), style = Type.bodySmall, color = dim)
        Spacer(Modifier.height(Spacing.l))
        if (justLogged != null) {
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Logged $justLogged ml", style = Type.body, modifier = Modifier.weight(1f))
                TextButton(onClick = onUndo) { Text("Undo", style = Type.label) }
            }
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                WaterDao.AMOUNTS.forEach { a ->
                    FilledTonalButton(onClick = { onAdd(a) }, modifier = Modifier.weight(1f).height(56.dp), shape = Shapes.card, contentPadding = PaddingValues(0.dp)) {
                        Text("+$a", style = Type.label)
                    }
                }
                OutlinedButton(onClick = onOther, modifier = Modifier.weight(1f).height(56.dp), shape = Shapes.card, contentPadding = PaddingValues(0.dp)) {
                    Text("Other", style = Type.label)
                }
            }
        }
    }
}

@Composable
private fun DrinksCard(entries: List<WaterDao.Entry>, onDelete: (WaterDao.Entry) -> Unit) {
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    var confirm by remember { mutableStateOf<WaterDao.Entry?>(null) }
    SectionCard("Today's drinks") {
        if (entries.isEmpty()) {
            Text("Nothing logged today.", style = Type.bodySmall, color = dim)
            return@SectionCard
        }
        entries.forEachIndexed { i, e ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = Spacing.minTouch).clickable { confirm = if (confirm == e) null else e },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(clockOf(e.t), style = Type.body, color = dim, modifier = Modifier.width(64.dp))
                Text("${e.ml} ml", style = Type.body, modifier = Modifier.weight(1f))
                if (confirm == e) TextButton(onClick = { confirm = null; onDelete(e) }) { Text("Delete", style = Type.label, color = MaterialTheme.colorScheme.error) }
                else if (e.origin != WaterDao.ORIGIN) Text("Health Connect", style = Type.bodySmall, color = dim)
            }
            if (i < entries.lastIndex) com.fitair.app.ui.components.Hairline()
        }
        Spacer(Modifier.height(Spacing.s))
        Text("Tap a drink to delete it.", style = Type.bodySmall, color = dim)
    }
}

@Composable
private fun HistoryCard(ui: WaterUi, onSpan: (Int) -> Unit) {
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    val goal = ui.today.goalMl - ui.today.extraMl
    var selected by remember(ui.span) { mutableStateOf<Int?>(null) }
    SectionCard("History") {
        SubTabs(SPANS.map { "$it days" }, SPANS.indexOf(ui.span).coerceAtLeast(0), { onSpan(SPANS[it]) })
        Spacer(Modifier.height(Spacing.l))
        val xl = ui.days.mapIndexed { i, d ->
            if (ui.span == 7) d.format(WEEKDAY_FMT) else if (i == 0 || i == ui.days.lastIndex || i == ui.days.size / 2) d.format(SHORT_FMT) else ""
        }
        val reached = MaterialTheme.colorScheme.primary
        BarChart(
            ui.totals.map { it?.let { v -> v / 1000f } }, Modifier.fillMaxWidth(), selectedIndex = selected, onSelect = { selected = it },
            baseline = goal / 1000f, xLabels = xl, barColors = ui.totals.map { v -> if (v != null && v >= goal) reached else null },
        )
        Text("Dashed line: your goal, ${l(goal)} L. Days that reached it are in colour.", style = Type.bodySmall, color = dim)
        selected?.takeIf { it in ui.days.indices }?.let { i ->
            Spacer(Modifier.height(Spacing.s))
            Text("${ui.days[i].format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault()))}: ${ui.totals[i]?.let { "${l(it)} L" } ?: "nothing logged"}", style = Type.body)
        }
        Spacer(Modifier.height(Spacing.l))
        // finished days only: today is still filling up
        val done = ui.totals.dropLast(1)
        val logged = done.filterNotNull().filter { it > 0 }
        val (hit, of) = WaterMath.goalDays(done, goal)
        Row(Modifier.fillMaxWidth()) {
            Stat("Average", if (logged.isEmpty()) Format.DASH else "${l(logged.average().toInt())} L", "on days you logged", Modifier.weight(1f))
            Stat("Goal reached", if (of == 0) Format.DASH else "$hit of $of", "logged days", Modifier.weight(1f))
            Stat("Total", "${l(ui.totals.filterNotNull().sum())} L", "incl. today", Modifier.weight(1f))
        }
    }
}

@Composable
private fun Stat(label: String, value: String, sub: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, style = Type.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        Text(value, style = MaterialTheme.typography.titleLarge)
        Text(sub, style = Type.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

@Composable
private fun OtherAmountDialog(onDismiss: () -> Unit, onAdd: (Int) -> Unit) {
    var text by remember { mutableStateOf("") }
    val amount = WaterMath.parseAmount(text)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Log water") },
        text = {
            OutlinedTextField(
                text, { text = it.filter(Char::isDigit).take(4) }, singleLine = true, label = { Text("Amount in ml") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                supportingText = { Text("50 to 2000 ml") },
            )
        },
        confirmButton = { TextButton(onClick = { amount?.let(onAdd) }, enabled = amount != null) { Text("Log") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
