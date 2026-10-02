package com.fitair.app.ui.agenda

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fitair.app.integrations.calendar.AgendaItem
import com.fitair.app.ui.theme.Spacing
import com.fitair.app.ui.theme.Type
import java.time.LocalDate

/** The Calendar tab's hour view of the selected day: all-day rows on top (at most a third), then the scrolling timeline. */
@Composable
fun CalendarHoursView(vm: AgendaVm, modifier: Modifier = Modifier) {
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    if (!vm.loaded) { Text("Loading…", style = Type.bodySmall, color = dim, modifier = modifier.padding(Spacing.gutter)); return }
    val events = vm.items.filterIsInstance<AgendaItem.Event>().map { it.e }
    val allDay = events.filter { it.allDay }
    val timed = events.filter { !it.allDay }
    val isToday = vm.day == LocalDate.now()
    val nowMs = remember(vm.day, vm.items) { System.currentTimeMillis() }
    BoxWithConstraints(modifier.fillMaxSize().padding(horizontal = Spacing.gutter)) {
        val strip = minOf(ALL_DAY_ROW_H * allDay.size, maxHeight / 3)
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
            if (allDay.isNotEmpty()) AllDayStrip(allDay, vm.colors, {}, Modifier.fillMaxWidth().height(strip))
            if (timed.isEmpty() && allDay.isEmpty()) Text(if (isToday) AgendaFormat.emptyLine() else "Nothing scheduled", style = Type.body, color = dim)
            else if (timed.isNotEmpty()) DayTimeline(timed, vm.day, vm.colors, if (isToday) nowMs else null, {}, Modifier.fillMaxWidth().weight(1f))
        }
    }
}
