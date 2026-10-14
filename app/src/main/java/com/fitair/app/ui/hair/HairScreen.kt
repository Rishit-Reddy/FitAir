package com.fitair.app.ui.hair

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.fitair.app.core.Format
import com.fitair.app.data.dao.PrefDao
import com.fitair.app.data.dao.RoutineDao
import com.fitair.app.notify.RoutineAlarm
import com.fitair.app.notify.RoutineSchedule
import com.fitair.app.notify.WaterAlarm
import com.fitair.app.ui.components.DetailHeader
import com.fitair.app.ui.components.Hairline
import com.fitair.app.ui.components.Page
import com.fitair.app.ui.components.SectionCard
import com.fitair.app.ui.theme.Spacing
import com.fitair.app.ui.theme.Type
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

private val DAY_FMT = DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault())
private const val DAYS = 14

/** Hair routine: today's two doses, a 14-day check-off history, and the settings. */
@Composable
fun HairScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current.applicationContext
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    var tick by remember { mutableIntStateOf(0) }
    var on by remember { mutableStateOf(RoutineDao.on(ctx)) }
    var denied by remember { mutableStateOf(false) }
    var mask by remember { mutableIntStateOf(RoutineDao.ketoMask(ctx)) }
    var pm by remember { mutableIntStateOf(RoutineDao.pmMin(ctx)) }
    var full by remember { mutableStateOf(RoutineDao.fullScreenOn(ctx)) }
    var fsAllowed by remember { mutableStateOf(WaterAlarm.canFullScreen(ctx)) }
    var testSent by remember { mutableStateOf(false) }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycle) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) { fsAllowed = WaterAlarm.canFullScreen(ctx); tick++ } }
        lifecycle.lifecycle.addObserver(obs)
        onDispose { lifecycle.lifecycle.removeObserver(obs) }
    }
    val notif = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) { RoutineAlarm.enable(ctx); on = true; denied = false } else { on = false; denied = true }
    }
    val activityCtx = LocalContext.current
    val today = LocalDate.now()
    val z = ZoneId.systemDefault()
    val log = remember(tick) { RoutineDao.range(ctx, today.minusDays(DAYS - 1L), today) }
    val nowMin = remember(tick) { java.time.LocalTime.now().let { it.hour * 60 + it.minute } }

    Column(Modifier.fillMaxSize()) {
        DetailHeader("Hair routine", onBack)
        Page(Modifier.weight(1f)) {
            SectionCard("Today") {
                listOf(RoutineSchedule.AM to "Morning", RoutineSchedule.PM to "Evening").forEach { (slot, name) ->
                    val st = log[today.toString()]?.get(slot)
                    val keto = slot == RoutineSchedule.AM && RoutineSchedule.ketoToday(today.dayOfWeek, mask)
                    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(name, style = Type.body)
                            if (keto) Text("with shampoo", style = Type.bodySmall, color = dim)
                        }
                        when {
                            st != null -> {
                                Text(if (st.first == "done") "Done" else "Skipped", style = Type.body, color = dim)
                                TextButton(onClick = { RoutineDao.clear(ctx, today, slot); tick++ }) { Text("Undo", style = Type.label) }
                            }
                            else -> {
                                TextButton(onClick = { RoutineDao.set(ctx, today, slot, "skipped"); tick++ }) { Text("Skip", style = Type.label, color = dim) }
                                FilledTonalButton(onClick = { RoutineDao.set(ctx, today, slot, "done"); tick++ }) { Text("Done") }
                            }
                        }
                    }
                    if (slot == RoutineSchedule.AM) Hairline()
                }
            }
            Spacer(Modifier.height(Spacing.m))
            SectionCard("Last $DAYS days") {
                val days = (DAYS - 1 downTo 0).map { today.minusDays(it.toLong()) }
                Row(Modifier.fillMaxWidth()) { Spacer(Modifier.width(92.dp)); Text("AM", style = Type.axis, color = dim, modifier = Modifier.width(40.dp), textAlign = TextAlign.Center); Text("PM", style = Type.axis, color = dim, modifier = Modifier.width(40.dp), textAlign = TextAlign.Center) }
                days.reversed().forEach { d ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 32.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (d == today) "Today" else d.format(DAY_FMT), style = Type.bodySmall, color = dim, modifier = Modifier.width(92.dp))
                        listOf(RoutineSchedule.AM, RoutineSchedule.PM).forEach { slot ->
                            val status = log[d.toString()]?.get(slot)?.first
                            val cutoff = if (slot == RoutineSchedule.AM) RoutineSchedule.AM_CUTOFF_MIN else 23 * 60
                            val passed = d < today || nowMin >= cutoff
                            Box(Modifier.width(40.dp), contentAlignment = Alignment.Center) { Dot(RoutineSchedule.shown(status, passed)) }
                        }
                        if (RoutineSchedule.ketoToday(d.dayOfWeek, mask)) Text("shampoo day", style = Type.axis, color = dim)
                    }
                }
                Spacer(Modifier.height(Spacing.s))
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.m)) {
                    Legend("done"); Legend("skipped"); Legend("not logged")
                }
            }
            Spacer(Modifier.height(Spacing.m))
            SectionCard("Reminders") {
                Row(Modifier.fillMaxWidth().heightIn(min = Spacing.minTouch), verticalAlignment = Alignment.CenterVertically) {
                    Text("Remind me", style = Type.body, modifier = Modifier.weight(1f))
                    Switch(checked = on, onCheckedChange = { v ->
                        if (!v) { RoutineAlarm.disable(ctx); on = false }
                        else if (Build.VERSION.SDK_INT >= 33 && WaterAlarm.needsNotificationPermission(ctx)) notif.launch(Manifest.permission.POST_NOTIFICATIONS)
                        else { RoutineAlarm.enable(ctx); on = true; denied = false }
                    })
                }
                if (denied) Text("Notifications are off for FitAir, so reminders cannot show. Allow them in the system settings and try again.", style = Type.bodySmall, color = MaterialTheme.colorScheme.error)
                Text("Morning: ${RoutineSchedule.AFTER_WAKE_MIN} min after you wake, from your usual wake time and corrected when your sleep syncs. " +
                    "A missed dose is never made up or doubled. Questions about dose or side effects: ask a pharmacist or your doctor.", style = Type.bodySmall, color = dim)

                Spacer(Modifier.height(Spacing.m))
                Text("Evening", style = Type.label, color = dim)
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s), modifier = Modifier.padding(vertical = Spacing.xs)) {
                    listOf(18 * 60, 19 * 60, 20 * 60, 21 * 60).forEach { m ->
                        FilterChip(selected = pm == m, onClick = { pm = m; PrefDao.set(ctx, RoutineDao.PREF_PM_MIN, m.toString()); RoutineAlarm.reschedule(ctx) }, label = { Text(Format.clock(m / 60, 0)) })
                    }
                }
                Spacer(Modifier.height(Spacing.s))
                Text("Ketoconazole shampoo days (shown on the morning screen)", style = Type.label, color = dim)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(vertical = Spacing.xs)) {
                    DayOfWeek.values().forEach { d ->
                        FilterChip(
                            selected = (mask shr (d.value - 1)) and 1 == 1,
                            onClick = { mask = mask xor (1 shl (d.value - 1)); PrefDao.set(ctx, RoutineDao.PREF_KETO_MASK, mask.toString()) },
                            label = { Text(d.getDisplayName(TextStyle.NARROW, Locale.getDefault())) },
                            modifier = Modifier.weight(1f, fill = false),
                        )
                    }
                }
                Spacer(Modifier.height(Spacing.s))
                Row(Modifier.fillMaxWidth().heightIn(min = Spacing.minTouch), verticalAlignment = Alignment.CenterVertically) {
                    Text("Full-screen reminder", style = Type.body, modifier = Modifier.weight(1f))
                    Switch(checked = full, onCheckedChange = { full = it; PrefDao.setBool(ctx, RoutineDao.PREF_FULLSCREEN, it) })
                }
                Text("Shows over the lock screen. While you use the phone, Android shows a banner instead; tap it for the same screen.", style = Type.bodySmall, color = dim)
                if (full && !fsAllowed && Build.VERSION.SDK_INT >= 34) {
                    Row(Modifier.fillMaxWidth().heightIn(min = Spacing.minTouch), verticalAlignment = Alignment.CenterVertically) {
                        Text("Android has full-screen reminders off for FitAir.", style = Type.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
                        TextButton(onClick = {
                            runCatching {
                                activityCtx.startActivity(android.content.Intent(android.provider.Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, android.net.Uri.parse("package:${ctx.packageName}")))
                            }
                        }) { Text("Allow", style = Type.label) }
                    }
                }
                Spacer(Modifier.height(Spacing.s))
                OutlinedButton(onClick = { RoutineAlarm.scheduleTest(ctx); testSent = true }, modifier = Modifier.fillMaxWidth()) { Text("Test in 1 min", style = Type.label) }
                if (testSent) Text("Lock the phone and wait a minute. If nothing shows, set FitAir's battery use to Unrestricted.", style = Type.bodySmall, color = dim)
            }
        }
    }
}

@Composable
private fun Dot(state: String?) {
    val cs = MaterialTheme.colorScheme
    when (state) {
        "done" -> Box(Modifier.size(14.dp).clip(CircleShape).background(cs.primary))
        "skipped" -> Box(Modifier.size(14.dp).clip(CircleShape).background(cs.onSurfaceVariant.copy(alpha = 0.45f)))
        "not logged" -> Box(Modifier.size(14.dp).clip(CircleShape).background(cs.outlineVariant))
        else -> Box(Modifier.size(5.dp).clip(CircleShape).background(cs.outlineVariant))
    }
}

@Composable
private fun Legend(label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Dot(label); Spacer(Modifier.width(Spacing.xs))
        Text(label, style = Type.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
