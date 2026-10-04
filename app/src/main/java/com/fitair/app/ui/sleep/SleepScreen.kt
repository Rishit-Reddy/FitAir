package com.fitair.app.ui.sleep

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material3.TextButton
import com.fitair.app.ui.components.ExplainSheet
import com.fitair.app.ui.copy.Copy
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.fitair.app.core.Format
import com.fitair.app.ui.components.DetailHeader
import com.fitair.app.ui.components.EmptyState
import com.fitair.app.ui.components.InlineError
import com.fitair.app.ui.components.Page
import com.fitair.app.ui.components.SectionBreak
import com.fitair.app.ui.components.SectionHeader
import com.fitair.app.ui.components.SectionCard
import com.fitair.app.ui.components.ScoreBar
import com.fitair.app.ui.components.SelectionCard
import com.fitair.app.ui.components.StageBar
import com.fitair.app.ui.components.StatusDot
import com.fitair.app.ui.components.SubTabs
import com.fitair.app.ui.components.Tone
import com.fitair.app.ui.components.toneColor
import com.fitair.app.ui.components.charts.BarChart
import com.fitair.app.ui.components.charts.LineChart
import com.fitair.app.ui.theme.Shapes
import com.fitair.app.ui.theme.Spacing
import com.fitair.app.ui.theme.Type
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private fun hm(min: Double?): String = Format.duration(min?.let { Math.round(it) })

@Composable
fun SleepScreen(onBack: () -> Unit) {
    val vm: SleepVm = viewModel()
    Column(Modifier.fillMaxSize()) {
        DetailHeader("Sleep", onBack)
        when (val s = vm.state) {
            SleepState.Loading -> Text("Loading…", style = Type.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(Spacing.gutter))
            is SleepState.Error -> Column(Modifier.padding(horizontal = Spacing.gutter)) { InlineError("Could not load sleep: ${s.message}", onRetry = vm::load) }
            is SleepState.Ready -> Content(vm, s.nights, Modifier.weight(1f))
        }
    }
}

private val dateFmt = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)

@Composable
private fun Content(vm: SleepVm, all: List<Night>, modifier: Modifier) {
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    val nights = remember(all, vm.range) { SleepModel.lastN(all, vm.range) }
    val withData = nights.count { it.hasData }
    val default = remember(all) { SleepModel.defaultNight(all) }
    var explain by remember { mutableStateOf<String?>(null) }
    explain?.let { ExplainSheet(it, onDismiss = { explain = null }) }
    Page(modifier) {
        if (default == null) {
            EmptyState("No sleep data yet", "Sync Health Connect, then check back.")
            return@Page
        }
        // The top block always shows the last night; chart taps only change the selection card.
        val last = all.first { it.date == default }
        val sel = all.firstOrNull { it.date == vm.selected } ?: last
        SectionHeader("Last night · ${last.date.format(dateFmt)}")
        Spacer(Modifier.height(Spacing.s))
        Detail(last, vm.detailFor(last.date), onExplain = { explain = "sleep" })

        SectionBreak()
        SectionHeader("History")
        Spacer(Modifier.height(Spacing.s))
        SubTabs(listOf("14 nights", "30 nights"), if (vm.range == 14) 0 else 1, { vm.chooseRange(if (it == 0) 14 else 30) })
        Spacer(Modifier.height(Spacing.l))
        if (withData == 0) {
            EmptyState("No sleep data for this range", "Pick a longer range.")
            return@Page
        }
        Row(Modifier.fillMaxWidth().clip(Shapes.card).background(MaterialTheme.colorScheme.surfaceVariant).padding(Spacing.l)) {
            Stat("Avg sleep", hm(SleepModel.avgSleepMin(nights)), Modifier.weight(1f))
            Stat("Average night score", SleepModel.avgScore(nights)?.let { "${Math.round(it)}" } ?: Format.DASH, Modifier.weight(1f))
            Stat("Short over 7 nights", SleepModel.latestDebtMin(nights)?.let { hm(it) } ?: Format.DASH, Modifier.weight(1f))
        }
        Spacer(Modifier.height(Spacing.m))

        val labels = remember(nights) { nights.map { SleepModel.xLabel(it.date) } }
        val selIdx = nights.indexOfFirst { it.date == sel.date }.takeIf { it >= 0 }
        val need = SleepModel.needMin(nights)
        val pick: (Int) -> Unit = { i -> vm.select(nights[i].date) }
        val barTones = remember(nights) { SleepModel.durationTones(nights) }
        val scoreTones = remember(nights) { SleepModel.scoreTones(nights) }
        val barColors = barTones.map { if (it == Tone.Neutral) null else toneColor(it) }
        val pointColors = scoreTones.map { if (it == Tone.Neutral) null else toneColor(it) }
        SectionCard("Time asleep") {
            BarChart(
                SleepModel.durationHours(nights), selectedIndex = selIdx, onSelect = pick,
                baseline = need?.let { (it / 60.0).toFloat() }, xLabels = labels, barColors = barColors,
            )
            Text(
                if (need != null) "Dashed line: your need, ${hm(need)}. Green meets it, amber is up to an hour short, red is more." else " ",
                style = Type.bodySmall, color = dim, modifier = Modifier.padding(top = Spacing.s),
            )
        }
        Spacer(Modifier.height(Spacing.m))
        SelectedNight(sel, vm.detailFor(sel.date), isDefault = sel.date == default, onBack = vm::selectDefault)
        Spacer(Modifier.height(Spacing.m))
        BedWakeCard(nights, vm.windows, selIdx, pick)
        Spacer(Modifier.height(Spacing.m))
        SectionCard("Sleep score") {
            LineChart(SleepModel.scores(nights), selectedIndex = selIdx, onSelect = pick, xLabels = labels, pointColors = pointColors)
        }
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(value, style = MaterialTheme.typography.titleMedium)
        Text(label, style = Type.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Card between the charts: the selected night. Fixed height so selecting never moves the page. */
@Composable
private fun SelectedNight(n: Night, d: NightDetail?, isDefault: Boolean, onBack: () -> Unit) {
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    SelectionCard(
        caption = n.date.format(dateFmt),
        actionLabel = if (isDefault) null else "Back to last night",
        onAction = if (isDefault) null else onBack,
    ) {
        if (!n.hasData) {
            Text("No sleep recorded", style = Type.bodySmall, color = dim)
            return@SelectionCard
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            val window = if (d?.bedtime != null) " · ${d.bedtime} – ${d.wake}" else ""
            Text(hm(n.sleepMin) + window, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            VerdictScore(n.score)
        }
        Spacer(Modifier.height(Spacing.s))
        val st = d?.stages
        if (st != null) StageBar(st.awake, st.light, st.rem, st.deep, height = Spacing.s, legend = false)
        else Box(Modifier.fillMaxWidth().height(Spacing.s).clip(Shapes.chip).background(MaterialTheme.colorScheme.outlineVariant))
    }
}

/** Top block: duration, score, window, stages, component cards. */
@Composable
private fun Detail(n: Night, d: NightDetail?, onExplain: () -> Unit) {
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    if (!n.hasData) {
        Text("No sleep recorded.", style = Type.bodySmall, color = dim)
        return
    }
    SectionCard(null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(hm(n.sleepMin), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
            VerdictScore(n.score)
        }
        Spacer(Modifier.height(Spacing.s))
        ScoreBar(n.score, SleepModel.tone(n.score), Modifier.fillMaxWidth())
        if (d?.bedtime != null) Text("${d.bedtime} – ${d.wake}", style = Type.bodySmall, color = dim, modifier = Modifier.padding(top = Spacing.s))
        Spacer(Modifier.height(Spacing.l))
        val st = d?.stages
        if (st == null) {
            Text(if (d == null) "Loading…" else "No stage data for this night.", style = Type.bodySmall, color = dim)
        } else {
            if (d.segments.isNotEmpty() && d.startMs != null && d.endMs != null) {
                StageTimeline(d.segments, d.startMs, d.endMs)
                Spacer(Modifier.height(Spacing.l))
            }
            StageBar(st.awake, st.light, st.rem, st.deep, height = 28.dp, legend = true)
        }
    }
    if (d?.startMs != null) {
        Spacer(Modifier.height(Spacing.m))
        SleepHeartCard(d)
    }

    Spacer(Modifier.height(Spacing.l))
    Row(verticalAlignment = Alignment.CenterVertically) {
        SectionHeader("What made the score", Modifier.weight(1f))
        TextButton(onClick = onExplain) { Text("What is this?", style = Type.label) }
    }
    Spacer(Modifier.height(Spacing.s))
    val cards = SleepModel.componentCards(n)
    cards.chunked(2).forEachIndexed { r, row ->
        if (r > 0) Spacer(Modifier.height(Spacing.m))
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(Spacing.m)) {
            row.forEach { ComponentCardView(it, Modifier.weight(1f).fillMaxHeight()) }
        }
    }
    Text(
        "Score = enough sleep 40% · restful 25% · deep + dream sleep 20% · same-time sleep 15%. Enough sleep is compared with your own need; the others with healthy ranges.",
        style = Type.bodySmall, color = dim, modifier = Modifier.padding(top = Spacing.m),
    )
}

@Composable
private fun ComponentCardView(c: ComponentCard, modifier: Modifier) {
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    Column(modifier.clip(Shapes.card).background(MaterialTheme.colorScheme.surfaceVariant).padding(Spacing.l)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(c.name.uppercase(), style = Type.caption, color = dim, modifier = Modifier.weight(1f))
            if (c.tone != Tone.Neutral) StatusDot(toneColor(c.tone))
        }
        Spacer(Modifier.height(Spacing.xs))
        Text(c.headline, style = MaterialTheme.typography.headlineSmall)
        Text(c.reading, style = Type.bodySmall, color = dim, minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(Spacing.s))
        if (c.score != null) {
            ScoreBar(c.score, c.tone, Modifier.fillMaxWidth())
            Text("Score ${Math.round(c.score)}/100", style = Type.bodySmall, color = dim, modifier = Modifier.padding(top = Spacing.xs))
        } else {
            Spacer(Modifier.height(4.dp))
            Text(" ", style = Type.bodySmall, modifier = Modifier.padding(top = Spacing.xs))
        }
    }
}

/** "Good night" first (with its tier dot), the score as a dim number after it. */
@Composable
internal fun VerdictScore(score: Double?) {
    val v = Copy.sleepNight(score)
    if (v.headline.isEmpty()) return
    StatusDot(toneColor(v.tone)); Spacer(Modifier.width(Spacing.s))
    Text(v.headline, style = MaterialTheme.typography.bodyLarge)
    Text("  ${Math.round(score!!)}", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
