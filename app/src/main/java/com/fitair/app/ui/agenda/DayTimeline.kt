package com.fitair.app.ui.agenda

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.fitair.app.integrations.calendar.CalEvent
import com.fitair.app.ui.theme.LocalStatusColors
import com.fitair.app.ui.theme.Type
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** One hour is 50 dp, about 0.8 cm on screen. */
private val HOUR_H = 50.dp
private val LABEL_W = 40.dp

/**
 * A compact day view like a calendar app: hour grid, timed events (overlaps side by side), a "now" line, scrolling on its own.
 * Opens near [nowMs] for today, otherwise at the first event (or 07:00).
 */
@Composable
fun DayTimeline(events: List<CalEvent>, day: LocalDate, colors: AgendaColors, nowMs: Long?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val z = remember { ZoneId.systemDefault() }
    val dayStart = remember(day) { day.atStartOfDay(z).toInstant() }
    val placed = remember(events) { TimelineLayout.place(events) }
    val scroll = rememberScrollState()
    val px = with(LocalDensity.current) { HOUR_H.toPx() }
    val ink = MaterialTheme.colorScheme.onSurface
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    val line = MaterialTheme.colorScheme.outlineVariant
    LaunchedEffect(day, placed.isNotEmpty()) {
        val firstMin = placed.minOfOrNull { Duration.between(dayStart, it.e.begin).toMinutes() }?.coerceAtLeast(0)
        val target = if (nowMs != null) (nowMs - dayStart.toEpochMilli()) / 60_000L - 60 else (firstMin ?: 7 * 60L) - 30
        scroll.scrollTo((target.coerceAtLeast(0) / 60f * px).toInt())
    }
    Box(modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp)).verticalScroll(scroll)) {
        BoxWithConstraints(Modifier.fillMaxWidth().height(HOUR_H * 24)) {
            val gridW = maxWidth - LABEL_W
            repeat(24) { h ->
                Box(Modifier.offset(y = HOUR_H * h).fillMaxWidth().height(HOUR_H)) {
                    Box(Modifier.offset(x = LABEL_W).fillMaxWidth().height(1.dp).background(line))
                    Text("%02d".format(h), style = Type.axis, color = dim, modifier = Modifier.width(LABEL_W - 6.dp).offset(y = (-7).dp))
                }
            }
            placed.forEach { p ->
                val top = minutes(dayStart, p.e.begin).coerceIn(0f, 1440f)
                val end = minutes(dayStart, maxOf(p.e.end, p.e.begin.plusSeconds(TimelineLayout.MIN_MINUTES * 60L))).coerceIn(top, 1440f)
                if (end <= top) return@forEach
                val colW = gridW / p.cols
                val h = HOUR_H * ((end - top) / 60f)
                val cal = colors.of(p.e)?.let { Color(it) } ?: dim
                Row(
                    Modifier.offset(x = LABEL_W + colW * p.col, y = HOUR_H * (top / 60f)).width(colW - 2.dp).height(h - 2.dp)
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh).clickable(onClick = onClick),
                ) {
                    Box(Modifier.width(3.dp).fillMaxHeight().background(cal))
                    Column(Modifier.padding(horizontal = 6.dp, vertical = 2.dp)) {
                        Text(p.e.title.ifBlank { "Busy" }, style = Type.bodySmall, color = ink, maxLines = if (h >= 44.dp) 2 else 1, overflow = TextOverflow.Ellipsis)
                        if (h >= 44.dp) Text(AgendaFormat.range(p.e.begin, p.e.end, z), style = Type.axis, color = dim, maxLines = 1)
                    }
                }
            }
            if (nowMs != null) {
                val y = HOUR_H * (minutes(dayStart, Instant.ofEpochMilli(nowMs)).coerceIn(0f, 1440f) / 60f)
                val red = LocalStatusColors.current.alert
                Box(Modifier.offset(x = LABEL_W, y = y - 1.dp).fillMaxWidth().height(2.dp).background(red))
                Box(Modifier.offset(x = LABEL_W - 4.dp, y = y - 4.dp).size(8.dp).clip(androidx.compose.foundation.shape.CircleShape).background(red))
            }
        }
    }
}

private fun minutes(from: Instant, to: Instant): Float = Duration.between(from, to).seconds / 60f
