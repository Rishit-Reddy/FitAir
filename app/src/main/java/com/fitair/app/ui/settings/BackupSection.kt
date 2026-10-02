package com.fitair.app.ui.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.fitair.app.AppLog
import com.fitair.app.BackupPhase
import com.fitair.app.DriveAuth
import com.fitair.app.DriveBackup
import com.fitair.app.DrivePrefs
import com.fitair.app.DriveScheduler
import com.fitair.app.SyncPrefs
import com.fitair.app.backup.BackupCrypto
import com.google.android.gms.auth.api.identity.Identity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun startBackup(ctx: Context) {
    DriveBackup.markQueued()
    DriveScheduler.backupNow(ctx)
}

/** Self-contained Google Drive backup block: connect, back up now, progress, last backup, restore, optional passphrase. */
@Composable
fun BackupSection() {
    val ctx = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    val prefs = remember { ctx.getSharedPreferences(SyncPrefs.FILE, Context.MODE_PRIVATE) }

    var tick by remember { mutableIntStateOf(0) }
    DisposableEffect(prefs) {
        val l = SharedPreferences.OnSharedPreferenceChangeListener { _, k -> if (k?.startsWith("drive_") == true) tick++ }
        prefs.registerOnSharedPreferenceChangeListener(l)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(l) }
    }
    val connected = remember(tick) { prefs.getBoolean(DrivePrefs.CONNECTED, false) }
    val lastMs = remember(tick) { prefs.getLong(DrivePrefs.LAST, 0L) }
    val sizeB = remember(tick) { prefs.getLong(DrivePrefs.SIZE, 0L) }
    val failedBefore = remember(tick) { prefs.getBoolean(DrivePrefs.FAILED, false) }
    val live by DriveBackup.state.collectAsState()
    val notice by DriveBackup.notice.collectAsState()
    val st = if (live.phase == BackupPhase.Idle && failedBefore) live.copy(phase = BackupPhase.Failed) else live
    val busy = st.phase in listOf(BackupPhase.Preparing, BackupPhase.Compressing, BackupPhase.Uploading, BackupPhase.Downloading, BackupPhase.Restoring)

    var encrypted by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { encrypted = withContext(Dispatchers.IO) { runCatching { BackupCrypto.isEnabled(ctx) }.getOrDefault(false) } }
    var settingUp by remember { mutableStateOf(false) }
    var pass1 by remember { mutableStateOf("") }
    var pass2 by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var formError by remember { mutableStateOf<String?>(null) }
    var restoreOpen by remember { mutableStateOf(false) }
    var restorePass by remember { mutableStateOf("") }

    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        try {
            val res = Identity.getAuthorizationClient(ctx).getAuthorizationResultFromIntent(r.data)
            if (res.accessToken != null) { DriveBackup.onConnected(ctx); startBackup(ctx) } else AppLog.d("drive: consent returned no token")
        } catch (e: Exception) { AppLog.e("drive: consent cancelled/failed", e) }
    }
    fun connect() {
        scope.launch {
            AppLog.d("drive: connect tapped")
            when (val a = DriveAuth.authorize(ctx)) {
                is DriveAuth.NeedsResolution -> consent.launch(IntentSenderRequest.Builder(a.intent).build())
                is DriveAuth.Token -> { DriveBackup.onConnected(ctx); startBackup(ctx) }
                is DriveAuth.Failed -> AppLog.e("drive: connect failed", a.error)
            }
        }
    }
    /** Closed years are frozen; after a passphrase change they must be uploaded once more under the new setting. */
    fun afterEncryptionChange() {
        prefs.edit().remove(DrivePrefs.FROZEN).apply()
        if (connected) startBackup(ctx)
    }

    Column(Modifier.fillMaxWidth()) {
        Text("GOOGLE DRIVE BACKUP", style = MaterialTheme.typography.labelSmall, color = dim)
        Spacer(Modifier.height(8.dp))
        if (!connected) {
            TextButton(onClick = { connect() }) { Text("Connect Google Drive") }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Connected", style = MaterialTheme.typography.bodyMedium, color = dim)
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = { DriveBackup.clearNotice(); startBackup(ctx) }, enabled = !busy) { Text(if (busy) "Working…" else "Back up now") }
                TextButton(onClick = { DriveBackup.clearNotice(); restorePass = ""; restoreOpen = true }, enabled = !busy) { Text("Restore") }
            }
        }
        if (connected && st.phase != BackupPhase.Idle) {
            val failed = st.phase == BackupPhase.Failed
            val label = when (st.phase) {
                BackupPhase.Preparing -> "Preparing"
                BackupPhase.Compressing -> "Compressing"
                BackupPhase.Uploading -> "Uploading"
                BackupPhase.Downloading -> "Downloading"
                BackupPhase.Restoring -> "Restoring"
                BackupPhase.Done -> "Done"
                else -> "Failed"
            } + (if (st.detail.isNotEmpty() && !failed) " · ${st.detail}" else "")
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Row {
                    Text(label, style = MaterialTheme.typography.bodySmall, color = if (failed) MaterialTheme.colorScheme.error else dim, modifier = Modifier.weight(1f))
                    if (!failed) Text("${st.pct}%", style = MaterialTheme.typography.bodySmall, color = dim)
                }
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { if (failed) 0f else st.pct / 100f }, modifier = Modifier.fillMaxWidth(),
                    color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.outlineVariant,
                )
            }
        }
        notice?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = dim, modifier = Modifier.padding(vertical = 2.dp)) }
        val last = if (lastMs > 0)
            java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(java.util.Date(lastMs)) +
                " · %.1f MB".format(sizeB / 1048576.0) else "never"
        Text("Last backup: $last", style = MaterialTheme.typography.bodySmall, color = dim)
        Text("One file per year in your Drive; only the current year is re-uploaded.", style = MaterialTheme.typography.bodySmall, color = dim)

        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Encrypt with a passphrase", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Switch(checked = encrypted || settingUp, enabled = !working && !busy, onCheckedChange = { on ->
                formError = null
                if (on) { settingUp = true; pass1 = ""; pass2 = "" }
                else if (encrypted) {
                    scope.launch(Dispatchers.IO) { BackupCrypto.disable(ctx); encrypted = false; afterEncryptionChange() }
                } else settingUp = false
            })
        }
        if (settingUp && !encrypted) {
            Text(
                "If you forget this passphrase, your backups cannot be recovered. FitAir does not store it and nobody can reset it. " +
                    "Write it down somewhere safe. Restoring always asks for it.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 6.dp),
            )
            OutlinedTextField(pass1, { pass1 = it }, label = { Text("Passphrase (8+ characters)") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
            OutlinedTextField(pass2, { pass2 = it }, label = { Text("Repeat passphrase") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
            formError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            Row {
                TextButton(enabled = !working, onClick = {
                    when {
                        pass1.length < 8 -> formError = "Use at least 8 characters"
                        pass1 != pass2 -> formError = "The two entries differ"
                        else -> {
                            working = true; formError = null
                            val p = pass1
                            scope.launch(Dispatchers.Default) {
                                try {
                                    BackupCrypto.enable(ctx, p)
                                    encrypted = true; settingUp = false; pass1 = ""; pass2 = ""
                                    afterEncryptionChange()
                                } catch (e: Exception) { AppLog.e("backup: enable passphrase failed", e); formError = "Could not enable: ${e.message}" }
                                working = false
                            }
                        }
                    }
                }) { Text(if (working) "Working…" else "Turn on") }
                TextButton(enabled = !working, onClick = { settingUp = false; pass1 = ""; pass2 = ""; formError = null }) { Text("Cancel") }
            }
        } else if (encrypted) {
            Text(
                "New backups are encrypted (AES-256-GCM). Forgotten passphrases cannot be recovered. Turning this on or off re-uploads closed years once.",
                style = MaterialTheme.typography.bodySmall, color = dim,
            )
        }
    }

    if (restoreOpen) {
        AlertDialog(
            onDismissRequest = { restoreOpen = false },
            title = { Text("Restore from Google Drive?") },
            text = {
                Column {
                    Text("This reads every FitAir backup file in your Drive and merges it into this phone. Rows with the same key are replaced by the backup's version; nothing is deleted. It can take a while on a large backup.")
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(restorePass, { restorePass = it }, label = { Text("Passphrase (if encrypted)") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = { TextButton(onClick = { restoreOpen = false; DriveBackup.restoreAsync(ctx, restorePass); restorePass = "" }) { Text("Restore") } },
            dismissButton = { TextButton(onClick = { restoreOpen = false }) { Text("Cancel") } },
        )
    }
}
