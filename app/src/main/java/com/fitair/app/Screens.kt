package com.fitair.app

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.fitair.app.ui.components.Hairline
import com.fitair.app.ui.components.Page
import com.fitair.app.ui.components.SectionHeader
import kotlinx.coroutines.delay

// ---------- helpers ----------

private fun fmtTimer(ms: Long): String {
    val s = ms / 1000
    return "%02d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
}

/** Kept for CoachScreen; same as [SectionHeader]. */
@Composable
fun Caption(text: String) = SectionHeader(text)

@Composable
private fun CenterMessage(title: String, body: String?, button: String?, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(40.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.headlineMedium)
        if (body != null) {
            Spacer(Modifier.height(12.dp))
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (button != null) {
            Spacer(Modifier.height(24.dp))
            TextButton(onClick = onClick) { Text(button) }
        }
    }
}

// ---------- gating screens ----------

@Composable
fun GrantAccessScreen(onGrant: () -> Unit) =
    CenterMessage("FitAir", "Read access to steps, heart rate, sleep and more from Health Connect.", "Grant access", onGrant)

@Composable
fun HealthConnectMissingScreen(updateRequired: Boolean) {
    val ctx = LocalContext.current
    CenterMessage(
        if (updateRequired) "Update Health Connect" else "Health Connect needed",
        "FitAir reads your data through Health Connect.",
        if (updateRequired) "Open update page" else "Open install page",
    ) {
        val pkg = "com.google.android.apps.healthdata"
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            ctx.startActivity(intent)
        } catch (e: Exception) {
            try {
                ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$pkg")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Exception) {
            }
        }
    }
}

@Composable
fun LoadingScreen() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("…", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ---------- Log ----------

@Composable
fun LogScreen(vm: MainViewModel) {
    val start = vm.sessionStart
    val end = vm.sessionEnd
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(start, end) {
        while (start != null && end == null) {
            now = System.currentTimeMillis()
            delay(500)
        }
    }
    var effort by remember { mutableStateOf<Int?>(null) }
    var kcal by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }

    Page {
        com.fitair.app.ui.weight.WeightEntry()
        Spacer(Modifier.height(16.dp))
        Hairline()
        Spacer(Modifier.height(16.dp))
        Caption("Pickleball")
        val elapsed = when {
            start == null -> 0L
            end != null -> end - start
            else -> now - start
        }
        Text(
            fmtTimer(elapsed),
            style = MaterialTheme.typography.displayLarge.copy(fontFamily = FontFamily.SansSerif),
            modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
        )
        when {
            start == null -> TextButton(onClick = { vm.startSession() }) { Text("Start") }
            end == null -> TextButton(onClick = { vm.stopSession() }) { Text("Stop") }
            else -> {
                Hairline()
                Spacer(Modifier.height(20.dp))
                Caption("Effort")
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    (1..5).forEach { n ->
                        FilterChip(
                            selected = effort == n,
                            onClick = { effort = if (effort == n) null else n },
                            label = { Text("$n") },
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = kcal, onValueChange = { kcal = it.filter { c -> c.isDigit() || c == '.' } },
                    label = { Text("Energy, kcal (optional)") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = notes, onValueChange = { notes = it },
                    label = { Text("Notes (optional)") }, minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(16.dp))
                Row {
                    TextButton(onClick = { vm.discardSession() }) { Text("Discard") }
                    Spacer(Modifier.weight(1f))
                    TextButton(
                        enabled = !vm.saving,
                        onClick = {
                            val parts = listOfNotNull(effort?.let { "Effort $it/5" }, notes.trim().ifEmpty { null })
                            vm.saveSession(parts.joinToString("\n").ifEmpty { null }, kcal.toDoubleOrNull())
                            effort = null; kcal = ""; notes = ""
                        },
                    ) { Text(if (vm.saving) "Saving" else "Save") }
                }
            }
        }
        vm.logMessage?.let {
            Spacer(Modifier.height(16.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
