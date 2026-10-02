package com.fitair.app.ui.today

import androidx.compose.foundation.layout.*
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

/** Today, top to bottom: header, readiness hero, vitals, last night, exceptions. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodayScreen(vm: TodayVm, onOpen: (TodayDest) -> Unit = {}) {
    val ui = vm.ui
    var sheet by remember { mutableStateOf(false) }
    // tick once a minute so "synced 12 min ago" stays true
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(60_000); now = System.currentTimeMillis() } }

    PullToRefreshBox(isRefreshing = vm.syncing, onRefresh = vm::refresh, modifier = Modifier.fillMaxSize()) {
        Page {
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
            ReadinessHero(ui.readiness?.score, ui.readiness?.mainDriver ?: ui.readiness?.note, onClick = { if (ui.readiness != null) sheet = true else onOpen(TodayDest.Readiness) })

            SectionBreak()
            ui.vitals.chunked(2).forEachIndexed { i, pair ->
                if (i > 0) Spacer(Modifier.height(Spacing.m))
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.m)) {
                    pair.forEach { v ->
                        val m = Modifier.weight(1f).let { b ->
                            if (v.dest == null) b else b.clip(Shapes.card).clickable(role = Role.Button) { onOpen(v.dest!!) }
                        }
                        MetricTile(if (v.dest != null) v.label + " \u203A" else v.label, v.value, v.unit, v.delta, v.tone, m)
                    }
                }
            }

            ui.night?.let { n ->
                Spacer(Modifier.height(Spacing.m))
                val line = listOfNotNull(n.window, n.score?.let { "score $it" }, n.debt?.let { "$it debt" }).joinToString(" \u00B7 ")
                SummaryRow("Last night", line.ifEmpty { Format.DASH }, onClick = { onOpen(TodayDest.Sleep) })
            }

            if (ui.insights.isNotEmpty()) {
                SectionBreak()
                SectionHeader("Worth a look")
                Spacer(Modifier.height(Spacing.s))
                ui.insights.forEach { i ->
                    Row(Modifier.fillMaxWidth().padding(vertical = Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
                        StatusDot(toneColor(if (i.alert) Tone.Alert else Tone.Caution))
                        Spacer(Modifier.width(Spacing.m))
                        Text(i.title, style = Type.body)
                    }
                }
            }
        }
    }
    val r = ui?.readiness
    if (sheet && r != null) BreakdownSheet(r, onDismiss = { sheet = false }, onTrend = { sheet = false; onOpen(TodayDest.Readiness) })
}
