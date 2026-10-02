package com.fitair.app.ui.today

import androidx.compose.foundation.layout.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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

/** Today, top to bottom: header, readiness hero, vitals, last night, exceptions. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodayScreen(vm: TodayVm) {
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
            ReadinessHero(ui.readiness?.score, ui.readiness?.mainDriver ?: ui.readiness?.note, onClick = { if (ui.readiness != null) sheet = true })

            SectionBreak()
            ui.vitals.chunked(2).forEachIndexed { i, pair ->
                if (i > 0) Spacer(Modifier.height(Spacing.m))
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.m)) {
                    pair.forEach { v -> MetricTile(v.label, v.value, v.unit, v.delta, v.tone, Modifier.weight(1f)) }
                }
            }

            ui.night?.let { n ->
                SectionBreak()
                SectionHeader("Last night")
                Spacer(Modifier.height(Spacing.xs))
                StatRow("Asleep", n.asleep)
                n.window?.let { StatRow("In bed", it) }
                n.score?.let { StatRow("Sleep score", it) }
                n.efficiency?.let { StatRow("Efficiency", it) }
                n.deepRem?.let { StatRow("Deep + REM", it) }
                n.debt?.let { StatRow("Sleep debt, 7 nights", it) }
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
    if (sheet && r != null) BreakdownSheet(r) { sheet = false }
}
