package com.fitair.app.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.fitair.app.coach.DayFacts
import com.fitair.app.core.Format
import com.fitair.app.ui.copy.Copy
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
 * The fact grid of the Details sheet: a two-column grid of today's numbers, heart rate, workouts and a partial-day note.
 * No summary text (that is the three bullets on Today).
 */
@Composable
fun DayFactsGrid(facts: DayFacts?, modifier: Modifier = Modifier) {
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    Column(modifier.fillMaxWidth()) {
        if (facts == null || !facts.hasData) {
            Text("Not enough data from today yet", style = Type.body, color = dim)
            return@Column
        }
        dayFactCells(facts).chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth().padding(vertical = Spacing.xs)) {
                row.forEach { (cap, v) ->
                    Column(Modifier.weight(1f)) {
                        Text(cap, style = Type.metricTitle, color = dim)
                        Text(v, style = Type.body)
                    }
                }
            }
        }
        heartLine(facts)?.let {
            Column(Modifier.fillMaxWidth().padding(vertical = Spacing.xs)) {
                Text("Heart rate", style = Type.metricTitle, color = dim)
                Text(it, style = Type.body)
            }
        }
        if (facts.workouts.isNotEmpty()) {
            Column(Modifier.fillMaxWidth().padding(vertical = Spacing.xs)) {
                Text("Workouts", style = Type.metricTitle, color = dim)
                facts.workouts.forEach { Text(it, style = Type.body) }
            }
        }
        if (facts.partial) Text("Partial day: the band had gaps today.", style = Type.bodySmall, color = dim, modifier = Modifier.padding(top = Spacing.xs))
    }
}
