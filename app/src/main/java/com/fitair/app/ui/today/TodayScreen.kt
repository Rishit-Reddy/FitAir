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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import com.fitair.app.data.metrics.MetricId
import com.fitair.app.ui.metrics.MetricCards
import com.fitair.app.ui.theme.Type as T
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

/** Screens opened from Today, the gear and the Metrics tab. Agenda means "switch to the Calendar tab". */
enum class TodayDest { Agenda, Sleep, Readiness, Hrv, RestingHr, Load, Settings, Heart, Steps, Energy, Distance, Water }

/** The detail screen a metric card opens; null where there is none yet (Weight arrives in 0.9.1). Bedtime opens Sleep. */
fun destFor(id: MetricId): TodayDest? = when (id) {
    MetricId.Heart -> TodayDest.Heart; MetricId.Readiness -> TodayDest.Readiness; MetricId.Sleep, MetricId.Bedtime -> TodayDest.Sleep
    MetricId.RestingHr -> TodayDest.RestingHr; MetricId.Hrv -> TodayDest.Hrv; MetricId.Load -> TodayDest.Load
    MetricId.Energy -> TodayDest.Energy; MetricId.Distance -> TodayDest.Distance; MetricId.Steps -> TodayDest.Steps
    MetricId.Water -> TodayDest.Water; MetricId.Weight -> null
}

private val WIDE = 600.dp

/**
 * Today: header (date, "data to 14:05", gear), one big card with two large and three small metric cards chosen by [todayLayout]
 * and exactly three summary bullets, then the slim Next up and Water strips. The mode is recomputed on open, on pull-to-refresh and
 * after a sync-on-open that finishes within 30 s, never while the screen is being touched.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodayScreen(
    vm: TodayVm, onOpen: (TodayDest) -> Unit = {}, scroll: ScrollState = rememberScrollState(), onOpenCalendar: () -> Unit = {},
) {
    val ui = vm.ui
    val mode = vm.mode
    var sheet by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf(false) }
    var syncInfo by remember { mutableStateOf(false) }
    val agenda: AgendaVm = viewModel()
    val requestCalendar = rememberCalendarPermissionRequest { agenda.refresh() }
    LifecycleResumeEffect(Unit) { vm.onOpen(); onPauseOrDispose { } }
    AgendaLive(agenda)
    // tick once a minute so "in 40 min" and the freshness text stay true
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(60_000); now = System.currentTimeMillis() } }
    LaunchedEffect(mode, ui?.date, ui?.lastSyncMs, ui?.dataToMs) { vm.ensureBrief() }
    val notif = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (granted) vm.enableWaterReminders() }

    if (syncInfo) {
        val ctx = androidx.compose.ui.platform.LocalContext.current
        val zone = ZoneId.systemDefault()
        fun clock(ms: Long?) = ms?.takeIf { it > 0 }?.let { Instant.ofEpochMilli(it).atZone(zone) }?.let { Format.clock(it.hour, it.minute) } ?: "never"
        AlertDialog(
            onDismissRequest = { syncInfo = false },
            title = { Text("Sync status") },
            text = {
                Column {
                    Text("FitAir last checked Health Connect at ${clock(ui?.lastSyncMs)}.")
                    Spacer(Modifier.height(Spacing.s))
                    Text("Newest data it holds is from ${clock(ui?.dataToMs)}.")
                    Spacer(Modifier.height(Spacing.s))
                    Text("Your Air sends data to Google Health, and Google Health passes it to Health Connect, where FitAir reads it. " +
                        "If the newest data is old, open Google Health so it syncs your Air, then pull down here.")
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    syncInfo = false
                    val i = ctx.packageManager.getLaunchIntentForPackage("com.fitbit.FitbitMobile")
                    if (i != null) try { ctx.startActivity(i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) { }
                }) { Text("Open Google Health") }
            },
            dismissButton = { TextButton(onClick = { syncInfo = false; vm.refresh() }) { Text("Sync now") } },
        )
    }

    PullToRefreshBox(
        isRefreshing = vm.syncing, onRefresh = vm::refresh,
        modifier = Modifier.fillMaxSize().pointerInput(Unit) {
            awaitPointerEventScope { while (true) { awaitPointerEvent(PointerEventPass.Initial); vm.onTouched() } }
        },
    ) {
        Page(scrollState = scroll) {
            val fresh = Format.freshness(now, ui?.lastSyncMs ?: 0L)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(ui?.date?.format(DATE_FMT) ?: "", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f), maxLines = 1)
                val dataTo = ui?.dataToMs?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()) }
                val checked = ui?.lastSyncMs?.takeIf { it > 0 }?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()) }
                val newestAgeMin = ui?.dataToMs?.let { (now - it) / 60_000L }
                FreshnessPill(
                    if (dataTo != null) Copy.dataTo(Format.clock(dataTo.hour, dataTo.minute)) else fresh.text,
                    stale = fresh.stale || (newestAgeMin != null && newestAgeMin > 45),
                    sub = checked?.let { "checked " + Format.clock(it.hour, it.minute) },
                    onClick = { syncInfo = true },
                )
                Box(
                    Modifier.size(Spacing.minTouch).clickable(role = Role.Button) { onOpen(TodayDest.Settings) }.semantics { contentDescription = "Settings" },
                    contentAlignment = Alignment.Center,
                ) { Icon(NavIcons.Gear, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            vm.syncNote?.let { Text(it, style = Type.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = Spacing.xs)) }
            Spacer(Modifier.height(Spacing.m))
            vm.error?.let { InlineError(it, onRetry = { vm.load(setMode = true) }) }
            if (ui == null || mode == null) return@Page
            if (!ui.hasData) {
                EmptyState("No data yet", "Pull down to sync from Health Connect.")
                return@Page
            }
            val nowLocal = remember(now) { LocalDateTime.now() }
            val waterOpen = remember(now, ui.wake, mode) {
                val nowMin = nowLocal.hour * 60 + nowLocal.minute
                val bed = (ui.wake?.usualBedMin ?: 23 * 60).let { if (it < 12 * 60) it + 24 * 60 else it }
                !(mode == Mode.Evening && (nowMin >= bed - 60 || nowMin < 4 * 60))
            }
            val layout = todayLayout(mode, waterOpen = waterOpen && ui.water != null, alert = ui.insights.any { it.alert })
            val hero: @Composable () -> Unit = {
                HeroCard(ui, mode, layout, vm.brief, now,
                    onCard = { id -> if (id == MetricId.Readiness && ui.readiness != null) sheet = true else destFor(id)?.let(onOpen) },
                    onDetails = { details = true })
            }
            val strips: @Composable () -> Unit = {
                layout.below.forEach { b ->
                    when (b) {
                        Below.NextUp -> NextUpStripBlock(agenda, mode, now, onOpenCalendar, requestCalendar)
                        Below.Water -> WaterBlock(ui, mode, vm, onEnableReminders = {
                            if (Build.VERSION.SDK_INT >= 33) notif.launch(Manifest.permission.POST_NOTIFICATIONS) else vm.enableWaterReminders()
                        })
                        Below.Alert -> ui.insights.firstOrNull { it.alert }?.let { AlertLine(it) }
                    }
                }
            }
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                if (maxWidth >= WIDE) {
                    // unfolded: big card left (max 560 dp), Next up + today's agenda + Water on the right
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.gap)) {
                        Column(Modifier.weight(1f).widthIn(max = 560.dp)) { hero() }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.gap)) {
                            strips()
                            AgendaBlock(agenda, rest = true, onOpen = onOpenCalendar, onRequest = requestCalendar, onSettings = { onOpen(TodayDest.Settings) })
                        }
                    }
                } else {
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.gap)) { hero(); strips() }
                }
            }
        }
    }
    val r = ui?.readiness
    if (sheet && r != null) BreakdownSheet(r, onDismiss = { sheet = false }, onTrend = { sheet = false; onOpen(TodayDest.Readiness) })
    if (details) DetailsSheet(ui?.facts, onDismiss = { details = false })
}

@Composable
private fun HeroCard(
    ui: TodayUi, mode: Mode, layout: TodayLayout, brief: com.fitair.app.coach.Brief?, nowMs: Long,
    onCard: (MetricId) -> Unit, onDetails: () -> Unit,
) {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val border = if (dark) Modifier else Modifier.border(1.dp, androidx.compose.ui.graphics.Color(0xFFE7E7E4), Shapes.hero)
    val snap = ui.snapshot
    Column(
        Modifier.fillMaxWidth().then(border).clip(Shapes.hero).background(MaterialTheme.colorScheme.surfaceContainer).padding(Spacing.heroPad),
        verticalArrangement = Arrangement.spacedBy(Spacing.subGap),
    ) {
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(Spacing.subGap)) {
            layout.large.forEach { id ->
                val m = Modifier.weight(1f).fillMaxHeight()
                if (snap == null) MetricCardSkeleton(CardSize.Large, m)
                else MetricCard(MetricCards.card(id, snap, CardSize.Large, mode, nowMs), CardSize.Large, { onCard(id) }, m)
            }
        }
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(Spacing.subGap)) {
            layout.small.forEach { id ->
                val m = Modifier.weight(1f).fillMaxHeight()
                if (snap == null) MetricCardSkeleton(CardSize.Small, m)
                else MetricCard(MetricCards.card(id, snap, CardSize.Small, mode, nowMs), CardSize.Small, { onCard(id) }, m)
            }
        }
        SummarySection(brief, onDetails)
    }
}

/** "Summary" with "Details ›", then exactly three bullets (placeholders of the same height while nothing is there yet). */
@Composable
private fun SummarySection(brief: com.fitair.app.coach.Brief?, onDetails: () -> Unit) {
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.fillMaxWidth().padding(horizontal = Spacing.s)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Summary", style = Type.metricTitle, color = dim)
            if (brief?.source == "template") Text("  rules", style = Type.caption, color = dim)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onDetails, modifier = Modifier.heightIn(min = Spacing.minTouch)) { Text("Details ›", style = Type.label) }
        }
        val bullets = brief?.bullets?.takeIf { it.size == 3 }
        repeat(3) { i ->
            Row(Modifier.fillMaxWidth().padding(bottom = if (i < 2) Spacing.s else Spacing.xs), verticalAlignment = Alignment.Top) {
                Box(Modifier.padding(top = 8.dp, end = 10.dp).size(6.dp).clip(androidx.compose.foundation.shape.CircleShape).background(MaterialTheme.colorScheme.onSurfaceVariant))
                if (bullets != null) Text(bullets[i], style = Type.bullet, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                else Box(Modifier.weight(1f).padding(vertical = 4.dp).height(14.dp).clip(Shapes.pill).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f)))
            }
        }
    }
}

/** Today's fact grid in a bottom sheet (steps, distance, cardio load, water, heart rate, workouts). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetailsSheet(facts: com.fitair.app.coach.DayFacts?, onDismiss: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = Shapes.sheet, containerColor = MaterialTheme.colorScheme.background, tonalElevation = 0.dp,
    ) {
        Column(Modifier.padding(start = Spacing.gutter, end = Spacing.gutter, bottom = Spacing.xxl)) {
            Text("Today so far", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(Spacing.s))
            DayFactsGrid(facts)
        }
    }
}

/** Next up (Morning, Day) or "Tomorrow 08:00 Shift" (Evening) as a slim strip; a calendar prompt without permission. */
@Composable
private fun NextUpStripBlock(agenda: AgendaVm, mode: Mode, nowMs: Long, onOpenCalendar: () -> Unit, onRequest: () -> Unit) {
    if (!agenda.hasPerm) {
        Row(
            Modifier.fillMaxWidth().clip(Shapes.tile).background(MaterialTheme.colorScheme.surfaceContainer).heightIn(min = 64.dp).padding(start = Spacing.l, end = Spacing.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("See today's events here", style = Type.body, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            TextButton(onClick = onRequest, modifier = Modifier.heightIn(min = Spacing.minTouch)) { Text("Show my calendar", style = Type.label) }
        }
        return
    }
    if (!agenda.loaded) return
    val z = remember { ZoneId.systemDefault() }
    val n = if (mode == Mode.Evening) null else AgendaFormat.nextUp(agenda.todayItems, Instant.ofEpochMilli(nowMs))
    if (n != null) {
        NextUpStrip("Next up", n.e.title.ifBlank { "Busy" }, AgendaFormat.nextUpLine(n, z),
            agenda.colors[n.e.calId]?.let { Color(it) }, n.running, onOpenCalendar)
    } else {
        val tomorrow = AgendaFormat.tomorrowLine(agenda.tomorrow, z)
        NextUpStrip(if (mode == Mode.Evening) "Next up" else "Free for the rest of the day", tomorrow ?: "Nothing scheduled tomorrow", null, null, false, onOpenCalendar)
    }
}

/** One quiet line for an alert-level insight (everything milder lives in the summary bullets). */
@Composable
private fun AlertLine(i: Insight) {
    Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
        StatusDot(toneColor(Tone.Alert))
        Spacer(Modifier.width(Spacing.m))
        Text("Worth a look: ${i.title}", style = Type.body)
    }
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

/** The Water strip (the layout drops it once the window has ended). */
@Composable
private fun WaterBlock(ui: TodayUi, mode: Mode, vm: TodayVm, onEnableReminders: () -> Unit) {
    val w = ui.water ?: return
    WaterCard(
        w.ml, w.goalMl, w.extraMl, w.pace, ui.glassMl, vm.logged?.ml,
        onAdd = vm::addWater, onUndo = vm::undoWater, remindersOn = w.remindersOn, onEnableReminders = onEnableReminders,
    )
}

