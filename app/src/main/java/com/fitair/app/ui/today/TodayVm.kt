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
import com.fitair.app.coach.SummaryText
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

/** A vital tile: plain [verdict] first, the number in its detail. */
class Vital(val label: String, val verdict: Verdict) { var dest: TodayDest? = null }

/** Last night on Today: duration, window, score, difference to the personal need, 7-day debt and stage minutes. */
class SleepNight(
    val durationMin: Long, val window: String?, val score: Double?, val needDiffMin: Double?, val debt: String?, val stages: StageMinutes?,
)

class Insight(val title: String, val alert: Boolean, val id: String = "")

/** Today's content blocks. `card` blocks sit on surfaceVariant and are separated by a gap instead of a hairline. */
enum class TodayBlock(val card: Boolean) {
    Readiness(false), ReadinessCompact(false),
    Sleep(true), SleepLine(true),
    Vitals(true),
    NextUp(true), Agenda(false), AgendaRest(false),
    Load(true), Water(true),
    DaySummary(true), Tomorrow(false), WindDown(false),
    Insights(false),
}

/** Insight ids that stay on Today in the evening (load and sleep debt only). */
val EVENING_INSIGHTS = setOf("acwr_high", "sleep_debt")

/**
 * The block list for [mode] (docs/PLAN_081.md 3.5). Collapsed blocks (compact readiness, sleep line) become their full
 * form when they are in [expanded]. [agendaLeft] says whether today still has events (Evening shows the rest of today only then).
 */
fun todayBlocks(ui: TodayUi, mode: Mode = Mode.Morning, expanded: Set<TodayBlock> = emptySet(), agendaLeft: Boolean = true): List<TodayBlock> = buildList {
    fun readiness(compact: Boolean) = if (compact && TodayBlock.ReadinessCompact !in expanded) TodayBlock.ReadinessCompact else TodayBlock.Readiness
    fun sleep() = if (ui.night != null && TodayBlock.SleepLine in expanded) TodayBlock.Sleep else TodayBlock.SleepLine
    val insights = if (mode == Mode.Evening) ui.insights.filter { it.id in EVENING_INSIGHTS } else ui.insights
    when (mode) {
        Mode.Morning -> {
            add(TodayBlock.Readiness)
            add(if (ui.night != null) TodayBlock.Sleep else TodayBlock.SleepLine)
            add(TodayBlock.Vitals)
            add(TodayBlock.Agenda)
            add(TodayBlock.Water)
        }
        Mode.Day -> {
            add(TodayBlock.NextUp)
            add(TodayBlock.Water)
            add(TodayBlock.AgendaRest)
            add(readiness(true))
            add(TodayBlock.Load)
            add(sleep())
        }
        Mode.Evening -> {
            add(TodayBlock.DaySummary)
            add(TodayBlock.Tomorrow)
            add(TodayBlock.Water)
            if (ui.windDown != null) add(TodayBlock.WindDown)
            if (agendaLeft) add(TodayBlock.AgendaRest)
        }
    }
    if (insights.isNotEmpty()) add(TodayBlock.Insights)
}

data class TodayUi(
    val date: LocalDate,
    val readiness: ReadinessView?,
    val lastSyncMs: Long,
    val vitals: List<Vital>,
    val night: SleepNight?,
    val insights: List<Insight>,
    val hasData: Boolean,
    val wake: WakeInfo? = null,
    val water: WaterToday? = null,
    val load: LoadToday? = null,
    /** Newest heart-rate bucket (ms), shown as "data to 14:05". */
    val dataToMs: Long? = null,
    /** Latest heart rate within the last 30 minutes, for the compact readiness line. */
    val hrNow: Int? = null,
    val restingHr: Int? = null,
    val windDown: WindDown? = null,
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
    var summary by mutableStateOf<SummaryText?>(null); private set
    var summaryLoading by mutableStateOf(false); private set
    var logged by mutableStateOf<LoggedWater?>(null); private set
    /** One quiet line after a sync: what it found ("no newer data from Google Health"), cleared after ~10 s. */
    var syncNote by mutableStateOf<String?>(null); private set
    private var dataToBeforeSync: Long? = null

    private var touched = false
    private var openedAtMs = 0L
    private var modeOnSyncEnd = false
    private var summaryDate: LocalDate? = null

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
        val w = withContext(Dispatchers.IO) { WaterDao.today(getApplication()) }
        val f = cur.facts?.let { withContext(Dispatchers.IO) { DaySummary.facts(getApplication(), cur.date) } }
        ui = cur.copy(water = w, facts = f ?: cur.facts)
    }

    // ---- evening summary -------------------------------------------------------------------------------------

    /** Loads the cached summary (one model call per evening); [regenerate] asks for a new one. Falls back to the rules text. */
    fun ensureSummary(regenerate: Boolean = false) {
        val u = ui ?: return
        val facts = u.facts ?: return
        if (!facts.hasData) return
        if (!regenerate && summaryDate == u.date && summary != null) return
        if (summaryLoading) return
        summaryLoading = true
        viewModelScope.launch {
            val r = try {
                withContext(Dispatchers.IO) { DaySummary.text(getApplication(), u.date, regenerate) }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) {
                AppLog.d("day summary failed: ${e.message}")
                SummaryText(Copy.daySummary(facts), "template", System.currentTimeMillis())
            }
            summary = if (r.text.isBlank()) SummaryText(Copy.daySummary(facts), "template", r.createdMs) else r
            summaryDate = u.date
            summaryLoading = false
        }
    }

    // ---- build -----------------------------------------------------------------------------------------------

    private fun build(): TodayUi {
        val ctx = getApplication<Application>()
        val z = ZoneId.systemDefault()
        val today = LocalDate.now(z)
        val nowMs = System.currentTimeMillis()
        DailyMetrics.ensure(ctx, today.minusDays(28).toString(), today.toString())
        val rows = LocalStore.get(ctx).getDaily(today.minusDays(28).toString(), today.toString())
        val row = rows.firstOrNull { it.optString("date") == today.toString() }
        val hist = rows.filter { it.optString("date") != today.toString() }
        fun col(k: String) = hist.filter { it.has(k) }.map { it.getDouble(k) }

        val readiness = ReadinessView.parse(row?.optString("readiness_json"))
        val sleepJson = row?.optString("sleep_json")?.takeIf { it.isNotEmpty() && it != "null" }?.let { JSONObject(it) }

        val vitals = ArrayList<Vital>()
        val sleepMin = row?.takeIf { it.has("sleep_min") }?.getLong("sleep_min")
        vitals.add(vital("Recovery signal", row, "hrv", col("hrv"), hrv = true, minDelta = 2.0))
        vitals.add(vital("Resting heart rate", row, "rhr", col("rhr"), hrv = false, minDelta = 1.0))
        vitals[0].dest = TodayDest.Hrv; vitals[1].dest = TodayDest.RestingHr

        val wake = WakeDao.get(ctx, nowMs)
        val need = sleepJson?.optDouble("need_min", Double.NaN)?.takeIf { !it.isNaN() }
        val debt = sleepJson?.optDouble("sleep_debt_min", 0.0)
        val evening = todayMode(LocalDateTime.now(z), wake, z) == Mode.Evening
        val (dataTo, hrNow) = latestHeart(ctx, nowMs)
        return TodayUi(
            date = today, readiness = readiness, lastSyncMs = prefs.getLong(SyncPrefs.LAST, 0L), vitals = vitals,
            night = sleepNight(ctx, z, today, sleepMin, sleepJson), insights = insights(row), hasData = row != null,
            wake = wake, water = WaterDao.today(ctx), load = LoadDao.today(ctx),
            dataToMs = dataTo, hrNow = hrNow, restingHr = row?.takeIf { it.has("rhr") }?.getDouble("rhr")?.let { Math.round(it).toInt() },
            windDown = windDown(wake.usualWakeMin, need, debt),
            facts = if (evening) DaySummary.facts(ctx, today) else null,
            glassMl = WaterDao.glassMl(ctx),
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

    private fun vital(label: String, row: JSONObject?, key: String, hist: List<Double>, hrv: Boolean, minDelta: Double): Vital {
        val v = row?.takeIf { it.has(key) }?.getDouble(key) ?: return Vital(label, Verdict(Format.DASH))
        return Vital(label, Copy.vital(hrv, v, Format.baseline(hist), minDelta))
    }

    private fun sleepNight(ctx: Context, z: ZoneId, today: LocalDate, sleepMin: Long?, sj: JSONObject?): SleepNight? {
        if (sleepMin == null) return null
        val (lo, hi) = LocalApi.bounds(today, z)
        var window: String? = null
        var stages: StageMinutes? = null
        val db = LocalStore.get(ctx).db
        db.rawQuery("SELECT start_ms,end_ms,origin FROM sleep WHERE end_ms>=? AND end_ms<? ORDER BY end_ms-start_ms DESC LIMIT 1",
            arrayOf(lo.toString(), hi.toString())).use {
            if (it.moveToFirst()) {
                val start = it.getLong(0); val end = it.getLong(1); val origin = it.getString(2)
                val a = Instant.ofEpochMilli(start).atZone(z); val b = Instant.ofEpochMilli(end).atZone(z)
                window = "${Format.clock(a.hour, a.minute)} – ${Format.clock(b.hour, b.minute)}"
                if (origin != null) {
                    // same grouping as SleepVm.queryDetail so Today and Sleep show identical minutes
                    val by = HashMap<Int, Double>()
                    db.rawQuery("SELECT stage, sum(end_ms-start_ms)/60000.0 FROM sleep_stage WHERE sleep_start_ms=? AND origin=? GROUP BY stage",
                        arrayOf(start.toString(), origin)).use { c -> while (c.moveToNext()) by[c.getInt(0)] = c.getDouble(1) }
                    stages = SleepModel.stageMinutes(by).takeIf { s -> !s.isEmpty }
                }
            }
        }
        val score = sj?.takeIf { !it.isNull("score") }?.optDouble("score")?.takeIf { !it.isNaN() }
        val need = sj?.optDouble("need_min", Double.NaN)?.takeIf { !it.isNaN() }
        val debt = sj?.optDouble("sleep_debt_min", 0.0) ?: 0.0
        return SleepNight(
            durationMin = sleepMin, window = window, score = score, needDiffMin = need?.let { sleepMin - it },
            debt = if (debt >= 60) Format.duration(Math.round(debt)) else null, stages = stages,
        )
    }

    private fun insights(row: JSONObject?): List<Insight> {
        val arr = try { JSONArray(row?.optString("insights_json").takeUnless { it.isNullOrEmpty() } ?: "[]") } catch (e: Exception) { JSONArray() }
        return (0 until arr.length()).map { arr.getJSONObject(it) }
            .filter { it.optString("severity") == "watch" || it.optString("severity") == "alert" }
            .sortedBy { if (it.optString("severity") == "alert") 0 else 1 }
            .take(2).map { Insight(Copy.insight(it.optString("id"), it.optString("title")), it.optString("severity") == "alert", it.optString("id")) }
    }
}
