package com.fitair.app.notify

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import com.fitair.app.AppLog
import com.fitair.app.data.dao.RoutineDao
import com.fitair.app.ui.theme.FitAirTheme
import com.fitair.app.ui.theme.Shapes
import com.fitair.app.ui.theme.Spacing
import com.fitair.app.ui.theme.ThemeMode
import com.fitair.app.ui.theme.Type
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

/** Full-screen hair routine reminder over the lock screen: Done, Snooze 15 min (twice at most) or Skip. */
class RoutineReminderActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLog.init(applicationContext)
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true) }
        else @Suppress("DEPRECATION") window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        val slot = intent.getStringExtra(RoutineAlarm.EXTRA_SLOT) ?: RoutineSchedule.AM
        val test = intent.getBooleanExtra(RoutineAlarm.EXTRA_TEST, false)
        val mode = ThemeMode.fromKey(getSharedPreferences("fitair", Context.MODE_PRIVATE).getString("theme", null))
        setContent { FitAirTheme(mode) { Screen(slot, test) } }
    }

    @Composable
    private fun Screen(slot: String, test: Boolean) {
        val app = applicationContext
        val scope = rememberCoroutineScope()
        val today = remember { LocalDate.now() }
        val keto = slot == RoutineSchedule.AM && RoutineSchedule.ketoToday(today.dayOfWeek, RoutineDao.ketoMask(app))
        var answered by remember { mutableStateOf<String?>(null) }
        val canSnooze = test || RoutineAlarm.canSnooze(app, today, slot)
        fun finishWith(label: String, block: () -> Unit) {
            answered = label
            scope.launch {
                withContext(Dispatchers.IO) { runCatching(block) }
                try { NotificationManagerCompat.from(app).cancel(RoutineAlarm.NOTIF_ID) } catch (e: Exception) { }
                kotlinx.coroutines.delay(800); finish()
            }
        }
        Column(
            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).systemBarsPadding().padding(horizontal = Spacing.xl, vertical = Spacing.xxl),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.weight(1f))
            Text(if (slot == RoutineSchedule.AM) "🌅" else "🌙", fontSize = 56.sp)
            Spacer(Modifier.height(Spacing.l))
            Text(answered ?: if (slot == RoutineSchedule.AM) "Good morning" else "Good evening", style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
            Spacer(Modifier.height(Spacing.m))
            if (answered == null) {
                if (keto) {
                    Text("1. Shampoo with ketoconazole, leave 3–5 min, rinse", style = Type.body, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(Spacing.xs))
                    Text("2. Dry your scalp, then minoxidil", style = Type.body, textAlign = TextAlign.Center)
                } else Text("Minoxidil on a dry scalp", style = Type.body, textAlign = TextAlign.Center)
                if (test) Text("(test reminder, nothing is logged)", style = Type.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = Spacing.s))
            }
            Spacer(Modifier.weight(1f))
            if (answered == null) {
                Button(
                    onClick = { finishWith("Done") { if (!test) RoutineDao.set(app, today, slot, "done") } },
                    modifier = Modifier.fillMaxWidth().height(72.dp), shape = Shapes.tile,
                ) { Text("Done", style = MaterialTheme.typography.titleLarge) }
                Spacer(Modifier.height(Spacing.m))
                if (canSnooze) {
                    OutlinedButton(
                        onClick = { finishWith("Snoozed") { if (!test) RoutineAlarm.snooze(app, today, slot) } },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                    ) { Text("Snooze ${RoutineSchedule.SNOOZE_MIN} min", style = Type.label) }
                    Spacer(Modifier.height(Spacing.s))
                }
                TextButton(
                    onClick = { finishWith("Skipped") { if (!test) RoutineDao.set(app, today, slot, "skipped") } },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) { Text("Skip this one", style = Type.label, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}
