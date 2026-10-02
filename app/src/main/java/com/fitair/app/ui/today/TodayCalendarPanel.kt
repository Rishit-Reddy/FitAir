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
import androidx.compose.ui.unit.dp
import com.fitair.app.integrations.calendar.AgendaItem
import com.fitair.app.ui.agenda.ALL_DAY_ROW_H
import com.fitair.app.ui.agenda.AgendaVm
import com.fitair.app.ui.agenda.AllDayStrip
import com.fitair.app.ui.agenda.DayTimeline
import com.fitair.app.ui.theme.Spacing
import com.fitair.app.ui.theme.Type
import java.time.LocalDate

/**
 * The calendar part of Today: a caption, the all-day strip (about a third of the panel at most) and the scrolling day timeline.
 * In the evening it shows tomorrow, because today is over.
 */
@Composable
fun TodayCalendarPanel(agenda: AgendaVm, evening: Boolean, nowMs: Long, onOpen: () -> Unit, onRequest: () -> Unit, modifier: Modifier = Modifier) {
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    if (!agenda.active) {
        Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("See today's events here", style = Type.body, color = dim, modifier = Modifier.weight(1f))
            TextButton(onClick = onRequest) { Text("Show my calendar", style = Type.label) }
        }
        return
    }
    if (!agenda.loaded) return
    val day = remember(evening) { LocalDate.now().plusDays(if (evening) 1 else 0) }
    val events = if (evening) agenda.tomorrow else agenda.todayItems.filterIsInstance<AgendaItem.Event>().map { it.e }
    val allDay = events.filter { it.allDay }
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val strip = minOf(ALL_DAY_ROW_H * allDay.size, maxHeight / 3)
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().heightIn(min = 28.dp).clickable(role = Role.Button, onClick = onOpen), verticalAlignment = Alignment.CenterVertically) {
                Text(if (evening) "Tomorrow ›" else "Today ›", style = Type.metricTitle, color = dim)
            }
            if (allDay.isNotEmpty()) {
                AllDayStrip(allDay, agenda.colors, onOpen, Modifier.fillMaxWidth().height(strip))
                Spacer(Modifier.height(Spacing.s))
            }
            DayTimeline(events, day, agenda.colors, if (evening) null else nowMs, onOpen, Modifier.fillMaxWidth().weight(1f))
        }
    }
}
