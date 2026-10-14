package com.fitair.app.ui.hair

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.fitair.app.data.dao.PrefDao
import com.fitair.app.data.dao.RoutineDao
import com.fitair.app.data.dao.WakeDao
import com.fitair.app.notify.RoutineSchedule
import com.fitair.app.ui.theme.Shapes
import com.fitair.app.ui.theme.Spacing
import com.fitair.app.ui.theme.Type
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

private class Item(val key: String, val title: String, val sub: String?, val onDone: (() -> Unit)?, val onSkip: (() -> Unit)?, val onOpen: () -> Unit)

/**
 * The Today spot for things that need an answer: a hair dose from its reminder time until it is answered, one neutral line for
 * yesterday's unanswered dose, and a running pickleball timer. At most two rows show; the rest collapse into one line. Nothing
 * shows when nothing is due.
 */
@Composable
fun RoutineCards(
    nowMs: Long, timerStartMs: Long?, onStopTimer: () -> Unit, onOpenHair: () -> Unit, onOpenPickleball: () -> Unit, modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current.applicationContext
    var refresh by remember { mutableIntStateOf(0) }
    val minute = nowMs / 60_000L
    val items by produceState(emptyList<Item>(), minute, refresh, timerStartMs != null) {
        value = withContext(Dispatchers.IO) { build(ctx, nowMs, onOpenHair) { refresh++ } }
    }
    val timer = timerStartMs?.let { start ->
        val s = ((nowMs - start) / 1000).coerceAtLeast(0)
        Item("timer", "Pickleball", "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60), null, null, onOpenPickleball)
    }
    val all = listOfNotNull(timer) + items
    if (all.isEmpty()) return
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.subGap)) {
        all.take(2).forEach { RoutineRow(it, timer != null && it === timer, onStopTimer) }
        if (all.size > 2) Text("${all.size - 2} more in Hair routine", style = Type.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.clickable { onOpenHair() }.padding(horizontal = Spacing.xs, vertical = Spacing.xs))
    }
}

@Composable
private fun RoutineRow(it: Item, isTimer: Boolean, onStop: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(Shapes.tile).background(MaterialTheme.colorScheme.surfaceContainer).clickable { it.onOpen() }
            .heightIn(min = 64.dp).padding(start = Spacing.l, end = Spacing.s, top = Spacing.xs, bottom = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(it.title, style = Type.body, maxLines = 2)
            it.sub?.let { s -> Text(s, style = Type.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1) }
        }
        if (isTimer) TextButton(onClick = onStop) { Text("Stop", style = Type.label) }
        it.onSkip?.let { f -> TextButton(onClick = f) { Text("Skip", style = Type.label, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
        it.onDone?.let { f -> FilledTonalButton(onClick = f, contentPadding = PaddingValues(horizontal = 16.dp)) { Text("Done") } }
    }
}

private fun build(ctx: android.content.Context, nowMs: Long, open: () -> Unit, changed: () -> Unit): List<Item> {
    if (!RoutineDao.on(ctx)) return emptyList()
    val z = ZoneId.systemDefault()
    val now = Instant.ofEpochMilli(nowMs).atZone(z)
    val today = now.toLocalDate()
    val nowMin = now.hour * 60 + now.minute
    val wake = WakeDao.get(ctx, nowMs)
    val detected = wake.wakeMs?.takeIf { Instant.ofEpochMilli(it).atZone(z).toLocalDate() == today }?.let { Instant.ofEpochMilli(it).atZone(z).let { t -> t.hour * 60 + t.minute } }
    val log = RoutineDao.range(ctx, today.minusDays(1), today)
    val todayLog = log[today.toString()].orEmpty(); val yLog = log[today.minusDays(1).toString()].orEmpty()
    val keto = RoutineSchedule.ketoToday(today.dayOfWeek, RoutineDao.ketoMask(ctx))
    val out = ArrayList<Item>()
    fun answer(d: LocalDate, slot: String, status: String) = { RoutineDao.set(ctx, d, slot, status); changed() }

    val amMin = RoutineSchedule.amMinute(wake.usualWakeMin, detected)
    if (RoutineSchedule.AM !in todayLog && nowMin >= amMin && nowMin < RoutineSchedule.AM_CUTOFF_MIN)
        out.add(Item("am", if (keto) "Shampoo, then minoxidil" else "Minoxidil, morning", if (keto) "Leave the shampoo 3–5 min, dry your scalp" else null,
            answer(today, RoutineSchedule.AM, "done"), answer(today, RoutineSchedule.AM, "skipped"), open))
    val amDone = todayLog[RoutineSchedule.AM]?.takeIf { it.first == "done" }?.let { Instant.ofEpochMilli(it.second).atZone(z).let { t -> t.hour * 60 + t.minute } }
    val pmMin = RoutineSchedule.pmMinute(RoutineDao.pmMin(ctx), wake.usualBedMin, amDone)
    if (pmMin != null && RoutineSchedule.PM !in todayLog && nowMin >= pmMin && nowMin < RoutineSchedule.pmCutoffMin(wake.usualBedMin))
        out.add(Item("pm", "Minoxidil, evening", null, answer(today, RoutineSchedule.PM, "done"), answer(today, RoutineSchedule.PM, "skipped"), open))

    // yesterday's unanswered doses: one neutral line, only for days after the routine was switched on
    val since = PrefDao.get(ctx, "routine_since")?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    val yesterday = today.minusDays(1)
    if (since != null && !yesterday.isBefore(since)) {
        val missing = listOf(RoutineSchedule.AM to "morning", RoutineSchedule.PM to "evening").filter { it.first !in yLog }
        if (missing.isNotEmpty()) {
            val what = missing.joinToString(" and ") { it.second }
            out.add(Item("missed", "Yesterday's $what dose was not logged", null,
                { missing.forEach { RoutineDao.set(ctx, yesterday, it.first, "done") }; changed() },
                { missing.forEach { RoutineDao.set(ctx, yesterday, it.first, "skipped") }; changed() }, open))
        }
    }
    return out
}
