package com.fitair.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fitair.app.ui.components.charts.EmptyBaseline
import com.fitair.app.ui.components.charts.Mini
import com.fitair.app.ui.components.charts.MiniChart
import com.fitair.app.ui.components.charts.WeekdayLabels
import com.fitair.app.ui.components.charts.DayAxisLabels
import com.fitair.app.ui.theme.ChipPalette
import com.fitair.app.ui.theme.Shapes
import com.fitair.app.ui.theme.Spacing
import com.fitair.app.ui.theme.Type

@Composable
private fun isDarkTheme() = MaterialTheme.colorScheme.background.luminance() < 0.5f

/**
 * The status chip: pill, 24dp high, 6dp dot + 6dp gap (status tones only), container tinted with the status colour, text in
 * neutral ink so contrast never depends on the status colour. [surface] is what the chip sits on.
 */
@Composable
fun StatusChip(chip: Chip, surface: Color, modifier: Modifier = Modifier, short: Boolean = false) {
    val dark = isDarkTheme()
    val status = if (chip.tone == Tone.Neutral) null else toneColor(chip.tone)
    val container = Color(ChipPalette.container(status?.toArgb(), surface.toArgb(), dark))
    Row(
        modifier.height(24.dp).clip(Shapes.pill).background(container).padding(horizontal = if (short) 8.dp else 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (status != null) { StatusDot(status); Spacer(Modifier.width(6.dp)) }
        Text(
            if (short) chip.short ?: chip.text else chip.text, style = Type.chip, color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ValueText(value: String?, unit: String?, valueStyle: androidx.compose.ui.text.TextStyle, unitStyle: androidx.compose.ui.text.TextStyle, stale: Boolean) {
    val ink = if (stale) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    Text(
        buildAnnotatedString {
            append(value ?: "—")
            if (value != null && !unit.isNullOrEmpty()) withStyle(SpanStyle(fontSize = unitStyle.fontSize, color = dim, fontWeight = unitStyle.fontWeight)) { append(" "); append(unit) }
        },
        style = valueStyle, color = ink, maxLines = 1, softWrap = false, overflow = TextOverflow.Clip,
    )
}

/**
 * One metric card in three sizes (docs/PLAN_090 section 4): Today's large and small sub-cards sit on `surfaceContainerHigh`
 * inside the big card; Grid cards on the Metrics tab sit on `surfaceContainer` (1dp border in the light theme).
 * The whole card is one click target with a full-sentence description; charts are silent.
 */
@Composable
fun MetricCard(data: CardData, size: CardSize, onClick: () -> Unit, modifier: Modifier = Modifier, onPage: Boolean = false, compact: Boolean = false, chartHeight: androidx.compose.ui.unit.Dp? = null) {
    val dark = isDarkTheme()
    val raised = size == CardSize.Grid || onPage
    val surface = if (raised) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.surfaceContainerHigh
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    val stale = data.state == CardState.Stale
    val empty = data.state == CardState.Empty
    val minH = when (size) { CardSize.Large -> if (compact) 104.dp else 172.dp; CardSize.Small -> if (compact) 60.dp else 112.dp; CardSize.Grid -> 188.dp }
    val vPad = if (compact) 8.dp else Spacing.tilePad
    val chartH = chartHeight ?: if (compact) 26.dp else 56.dp
    val border = if (raised && !dark) Modifier.border(1.dp, Color(0xFFE7E7E4), Shapes.tile) else Modifier
    val accent = if (stale || empty) null else MetricAccent.of(data.id, dark)
    androidx.compose.runtime.CompositionLocalProvider(com.fitair.app.ui.components.charts.LocalChartAccent provides accent) {
    Column(
        modifier.then(border).clip(Shapes.tile).background(surface)
            .clickable(role = Role.Button, onClick = onClick)
            .clearAndSetSemantics { contentDescription = data.a11y; role = Role.Button }
            .heightIn(min = maxOf(minH, Spacing.minTouch))
            .padding(horizontal = if (size == CardSize.Small) 12.dp else Spacing.tilePad, vertical = vPad),
    ) {
        val chip = if (empty) Chip("No data", Tone.Neutral) else data.chip
        when (size) {
            CardSize.Small -> {
                val dense = LocalDensity.current.fontScale >= 1.3f || ((data.value?.length ?: 0) + (data.unit?.length ?: 0)) > 8
                Text(data.title, style = Type.metricTitle.copy(fontSize = 13.sp), color = dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(2.dp))
                if (compact) {
                    // value on the left, the chip (or the small sub line) on the same row
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f, fill = false)) { ValueText(data.value, data.unit, if (dense) Type.valueSDense else Type.valueS, Type.unitS, stale) }
                        Spacer(Modifier.weight(1f).widthIn(min = 6.dp))
                        when {
                            chip != null -> StatusChip(chip, surface, short = true)
                            data.sub != null -> Text(data.sub, style = Type.unitS, color = dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                } else {
                ValueText(data.value, data.unit, if (dense) Type.valueSDense else Type.valueS, Type.unitS, stale)
                Spacer(Modifier.weight(1f, fill = true).heightIn(min = 6.dp))
                when {
                    empty -> StatusChip(chip!!, surface, short = true)
                    data.smallShowsMini && data.mini != null -> MiniChart(data.mini, 24.dp)
                    chip != null -> StatusChip(chip, surface, short = true)
                    data.sub != null -> Text(data.sub, style = Type.unitS, color = dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                }
            }
            CardSize.Large -> {
                Text(data.title, style = Type.metricTitle, color = dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                ValueText(data.value, data.unit, Type.valueL, Type.unitL, stale)
                if (data.sub != null) Text(data.sub, style = Type.unitS, color = dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.weight(1f, fill = true).heightIn(min = 6.dp))
                if (empty || data.mini == null) EmptyBaseline(chartH) else MiniChart(data.mini, chartH)
                if (data.mini is Mini.HrDay || data.mini is Mini.LoadCurve) DayAxisLabels()
                if (chip != null) { Spacer(Modifier.height(Spacing.s)); StatusChip(chip, surface) }
            }
            CardSize.Grid -> {
                Text(data.title, style = Type.metricTitle, color = dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                ValueText(data.value, data.unit, Type.valueGrid, Type.unitL, stale)
                if (data.sub != null) Text(data.sub, style = Type.unitS, color = dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.weight(1f, fill = true).heightIn(min = 6.dp))
                if (empty || data.mini == null) EmptyBaseline(64.dp)
                else if (data.mini is Mini.Ring) Box(Modifier.fillMaxWidth().height(64.dp), contentAlignment = Alignment.CenterStart) { MiniChart(data.mini, 56.dp) }
                else MiniChart(data.mini, 64.dp)
                when {
                    data.mini is Mini.HrDay -> DayAxisLabels()
                    data.weekdays.isNotEmpty() && !empty && (data.mini is Mini.Bars || data.mini is Mini.Spark || data.mini is Mini.DotsOnBand) ->
                        WeekdayLabels(data.weekdays, data.todayIndex)
                }
                if (chip != null) { Spacer(Modifier.height(Spacing.s)); StatusChip(chip, surface) }
            }
        }
    }
    }
}

/** Loading shell at the final size: a 7 %-ink block where the value and the chart go (no shimmer). */
@Composable
fun MetricCardSkeleton(size: CardSize, modifier: Modifier = Modifier, onPage: Boolean = false) {
    val surface = if (size == CardSize.Grid || onPage) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.surfaceContainerHigh
    val block = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f)
    val minH = when (size) { CardSize.Large -> 172.dp; CardSize.Small -> 112.dp; CardSize.Grid -> 188.dp }
    Column(modifier.clip(Shapes.tile).background(surface).heightIn(min = minH).padding(Spacing.tilePad).clearAndSetSemantics { }) {
        Box(Modifier.width(72.dp).height(12.dp).clip(Shapes.pill).background(block))
        Spacer(Modifier.height(Spacing.s))
        Box(Modifier.width(if (size == CardSize.Small) 48.dp else 80.dp).height(if (size == CardSize.Small) 22.dp else 32.dp).clip(Shapes.chip).background(block))
        Spacer(Modifier.weight(1f))
        if (size != CardSize.Small) Box(Modifier.fillMaxWidth().height(if (size == CardSize.Large) 56.dp else 64.dp).clip(Shapes.chip).background(block))
    }
}
