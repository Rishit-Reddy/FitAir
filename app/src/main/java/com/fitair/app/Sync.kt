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
            "steps", "distance", "total_calories", "resting_hr",
            "hrv", "respiratory_rate", "sleep", "exercise", "heart_rate",
        )
        private const val CHUNK = 86_400_000L
        private const val WIN = 5 * 60_000L
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
        val emptyBefore = listOf("sleep", "heart_rate", "steps").all { store.maxT(it) == null }
        try { store.migrateHeartRate() } catch (e: Exception) { AppLog.e("heart-rate migration failed (will resume next sync)", e) }
        for (type in TYPES) {
            val tType = System.currentTimeMillis()
            try {
                if (type == "heart_rate") {
                    written[type] = syncHeartRate(now, defaultDays)
                    continue
                }
                val wm = store.maxT(type)
                AppLog.d("[$type] local maxT=${wm ?: "none"}")
                val isSession = type == "sleep" || type == "exercise"
                val from = if (isSession) SyncWindow.sessionFrom(wm, now, defaultDays, OVERLAP) else SyncWindow.from(wm, now, defaultDays, OVERLAP)
                val seenSleep = HashSet<Pair<Long, String>>()
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
                        if (type == "sleep") seenSleep.add(o.getLong("start") to o.optString("origin", ""))
                        buf.add(o)
                        if (buf.size >= BATCH) flush()
                    }
                }
                flush()
                if (type == "sleep") {
                    val gone = store.pruneSleep(from.toEpochMilli(), seenSleep)
                    AppLog.d("[sleep] Health Connect returned ${seenSleep.size} session(s) since the re-read window start" + if (gone > 0) "; removed $gone replaced session(s)" else "")
                }
                AppLog.d("[$type] done: $n rows over $pages pages in ${System.currentTimeMillis() - tType} ms")
                written[type] = n
            } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (e: SecurityException) {
                AppLog.e("[$type] failed after ${System.currentTimeMillis() - tType} ms", e)
                throw e
            } catch (e: Exception) {
                // one broken type must not stop sleep and heart rate behind it; the next sync tries it again
                AppLog.e("[$type] failed after ${System.currentTimeMillis() - tType} ms (continuing with the other types)", e)
                prefs.edit().putString(SyncPrefs.STATUS, "partial: $type failed").apply()
            }
        }
        try { backfillWorkoutHr(now) } catch (e: Exception) { AppLog.e("workout HR backfill failed", e) }
        // water: import drinks from other apps and flush ours to Health Connect (optional permissions; silently skipped without them)
        try { com.fitair.app.notify.WaterAlarm.importHealthConnect(context) } catch (e: kotlinx.coroutines.CancellationException) { throw e
        } catch (e: Throwable) { AppLog.d("water import skipped: ${e.javaClass.simpleName}: ${e.message}") }
        try { com.fitair.app.notify.WaterAlarm.flushHealthConnect(context) } catch (e: kotlinx.coroutines.CancellationException) { throw e
        } catch (e: Throwable) { AppLog.d("water flush skipped: ${e.javaClass.simpleName}: ${e.message}") }
        try { com.fitair.app.data.WeightSync.sync(context) } catch (e: kotlinx.coroutines.CancellationException) { throw e
        } catch (e: Throwable) { AppLog.d("weight sync skipped: ${e.javaClass.simpleName}: ${e.message}") }
        val tA = System.currentTimeMillis()
        try {
            DailyMetrics.recomputeRecent(context, 14)
            AppLog.d("daily metrics recomputed in ${System.currentTimeMillis() - tA} ms")
        } catch (e: kotlinx.coroutines.CancellationException) { throw e
        } catch (e: Throwable) { AppLog.e("daily metrics failed (ignored) after ${System.currentTimeMillis() - tA} ms", e) }
        val counts = store.counts()
        prefs.edit()
            .putLong(SyncPrefs.LAST, System.currentTimeMillis())
            .putString(SyncPrefs.COUNTS, JSONObject(counts as Map<*, *>).toString())
            .apply()
        // Only now (after the first ingest) may the one-time v3 recompute run; on a fresh database rebuild everything once.
        try {
            val gotData = listOf("sleep", "heart_rate", "steps").any { store.maxT(it) != null }
            if (emptyBefore && gotData) com.fitair.app.data.Rebuild.start(context)
            else DailyMetrics.migrateIfNeeded(context)
        } catch (e: kotlinx.coroutines.CancellationException) { throw e
        } catch (e: Throwable) { AppLog.e("post-sync migration failed (ignored)", e) }
        try { com.fitair.app.notify.WaterAlarm.reschedule(context) } catch (e: Throwable) { AppLog.d("water reschedule failed: ${e.message}") }
        com.fitair.app.notify.WakeNotice.afterSync(context)
        AppLog.d("sync finished OK in ${System.currentTimeMillis() - t0} ms, wrote ${written.values.sum()} rows")
        written
    }

    /**
     * Reads heart rate in 1-day chunks aligned to 30 s. Each chunk is read whole (samples filtered to the
     * chunk), so 30 s buckets are replaced wholesale and overlap with earlier reads is always correct.
     * Raw samples are kept only inside exercise windows.
     */
    private suspend fun syncHeartRate(now: Instant, defaultDays: Long): Int {
        val wm = store.maxT("heart_rate")
        AppLog.d("[heart_rate] hr_30s maxT=${wm ?: "none"}")
        val start = if (wm == null) now.minus(Duration.ofDays(defaultDays)).toEpochMilli() else wm - OVERLAP.toMillis()
        val end = now.toEpochMilli() + 1
        var cs = start - start % 30000
        var n = 0
        while (cs < end) {
            val ce = minOf(cs + CHUNK, end - end % 30000 + 30000)
            val wins = store.exerciseWindows(cs, ce)
            val samples = ArrayList<JSONObject>()
            health.readForSync("heart_rate", Instant.ofEpochMilli(cs), Instant.ofEpochMilli(ce)) { page -> samples.addAll(page) }
            store.ingestHeartRate(samples, cs, ce, wins)
            n += samples.size
            cs = ce
        }
        AppLog.d("[heart_rate] processed $n samples into hr_30s")
        return n
    }

    /** Exercise sessions (last 30 d) lacking raw HR: re-read their window and keep raw samples. */
    private suspend fun backfillWorkoutHr(now: Instant) {
        for (w in store.exerciseSince(now.minus(Duration.ofDays(30)).toEpochMilli())) {
            val a = w[0] - WIN; val b = w[1] + WIN
            if (store.hasRawHr(a, b) || !store.hasHr30(a, b)) continue
            val samples = ArrayList<JSONObject>()
            health.readForSync("heart_rate", Instant.ofEpochMilli(a), Instant.ofEpochMilli(b)) { page -> samples.addAll(page) }
            val raw = samples.filter { it.getLong("t") in a..b }
            store.upsert("heart_rate", raw)
            AppLog.d("backfilled ${raw.size} raw HR samples for workout at ${w[0]}")
        }
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
