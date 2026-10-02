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
import com.fitair.app.core.Format
import com.fitair.app.ui.components.Tone
import com.fitair.app.ui.sleep.SleepModel
import com.fitair.app.ui.sleep.StageMinutes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId

class Vital(val label: String, val value: String, val unit: String?, val delta: String?, val tone: Tone) { var dest: TodayDest? = null }

/** Last night on Today: duration, window, score, difference to the personal need, 7-day debt and stage minutes. */
class SleepNight(
    val durationMin: Long, val window: String?, val score: Double?, val needDiffMin: Double?, val debt: String?, val stages: StageMinutes?,
)

class Insight(val title: String, val alert: Boolean)

/** Today's content blocks, top to bottom. `card` blocks sit on surfaceVariant and are separated by a gap instead of a hairline. */
enum class TodayBlock(val card: Boolean) { Readiness(false), Sleep(true), Vitals(true), Insights(false) }

fun todayBlocks(ui: TodayUi): List<TodayBlock> = buildList {
    add(TodayBlock.Readiness)
    if (ui.night != null) add(TodayBlock.Sleep)
    add(TodayBlock.Vitals)
    if (ui.insights.isNotEmpty()) add(TodayBlock.Insights)
}

class TodayUi(
    val date: LocalDate,
    val readiness: ReadinessView?,
    val lastSyncMs: Long,
    val vitals: List<Vital>,
    val night: SleepNight?,
    val insights: List<Insight>,
    val hasData: Boolean,
)

/** Today's state. Every number comes from the local DB (daily_metrics + sleep rows); Health Connect is read only by SyncRepo. */
class TodayVm(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences(SyncPrefs.FILE, Context.MODE_PRIVATE)

    var ui by mutableStateOf<TodayUi?>(null); private set
    var syncing by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set

    init {
        load()
        viewModelScope.launch {
            var was = false
            SyncScheduler.nowFlow(app).collect { infos ->
                val now = infos.any { !it.state.isFinished }
                syncing = now
                if (was && !now) load()
                was = now
            }
        }
    }

    /** Pull-to-refresh: run a sync; the screen reloads when it finishes. */
    fun refresh() { AppLog.d("Today: pull to refresh"); SyncScheduler.syncNow(getApplication()) }

    fun load() {
        viewModelScope.launch {
            try {
                ui = withContext(Dispatchers.IO) { build() }
                error = null
            } catch (e: Exception) {
                AppLog.e("Today load failed", e)
                error = e.message ?: e.javaClass.simpleName
            }
        }
    }

    private fun build(): TodayUi {
        val ctx = getApplication<Application>()
        val z = ZoneId.systemDefault()
        val today = LocalDate.now(z)
        DailyMetrics.ensure(ctx, today.minusDays(28).toString(), today.toString())
        val rows = LocalStore.get(ctx).getDaily(today.minusDays(28).toString(), today.toString())
        val row = rows.firstOrNull { it.optString("date") == today.toString() }
        val hist = rows.filter { it.optString("date") != today.toString() }
        fun col(k: String) = hist.filter { it.has(k) }.map { it.getDouble(k) }

        val readiness = ReadinessView.parse(row?.optString("readiness_json"))
        val sleepJson = row?.optString("sleep_json")?.takeIf { it.isNotEmpty() && it != "null" }?.let { JSONObject(it) }

        val vitals = ArrayList<Vital>()
        val sleepMin = row?.takeIf { it.has("sleep_min") }?.getLong("sleep_min")
        vitals.add(vital("HRV", row, "hrv", col("hrv"), "ms", higherBetter = true, minDelta = 2.0))
        vitals.add(vital("Resting HR", row, "rhr", col("rhr"), "bpm", higherBetter = false, minDelta = 1.0))
        val steps = row?.takeIf { it.has("steps") }?.getLong("steps")
        val typSteps = Format.baseline(col("steps"))
        vitals.add(Vital("Steps", steps?.let { Format.compactCount(it) } ?: Format.DASH, null,
            typSteps?.let { "typical ${Format.compactCount(Math.round(it))}" }, Tone.Neutral))

        vitals[0].dest = TodayDest.Hrv; vitals[1].dest = TodayDest.RestingHr
        return TodayUi(
            date = today, readiness = readiness, lastSyncMs = prefs.getLong(SyncPrefs.LAST, 0L), vitals = vitals,
            night = sleepNight(ctx, z, today, sleepMin, sleepJson), insights = insights(row), hasData = row != null,
        )
    }

    private fun vital(label: String, row: JSONObject?, key: String, hist: List<Double>, unit: String, higherBetter: Boolean, minDelta: Double): Vital {
        val v = row?.takeIf { it.has(key) }?.getDouble(key) ?: return Vital(label, Format.DASH, null, null, Tone.Neutral)
        val b = Format.baseline(hist) ?: return Vital(label, "${Math.round(v)}", unit, null, Tone.Neutral)
        val d = v - b
        if (Math.abs(d) < minDelta) return Vital(label, "${Math.round(v)}", unit, "in line with base ${Math.round(b)}", Tone.Neutral)
        val good = (d > 0) == higherBetter
        return Vital(label, "${Math.round(v)}", unit, (if (d > 0) "▲ " else "▼ ") + "${Math.round(Math.abs(d))} $unit vs base ${Math.round(b)}", if (good) Tone.Good else Tone.Caution)
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
                val a = java.time.Instant.ofEpochMilli(start).atZone(z); val b = java.time.Instant.ofEpochMilli(end).atZone(z)
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
            .take(2).map { Insight(it.optString("title"), it.optString("severity") == "alert") }
    }
}
