package com.fitair.app.ui.settings

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.fitair.app.SyncPrefs
import com.fitair.app.integrations.calendar.CalendarInfo
import com.fitair.app.integrations.calendar.CalendarPrefs
import com.fitair.app.integrations.calendar.CalendarRepo
import com.fitair.app.ui.agenda.rememberCalendarPermissionRequest
import com.fitair.app.ui.components.SectionHeader
import com.fitair.app.ui.components.StatusDot
import com.fitair.app.ui.theme.Spacing
import com.fitair.app.ui.theme.Type
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

const val PREF_COACH_SHARE_TITLES = "coach_share_titles"

/** Settings > General > Calendar: permission, which calendars feed the agenda, and whether event titles go to the coach. */
@Composable
fun CalendarSection() {
    val ctx = LocalContext.current
    val repo = remember { CalendarRepo(ctx) }
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    var granted by remember { mutableStateOf(repo.hasPermission()) }
    val request = rememberCalendarPermissionRequest { granted = repo.hasPermission() }
    var cals by remember { mutableStateOf<List<CalendarInfo>>(emptyList()) }
    var selected by remember { mutableStateOf<Set<Long>?>(CalendarPrefs.selectedIds(ctx)) }
    LaunchedEffect(granted) {
        cals = if (granted) withContext(Dispatchers.IO) { runCatching { repo.calendars() }.getOrDefault(emptyList()) } else emptyList()
    }
    val prefs = remember { ctx.getSharedPreferences(SyncPrefs.FILE, Context.MODE_PRIVATE) }
    var share by remember { mutableStateOf(prefs.getBoolean(PREF_COACH_SHARE_TITLES, false)) }

    var showHowTo by remember { mutableStateOf<CalendarInfo?>(null) }
    showHowTo?.let { c ->
        AlertDialog(
            onDismissRequest = { showHowTo = null },
            title = { Text("Turn on sync") },
            text = { Text("This calendar is not synced to your phone, so it has no events here. In Google Calendar open Settings › ${c.name} › Sync. " +
                "Subscribed (URL) calendars are refreshed by Google only every few hours, sometimes up to a day.") },
            confirmButton = {
                TextButton(onClick = {
                    showHowTo = null
                    val i = ctx.packageManager.getLaunchIntentForPackage("com.google.android.calendar")
                    if (i != null) try { ctx.startActivity(i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) { }
                }) { Text("Open Google Calendar") }
            },
            dismissButton = { TextButton(onClick = { showHowTo = null }) { Text("Close") } },
        )
    }

    SectionHeader("Calendar")
    Spacer(Modifier.height(Spacing.s))
    if (!granted) {
        Text("Calendar access is off. Events stay on this device.", style = Type.bodySmall, color = dim)
        TextButton(onClick = request) { Text("Show my calendar", style = Type.label) }
    } else {
        Text("Calendar access is on.", style = Type.bodySmall, color = dim)
        var syncMsg by remember { mutableStateOf<String?>(null) }
        var syncBusy by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()
        TextButton(enabled = !syncBusy, onClick = {
            scope.launch {
                syncBusy = true
                val n = withContext(Dispatchers.IO) { repo.requestSync() }
                syncMsg = if (n == 0) "No synced calendar account found" else "Asked Google to sync. New events appear in a few seconds."
                kotlinx.coroutines.delay(8_000)
                syncBusy = false
            }
        }) { Text(if (syncBusy) "Syncing\u2026" else "Sync calendars now", style = Type.label) }
        syncMsg?.let { Text(it, style = Type.bodySmall, color = dim) }
        if (cals.isEmpty()) Text("No calendars found.", style = Type.bodySmall, color = dim)
        cals.forEach { c ->
            val on = selected?.contains(c.id) ?: true
            Row(Modifier.fillMaxWidth().heightIn(min = Spacing.minTouch), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = on, onCheckedChange = { v ->
                    val base = selected ?: cals.map { it.id }.toSet()
                    val next = if (v) base + c.id else base - c.id
                    selected = next; CalendarPrefs.setSelected(ctx, next)
                })
                StatusDot(Color(c.color))
                Spacer(Modifier.width(Spacing.s))
                Column(Modifier.weight(1f)) {
                    Text(c.name, style = Type.body, maxLines = 1)
                    if (c.account.isNotBlank()) Text(c.account, style = Type.bodySmall, color = dim, maxLines = 1)
                    if (!c.syncing) Text("Not synced to this phone", style = Type.bodySmall, color = dim)
                }
                if (!c.syncing) TextButton(onClick = { showHowTo = c }) { Text("How to turn on", style = Type.label) }
            }
        }
    }
    Spacer(Modifier.height(Spacing.s))
    Row(Modifier.fillMaxWidth().heightIn(min = Spacing.minTouch), verticalAlignment = Alignment.CenterVertically) {
        Text("Share event titles with the coach", style = Type.body, modifier = Modifier.weight(1f))
        Switch(checked = share, onCheckedChange = { share = it; prefs.edit().putBoolean(PREF_COACH_SHARE_TITLES, it).apply() })
    }
    Text("Off: the coach only sees when you are busy or free. On: event titles are sent to your AI provider too.",
        style = Type.bodySmall, color = dim)
    CalendarLinksSection()
}
