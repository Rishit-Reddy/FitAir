package com.fitair.app

import android.content.Context
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit

object SyncPrefs {
    const val FILE = "fitair"
    const val ADDR = "server_addr"
    const val KEY = "server_key"
    const val LAST = "sync_last_ms"
    const val COUNTS = "sync_counts"
    const val STATUS = "sync_status"
}

class SyncRepo(private val context: Context) {
    private val prefs = context.getSharedPreferences(SyncPrefs.FILE, Context.MODE_PRIVATE)
    private val health = HealthRepo(context)

    companion object {
        val TYPES = listOf(
            "heart_rate", "steps", "distance", "total_calories", "resting_hr",
            "hrv", "respiratory_rate", "sleep", "exercise",
        )
        private const val BATCH = 5000
        private val OVERLAP = Duration.ofMinutes(10)

        fun baseUrl(raw: String): String {
            var a = raw.trim().trimEnd('/')
            if (a.isEmpty()) return ""
            if (!a.startsWith("http://") && !a.startsWith("https://")) a = "http://$a"
            return a
        }

        /** GET /health; returns a short human message. Never throws. */
        suspend fun testConnection(addr: String, key: String): String = withContext(Dispatchers.IO) {
            try {
                val base = baseUrl(addr)
                if (base.isEmpty()) return@withContext "Enter a server address"
                val j = JSONObject(request("GET", "$base/health", key, null))
                if (j.optBoolean("ok")) "Connected" else "Server replied, not ok"
            } catch (e: Exception) {
                "Failed: ${e.message ?: e.javaClass.simpleName}"
            }
        }

        /** @throws IOException on any network/HTTP failure. */
        fun request(method: String, url: String, key: String, body: String?): String {
            val t0 = System.currentTimeMillis()
            val shortUrl = url.substringAfter("//").substringAfter("/", "").let { "/$it" }
            val c = URL(url).openConnection() as HttpURLConnection
            try {
                c.requestMethod = method
                c.connectTimeout = 30_000
                c.readTimeout = 30_000
                c.setRequestProperty("X-Api-Key", key)
                c.setRequestProperty("Accept", "application/json")
                if (body != null) {
                    c.doOutput = true
                    c.setRequestProperty("Content-Type", "application/json")
                    c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                }
                val code = c.responseCode
                val stream = if (code in 200..299) c.inputStream else c.errorStream
                val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
                AppLog.d("$method $shortUrl -> $code (${System.currentTimeMillis() - t0} ms, ${text.length} B back)")
                if (code !in 200..299) throw IOException("HTTP $code: ${text.take(200)}")
                return text
            } catch (e: IOException) {
                if (e.message?.startsWith("HTTP ") != true) AppLog.e("$method $shortUrl failed after ${System.currentTimeMillis() - t0} ms", e)
                throw e
            } finally {
                c.disconnect()
            }
        }
    }

    /** Throws IOException for network problems (caller retries), SecurityException if HC access missing. */
    suspend fun syncAll(): Map<String, Int> = withContext(Dispatchers.IO) {
        val base = baseUrl(prefs.getString(SyncPrefs.ADDR, "") ?: "")
        val key = prefs.getString(SyncPrefs.KEY, "") ?: ""
        if (base.isEmpty()) throw IllegalStateException("No server address")
        val now = Instant.now()
        val hist = health.hasHistoryPermission()
        val defaultDays = if (hist) 365L else 30L
        AppLog.d("sync start: server=$base, history permission=$hist, default window=$defaultDays d")
        val sent = LinkedHashMap<String, Int>()
        for (type in TYPES) {
            val wm = JSONObject(request("GET", "$base/watermark?type=${URLEncoder.encode(type, "UTF-8")}", key, null))
            AppLog.d("[$type] server watermark=${if (wm.isNull("maxT")) "none" else wm.getLong("maxT")}")
            val from = if (wm.isNull("maxT")) now.minus(Duration.ofDays(defaultDays))
            else Instant.ofEpochMilli(wm.getLong("maxT")).minus(OVERLAP)
            var n = 0
            val buf = ArrayList<JSONObject>()
            suspend fun flush() {
                if (buf.isEmpty()) return
                val arr = JSONArray()
                buf.forEach { arr.put(it) }
                val body = JSONObject().put("type", type).put("records", arr).toString()
                AppLog.d("[$type] POST ${buf.size} records (${body.length / 1024} KB)")
                request("POST", "$base/ingest", key, body)
                n += buf.size
                buf.clear()
            }
            var pages = 0
            health.readForSync(type, from, now) { page ->
                pages++
                if (pages == 1 || pages % 10 == 0) AppLog.d("[$type] read page $pages (${page.size} items) from Health Connect")
                for (o in page) {
                    buf.add(o)
                    if (buf.size >= BATCH) flush()
                }
            }
            flush()
            AppLog.d("[$type] done: $n sent over $pages pages")
            sent[type] = n
        }
        prefs.edit()
            .putLong(SyncPrefs.LAST, System.currentTimeMillis())
            .putString(SyncPrefs.COUNTS, JSONObject(sent as Map<*, *>).toString())
            .apply()
        AppLog.d("sync finished OK")
        sent
    }
}

class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        AppLog.init(applicationContext)
        AppLog.d("worker started (attempt $runAttemptCount)")
        val prefs = applicationContext.getSharedPreferences(SyncPrefs.FILE, Context.MODE_PRIVATE)
        fun status(s: String) = prefs.edit().putString(SyncPrefs.STATUS, s).apply()
        return try {
            SyncRepo(applicationContext).syncAll()
            status("ok")
            Result.success()
        } catch (e: IllegalStateException) {
            AppLog.e("worker: not configured", e)
            status("Not configured"); Result.success()
        } catch (e: SecurityException) {
            AppLog.e("worker: Health Connect permission", e)
            status("Health Connect permission missing"); Result.failure()
        } catch (e: Exception) {
            AppLog.e("worker failed, will retry", e)
            status("Retrying: ${e.message ?: e.javaClass.simpleName}"); Result.retry()
        }
    }
}

object SyncScheduler {
    private const val PERIODIC = "fitair-sync-periodic"
    private const val NOW = "fitair-sync-now"
    private val net = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun schedulePeriodic(ctx: Context) {
        val req = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES).setConstraints(net).build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, req)
    }

    fun syncNow(ctx: Context) {
        val req = OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(net).build()
        WorkManager.getInstance(ctx).enqueueUniqueWork(NOW, ExistingWorkPolicy.REPLACE, req)
    }

    fun nowFlow(ctx: Context) = WorkManager.getInstance(ctx).getWorkInfosForUniqueWorkFlow(NOW)
}
