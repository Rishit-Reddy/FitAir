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

    SectionHeader("Calendar")
    Spacer(Modifier.height(Spacing.s))
    if (!granted) {
        Text("Calendar access is off. Events stay on this device.", style = Type.bodySmall, color = dim)
        TextButton(onClick = request) { Text("Show my calendar", style = Type.label) }
    } else {
        Text("Calendar access is on.", style = Type.bodySmall, color = dim)
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
                }
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
}
