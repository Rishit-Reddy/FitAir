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
import com.fitair.app.core.Format
import com.fitair.app.ui.components.*
import com.fitair.app.ui.theme.Spacing
import com.fitair.app.ui.theme.Type
import kotlinx.coroutines.delay
import java.time.format.DateTimeFormatter
import java.util.Locale

private val DATE_FMT = DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault())

/** Screens opened by tapping a row or card on Today. */
enum class TodayDest { Sleep, Readiness, Hrv, RestingHr }

/** Today: header, then [todayBlocks] in order (readiness, sleep card, vitals, exceptions). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodayScreen(vm: TodayVm, onOpen: (TodayDest) -> Unit = {}, scroll: ScrollState = rememberScrollState()) {
    val ui = vm.ui
    var sheet by remember { mutableStateOf(false) }
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
