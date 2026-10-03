package com.fitair.app.ui.load

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fitair.app.ui.components.StatusDot
import com.fitair.app.ui.components.SurfaceCard
import com.fitair.app.ui.components.charts.DayAxisLabels
import com.fitair.app.ui.components.charts.LoadCurve
import com.fitair.app.ui.components.toneColor
import com.fitair.app.ui.copy.Copy
import com.fitair.app.ui.theme.Spacing
import com.fitair.app.ui.theme.Type

/** The top of the Load page: today's number, the verdict against your usual for this hour, the day curve and how much of the day the band covers. */
@Composable
fun LoadHero(ui: LoadUi, nowHour: Int) {
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    val v = Copy.loadSoFar(ui.today.soFar, ui.today.typicalByNow, ui.today.partial)
    SurfaceCard {
        Text("Today so far", style = Type.metricTitle, color = dim)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(Math.round(ui.today.soFar).toString(), style = Type.valueL)
            Spacer(Modifier.width(Spacing.m))
            Row(Modifier.padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                StatusDot(toneColor(v.tone)); Spacer(Modifier.width(6.dp)); Text(v.headline, style = Type.body)
            }
        }
        Spacer(Modifier.height(Spacing.m))
        LoadCurve(ui.hourly, ui.typicalHourly, nowHour, 96.dp)
        DayAxisLabels()
        Spacer(Modifier.height(Spacing.s))
        Text(
            ui.today.coverage?.let { "Heart rate covers ${Math.round(it * 100)} % of your waking hours so far" + if (ui.today.partial) " · partial day" else "" }
                ?: "Not enough of the day has passed to judge coverage.",
            style = Type.bodySmall, color = dim,
        )
        Text("Solid line: today. Dashed: your usual by each hour.", style = Type.bodySmall, color = dim)
    }
}
