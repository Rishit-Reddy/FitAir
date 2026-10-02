package com.fitair.app

import android.content.Context
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit

object SyncPrefs {
    const val FILE = "fitair"
    const val LAST = "sync_last_ms"
    const val COUNTS = "sync_counts"
    const val STATUS = "sync_status"
}

class SyncRepo(private val context: Context) {
    private val prefs = context.getSharedPreferences(SyncPrefs.FILE, Context.MODE_PRIVATE)
    private val health = HealthRepo(context)
    private val store = LocalStore.get(context)

    companion object {
        val TYPES = listOf(
            "heart_rate", "steps", "distance", "total_calories", "resting_hr",
            "hrv", "respiratory_rate", "sleep", "exercise",
        )
        private const val BATCH = 5000
        private val OVERLAP = Duration.ofMinutes(10)
    }

    /** Reads Health Connect into the local DB. Throws SecurityException if HC access is missing. */
    suspend fun syncAll(): Map<String, Int> = withContext(Dispatchers.IO) {
        val t0 = System.currentTimeMillis()
        val now = Instant.now()
        val hist = health.hasHistoryPermission()
        val defaultDays = if (hist) 365L else 30L
        AppLog.d("sync start: history permission=$hist, default window=$defaultDays d, types=${TYPES.size}")
        val written = LinkedHashMap<String, Int>()
        for (type in TYPES) {
            val tType = System.currentTimeMillis()
            try {
                val wm = store.maxT(type)
                AppLog.d("[$type] local maxT=${wm ?: "none"}")
                val from = if (wm == null) now.minus(Duration.ofDays(defaultDays))
                else Instant.ofEpochMilli(wm).minus(OVERLAP)
                var n = 0
                val buf = ArrayList<JSONObject>()
                fun flush() {
                    if (buf.isEmpty()) return
                    store.upsert(type, buf)
                    n += buf.size
                    AppLog.d("[$type] wrote ${buf.size} rows (total $n)")
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
                AppLog.d("[$type] done: $n rows over $pages pages in ${System.currentTimeMillis() - tType} ms")
                written[type] = n
            } catch (e: Exception) {
                AppLog.e("[$type] failed after ${System.currentTimeMillis() - tType} ms", e)
                throw e
            }
        }
        val counts = store.counts()
        prefs.edit()
            .putLong(SyncPrefs.LAST, System.currentTimeMillis())
            .putString(SyncPrefs.COUNTS, JSONObject(counts as Map<*, *>).toString())
            .apply()
        AppLog.d("sync finished OK in ${System.currentTimeMillis() - t0} ms, wrote ${written.values.sum()} rows")
        written
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
    private val net = Constraints.NONE

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
