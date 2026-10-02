package com.fitair.app.ui.today

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.fitair.app.AppLog
import com.fitair.app.DailyMetrics
import com.fitair.app.LocalApi
import com.fitair.app.LocalStore
import com.fitair.app.SyncPrefs
import com.fitair.app.SyncScheduler
import com.fitair.app.analytics.ReadinessView
import com.fitair.app.coach.DayFacts
import com.fitair.app.coach.DaySummary
import com.fitair.app.coach.Brief
import com.fitair.app.coach.TodayBrief
import com.fitair.app.data.metrics.MetricId
import com.fitair.app.data.metrics.MetricSnapshot
import com.fitair.app.data.metrics.MetricsRepo
import kotlinx.coroutines.Job
import com.fitair.app.core.Format
import com.fitair.app.data.dao.LoadDao
import com.fitair.app.data.dao.LoadToday
import com.fitair.app.data.dao.WakeDao
import com.fitair.app.data.dao.WakeInfo
import com.fitair.app.data.dao.WaterDao
import com.fitair.app.data.dao.WaterToday
import com.fitair.app.notify.WaterAlarm
import com.fitair.app.ui.components.Tone
import com.fitair.app.ui.copy.Copy
import com.fitair.app.ui.copy.Verdict
import com.fitair.app.ui.sleep.SleepModel
import com.fitair.app.ui.sleep.StageMinutes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class Insight(val title: String, val alert: Boolean, val id: String = "")

/** What sits below the big card, in order (docs/PLAN_090 6.3). */
enum class Below { NextUp, Water, Alert }

/** Today's big card: the two large and three small metric cards for a mode, plus the strips under it. */
class TodayLayout(val large: List<MetricId>, val small: List<MetricId>, val below: List<Below>)

/**
 * The layout for [mode] (docs/PLAN_090 6.1). Day and Evening share the large pair; only the small trio rotates.
 * [waterOpen] = the water window has not ended; [alert] = an alert-level insight exists (shown as one line).
 */
fun todayLayout(mode: Mode, waterOpen: Boolean = true, alert: Boolean = false): TodayLayout {
    val (large, small) = when (mode) {
        Mode.Morning -> listOf(MetricId.Readiness, MetricId.Sleep) to listOf(MetricId.Hrv, MetricId.RestingHr, MetricId.Load)
        Mode.Day -> listOf(MetricId.Load, MetricId.Heart) to listOf(MetricId.Readiness, MetricId.Sleep)
        Mode.Evening -> listOf(MetricId.Load, MetricId.Heart) to listOf(MetricId.Steps, MetricId.Bedtime)
    }
    return TodayLayout(large, small, buildList {
        add(Below.NextUp)
        if (waterOpen) add(Below.Water)
        if (alert) add(Below.Alert)
    })
}

/** Insight ids that stay on Today in the evening (load and sleep debt only). */
val EVENING_INSIGHTS = setOf("acwr_high", "sleep_debt")

data class TodayUi(
    val date: LocalDate,
    val readiness: ReadinessView?,
    val lastSyncMs: Long,
    val insights: List<Insight>,
    val hasData: Boolean,
    val wake: WakeInfo? = null,
    val water: WaterToday? = null,
    /** Newest heart-rate bucket (ms), shown as "data to 14:05". */
    val dataToMs: Long? = null,
    /** Everything the metric cards show (null while the first snapshot is being built). */
    val snapshot: MetricSnapshot? = null,
    /** Today's fact grid for the Details sheet. */
    val facts: DayFacts? = null,
    val glassMl: Int = 250,
)

/** The drink just logged, for the 10 s "Logged 250 ml · Undo" line. */
class LoggedWater(val t: Long, val ml: Int)

/** Today's state. Every number comes from the local DB; Health Connect is read only by SyncRepo. */
class TodayVm(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences(SyncPrefs.FILE, Context.MODE_PRIVATE)

    var ui by mutableStateOf<TodayUi?>(null); private set
    /** Null until the first load; changes only on resume, pull-to-refresh and a sync-on-open that finishes within 30 s of an untouched screen. */
    var mode by mutableStateOf<Mode?>(null); private set
    var syncing by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    /** The three summary bullets: the rules text at once, replaced when the model text arrives. Never blocks the screen. */
    var brief by mutableStateOf<Brief?>(null); private set
    private var briefJob: Job? = null
    private var briefKey: String? = null
    var logged by mutableStateOf<LoggedWater?>(null); private set
    /** One quiet line after a sync: what it found ("no newer data from Google Health"), cleared after ~10 s. */
    var syncNote by mutableStateOf<String?>(null); private set
    private var dataToBeforeSync: Long? = null

    private var touched = false
    private var openedAtMs = 0L
    private var modeOnSyncEnd = false

    init {
        load(setMode = true)
        viewModelScope.launch {
            var was = false
            SyncScheduler.nowFlow(app).collect { infos ->
                val now = infos.any { !it.state.isFinished }
                syncing = now
                if (!was && now) { dataToBeforeSync = ui?.dataToMs; syncNote = null }
                if (was && !now) {
                    val within = openedAtMs > 0 && System.currentTimeMillis() - openedAtMs <= 30_000 && !touched
                    load(setMode = modeOnSyncEnd && (within || openedAtMs == 0L))
                    modeOnSyncEnd = false
                    val before = dataToBeforeSync
                    launch {
                        kotlinx.coroutines.delay(700) // let load() publish the new state
                        val after = ui?.dataToMs
                        val failed = prefs.getString(SyncPrefs.STATUS, null)?.let { it != "ok" } ?: false
                        syncNote = when {
                            failed -> "Sync problem: " + prefs.getString(SyncPrefs.STATUS, "")
                            after == null -> "Synced. No heart rate data in Health Connect yet."
                            before == null || after > before -> "Synced. New data up to " + Format.clock(
                                java.time.Instant.ofEpochMilli(after).atZone(java.time.ZoneId.systemDefault()).let { it.hour }, 
                                java.time.Instant.ofEpochMilli(after).atZone(java.time.ZoneId.systemDefault()).minute)
                            else -> "Synced. Google Health has nothing newer yet. Open it to sync your Air."
                        }
                        kotlinx.coroutines.delay(10_000)
                        syncNote = null
                    }
                }
                was = now
            }
        }
    }

    /** The screen was touched: from now on the mode never changes under the thumb. */
    fun onTouched() { touched = true }

    /** Today came to the front: render from the DB at once, and start a sync when the last one is older than 5 minutes. */
    fun onOpen() {
        touched = false
        openedAtMs = System.currentTimeMillis()
        load(setMode = true)
        val stale = openedAtMs - prefs.getLong(SyncPrefs.LAST, 0L) > 5 * 60_000L
        if (stale && !syncing) { AppLog.d("Today: sync on open"); modeOnSyncEnd = true; SyncScheduler.syncNow(getApplication()) }
    }

    /** Pull-to-refresh: run a sync; the screen reloads (and may change mode) when it finishes. */
    fun refresh() { AppLog.d("Today: pull to refresh"); modeOnSyncEnd = true; openedAtMs = 0L; SyncScheduler.syncNow(getApplication()) }

    fun load(setMode: Boolean = false) {
        viewModelScope.launch {
            try {
                val built = withContext(Dispatchers.IO) { build() }
                ui = built
                if (setMode || mode == null) mode = withContext(Dispatchers.Default) {
                    built.wake?.let { todayMode(LocalDateTime.now(), it) } ?: Mode.Day
                }
                error = null
            } catch (e: Exception) {
                AppLog.e("Today load failed", e)
                error = e.message ?: e.javaClass.simpleName
            }
        }
    }

    // ---- water -----------------------------------------------------------------------------------------------

    fun addWater(ml: Int) {
        viewModelScope.launch {
            val t = withContext(Dispatchers.IO) { WaterDao.add(getApplication(), ml) }
            val mine = LoggedWater(t, ml)
            logged = mine
            reloadWater()
            delay(10_000)
            if (logged === mine) logged = null
        }
    }

    fun undoWater() {
        val l = logged ?: return
        logged = null
        viewModelScope.launch {
            withContext(Dispatchers.IO) { WaterDao.undo(getApplication(), l.t) }
            reloadWater()
        }
    }

    /** Turns reminders on (the screen has asked for the notification permission first). */
    fun enableWaterReminders() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { WaterAlarm.enable(getApplication()) }
            reloadWater()
        }
    }

    private suspend fun reloadWater() {
        val cur = ui ?: return
        val ctx = getApplication<Application>()
        val (w, snap, f) = withContext(Dispatchers.IO) {
            MetricsRepo.invalidate()
            Triple(WaterDao.today(ctx), runCatching { MetricsRepo.snapshot(ctx, cur.date) }.getOrNull(), runCatching { DaySummary.facts(ctx, cur.date) }.getOrNull())
        }
        ui = cur.copy(water = w, snapshot = snap ?: cur.snapshot, facts = f ?: cur.facts)
        refreshBriefTemplate()
    }

    // ---- three-bullet summary --------------------------------------------------------------------------------

    /**
     * Shows the rules bullets for the current facts at once, then asks [TodayBrief.get] (cached, hash-gated, at most 6 model calls
     * a day, strict validation) and swaps the text in when it arrives. One run per (date, mode, data version); a refresh forces one.
     */
    fun ensureBrief(force: Boolean = false) {
        val u = ui ?: return
        val m = mode ?: return
        val key = "${u.date}|$m|${u.lastSyncMs}|${u.dataToMs}"
        if (!force && key == briefKey && brief != null) return
        briefKey = key
        briefJob?.cancel()
        val ctx = getApplication<Application>()
        briefJob = viewModelScope.launch {
            val now = withContext(Dispatchers.IO) { try { TodayBrief.templateNow(ctx, u.date, m) } catch (e: Exception) { null } }
            if (now != null && now.bullets.size == 3) brief = now
            val full = try { withContext(Dispatchers.IO) { TodayBrief.get(ctx, u.date, m) } }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { AppLog.d("today brief failed: ${e.message}"); null }
            if (full != null && full.bullets.size == 3) brief = full
        }
    }

    /** Rules bullets for the current numbers only (after a water entry), no model call. */
    private fun refreshBriefTemplate() {
        val u = ui ?: return
        val m = mode ?: return
        val ctx = getApplication<Application>()
        viewModelScope.launch {
            val t = withContext(Dispatchers.IO) { try { TodayBrief.templateNow(ctx, u.date, m) } catch (e: Exception) { null } }
            if (t != null && t.bullets.size == 3) brief = t
        }
    }

    // ---- build -----------------------------------------------------------------------------------------------

    private fun build(): TodayUi {
        val ctx = getApplication<Application>()
        val z = ZoneId.systemDefault()
        val today = LocalDate.now(z)
        val nowMs = System.currentTimeMillis()
        DailyMetrics.ensure(ctx, today.minusDays(28).toString(), today.toString())
        val row = LocalStore.get(ctx).getDaily(today.toString(), today.toString()).firstOrNull { it.optString("date") == today.toString() }
        val readiness = ReadinessView.parse(row?.optString("readiness_json"))
        val wake = WakeDao.get(ctx, nowMs)
        val (dataTo, _) = latestHeart(ctx, nowMs)
        val snap = try { MetricsRepo.snapshot(ctx, today) } catch (e: Exception) { AppLog.e("snapshot failed", e); null }
        return TodayUi(
            date = today, readiness = readiness, lastSyncMs = prefs.getLong(SyncPrefs.LAST, 0L),
            insights = insights(row), hasData = row != null, wake = wake, water = WaterDao.today(ctx), dataToMs = dataTo,
            snapshot = snap, facts = runCatching { DaySummary.facts(ctx, today) }.getOrNull(), glassMl = WaterDao.glassMl(ctx),
        )
    }

    /** Newest heart-rate bucket time and, when it is under 30 minutes old, the heart rate "now". */
    private fun latestHeart(ctx: Context, nowMs: Long): Pair<Long?, Int?> = try {
        LocalStore.get(ctx).db.rawQuery(
            "SELECT t30, sum(mean*n)/sum(n) FROM hr_30s WHERE t30=(SELECT max(t30) FROM hr_30s WHERE t30<=?) GROUP BY t30",
            arrayOf(nowMs.toString())).use {
            if (it.moveToFirst()) {
                val t = it.getLong(0) + 30_000L
                t to if (nowMs - t < 30 * 60_000L) Math.round(it.getDouble(1)).toInt() else null
            } else null to null
        }
    } catch (e: Exception) { null to null }

    private fun insights(row: JSONObject?): List<Insight> {
        val arr = try { JSONArray(row?.optString("insights_json").takeUnless { it.isNullOrEmpty() } ?: "[]") } catch (e: Exception) { JSONArray() }
        return (0 until arr.length()).map { arr.getJSONObject(it) }
            .filter { it.optString("severity") == "watch" || it.optString("severity") == "alert" }
            .sortedBy { if (it.optString("severity") == "alert") 0 else 1 }
            .take(2).map { Insight(Copy.insight(it.optString("id"), it.optString("title")), it.optString("severity") == "alert", it.optString("id")) }
    }
}
