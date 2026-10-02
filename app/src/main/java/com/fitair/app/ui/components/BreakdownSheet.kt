package com.fitair.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.fitair.app.analytics.ReadinessView
import com.fitair.app.core.Format
import com.fitair.app.ui.copy.Copy
import com.fitair.app.ui.theme.Shapes
import com.fitair.app.ui.theme.Spacing
import com.fitair.app.ui.theme.Type

/**
 * What made today's readiness, in words: the verdict first, then one plain line per driver (tap one for "What is this?").
 * The numbers, weights and version sit behind a "Details" expander.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BreakdownSheet(view: ReadinessView, onDismiss: () -> Unit, onTrend: (() -> Unit)? = null) {
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    var details by rememberSaveable { mutableStateOf(false) }
    var explain by rememberSaveable { mutableStateOf<String?>(null) }
    val verdict = Copy.readiness(view.score)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = Shapes.sheet,
        containerColor = MaterialTheme.colorScheme.background,
        tonalElevation = 0.dp,
    ) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(start = Spacing.gutter, end = Spacing.gutter, bottom = Spacing.xxl)) {
            Text(verdict.headline, style = MaterialTheme.typography.headlineMedium)
            if (verdict.detail != null && view.score != null) Text(verdict.detail, style = Type.body, color = dim)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (view.score != null) "${view.score} · ${Copy.OUT_OF_100}" else (view.note ?: ""),
                    style = Type.bodySmall, color = dim, modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { explain = "readiness" }) { Text("What is this?", style = Type.label) }
            }
            Spacer(Modifier.height(Spacing.m))
            if (view.drivers.isEmpty()) {
                Text(view.note ?: "No breakdown available for this day.", style = Type.bodySmall, color = dim)
            } else {
                SectionHeader("What went in")
                Spacer(Modifier.height(Spacing.s))
                Hairline()
                view.drivers.forEach { d ->
                    val v = Copy.driver(d.key, d.score, d.text)
                    Row(
                        Modifier.fillMaxWidth().clickable(role = Role.Button) { explain = d.key }.heightIn(min = 56.dp).padding(vertical = Spacing.m),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (v.tone != Tone.Neutral) { StatusDot(toneColor(v.tone)); Spacer(Modifier.width(Spacing.m)) }
                        Text(v.headline, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        Text("›", style = MaterialTheme.typography.titleMedium, color = dim)
                    }
                    Hairline()
                }
            }
            Spacer(Modifier.height(Spacing.s))
            TextButton(onClick = { details = !details }) { Text(if (details) "Hide details" else "Details", style = Type.label) }
            if (details) {
                view.drivers.forEach { d ->
                    Row(Modifier.fillMaxWidth().padding(vertical = Spacing.s), verticalAlignment = Alignment.Top) {
                        Column(Modifier.weight(1f)) {
                            Text(d.label, style = MaterialTheme.typography.bodyMedium)
                            Text(d.text, style = Type.bodySmall, color = dim)
                        }
                        Spacer(Modifier.width(Spacing.m))
                        Column(horizontalAlignment = Alignment.End) {
                            Text("${Math.round(d.score)}", style = MaterialTheme.typography.titleMedium)
                            Text("${d.weightPct} %", style = Type.bodySmall, color = dim)
                        }
                    }
                    Hairline()
                }
                if (view.drivers.isNotEmpty())
                    Text("Weights add up to ${view.drivers.sumOf { it.weightPct }} %. Readiness v${view.version}.", style = Type.bodySmall, color = dim, modifier = Modifier.padding(top = Spacing.s))
                if (view.missing.isNotEmpty()) {
                    Spacer(Modifier.height(Spacing.l))
                    SectionHeader("Not included")
                    view.missing.forEach { (k, why) ->
                        Text("$k: $why", style = Type.bodySmall, color = dim, modifier = Modifier.padding(top = Spacing.xs))
                    }
                }
            }
            if (onTrend != null) {
                Spacer(Modifier.height(Spacing.l))
                TextButton(onClick = onTrend) { Text("30-day trend ›", style = Type.label) }
            }
        }
    }
    explain?.let { ExplainSheet(it, onDismiss = { explain = null }) }
}
