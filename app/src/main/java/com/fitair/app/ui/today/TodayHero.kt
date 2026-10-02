package com.fitair.app.ui.today

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.fitair.app.data.metrics.MetricId
import com.fitair.app.data.metrics.MetricSnapshot
import com.fitair.app.ui.components.CardSize
import com.fitair.app.ui.components.MetricCard
import com.fitair.app.ui.components.MetricCardSkeleton
import com.fitair.app.ui.metrics.MetricCards

/** The heart-rate card across the full width (one row, two columns). Its chart takes the height the card is given. */
@Composable
fun HeartHero(snap: MetricSnapshot?, mode: Mode, nowMs: Long, onClick: () -> Unit, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val chart: Dp = (maxHeight - 112.dp).coerceIn(40.dp, 90.dp)
        if (snap == null) MetricCardSkeleton(CardSize.Large, Modifier.fillMaxWidth(), onPage = true)
        else MetricCard(MetricCards.card(MetricId.Heart, snap, CardSize.Large, mode, nowMs), CardSize.Large, onClick, Modifier.fillMaxWidth(), onPage = true, chartHeight = chart)
    }
}
