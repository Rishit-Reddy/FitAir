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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import com.fitair.app.AppLog
import com.fitair.app.data.dao.WaterDao
import com.fitair.app.ui.copy.Copy
import com.fitair.app.ui.theme.FitAirTheme
import com.fitair.app.ui.theme.Shapes
import com.fitair.app.ui.theme.Spacing
import com.fitair.app.ui.theme.ThemeMode
import com.fitair.app.ui.theme.Type
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Full-screen water reminder: shown over the lock screen when the reminder fires with the screen off, or when the notification is
 * tapped. Three amounts log and close; Snooze moves the reminder 15 min; Skip just closes (counts as unanswered, like a dismissed
 * notification).
 */
class WaterReminderActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLog.init(applicationContext)
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true) }
        else @Suppress("DEPRECATION") window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        val mode = ThemeMode.fromKey(getSharedPreferences("fitair", Context.MODE_PRIVATE).getString("theme", null))
        setContent { FitAirTheme(mode) { Screen(onDone = ::finish) } }
    }

    @Composable
    private fun Screen(onDone: () -> Unit) {
        val app = applicationContext
        val scope = rememberCoroutineScope()
        var today by remember { mutableStateOf<com.fitair.app.data.dao.WaterToday?>(null) }
        var logged by remember { mutableStateOf<Int?>(null) }
        LaunchedEffect(Unit) { today = withContext(Dispatchers.IO) { runCatching { WaterDao.today(app) }.getOrNull() } }
        fun clearNotification() { try { NotificationManagerCompat.from(app).cancel(WaterAlarm.NOTIF_ID) } catch (e: Exception) { } }

        Column(
            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).systemBarsPadding().padding(horizontal = Spacing.xl, vertical = Spacing.xxl),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.weight(1f))
            Text("💧", fontSize = 56.sp)
            Spacer(Modifier.height(Spacing.l))
            Text(if (logged != null) "Logged $logged ml" else "Time for water", style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
            Spacer(Modifier.height(Spacing.s))
            val t = today
            val ml = (t?.ml ?: 0) + (logged ?: 0)
            if (t != null) {
                Text(Copy.waterLine(ml, t.goalMl), style = Type.body, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(Spacing.m))
                val frac = if (t.goalMl > 0) (ml.toFloat() / t.goalMl).coerceIn(0f, 1f) else 0f
                Box(Modifier.fillMaxWidth(0.7f).height(6.dp).clip(Shapes.pill).background(MaterialTheme.colorScheme.outlineVariant)) {
                    if (frac > 0f) Box(Modifier.fillMaxWidth(frac).fillMaxHeight().background(MaterialTheme.colorScheme.primary))
                }
            }
            Spacer(Modifier.weight(1f))
            if (logged == null) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.m)) {
                    WaterDao.AMOUNTS.forEach { amount ->
                        Button(
                            onClick = {
                                logged = amount
                                scope.launch {
                                    withContext(Dispatchers.IO) { runCatching { WaterDao.add(app, amount) } }
                                    clearNotification()
                                    delay(900); onDone()
                                }
                            },
                            modifier = Modifier.weight(1f).height(88.dp), shape = Shapes.tile,
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer),
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("$amount", style = MaterialTheme.typography.headlineSmall)
                                Text("ml", style = Type.bodySmall)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(Spacing.l))
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            withContext(Dispatchers.IO) { runCatching { WaterAlarm.snooze(app) } }
                            clearNotification(); onDone()
                        }
                    },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                ) { Text("Snooze ${WaterAlarm.SNOOZE_MIN} min", style = Type.label) }
                Spacer(Modifier.height(Spacing.s))
                TextButton(onClick = { clearNotification(); onDone() }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text("Skip", style = Type.label, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
