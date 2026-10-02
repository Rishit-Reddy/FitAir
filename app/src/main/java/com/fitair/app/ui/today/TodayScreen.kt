package com.fitair.app.ui.today

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import com.fitair.app.core.Format
import com.fitair.app.integrations.calendar.CalEvent
import com.fitair.app.ui.agenda.AgendaFormat
import com.fitair.app.ui.agenda.AgendaList
import com.fitair.app.ui.agenda.AgendaLive
import com.fitair.app.ui.agenda.AgendaVm
import com.fitair.app.ui.agenda.CalendarDiagnostic
import com.fitair.app.ui.agenda.NextUpBlock
import com.fitair.app.ui.agenda.rememberCalendarPermissionRequest
import com.fitair.app.ui.components.*
import com.fitair.app.ui.copy.Copy
import com.fitair.app.ui.theme.NavIcons
import com.fitair.app.ui.theme.Shapes
import com.fitair.app.ui.theme.Spacing
import com.fitair.app.ui.theme.Type
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val DATE_FMT = DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault())

/** Screens opened from Today (and the gear). Agenda now means "switch to the Calendar tab". */
enum class TodayDest { Agenda, Sleep, Readiness, Hrv, RestingHr, Load, Settings }

/**
 * Today: header (date, "data to 14:05", gear), then [todayBlocks] for the current [Mode]. The mode is recomputed on open,
 * on pull-to-refresh and after a sync-on-open that finishes within 30 s, never while the screen is being touched.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodayScreen(
    vm: TodayVm, onOpen: (TodayDest) -> Unit = {}, scroll: ScrollState = rememberScrollState(), onOpenCalendar: () -> Unit = {},
) {
    val ui = vm.ui
    val mode = vm.mode
    var sheet by remember { mutableStateOf(false) }
    val agenda: AgendaVm = viewModel()
    val requestCalendar = rememberCalendarPermissionRequest { agenda.refresh() }
    LifecycleResumeEffect(Unit) { vm.onOpen(); onPauseOrDispose { } }
    AgendaLive(agenda)
    // tick once a minute so "in 40 min" and the freshness text stay true
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(60_000); now = System.currentTimeMillis() } }
    // collapsed blocks expand inline; reset when the mode changes
    var readinessOpen by rememberSaveable(mode) { mutableStateOf(false) }
    var sleepOpen by rememberSaveable(mode) { mutableStateOf(false) }
    LaunchedEffect(mode, ui?.date, ui?.facts != null) { if (mode == Mode.Evening) vm.ensureSummary() }
    val notif = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (granted) vm.enableWaterReminders() }

    PullToRefreshBox(
        isRefreshing = vm.syncing, onRefresh = vm::refresh,
        modifier = Modifier.fillMaxSize().pointerInput(Unit) {
            awaitPointerEventScope { while (true) { awaitPointerEvent(PointerEventPass.Initial); vm.onTouched() } }
        },
    ) {
        Page(scrollState = scroll) {
            val fresh = Format.freshness(now, ui?.lastSyncMs ?: 0L)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(ui?.date?.format(DATE_FMT) ?: "", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                val dataTo = ui?.dataToMs?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()) }
                FreshnessPill(if (dataTo != null) Copy.dataTo(Format.clock(dataTo.hour, dataTo.minute)) else fresh.text, fresh.stale)
                Box(
                    Modifier.size(Spacing.minTouch).clickable(role = Role.Button) { onOpen(TodayDest.Settings) }.semantics { contentDescription = "Settings" },
                    contentAlignment = Alignment.Center,
                ) { Icon(NavIcons.Gear, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            Spacer(Modifier.height(Spacing.l))
            vm.error?.let { InlineError(it, onRetry = { vm.load(setMode = true) }) }
            if (ui == null || mode == null) return@Page
            if (!ui.hasData) {
                EmptyState("No data yet", "Pull down to sync from Health Connect.")
                return@Page
            }
            val agendaLeft = AgendaFormat.nextUp(agenda.todayItems, Instant.ofEpochMilli(now)) != null
            val expanded = buildSet {
                if (readinessOpen) add(TodayBlock.ReadinessCompact)
                if (sleepOpen) add(TodayBlock.SleepLine)
            }
            val blocks = todayBlocks(ui, mode, expanded, agendaLeft)
            blocks.forEachIndexed { i, b ->
                if (i > 0) { if (blocks[i - 1].card && b.card) Spacer(Modifier.height(Spacing.m)) else SectionBreak() }
                when (b) {
                    TodayBlock.Readiness -> {
                        val r = ui.readiness
                        ReadinessHero(
                            r?.score, Copy.readiness(r?.score),
                            driver = r?.mainDriverPlain ?: r?.note,
                            onClick = { if (r != null) sheet = true else onOpen(TodayDest.Readiness) },
                        )
                    }
                    TodayBlock.ReadinessCompact -> ReadinessCompact(
                        ui.readiness?.score, Copy.readiness(ui.readiness?.score), heartLine(ui), onClick = { readinessOpen = true })
                    TodayBlock.Sleep -> {
                        val n = ui.night!!
                        SleepCard(Format.duration(n.durationMin), n.window, n.score, n.needDiffMin, n.debt, n.stages, onClick = { onOpen(TodayDest.Sleep) })
                    }
                    TodayBlock.SleepLine -> SleepLineBlock(ui, mode, onExpand = { sleepOpen = true }, onOpen = onOpen)
                    TodayBlock.Vitals -> VitalsBlock(ui.vitals, onOpen)
                    TodayBlock.NextUp -> if (!agenda.hasPerm) CalendarPrompt(onRequestCalendar = requestCalendar)
                        else if (agenda.loaded) NextUpBlock(agenda.todayItems, agenda.tomorrow, agenda.colors, ZoneId.systemDefault(), onClick = onOpenCalendar)
                    TodayBlock.Agenda, TodayBlock.AgendaRest -> AgendaBlock(agenda, rest = b == TodayBlock.AgendaRest,
                        onOpen = onOpenCalendar, onRequest = requestCalendar, onSettings = { onOpen(TodayDest.Settings) })
                    TodayBlock.Load -> ui.load?.let {
                        LoadCard(Copy.loadSoFar(it.soFar, it.typicalByNow, it.partial), onClick = { onOpen(TodayDest.Load) })
                    }
                    TodayBlock.Water -> WaterBlock(ui, mode, vm, onEnableReminders = {
                        if (Build.VERSION.SDK_INT >= 33) notif.launch(Manifest.permission.POST_NOTIFICATIONS) else vm.enableWaterReminders()
                    })
                    TodayBlock.DaySummary -> DaySummaryCard(
                        ui.facts, vm.summary, vm.summaryLoading, onOpen = { onOpen(TodayDest.Load) }, onRegenerate = { vm.ensureSummary(regenerate = true) })
                    TodayBlock.Tomorrow -> TomorrowBlock(agenda, onOpen = onOpenCalendar, onRequest = requestCalendar)
                    TodayBlock.WindDown -> ui.windDown?.let { QuietLine("Wind down", Copy.windDown(it.needMin, it.bedMin, it.short)) }
                    TodayBlock.Insights -> InsightsBlock(if (mode == Mode.Evening) ui.insights.filter { it.id in EVENING_INSIGHTS } else ui.insights)
                }
            }
        }
    }
    val r = ui?.readiness
    if (sheet && r != null) BreakdownSheet(r, onDismiss = { sheet = false }, onTrend = { sheet = false; onOpen(TodayDest.Readiness) })
}

private fun heartLine(ui: TodayUi): String? {
    val parts = listOfNotNull(ui.hrNow?.let { "heart rate now $it" }, ui.restingHr?.let { "resting $it" })
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

@Composable
private fun SleepLineBlock(ui: TodayUi, mode: Mode, onExpand: () -> Unit, onOpen: (TodayDest) -> Unit) {
    val n = ui.night
    if (n != null) {
        SleepLine(Copy.sleepLine(n.durationMin, n.score), onClick = if (mode == Mode.Morning) ({ onOpen(TodayDest.Sleep) }) else onExpand)
        return
    }
    val synced = ui.lastSyncMs.takeIf { it > 0 }?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()) }
    val waiting = ui.wake?.waiting == true
    SleepLine(
        if (waiting) Copy.SLEEP_WAITING + (synced?.let { " · synced ${Format.clock(it.hour, it.minute)}" } ?: "") else Copy.SLEEP_NONE,
        onClick = null,
    )
}

/** Quiet prompt while the calendar permission is missing. */
@Composable
private fun CalendarPrompt(onRequestCalendar: () -> Unit) {
    Text("See today's events next to your readiness", style = Type.body, color = MaterialTheme.colorScheme.onSurfaceVariant)
    TextButton(onClick = onRequestCalendar) { Text("Show my calendar", style = Type.label) }
}

/** "AGENDA ›" (opens the Calendar tab), then rows with a "now" hairline, a quiet prompt, or the empty line. */
@Composable
private fun AgendaBlock(vm: AgendaVm, rest: Boolean, onOpen: () -> Unit, onRequest: () -> Unit, onSettings: () -> Unit) {
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    val z = remember { ZoneId.systemDefault() }
    Box(Modifier.heightIn(min = Spacing.minTouch).clickable(role = Role.Button, onClick = onOpen), contentAlignment = Alignment.CenterStart) {
        SectionHeader(if (rest) "Rest of today ›" else "Agenda ›")
    }
    if (!vm.hasPerm) { CalendarPrompt(onRequest); return }
    if (!vm.loaded) return
    CalendarDiagnostic(vm.unsynced, onSettings)
    if (!AgendaFormat.hasEvents(vm.todayItems)) { Text(AgendaFormat.emptyLine(), style = Type.body, color = dim); return }
    val c = AgendaFormat.collapse(vm.todayItems)
    AgendaList(c.visible, vm.colors, z, withEnd = false, markNow = true)
    if (c.hidden) TextButton(onClick = onOpen) { Text("Show all (${c.eventCount})", style = Type.label) }
}

/** Tomorrow's events (up to 4) and the first start, or "Nothing scheduled tomorrow". */
@Composable
private fun TomorrowBlock(vm: AgendaVm, onOpen: () -> Unit, onRequest: () -> Unit) {
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    val z = remember { ZoneId.systemDefault() }
    Box(Modifier.heightIn(min = Spacing.minTouch).clickable(role = Role.Button, onClick = onOpen), contentAlignment = Alignment.CenterStart) {
        SectionHeader("Tomorrow ›")
    }
    if (!vm.hasPerm) { CalendarPrompt(onRequest); return }
    if (!vm.loaded) return
    val ev: List<CalEvent> = vm.tomorrow.sortedWith(compareBy({ !it.allDay }, { it.begin }))
    if (ev.isEmpty()) { Text("Nothing scheduled tomorrow", style = Type.body, color = dim); return }
    AgendaFormat.firstEventLine(ev, z)?.let { Text(it, style = Type.body) }
    Spacer(Modifier.height(Spacing.xs))
    ev.take(4).forEach {
        com.fitair.app.ui.components.AgendaRow(AgendaFormat.eventTime(it, z), it.title.ifBlank { "Busy" }, busy = it.busy,
            dot = vm.colors[it.calId]?.let { c -> Color(c) })
    }
    if (ev.size > 4) TextButton(onClick = onOpen) { Text("Show all (${ev.size})", style = Type.label) }
}

/** Water until the window ends (bedtime minus an hour), then one line with the day's total. */
@Composable
private fun WaterBlock(ui: TodayUi, mode: Mode, vm: TodayVm, onEnableReminders: () -> Unit) {
    val w = ui.water ?: return
    val now = LocalDateTime.now()
    val nowMin = now.hour * 60 + now.minute
    val bed = (ui.wake?.usualBedMin ?: 23 * 60).let { if (it < 12 * 60) it + 24 * 60 else it }
    if (mode == Mode.Evening && (nowMin >= bed - 60 || nowMin < 4 * 60)) {
        QuietLine("Water", "Today ${Copy.litres(w.ml)} L")
        return
    }
    WaterCard(
        w.ml, w.goalMl, w.extraMl, w.pace, ui.glassMl, vm.logged?.ml,
        onAdd = vm::addWater, onUndo = vm::undoWater, remindersOn = w.remindersOn, onEnableReminders = onEnableReminders,
    )
}

/** One row of two compact tiles with equal heights. */
@Composable
private fun VitalsBlock(vitals: List<Vital>, onOpen: (TodayDest) -> Unit) {
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(Spacing.m)) {
        vitals.forEach { v ->
            val m = Modifier.weight(1f).fillMaxHeight().let { b ->
                if (v.dest == null) b else b.clip(Shapes.card).clickable(role = Role.Button) { onOpen(v.dest!!) }
            }
            VitalTile(if (v.dest != null) v.label + " ›" else v.label, v.verdict, m)
        }
    }
}

@Composable
private fun InsightsBlock(insights: List<Insight>) {
    SectionHeader("Worth a look")
    Spacer(Modifier.height(Spacing.s))
    insights.forEach { i ->
        Row(Modifier.fillMaxWidth().padding(vertical = Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
            StatusDot(toneColor(if (i.alert) Tone.Alert else Tone.Caution))
            Spacer(Modifier.width(Spacing.m))
            Text(i.title, style = Type.body)
        }
    }
}
