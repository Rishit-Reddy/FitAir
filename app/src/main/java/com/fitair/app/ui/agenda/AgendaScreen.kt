package com.fitair.app.ui.agenda

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.CalendarContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDateTime
import java.time.temporal.WeekFields
import com.fitair.app.ui.theme.NavIcons
import com.fitair.app.ui.theme.Shapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
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
    LaunchedEffect(owner, vm.hasPerm, vm.hasFeeds) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            vm.refresh()
            if (vm.hasPerm) launch { vm.changes().collect { vm.refresh() } }
            FeedSignal.tick.collect { vm.refresh() }
        }
    }
}

/** Opens Google Calendar's "new event" screen pre-filled for [day] (a bridge until in-app editing, 0.8.3). No permission needed. */
@Composable
fun rememberInsertEvent(): (LocalDate) -> Unit {
    val ctx = LocalContext.current
    return { day ->
        val z = ZoneId.systemDefault()
        val start = AgendaFormat.insertStart(day, LocalDateTime.now(z)).atZone(z).toInstant().toEpochMilli()
        val i = Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI)
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, start)
            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, start + 30 * 60_000L)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { ctx.startActivity(i) } catch (e: ActivityNotFoundException) { com.fitair.app.AppLog.d("calendar insert: no app") }
    }
}

/**
 * The Calendar tab ([onBack] null: no back arrow) or the legacy detail screen: week strip, "Next up" for today, the day list and a
 * "+" that opens Google Calendar. [onOpenSettings] is used by the "calendar has no events on this phone" line.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgendaScreen(onBack: (() -> Unit)? = null, onOpenSettings: () -> Unit = {}) {
    val vm: AgendaVm = viewModel()
    val request = rememberCalendarPermissionRequest { vm.refresh() }
    val insert = rememberInsertEvent()
    AgendaLive(vm)
    LaunchedEffect(Unit) { if (onBack == null) vm.goToday() }
    val z = remember { ZoneId.systemDefault() }
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    var hours by rememberSaveable { mutableStateOf(false) }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            if (onBack != null) DetailHeader("Calendar", onBack)
            else Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.gutter), verticalAlignment = Alignment.CenterVertically) {
                Text("Calendar", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).padding(vertical = Spacing.l))
                if (vm.active) TextButton(onClick = { hours = !hours }) { Text(if (hours) "List" else "Hours", style = Type.label) }
                if (vm.active) TextButton(onClick = vm::resync, enabled = !vm.syncing) {
                    Text(if (vm.syncing) "Syncing\u2026" else "Sync", style = Type.label)
                }
            }
            vm.syncNote?.let {
                Text(it, style = Type.bodySmall, color = dim, modifier = Modifier.padding(horizontal = Spacing.gutter, vertical = Spacing.xs))
            }
            if (!vm.active) {
                Column(Modifier.padding(horizontal = Spacing.gutter, vertical = Spacing.xl)) {
                    Text("See your events next to your readiness.", style = Type.body, color = dim)
                    TextButton(onClick = request) { Text("Show my calendar", style = Type.label) }
                    Text("Or paste a calendar link in Settings.", style = Type.bodySmall, color = dim)
                    TextButton(onClick = onOpenSettings) { Text("Open settings", style = Type.label) }
                }
                return@Column
            }
            WeekStrip(vm.day, onPick = { vm.showDay(it) }, onShift = { vm.shift(it * 7L) }, onToday = { vm.goToday() })
            PullToRefreshBox(isRefreshing = (vm.loading && vm.loaded) || vm.feedBusy, onRefresh = vm::pullRefresh, modifier = Modifier.weight(1f)) {
                if (hours) CalendarHoursView(vm) else Page {
                    vm.error?.let { InlineError("Could not load events: $it", onRetry = vm::refresh) }
                    CalendarDiagnostic(vm.unsynced, onOpenSettings)
                    val isToday = vm.day == LocalDate.now()
                    if (isToday && vm.loaded) {
                        NextUpBlock(vm.todayItems, vm.tomorrow, vm.colors, z, onClick = null)
                        Spacer(Modifier.height(Spacing.l))
                    }
                    val items = AgendaFormat.order(vm.items)
                    when {
                        !vm.loaded -> Text("Loading…", style = Type.bodySmall, color = dim)
                        !AgendaFormat.hasEvents(items) -> Text(
                            if (isToday) AgendaFormat.emptyLine() else "Nothing scheduled",
                            style = Type.body, color = dim)
                        else -> AgendaList(items, vm.colors, z, withEnd = true, markNow = isToday)
                    }
                    Spacer(Modifier.height(72.dp)) // room for the "+" button
                }
            }
        }
        if (vm.hasPerm) {
            FloatingActionButton(
                onClick = { insert(vm.day) },
                modifier = Modifier.align(Alignment.BottomEnd).padding(Spacing.l).semantics { contentDescription = "Add event" },
                containerColor = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ) { Icon(NavIcons.Add, contentDescription = null) }
        }
    }
}

/** "1 selected calendar has no events on this phone ›" (opens Settings > Calendars); nothing when all selected calendars sync. */
@Composable
fun CalendarDiagnostic(unsynced: Int, onOpen: () -> Unit) {
    if (unsynced <= 0) return
    val text = if (unsynced == 1) "1 selected calendar has no events on this phone \u203A" else "$unsynced selected calendars have no events on this phone \u203A"
    Box(Modifier.fillMaxWidth().heightIn(min = Spacing.minTouch).clickable(role = Role.Button, onClick = onOpen), contentAlignment = Alignment.CenterStart) {
        Text(text, style = Type.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The "Next up" card from today's items, or "Free for the rest of the day" plus tomorrow's first event. */
@Composable
fun NextUpBlock(todayItems: List<AgendaItem>, tomorrow: List<com.fitair.app.integrations.calendar.CalEvent>, colors: AgendaColors, z: ZoneId, onClick: (() -> Unit)?) {
    val n = AgendaFormat.nextUp(todayItems, Instant.now())
    if (n != null) {
        NextUpCard(n.e.title.ifBlank { "Busy" }, AgendaFormat.nextUpLine(n, z), colors.of(n.e)?.let { Color(it) }, n.running, null, onClick)
    } else {
        NextUpCard(null, null, null, false, AgendaFormat.tomorrowLine(tomorrow, z) ?: "Nothing scheduled tomorrow", onClick)
    }
}

/** Seven day cells of the selected day's week with ‹ › to move a week; the selected day sits in a soft circle. */
@Composable
private fun WeekStrip(day: LocalDate, onPick: (LocalDate) -> Unit, onShift: (Int) -> Unit, onToday: () -> Unit) {
    val first = remember { WeekFields.of(Locale.getDefault()).firstDayOfWeek }
    val days = remember(day, first) { AgendaFormat.weekDays(day, first) }
    val today = LocalDate.now()
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.fillMaxWidth().padding(horizontal = Spacing.s)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { onShift(-1) }) { Text("\u2039", style = MaterialTheme.typography.titleMedium) }
            Text(day.format(DAY_FMT), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            if (day != today) TextButton(onClick = onToday) { Text("Today", style = Type.label) }
            TextButton(onClick = { onShift(1) }) { Text("\u203A", style = MaterialTheme.typography.titleMedium) }
        }
        Row(Modifier.fillMaxWidth()) {
            days.forEach { d ->
                val sel = d == day
                Column(
                    Modifier.weight(1f).heightIn(min = Spacing.minTouch).clickable(role = Role.Button) { onPick(d) },
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                ) {
                    Text(d.dayOfWeek.getDisplayName(java.time.format.TextStyle.NARROW, Locale.getDefault()), style = Type.caption, color = dim)
                    Spacer(Modifier.height(2.dp))
                    Box(
                        Modifier.size(32.dp).clip(CircleShape).background(if (sel) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "${d.dayOfMonth}", style = Type.body,
                            color = if (sel) MaterialTheme.colorScheme.onSecondaryContainer else if (d == today) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }
        Hairline()
    }
}

/** Timeline rows; with [markNow] a hairline "now" marker sits between the running events and the ones to come. */
@Composable
fun AgendaList(items: List<AgendaItem>, colors: AgendaColors, z: ZoneId, withEnd: Boolean, markNow: Boolean) {
    val now = Instant.now()
    val lastStarted = if (!markNow) -1 else items.indexOfLast { it is AgendaItem.Event && !it.e.allDay && it.e.begin <= now }
    items.forEachIndexed { i, it ->
        AgendaItemRow(it, colors, z, withEnd)
        if (i == lastStarted && i < items.lastIndex) NowMarker()
    }
}

@Composable
private fun NowMarker() {
    Row(Modifier.fillMaxWidth().heightIn(min = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("now", style = Type.caption, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(104.dp))
        Hairline(Modifier.weight(1f))
    }
}

/** One timeline row: an event (with its calendar colour dot) or a free gap. */
@Composable
fun AgendaItemRow(item: AgendaItem, colors: AgendaColors, z: ZoneId, withEnd: Boolean) {
    when (item) {
        is AgendaItem.Event -> {
            val e = item.e
            AgendaRow(AgendaFormat.eventTime(e, z, withEnd), e.title.ifBlank { "Busy" }, busy = e.busy,
                dot = colors.of(e)?.let { Color(it) }, tag = if (e.work) "shift" else null)
        }
        is AgendaItem.Gap -> FreeGapRow(AgendaFormat.range(item.from, item.to, z))
    }
}
