package com.fitair.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.fitair.app.core.Format
import com.fitair.app.ui.theme.LocalStatusColors
import com.fitair.app.ui.theme.Shapes
import com.fitair.app.ui.theme.Spacing
import com.fitair.app.ui.theme.Type

/** "synced 12 min ago" / "stale 3 h" with a status dot. */
@Composable
fun FreshnessPill(text: String, stale: Boolean, modifier: Modifier = Modifier) {
    val st = LocalStatusColors.current
    Row(
        modifier.clip(Shapes.chip).background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = Spacing.m, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(if (stale) st.caution else st.good)
        Spacer(Modifier.width(6.dp))
        Text(text, style = Type.label, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Big readiness number, band word and the one main driver. Tap opens the breakdown. */
@Composable
fun ReadinessHero(score: Int?, driver: String?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val st = LocalStatusColors.current
    val dot = when { score == null -> MaterialTheme.colorScheme.outline; score >= 70 -> st.good; score >= 50 -> st.caution; else -> st.alert }
    Column(modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(vertical = Spacing.xs)) {
        SectionHeader("Readiness")
        Row(verticalAlignment = Alignment.Bottom) {
            Text(score?.toString() ?: Format.DASH, style = MaterialTheme.typography.displayLarge)
            val band = Format.band(score)
            if (band != null) {
                Spacer(Modifier.width(Spacing.m))
                Row(Modifier.padding(bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(dot)
                    Spacer(Modifier.width(6.dp))
                    Text(band, style = MaterialTheme.typography.titleMedium)
                }
            }
        }
        if (driver != null) Text(driver, style = Type.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Vital tile: label, value + unit, delta line. */
@Composable
fun MetricTile(label: String, value: String, unit: String?, delta: String?, tone: Tone, modifier: Modifier = Modifier) {
    Column(modifier.clip(Shapes.card).background(MaterialTheme.colorScheme.surfaceVariant).padding(Spacing.l)) {
        SectionHeader(label)
        Spacer(Modifier.height(Spacing.xs))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, style = MaterialTheme.typography.headlineMedium)
            if (unit != null) {
                Spacer(Modifier.width(Spacing.xs))
                Text(unit, style = Type.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 5.dp))
            }
        }
        Text(delta ?: " ", style = Type.bodySmall, color = toneColor(tone))
    }
}
