package com.fitair.app

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject
import com.fitair.app.analytics.ReadinessMath
import com.fitair.app.core.Format
import com.fitair.app.core.Num
import java.net.URLDecoder
import java.time.DateTimeException
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/**
 * In-process "HTTP-like" read API over the local SQLite DB ([LocalStore]); the coach tools and Today call it.
 * Readiness v2 (see [ReadinessMath]) lives in [readiness].
 */
object LocalApi {
    private val STAGE_NAMES = mapOf(0 to "unknown", 1 to "awake", 2 to "sleeping", 3 to "out_of_bed",
        4 to "light", 5 to "deep", 6 to "rem", 7 to "awake_in_bed")
    private val ASLEEP = setOf(2, 4, 5, 6)
    private const val MIN_BASELINE_DAYS = 14
    private const val DEFAULT_INTENSITY = 0.6

    /** HRmax: pref hr_max, else 208 - 0.7 x age, else observed P99.5 + 5, else 190 (see [LoadDao.hrMax]). */
    internal fun maxHr(ctx: Context): Double = com.fitair.app.data.dao.LoadDao.hrMax(ctx).value

    internal fun maxHr(): Double = appCtx?.let { maxHr(it) } ?: 190.0

    fun get(ctx: Context, path: String): JSONObject {
        val t0 = System.currentTimeMillis()
        val p = if (path.startsWith("/")) path else "/$path"
        val route = p.substringBefore('?')
        val q = parseQuery(p.substringAfter('?', ""))
        val db = LocalStore.get(ctx).db
        appCtx = ctx.applicationContext
        val out = try {
            when (route) {
                "/summary/day" -> summaryDay(db, q)
                "/summary/range" -> summaryRange(db, q)
                "/hr" -> hr(db, q)
                "/workouts" -> workouts(db, q)
                "/readiness" -> readinessEndpoint(db, q)
                "/baselines" -> baselinesEndpoint(db, q)
                "/daily" -> dailyEndpoint(ctx, q)
                "/insights" -> insightsEndpoint(ctx, q)
                "/load" -> loadEndpoint(ctx, q)
                else -> throw IllegalArgumentException("unknown path $route")
            }
        } catch (e: Exception) {
            AppLog.d("LocalApi $route failed in ${System.currentTimeMillis() - t0} ms: ${e.javaClass.simpleName}: ${e.message}")
            throw e
        }
        AppLog.d("LocalApi $route ok in ${System.currentTimeMillis() - t0} ms")
        return out
    }

    // ---------- request parsing ----------

    private fun parseQuery(s: String): Map<String, String> {
        val m = HashMap<String, String>()
        if (s.isEmpty()) return m
        for (part in s.split('&')) {
            if (part.isEmpty()) continue
            val k = URLDecoder.decode(part.substringBefore('='), "UTF-8")
            m[k] = URLDecoder.decode(part.substringAfter('=', ""), "UTF-8")
        }
        return m
    }

    private fun req(q: Map<String, String>, k: String): String =
        q[k] ?: throw IllegalArgumentException("missing query parameter '$k'")

    private fun date(q: Map<String, String>, k: String): LocalDate =
        try { LocalDate.parse(req(q, k)) } catch (e: DateTimeException) { throw IllegalArgumentException("invalid date for '$k'") }

    private fun long(q: Map<String, String>, k: String): Long =
        req(q, k).toLongOrNull() ?: throw IllegalArgumentException("invalid integer for '$k'")

    private fun zone(q: Map<String, String>): ZoneId {
        val n = q["tz"]?.takeIf { it.isNotEmpty() } ?: return ZoneId.systemDefault()
        return try { ZoneId.of(n) } catch (e: DateTimeException) { throw IllegalArgumentException("unknown timezone '$n'") }
    }

    // ---------- helpers ----------

    internal fun r(x: Double?, n: Int = 1): Double? = Num.r(x, n)

    internal fun j(x: Any?): Any = x ?: JSONObject.NULL

    internal fun bounds(d: LocalDate, z: ZoneId): Pair<Long, Long> =
        d.atStartOfDay(z).toInstant().toEpochMilli() to d.plusDays(1).atStartOfDay(z).toInstant().toEpochMilli()

    internal fun daysBetween(d0: LocalDate, d1: LocalDate): List<LocalDate> {
        val n = (d1.toEpochDay() - d0.toEpochDay()).toInt()
        return (0..n).map { d0.plusDays(it.toLong()) }
    }

    internal fun clip(x: Double) = Num.clip(x)

    internal fun mean(v: List<Double>): Double? = Num.mean(v)

    private inline fun <T> SQLiteDatabase.rows(sql: String, args: Array<String> = emptyArray(), f: (android.database.Cursor) -> T): List<T> {
        val out = ArrayList<T>()
        rawQuery(sql, args).use { c -> while (c.moveToNext()) out.add(f(c)) }
        return out
    }

    // ---------- per-day series ----------

    internal class Day(val date: String) {
        var steps = 0L
        var distance = 0.0
        var active = 0L
        var restingHr: Double? = null
        var hrv: Double? = null
        var hrMean: Double? = null
        var hrMin: Double? = null
        var hrMax: Double? = null
        var sleepMin: Double? = null
        var stages: Map<String, Double?> = emptyMap()
        var exCount = 0
        var kcal = 0.0
        var load = 0.0

        fun toJson(readiness: Int?): JSONObject = JSONObject()
            .put("date", date).put("steps", steps).put("distance_m", r(distance))
            .put("active_minutes_est", active).put("resting_hr", j(restingHr))
            .put("hrv_rmssd_night_mean", j(hrv)).put("hr_mean", j(hrMean)).put("hr_min", j(hrMin))
            .put("hr_max", j(hrMax)).put("sleep_minutes", j(sleepMin))
            .put("sleep_stages_minutes", JSONObject().also { o -> stages.forEach { (k, v) -> o.put(k, j(v)) } })
            .put("exercise_count", exCount).put("kcal_total", r(kcal)).put("readiness", j(readiness))
    }

    internal class Ex(val start: Long, val end: Long, val type: Int, val title: String, val hrAvg: Double?)

    /** Sessions starting in [lo, hi). Sessions that count as "not real exercise" (exercise_flag) are left out unless [includeFlagged]. */
    internal fun exercises(db: SQLiteDatabase, lo: Long, hi: Long, includeFlagged: Boolean = false): List<Ex> {
        val flagSql = if (includeFlagged) "" else
            " AND NOT EXISTS (SELECT 1 FROM exercise_flag f WHERE f.start_ms=e.start_ms AND f.origin=e.origin AND f.verdict='not_exercise')"
        val rows = db.rows(
            "SELECT e.start_ms, e.end_ms, e.type, e.title, " +
                "COALESCE((SELECT avg(bpm) FROM heart_rate h WHERE h.t BETWEEN e.start_ms AND e.end_ms), " +
                "(SELECT sum(mean*n)/sum(n) FROM hr_30s h WHERE h.t30+30000>e.start_ms AND h.t30<=e.end_ms)) " +
                "FROM exercise e WHERE e.start_ms >= $lo AND e.start_ms < $hi$flagSql ORDER BY e.start_ms, e.end_ms DESC"
        ) { Ex(it.getLong(0), it.getLong(1), it.getInt(2), it.getString(3) ?: "", if (it.isNull(4)) null else it.getDouble(4)) }
        val out = ArrayList<Ex>()
        var lastEnd = -1L
        for (e in rows) {  // overlapping duplicates (same workout from two apps) dropped
            if (e.start < lastEnd) continue
            lastEnd = e.end
            out.add(e)
        }
        return out
    }

    internal fun series(db: SQLiteDatabase, z: ZoneId, d0: LocalDate, d1: LocalDate, maxhr: Double): LinkedHashMap<String, Day> {
        val dates = daysBetween(d0, d1)
        val bnd = dates.map { bounds(it, z) }
        val lo0 = bnd.first().first
        val hi1 = bnd.last().second
        val out = LinkedHashMap<String, Day>()
        dates.forEach { out[it.toString()] = Day(it.toString()) }
        val daysCte = "days(d,lo,hi) AS (VALUES " +
            dates.indices.joinToString(",") { "('${dates[it]}',${bnd[it].first},${bnd[it].second})" } + ")"

        for (i in dates.indices) {
            val (lo, hi) = bnd[i]
            val day = out[dates[i].toString()]!!
            db.rawQuery("SELECT sum(mean*n)/sum(n), min(\"min\"), max(\"max\") FROM hr_30s WHERE t30>=$lo AND t30<$hi", null).use { c ->
                if (c.moveToFirst() && !c.isNull(0)) {
                    day.hrMean = r(c.getDouble(0)); day.hrMin = r(c.getDouble(1)); day.hrMax = r(c.getDouble(2))
                }
            }
            db.rawQuery("SELECT avg(bpm) FROM resting_hr WHERE t>=$lo AND t<$hi", null).use { c ->
                if (c.moveToFirst() && !c.isNull(0)) day.restingHr = r(c.getDouble(0))
            }
        }

        // Interval metrics: per minute take the origin with the larger value (no summing across origins).
        fun dedup(table: String, col: String, f: (Day, Double, Long) -> Unit) {
            val sql = "WITH $daysCte, " +
                "m AS (SELECT origin, start_ms/60000 AS mn, sum($col) AS v FROM $table " +
                "WHERE start_ms>=$lo0 AND start_ms<$hi1 GROUP BY origin, mn), " +
                "b AS (SELECT mn, max(v) AS v FROM m GROUP BY mn) " +
                "SELECT d, sum(v), sum(v>=60) FROM b JOIN days ON mn*60000>=lo AND mn*60000<hi GROUP BY d"
            db.rawQuery(sql, null).use { c ->
                while (c.moveToNext()) out[c.getString(0)]?.let { f(it, c.getDouble(1), c.getLong(2)) }
            }
        }
        dedup("steps", "\"count\"") { d, v, a -> d.steps = v.toLong(); d.active = a }
        dedup("distance", "meters") { d, v, _ -> d.distance = v }
        dedup("total_calories", "kcal") { d, v, _ -> d.kcal = v }

        // Sleep belongs to the day it ends on. If several origins recorded it, use the fuller one.
        class Per(var asleep: Double = 0.0, val stg: LinkedHashMap<String, Double> = LinkedHashMap(), var hs: Double = 0.0, var hn: Int = 0)
        val per = LinkedHashMap<Pair<String, String>, Per>()
        val sleeps = db.rows("WITH $daysCte SELECT d, s.start_ms, s.end_ms, s.origin FROM sleep s JOIN days ON s.end_ms>=lo AND s.end_ms<hi") {
            listOf(it.getString(0), it.getLong(1), it.getLong(2), it.getString(3))
        }
        for (s in sleeps) {
            val d = s[0] as String; val st = s[1] as Long; val en = s[2] as Long; val org = s[3] as String
            val stg = LinkedHashMap<Int, Double>()
            db.rawQuery("SELECT stage, sum(end_ms-start_ms)/60000.0 FROM sleep_stage WHERE sleep_start_ms=? AND origin=? GROUP BY stage",
                arrayOf(st.toString(), org)).use { c -> while (c.moveToNext()) stg[c.getInt(0)] = c.getDouble(1) }
            val asleep = if (stg.isNotEmpty()) stg.filterKeys { it in ASLEEP }.values.sum() else (en - st) / 60000.0
            val a = per.getOrPut(d to org) { Per() }
            a.asleep += asleep
            for ((k, m) in stg) { val n = STAGE_NAMES[k] ?: k.toString(); a.stg[n] = (a.stg[n] ?: 0.0) + m }
            db.rawQuery("SELECT sum(rmssd), count(*) FROM hrv WHERE t>=$st AND t<=$en", null).use { c ->
                if (c.moveToFirst() && c.getLong(1) > 0) { a.hs += c.getDouble(0); a.hn += c.getInt(1) }
            }
        }
        for ((k, a) in per.entries.sortedBy { it.value.asleep }) {  // fullest last
            val day = out[k.first] ?: continue
            day.sleepMin = r(a.asleep)
            day.stages = a.stg.mapValues { r(it.value) }
            day.hrv = if (a.hn > 0) r(a.hs / a.hn) else null
        }

        for (ex in exercises(db, lo0, hi1)) {
            val d = java.time.Instant.ofEpochMilli(ex.start).atZone(z).toLocalDate().toString()
            val mins = (ex.end - ex.start) / 60000.0
            val intensity = if (ex.hrAvg != null && ex.hrAvg != 0.0) ex.hrAvg / maxhr else DEFAULT_INTENSITY
            out[d]?.let { it.exCount += 1; it.load += mins * intensity }
        }
        return out
    }

    // ---------- baselines & readiness ----------

    private class Base(
        val map: JSONObject, val restMean: Double?, val restSd: Double?, val restDays: Int,
        val hrvMean: Double?, val hrvSd: Double?, val hrvDays: Int,
        val sleepMean: Double?, val sleepDays: Int, val dataDays: Int, val weeklyLoad: Double,
    )

    private fun stats(v: List<Double>): Pair<Double?, Double?> {
        val n = v.size
        if (n == 0) return null to null
        val m = v.sum() / n
        val sd = if (n > 1) Math.sqrt(v.sumOf { (it - m) * (it - m) } / (n - 1)) else 0.0
        return m to sd
    }

    private fun baselinesFrom(days: List<Day>, maxhr: Double): Base {
        val o = JSONObject().put("window_days", days.size).put("max_hr", maxhr)
        val res = ArrayList<Triple<Double?, Double?, Int>>()
        for ((name, sel) in listOf<Pair<String, (Day) -> Double?>>(
            "resting_hr_28d" to { it.restingHr }, "hrv_28d" to { it.hrv }, "sleep_28d" to { it.sleepMin })) {
            val vals = days.mapNotNull(sel)
            val (m, sd) = stats(vals)
            val suffix = if (name == "sleep_28d") "_min" else ""
            val rm = r(m, 2); val rs = r(sd, 2)
            o.put("${name}_mean$suffix", j(rm)).put("${name}_sd$suffix", j(rs)).put("${name}_days", vals.size)
            res.add(Triple(rm, rs, vals.size))
        }
        val first = days.indexOfFirst { it.steps != 0L || it.hrMean != null || it.sleepMin != null }
        val span = if (first < 0) 0 else days.size - first
        o.put("data_days", span)
        val total = days.sumOf { it.load }
        val weekly = r(total / (maxOf(span, 7) / 7.0), 1)!!
        o.put("weekly_load_28d_mean", weekly)
        return Base(o, res[0].first, res[0].second, res[0].third, res[1].first, res[1].second, res[1].third,
            res[2].first, res[2].third, span, weekly)
    }

    internal class Ready(val score: Int?, val json: JSONObject)

    /**
     * Readiness v2: components sleep (the sleep score), hrv, resting_hr, load (ACWR) and subjective (check-in).
     * Without [x] (no DailyMetrics context) only hrv and resting_hr can be scored.
     */
    internal fun readiness(ser: Map<String, Day>, d: LocalDate, maxhr: Double, x: Extras? = null): Ready {
        val today = ser[d.toString()]!!
        val baseDays = (28 downTo 1).map { ser[d.minusDays(it.toLong()).toString()]!! }
        val b = baselinesFrom(baseDays, maxhr)
        val scores = LinkedHashMap<String, Double>()
        val comps = LinkedHashMap<String, JSONObject>()
        val text = HashMap<String, String>()
        val missing = LinkedHashMap<String, String>()
        val notes = ArrayList<String>()
        fun skip(key: String, why: String, note: Boolean = true) {
            missing[key] = why
            if (note) notes.add("${ReadinessMath.LABELS.getValue(key)} skipped: $why.")
        }
        fun f(fmt: String, vararg a: Any) = String.format(Locale.US, fmt, *a)

        if (x?.sleepScore != null) {
            val sc = r(clip(x.sleepScore))!!
            comps["sleep"] = JSONObject().put("score", sc).put("sleep_min", j(today.sleepMin))
            scores["sleep"] = sc
            text["sleep"] = "Sleep score ${sc.toInt()}" + (today.sleepMin?.let { ", ${Format.duration(Math.round(it))} asleep" } ?: "")
        } else skip("sleep", "no sleep score for this date")

        for ((name, x0, sign, floor) in listOf(
            Quad("hrv", today.hrv, 1, 0.05), Quad("resting_hr", today.restingHr, -1, 0.02))) {
            val hrvK = name == "hrv"
            val unit = if (hrvK) "ms" else "bpm"
            val days = if (hrvK) b.hrvDays else b.restDays
            if (x0 == null) skip(name, "no reading for this date")
            else if (days < MIN_BASELINE_DAYS) skip(name, "only $days baseline days (<$MIN_BASELINE_DAYS)")
            else {
                val m = (if (hrvK) b.hrvMean else b.restMean)!!
                val sd = (if (hrvK) b.hrvSd else b.restSd)!!
                val z = ReadinessMath.z(x0, m, sd, floor)
                val sc = r(ReadinessMath.zScore(z, sign))!!
                comps[name] = JSONObject().put("score", sc).put("value", x0).put("baseline_mean", j(r(m, 1))).put("z", j(r(z, 2)))
                scores[name] = sc
                text[name] = f("%s %.0f %s, %s", if (hrvK) "HRV" else "Resting HR", x0, unit, Format.sdPhrase(z))
            }
        }

        if (x?.acwr != null) {
            val sc = r(ReadinessMath.acwrScore(x.acwr))!!
            comps["load"] = JSONObject().put("score", sc).put("acwr", j(r(x.acwr, 2)))
                .put("acute_load", j(r(x.acute, 1))).put("chronic_load", j(r(x.chronic, 1)))
            scores["load"] = sc
            text["load"] = f("Training load ratio %.2f (7-day vs 28-day; 0.8-1.3 is comfortable)", x.acwr)
        } else skip("load", "needs >= 14 days of history and a non-trivial chronic load")

        if (x?.subjective != null) {
            val sc = r(ReadinessMath.subjectiveScore(x.subjective))!!
            comps["subjective"] = JSONObject().put("score", sc).put("checkin_mean", j(r(x.subjective, 2)))
            scores["subjective"] = sc
            text["subjective"] = f("Check-in %.1f of 5", x.subjective)
        } else skip("subjective", "no check-in for this date", note = false)

        val combined = ReadinessMath.combine(scores)
        val drivers = JSONArray()
        if (combined == null) {
            notes.add(0, "Not enough data for a readiness score (needs >= $MIN_BASELINE_DAYS days of baseline and at least ${ReadinessMath.MIN_COMPONENTS} signals).")
        } else {
            val pct = ReadinessMath.percents(combined.weights)
            for (k in combined.ranked) {
                val imp = combined.impact.getValue(k)
                drivers.put(JSONObject().put("key", k).put("label", ReadinessMath.LABELS.getValue(k))
                    .put("score", scores.getValue(k)).put("weight_pct", pct.getValue(k)).put("impact", r(imp, 2)!!)
                    .put("text", text.getValue(k)))
            }
            notes.addAll(0, combined.ranked.take(3).map { (if (combined.impact.getValue(it) >= 0) "Helping" else "Hurting") + ": " + text.getValue(it) })
        }
        val compsJson = JSONObject()
        for (k in ReadinessMath.WEIGHTS.keys) compsJson.put(k, comps[k] ?: JSONObject.NULL)
        val missJson = JSONObject().also { o -> missing.forEach { (k, v) -> o.put(k, v) } }
        val json = JSONObject().put("date", d.toString()).put("readiness_version", ReadinessMath.VERSION)
            .put("score", j(combined?.score))
            .put("components", compsJson).put("drivers", drivers).put("missing", missJson)
            .put("baseline", b.map).put("notes", JSONArray(notes))
        return Ready(combined?.score, json)
    }

    /** Extra readiness inputs from [DailyMetrics]; [subjective] is the check-in mean on 1..5. */
    internal class Extras(val sleepScore: Double?, val acwr: Double?, val acute: Double?, val chronic: Double?,
                          val subjective: Double? = null)

    private data class Quad(val name: String, val x: Double?, val sign: Int, val floor: Double)

    // ---------- endpoints ----------

    private fun summaryDay(db: SQLiteDatabase, q: Map<String, String>): JSONObject {
        val d = date(q, "date"); val z = zone(q); val mh = maxHr()
        val ser = series(db, z, d.minusDays(28), d, mh)
        return ser[d.toString()]!!.toJson(storedReadiness(db, d.toString(), d, z) ?: readiness(ser, d, mh).score)
    }

    private fun summaryRange(db: SQLiteDatabase, q: Map<String, String>): JSONObject {
        val from = date(q, "from"); val to = date(q, "to"); val z = zone(q); val mh = maxHr()
        if (to < from || to.toEpochDay() - from.toEpochDay() > 366) throw IllegalArgumentException("range must be 0..366 days and from <= to")
        val ser = series(db, z, from.minusDays(28), to, mh)
        val arr = JSONArray()
        for (d in daysBetween(from, to)) arr.put(ser[d.toString()]!!.toJson(storedReadiness(db, d.toString(), d, z) ?: readiness(ser, d, mh).score))
        return JSONObject().put("days", arr)
    }

    /** Readiness (rounded) from daily_metrics for [ds]; null when no row exists. */
    private fun storedReadiness(db: SQLiteDatabase, ds: String, d: LocalDate, z: ZoneId): Int? = try {
        if (zonedToday(z).toEpochDay() - d.toEpochDay() <= 1) DailyMetrics.ensure(appCtx!!, ds, ds)
        db.rawQuery("SELECT readiness FROM daily_metrics WHERE date=?", arrayOf(ds)).use { c ->
            if (c.moveToFirst() && !c.isNull(0)) Math.rint(c.getDouble(0)).toInt() else null
        }
    } catch (e: Exception) { AppLog.d("LocalApi stored readiness failed: ${e.message}"); null }

    private fun zonedToday(z: ZoneId) = LocalDate.now(z)

    @Volatile private var appCtx: Context? = null

    private fun readinessEndpoint(db: SQLiteDatabase, q: Map<String, String>): JSONObject {
        val d = date(q, "date"); val z = zone(q); val mh = maxHr()
        val ctx = appCtx
        if (ctx != null) try {
            DailyMetrics.ensure(ctx, d.toString(), d.toString())
            DailyMetrics.stored(ctx, d.toString())?.optString("readiness_json")?.takeIf { it.isNotEmpty() && it != "null" }
                ?.let { return JSONObject(it) }
        } catch (e: Exception) { AppLog.d("LocalApi readiness via daily_metrics failed: ${e.message}") }
        return readiness(series(db, z, d.minusDays(28), d, mh), d, mh).json
    }

    // ---------- daily metrics endpoints ----------
    /*
     * GET /daily?from=YYYY-MM-DD&to=YYYY-MM-DD   (range 0..366 days)
     *   {"days":[{"date","tz","computed_ms","readiness","readiness_breakdown":{date,score,components,baseline,notes}|null,
     *             "sleep_score","sleep_breakdown":{score,need_min,sleep_debt_min,components{duration,efficiency,restorative,
     *             consistency:{score,weight,...}},notes[]}|null,"load_trimp","acute_load","chronic_load","acwr",
     *             "rhr","hrv","sleep_min","steps","insights":[{id,severity,title,detail}]}]}
     * GET /insights?date=YYYY-MM-DD
     *   {"date","computed_ms","insights":[{id,severity:info|watch|alert,title,detail}]}   (empty when baseline < 7 days)
     * GET /load?from&to
     *   {"days":[{"date","cardio","minutes_light|moderate|vigorous|peak","coverage","acute","chronic","acwr","exercise_trimp"}]}
     *   cardio = whole-day heart-rate load; acute=EWMA 7 d, chronic=EWMA 28 d of it; acwr=acute/chronic or null
     *   (chronic ~0 or < 14 days of history); exercise_trimp = old per-workout TRIMP (information).
     * Missing/recent rows are computed on demand. Fields are null when not computable.
     */
    private fun parseOrNull(s: String?): Any = try {
        if (s.isNullOrEmpty() || s == "null") JSONObject.NULL
        else if (s.trimStart().startsWith("[")) JSONArray(s) else JSONObject(s)
    } catch (e: Exception) { JSONObject.NULL }

    private fun rangeRows(ctx: Context, q: Map<String, String>): List<JSONObject> {
        val from = date(q, "from"); val to = date(q, "to")
        if (to < from || to.toEpochDay() - from.toEpochDay() > 366) throw IllegalArgumentException("range must be 0..366 days and from <= to")
        DailyMetrics.ensure(ctx, from.toString(), to.toString())
        return LocalStore.get(ctx).getDaily(from.toString(), to.toString())
    }

    private fun dailyEndpoint(ctx: Context, q: Map<String, String>): JSONObject {
        val arr = JSONArray()
        for (row in rangeRows(ctx, q)) {
            val o = JSONObject()
            for (k in listOf("date", "tz", "computed_ms", "readiness")) o.put(k, row.opt(k) ?: JSONObject.NULL)
            o.put("readiness_breakdown", parseOrNull(row.optString("readiness_json")))
            o.put("sleep_score", row.opt("sleep_score") ?: JSONObject.NULL)
            o.put("sleep_breakdown", parseOrNull(row.optString("sleep_json")))
            for (k in listOf("load_trimp", "acute_load", "chronic_load", "acwr", "rhr", "hrv", "sleep_min", "steps"))
                o.put(k, row.opt(k) ?: JSONObject.NULL)
            o.put("insights", parseOrNull(row.optString("insights_json")).let { if (it === JSONObject.NULL) JSONArray() else it })
            arr.put(o)
        }
        return JSONObject().put("days", arr)
    }

    private fun insightsEndpoint(ctx: Context, q: Map<String, String>): JSONObject {
        val d = date(q, "date").toString()
        DailyMetrics.ensure(ctx, d, d)
        val row = DailyMetrics.stored(ctx, d)
        return JSONObject().put("date", d).put("computed_ms", row?.opt("computed_ms") ?: JSONObject.NULL)
            .put("insights", parseOrNull(row?.optString("insights_json")).let { if (it === JSONObject.NULL) JSONArray() else it })
    }

    private fun loadEndpoint(ctx: Context, q: Map<String, String>): JSONObject {
        val arr = JSONArray()
        val rows = rangeRows(ctx, q)
        val from = date(q, "from"); val to = date(q, "to")
        val ld = com.fitair.app.data.dao.LoadDao.rows(ctx, from, to).associateBy { it.date }
        for (row in rows) {
            val l = ld[row.optString("date")]
            arr.put(JSONObject().put("date", row.opt("date"))
                .put("cardio", j(l?.cardio?.let { r(it, 1) }))
                .put("minutes_light", j(l?.zLight)).put("minutes_moderate", j(l?.zMod))
                .put("minutes_vigorous", j(l?.zVig)).put("minutes_peak", j(l?.zPeak))
                .put("coverage", j(l?.coverage?.let { r(it, 2) }))
                .put("acute", row.opt("acute_load") ?: JSONObject.NULL).put("chronic", row.opt("chronic_load") ?: JSONObject.NULL)
                .put("acwr", row.opt("acwr") ?: JSONObject.NULL)
                .put("exercise_trimp", row.opt("load_trimp") ?: JSONObject.NULL))
        }
        return JSONObject().put("days", arr)
    }

    private fun baselinesEndpoint(db: SQLiteDatabase, q: Map<String, String>): JSONObject {
        val z = zone(q); val mh = maxHr()
        val today = java.time.LocalDate.now(z)
        val ser = series(db, z, today.minusDays(27), today, mh)
        return baselinesFrom(ser.values.toList(), mh).map
    }

    private fun hr(db: SQLiteDatabase, q: Map<String, String>): JSONObject {
        val from = long(q, "from_ms"); val to = long(q, "to_ms")
        val req = q["bucket_s"]?.toLongOrNull() ?: 60L
        val pts = JSONArray()
        // Raw samples exist only inside exercise windows (+-5 min): raw only when bucket_s=0 and the range lies in one.
        val raw = req <= 0 && db.rawQuery("SELECT 1 FROM exercise WHERE start_ms-300000<=? AND end_ms+300000>=? LIMIT 1",
            arrayOf(from.toString(), to.toString())).use { it.moveToFirst() }
        if (raw) {
            db.rawQuery("SELECT t, bpm FROM heart_rate WHERE t>=$from AND t<$to ORDER BY t", null).use { c ->
                while (c.moveToNext()) {
                    val b = c.getDouble(1)
                    pts.put(JSONObject().put("t", c.getLong(0)).put("min", b).put("mean", r(b)).put("max", b).put("n", 1))
                }
            }
            return JSONObject().put("bucket_s", 0).put("resolution", "raw").put("points", pts)
        }
        val bucket = if (req <= 0) 30L else ((req + 29) / 30) * 30  // multiples of 30 s
        val b = bucket * 1000
        db.rawQuery("SELECT (t30/$b)*$b AS k, min(\"min\"), sum(mean*n)/sum(n), max(\"max\"), sum(n) FROM hr_30s " +
            "WHERE t30>=$from AND t30<$to GROUP BY k ORDER BY k", null).use { c ->
            while (c.moveToNext()) pts.put(JSONObject().put("t", c.getLong(0)).put("min", c.getDouble(1))
                .put("mean", r(c.getDouble(2))).put("max", c.getDouble(3)).put("n", c.getLong(4)))
        }
        return JSONObject().put("bucket_s", bucket).put("resolution", "30s").put("points", pts)
    }

    private fun workouts(db: SQLiteDatabase, q: Map<String, String>): JSONObject {
        val from = date(q, "from"); val to = date(q, "to"); val z = zone(q); val mh = maxHr()
        val arr = JSONArray()
        val withFlagged = q["include_flagged"] == "1" || q["include_flagged"] == "true"
        for (ex in exercises(db, bounds(from, z).first, bounds(to, z).second, withFlagged)) {
            val flag = db.rawQuery("SELECT verdict, auto FROM exercise_flag WHERE start_ms=?", arrayOf(ex.start.toString())).use {
                if (it.moveToFirst()) it.getString(0) + (if (it.getInt(1) == 1) " (auto)" else " (you)") else "none"
            }
            val ts = ArrayList<Long>(); val bs = ArrayList<Double>()
            db.rawQuery("SELECT t, bpm FROM heart_rate WHERE t BETWEEN ${ex.start} AND ${ex.end + 75000} ORDER BY t", null).use { c ->
                while (c.moveToNext()) { ts.add(c.getLong(0)); bs.add(c.getDouble(1)) }
            }
            var resolution = "raw"
            if (ts.none { it in ex.start..ex.end }) {  // no raw samples: fall back to the 30 s tier (bucket mean at mid-bucket)
                ts.clear(); bs.clear()
                db.rawQuery("SELECT t30+15000, sum(mean*n)/sum(n) FROM hr_30s WHERE t30+30000>${ex.start} AND t30<=${ex.end + 75000} " +
                    "GROUP BY t30 ORDER BY t30", null).use { c ->
                    while (c.moveToNext()) { ts.add(c.getLong(0)); bs.add(c.getDouble(1)) }
                }
                resolution = if (ts.isEmpty()) "none" else "30s"
            }
            val o = JSONObject().put("start", ex.start).put("end", ex.end).put("type", ex.type).put("title", ex.title)
                .put("duration_min", j(r((ex.end - ex.start) / 60000.0))).put("resolution", resolution).put("flag", flag)
            workoutMetrics(ts, bs, ex.start, ex.end, mh, o)
            arr.put(o)
        }
        return JSONObject().put("workouts", arr)
    }

    private fun workoutMetrics(ts: List<Long>, bs: List<Double>, start: Long, end: Long, maxhr: Double, o: JSONObject) {
        val zones = DoubleArray(5)
        var hrMean: Double? = null; var hrMax: Double? = null; var rec: Double? = null; var drift: Double? = null
        val idx = ts.indices.filter { ts[it] in start..end }
        if (idx.isNotEmpty()) {
            val inside = idx.map { bs[it] }
            hrMean = r(mean(inside)); hrMax = r(inside.max())
            fun win(a: Long, z: Long): List<Double> = ts.indices.filter { ts[it] in a..z }.map { bs[it] }
            val atEnd = win(end - 15000, end); val after = win(end + 45000, end + 75000)
            if (atEnd.isNotEmpty() && after.isNotEmpty()) rec = r(mean(atEnd)!! - mean(after)!!)
            val dur = end - start
            if (dur >= 10 * 60000L) {  // skip 10% warm-up, compare second half with first half
                val t0 = start + 0.1 * dur
                val mid = (t0 + end) / 2
                val a = idx.filter { ts[it] >= t0 && ts[it] < mid }.map { bs[it] }
                val c = idx.filter { ts[it] >= mid }.map { bs[it] }
                if (a.isNotEmpty() && c.isNotEmpty()) {
                    val ma = mean(a)!!
                    if (ma != 0.0) drift = r((mean(c)!! - ma) / ma * 100, 2)
                }
            }
            for ((i, k) in idx.withIndex()) {  // each sample covers the time until the next (capped at 2 min)
                val nxt = if (i + 1 < idx.size) ts[idx[i + 1]] else end
                val frac = bs[k] / maxhr
                if (frac >= 0.5) zones[minOf(((frac - 0.5) / 0.1).toInt(), 4)] += minOf(maxOf(nxt - ts[k], 0L), 120000L) / 60000.0
            }
        }
        o.put("hr_mean", j(hrMean)).put("hr_max", j(hrMax)).put("hr_recovery_1min", j(rec)).put("drift_pct", j(drift))
        val z = JSONObject()
        for (i in 0 until 5) z.put("z${i + 1}", j(r(zones[i])))
        o.put("minutes_in_zone", z)
    }
}
