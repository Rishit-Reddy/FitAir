package com.fitair.app.ui.today

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.fitair.app.data.metrics.MetricId
import com.fitair.app.data.metrics.MetricSnapshot
import com.fitair.app.ui.components.CardSize
import com.fitair.app.ui.components.MetricCard
import com.fitair.app.ui.components.MetricCardSkeleton
import com.fitair.app.ui.metrics.MetricCards
import com.fitair.app.ui.theme.Spacing

/** The five Today cards (two large, three small) sitting straight on the page, without a wrapping card or summary text. */
@Composable
fun TodayCards(snap: MetricSnapshot?, mode: Mode, layout: TodayLayout, nowMs: Long, onCard: (MetricId) -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.gap)) {
        row(layout.large, CardSize.Large, snap, mode, nowMs, onCard)
        row(layout.small, CardSize.Small, snap, mode, nowMs, onCard)
    }
}

@Composable
private fun row(ids: List<MetricId>, size: CardSize, snap: MetricSnapshot?, mode: Mode, nowMs: Long, onCard: (MetricId) -> Unit) {
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(Spacing.gap)) {
        ids.forEach { id ->
            val m = Modifier.weight(1f).fillMaxHeight()
            if (snap == null) MetricCardSkeleton(size, m, onPage = true)
            else MetricCard(MetricCards.card(id, snap, size, mode, nowMs), size, { onCard(id) }, m, onPage = true)
        }
    }
}
