package com.fitair.app.ui.today

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import com.fitair.app.analytics.WorkWindowStats
import com.fitair.app.integrations.calendar.CalEvent
import androidx.compose.ui.graphics.Color
import com.fitair.app.ui.agenda.AgendaColors
import com.fitair.app.ui.theme.LocalStatusColors
import com.fitair.app.ui.theme.Type
import java.time.LocalDate
import java.time.ZoneId

/**
 * One 00-24 bar for today: a dim band for last night's sleep, shifts as blocks shaded by heart-rate intensity, every event in its calendar's colour, other timed events as
 * ticks, and a red marker for now.
 */
@Composable
fun DayStrip(events: List<CalEvent>, shifts: List<WorkWindowStats>, colors: AgendaColors, sleepEndMs: Long?, nowMs: Long, modifier: Modifier = Modifier) {
    val z = ZoneId.systemDefault()
    val d0 = StripMath.dayStartMs(LocalDate.now(z), z)
    val track = MaterialTheme.colorScheme.outlineVariant
    val ink = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    val red = LocalStatusColors.current.alert
    Column(modifier.fillMaxWidth()) {
        Canvas(Modifier.fillMaxWidth().height(16.dp).clearAndSetSemantics { }) {
            val w = size.width; val h = size.height
            drawRoundRect(track.copy(alpha = 0.5f), Offset.Zero, Size(w, h), CornerRadius(h / 2f))
            sleepEndMs?.let { drawRect(dim.copy(alpha = 0.25f), Offset.Zero, Size(w * StripMath.frac(it, d0), h)) }
            events.filter { !it.allDay }.forEach { e ->
                val a = StripMath.frac(e.begin.toEpochMilli(), d0); val b = StripMath.frac(e.end.toEpochMilli(), d0)
                val cal = colors.of(e)?.let { Color(it) } ?: ink
                if (e.work) {
                    val st = shifts.firstOrNull { it.startMs == e.begin.toEpochMilli() }
                    drawRect(cal.copy(alpha = StripMath.shade(st?.avgPctHrr)), Offset(w * a, 0f), Size(maxOf(w * (b - a), 3.dp.toPx()), h))
                } else drawRect(cal, Offset(w * a, 2.dp.toPx()), Size(maxOf(w * (b - a), 2.dp.toPx()), h - 4.dp.toPx()))
            }
            val x = w * StripMath.frac(nowMs, d0)
            drawRect(red, Offset(x - 1.dp.toPx(), -2.dp.toPx()), Size(2.dp.toPx(), h + 4.dp.toPx()))
        }
        Row(Modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf("00", "06", "12", "18", "24").forEach { Text(it, style = Type.axis, color = dim) }
        }
    }
}
