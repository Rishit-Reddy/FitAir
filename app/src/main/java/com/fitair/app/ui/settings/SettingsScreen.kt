package com.fitair.app.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.fitair.app.AppLog
import com.fitair.app.MainViewModel
import com.fitair.app.ProbeRow
import com.fitair.app.analytics.Check
import com.fitair.app.analytics.CheckStatus
import com.fitair.app.analytics.SelfCheck
import com.fitair.app.core.Format
import com.fitair.app.diag.DebugBundle
import com.fitair.app.secure.SecretStore
import com.fitair.app.ui.components.*
import com.fitair.app.ui.theme.Spacing
import com.fitair.app.ui.theme.ThemeMode
import com.fitair.app.ui.theme.Type
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val TABS = listOf("General", "Data", "Logs", "Diagnostics")

/** Settings with four plain sub-tabs. Data (sync status, probe) and Logs are separate from General. */
@Composable
fun SettingsScreen(vm: MainViewModel) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        SubTabs(TABS, tab, { tab = it }, Modifier.padding(horizontal = Spacing.gutter))
        when (tab) {
            0 -> GeneralTab(vm)
            1 -> DataTab(vm)
            2 -> LogsTab()
            else -> DiagnosticsTab(vm)
        }
    }
}

// ---------- General ----------

@Composable
private fun GeneralTab(vm: MainViewModel) = Page {
    SectionHeader("Theme")
    Row(Modifier.padding(top = Spacing.s), horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
        ThemeMode.values().forEach { m ->
            FilterChip(selected = vm.themeMode == m, onClick = { vm.setTheme(m) }, label = { Text(m.label) })
        }
    }
    SectionBreak()
    SectionHeader("Coach")
    Spacer(Modifier.height(Spacing.s))
    var provider by remember { mutableStateOf(vm.getPref("coach_provider").ifEmpty { "gemini" }) }
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
        listOf("gemini" to "Gemini", "openai" to "OpenAI").forEach { (k, l) ->
            FilterChip(selected = provider == k, onClick = {
                provider = k; vm.setPref("coach_provider", k); AppLog.d("coach provider: $k")
            }, label = { Text(l) })
        }
    }
    Spacer(Modifier.height(Spacing.m))
    PrefField(vm, "coach_model_gemini", "Gemini model (blank = default)")
    PrefField(vm, "coach_model_openai", "OpenAI model (blank = default)")
    SecretField("gemini_key", "Gemini API key")
    SecretField("openai_key", "OpenAI API key")
    Text("Keys are stored encrypted on this device. Questions and the health numbers they need are sent to the provider you pick.",
        style = Type.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    SectionBreak()
    CalendarSection()
    SectionBreak()
    WaterSection()
    SectionBreak()
    HeartRateSection()
    SectionBreak()
    SectionHeader("Google Drive")
    Spacer(Modifier.height(Spacing.s))
    BackupSection()
}

@Composable
private fun PrefField(vm: MainViewModel, key: String, label: String) {
    var v by remember { mutableStateOf(vm.getPref(key)) }
    OutlinedTextField(
        value = v, onValueChange = { v = it; vm.setPref(key, it) },
        label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
    )
}

@Composable
private fun SecretField(name: String, label: String) {
    val ctx = LocalContext.current
    var v by remember { mutableStateOf(runCatching { SecretStore.get(ctx, name) }.getOrDefault("")) }
    var err by remember { mutableStateOf<String?>(null) }
    OutlinedTextField(
        value = v,
        onValueChange = {
            v = it
            err = try { SecretStore.set(ctx, name, it.trim()); null } catch (e: Exception) { e.message }
        },
        label = { Text(label) }, singleLine = true, visualTransformation = PasswordVisualTransformation(),
        isError = err != null, supportingText = err?.let { { Text(it) } },
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
    )
}

// ---------- Data ----------

private fun probeToText(rows: List<ProbeRow>): String = rows.joinToString("\n\n") { r ->
    "${r.type}\n  count: ${r.count}\n  range: ${r.firstIso ?: Format.DASH} .. ${r.lastIso ?: Format.DASH}\n" +
        "  median gap: ${r.medianGapSec?.let { "%.1f s".format(it) } ?: Format.DASH}\n  origins: ${r.origins.joinToString(", ").ifEmpty { Format.DASH }}"
}

@Composable
private fun DataTab(vm: MainViewModel) {
    val clip = LocalClipboardManager.current
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    Page {
        SectionHeader("Sync")
        TextButton(onClick = { vm.syncNow() }, enabled = !vm.syncing) { Text(if (vm.syncing) "Syncing…" else "Sync now") }
        val last = if (vm.syncLastMs > 0)
            java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(java.util.Date(vm.syncLastMs)) else "never"
        Text("Last sync: $last", style = Type.bodySmall, color = dim)
        if (vm.newestHrMs > 0) {
            val t = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(java.util.Date(vm.newestHrMs))
            Text("Newest heart rate data: $t", style = Type.bodySmall, color = dim)
            Text("If this is old, Google Health has not passed newer data from your Air to Health Connect yet. Open Google Health to sync it, then Sync now.",
                style = Type.bodySmall, color = dim)
        }
        vm.syncStatus?.takeIf { it != "ok" }?.let { Text(it, style = Type.bodySmall, color = dim) }
        vm.syncCounts?.let { raw ->
            val txt = runCatching {
                val j = org.json.JSONObject(raw)
                j.keys().asSequence().joinToString("  ·  ") { "$it ${j.getInt(it)}" }
            }.getOrNull()
            if (txt != null) Text(txt, style = Type.bodySmall, color = dim)
        }
        if (vm.dbSizeBytes > 0) Text("Database: %.1f MB".format(vm.dbSizeBytes / 1048576.0), style = Type.bodySmall, color = dim)

        SectionBreak()
        SectionHeader("Data probe · last 30 days")
        Row(Modifier.padding(vertical = Spacing.s), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { vm.runProbe() }, enabled = !vm.probing) { Text(if (vm.probing) "Probing…" else "Run probe") }
            Spacer(Modifier.weight(1f))
            if (vm.probeRows.isNotEmpty()) TextButton(onClick = { clip.setText(AnnotatedString(probeToText(vm.probeRows))) }) { Text("Copy all as text") }
        }
        vm.probeError?.let { InlineError(it) }
        Hairline()
        vm.probeRows.forEach { r ->
            Column(Modifier.fillMaxWidth().padding(vertical = Spacing.m)) {
                Row {
                    Text(r.type, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Text("${r.count}", style = MaterialTheme.typography.titleMedium)
                }
                Text("${r.firstIso ?: Format.DASH}  →  ${r.lastIso ?: Format.DASH}", style = Type.bodySmall, color = dim)
                Text("gap ${r.medianGapSec?.let { "%.1f s".format(it) } ?: Format.DASH}  ·  ${r.origins.joinToString(", ").ifEmpty { Format.DASH }}",
                    style = Type.bodySmall, color = dim)
            }
            Hairline()
        }
    }
}

// ---------- Logs ----------

@Composable
private fun LogsTab() {
    val clip = LocalClipboardManager.current
    Page {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionHeader("App log", Modifier.weight(1f))
            TextButton(onClick = { clip.setText(AnnotatedString(AppLog.text())) }) { Text("Copy") }
            TextButton(onClick = { AppLog.clear() }) { Text("Clear") }
        }
        val v = AppLog.version // re-read when the log changes
        val shown = remember(v) { AppLog.text().lines().takeLast(200).reversed().joinToString("\n") }
        Text(shown.ifEmpty { "No log yet" }, style = Type.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ---------- Diagnostics ----------

@Composable
private fun DiagnosticsTab(vm: MainViewModel) {
    val ctx = LocalContext.current
    val clip = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    var checks by remember { mutableStateOf<List<Check>?>(null) }
    var running by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    var aiCalls by remember { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(Unit) { aiCalls = withContext(Dispatchers.IO) { DebugBundle.recentAiCalls(ctx, 20) } }

    Page {
        SectionHeader("Self-check")
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(enabled = !running, onClick = {
                running = true
                scope.launch {
                    checks = withContext(Dispatchers.IO) { SelfCheck.execute(ctx) }
                    running = false
                }
            }) { Text(if (running) "Running…" else "Run self-check") }
            checks?.let { Text(SelfCheck.summary(it), style = Type.bodySmall, color = dim) }
        }
        checks?.forEach { c ->
            Row(Modifier.fillMaxWidth().padding(vertical = Spacing.s), verticalAlignment = Alignment.Top) {
                Box(Modifier.padding(top = 7.dp)) {
                    StatusDot(toneColor(when (c.status) { CheckStatus.PASS -> Tone.Good; CheckStatus.WARN -> Tone.Caution; CheckStatus.FAIL -> Tone.Alert }))
                }
                Spacer(Modifier.width(Spacing.m))
                Column {
                    Text(c.title, style = Type.body)
                    Text(c.detail, style = Type.bodySmall, color = dim)
                }
            }
            Hairline()
        }

        SectionBreak()
        SectionHeader("Analytics")
        val rebuild by com.fitair.app.data.Rebuild.state.collectAsState()
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(enabled = !rebuild.running, onClick = { com.fitair.app.data.Rebuild.start(ctx) }) {
                Text(if (rebuild.running) "Rebuilding…" else "Rebuild analytics")
            }
            Text(if (rebuild.running) rebuild.detail else rebuild.lastResult.orEmpty(), style = Type.bodySmall, color = dim)
        }
        if (rebuild.running) LinearProgressIndicator(progress = { rebuild.pct / 100f }, modifier = Modifier.fillMaxWidth())
        Text("Recomputes sleep scores, readiness and cardio load for every day with data. Safe to run again; it does not change your data.",
            style = Type.bodySmall, color = dim)

        SectionBreak()
        SectionHeader("Debug bundle")
        Text("Redacted text with version, self-check, counts, recent AI calls and the last 200 log lines.", style = Type.bodySmall, color = dim)
        TextButton(onClick = {
            scope.launch {
                val t = withContext(Dispatchers.IO) { DebugBundle.build(ctx, checks) }
                clip.setText(AnnotatedString(t)); copied = true
            }
        }) { Text(if (copied) "Copied" else "Copy debug bundle") }

        SectionBreak()
        SectionHeader("Last 20 AI calls")
        Spacer(Modifier.height(Spacing.s))
        if (aiCalls.isEmpty()) Text("None yet", style = Type.bodySmall, color = dim)
        aiCalls.forEach { Text(it, style = Type.bodySmall.copy(fontFamily = FontFamily.Monospace), color = dim, modifier = Modifier.padding(vertical = 2.dp)) }

        SectionBreak()
        SectionHeader("Status")
        Spacer(Modifier.height(Spacing.s))
        Text("App ${DebugBundle.version(ctx)}", style = Type.bodySmall, color = dim)
        val sync = Format.freshness(System.currentTimeMillis(), vm.syncLastMs).text
        Text("Sync: $sync" + (vm.syncStatus?.takeIf { it != "ok" }?.let { " ($it)" } ?: ""), style = Type.bodySmall, color = dim)
        if (vm.dbSizeBytes > 0) Text("Database: %.1f MB".format(vm.dbSizeBytes / 1048576.0), style = Type.bodySmall, color = dim)
    }
}
