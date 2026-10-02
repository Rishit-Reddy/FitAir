package com.fitair.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.fitair.app.ui.theme.LocalStatusColors
import com.fitair.app.ui.theme.Spacing
import com.fitair.app.ui.theme.Type

/** Scrolling column with the page gutter. */
@Composable
fun Page(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Spacing.gutter, vertical = Spacing.xl),
        content = content,
    )
}

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) =
    Text(text.uppercase(), style = Type.caption, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier)

@Composable
fun Hairline(modifier: Modifier = Modifier) =
    HorizontalDivider(modifier, thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)

/** 24dp gap, hairline, 24dp gap: the separator between Today sections. */
@Composable
fun SectionBreak() {
    Spacer(Modifier.height(Spacing.section))
    Hairline()
    Spacer(Modifier.height(Spacing.section))
}

enum class Tone { Good, Neutral, Caution, Alert }

@Composable
fun toneColor(t: Tone): Color = when (t) {
    Tone.Good -> LocalStatusColors.current.good
    Tone.Caution -> LocalStatusColors.current.caution
    Tone.Alert -> LocalStatusColors.current.alert
    Tone.Neutral -> MaterialTheme.colorScheme.onSurfaceVariant
}

/** The 6dp status dot. */
@Composable
fun StatusDot(color: Color, modifier: Modifier = Modifier) =
    Box(modifier.size(6.dp).clip(CircleShape).background(color))

/** Label left, value right, hairline below. */
@Composable
fun StatRow(label: String, value: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
    Hairline()
}

@Composable
fun InlineError(message: String, onRetry: (() -> Unit)? = null, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(vertical = Spacing.s), verticalAlignment = Alignment.CenterVertically) {
        Text(message, style = Type.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
        if (onRetry != null) TextButton(onClick = onRetry) { Text("Retry") }
    }
}

@Composable
fun EmptyState(title: String, body: String? = null, action: String? = null, onAction: (() -> Unit)? = null, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(vertical = Spacing.xxl), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        if (body != null) {
            Spacer(Modifier.height(Spacing.s))
            Text(body, style = Type.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
        if (action != null && onAction != null) TextButton(onClick = onAction) { Text(action) }
    }
}

/** Plain-text sub navigation (General / Data / Logs / Diagnostics). */
@Composable
fun SubTabs(labels: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth()) {
            labels.forEachIndexed { i, l ->
                val on = i == selected
                Column(
                    Modifier.weight(1f).heightIn(min = Spacing.minTouch).clickable { onSelect(i) },
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                ) {
                    Text(l, style = Type.label, color = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(Spacing.s))
                    Box(Modifier.height(2.dp).fillMaxWidth(0.5f).background(if (on) MaterialTheme.colorScheme.primary else Color.Transparent))
                }
            }
        }
        Hairline()
    }
}
