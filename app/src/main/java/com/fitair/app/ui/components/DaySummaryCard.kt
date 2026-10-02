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
import androidx.compose.ui.semantics.Role
import com.fitair.app.coach.DayFacts
import com.fitair.app.coach.SummaryText
import com.fitair.app.core.Format
import com.fitair.app.ui.copy.Copy
import com.fitair.app.ui.theme.Shapes
import com.fitair.app.ui.theme.Spacing
import com.fitair.app.ui.theme.Type
import java.util.Locale

/** The fact cells of the card as (caption, value) pairs. Steps appear here and nowhere else on Today. */
fun dayFactCells(f: DayFacts): List<Pair<String, String>> = buildList {
    add("Steps" to Format.compactCount(f.steps))
    add("Distance" to String.format(Locale.US, "%.1f km", f.distanceM / 1000.0))
    add("Cardio load" to (Math.round(f.cardio).toString() + if (f.zoneMin > 0) " · ${f.zoneMin} min hard" else ""))
    add("Water" to Copy.waterLine(f.waterMl, f.waterGoalMl))
}

/** "resting 56 · avg 74 · max 152" with only the parts that exist; null when none. */
fun heartLine(f: DayFacts): String? = listOfNotNull(
    f.rhr?.let { "resting ${Math.round(it)}" }, f.hrAvg?.let { "avg ${Math.round(it)}" }, f.hrMax?.let { "max ${Math.round(it)}" },
).takeIf { it.isNotEmpty() }?.joinToString(" · ")

/**
 * Evening "TODAY SO FAR": a two-column fact grid, then 2-3 plain sentences (model text validated by the data layer, or the rules
 * template) and a quiet Regenerate. Tapping the card opens the Load screen. Loading shows a fixed-height dim line.
 */
@Composable
fun DaySummaryCard(
    facts: DayFacts?, summary: SummaryText?, loading: Boolean, onOpen: () -> Unit, onRegenerate: () -> Unit, modifier: Modifier = Modifier,
) {
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier.fillMaxWidth().clip(Shapes.card).background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(role = Role.Button, onClick = onOpen).padding(Spacing.l),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionHeader("Today so far", Modifier.weight(1f))
            Text("›", style = MaterialTheme.typography.titleMedium, color = dim)
        }
        Spacer(Modifier.height(Spacing.s))
        if (facts == null || !facts.hasData) {
            Text("Not enough data from today yet", style = Type.body, color = dim)
            return@Column
        }
        dayFactCells(facts).chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth().padding(vertical = Spacing.xs)) {
                row.forEach { (cap, v) ->
                    Column(Modifier.weight(1f)) {
                        Text(cap.uppercase(), style = Type.caption, color = dim)
                        Text(v, style = Type.body)
                    }
                }
            }
        }
        heartLine(facts)?.let {
            Column(Modifier.fillMaxWidth().padding(vertical = Spacing.xs)) {
                Text("HEART RATE", style = Type.caption, color = dim)
                Text(it, style = Type.body)
            }
        }
        if (facts.workouts.isNotEmpty()) {
            Column(Modifier.fillMaxWidth().padding(vertical = Spacing.xs)) {
                Text("WORKOUTS", style = Type.caption, color = dim)
                facts.workouts.forEach { Text(it, style = Type.body) }
            }
        }
        if (facts.partial) Text("Partial day: the band had gaps today.", style = Type.bodySmall, color = dim, modifier = Modifier.padding(top = Spacing.xs))
        Spacer(Modifier.height(Spacing.m))
        Hairline()
        Spacer(Modifier.height(Spacing.m))
        if (summary == null || (loading && summary.text.isBlank())) {
            Text("Writing summary…", style = Type.body, color = dim, minLines = 3)
        } else {
            Text(summary.text, style = Type.body, minLines = 3)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (summary?.source == "template") Text("rules", style = Type.caption, color = dim)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onRegenerate, enabled = !loading) { Text("Regenerate", style = Type.label, color = dim) }
        }
    }
}
