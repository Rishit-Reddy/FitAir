package com.fitair.app.ui.today

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fitair.app.integrations.calendar.AgendaItem
import com.fitair.app.ui.agenda.AgendaFormat
import com.fitair.app.ui.agenda.AgendaVm
import com.fitair.app.ui.theme.Type
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * What the day looks like, in four short pieces: the 00-24 strip, "Next: ...", the task count with its first title, and (after the
 * day is over) a tomorrow preview. The full calendar lives on the Calendar tab; any piece opens it.
 */
@Composable
fun DaySnippets(agenda: AgendaVm, sleepEndMs: Long?, nowMs: Long, onOpen: () -> Unit, onRequest: () -> Unit, modifier: Modifier = Modifier) {
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    if (!agenda.active) {
        Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("See today's events here", style = Type.body, color = dim, modifier = Modifier.weight(1f))
            TextButton(onClick = onRequest) { Text("Show my calendar", style = Type.label) }
        }
        return
    }
    if (!agenda.loaded) return
    val z = remember { ZoneId.systemDefault() }
    val events = agenda.todayItems.filterIsInstance<AgendaItem.Event>().map { it.e }
    val next = AgendaFormat.nextUp(agenda.todayItems, Instant.ofEpochMilli(nowMs))
    val nextLine = next?.let { "Next: ${it.e.title.ifBlank { "Busy" }} · ${AgendaFormat.nextUpLine(it, z)}" }
        ?: (if (events.any { !it.allDay }) "Nothing more today" else "No events today")
    val showTomorrow = CalendarDay.showTomorrow(LocalDateTime.now(), events)
    Column(modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onOpen), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        DayStrip(events, agenda.shifts, sleepEndMs, nowMs)
        Text(nextLine, style = Type.body, maxLines = 2, overflow = TextOverflow.Ellipsis)
        TodayLines.tasks(events)?.let { Text(it, style = Type.bodySmall, color = dim, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        if (showTomorrow) TodayLines.tomorrow(agenda.tomorrow, z)?.let { Text(it, style = Type.bodySmall, color = dim, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}
