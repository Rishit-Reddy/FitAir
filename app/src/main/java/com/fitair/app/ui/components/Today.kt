package com.fitair.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fitair.app.core.Format
import com.fitair.app.data.dao.Pace
import com.fitair.app.ui.copy.Copy
import com.fitair.app.ui.theme.LocalStatusColors
import com.fitair.app.ui.theme.Shapes
import com.fitair.app.ui.theme.Spacing
import com.fitair.app.ui.theme.Type

/** "data to 14:05" (newest data) over "checked 14:20" (last sync) with a status dot; tap opens the sync-status sheet. */
@Composable
fun FreshnessPill(text: String, stale: Boolean, modifier: Modifier = Modifier, sub: String? = null, onClick: (() -> Unit)? = null) {
    val st = LocalStatusColors.current
    Row(
        modifier.clip(Shapes.chip).background(MaterialTheme.colorScheme.surfaceVariant)
            .then(if (onClick != null) Modifier.clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onClick) else Modifier)
            .padding(horizontal = Spacing.m, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(if (stale) st.caution else MaterialTheme.colorScheme.outline)
        Spacer(Modifier.width(6.dp))
        Column {
            Text(text, style = Type.label, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (sub != null) Text(sub, style = Type.caption, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * Next up: the running or next event, big title, "14:30–15:15 · in 40 min · location", a 3dp left edge in the calendar colour
 * and a "Now" chip while it runs. [title] null with [freeLine] shows "Free for the rest of the day".
 */
@Composable
fun NextUpCard(
    title: String?, line: String?, edge: Color?, running: Boolean, freeLine: String?, onClick: (() -> Unit)?, modifier: Modifier = Modifier,
) {
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier.fillMaxWidth().height(IntrinsicSize.Min).clip(Shapes.card).background(MaterialTheme.colorScheme.surfaceVariant)
            .let { if (onClick != null) it.clickable(role = Role.Button, onClick = onClick) else it }.heightIn(min = 56.dp),
    ) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(edge ?: MaterialTheme.colorScheme.outline))
        Column(Modifier.weight(1f).padding(Spacing.l)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionHeader("Next up", Modifier.weight(1f))
                if (running) {
                    Text(
                        "Now", style = Type.label, color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.clip(Shapes.chip).background(MaterialTheme.colorScheme.secondaryContainer).padding(horizontal = Spacing.s, vertical = 2.dp),
                    )
                }
            }
            Spacer(Modifier.height(Spacing.xs))
            if (title != null) {
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (line != null) Text(line, style = Type.body, color = dim, maxLines = 2, overflow = TextOverflow.Ellipsis)
            } else {
                Text("Free for the rest of the day", style = MaterialTheme.typography.titleMedium)
                if (freeLine != null) Text(freeLine, style = Type.body, color = dim, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/**
 * Today's slim Next up strip (64dp): 3dp calendar-colour edge, "Next up" caption, "Shift · 15:00–19:00 · in 55 min" on one line.
 * [onClick] opens the Calendar tab. [running] adds a "Now" chip.
 */
@Composable
fun NextUpStrip(
    caption: String, title: String, line: String?, edge: Color?, running: Boolean, onClick: (() -> Unit)?, modifier: Modifier = Modifier,
) {
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier.fillMaxWidth().height(IntrinsicSize.Min).clip(Shapes.tile).background(MaterialTheme.colorScheme.surfaceContainer)
            .let { if (onClick != null) it.clickable(role = Role.Button, onClick = onClick) else it }.heightIn(min = 64.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(edge ?: MaterialTheme.colorScheme.outline))
        Column(Modifier.weight(1f).padding(horizontal = Spacing.l, vertical = Spacing.s)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(caption, style = Type.metricTitle, color = dim)
                if (running) {
                    Spacer(Modifier.width(Spacing.s))
                    Text(
                        "Now", style = Type.chip, color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.clip(Shapes.pill).background(MaterialTheme.colorScheme.secondaryContainer).padding(horizontal = 8.dp, vertical = 1.dp),
                    )
                }
            }
            Text(listOfNotNull(title, line).joinToString(" · "), style = Type.body, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (onClick != null) Text("›", style = MaterialTheme.typography.titleMedium, color = dim, modifier = Modifier.padding(end = Spacing.l))
    }
}

/**
 * Water as one slim strip: "Water 1.25 of 2.5 L", a neutral-ink bar (never tier colour, never red), the pace words and the
 * quick-add buttons. [loggedMl] shows "Logged 250 ml" with Undo for 10 s.
 */
@Composable
fun WaterCard(
    ml: Int, goalMl: Int, extraMl: Int, pace: Pace, glassMl: Int, loggedMl: Int?,
    onAdd: (Int) -> Unit, onUndo: () -> Unit, remindersOn: Boolean, onEnableReminders: (() -> Unit)?, modifier: Modifier = Modifier,
) {
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    Column(modifier.fillMaxWidth().clip(Shapes.tile).background(MaterialTheme.colorScheme.surfaceContainer).heightIn(min = 64.dp).padding(start = Spacing.l, end = Spacing.s, top = Spacing.s, bottom = Spacing.s)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = Spacing.s)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("Water", style = Type.metricTitle, color = dim)
                    Spacer(Modifier.width(Spacing.s))
                    Text(Copy.waterLine(ml, goalMl), style = Type.body, maxLines = 1)
                }
                Spacer(Modifier.height(Spacing.xs))
                val frac = if (goalMl > 0) (ml.toFloat() / goalMl).coerceIn(0f, 1f) else 0f
                Box(Modifier.fillMaxWidth().height(4.dp).clip(Shapes.pill).background(MaterialTheme.colorScheme.outlineVariant)) {
                    if (frac > 0f) Box(Modifier.fillMaxWidth(frac).fillMaxHeight().background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)))
                }
                Text(
                    if (loggedMl != null) "Logged $loggedMl ml" else Copy.waterPace(pace, extraMl),
                    style = Type.unitS, color = dim, maxLines = 1, modifier = Modifier.padding(top = 2.dp),
                )
            }
            if (loggedMl != null) {
                TextButton(onClick = onUndo, modifier = Modifier.heightIn(min = Spacing.minTouch)) { Text("Undo", style = Type.label) }
            } else {
                TextButton(onClick = { onAdd(glassMl) }, modifier = Modifier.heightIn(min = Spacing.minTouch), contentPadding = PaddingValues(horizontal = 10.dp)) { Text("+ glass", style = Type.label) }
                TextButton(onClick = { onAdd(500) }, modifier = Modifier.heightIn(min = Spacing.minTouch), contentPadding = PaddingValues(horizontal = 10.dp)) { Text("+500", style = Type.label) }
            }
        }
        if (!remindersOn && onEnableReminders != null && loggedMl == null) {
            TextButton(onClick = onEnableReminders, modifier = Modifier.heightIn(min = Spacing.minTouch)) { Text("Remind me", style = Type.label, color = dim) }
        }
    }
}
