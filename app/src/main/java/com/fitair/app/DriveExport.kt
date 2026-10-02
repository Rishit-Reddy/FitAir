package com.fitair.app

import android.app.PendingIntent
import android.content.Context
import androidx.work.*
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.time.LocalDate
import java.util.TimeZone
import java.util.UUID
import java.util.zip.GZIPOutputStream
import kotlin.coroutines.resume

/** Prefs keys (file SyncPrefs.FILE) used by the Drive export. */
object DrivePrefs {
    const val CONNECTED = "drive_connected"
    const val STATUS = "drive_status"
    const val LAST = "drive_last_ms"
    const val FILES = "drive_files"
    const val UPLOADS = "drive_uploads"
    const val ERROR = "drive_error"
    const val ATTEMPT = "drive_attempt_ms"
    const val FULL = "drive_full_ms"
    const val MAX_DAY = "drive_max_day"
    const val MANIFEST = "drive_manifest"
    const val HASH_PREFIX = "dh_"
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

object DriveExport {
    private const val DAY = 86_400_000L
    private const val MIN_INTERVAL = 30 * 60_000L
    private const val FULL_INTERVAL = 24 * 3_600_000L
    private const val API = "https://www.googleapis.com/drive/v3/files"
    private const val UPLOAD = "https://www.googleapis.com/upload/drive/v3/files"
    private const val FOLDER_MIME = "application/vnd.google-apps.folder"
    private const val MANIFEST = "manifest.json"
    private val lock = Mutex()

    private class Spec(val type: String, val timeCol: String, val cols: List<String>, val order: String)

    private val SPECS = listOf(
        Spec("heart_rate", "t", listOf("t", "bpm", "origin"), "t,origin"),
        Spec("steps", "start_ms", listOf("start_ms", "end_ms", "count", "origin"), "start_ms,end_ms,origin"),
        Spec("distance", "start_ms", listOf("start_ms", "end_ms", "meters", "origin"), "start_ms,end_ms,origin"),
        Spec("total_calories", "start_ms", listOf("start_ms", "end_ms", "kcal", "origin"), "start_ms,end_ms,origin"),
        Spec("resting_hr", "t", listOf("t", "bpm", "origin"), "t,origin"),
        Spec("hrv", "t", listOf("t", "rmssd", "origin"), "t,origin"),
        Spec("respiratory_rate", "t", listOf("t", "rate", "origin"), "t,origin"),
        Spec("sleep", "start_ms", listOf("start_ms", "end_ms", "origin"), "start_ms,origin"),
        Spec("exercise", "start_ms", listOf("start_ms", "end_ms", "type", "title", "origin"), "start_ms,end_ms,origin"),
    )

    /** Best-effort; never throws (except coroutine cancellation). [force] skips the 30 min throttle and rescans all days. */
    suspend fun run(ctx: Context, force: Boolean) = withContext(Dispatchers.IO) {
        val prefs = ctx.getSharedPreferences(SyncPrefs.FILE, Context.MODE_PRIVATE)
        fun status(s: String) = prefs.edit().putString(DrivePrefs.STATUS, s).apply()
        val now = System.currentTimeMillis()
        if (!prefs.getBoolean(DrivePrefs.CONNECTED, false)) {
            AppLog.d("drive: not connected, skipping"); return@withContext
        }
        if (!force && now - prefs.getLong(DrivePrefs.ATTEMPT, 0L) < MIN_INTERVAL) {
            AppLog.d("drive: last attempt <30 min ago, skipping"); return@withContext
        }
        if (!lock.tryLock()) { AppLog.d("drive: export already running"); return@withContext }
        try {
            prefs.edit().putLong(DrivePrefs.ATTEMPT, now).apply()
            status("Drive: exporting")
            val t0 = System.currentTimeMillis()
            when (val a = DriveAuth.authorize(ctx)) {
                is DriveAuth.NeedsResolution -> {
                    AppLog.d("drive: authorization needs user resolution, skipping")
                    prefs.edit().putBoolean(DrivePrefs.CONNECTED, false)
                        .putString(DrivePrefs.STATUS, "Drive: needs sign-in").apply()
                }
                is DriveAuth.Failed -> {
                    AppLog.e("drive: authorize failed", a.error)
                    prefs.edit().putString(DrivePrefs.STATUS, "Drive: error")
                        .putString(DrivePrefs.ERROR, oneLine(a.error)).apply()
                }
                is DriveAuth.Token -> {
                    try {
                        val uploads = pass(ctx, prefs, a.token, force)
                        AppLog.d("drive: finished OK in ${System.currentTimeMillis() - t0} ms, $uploads uploads")
                    } catch (e: DriveAuthException) {
                        AppLog.e("drive: token rejected (401)", e)
                        prefs.edit().putBoolean(DrivePrefs.CONNECTED, false)
                            .putString(DrivePrefs.STATUS, "Drive: needs sign-in").apply()
                    } catch (e: DriveRetryException) {
                        AppLog.e("drive: throttled/server error, will retry later", e)
                        prefs.edit().putString(DrivePrefs.STATUS, "Drive: retry later")
                            .putString(DrivePrefs.ERROR, oneLine(e)).apply()
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        AppLog.e("drive: export failed", e)
                        prefs.edit().putString(DrivePrefs.STATUS, "Drive: error")
                            .putString(DrivePrefs.ERROR, oneLine(e)).apply()
                    }
                }
            }
        } finally {
            lock.unlock()
        }
    }

    private fun oneLine(e: Throwable) =
        ((e.message ?: e.javaClass.simpleName).replace('\n', ' ')).take(160)

    private fun pass(ctx: Context, prefs: android.content.SharedPreferences, token: String, force: Boolean): Int {
        val db = LocalStore.get(ctx).db
        val now = System.currentTimeMillis()
        val folder = findOrCreateFolder(token)
        AppLog.d("drive: folder id=$folder")
        val remote = listFolder(token, folder)
        AppLog.d("drive: ${remote.size} files already in folder")

        val firstRun = !prefs.contains(DrivePrefs.MAX_DAY)
        val full = force || firstRun || now - prefs.getLong(DrivePrefs.FULL, 0L) > FULL_INTERVAL
        val from = if (full) Long.MIN_VALUE else (prefs.getLong(DrivePrefs.MAX_DAY, 0L) - 2) * DAY
        AppLog.d("drive: ${if (full) "full" else "incremental"} pass")

        val manifest = runCatching { JSONObject(prefs.getString(DrivePrefs.MANIFEST, null) ?: "{}") }.getOrDefault(JSONObject())
        var uploads = 0
        var maxDay = Long.MIN_VALUE
        val tmpDir = File(ctx.cacheDir, "drive").also { it.mkdirs() }

        for (spec in SPECS) {
            val tType = System.currentTimeMillis()
            var up = 0; var skipped = 0
            val days = ArrayList<Long>()
            db.rawQuery("SELECT DISTINCT ${spec.timeCol}/$DAY FROM ${spec.type} WHERE ${spec.timeCol}>=? ORDER BY 1",
                arrayOf(from.toString())).use { c -> while (c.moveToNext()) days.add(c.getLong(0)) }
            for (day in days) {
                val name = "${spec.type}_${LocalDate.ofEpochDay(day)}.jsonl.gz"
                val tmp = File(tmpDir, name)
                try {
                    val (rows, maxMs, hash) = writeDay(db, spec, day, tmp)
                    if (rows == 0) continue
                    maxDay = maxOf(maxDay, day)
                    manifest.put(name, JSONObject().put("rows", rows).put("max_ms", maxMs))
                    val hk = DrivePrefs.HASH_PREFIX + name
                    if (prefs.getString(hk, null) == hash && remote.containsKey(name)) { skipped++; continue }
                    val t1 = System.currentTimeMillis()
                    val id = upload(token, remote[name], folder, name, "application/gzip", tmp)
                    remote[name] = id
                    prefs.edit().putString(hk, hash).apply()
                    up++; uploads++
                    AppLog.d("drive: uploaded $name ($rows rows, ${tmp.length()} B) in ${System.currentTimeMillis() - t1} ms")
                } finally {
                    tmp.delete()
                }
            }
            AppLog.d("drive: [${spec.type}] ${days.size} days: $up uploaded, $skipped unchanged (skipped) in ${System.currentTimeMillis() - tType} ms")
        }

        // manifest last; only rewritten when the files map changed
        val sorted = JSONObject()
        manifest.keys().asSequence().sorted().forEach { sorted.put(it, manifest.get(it)) }
        val filesJson = sorted.toString()
        val mh = sha256Hex(filesJson.toByteArray())
        val mhk = DrivePrefs.HASH_PREFIX + MANIFEST
        if (prefs.getString(mhk, null) != mh || !remote.containsKey(MANIFEST)) {
            val body = JSONObject().put("version", 1).put("exported_at", now)
                .put("tz", TimeZone.getDefault().id).put("files", sorted).toString()
            val mf = File(tmpDir, MANIFEST)
            try {
                mf.writeText(body)
                upload(token, remote[MANIFEST], folder, MANIFEST, "application/json", mf)
                prefs.edit().putString(mhk, mh).apply()
                uploads++
                AppLog.d("drive: uploaded $MANIFEST (${sorted.length()} files)")
            } finally { mf.delete() }
        } else AppLog.d("drive: $MANIFEST unchanged (skipped)")

        val ed = prefs.edit()
            .putString(DrivePrefs.MANIFEST, manifest.toString())
            .putLong(DrivePrefs.LAST, System.currentTimeMillis())
            .putInt(DrivePrefs.FILES, manifest.length())
            .putInt(DrivePrefs.UPLOADS, uploads)
            .putString(DrivePrefs.STATUS, "ok")
            .remove(DrivePrefs.ERROR)
        if (maxDay != Long.MIN_VALUE) ed.putLong(DrivePrefs.MAX_DAY, maxOf(maxDay, prefs.getLong(DrivePrefs.MAX_DAY, Long.MIN_VALUE)))
        else if (firstRun) ed.putLong(DrivePrefs.MAX_DAY, now / DAY)
        if (full) ed.putLong(DrivePrefs.FULL, now)
        ed.apply()
        return uploads
    }

    private data class DayResult(val rows: Int, val maxMs: Long, val hash: String)

    /** Streams one day of one type into [out] as gzip JSONL; hash is SHA-256 of the uncompressed content. */
    private fun writeDay(db: android.database.sqlite.SQLiteDatabase, spec: Spec, day: Long, out: File): DayResult {
        val md = MessageDigest.getInstance("SHA-256")
        var rows = 0
        var maxMs = 0L
        val endCol = if ("end_ms" in spec.cols) "end_ms" else spec.timeCol
        val sql = "SELECT ${spec.cols.joinToString(",")} FROM ${spec.type} WHERE ${spec.timeCol}>=? AND ${spec.timeCol}<? ORDER BY ${spec.order}"
        GZIPOutputStream(out.outputStream().buffered(64 * 1024)).use { gz ->
            db.rawQuery(sql, arrayOf((day * DAY).toString(), ((day + 1) * DAY).toString())).use { c ->
                val endIdx = c.getColumnIndexOrThrow(endCol)
                while (c.moveToNext()) {
                    val o = JSONObject()
                    for (i in spec.cols.indices) {
                        when (c.getType(i)) {
                            android.database.Cursor.FIELD_TYPE_INTEGER -> o.put(spec.cols[i], c.getLong(i))
                            android.database.Cursor.FIELD_TYPE_FLOAT -> o.put(spec.cols[i], c.getDouble(i))
                            else -> o.put(spec.cols[i], c.getString(i) ?: "")
                        }
                    }
                    if (spec.type == "sleep") {
                        val arr = JSONArray()
                        db.rawQuery("SELECT start_ms,end_ms,stage FROM sleep_stage WHERE sleep_start_ms=? AND origin=? ORDER BY start_ms",
                            arrayOf(c.getLong(0).toString(), c.getString(2))).use { sc ->
                            while (sc.moveToNext()) arr.put(JSONObject().put("start_ms", sc.getLong(0)).put("end_ms", sc.getLong(1)).put("stage", sc.getInt(2)))
                        }
                        o.put("stages", arr)
                    }
                    val bytes = (o.toString() + "\n").toByteArray(Charsets.UTF_8)
                    md.update(bytes)
                    gz.write(bytes)
                    rows++
                    maxMs = maxOf(maxMs, c.getLong(endIdx))
                }
            }
        }
        return DayResult(rows, maxMs, md.digest().joinToString("") { "%02x".format(it) })
    }

    private fun sha256Hex(b: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    // ---------- Drive REST ----------

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun findOrCreateFolder(token: String): String {
        val q = "name='FitAir' and mimeType='$FOLDER_MIME' and trashed=false"
        val r = JSONObject(call("GET", "$API?q=${enc(q)}&fields=${enc("files(id,name)")}&pageSize=10&orderBy=createdTime", token))
        val files = r.optJSONArray("files")
        if (files != null && files.length() > 0) return files.getJSONObject(0).getString("id")
        val meta = JSONObject().put("name", "FitAir").put("mimeType", FOLDER_MIME).toString().toByteArray()
        val c = JSONObject(call("POST", "$API?fields=id", token, "application/json; charset=UTF-8", meta.size.toLong()) { it.write(meta) })
        AppLog.d("drive: created folder FitAir")
        return c.getString("id")
    }

    private fun listFolder(token: String, folder: String): MutableMap<String, String> {
        val out = HashMap<String, String>()
        var page: String? = null
        do {
            val q = "'$folder' in parents and trashed=false"
            var url = "$API?q=${enc(q)}&fields=${enc("nextPageToken,files(id,name)")}&pageSize=1000"
            if (page != null) url += "&pageToken=${enc(page)}"
            val r = JSONObject(call("GET", url, token))
            val arr = r.optJSONArray("files")
            if (arr != null) for (i in 0 until arr.length()) {
                val f = arr.getJSONObject(i)
                out.putIfAbsent(f.getString("name"), f.getString("id"))
            }
            page = r.optString("nextPageToken", "").ifEmpty { null }
        } while (page != null)
        return out
    }

    /** Multipart create (no [id]) or PATCH update. Returns the file id. */
    private fun upload(token: String, id: String?, folder: String, name: String, mime: String, file: File): String {
        val boundary = "fitair" + UUID.randomUUID().toString().replace("-", "")
        val meta = JSONObject().put("name", name)
        if (id == null) meta.put("parents", JSONArray().put(folder))
        val head = ("--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n$meta\r\n" +
            "--$boundary\r\nContent-Type: $mime\r\n\r\n").toByteArray(Charsets.UTF_8)
        val tail = "\r\n--$boundary--".toByteArray(Charsets.UTF_8)
        val len = head.size + file.length() + tail.size
        val url = if (id == null) "$UPLOAD?uploadType=multipart&fields=id" else "$UPLOAD/$id?uploadType=multipart&fields=id"
        val resp = call(if (id == null) "POST" else "PATCH", url, token, "multipart/related; boundary=$boundary", len) { os ->
            os.write(head)
            FileInputStream(file).use { it.copyTo(os, 64 * 1024) }
            os.write(tail)
        }
        return JSONObject(resp).getString("id")
    }

    private fun call(
        method: String, url: String, token: String,
        contentType: String? = null, bodyLen: Long = 0, body: ((OutputStream) -> Unit)? = null,
    ): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            // HttpURLConnection on Android can reject PATCH; Google APIs accept POST + override header.
            if (method == "PATCH") {
                conn.requestMethod = "POST"
                conn.setRequestProperty("X-HTTP-Method-Override", "PATCH")
            } else {
                conn.requestMethod = method
            }
            conn.connectTimeout = 20_000
            conn.readTimeout = 60_000
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.setRequestProperty("Accept", "application/json")
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", contentType)
                conn.setFixedLengthStreamingMode(bodyLen)
                conn.outputStream.use { body(it) }
            }
            val code = conn.responseCode
            if (code in 200..299) return conn.inputStream.bufferedReader().use { it.readText() }
            val err = runCatching { conn.errorStream?.bufferedReader()?.use { it.readText() } }.getOrNull().orEmpty()
            val short = err.replace(Regex("\\s+"), " ").take(160)
            when {
                code == 401 -> throw DriveAuthException()
                code == 429 || code >= 500 -> throw DriveRetryException("HTTP $code")
                code == 403 && (err.contains("ateLimit") || err.contains("quotaExceeded")) -> throw DriveRetryException("HTTP 403 rate limit")
                else -> throw IOException("HTTP $code $short")
            }
        } finally {
            conn.disconnect()
        }
    }
}

class DriveExportWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        AppLog.init(applicationContext)
        AppLog.d("drive: manual export started")
        DriveExport.run(applicationContext, force = true)
        return Result.success()
    }
}

object DriveScheduler {
    private const val NOW = "fitair-drive-now"
    fun exportNow(ctx: Context) {
        val req = OneTimeWorkRequestBuilder<DriveExportWorker>().build()
        WorkManager.getInstance(ctx).enqueueUniqueWork(NOW, ExistingWorkPolicy.REPLACE, req)
    }
    fun nowFlow(ctx: Context) = WorkManager.getInstance(ctx).getWorkInfosForUniqueWorkFlow(NOW)
}
