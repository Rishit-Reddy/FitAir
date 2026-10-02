package com.fitair.app.ui.today

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.fitair.app.core.Format
import com.fitair.app.data.metrics.MetricId
import com.fitair.app.data.metrics.MetricSnapshot
import com.fitair.app.ui.components.ScoreBar
import com.fitair.app.ui.components.StageBar
import com.fitair.app.ui.components.StatusChip
import com.fitair.app.ui.components.Tiers
import com.fitair.app.ui.copy.Copy
import com.fitair.app.ui.copy.CopyMorning
import com.fitair.app.ui.metrics.MetricCards
import com.fitair.app.ui.sleep.VerdictScore
import com.fitair.app.ui.theme.Shapes
import com.fitair.app.ui.theme.Spacing
import com.fitair.app.ui.theme.Type

/**
 * Morning recap: how last night went (duration, verdict and score, stages) and readiness, in the Sleep page style.
 * Shown instead of the five cards until "Seen?" is confirmed or the morning window ends.
 */
@Composable
fun MorningRecap(snap: MetricSnapshot?, onSleep: () -> Unit, onReadiness: () -> Unit, onSeen: () -> Unit) {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val border = if (dark) Modifier else Modifier.border(1.dp, Color(0xFFE7E7E4), Shapes.hero)
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    val surface = MaterialTheme.colorScheme.surfaceContainer
    val night = snap?.nights?.lastOrNull()
    Column(Modifier.fillMaxWidth().then(border).clip(Shapes.hero).background(surface).padding(Spacing.heroPad)) {
        Text(CopyMorning.CAPTION, style = Type.metricTitle, color = dim)
        if (night == null) {
            Spacer(Modifier.height(Spacing.s))
            Text(CopyMorning.WAITING, style = Type.body, color = dim)
        } else {
            Column(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onSleep)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(Format.duration(Math.round(night.asleepMin)), style = Type.valueL, modifier = Modifier.weight(1f))
                    VerdictScore(night.score)
                }
                Spacer(Modifier.height(Spacing.s))
                ScoreBar(night.score, Tiers.sleepScore(night.score))
                Copy.needDiff(night.needMin?.let { Math.round(night.asleepMin - it) })?.let {
                    Text(it, style = Type.bodySmall, color = dim, modifier = Modifier.padding(top = Spacing.s))
                }
                snap.lastStages?.takeIf { !it.isEmpty }?.let {
                    Spacer(Modifier.height(Spacing.l))
                    StageBar(it.awake, it.light, it.rem, it.deep, height = 28.dp, legend = true)
                }
            }
        }
        val ready = snap?.let { MetricCards.card(MetricId.Readiness, it) }
        if (ready?.value != null) {
            Spacer(Modifier.height(Spacing.l))
            Row(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onReadiness), verticalAlignment = Alignment.CenterVertically) {
                Text("Readiness", style = Type.metricTitle, color = dim)
                Spacer(Modifier.width(Spacing.m))
                Text(ready.value, style = Type.valueS)
                Spacer(Modifier.weight(1f))
                ready.chip?.let { StatusChip(it, surface) }
            }
        }
        TextButton(onClick = onSeen, modifier = Modifier.padding(top = Spacing.s).heightIn(min = Spacing.minTouch)) { Text(CopyMorning.SEEN, style = Type.label) }
    }
}
