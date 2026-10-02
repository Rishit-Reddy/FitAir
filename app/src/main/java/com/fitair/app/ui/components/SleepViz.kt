package com.fitair.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.fitair.app.ui.theme.LocalStageColors
import com.fitair.app.ui.theme.Shapes
import com.fitair.app.ui.theme.Spacing
import com.fitair.app.ui.theme.Type
import kotlin.math.roundToInt

private fun hm(min: Double): String {
    val m = Math.round(min)
    return "${m / 60}h %02dm".format(m % 60)
}

/**
 * Stacked sleep stage bar in the stage colours, order Awake, Light, REM, Deep, with 1dp gaps in the
 * card colour. With [legend] it adds 4 equal columns: dot, name, "1h 05m · 23%".
 */
@Composable
fun StageBar(
    awake: Double, light: Double, rem: Double, deep: Double,
    height: Dp = 12.dp, legend: Boolean = true, modifier: Modifier = Modifier,
) {
    val sc = LocalStageColors.current
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    val gap = MaterialTheme.colorScheme.surfaceVariant
    val names = listOf("Awake", "Light", "REM", "Deep")
    val mins = listOf(awake, light, rem, deep)
    val colors = listOf(sc.awake, sc.light, sc.rem, sc.deep)
    val total = mins.sum()
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().height(height).clip(Shapes.chip), horizontalArrangement = Arrangement.spacedBy(1.dp)) {
            if (total <= 0) Box(Modifier.weight(1f).fillMaxHeight().background(MaterialTheme.colorScheme.outlineVariant))
            else mins.forEachIndexed { i, m ->
                if (m > 0) Box(Modifier.weight(m.toFloat()).fillMaxHeight().background(colors[i]))
            }
        }
        if (legend) {
            Spacer(Modifier.height(Spacing.s))
            Row(Modifier.fillMaxWidth()) {
                names.forEachIndexed { i, name ->
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(8.dp).clip(CircleShape).background(colors[i]))
                            Spacer(Modifier.width(6.dp))
                            Text(name, style = Type.bodySmall, color = dim, maxLines = 1)
                        }
                        val pct = if (total > 0) (mins[i] / total * 100).roundToInt() else 0
                        Text("${hm(mins[i])} · $pct%", style = Type.bodySmall, maxLines = 1)
                    }
                }
            }
        }
    }
}

/** 4dp bar: track outlineVariant, fill = score/100 in the tier colour. Draws only the track when score is null. */
@Composable
fun ScoreBar(score: Double?, tone: Tone, modifier: Modifier = Modifier) {
    val frac = ((score ?: 0.0) / 100.0).coerceIn(0.0, 1.0).toFloat()
    val fill: Color = toneColor(tone)
    Box(modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(MaterialTheme.colorScheme.outlineVariant)) {
        if (score != null && frac > 0f) Box(Modifier.fillMaxWidth(frac).fillMaxHeight().background(fill))
    }
}
