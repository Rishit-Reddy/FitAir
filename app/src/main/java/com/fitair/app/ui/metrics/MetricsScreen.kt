package com.fitair.app.ui.metrics

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import com.fitair.app.data.metrics.MetricId
import com.fitair.app.data.metrics.MetricSnapshot
import com.fitair.app.ui.components.CardSize
import com.fitair.app.ui.components.MetricCard
import com.fitair.app.ui.components.MetricCardSkeleton
import com.fitair.app.ui.theme.Spacing
import com.fitair.app.ui.theme.Type

/** Grid columns by width: 2 on a phone, 3 from 600 dp, 4 from 840 dp (docs/PLAN_090 6.3). */
fun metricsColumns(widthDp: Float): Int = when { widthDp >= 840f -> 4; widthDp >= 600f -> 3; else -> 2 }

/**
 * Metrics tab: "Metrics" and an Edit button, then a card grid (Heart rate full width) in the saved order. Tapping a card calls
 * [onOpen]. [gridState] is hoisted so the scroll position survives a detail screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MetricsScreen(onOpen: (MetricId) -> Unit, gridState: LazyGridState = rememberLazyGridState(), vm: MetricsVm = viewModel()) {
    LifecycleResumeEffect(Unit) { vm.load(); onPauseOrDispose { } }
    val snap = vm.snapshot
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(snap) { now = System.currentTimeMillis() }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = Spacing.gutter, end = Spacing.gutter, top = Spacing.l, bottom = Spacing.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Metrics", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            if (vm.editing) {
                TextButton(onClick = vm::reset, modifier = Modifier.heightIn(min = Spacing.minTouch)) { Text("Reset", style = Type.label) }
                Button(
                    onClick = vm::done, modifier = Modifier.height(40.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer),
                    contentPadding = PaddingValues(horizontal = 20.dp),
                ) { Text("Done", style = Type.label) }
            } else {
                Button(
                    onClick = vm::startEdit, modifier = Modifier.height(40.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer),
                    contentPadding = PaddingValues(horizontal = 20.dp),
                ) { Text("Edit", style = Type.label) }
            }
        }
        if (vm.editing) EditList(vm) else PullToRefreshBox(isRefreshing = vm.syncing, onRefresh = vm::refresh, modifier = Modifier.fillMaxSize()) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val cols = metricsColumns(maxWidth.value)
                val ids = MetricsLayout.visible(vm.layout)
                LazyVerticalGrid(
                    columns = GridCells.Fixed(cols), state = gridState,
                    contentPadding = PaddingValues(start = Spacing.gutter, end = Spacing.gutter, top = Spacing.xs, bottom = Spacing.xl),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.gap), verticalArrangement = Arrangement.spacedBy(Spacing.gap),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(ids.size, key = { ids[it].name }, span = { i ->
                        // Heart rate is full width on a phone and two columns wide on larger screens
                        GridItemSpan(if (ids[i] == MetricId.Heart) (if (cols <= 2) cols else 2) else 1)
                    }) { i ->
                        val id = ids[i]
                        if (snap == null) MetricCardSkeleton(CardSize.Grid)
                        else MetricCard(MetricCards.card(id, snap, CardSize.Grid, null, now), CardSize.Grid, { onOpen(id) })
                    }
                }
            }
        }
    }
}

@Composable
private fun EditList(vm: MetricsVm) {
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = Spacing.gutter, end = Spacing.gutter, bottom = Spacing.xl)) {
        item {
            Text("Move cards up or down and choose what to show. Heart rate always keeps the full width.", style = Type.bodySmall, color = dim, modifier = Modifier.padding(bottom = Spacing.s))
        }
        itemsIndexed(vm.draft, key = { _, e -> e.id.name }) { i, e ->
            val title = MetricCards.title(e.id)
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.bodyMedium, color = if (e.on) MaterialTheme.colorScheme.onSurface else dim, modifier = Modifier.weight(1f))
                MoveButton("▲", "Move $title up", enabled = i > 0) { vm.move(i, -1) }
                MoveButton("▼", "Move $title down", enabled = i < vm.draft.lastIndex) { vm.move(i, 1) }
                Switch(checked = e.on, onCheckedChange = { vm.setOn(e, it) }, modifier = Modifier.semantics { contentDescription = "Show $title" })
            }
        }
    }
}

@Composable
private fun MoveButton(glyph: String, description: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(Spacing.minTouch).alpha(if (enabled) 1f else 0.3f)
            .then(if (enabled) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) { Text(glyph, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary) }
}
