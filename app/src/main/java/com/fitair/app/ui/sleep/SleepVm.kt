package com.fitair.app.ui.sleep

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.fitair.app.AppLog
import com.fitair.app.LocalApi
import com.fitair.app.LocalStore
import com.fitair.app.ui.components.Tone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** One calendar day on the sleep chart; every field is null when there is no sleep for that date. */
class Night(
    val date: LocalDate,
    val sleepMin: Double?,
    val score: Double?,
    val needMin: Double?,
    val debtMin: Double?,
    /** Component scores 0-100 keyed duration / efficiency / restorative / consistency. */
    val components: Map<String, Double>,
    val efficiency: Double?,
) {
    val hasData get() = sleepMin != null
}

/** Stage minutes of one night. */
class StageMinutes(val awake: Double, val light: Double, val rem: Double, val deep: Double) {
    val total get() = awake + light + rem + deep
    val isEmpty get() = total <= 0.0
}

class NightDetail(val bedtime: String?, val wake: String?, val stages: StageMinutes?)

/** Pure helpers (JVM-testable). */
object SleepModel {
    val COMPONENTS = listOf("duration" to "Duration", "efficiency" to "Efficiency", "restorative" to "Restorative", "consistency" to "Consistency")

    private fun JSONObject.num(k: String): Double? =
        if (!has(k) || isNull(k)) null else optDouble(k, Double.NaN).takeIf { !it.isNaN() }

    /** Maps the /daily response to one Night per date in [from, to], missing dates included with null data. */
    fun parseNights(resp: JSONObject, from: LocalDate, to: LocalDate): List<Night> {
        val byDate = HashMap<String, JSONObject>()
        val days = resp.optJSONArray("days")
        if (days != null) for (i in 0 until days.length()) days.optJSONObject(i)?.let { byDate[it.optString("date")] = it }
        val out = ArrayList<Night>()
        var d = from
        while (!d.isAfter(to)) {
            val row = byDate[d.toString()]
            val sj = row?.optJSONObject("sleep_breakdown")
            val comps = LinkedHashMap<String, Double>()
            val cj = sj?.optJSONObject("components")
            if (cj != null) for ((k, _) in COMPONENTS) cj.optJSONObject(k)?.num("score")?.let { comps[k] = it }
            val sleep = row?.num("sleep_min")?.takeIf { it > 0 }
            out.add(Night(
                date = d, sleepMin = sleep,
                score = if (sleep == null) null else (row.num("sleep_score") ?: sj?.num("score")),
                needMin = sj?.num("need_min"), debtMin = sj?.num("sleep_debt_min"),
                components = comps, efficiency = cj?.optJSONObject("efficiency")?.num("efficiency"),
            ))
            d = d.plusDays(1)
        }
        return out
    }

    fun avgSleepMin(n: List<Night>): Double? = n.mapNotNull { it.sleepMin }.takeIf { it.isNotEmpty() }?.average()
    fun avgScore(n: List<Night>): Double? = n.mapNotNull { it.score }.takeIf { it.isNotEmpty() }?.average()

    /** Latest 7-day sleep debt (minutes) as computed with the newest night that has it. */
    fun latestDebtMin(n: List<Night>): Double? = n.lastOrNull { it.hasData && it.debtMin != null }?.debtMin

    /** Personal need for the chart baseline: the most recent night's need, in minutes. */
    fun needMin(n: List<Night>): Double? = n.lastOrNull { it.needMin != null }?.needMin

    /** Bar heights in hours, null for nights without data. */
    fun durationHours(n: List<Night>): List<Float?> = n.map { it.sleepMin?.let { m -> (m / 60.0).toFloat() } }
    fun scores(n: List<Night>): List<Float?> = n.map { it.score?.toFloat() }

    /** Score tint: 75+ good, 60-74 caution, below 60 alert, no score neutral. */
    fun tone(score: Double?): Tone = when {
        score == null -> Tone.Neutral
        score >= 75 -> Tone.Good
        score >= 60 -> Tone.Caution
        else -> Tone.Alert
    }

    /** Stage codes: 1,3,7 awake-ish; 2,4 light (2 = unspecified sleep); 5 deep; 6 REM. Code 0 (unknown) is ignored. */
    fun stageMinutes(byStage: Map<Int, Double>): StageMinutes {
        fun s(vararg k: Int) = k.sumOf { byStage[it] ?: 0.0 }
        return StageMinutes(awake = s(1, 3, 7), light = s(2, 4), rem = s(6), deep = s(5))
    }

    /** 14 or 30 nights taken from the end of [all]. */
    fun lastN(all: List<Night>, n: Int): List<Night> = all.takeLast(n)

    /** The latest night that has data, or null when none do. */
    fun defaultNight(nights: List<Night>): LocalDate? = nights.lastOrNull { it.hasData }?.date

    /** "Last night" for [today], otherwise e.g. "Wed 30 Sep". */
    fun headerLabel(date: LocalDate, today: LocalDate): String =
        if (date == today) "Last night" else date.format(java.time.format.DateTimeFormatter.ofPattern("EEE d MMM", java.util.Locale.ENGLISH))

    /** Short x label, e.g. "2 Oct". */
    fun xLabel(d: LocalDate): String = "${d.dayOfMonth} ${d.month.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.ENGLISH)}"
}

sealed interface SleepState {
    data object Loading : SleepState
    class Error(val message: String) : SleepState
    class Ready(val nights: List<Night>) : SleepState
}

class SleepVm(app: Application) : AndroidViewModel(app) {
    var state by mutableStateOf<SleepState>(SleepState.Loading); private set
    var range by mutableStateOf(30); private set
    var selected by mutableStateOf<LocalDate?>(null); private set
    var detail by mutableStateOf<NightDetail?>(null); private set

    private val detailCache = HashMap<LocalDate, NightDetail>()

    init { load() }

    fun chooseRange(n: Int) {
        range = n
        val nights = (state as? SleepState.Ready)?.nights ?: return
        val inRange = SleepModel.lastN(nights, n).any { it.date == selected }
        if (!inRange) selectDefault()
    }

    fun selectDefault() {
        val nights = (state as? SleepState.Ready)?.nights ?: return
        SleepModel.defaultNight(nights)?.let { select(it) }
    }

    fun load() {
        state = SleepState.Loading
        viewModelScope.launch {
            try {
                val to = LocalDate.now(ZoneId.systemDefault()); val from = to.minusDays(29)
                val nights = withContext(Dispatchers.IO) {
                    SleepModel.parseNights(LocalApi.get(getApplication(), "/daily?from=$from&to=$to"), from, to)
                }
                state = SleepState.Ready(nights)
                if (selected == null || nights.none { it.date == selected }) selectDefault()
            } catch (e: Exception) {
                AppLog.e("Sleep load failed", e)
                state = SleepState.Error(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun select(date: LocalDate) {
        selected = date
        detailCache[date]?.let { detail = it; return }
        detail = null
        viewModelScope.launch {
            val d = try { withContext(Dispatchers.IO) { queryDetail(date) } } catch (e: Exception) { AppLog.e("Sleep detail failed", e); NightDetail(null, null, null) }
            detailCache[date] = d
            if (selected == date) detail = d
        }
    }

    /** Longest sleep session that ends on [date] (same rule as DailyMetrics), plus its stage totals. */
    private fun queryDetail(date: LocalDate): NightDetail {
        val z = ZoneId.systemDefault()
        val (lo, hi) = LocalApi.bounds(date, z)
        val db = LocalStore.get(getApplication()).db
        var start = 0L; var end = 0L; var origin: String? = null
        db.rawQuery("SELECT start_ms,end_ms,origin FROM sleep WHERE end_ms>=? AND end_ms<? ORDER BY end_ms-start_ms DESC LIMIT 1",
            arrayOf(lo.toString(), hi.toString())).use { if (it.moveToFirst()) { start = it.getLong(0); end = it.getLong(1); origin = it.getString(2) } }
        if (origin == null) return NightDetail(null, null, null)
        val by = HashMap<Int, Double>()
        db.rawQuery("SELECT stage, sum(end_ms-start_ms)/60000.0 FROM sleep_stage WHERE sleep_start_ms=? AND origin=? GROUP BY stage",
            arrayOf(start.toString(), origin)).use { while (it.moveToNext()) by[it.getInt(0)] = it.getDouble(1) }
        fun clock(ms: Long) = Instant.ofEpochMilli(ms).atZone(z).let { com.fitair.app.core.Format.clock(it.hour, it.minute) }
        val st = SleepModel.stageMinutes(by)
        return NightDetail(clock(start), clock(end), st.takeIf { !it.isEmpty })
    }
}
