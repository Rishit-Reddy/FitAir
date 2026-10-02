package com.fitair.app.ui.today

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.fitair.app.data.metrics.MetricId
import com.fitair.app.data.metrics.MetricSnapshot
import com.fitair.app.ui.components.CardData
import com.fitair.app.ui.components.StatusDot
import com.fitair.app.ui.components.Tone
import com.fitair.app.ui.components.toneColor
import com.fitair.app.ui.metrics.MetricCards
import com.fitair.app.ui.theme.Shapes
import com.fitair.app.ui.theme.Spacing
import com.fitair.app.ui.theme.Type

/** Which numbers sit in the vitals row. Heart rate has its own big card above it. */
fun vitalIds(evening: Boolean): List<MetricId> =
    if (evening) listOf(MetricId.Load, MetricId.Steps, MetricId.Bedtime) else listOf(MetricId.Readiness, MetricId.Sleep, MetricId.Load, MetricId.Steps)

/** One row of small number tiles: label, value, and a status dot with one or two words. No charts. */
@Composable
fun VitalsRow(snap: MetricSnapshot?, ids: List<MetricId>, mode: Mode, nowMs: Long, onCard: (MetricId) -> Unit, modifier: Modifier = Modifier) {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    Row(modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
        ids.forEach { id ->
            val m = Modifier.weight(1f).fillMaxHeight()
            if (snap == null) Box(m.heightIn(min = 72.dp).clip(Shapes.tile).background(MaterialTheme.colorScheme.surfaceContainer))
            else VitalTile(MetricCards.card(id, snap, com.fitair.app.ui.components.CardSize.Small, mode, nowMs), dark, { onCard(id) }, m)
        }
    }
}

@Composable
private fun VitalTile(d: CardData, dark: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    val border = if (dark) Modifier else Modifier.border(1.dp, Color(0xFFE7E7E4), Shapes.tile)
    val chip = d.chip
    Column(
        modifier.then(border).clip(Shapes.tile).background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable(role = Role.Button, onClick = onClick).clearAndSetSemantics { contentDescription = d.a11y; role = Role.Button }
            .heightIn(min = 72.dp).padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Text(d.title, style = Type.axis, color = dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            buildAnnotatedString {
                append(d.value ?: "—")
                if (d.value != null && !d.unit.isNullOrEmpty()) withStyle(SpanStyle(fontSize = Type.unitS.fontSize, color = dim)) { append(" "); append(d.unit) }
            },
            style = Type.valueS, maxLines = 1, softWrap = false, overflow = TextOverflow.Clip,
        )
        Spacer(Modifier.weight(1f).heightIn(min = 2.dp))
        val note = chip?.short ?: chip?.text ?: d.sub
        if (note != null) Row(verticalAlignment = Alignment.CenterVertically) {
            if (chip != null && chip.tone != Tone.Neutral) { StatusDot(toneColor(chip.tone)); Spacer(Modifier.width(5.dp)) }
            Text(note, style = Type.axis, color = dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
