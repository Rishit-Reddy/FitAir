package com.fitair.app

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

// ---------- helpers ----------

private const val DASH = "—"

private fun fmtSleep(min: Long?): String = if (min == null) DASH else "${min / 60}h ${min % 60}m"
private fun fmtTimer(ms: Long): String {
    val s = ms / 1000
    return "%02d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
}

@Composable
private fun Hairline() = HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)

@Composable
fun Caption(text: String) =
    Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun StatRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
    Hairline()
}

@Composable
private fun Page(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 28.dp, vertical = 32.dp),
        content = content,
    )
}

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

// ---------- Today ----------

@Composable
fun TodayScreen(vm: MainViewModel) {
    val s = vm.stats
    LaunchedEffect(Unit) { vm.loadReadiness() }
    Page {
        Caption("Readiness")
        if (vm.readinessOffline) {
            Text("Laptop offline", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 24.dp))
        } else {
            Text(vm.readinessScore ?: DASH, style = MaterialTheme.typography.displayLarge, modifier = Modifier.padding(top = 4.dp))
            Text(vm.readinessNote ?: "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 24.dp))
        }
        Hairline()
        Spacer(Modifier.height(24.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Caption("Steps today")
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { vm.refresh(); vm.loadReadiness() }, enabled = !vm.loading) {
                Text(if (vm.loading) "Refreshing" else "Refresh")
            }
        }
        Text(
            s?.steps?.toString() ?: DASH,
            style = MaterialTheme.typography.displayLarge,
            modifier = Modifier.padding(top = 4.dp, bottom = 32.dp),
        )
        vm.error?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(16.dp))
        }
        Hairline()
        StatRow("Distance", s?.let { "%.2f km".format(it.distanceM / 1000.0) } ?: DASH)
        StatRow("Heart rate avg", s?.hrAvg?.let { "$it bpm" } ?: DASH)
        StatRow("Heart rate min", s?.hrMin?.let { "$it bpm" } ?: DASH)
        StatRow("Heart rate max", s?.hrMax?.let { "$it bpm" } ?: DASH)
        StatRow("Resting HR", s?.restingHr?.let { "$it bpm" } ?: DASH)
        StatRow("HRV", s?.hrvMs?.let { "%.0f ms".format(it) } ?: DASH)
        StatRow("Sleep", fmtSleep(s?.sleepMinutes))
        StatRow("SpO2", s?.spo2?.let { "%.0f %%".format(it) } ?: DASH)
        StatRow("Active energy", s?.activeKcal?.let { "%.0f kcal".format(it) } ?: DASH)
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

// ---------- Data ----------

fun probeToText(rows: List<ProbeRow>): String = rows.joinToString("\n\n") { r ->
    "${r.type}\n  count: ${r.count}\n  range: ${r.firstIso ?: DASH} .. ${r.lastIso ?: DASH}\n" +
        "  median gap: ${r.medianGapSec?.let { "%.1f s".format(it) } ?: DASH}\n  origins: ${r.origins.joinToString(", ").ifEmpty { DASH }}"
}

@Composable
fun DataScreen(vm: MainViewModel) {
    val clip = LocalClipboardManager.current
    Page {
        Caption("Data probe · last 30 days")
        Row(Modifier.padding(top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { vm.runProbe() }, enabled = !vm.probing) {
                Text(if (vm.probing) "Probing…" else "Data probe")
            }
            Spacer(Modifier.weight(1f))
            if (vm.probeRows.isNotEmpty()) {
                TextButton(onClick = { clip.setText(AnnotatedString(probeToText(vm.probeRows))) }) { Text("Copy all as text") }
            }
        }
        vm.probeError?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        Hairline()
        vm.probeRows.forEach { r ->
            Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                Row {
                    Text(r.type, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Text("${r.count}", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                }
                val dim = MaterialTheme.colorScheme.onSurfaceVariant
                Text("${r.firstIso ?: DASH}  →  ${r.lastIso ?: DASH}", style = MaterialTheme.typography.bodySmall, color = dim)
                Text(
                    "gap ${r.medianGapSec?.let { "%.1f s".format(it) } ?: DASH}  ·  ${r.origins.joinToString(", ").ifEmpty { DASH }}",
                    style = MaterialTheme.typography.bodySmall, color = dim,
                )
            }
            Hairline()
        }
    }
}

// ---------- Settings ----------

@Composable
fun SettingsScreen(vm: MainViewModel) {
    Page {
        Caption("Theme")
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ThemeMode.values().forEach { m ->
                FilterChip(selected = vm.themeMode == m, onClick = { vm.setTheme(m) }, label = { Text(m.label) })
            }
        }
        Spacer(Modifier.height(32.dp))
        Hairline()
        Spacer(Modifier.height(24.dp))
        Caption("Coach")
        Spacer(Modifier.height(8.dp))
        var provider by remember { mutableStateOf(vm.getPref("coach_provider").ifEmpty { "gemini" }) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("gemini" to "Gemini", "openai" to "OpenAI").forEach { (k, l) ->
                FilterChip(selected = provider == k, onClick = {
                    provider = k; vm.setPref("coach_provider", k); AppLog.d("coach provider: $k")
                }, label = { Text(l) })
            }
        }
        Spacer(Modifier.height(12.dp))
        PrefField(vm, "coach_model_gemini", "Gemini model (default gemini-2.5-flash)", secret = false)
        PrefField(vm, "coach_model_openai", "OpenAI model (default gpt-4o-mini)", secret = false)
        PrefField(vm, "gemini_key", "Gemini API key", secret = true)
        PrefField(vm, "openai_key", "OpenAI API key", secret = true)
        Spacer(Modifier.height(24.dp))
        Hairline()
        Spacer(Modifier.height(24.dp))
        Caption("Laptop sync")
        Spacer(Modifier.height(8.dp))
        PrefField(vm, SyncPrefs.ADDR, "Laptop server address (http://100.x.y.z:8787)", secret = false)
        PrefField(vm, SyncPrefs.KEY, "Server API key", secret = true)
        Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { vm.testConnection() }, enabled = !vm.testing) {
                Text(if (vm.testing) "Testing…" else "Test connection")
            }
            TextButton(onClick = { vm.syncNow() }, enabled = !vm.syncing) {
                Text(if (vm.syncing) "Syncing…" else "Sync now")
            }
        }
        val dim = MaterialTheme.colorScheme.onSurfaceVariant
        vm.testResult?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = dim) }
        val last = if (vm.syncLastMs > 0)
            java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT)
                .format(java.util.Date(vm.syncLastMs)) else "never"
        Text("Last sync: $last", style = MaterialTheme.typography.bodySmall, color = dim)
        vm.syncStatus?.takeIf { it != "ok" }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = dim) }
        vm.syncCounts?.let { raw ->
            val txt = runCatching {
                val j = org.json.JSONObject(raw)
                j.keys().asSequence().joinToString("  ·  ") { "$it ${j.getInt(it)}" }
            }.getOrNull()
            if (txt != null) Text(txt, style = MaterialTheme.typography.bodySmall, color = dim)
        }
        Spacer(Modifier.height(24.dp))
        Hairline()
        Spacer(Modifier.height(24.dp))
        LogsSection()
    }
}

@Composable
private fun LogsSection() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    var open by remember { mutableStateOf(false) }
    Caption("Logs")
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { open = !open }) { Text(if (open) "Hide" else "Show") }
        TextButton(onClick = { clipboard.setText(androidx.compose.ui.text.AnnotatedString(AppLog.text())) }) { Text("Copy") }
        TextButton(onClick = { AppLog.clear() }) { Text("Clear") }
    }
    if (open) {
        val v = AppLog.version // re-read when the log changes
        val shown = remember(v) { AppLog.text().lines().takeLast(120).reversed().joinToString("\n") }
        Text(
            shown.ifEmpty { "No log yet" },
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, fontSize = 11.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PrefField(vm: MainViewModel, key: String, label: String, secret: Boolean) {
    var v by remember { mutableStateOf(vm.getPref(key)) }
    OutlinedTextField(
        value = v,
        onValueChange = { v = it; vm.setPref(key, it) },
        label = { Text(label) }, singleLine = true,
        visualTransformation = if (secret) androidx.compose.ui.text.input.PasswordVisualTransformation()
        else androidx.compose.ui.text.input.VisualTransformation.None,
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
    )
}
