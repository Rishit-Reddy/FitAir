package com.fitair.app.data.metrics

import android.content.Context
import com.fitair.app.AppLog
import com.fitair.app.DailyMetrics
import com.fitair.app.LocalApi
import com.fitair.app.LocalStore
import com.fitair.app.SyncPrefs
import com.fitair.app.data.dao.LoadDao
import com.fitair.app.data.dao.WakeDao
import com.fitair.app.data.dao.WaterDao
import com.fitair.app.ui.sleep.SleepModel
import com.fitair.app.ui.today.windDown
import org.json.JSONObject
import java.time.ZoneId
import com.fitair.app.data.dao.LoadToday
import com.fitair.app.data.dao.WaterToday
import com.fitair.app.ui.sleep.StageMinutes
import com.fitair.app.ui.today.WindDown
import com.fitair.app.ui.trends.Band
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.LocalDate

/** The metrics shared by Today and the Metrics tab (docs/PLAN_090 section 5). Names are the contract between Agent A and B. */
enum class MetricId { Heart, Readiness, Sleep, RestingHr, Hrv, Load, Energy, Distance, Steps, Water, Weight, Bedtime }

/** One night: [asleepMin] asleep, [needMin] personal need (null while unknown), [score] 0-100 sleep score. */
class NightBrief(val asleepMin: Double, val needMin: Double?, val score: Double?)

/**
 * Heart rate of one day as 288 five-minute means ([points], index 0 = 00:00, NaN = no data). [rest] = resting heart rate used for
 * the day, [hrMax] = maximum heart rate in use, [zoneBpm] = lower bpm bound of Light, Moderate, Vigorous, Peak.
 * [latestBpm]/[latestMs] = newest 30 s reading of that day (any age; callers decide what is stale).
 */
class HeartDay(
    val points: FloatArray, val rest: Float, val hrMax: Float, val zoneBpm: FloatArray,
    val latestBpm: Int?, val latestMs: Long?, val coverage: Double?,
)

/**
 * Everything the Metrics cards and Today's big card show, built from the local database only. All lists of [days] length (7, oldest
 * first, last = [date]) use null for "no value that day". [bandDays] = days with a reading in the 28 days before [date] (the smaller of
 * resting HR and HRV); the bands are null below 14.
 */
class MetricSnapshot(
    val date: LocalDate,
    val lastDataMs: Long? = null,
    val days: List<LocalDate> = emptyList(),
    val readiness: List<Double?> = emptyList(),
    val rhr: List<Double?> = emptyList(),
    val hrv: List<Double?> = emptyList(),
    val load: List<Double?> = emptyList(),
    val steps: List<Double?> = emptyList(),
    val distanceM: List<Double?> = emptyList(),
    val kcal: List<Double?> = emptyList(),
    val waterMl: List<Double?> = emptyList(),
    val rhrBand: Band? = null,
    val hrvBand: Band? = null,
    val bandDays: Int = 0,
    val nights: List<NightBrief?> = emptyList(),
    val lastStages: StageMinutes? = null,
    val debtMin: Double? = null,
    val loadToday: LoadToday? = null,
    /** Cumulative load at the end of each hour 0..23; null for hours still to come. */
    val loadHourly: List<Double?> = emptyList(),
    /** Median cumulative load at the end of each hour over the last 28 days; all null with fewer than 7 days. */
    val typicalHourly: List<Double?> = emptyList(),
    val ratio: Double? = null,
    /** Median daily cardio load over the last 28 days (null with fewer than 7 days). */
    val loadUsual: Double? = null,
    val heart: HeartDay? = null,
    val water: WaterToday? = null,
    /** (time ms, kg) of the last 90 days, oldest first. */
    val weight: List<Pair<Long, Double>> = emptyList(),
    val windDown: WindDown? = null,
)

/**
 * Builds [MetricSnapshot]s from the local database only (Health Connect is read by the sync, never here). One snapshot per
 * (date, last sync time, water version) is cached; a sync end or a water change changes the key, [invalidate] drops everything.
 */
object MetricsRepo {
    private const val TTL_MS = 10 * 60_000L
    private const val DAY = 86_400_000L

    private class Entry(val key: String, val builtMs: Long, val snap: MetricSnapshot)

    private val _flow = MutableStateFlow<MetricSnapshot?>(null)
    /** The newest snapshot built for today's date (null until the first one). Collectors re-read after a sync or a water log. */
    val flow: StateFlow<MetricSnapshot?> = _flow

    private val cache = HashMap<LocalDate, Entry>()

    /** Forget cached snapshots (after a sync or a water change). */
    @Synchronized fun invalidate() { cache.clear() }

    /** Blocking (database work): call from an IO dispatcher. Never throws except cancellation; a failing part is left empty. */
    @Synchronized
    fun snapshot(ctx: Context, date: LocalDate): MetricSnapshot {
        val now = System.currentTimeMillis()
        val key = try { keyOf(ctx, date) } catch (e: Exception) { "" }
        val hit = cache[date]
        if (hit != null && hit.key == key && key.isNotEmpty() && now - hit.builtMs < TTL_MS) return hit.snap
        val snap = try { build(ctx, date, now) } catch (e: kotlinx.coroutines.CancellationException) { throw e
        } catch (e: Exception) { AppLog.e("MetricsRepo.snapshot failed", e); hit?.snap ?: empty(date) }
        cache[date] = Entry(key, now, snap)
        if (cache.size > 4) cache.keys.filter { it.isBefore(LocalDate.now().minusDays(3)) }.forEach { cache.remove(it) }
        if (date == LocalDate.now(ZoneId.systemDefault())) _flow.value = snap
        return snap
    }

    private fun empty(date: LocalDate) = MetricSnapshot(date, days = (6 downTo 0).map { date.minusDays(it.toLong()) },
        readiness = nulls(7), rhr = nulls(7), hrv = nulls(7), load = nulls(7), steps = nulls(7), distanceM = nulls(7), kcal = nulls(7),
        waterMl = nulls(7), nights = List(7) { null }, loadHourly = nulls(24), typicalHourly = nulls(24))

    private fun nulls(n: Int): List<Double?> = List(n) { null }

    private fun keyOf(ctx: Context, date: LocalDate): String {
        val sync = ctx.getSharedPreferences(SyncPrefs.FILE, Context.MODE_PRIVATE).getLong(SyncPrefs.LAST, 0L)
        val z = ZoneId.systemDefault()
        val lo = LocalApi.bounds(date.minusDays(6), z).first
        val water = LocalStore.get(ctx).db.rawQuery("SELECT count(*), coalesce(max(t),0), coalesce(sum(ml),0) FROM water WHERE t>=?", arrayOf(lo.toString()))
            .use { if (it.moveToFirst()) "${it.getLong(0)}/${it.getLong(1)}/${it.getDouble(2)}" else "" }
        return "$date|$sync|$water"
    }

    private fun num(o: JSONObject?, k: String): Double? =
        o?.takeIf { it.has(k) && !it.isNull(k) }?.optDouble(k)?.takeIf { !it.isNaN() }

    private fun build(ctx: Context, date: LocalDate, nowMs: Long): MetricSnapshot {
        val z = ZoneId.systemDefault()
        val today = LocalDate.now(z)
        val isToday = date == today
        val db = LocalStore.get(ctx).db
        val days = (6 downTo 0).map { date.minusDays(it.toLong()) }

        try { DailyMetrics.ensure(ctx, date.minusDays(28).toString(), minOf(date, today).toString()) } catch (e: Exception) { AppLog.d("metrics: ensure failed: ${e.message}") }
        val rows = try { LocalStore.get(ctx).getDaily(date.minusDays(34).toString(), date.toString()) } catch (e: Exception) { emptyList() }
        val byDate = rows.associateBy { it.optString("date") }
        fun col(k: String, ds: List<LocalDate>) = ds.map { num(byDate[it.toString()], k) }

        val readiness = col("readiness", days)
        val rhr = col("rhr", days); val hrv = col("hrv", days)
        val hist = (28 downTo 1).map { date.minusDays(it.toLong()) }
        val rhrHist = col("rhr", hist); val hrvHist = col("hrv", hist)
        val bandDays = minOf(MetricStats.daysWithData(rhrHist), MetricStats.daysWithData(hrvHist))

        val hm = LoadDao.hrMax(ctx)
        val ser = try { LocalApi.series(db, z, days.first(), date, hm.value) } catch (e: Exception) { AppLog.d("metrics: series failed: ${e.message}"); emptyMap() }
        fun ser(f: (LocalApi.Day) -> Double): List<Double?> = days.map { d -> ser[d.toString()]?.let(f)?.takeIf { it > 0.0 } }
        val steps = ser { it.steps.toDouble() }; val dist = ser { it.distance }; val kcal = ser { it.kcal }

        val waterTotals = try { WaterDao.dailyTotals(ctx, days.first(), date, z) } catch (e: Exception) { emptyMap() }
        val water = days.map { d -> waterTotals[d.toString()]?.first?.toDouble() }

        // sleep
        val nights = days.map { d ->
            val r = byDate[d.toString()]
            val asleep = num(r, "sleep_min") ?: return@map null
            val sj = r?.optString("sleep_json")?.takeIf { it.isNotEmpty() && it != "null" }?.let { try { JSONObject(it) } catch (e: Exception) { null } }
            NightBrief(asleep, num(sj, "need_min"), num(sj, "score"))
        }
        val sj = byDate[date.toString()]?.optString("sleep_json")?.takeIf { it.isNotEmpty() && it != "null" }?.let { try { JSONObject(it) } catch (e: Exception) { null } }
        val debt = num(sj, "sleep_debt_min")
        val stages = lastStages(ctx, z, date)

        // load
        val loadRows = LoadDao.rows(ctx, date.minusDays(34), date)
        val loadBy = loadRows.associateBy { it.date }
        val live = if (isToday) LoadDao.live(ctx, date) else null
        val loadToday = if (isToday) LoadDao.today(ctx) else null
        val load = days.map { d -> if (isToday && d == date && live != null) live.cardio else loadBy[d.toString()]?.cardio }
        val ndays = (28 downTo 1).map { date.minusDays(it.toLong()).toString() }
        val usual = MetricStats.usual(ndays.map { loadBy[it]?.cardio })
        val typical = MetricStats.typicalHourly(ndays.mapNotNull { loadBy[it]?.hourly })
        val cum: DoubleArray? = if (live != null) live.hourly else loadBy[date.toString()]?.hourly
        val nowHour = java.time.Instant.ofEpochMilli(nowMs).atZone(z).hour
        val loadHourly: List<Double?> = (0 until 24).map { h ->
            if (cum == null || (isToday && h > nowHour)) null else cum[h]
        }
        val ratio = loadToday?.ratio ?: loadRows.lastOrNull { it.ratio != null && it.date >= date.minusDays(3).toString() }?.ratio

        val heart = heartDay(ctx, z, date, hm.value, live?.coverage ?: loadBy[date.toString()]?.coverage)
        val wake = WakeDao.get(ctx, nowMs)
        val need = num(sj, "need_min")

        return MetricSnapshot(
            date = date, lastDataMs = lastDataMs(ctx, nowMs), days = days, readiness = readiness, rhr = rhr, hrv = hrv, load = load,
            steps = steps, distanceM = dist, kcal = kcal, waterMl = water,
            rhrBand = MetricStats.band(rhrHist), hrvBand = MetricStats.band(hrvHist), bandDays = bandDays,
            nights = nights, lastStages = stages, debtMin = debt, loadToday = loadToday, loadHourly = loadHourly,
            typicalHourly = typical, ratio = ratio, loadUsual = usual, heart = heart,
            water = if (isToday) WaterDao.today(ctx) else null, weight = weight(ctx, nowMs),
            windDown = if (isToday) windDown(wake.usualWakeMin, need, debt) else null,
        )
    }

    private fun lastDataMs(ctx: Context, nowMs: Long): Long? = try {
        LocalStore.get(ctx).db.rawQuery("SELECT max(t30) FROM hr_30s WHERE t30<=?", arrayOf(nowMs.toString()))
            .use { if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) + 30_000L else null }
    } catch (e: Exception) { null }

    private fun weight(ctx: Context, nowMs: Long): List<Pair<Long, Double>> = try {
        val out = ArrayList<Pair<Long, Double>>()
        LocalStore.get(ctx).db.rawQuery("SELECT t, kg FROM weight WHERE t>=? ORDER BY t", arrayOf((nowMs - 90 * DAY).toString()))
            .use { while (it.moveToNext()) out.add(it.getLong(0) to it.getDouble(1)) }
        out
    } catch (e: Exception) { emptyList() }

    /** Stage minutes of the main sleep that ended on [date] (same grouping as the Sleep screen). */
    private fun lastStages(ctx: Context, z: ZoneId, date: LocalDate): com.fitair.app.ui.sleep.StageMinutes? = try {
        val (lo, hi) = LocalApi.bounds(date, z)
        val db = LocalStore.get(ctx).db
        db.rawQuery("SELECT start_ms,origin FROM sleep WHERE end_ms>=? AND end_ms<? ORDER BY end_ms-start_ms DESC LIMIT 1",
            arrayOf(lo.toString(), hi.toString())).use { c ->
            if (!c.moveToFirst()) return@use null
            val start = c.getLong(0); val origin = c.getString(1) ?: return@use null
            val by = HashMap<Int, Double>()
            db.rawQuery("SELECT stage, sum(end_ms-start_ms)/60000.0 FROM sleep_stage WHERE sleep_start_ms=? AND origin=? GROUP BY stage",
                arrayOf(start.toString(), origin)).use { s -> while (s.moveToNext()) by[s.getInt(0)] = s.getDouble(1) }
            SleepModel.stageMinutes(by).takeIf { !it.isEmpty }
        }
    } catch (e: Exception) { null }

    /** 30 s heart-rate rows of [date] (all origins) as stored. */
    fun hrRows(ctx: Context, z: ZoneId, date: LocalDate): List<MetricStats.HrRow> = try {
        val (lo, hi) = LocalApi.bounds(date, z)
        val out = ArrayList<MetricStats.HrRow>()
        LocalStore.get(ctx).db.rawQuery("SELECT t30, mean, \"min\", \"max\", n FROM hr_30s WHERE t30>=? AND t30<? ORDER BY t30",
            arrayOf(lo.toString(), hi.toString())).use { while (it.moveToNext()) out.add(MetricStats.HrRow(it.getLong(0), it.getDouble(1), it.getInt(2), it.getInt(3), it.getInt(4))) }
        out
    } catch (e: Exception) { emptyList() }

    /** Heart rate of [date] as 288 five-minute means plus zone bounds and the newest reading. */
    fun heartDay(ctx: Context, z: ZoneId, date: LocalDate, hrMax: Double, coverage: Double?): HeartDay {
        val rows = hrRows(ctx, z, date)
        val (lo, _) = LocalApi.bounds(date, z)
        val rest = LoadDao.restFor(ctx, date, z)
        val bins = MetricStats.downsample(rows, lo)
        val last = rows.lastOrNull()
        return HeartDay(bins.mean, rest.toFloat(), hrMax.toFloat(), MetricStats.zoneBpm(rest, hrMax),
            last?.let { Math.round(it.mean).toInt() }, last?.let { it.t30 + 30_000L }, coverage)
    }
}
