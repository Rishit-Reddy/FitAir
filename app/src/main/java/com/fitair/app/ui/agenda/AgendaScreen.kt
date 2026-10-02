package com.fitair.app.ui.agenda

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.fitair.app.integrations.calendar.AgendaItem
import com.fitair.app.ui.components.*
import com.fitair.app.ui.theme.Spacing
import com.fitair.app.ui.theme.Type
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val DAY_FMT = DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault())

/** Returns a function that asks for READ_CALENDAR; [onResult] runs with the outcome (denial is not an error). */
@Composable
fun rememberCalendarPermissionRequest(onResult: (Boolean) -> Unit = {}): () -> Unit {
    val l = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { onResult(it) }
    return { l.launch(Manifest.permission.READ_CALENDAR) }
}

/** Keeps [vm] fresh while the screen is visible: reload on resume and whenever the calendar provider changes. */
@Composable
fun AgendaLive(vm: AgendaVm) {
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(owner, vm.hasPerm) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            vm.refresh()
            if (vm.hasPerm) vm.changes().collect { vm.refresh() }
        }
    }
}

/** Full-screen day timeline: DetailHeader, day switcher, events and free gaps. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgendaScreen(onBack: () -> Unit) {
    val vm: AgendaVm = viewModel()
    val request = rememberCalendarPermissionRequest { vm.refresh() }
    AgendaLive(vm)
    val z = remember { ZoneId.systemDefault() }
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.fillMaxSize()) {
        DetailHeader("Agenda", onBack)
        if (!vm.hasPerm) {
            Column(Modifier.padding(horizontal = Spacing.gutter, vertical = Spacing.xl)) {
                Text("See your events next to your readiness.", style = Type.body, color = dim)
                TextButton(onClick = request) { Text("Show my calendar", style = Type.label) }
            }
            return@Column
        }
        DaySwitcher(vm.day, onPrev = { vm.shift(-1) }, onNext = { vm.shift(1) }, onToday = { vm.goToday() })
        PullToRefreshBox(isRefreshing = vm.loading && vm.loaded, onRefresh = vm::refresh, modifier = Modifier.weight(1f)) {
            Page {
                vm.error?.let { InlineError("Could not load events: $it", onRetry = vm::refresh) }
                val items = AgendaFormat.order(vm.items)
                when {
                    !vm.loaded -> Text("Loading…", style = Type.bodySmall, color = dim)
                    !AgendaFormat.hasEvents(items) -> Text(
                        if (vm.day == LocalDate.now()) AgendaFormat.emptyLine() else "Nothing scheduled",
                        style = Type.body, color = dim)
                    else -> items.forEach { AgendaItemRow(it, vm.colors, z, withEnd = true) }
                }
            }
        }
    }
}

@Composable
private fun DaySwitcher(day: LocalDate, onPrev: () -> Unit, onNext: () -> Unit, onToday: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.s), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onPrev) { Text("‹", style = MaterialTheme.typography.titleMedium) }
        Text(day.format(DAY_FMT), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        if (day != LocalDate.now()) TextButton(onClick = onToday) { Text("Today", style = Type.label) }
        TextButton(onClick = onNext) { Text("›", style = MaterialTheme.typography.titleMedium) }
    }
}

/** One timeline row: an event (with its calendar colour dot) or a free gap. */
@Composable
fun AgendaItemRow(item: AgendaItem, colors: Map<Long, Int>, z: ZoneId, withEnd: Boolean) {
    when (item) {
        is AgendaItem.Event -> {
            val e = item.e
            AgendaRow(AgendaFormat.eventTime(e, z, withEnd), e.title.ifBlank { "Busy" }, busy = e.busy,
                dot = colors[e.calId]?.let { Color(it) })
        }
        is AgendaItem.Gap -> FreeGapRow(AgendaFormat.range(item.from, item.to, z))
    }
}
