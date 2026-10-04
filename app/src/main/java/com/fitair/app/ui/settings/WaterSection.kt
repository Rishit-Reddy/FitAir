package com.fitair.app.ui.settings

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.health.connect.client.PermissionController
import com.fitair.app.HealthPerms
import com.fitair.app.HealthRepo
import com.fitair.app.data.dao.PrefDao
import com.fitair.app.data.dao.WaterDao
import com.fitair.app.notify.WaterAlarm
import com.fitair.app.notify.WaterSchedule
import com.fitair.app.ui.components.SectionHeader
import com.fitair.app.ui.theme.Spacing
import com.fitair.app.ui.theme.Type
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Settings > General > Water reminders (docs/PLAN_081.md 3.13): on/off (asks for notification permission), interval, glass, goal,
 * quiet during calendar events, and the optional Health Connect link for hydration.
 */
@Composable
fun WaterSection() {
    val ctx = LocalContext.current.applicationContext
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    var on by remember { mutableStateOf(WaterDao.remindersOn(ctx)) }
    var interval by remember { mutableIntStateOf(WaterDao.intervalMin(ctx)) }
    var fullScreen by remember { mutableStateOf(WaterDao.fullScreenOn(ctx)) }
    // re-checked on every return to Settings, since the grant lives in a system screen
    var fsAllowed by remember { mutableStateOf(WaterAlarm.canFullScreen(ctx)) }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycle) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) fsAllowed = WaterAlarm.canFullScreen(ctx) }
        lifecycle.lifecycle.addObserver(obs)
        onDispose { lifecycle.lifecycle.removeObserver(obs) }
    }
    val activityCtx = LocalContext.current
    var goal by remember { mutableIntStateOf(WaterDao.baseGoalMl(ctx)) }
    var quiet by remember { mutableStateOf(PrefDao.bool(ctx, WaterDao.PREF_QUIET, false)) }
    var denied by remember { mutableStateOf(false) }
    var hcGranted by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(Unit) {
        hcGranted = withContext(Dispatchers.IO) { runCatching { HealthRepo(ctx).grantedOptional().containsAll(HealthPerms.hydration) }.getOrDefault(false) }
    }

    val notif = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) { WaterAlarm.enable(ctx); on = true; denied = false } else { on = false; denied = true }
    }
    val hc = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) { granted ->
        hcGranted = granted.containsAll(HealthPerms.hydration)
        if (hcGranted == true) WaterAlarm.enqueueHcFlush(ctx)
    }
    fun resched() { if (on) WaterAlarm.reschedule(ctx) }

    SectionHeader("Water reminders")
    Spacer(Modifier.height(Spacing.s))
    Row(Modifier.fillMaxWidth().heightIn(min = Spacing.minTouch), verticalAlignment = Alignment.CenterVertically) {
        Text("Remind me to drink", style = Type.body, modifier = Modifier.weight(1f))
        Switch(checked = on, onCheckedChange = { v ->
            if (!v) { WaterAlarm.disable(ctx); on = false }
            else if (Build.VERSION.SDK_INT >= 33 && WaterAlarm.needsNotificationPermission(ctx)) notif.launch(Manifest.permission.POST_NOTIFICATIONS)
            else { WaterAlarm.enable(ctx); on = true; denied = false }
        })
    }
    if (denied) Text("Notifications are off for FitAir, so reminders cannot show. Allow them in the system settings and try again.",
        style = Type.bodySmall, color = dim)
    Text("A nudge every ${interval} min between 30 min after you wake and 1 h before your usual bedtime, with 200, 300 and 500 ml buttons. " +
        "It only knows what you tap; the band cannot see drinking.", style = Type.bodySmall, color = dim)

    Spacer(Modifier.height(Spacing.m))
    Text("Every", style = Type.label, color = dim)
    ChipRow(listOf(45, 60, 90, 120, 150, 180), interval, { "$it min" }) { interval = it; PrefDao.set(ctx, WaterDao.PREF_INTERVAL, it.toString()); resched() }
    Row(Modifier.fillMaxWidth().heightIn(min = Spacing.minTouch), verticalAlignment = Alignment.CenterVertically) {
        Text("Full-screen reminder", style = Type.body, modifier = Modifier.weight(1f))
        Switch(checked = fullScreen, onCheckedChange = { fullScreen = it; PrefDao.setBool(ctx, WaterDao.PREF_FULLSCREEN, it) })
    }
    Text("When the phone is locked or the screen is off, the reminder fills the screen with 200, 300 and 500 ml, Snooze and Skip. " +
        "While you use the phone, Android shows a banner instead; tap it for the same screen.", style = Type.bodySmall, color = dim)
    if (fullScreen && !fsAllowed && Build.VERSION.SDK_INT >= 34) {
        Row(Modifier.fillMaxWidth().heightIn(min = Spacing.minTouch), verticalAlignment = Alignment.CenterVertically) {
            Text("Android has full-screen reminders off for FitAir.", style = Type.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
            TextButton(onClick = {
                runCatching {
                    activityCtx.startActivity(android.content.Intent(android.provider.Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                        android.net.Uri.parse("package:${ctx.packageName}")))
                }
            }) { Text("Allow", style = Type.label) }
        }
    }
    Spacer(Modifier.height(Spacing.s))
    Text("Daily goal (+0.5 L on a hard day)", style = Type.label, color = dim)
    ChipRow(listOf(1500, 2000, 2500, 3000, 3500, 4000), goal, { String.format(Locale.US, "%.1f L", it / 1000.0) }) {
        goal = WaterSchedule.clampGoal(it); PrefDao.set(ctx, WaterDao.PREF_GOAL, goal.toString()); resched()
    }
    Text("2.5 L is a common default, not a personal need; food counts too.", style = Type.bodySmall, color = dim)

    Row(Modifier.fillMaxWidth().heightIn(min = Spacing.minTouch), verticalAlignment = Alignment.CenterVertically) {
        Text("Quiet during calendar events", style = Type.body, modifier = Modifier.weight(1f))
        Switch(checked = quiet, onCheckedChange = { quiet = it; PrefDao.setBool(ctx, WaterDao.PREF_QUIET, it) })
    }
    Text("Off by default, so shifts that are calendar events still get nudges.", style = Type.bodySmall, color = dim)

    Spacer(Modifier.height(Spacing.s))
    Row(Modifier.fillMaxWidth().heightIn(min = Spacing.minTouch), verticalAlignment = Alignment.CenterVertically) {
        Text(if (hcGranted == true) "Saved to Health Connect" else "Health Connect not linked", style = Type.body, modifier = Modifier.weight(1f))
        if (hcGranted == false) TextButton(onClick = { hc.launch(HealthPerms.hydration) }) { Text("Link", style = Type.label) }
    }
    Text("Optional. Drinks are written to Health Connect and drinks other apps wrote are counted. Whether Google Health shows them is not verified.",
        style = Type.bodySmall, color = dim)
    Text("Tip: set FitAir's battery use to Unrestricted so the phone does not delay reminders. They can still arrive several minutes late.",
        style = Type.bodySmall, color = dim)
}

@Composable
private fun <T> ChipRow(values: List<T>, selected: T, label: (T) -> String, onPick: (T) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = Spacing.xs), horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
        // up to 6 short chips: wrap into two rows on narrow screens
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            values.chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                    row.forEach { v -> FilterChip(selected = v == selected, onClick = { onPick(v) }, label = { Text(label(v)) }) }
                }
            }
        }
    }
}
