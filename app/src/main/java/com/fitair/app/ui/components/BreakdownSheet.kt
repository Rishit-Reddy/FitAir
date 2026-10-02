package com.fitair.app.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fitair.app.analytics.ReadinessView
import com.fitair.app.core.Format
import com.fitair.app.ui.theme.Shapes
import com.fitair.app.ui.theme.Spacing
import com.fitair.app.ui.theme.Type

/** How the readiness number was made: up to 5 components, weights that add up to 100 %, and the version. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BreakdownSheet(view: ReadinessView, onDismiss: () -> Unit) {
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = Shapes.sheet,
        containerColor = MaterialTheme.colorScheme.background,
        tonalElevation = 0.dp,
    ) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(start = Spacing.gutter, end = Spacing.gutter, bottom = Spacing.xxl)) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.Bottom) {
                Text(view.score?.toString() ?: Format.DASH, style = Type.headline)
                Spacer(Modifier.width(Spacing.m))
                Text(Format.band(view.score) ?: "", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).padding(bottom = 3.dp))
                Text("Readiness v${view.version}", style = Type.label, color = dim, modifier = Modifier.padding(bottom = 4.dp))
            }
            Spacer(Modifier.height(Spacing.l))
            if (view.drivers.isEmpty()) {
                Text(view.note ?: "No breakdown available for this day.", style = Type.bodySmall, color = dim)
            } else {
                SectionHeader("What went in")
                Spacer(Modifier.height(Spacing.s))
                Hairline()
                view.drivers.forEach { d ->
                    Row(Modifier.fillMaxWidth().padding(vertical = Spacing.m), verticalAlignment = androidx.compose.ui.Alignment.Top) {
                        Column(Modifier.weight(1f)) {
                            Text(d.label, style = MaterialTheme.typography.bodyMedium)
                            Text(d.text, style = Type.bodySmall, color = dim)
                        }
                        Spacer(Modifier.width(Spacing.m))
                        Column(horizontalAlignment = androidx.compose.ui.Alignment.End) {
                            Text("${Math.round(d.score)}", style = MaterialTheme.typography.titleMedium)
                            Text("${d.weightPct} %", style = Type.bodySmall, color = dim)
                        }
                    }
                    Hairline()
                }
                Text("Weights add up to ${view.drivers.sumOf { it.weightPct }} %.", style = Type.bodySmall, color = dim, modifier = Modifier.padding(top = Spacing.s))
            }
            if (view.missing.isNotEmpty()) {
                Spacer(Modifier.height(Spacing.l))
                SectionHeader("Not included")
                view.missing.forEach { (k, why) ->
                    Text("$k: $why", style = Type.bodySmall, color = dim, modifier = Modifier.padding(top = Spacing.xs))
                }
            }
        }
    }
}
