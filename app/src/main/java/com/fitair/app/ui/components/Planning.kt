package com.fitair.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.fitair.app.ui.theme.Shapes
import com.fitair.app.ui.theme.Spacing
import com.fitair.app.ui.theme.Type

/** Building blocks for the planner phases (P2-P5); unused on Today until then. */

@Composable
fun SuggestionCard(title: String, reason: String, onAccept: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().clip(Shapes.card).background(MaterialTheme.colorScheme.surfaceVariant).padding(Spacing.l)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(reason, style = Type.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row {
            TextButton(onClick = onAccept) { Text("Accept", style = Type.label) }
            TextButton(onClick = onDismiss) { Text("Dismiss", style = Type.label, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

/** One calendar event: time on the left, optional calendar colour dot, title; tentative (non-busy) events render dimmed. */
@Composable
fun AgendaRow(time: String, title: String, busy: Boolean = true, modifier: Modifier = Modifier, dot: Color? = null) {
    val c = if (busy) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
    Row(modifier.fillMaxWidth().heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(time, style = Type.body, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(104.dp))
        if (dot != null) { StatusDot(dot); Spacer(Modifier.width(Spacing.s)) }
        Text(title, style = Type.body, color = c, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A free slot between events; the time is onSurfaceVariant (primary is for interaction). */
@Composable
fun FreeGapRow(range: String, modifier: Modifier = Modifier) {
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    Row(modifier.fillMaxWidth().heightIn(min = 32.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(range, style = Type.bodySmall, color = dim, modifier = Modifier.width(104.dp))
        Text("free", style = Type.bodySmall, color = dim)
    }
}

@Composable
fun TaskRow(title: String, done: Boolean, onToggle: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().heightIn(min = Spacing.minTouch), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = done, onCheckedChange = onToggle)
        Text(title, style = Type.body, textDecoration = if (done) TextDecoration.LineThrough else null,
            color = if (done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
    }
}

/** 1-5 one-tap picker. */
@Composable
fun ScaleChips(selected: Int?, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
        (1..5).forEach { n -> FilterChip(selected = selected == n, onClick = { onSelect(n) }, label = { Text("$n") }, shape = Shapes.chip) }
    }
}
