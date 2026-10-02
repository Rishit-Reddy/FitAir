package com.fitair.app

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.math.RoundingMode
import java.net.URLDecoder
import java.time.DateTimeException
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/**
 * On-device port of server/analytics.py + the read endpoints of server/app.py.
 * Same paths, same JSON shapes; computed from the local SQLite DB ([LocalStore]).
 */
object LocalApi {
    private val STAGE_NAMES = mapOf(0 to "unknown", 1 to "awake", 2 to "sleeping", 3 to "out_of_bed",
        4 to "light", 5 to "deep", 6 to "rem", 7 to "awake_in_bed")
    private val ASLEEP = setOf(2, 4, 5, 6)
    private val WEIGHTS = linkedMapOf("sleep" to 0.35, "hrv" to 0.30, "resting_hr" to 0.20, "load" to 0.15)
    private const val MIN_BASELINE_DAYS = 7
    private const val DEFAULT_INTENSITY = 0.6

    private fun maxHr(): Double = System.getenv("FITAIR_MAXHR")?.toDoubleOrNull() ?: 190.0

    fun get(ctx: Context, path: String): JSONObject {
        val t0 = System.currentTimeMillis()
        val p = if (path.startsWith("/")) path else "/$path"
        val route = p.substringBefore('?')
        val q = parseQuery(p.substringAfter('?', ""))
        val db = LocalStore.get(ctx).db
        val out = try {
            when (route) {
                "/summary/day" -> summaryDay(db, q)
                "/summary/range" -> summaryRange(db, q)
                "/hr" -> hr(db, q)
                "/workouts" -> workouts(db, q)
                "/readiness" -> readinessEndpoint(db, q)
                "/baselines" -> baselinesEndpoint(db, q)
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

    private fun r(x: Double?, n: Int = 1): Double? {
        if (x == null || x.isNaN() || x.isInfinite()) return x
        return BigDecimal(x).setScale(n, RoundingMode.HALF_EVEN).toDouble()  // exact-binary half-even, like Python round()
    }

    private fun j(x: Any?): Any = x ?: JSONObject.NULL

    private fun bounds(d: LocalDate, z: ZoneId): Pair<Long, Long> =
        d.atStartOfDay(z).toInstant().toEpochMilli() to d.plusDays(1).atStartOfDay(z).toInstant().toEpochMilli()

    private fun daysBetween(d0: LocalDate, d1: LocalDate): List<LocalDate> {
        val n = (d1.toEpochDay() - d0.toEpochDay()).toInt()
        return (0..n).map { d0.plusDays(it.toLong()) }
    }

    private fun clip(x: Double) = maxOf(0.0, minOf(100.0, x))

    private fun mean(v: List<Double>): Double? = if (v.isEmpty()) null else v.sum() / v.size

    private inline fun <T> SQLiteDatabase.rows(sql: String, args: Array<String> = emptyArray(), f: (android.database.Cursor) -> T): List<T> {
        val out = ArrayList<T>()
        rawQuery(sql, args).use { c -> while (c.moveToNext()) out.add(f(c)) }
        return out
    }

    // ---------- per-day series ----------

    private class Day(val date: String) {
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

    private class Ex(val start: Long, val end: Long, val type: Int, val title: String, val hrAvg: Double?)

    private fun exercises(db: SQLiteDatabase, lo: Long, hi: Long): List<Ex> {
        val rows = db.rows(
            "SELECT e.start_ms, e.end_ms, e.type, e.title, " +
                "(SELECT avg(bpm) FROM heart_rate h WHERE h.t BETWEEN e.start_ms AND e.end_ms) " +
                "FROM exercise e WHERE e.start_ms >= $lo AND e.start_ms < $hi ORDER BY e.start_ms, e.end_ms DESC"
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

    private fun series(db: SQLiteDatabase, z: ZoneId, d0: LocalDate, d1: LocalDate, maxhr: Double): LinkedHashMap<String, Day> {
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
            db.rawQuery("SELECT avg(bpm), min(bpm), max(bpm) FROM heart_rate WHERE t>=$lo AND t<$hi", null).use { c ->
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

    private class Ready(val score: Int?, val json: JSONObject)

    private fun readiness(ser: Map<String, Day>, d: LocalDate, maxhr: Double): Ready {
        val today = ser[d.toString()]!!
        val baseDays = (28 downTo 1).map { ser[d.minusDays(it.toLong()).toString()]!! }
        val b = baselinesFrom(baseDays, maxhr)
        val comps = LinkedHashMap<String, JSONObject>()
        val scores = LinkedHashMap<String, Double>()
        val text = HashMap<String, String>()
        var notes = ArrayList<String>()
        fun skip(label: String, why: String) { notes.add("$label skipped: $why.") }
        fun f(fmt: String, vararg a: Any) = String.format(Locale.US, fmt, *a)

        val sl = today.sleepMin
        if (sl == null) skip("Sleep", "no sleep session ends on this date")
        else if (b.sleepDays < MIN_BASELINE_DAYS) skip("Sleep", "only ${b.sleepDays} baseline days (<$MIN_BASELINE_DAYS)")
        else {
            val ratio = sl / b.sleepMean!!
            val sc = r(clip(100 - 200 * maxOf(0.0, 1 - ratio)))!!
            comps["sleep"] = JSONObject().put("score", sc).put("value_min", sl).put("ratio", j(r(ratio, 2)))
            scores["sleep"] = sc
            text["sleep"] = f("Sleep %.1f h vs 28-day mean %.1f h", sl / 60, b.sleepMean / 60)
        }

        for ((name, x0, sign, floor) in listOf(
            Quad("hrv", today.hrv, 1, 0.05), Quad("resting_hr", today.restingHr, -1, 0.02))) {
            val hrvK = name == "hrv"
            val label = if (hrvK) "Overnight HRV" else "Resting HR"
            val unit = if (hrvK) "ms" else "bpm"
            val days = if (hrvK) b.hrvDays else b.restDays
            if (x0 == null) skip(label, "no reading for this date")
            else if (days < MIN_BASELINE_DAYS) skip(label, "only $days baseline days (<$MIN_BASELINE_DAYS)")
            else {
                val m = (if (hrvK) b.hrvMean else b.restMean)!!
                val sd = (if (hrvK) b.hrvSd else b.restSd)!!
                val z = (x0 - m) / maxOf(sd, 1.0, floor * m)
                val sc = r(clip(75 + 25 * sign * z))!!
                comps[name] = JSONObject().put("score", sc).put("value", x0).put("z", j(r(z, 2)))
                scores[name] = sc
                text[name] = f("%s %.0f %s vs mean %.0f %s (z=%+.1f%s)", label, x0, unit, m, unit, z, if (sign < 0) ", lower is better" else "")
            }
        }

        if (b.dataDays < MIN_BASELINE_DAYS) skip("Training load", "only ${b.dataDays} baseline days (<$MIN_BASELINE_DAYS)")
        else if (b.weeklyLoad <= 0) skip("Training load", "no training history")
        else {
            val acute = baseDays.takeLast(7).sumOf { it.load }
            val ratio = acute / b.weeklyLoad
            val sc = r(clip(100 - maxOf(0.0, ratio - 1.1) * 100))!!
            comps["load"] = JSONObject().put("score", sc).put("acute_7d", j(r(acute, 1))).put("ratio", j(r(ratio, 2)))
            scores["load"] = sc
            text["load"] = f("7-day load %.0f vs weekly average %.0f (ratio %.2f)", acute, b.weeklyLoad, ratio)
        }

        var score: Int? = null
        if (comps.size < 2) {
            notes.add(0, "Not enough data for a readiness score (needs >= $MIN_BASELINE_DAYS days of baseline and at least two signals).")
        } else {
            val wsum = scores.keys.sumOf { WEIGHTS[it]!! }
            score = Math.rint(scores.entries.sumOf { WEIGHTS[it.key]!! * it.value } / wsum).toInt()
            val impact = scores.mapValues { WEIGHTS[it.key]!! * (it.value - 75) / wsum }
            val drivers = impact.keys.sortedBy { -Math.abs(impact[it]!!) }.take(3)
                .map { (if (impact[it]!! >= 0) "Helping" else "Hurting") + ": " + text[it] }
            notes = ArrayList(drivers + notes)
        }
        val compsJson = JSONObject()
        for (k in WEIGHTS.keys) compsJson.put(k, comps[k] ?: JSONObject.NULL)
        val json = JSONObject().put("date", d.toString()).put("score", j(score))
            .put("components", compsJson).put("baseline", b.map).put("notes", JSONArray(notes))
        return Ready(score, json)
    }

    private data class Quad(val name: String, val x: Double?, val sign: Int, val floor: Double)

    // ---------- endpoints ----------

    private fun summaryDay(db: SQLiteDatabase, q: Map<String, String>): JSONObject {
        val d = date(q, "date"); val z = zone(q); val mh = maxHr()
        val ser = series(db, z, d.minusDays(28), d, mh)
        return ser[d.toString()]!!.toJson(readiness(ser, d, mh).score)
    }

    private fun summaryRange(db: SQLiteDatabase, q: Map<String, String>): JSONObject {
        val from = date(q, "from"); val to = date(q, "to"); val z = zone(q); val mh = maxHr()
        if (to < from || to.toEpochDay() - from.toEpochDay() > 366) throw IllegalArgumentException("range must be 0..366 days and from <= to")
        val ser = series(db, z, from.minusDays(28), to, mh)
        val arr = JSONArray()
        for (d in daysBetween(from, to)) arr.put(ser[d.toString()]!!.toJson(readiness(ser, d, mh).score))
        return JSONObject().put("days", arr)
    }

    private fun readinessEndpoint(db: SQLiteDatabase, q: Map<String, String>): JSONObject {
        val d = date(q, "date"); val z = zone(q); val mh = maxHr()
        return readiness(series(db, z, d.minusDays(28), d, mh), d, mh).json
    }

    private fun baselinesEndpoint(db: SQLiteDatabase, q: Map<String, String>): JSONObject {
        val z = zone(q); val mh = maxHr()
        val today = java.time.LocalDate.now(z)
        val ser = series(db, z, today.minusDays(27), today, mh)
        return baselinesFrom(ser.values.toList(), mh).map
    }

    private fun hr(db: SQLiteDatabase, q: Map<String, String>): JSONObject {
        val from = long(q, "from_ms"); val to = long(q, "to_ms")
        val bucket = q["bucket_s"]?.toLongOrNull() ?: 60L
        val pts = JSONArray()
        if (bucket <= 0) {
            db.rawQuery("SELECT t, bpm FROM heart_rate WHERE t>=$from AND t<$to ORDER BY t", null).use { c ->
                while (c.moveToNext()) {
                    val b = c.getDouble(1)
                    pts.put(JSONObject().put("t", c.getLong(0)).put("min", b).put("mean", r(b)).put("max", b).put("n", 1))
                }
            }
        } else {
            val b = bucket * 1000
            db.rawQuery("SELECT (t/$b)*$b AS k, min(bpm), avg(bpm), max(bpm), count(*) FROM heart_rate " +
                "WHERE t>=$from AND t<$to GROUP BY k ORDER BY k", null).use { c ->
                while (c.moveToNext()) pts.put(JSONObject().put("t", c.getLong(0)).put("min", c.getDouble(1))
                    .put("mean", r(c.getDouble(2))).put("max", c.getDouble(3)).put("n", c.getLong(4)))
            }
        }
        return JSONObject().put("bucket_s", maxOf(bucket, 0L)).put("points", pts)
    }

    private fun workouts(db: SQLiteDatabase, q: Map<String, String>): JSONObject {
        val from = date(q, "from"); val to = date(q, "to"); val z = zone(q); val mh = maxHr()
        val arr = JSONArray()
        for (ex in exercises(db, bounds(from, z).first, bounds(to, z).second)) {
            val ts = ArrayList<Long>(); val bs = ArrayList<Double>()
            db.rawQuery("SELECT t, bpm FROM heart_rate WHERE t BETWEEN ${ex.start} AND ${ex.end + 75000} ORDER BY t", null).use { c ->
                while (c.moveToNext()) { ts.add(c.getLong(0)); bs.add(c.getDouble(1)) }
            }
            val o = JSONObject().put("start", ex.start).put("end", ex.end).put("type", ex.type).put("title", ex.title)
                .put("duration_min", j(r((ex.end - ex.start) / 60000.0)))
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
