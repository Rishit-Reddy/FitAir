package com.fitair.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import androidx.core.app.NotificationCompat
import com.fitair.app.backup.BackupCrypto
import com.fitair.app.backup.BackupFiles
import com.fitair.app.backup.NeedsPassphraseException
import com.fitair.app.backup.WrongPassphraseException
import androidx.work.*
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.coroutines.resume

/** Prefs keys (file SyncPrefs.FILE) used by the Drive backup. */
object DrivePrefs {
    const val CONNECTED = "drive_connected"
    const val LAST = "drive_last_ms"
    const val SIZE = "drive_size_bytes"
    const val FAILED = "drive_failed"
    /** Comma list of closed years that are uploaded for the last time, e.g. "2024,2025". */
    const val FROZEN = "drive_frozen_years"
    /** "<year>:<bytes>,..." of the last upload per year. */
    const val SIZES = "drive_year_sizes"
}

sealed class DriveAuth {
    data class Token(val token: String) : DriveAuth()
    data class NeedsResolution(val intent: PendingIntent) : DriveAuth()
    data class Failed(val error: Throwable) : DriveAuth()

    companion object {
        const val SCOPE = "https://www.googleapis.com/auth/drive.file"

        suspend fun authorize(ctx: Context): DriveAuth = suspendCancellableCoroutine { cont ->
            val req = AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(SCOPE))).build()
            Identity.getAuthorizationClient(ctx).authorize(req)
                .addOnSuccessListener { r ->
                    val pi = r.pendingIntent
                    val tok = r.accessToken
                    val out: DriveAuth = when {
                        r.hasResolution() && pi != null -> NeedsResolution(pi)
                        tok != null -> Token(tok)
                        else -> Failed(IllegalStateException("no access token"))
                    }
                    if (cont.isActive) cont.resume(out)
                }
                .addOnFailureListener { e -> if (cont.isActive) cont.resume(Failed(e)) }
        }
    }
}

private class DriveAuthException : IOException("401 unauthorized")
private class DriveRetryException(msg: String) : IOException(msg)

enum class BackupPhase { Idle, Preparing, Compressing, Uploading, Done, Failed, Downloading, Restoring }

/** [pct] is overall progress 0..100; [detail] is a short human hint such as "2025" or "2024 - part 2". */
data class BackupState(val phase: BackupPhase = BackupPhase.Idle, val pct: Int = 0, val detail: String = "")

/**
 * Per-year Drive backups: FitAir-backup-<year>.zip (VACUUM INTO snapshot holding only that year's rows + backup.json), at the root of
 * My Drive. Only the current year is re-uploaded; a closed year is uploaded one last time, then frozen. A year over 200 MB is split
 * into FitAir-backup-<year>-2.zip, ... Optional passphrase encryption (BackupCrypto) wraps each zip. Restore merges every
 * FitAir-backup-*.zip into the live database. Progress is published on [state] (process-wide).
 */
object DriveBackup {
    /** The single-file name used before per-year backups; still listed and restorable. */
    const val LEGACY_NAME = "FitAir-backup.zip"
    private val NAME_RE = Regex("^FitAir-backup(-\\d{4}(-\\d+)?)?\\.zip$")
    private const val API = "https://www.googleapis.com/drive/v3/files"
    private const val UPLOAD = "https://www.googleapis.com/upload/drive/v3/files"
    private const val CHUNK = 6 * 1024 * 1024 // multiple of 256 KiB as Drive requires
    private const val MAX_CHUNK_RETRIES = 5
    private val lock = Mutex()
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(BackupState())
    val state: StateFlow<BackupState> = _state

    /** One-shot message for the UI after a restore or a failure ("Restored 3 files", "Wrong passphrase"). */
    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice
    fun clearNotice() { _notice.value = null }

    fun markQueued() { if (!isRunning()) _state.value = BackupState(BackupPhase.Preparing, 0) }
    private fun isRunning() = _state.value.phase.let {
        it == BackupPhase.Preparing || it == BackupPhase.Compressing || it == BackupPhase.Uploading ||
            it == BackupPhase.Downloading || it == BackupPhase.Restoring
    }
    private fun set(p: BackupPhase, pct: Int, detail: String = "") { _state.value = BackupState(p, pct.coerceIn(0, 100), detail) }

    enum class Outcome { Ok, Skipped, Retry, Failed }

    // ---- connect (moved here from MainViewModel so BackupSection is self-contained) ----

    /** Marks Drive connected and schedules the daily backup. */
    fun onConnected(ctx: Context) {
        val prefs = ctx.getSharedPreferences(SyncPrefs.FILE, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(DrivePrefs.CONNECTED, true).putBoolean(DrivePrefs.FAILED, false).apply()
        DriveScheduler.schedulePeriodic(ctx)
    }

    // ---------- backup ----------

    private fun frozen(prefs: android.content.SharedPreferences): Set<Int> =
        (prefs.getString(DrivePrefs.FROZEN, "") ?: "").split(',').mapNotNull { it.trim().toIntOrNull() }.toSet()

    private fun sizes(prefs: android.content.SharedPreferences): MutableMap<String, Long> =
        (prefs.getString(DrivePrefs.SIZES, "") ?: "").split(',').filter { ':' in it }
            .associate { it.substringBefore(':') to (it.substringAfter(':').toLongOrNull() ?: 0L) }.toMutableMap()

    /** Best-effort; never throws (except coroutine cancellation). */
    suspend fun run(ctx: Context): Outcome = withContext(Dispatchers.IO) {
        val prefs = ctx.getSharedPreferences(SyncPrefs.FILE, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(DrivePrefs.CONNECTED, false)) {
            AppLog.d("backup: not connected, skipping")
            return@withContext Outcome.Skipped
        }
        if (!lock.tryLock()) { AppLog.d("backup: already running"); return@withContext Outcome.Skipped }
        val full = File(ctx.cacheDir, "fitair-snapshot.db")
        try {
            val t0 = System.currentTimeMillis()
            set(BackupPhase.Preparing, 0)
            full.delete()
            when (val a = DriveAuth.authorize(ctx)) {
                is DriveAuth.NeedsResolution -> {
                    AppLog.d("backup: authorization needs user resolution")
                    prefs.edit().putBoolean(DrivePrefs.CONNECTED, false).putBoolean(DrivePrefs.FAILED, true).apply()
                    set(BackupPhase.Failed, 0); Outcome.Failed
                }
                is DriveAuth.Failed -> {
                    AppLog.e("backup: authorize failed", a.error)
                    prefs.edit().putBoolean(DrivePrefs.FAILED, true).apply()
                    set(BackupPhase.Failed, 0); Outcome.Retry
                }
                is DriveAuth.Token -> try {
                    val total = backupAll(ctx, prefs, a.token, full)
                    prefs.edit().putLong(DrivePrefs.LAST, System.currentTimeMillis()).putBoolean(DrivePrefs.FAILED, false).apply()
                    set(BackupPhase.Done, 100)
                    AppLog.d("backup: finished OK in ${System.currentTimeMillis() - t0} ms ($total B uploaded)")
                    Outcome.Ok
                } catch (e: DriveAuthException) {
                    AppLog.e("backup: token rejected (401)", e)
                    prefs.edit().putBoolean(DrivePrefs.CONNECTED, false).putBoolean(DrivePrefs.FAILED, true).apply()
                    set(BackupPhase.Failed, 0); Outcome.Failed
                } catch (e: CancellationException) {
                    set(BackupPhase.Failed, 0); throw e
                } catch (e: Exception) {
                    AppLog.e("backup: failed", e)
                    prefs.edit().putBoolean(DrivePrefs.FAILED, true).apply()
                    set(BackupPhase.Failed, 0)
                    if (e is DriveRetryException || e is IOException) Outcome.Retry else Outcome.Failed
                }
            }
        } finally {
            full.delete()
            lock.unlock()
        }
    }

    /** Snapshot, then per year: build zip part(s), (encrypt,) upload, and freeze closed years. Returns bytes uploaded. */
    private suspend fun backupAll(ctx: Context, prefs: android.content.SharedPreferences, token: String, full: File): Long {
        val zone = java.time.ZoneId.systemDefault()
        val thisYear = java.time.LocalDate.now(zone).year
        snapshot(ctx, full)
        val data = BackupFiles.yearsWithData(full, zone)
        val first = data.minOrNull() ?: thisYear
        val frozen = frozen(prefs).toMutableSet()
        val todo = (first..thisYear).filter { it == thisYear || it !in frozen }
        val key = BackupCrypto.loadKey(ctx).takeIf { BackupCrypto.isEnabled(ctx) }
        val sz = sizes(prefs)
        var uploaded = 0L
        todo.forEachIndexed { yi, year ->
            val base = yi * 100 / todo.size; val span = 100f / todo.size
            val detail = year.toString()
            set(BackupPhase.Compressing, base, detail)
            val parts = BackupFiles.buildYear(ctx, full, year, zone, ctx.cacheDir) { f -> set(BackupPhase.Compressing, base + (span * 0.3f * f).toInt(), detail) }
            try {
                var yearBytes = 0L
                parts.forEachIndexed { pi, part ->
                    val name = if (part.index == 1) "FitAir-backup-$year.zip" else "FitAir-backup-$year-${part.index}.zip"
                    var file = part.zip
                    if (key != null) {
                        val enc = File(ctx.cacheDir, "fitair-backup-enc.tmp")
                        BackupCrypto.encrypt(part.zip, enc, key)
                        part.zip.delete(); file = enc
                    }
                    upload(token, file, name) { f ->
                        val within = (pi + f) / parts.size
                        set(BackupPhase.Uploading, base + (span * (0.3f + 0.7f * within)).toInt(), detail + if (parts.size > 1) " - part ${part.index}" else "")
                    }
                    yearBytes += file.length(); uploaded += file.length()
                    file.delete()
                }
                sz[year.toString()] = yearBytes
                prefs.edit().putString(DrivePrefs.SIZES, sz.entries.joinToString(",") { "${it.key}:${it.value}" }).apply()
                if (year < thisYear) {
                    frozen.add(year)
                    prefs.edit().putString(DrivePrefs.FROZEN, frozen.sorted().joinToString(",")).apply()
                    AppLog.d("backup: year $year frozen")
                }
            } finally {
                parts.forEach { it.zip.delete() }
                File(ctx.cacheDir, "fitair-backup-enc.tmp").delete()
            }
        }
        prefs.edit().putLong(DrivePrefs.SIZE, sz.values.sum()).apply()
        return uploaded
    }

    // ---------- snapshot ----------

    /** Consistent copy of the DB: VACUUM INTO, else WAL checkpoint + file copy. */
    private fun snapshot(ctx: Context, out: File) {
        val store = LocalStore.get(ctx)
        try {
            store.db.execSQL("VACUUM INTO '${out.absolutePath.replace("'", "''")}'")
            AppLog.d("backup: snapshot via VACUUM INTO (${out.length()} B)")
        } catch (e: Exception) {
            AppLog.e("backup: VACUUM INTO failed, falling back to checkpoint + copy", e)
            out.delete()
            store.db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
            ctx.getDatabasePath(LocalStore.FILE).copyTo(out, overwrite = true)
            AppLog.d("backup: snapshot via file copy (${out.length()} B)")
        }
    }

    // ---------- restore ----------

    /** Starts a restore on a process-wide scope (survives leaving the screen). Result arrives via [state] and [notice]. */
    fun restoreAsync(ctx: Context, passphrase: String) {
        val app = ctx.applicationContext
        if (isRunning()) return
        set(BackupPhase.Downloading, 0)
        scope.launch { restore(app, passphrase) }
    }

    private class RemoteFile(val id: String, val name: String, val size: Long)

    private suspend fun restore(ctx: Context, passphrase: String) {
        AppLog.init(ctx)
        if (!lock.tryLock()) { AppLog.d("restore: backup running"); return }
        val dl = File(ctx.cacheDir, "fitair-restore.tmp")
        val dec = File(ctx.cacheDir, "fitair-restore-dec.tmp")
        val snap = File(ctx.cacheDir, "fitair-restore.db")
        try {
            val token = when (val a = DriveAuth.authorize(ctx)) {
                is DriveAuth.Token -> a.token
                else -> { _notice.value = "Reconnect Google Drive first"; set(BackupPhase.Failed, 0); return }
            }
            val files = listBackups(token)
            if (files.isEmpty()) { _notice.value = "No FitAir backups found in Google Drive"; set(BackupPhase.Idle, 0); return }
            var rows = 0L
            files.forEachIndexed { i, f ->
                val base = i * 100 / files.size; val span = 100f / files.size
                val detail = f.name.removePrefix("FitAir-backup").removeSuffix(".zip").trim('-').ifEmpty { "legacy" }
                dl.delete(); dec.delete(); snap.delete()
                download(token, f, dl) { fr -> set(BackupPhase.Downloading, base + (span * 0.7f * fr).toInt(), detail) }
                set(BackupPhase.Restoring, base + (span * 0.7f).toInt(), detail)
                var zip = dl
                if (BackupCrypto.isEncrypted(dl)) {
                    if (passphrase.isEmpty()) throw NeedsPassphraseException()
                    BackupCrypto.decrypt(dl, dec, passphrase); zip = dec
                }
                BackupFiles.extractDb(zip, snap)
                dl.delete(); dec.delete()
                rows += BackupFiles.merge(ctx, snap)
                snap.delete()
                set(BackupPhase.Restoring, base + span.toInt(), detail)
                AppLog.d("restore: merged ${f.name}")
            }
            set(BackupPhase.Done, 100)
            _notice.value = "Restored ${files.size} file${if (files.size == 1) "" else "s"} ($rows rows merged)"
            AppLog.d("restore: done, $rows rows from ${files.size} files")
        } catch (e: CancellationException) { set(BackupPhase.Failed, 0); throw e
        } catch (e: WrongPassphraseException) { _notice.value = "Wrong passphrase"; set(BackupPhase.Failed, 0)
        } catch (e: NeedsPassphraseException) { _notice.value = e.message; set(BackupPhase.Failed, 0)
        } catch (e: DriveAuthException) { _notice.value = "Reconnect Google Drive first"; set(BackupPhase.Failed, 0)
        } catch (e: Exception) {
            AppLog.e("restore: failed", e)
            _notice.value = "Restore failed: ${e.message ?: e.javaClass.simpleName}"
            set(BackupPhase.Failed, 0)
        } finally {
            dl.delete(); dec.delete(); snap.delete()
            lock.unlock()
        }
    }

    private fun listBackups(token: String): List<RemoteFile> {
        val out = ArrayList<RemoteFile>()
        var page: String? = null
        do {
            val q = "trashed=false and mimeType='application/zip'"
            val url = "$API?q=${enc(q)}&fields=${enc("nextPageToken,files(id,name,size)")}&pageSize=100" + (page?.let { "&pageToken=${enc(it)}" } ?: "")
            val r = JSONObject(ok(http("GET", url, token)).body)
            val a = r.optJSONArray("files")
            if (a != null) for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                if (NAME_RE.matches(o.getString("name"))) out.add(RemoteFile(o.getString("id"), o.getString("name"), o.optString("size").toLongOrNull() ?: 0L))
            }
            page = r.optString("nextPageToken").ifEmpty { null }
        } while (page != null)
        // oldest year first; the legacy single file (everything up to its date) goes first so per-year files win on conflicts
        return out.sortedBy { if (it.name == LEGACY_NAME) "" else it.name }
    }

    private fun download(token: String, f: RemoteFile, dst: File, onFrac: (Float) -> Unit) {
        val conn = URL("$API/${f.id}?alt=media").openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 20_000; conn.readTimeout = 60_000
            conn.setRequestProperty("Authorization", "Bearer $token")
            val code = conn.responseCode
            if (code !in 200..299) ok(Resp(code, null, null, runCatching { conn.errorStream?.bufferedReader()?.use { it.readText() } }.getOrNull().orEmpty()))
            val total = if (f.size > 0) f.size else conn.contentLengthLong
            var done = 0L; var last = -1
            conn.inputStream.use { ins ->
                dst.outputStream().buffered(64 * 1024).use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = ins.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n); done += n
                        if (total > 0) { val p = (done * 100 / total).toInt(); if (p != last) { last = p; onFrac(done.toFloat() / total) } }
                    }
                }
            }
            if (total > 0 && done != total) throw IOException("download incomplete ($done of $total B)")
        } finally { conn.disconnect() }
    }

    // ---------- Drive REST ----------

    private class Resp(val code: Int, val location: String?, val range: String?, val body: String)

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun findExisting(token: String, name: String): String? {
        val q = "name='$name' and 'root' in parents and trashed=false"
        val r = JSONObject(ok(http("GET", "$API?q=${enc(q)}&fields=${enc("files(id)")}&pageSize=1&orderBy=createdTime", token)).body)
        return r.optJSONArray("files")?.takeIf { it.length() > 0 }?.getJSONObject(0)?.getString("id")
    }

    private suspend fun upload(token: String, file: File, name: String, onFrac: (Float) -> Unit) {
        val total = file.length()
        val id = findExisting(token, name)
        AppLog.d("backup: $name ${if (id == null) "creating new file" else "updating existing file"}, $total B")
        val meta = JSONObject().put("name", name).toString().toByteArray()
        val url = if (id == null) "$UPLOAD?uploadType=resumable&fields=id" else "$UPLOAD/$id?uploadType=resumable&fields=id"
        val start = ok(http(if (id == null) "POST" else "PATCH", url, token, mapOf(
            "Content-Type" to "application/json; charset=UTF-8",
            "X-Upload-Content-Type" to "application/zip",
            "X-Upload-Content-Length" to total.toString(),
        ), meta.size.toLong()) { it.write(meta) })
        val session = start.location ?: throw IOException("no upload session URL")
        AppLog.d("backup: resumable session started")

        var offset = 0L
        var failures = 0
        onFrac(0f)
        RandomAccessFile(file, "r").use { raf ->
            while (offset < total) {
                val end = minOf(offset + CHUNK, total) // exclusive
                try {
                    val from = offset
                    val r = http("PUT", session, token, mapOf("Content-Range" to "bytes $from-${end - 1}/$total"), end - from) { os ->
                        raf.seek(from)
                        val buf = ByteArray(64 * 1024)
                        var sent = 0L
                        while (sent < end - from) {
                            val n = raf.read(buf, 0, minOf(buf.size.toLong(), end - from - sent).toInt())
                            if (n < 0) throw IOException("unexpected EOF")
                            os.write(buf, 0, n); sent += n
                            onFrac((from + sent).toFloat() / total)
                        }
                    }
                    when {
                        r.code == 200 || r.code == 201 -> offset = total
                        r.code == 308 -> offset = committed(r)
                        else -> { ok(r); offset = total }
                    }
                    failures = 0
                    AppLog.d("backup: uploaded $offset / $total B")
                } catch (e: DriveAuthException) { throw e
                } catch (e: IOException) {
                    if (++failures > MAX_CHUNK_RETRIES) throw e
                    AppLog.e("backup: chunk failed ($failures/$MAX_CHUNK_RETRIES), resuming", e)
                    delay(minOf(1000L shl failures, 30_000L))
                    offset = runCatching {
                        val s = http("PUT", session, token, mapOf("Content-Range" to "bytes */$total"), 0) { }
                        when (s.code) { 200, 201 -> total; 308 -> committed(s); else -> offset }
                    }.getOrDefault(offset)
                }
            }
        }
        onFrac(1f)
    }

    /** Bytes the server has committed, from the Range header ("bytes=0-N"); none means 0. */
    private fun committed(r: Resp): Long =
        r.range?.substringAfter('-', "")?.toLongOrNull()?.plus(1) ?: 0L

    private fun ok(r: Resp): Resp {
        if (r.code in 200..299) return r
        val short = r.body.replace(Regex("\\s+"), " ").take(160)
        when {
            r.code == 401 -> throw DriveAuthException()
            r.code == 429 || r.code >= 500 -> throw DriveRetryException("HTTP ${r.code}")
            r.code == 403 && (r.body.contains("ateLimit") || r.body.contains("quotaExceeded")) -> throw DriveRetryException("HTTP 403 rate limit")
            else -> throw IOException("HTTP ${r.code} $short")
        }
    }

    /** Returns the response for any status; callers decide. 401/429/5xx are mapped by [ok]. */
    private fun http(
        method: String, url: String, token: String,
        headers: Map<String, String> = emptyMap(), bodyLen: Long = -1, body: ((OutputStream) -> Unit)? = null,
    ): Resp {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            // HttpURLConnection on Android can reject PATCH; Google APIs accept POST + override header.
            if (method == "PATCH") {
                conn.requestMethod = "POST"
                conn.setRequestProperty("X-HTTP-Method-Override", "PATCH")
            } else {
                conn.requestMethod = method
            }
            conn.instanceFollowRedirects = false // 308 means "resume incomplete" here, not a redirect
            conn.connectTimeout = 20_000
            conn.readTimeout = 60_000
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.setRequestProperty("Accept", "application/json")
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            if (body != null) {
                conn.doOutput = true
                conn.setFixedLengthStreamingMode(bodyLen)
                conn.outputStream.use { body(it) }
            }
            val code = conn.responseCode
            val text = runCatching {
                (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }
            }.getOrNull().orEmpty()
            return Resp(code, conn.getHeaderField("Location"), conn.getHeaderField("Range"), text)
        } finally {
            conn.disconnect()
        }
    }
}

class DriveBackupWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        AppLog.init(applicationContext)
        AppLog.d("backup: worker started (attempt $runAttemptCount)")
        return try {
            val out = DriveBackup.run(applicationContext)
            if (out == DriveBackup.Outcome.Retry && runAttemptCount < 3 &&
                inputData.getBoolean(DriveScheduler.KEY_MANUAL, false).not()) Result.retry() else Result.success()
        } catch (e: CancellationException) { throw e
        } catch (e: Throwable) {
            AppLog.e("backup: worker crashed (ignored)", e); Result.success()
        }
    }

    /** Only used for expedited work on API < 31. */
    override suspend fun getForegroundInfo(): ForegroundInfo {
        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("backup", "Backup", NotificationManager.IMPORTANCE_LOW))
        val n = NotificationCompat.Builder(applicationContext, "backup")
            .setSmallIcon(android.R.drawable.stat_sys_upload).setContentTitle("Backing up to Google Drive")
            .setOngoing(true).setOnlyAlertOnce(true).build()
        return ForegroundInfo(7001, n)
    }
}

object DriveScheduler {
    private const val NOW = "fitair-drive-backup-now"
    private const val PERIODIC = "fitair-drive-backup-daily"
    const val KEY_MANUAL = "manual"

    /** Manual backup: any network, expedited when quota allows. */
    fun backupNow(ctx: Context) {
        val req = OneTimeWorkRequestBuilder<DriveBackupWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInputData(workDataOf(KEY_MANUAL to true))
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork(NOW, ExistingWorkPolicy.REPLACE, req)
    }

    /** Daily automatic backup: charging, unmetered network, battery not low. */
    fun schedulePeriodic(ctx: Context) {
        val c = Constraints.Builder().setRequiresCharging(true).setRequiredNetworkType(NetworkType.UNMETERED)
            .setRequiresBatteryNotLow(true).build()
        val req = PeriodicWorkRequestBuilder<DriveBackupWorker>(24, TimeUnit.HOURS).setConstraints(c).build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, req)
    }

    fun nowFlow(ctx: Context) = WorkManager.getInstance(ctx).getWorkInfosForUniqueWorkFlow(NOW)
}
