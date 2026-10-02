package com.fitair.app.ui.today

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import com.fitair.app.ui.theme.Shapes
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.material3.TextButton
import com.fitair.app.core.Format
import com.fitair.app.ui.agenda.AgendaFormat
import com.fitair.app.ui.agenda.AgendaItemRow
import com.fitair.app.ui.agenda.AgendaLive
import com.fitair.app.ui.agenda.AgendaVm
import com.fitair.app.ui.agenda.rememberCalendarPermissionRequest
import java.time.ZoneId
import com.fitair.app.ui.components.*
import com.fitair.app.ui.theme.Spacing
import com.fitair.app.ui.theme.Type
import kotlinx.coroutines.delay
import java.time.format.DateTimeFormatter
import java.util.Locale

private val DATE_FMT = DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault())

/** Screens opened by tapping a row or card on Today. */
enum class TodayDest { Agenda, Sleep, Readiness, Hrv, RestingHr }

/** Today: header, then [todayBlocks] in order (readiness, sleep card, vitals, exceptions). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodayScreen(vm: TodayVm, onOpen: (TodayDest) -> Unit = {}, scroll: ScrollState = rememberScrollState()) {
    val ui = vm.ui
    var sheet by remember { mutableStateOf(false) }
    val agenda: AgendaVm = viewModel()
    val requestCalendar = rememberCalendarPermissionRequest { agenda.refresh() }
    LaunchedEffect(Unit) { agenda.goToday() }
    AgendaLive(agenda)
    // tick once a minute so "synced 12 min ago" stays true
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(60_000); now = System.currentTimeMillis() } }

    PullToRefreshBox(isRefreshing = vm.syncing, onRefresh = vm::refresh, modifier = Modifier.fillMaxSize()) {
        Page(scrollState = scroll) {
            val fresh = Format.freshness(now, ui?.lastSyncMs ?: 0L)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(ui?.date?.format(DATE_FMT) ?: "", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                FreshnessPill(fresh.text, fresh.stale)
            }
            Spacer(Modifier.height(Spacing.xl))
            vm.error?.let { InlineError(it, onRetry = vm::load) }
            if (ui == null) return@Page
            if (!ui.hasData) {
                EmptyState("No data yet", "Pull down to sync from Health Connect.")
                return@Page
            }
            val blocks = todayBlocks(ui)
            blocks.forEachIndexed { i, b ->
                if (i > 0) { if (blocks[i - 1].card && b.card) Spacer(Modifier.height(Spacing.m)) else SectionBreak() }
                when (b) {
                    TodayBlock.Readiness -> ReadinessBlock(ui, onClick = { if (ui.readiness != null) sheet = true else onOpen(TodayDest.Readiness) })
                    TodayBlock.Agenda -> AgendaBlock(agenda, onOpen = { onOpen(TodayDest.Agenda) }, onRequest = requestCalendar)
                    TodayBlock.Sleep -> SleepBlock(ui.night!!, onOpen)
                    TodayBlock.Vitals -> VitalsBlock(ui.vitals, onOpen)
                    TodayBlock.Insights -> InsightsBlock(ui.insights)
                }
            }
        }
    }
    val r = ui?.readiness
    if (sheet && r != null) BreakdownSheet(r, onDismiss = { sheet = false }, onTrend = { sheet = false; onOpen(TodayDest.Readiness) })
}

@Composable
private fun ReadinessBlock(ui: TodayUi, onClick: () -> Unit) =
    ReadinessHero(ui.readiness?.score, ui.readiness?.mainDriver ?: ui.readiness?.note, onClick = onClick)

/** "AGENDA ›" caption (opens the day timeline), then rows, a quiet prompt, or the empty line. */
@Composable
private fun AgendaBlock(vm: AgendaVm, onOpen: () -> Unit, onRequest: () -> Unit) {
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    val z = remember { ZoneId.systemDefault() }
    Box(Modifier.heightIn(min = Spacing.minTouch).clickable(role = Role.Button, onClick = onOpen), contentAlignment = Alignment.CenterStart) {
        SectionHeader("Agenda \u203A")
    }
    if (!vm.hasPerm) {
        Text("See today's events next to your readiness", style = Type.body, color = dim)
        TextButton(onClick = onRequest) { Text("Show my calendar", style = Type.label) }
        return
    }
    if (!vm.loaded) return
    if (!AgendaFormat.hasEvents(vm.items)) { Text(AgendaFormat.emptyLine(), style = Type.body, color = dim); return }
    val c = AgendaFormat.collapse(vm.items)
    c.visible.forEach { AgendaItemRow(it, vm.colors, z, withEnd = false) }
    if (c.hidden) TextButton(onClick = onOpen) { Text("Show all (${c.eventCount})", style = Type.label) }
}

@Composable
private fun SleepBlock(n: SleepNight, onOpen: (TodayDest) -> Unit) =
    SleepCard(Format.duration(n.durationMin), n.window, n.score, n.needDiffMin, n.debt, n.stages, onClick = { onOpen(TodayDest.Sleep) })

/** One row of compact tiles with equal heights. */
@Composable
private fun VitalsBlock(vitals: List<Vital>, onOpen: (TodayDest) -> Unit) {
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(Spacing.m)) {
        vitals.forEach { v ->
            val m = Modifier.weight(1f).fillMaxHeight().let { b ->
                if (v.dest == null) b else b.clip(Shapes.card).clickable(role = Role.Button) { onOpen(v.dest!!) }
            }
            MetricTile(if (v.dest != null) v.label + " \u203A" else v.label, v.value, v.unit, v.delta, v.tone, m, compact = true)
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
