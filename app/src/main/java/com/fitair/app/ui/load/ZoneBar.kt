package com.fitair.app.ui.load

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
import androidx.compose.ui.unit.dp
import com.fitair.app.core.Format
import com.fitair.app.ui.theme.Type

private val NAMES = listOf("Light", "Moderate", "Vigorous", "Peak")
private val SHADE = listOf(0.28f, 0.5f, 0.75f, 1f)

/** Minutes per heart-rate zone as one bar in four shades of the load colour, with the minutes under a legend. */
@Composable
fun ZoneBar(minutes: IntArray, accent: Color, modifier: Modifier = Modifier) {
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    val total = minutes.sum()
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().height(14.dp).clip(CircleShape).background(MaterialTheme.colorScheme.outlineVariant)) {
            if (total > 0) minutes.forEachIndexed { i, m ->
                if (m > 0) Box(Modifier.weight(m.toFloat()).fillMaxHeight().background(accent.copy(alpha = SHADE[i])))
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            NAMES.forEachIndexed { i, name ->
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(accent.copy(alpha = SHADE[i])))
                        Spacer(Modifier.width(6.dp))
                        Text(name, style = Type.bodySmall, color = dim)
                    }
                    Text(if (minutes[i] == 0) "—" else Format.duration(minutes[i].toLong()), style = Type.body)
                }
            }
        }
    }
}
