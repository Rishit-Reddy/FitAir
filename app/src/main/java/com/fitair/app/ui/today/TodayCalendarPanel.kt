package com.fitair.app.ui.today

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fitair.app.integrations.calendar.AgendaItem
import com.fitair.app.ui.agenda.ALL_DAY_ROW_H
import com.fitair.app.ui.agenda.AgendaVm
import com.fitair.app.ui.agenda.AllDayStrip
import com.fitair.app.ui.agenda.DayTimeline
import com.fitair.app.ui.theme.Spacing
import com.fitair.app.ui.theme.Type
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * The calendar part of Today: a caption with a Today/Tomorrow switch, the all-day strip and the scrolling day timeline.
 * Opens on today (see [CalendarDay]). Only all-day events: the strip alone. Nothing at all: a short note.
 */
@Composable
fun TodayCalendarPanel(agenda: AgendaVm, nowMs: Long, onOpen: () -> Unit, onRequest: () -> Unit, modifier: Modifier = Modifier) {
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    if (!agenda.active) {
        Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("See today's events here", style = Type.body, color = dim, modifier = Modifier.weight(1f))
            TextButton(onClick = onRequest) { Text("Show my calendar", style = Type.label) }
        }
        return
    }
    if (!agenda.loaded) return
    val todayEvents = agenda.todayItems.filterIsInstance<AgendaItem.Event>().map { it.e }
    val auto = CalendarDay.showTomorrow(LocalDateTime.now(), todayEvents)
    var pick by remember { mutableStateOf<Boolean?>(null) } // null = follow the rule
    val tomorrow = pick ?: auto
    val events = if (tomorrow) agenda.tomorrow else todayEvents
    val day = remember(tomorrow) { LocalDate.now().plusDays(if (tomorrow) 1 else 0) }
    val allDay = events.filter { it.allDay }
    val timed = events.filter { !it.allDay }
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 32.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (tomorrow) "Tomorrow" else "Today", style = Type.metricTitle, color = dim, modifier = Modifier.weight(1f))
            TextButton(onClick = { pick = !tomorrow }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                Text(if (tomorrow) "Today" else "Tomorrow", style = Type.label)
            }
            TextButton(onClick = onOpen, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("Calendar ›", style = Type.label) }
        }
        when {
            events.isEmpty() -> Text(if (tomorrow) "Nothing scheduled tomorrow." else "Nothing scheduled today.", style = Type.body, color = dim)
            timed.isEmpty() -> AllDayStrip(allDay, agenda.colors, onOpen, Modifier.fillMaxWidth().weight(1f, fill = false))
            else -> BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
                val strip = minOf(ALL_DAY_ROW_H * allDay.size, maxHeight / 3)
                Column(Modifier.fillMaxSize()) {
                    if (allDay.isNotEmpty()) {
                        AllDayStrip(allDay, agenda.colors, onOpen, Modifier.fillMaxWidth().height(strip))
                        Spacer(Modifier.height(Spacing.s))
                    }
                    DayTimeline(timed, day, agenda.colors, if (tomorrow) null else nowMs, onOpen, Modifier.fillMaxWidth().weight(1f))
                }
            }
        }
    }
}
