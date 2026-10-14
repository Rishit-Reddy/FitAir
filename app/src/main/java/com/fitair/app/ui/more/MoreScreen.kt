package com.fitair.app.ui.more

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.fitair.app.ui.components.Hairline
import com.fitair.app.ui.components.Page
import com.fitair.app.ui.components.SectionHeader
import com.fitair.app.ui.theme.Spacing
import com.fitair.app.ui.theme.Type
import com.fitair.app.ui.today.TodayDest

/** Everything that needs no daily attention. One row per page, three short sections. */
@Composable
fun MoreScreen(onOpen: (TodayDest) -> Unit) {
    Page {
        Text("More", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(Spacing.l))
        Group("Routines", listOf(
            Entry("Hair routine", "Minoxidil, shampoo days, reminders", TodayDest.Hair),
            Entry("Water", "Log, history, reminders", TodayDest.Water),
        ), onOpen)
        Spacer(Modifier.height(Spacing.l))
        Group("Log", listOf(
            Entry("Weight and BMI", "Enter weight and height", TodayDest.Weight),
            Entry("Pickleball", "Timer and session notes", TodayDest.Pickleball),
        ), onOpen)
        Spacer(Modifier.height(Spacing.l))
        Group("App", listOf(Entry("Settings", "Sync, backup, calendar, theme", TodayDest.Settings)), onOpen)
    }
}

private class Entry(val title: String, val sub: String, val dest: TodayDest)

@Composable
private fun Group(title: String, rows: List<Entry>, onOpen: (TodayDest) -> Unit) {
    SectionHeader(title)
    Spacer(Modifier.height(Spacing.xs))
    rows.forEachIndexed { i, r ->
        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(role = Role.Button) { onOpen(r.dest) }, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(r.title, style = Type.body)
                Text(r.sub, style = Type.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("›", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (i < rows.lastIndex) Hairline()
    }
}
