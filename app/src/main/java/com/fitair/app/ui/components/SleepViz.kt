package com.fitair.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import com.fitair.app.core.Format
import com.fitair.app.ui.theme.LocalStageColors
import com.fitair.app.ui.theme.Spacing
import com.fitair.app.ui.theme.Type
import kotlin.math.roundToInt

private val STAGE_NAMES = listOf("Awake", "Light", "REM", "Deep")

/**
 * Stacked sleep stage bar in the stage colours, order Awake, Light, REM, Deep, with 1dp gaps and 6dp outer corners.
 * From 24dp tall the minutes are drawn inside each segment ([StageLabel.fit]: long form, else short form, else nothing;
 * ink chosen per colour for contrast). A hidden label never loses information: with [legend] the 4-column legend
 * (dot, name, "1h 05m · 23%") is shown, with [compactLegend] a one-line dot + name + minutes legend.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StageBar(
    awake: Double, light: Double, rem: Double, deep: Double,
    height: Dp = 12.dp, legend: Boolean = true, modifier: Modifier = Modifier, compactLegend: Boolean = false,
) {
    val sc = LocalStageColors.current
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    val track = MaterialTheme.colorScheme.outlineVariant
    val mins = listOf(awake, light, rem, deep)
    val colors = listOf(sc.awake, sc.light, sc.rem, sc.deep)
    val total = mins.sum()
    val measurer = rememberTextMeasurer()
    val labelStyle = Type.label.copy(fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.sp)
    val labels = height >= 24.dp
    val desc = "Sleep stages: " + STAGE_NAMES.indices.joinToString(", ") { "${STAGE_NAMES[it]} ${Format.hm(Math.round(mins[it]))}" }
    Column(modifier.fillMaxWidth()) {
        Canvas(Modifier.fillMaxWidth().height(height).semantics { contentDescription = desc }) {
            val corner = CornerRadius(6.dp.toPx())
            val clip = Path().apply { addRoundRect(RoundRect(0f, 0f, size.width, size.height, corner)) }
            clipPath(clip) {
                if (total <= 0) { drawRect(track); return@clipPath }
                val gap = 1.dp.toPx()
                val shown = mins.indices.filter { mins[it] > 0 }
                val avail = size.width - gap * (shown.size - 1)
                var x = 0f
                for (i in shown) {
                    val w = (mins[i] / total * avail).toFloat()
                    drawRect(colors[i], Offset(x, 0f), Size(w, size.height))
                    if (labels) {
                        val m = Math.round(mins[i])
                        val longT = measurer.measure(Format.stageLong(m), labelStyle)
                        val shortT = measurer.measure(Format.stageShort(m), labelStyle)
                        val pick = when (StageLabel.fit(w, longT.size.width.toFloat(), shortT.size.width.toFloat(), StageLabel.PAD_DP * density)) {
                            StageLabel.Fit.Long -> longT
                            StageLabel.Fit.Short -> shortT
                            StageLabel.Fit.None -> null
                        }
                        if (pick != null) {
                            val ink = Color(StageLabel.inkFor(colors[i].toArgb()))
                            drawText(pick, ink, Offset(x + (w - pick.size.width) / 2f, (size.height - pick.size.height) / 2f))
                        }
                    }
                    x += w + gap
                }
            }
        }
        if (legend) {
            Spacer(Modifier.height(Spacing.s))
            Row(Modifier.fillMaxWidth()) {
                STAGE_NAMES.forEachIndexed { i, name ->
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(8.dp).clip(CircleShape).background(colors[i]))
                            Spacer(Modifier.width(6.dp))
                            Text(name, style = Type.bodySmall, color = dim, maxLines = 1)
                        }
                        val pct = if (total > 0) (mins[i] / total * 100).roundToInt() else 0
                        Text("${Format.hm(Math.round(mins[i]))} · $pct%", style = Type.bodySmall)
                    }
                }
            }
        } else if (compactLegend) {
            Spacer(Modifier.height(Spacing.s))
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.m), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                STAGE_NAMES.forEachIndexed { i, name ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(colors[i]))
                        Spacer(Modifier.width(6.dp))
                        Text("$name ${Format.hm(Math.round(mins[i]))}", style = Type.bodySmall, color = dim)
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
