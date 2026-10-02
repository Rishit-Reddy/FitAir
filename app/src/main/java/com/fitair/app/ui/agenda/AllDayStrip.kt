package com.fitair.app.ui.agenda

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fitair.app.integrations.calendar.CalEvent
import com.fitair.app.ui.theme.Type

/** Rows of the all-day strip are this tall, so the caller can size the strip to its content. */
val ALL_DAY_ROW_H = 30.dp

/** All-day events (mostly Todoist tasks) as compact rows that scroll on their own. */
@Composable
fun AllDayStrip(events: List<CalEvent>, colors: AgendaColors, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    Column(modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainer).verticalScroll(rememberScrollState())) {
        events.forEach { e ->
            Row(Modifier.fillMaxWidth().heightIn(min = ALL_DAY_ROW_H).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(colors.of(e)?.let { Color(it) } ?: dim))
                Spacer(Modifier.width(10.dp))
                Text(e.title.ifBlank { "Busy" }, style = Type.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            }
        }
    }
}
