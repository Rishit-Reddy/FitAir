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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.fitair.app.core.Format
import com.fitair.app.data.dao.Pace
import com.fitair.app.ui.copy.Copy
import com.fitair.app.ui.copy.Verdict
import com.fitair.app.ui.sleep.StageMinutes
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
 * Readiness: the number, the verdict beside it (first thing you read), "out of 100 · compared with your own normal",
 * then the main driver in words. No progress bar. Tap opens the breakdown.
 */
@Composable
fun ReadinessHero(score: Int?, verdict: Verdict, driver: String?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    val dot = if (verdict.tone == Tone.Neutral) MaterialTheme.colorScheme.outline else toneColor(verdict.tone)
    Column(modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(vertical = Spacing.xs)) {
        SectionHeader("Readiness")
        Row(verticalAlignment = Alignment.Bottom) {
            Text(score?.toString() ?: Format.DASH, style = MaterialTheme.typography.displayLarge)
            Spacer(Modifier.width(Spacing.m))
            Column(Modifier.padding(bottom = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (score != null) { StatusDot(dot); Spacer(Modifier.width(6.dp)) }
                    Text(verdict.headline, style = MaterialTheme.typography.titleMedium)
                }
                if (verdict.detail != null) Text(verdict.detail, style = Type.bodySmall, color = dim)
            }
        }
        if (score != null) Text(Copy.OUT_OF_100, style = Type.bodySmall, color = dim)
        if (driver != null) {
            Spacer(Modifier.height(Spacing.s))
            Text(driver, style = Type.body)
        }
    }
}

/** Day mode: "74 · Well recovered" and, when known, "heart rate now 84 · resting 56". One tap expands to the full block. */
@Composable
fun ReadinessCompact(score: Int?, verdict: Verdict, heartLine: String?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    val dot = if (verdict.tone == Tone.Neutral) MaterialTheme.colorScheme.outline else toneColor(verdict.tone)
    Row(
        modifier.fillMaxWidth().clip(Shapes.card).background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(role = Role.Button, onClick = onClick).heightIn(min = 56.dp).padding(horizontal = Spacing.l, vertical = Spacing.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            SectionHeader("Readiness")
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (score != null) { StatusDot(dot); Spacer(Modifier.width(Spacing.s)) }
                Text(verdict.headline, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f, fill = false))
                if (score != null) Text("  $score", style = MaterialTheme.typography.titleMedium, color = dim)
            }
            if (heartLine != null) Text(heartLine, style = Type.bodySmall, color = dim)
        }
        Text("›", style = MaterialTheme.typography.titleMedium, color = dim)
    }
}

/** Vital tile: label, plain verdict, then the number against your usual. A coloured ▲/▼ only when it differs from your normal. */
@Composable
fun VitalTile(label: String, verdict: Verdict, modifier: Modifier = Modifier) {
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    Column(modifier.clip(Shapes.card).background(MaterialTheme.colorScheme.surfaceVariant).padding(Spacing.m)) {
        SectionHeader(label)
        Spacer(Modifier.height(Spacing.xs))
        Text(verdict.headline, style = MaterialTheme.typography.titleMedium, minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis)
        val d = verdict.detail
        if (d == null) Text(" ", style = Type.bodySmall)
        else Text(
            buildAnnotatedString {
                if (verdict.glyph != null) { withStyle(SpanStyle(color = toneColor(verdict.tone))) { append(verdict.glyph) }; append(" ") }
                append(d)
            },
            style = Type.bodySmall, color = dim, minLines = 2, maxLines = 2,
        )
    }
}

/**
 * Today's one Sleep card: duration, verdict ("Good night" first, score second), window with the need phrase, a bigger stage
 * bar with minutes inside the segments and a compact legend, and the 7-day debt. The whole card opens Sleep.
 */
@Composable
fun SleepCard(
    duration: String, window: String?, score: Double?, needDiffMin: Double?, debt: String?, stages: StageMinutes?,
    onClick: () -> Unit, modifier: Modifier = Modifier,
) {
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier.fillMaxWidth().clip(Shapes.card).background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(role = Role.Button, onClick = onClick).heightIn(min = 56.dp).padding(Spacing.l),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionHeader("Sleep · last night", Modifier.weight(1f))
            Text("›", style = MaterialTheme.typography.titleMedium, color = dim)
        }
        Spacer(Modifier.height(Spacing.xs))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(duration, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
            val v = Copy.sleepNight(score)
            if (v.headline.isNotEmpty()) {
                StatusDot(toneColor(v.tone)); Spacer(Modifier.width(Spacing.s))
                Text(v.headline, style = MaterialTheme.typography.bodyLarge)
                if (score != null) Text("  ${Math.round(score)}", style = MaterialTheme.typography.bodyLarge, color = dim)
            }
        }
        val diff = needDiffMin?.let { Math.round(it) }
        val tier = Tiers.duration(needDiffMin, 0.0)
        val glyph = when { diff == null -> null; diff <= -15 -> "▼"; diff >= 15 -> "▲"; else -> null }
        val needText = Copy.needDiff(diff)
        if (window != null || needText != null) {
            Text(
                buildAnnotatedString {
                    if (window != null) append(window)
                    if (window != null && needText != null) append(" · ")
                    if (glyph != null) { withStyle(SpanStyle(color = toneColor(tier))) { append(glyph) }; append(" ") }
                    if (needText != null) append(needText)
                },
                style = Type.bodySmall, color = dim,
            )
        }
        if (stages != null) {
            Spacer(Modifier.height(Spacing.m))
            StageBar(stages.awake, stages.light, stages.rem, stages.deep, height = 28.dp, legend = false, compactLegend = true)
        }
        if (debt != null) {
            Spacer(Modifier.height(Spacing.s))
            Text("Short on sleep · $debt over 7 nights", style = Type.bodySmall, color = dim)
        }
    }
}

/** Collapsed sleep: "Slept 7h 12m · Good night ›". [onClick] null for the quiet waiting / none states (no chevron). */
@Composable
fun SleepLine(text: String, onClick: (() -> Unit)?, modifier: Modifier = Modifier) {
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier.fillMaxWidth().clip(Shapes.card).background(MaterialTheme.colorScheme.surfaceVariant)
            .let { if (onClick != null) it.clickable(role = Role.Button, onClick = onClick) else it }
            .heightIn(min = 56.dp).padding(horizontal = Spacing.l, vertical = Spacing.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            SectionHeader("Sleep")
            Text(text, style = if (onClick != null) Type.body else Type.bodySmall, color = if (onClick != null) MaterialTheme.colorScheme.onSurface else dim)
        }
        if (onClick != null) Text("›", style = MaterialTheme.typography.titleMedium, color = dim)
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

/** "Load so far" card: the verdict for this time of day, then the number. Opens the Load screen. */
@Composable
fun LoadCard(verdict: Verdict, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier.fillMaxWidth().clip(Shapes.card).background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(role = Role.Button, onClick = onClick).heightIn(min = 56.dp).padding(horizontal = Spacing.l, vertical = Spacing.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            SectionHeader("Load so far")
            Text(verdict.headline, style = MaterialTheme.typography.titleMedium)
            if (verdict.detail != null) Text(verdict.detail, style = Type.bodySmall, color = dim)
        }
        Text("›", style = MaterialTheme.typography.titleMedium, color = dim)
    }
}

/**
 * Water: "1.25 of 2.5 L", a neutral-ink bar (never tier colour, never red), the pace words and quick-add buttons.
 * [logged] shows "Logged 250 ml · Undo" for 10 s.
 */
@Composable
fun WaterCard(
    ml: Int, goalMl: Int, extraMl: Int, pace: Pace, glassMl: Int, loggedMl: Int?,
    onAdd: (Int) -> Unit, onUndo: () -> Unit, remindersOn: Boolean, onEnableReminders: (() -> Unit)?, modifier: Modifier = Modifier,
) {
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    val ink = MaterialTheme.colorScheme.onSurfaceVariant
    Column(modifier.fillMaxWidth().clip(Shapes.card).background(MaterialTheme.colorScheme.surfaceVariant).padding(Spacing.l)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionHeader("Water", Modifier.weight(1f))
            Text(Copy.waterLine(ml, goalMl), style = Type.body)
        }
        Spacer(Modifier.height(Spacing.s))
        val frac = if (goalMl > 0) (ml.toFloat() / goalMl).coerceIn(0f, 1f) else 0f
        Box(Modifier.fillMaxWidth().height(4.dp).clip(Shapes.chip).background(MaterialTheme.colorScheme.outlineVariant)) {
            if (frac > 0f) Box(Modifier.fillMaxWidth(frac).fillMaxHeight().background(ink))
        }
        Spacer(Modifier.height(Spacing.xs))
        Text(
            if (loggedMl != null) "Logged $loggedMl ml" else Copy.waterPace(pace, extraMl),
            style = Type.bodySmall, color = dim,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (loggedMl != null) {
                TextButton(onClick = onUndo) { Text("Undo", style = Type.label) }
            } else {
                TextButton(onClick = { onAdd(glassMl) }) { Text("+ glass", style = Type.label) }
                TextButton(onClick = { onAdd(500) }) { Text("+500", style = Type.label) }
            }
            Spacer(Modifier.weight(1f))
            if (!remindersOn && onEnableReminders != null && loggedMl == null) {
                TextButton(onClick = onEnableReminders) { Text("Remind me", style = Type.label, color = dim) }
            }
        }
    }
}

/** One quiet line: caption and text (wind-down, "Today 2.3 L"). */
@Composable
fun QuietLine(caption: String, text: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(vertical = Spacing.xs)) {
        SectionHeader(caption)
        Text(text, style = Type.body)
    }
}

/** One full-width tappable summary row: caption, a single line of facts and a chevron. */
@Composable
fun SummaryRow(caption: String, line: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().clip(Shapes.card).background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(role = Role.Button, onClick = onClick).heightIn(min = 56.dp).padding(horizontal = Spacing.l, vertical = Spacing.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            SectionHeader(caption)
            Text(line, style = Type.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text("›", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
