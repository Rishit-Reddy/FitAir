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
                if (code !in 200..299) throw IOException("HTTP $code")
                return text
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
        val defaultDays = if (health.hasHistoryPermission()) 365L else 30L
        val sent = LinkedHashMap<String, Int>()
        for (type in TYPES) {
            val wm = JSONObject(request("GET", "$base/watermark?type=${URLEncoder.encode(type, "UTF-8")}", key, null))
            val from = if (wm.isNull("maxT")) now.minus(Duration.ofDays(defaultDays))
            else Instant.ofEpochMilli(wm.getLong("maxT")).minus(OVERLAP)
            var n = 0
            val buf = ArrayList<JSONObject>()
            suspend fun flush() {
                if (buf.isEmpty()) return
                val arr = JSONArray()
                buf.forEach { arr.put(it) }
                val body = JSONObject().put("type", type).put("records", arr).toString()
                request("POST", "$base/ingest", key, body)
                n += buf.size
                buf.clear()
            }
            health.readForSync(type, from, now) { page ->
                for (o in page) {
                    buf.add(o)
                    if (buf.size >= BATCH) flush()
                }
            }
            flush()
            sent[type] = n
        }
        prefs.edit()
            .putLong(SyncPrefs.LAST, System.currentTimeMillis())
            .putString(SyncPrefs.COUNTS, JSONObject(sent as Map<*, *>).toString())
            .apply()
        sent
    }
}

class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val prefs = applicationContext.getSharedPreferences(SyncPrefs.FILE, Context.MODE_PRIVATE)
        fun status(s: String) = prefs.edit().putString(SyncPrefs.STATUS, s).apply()
        return try {
            SyncRepo(applicationContext).syncAll()
            status("ok")
            Result.success()
        } catch (e: IllegalStateException) {
            status("Not configured"); Result.success()
        } catch (e: SecurityException) {
            status("Health Connect permission missing"); Result.failure()
        } catch (e: Exception) {
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
