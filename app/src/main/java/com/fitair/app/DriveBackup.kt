package com.fitair.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.work.*
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
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

enum class BackupPhase { Idle, Preparing, Compressing, Uploading, Done, Failed }

data class BackupState(val phase: BackupPhase = BackupPhase.Idle, val pct: Int = 0)

/**
 * One WhatsApp-style backup: FitAir-backup.zip (fitair.db snapshot + backup.json) at the root of My Drive,
 * overwritten in place via a resumable upload. Progress is published on [state] (process-wide).
 */
object DriveBackup {
    const val FILE_NAME = "FitAir-backup.zip"
    private const val API = "https://www.googleapis.com/drive/v3/files"
    private const val UPLOAD = "https://www.googleapis.com/upload/drive/v3/files"
    private const val CHUNK = 6 * 1024 * 1024 // multiple of 256 KiB as Drive requires
    private const val MAX_CHUNK_RETRIES = 5
    private val lock = Mutex()

    private val _state = MutableStateFlow(BackupState())
    val state: StateFlow<BackupState> = _state

    fun markQueued() { if (!isRunning()) _state.value = BackupState(BackupPhase.Preparing, 0) }
    private fun isRunning() = _state.value.phase.let { it == BackupPhase.Preparing || it == BackupPhase.Compressing || it == BackupPhase.Uploading }
    private fun set(p: BackupPhase, pct: Int) { _state.value = BackupState(p, pct.coerceIn(0, 100)) }

    enum class Outcome { Ok, Skipped, Retry, Failed }

    /** Best-effort; never throws (except coroutine cancellation). */
    suspend fun run(ctx: Context): Outcome = withContext(Dispatchers.IO) {
        val prefs = ctx.getSharedPreferences(SyncPrefs.FILE, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(DrivePrefs.CONNECTED, false)) {
            AppLog.d("backup: not connected, skipping")
            return@withContext Outcome.Skipped
        }
        if (!lock.tryLock()) { AppLog.d("backup: already running"); return@withContext Outcome.Skipped }
        val snap = File(ctx.cacheDir, "fitair-snapshot.db")
        val zip = File(ctx.cacheDir, "fitair-backup.zip.tmp")
        try {
            val t0 = System.currentTimeMillis()
            set(BackupPhase.Preparing, 0)
            snap.delete(); zip.delete()
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
                    snapshot(ctx, snap)
                    buildZip(ctx, snap, zip)
                    snap.delete()
                    upload(a.token, zip)
                    prefs.edit().putLong(DrivePrefs.LAST, System.currentTimeMillis()).putLong(DrivePrefs.SIZE, zip.length())
                        .putBoolean(DrivePrefs.FAILED, false).apply()
                    set(BackupPhase.Done, 100)
                    AppLog.d("backup: finished OK in ${System.currentTimeMillis() - t0} ms (${zip.length()} B)")
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
            snap.delete(); zip.delete()
            lock.unlock()
        }
    }

    // ---------- snapshot + zip ----------

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

    private fun buildZip(ctx: Context, snap: File, zip: File) {
        set(BackupPhase.Compressing, 0)
        val store = LocalStore.get(ctx)
        val appVersion = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: ""
        val meta = JSONObject().put("version", 2).put("created_ms", System.currentTimeMillis())
            .put("app_version", appVersion).put("db_version", store.db.version)
            .put("row_counts", JSONObject(store.counts() as Map<*, *>)).toString()
        val total = maxOf(snap.length(), 1L)
        var done = 0L
        ZipOutputStream(zip.outputStream().buffered(64 * 1024)).use { z ->
            z.setLevel(Deflater.DEFAULT_COMPRESSION)
            z.putNextEntry(ZipEntry("fitair.db"))
            snap.inputStream().use { ins ->
                val buf = ByteArray(64 * 1024)
                var lastPct = -1
                while (true) {
                    val n = ins.read(buf)
                    if (n < 0) break
                    z.write(buf, 0, n); done += n
                    val p = (done * 100 / total).toInt()
                    if (p != lastPct) { lastPct = p; set(BackupPhase.Compressing, p * 30 / 100) }
                }
            }
            z.closeEntry()
            z.putNextEntry(ZipEntry("backup.json"))
            z.write(meta.toByteArray(Charsets.UTF_8))
            z.closeEntry()
        }
        AppLog.d("backup: zip ready, ${zip.length()} B (db ${snap.length()} B)")
    }

    // ---------- Drive REST ----------

    private class Resp(val code: Int, val location: String?, val range: String?, val body: String)

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun findExisting(token: String): String? {
        val q = "name='$FILE_NAME' and 'root' in parents and trashed=false"
        val r = JSONObject(ok(http("GET", "$API?q=${enc(q)}&fields=${enc("files(id)")}&pageSize=1&orderBy=createdTime", token)).body)
        return r.optJSONArray("files")?.takeIf { it.length() > 0 }?.getJSONObject(0)?.getString("id")
    }

    private suspend fun upload(token: String, file: File) {
        val total = file.length()
        val id = findExisting(token)
        AppLog.d("backup: ${if (id == null) "creating new file" else "updating existing file"}, $total B")
        val meta = JSONObject().put("name", FILE_NAME).toString().toByteArray()
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
        set(BackupPhase.Uploading, 30)
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
                            set(BackupPhase.Uploading, 30 + ((from + sent) * 70 / total).toInt())
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
        set(BackupPhase.Uploading, 100)
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
