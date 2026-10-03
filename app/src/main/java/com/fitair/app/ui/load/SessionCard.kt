package com.fitair.app.ui.load

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.records.ExerciseSessionRecord
import com.fitair.app.core.Format
import com.fitair.app.data.dao.SessionRow
import com.fitair.app.data.dao.SessionState
import com.fitair.app.ui.components.Chip
import com.fitair.app.ui.components.StatusChip
import com.fitair.app.ui.components.SurfaceCard
import com.fitair.app.ui.components.Tone
import com.fitair.app.ui.theme.Type
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val WHEN_FMT = DateTimeFormatter.ofPattern("EEE HH:mm", Locale.ENGLISH)

private fun title(s: SessionRow): String {
    if (s.title.isNotBlank()) return s.title
    val t = ExerciseSessionRecord.EXERCISE_TYPE_INT_TO_STRING_MAP[s.type]?.replace('_', ' ')?.lowercase() ?: "workout"
    return t.replaceFirstChar { it.uppercase() }
}

/** One workout: name, when and how long, an effort chip, and the keep / not-exercise choice. */
@Composable
fun SessionCard(s: SessionRow, busy: Boolean, onVerdict: (exercise: Boolean) -> Unit) {
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    val surface = MaterialTheme.colorScheme.surfaceContainer
    val whenText = Instant.ofEpochMilli(s.startMs).atZone(ZoneId.systemDefault()).format(WHEN_FMT)
    SurfaceCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title(s), style = Type.body)
                Text("$whenText · ${Format.duration(Math.round(s.durationMin))}", style = Type.bodySmall, color = dim)
            }
            s.meanHrr?.let { StatusChip(Chip("Effort ${Math.round(it * 100)} %", Tone.Neutral), surface) }
        }
        when (s.state) {
            SessionState.AutoFlagged -> {
                Text("Low effort: probably not exercise", style = Type.bodySmall, modifier = Modifier.padding(top = 6.dp))
                Row {
                    TextButton(enabled = !busy, onClick = { onVerdict(true) }) { Text("Keep it", style = Type.label) }
                    TextButton(enabled = !busy, onClick = { onVerdict(false) }) { Text("Not exercise", style = Type.label) }
                }
            }
            SessionState.UserNotExercise -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Not counted as exercise", style = Type.bodySmall, color = dim, modifier = Modifier.weight(1f))
                TextButton(enabled = !busy, onClick = { onVerdict(true) }) { Text("Restore", style = Type.label) }
            }
            SessionState.UserKept -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Kept as exercise", style = Type.bodySmall, color = dim, modifier = Modifier.weight(1f))
                TextButton(enabled = !busy, onClick = { onVerdict(false) }) { Text("Not real exercise", style = Type.label) }
            }
            SessionState.Normal -> Row { Spacer(Modifier.weight(1f)); TextButton(enabled = !busy, onClick = { onVerdict(false) }) { Text("Not real exercise", style = Type.label) } }
        }
    }
}
