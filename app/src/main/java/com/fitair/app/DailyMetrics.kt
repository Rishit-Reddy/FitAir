package com.fitair.app

import android.content.Context
import com.fitair.app.analytics.ReadinessMath
import com.fitair.app.analytics.SleepMath
import com.fitair.app.core.Num
import com.fitair.app.data.dao.LoadDao
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/**
 * The app's own daily metrics, computed per local date and stored in daily_metrics:
 *  - sleep_score 0-100 = weighted mean of available components (renormalised):
 *      duration 0.40 (asleep / personal need; need = 450 min moved halfway toward the 28-day typical, clamped 360..540),
 *      efficiency 0.25 (asleep / (asleep + awake stages); 65% -> 0, 90% -> 100),
 *      restorative 0.20 ((deep+REM) / asleep; 38% -> 100),
 *      consistency 0.15 (std dev of sleep midpoints over 7 nights; <=20 min -> 100, >=90 min -> 0).
 *    sleep_debt_min = sum over the last 7 nights with data of max(0, need - asleep).
 *  - load: whole-day heart-rate cardio load (CardioLoad, stored in load_day by LoadDao); acute = EWMA 7 d, chronic = EWMA 28 d,
 *    acwr = acute / chronic (null if chronic < 1 or < 14 days of history). load_trimp is the old Banister TRIMP of the
 *    (unflagged) exercise sessions, information only.
 *  - readiness v3: see ReadinessMath (sleep score, HRV, resting HR, whole-day load ratio, check-in), version stored in readiness_json;
 *    rows older than 2 days are frozen unless incomplete (inputs exist that the row lacks), forced, or rebuilt ([rebuildAll]).
 *  - insights: conservative rules, suppressed with < 7 days of history. Not medical advice.
 */
object DailyMetrics {
    private const val HIST_DAYS = 90
    private const val MIN_BASE = 7
    private const val STALE_MS = 10 * 60_000L
    private val SLEEP_W = SleepMath.WEIGHTS
    private const val MIG_READINESS = "readiness_v3_done"
    private val lock = Any()
    private const val MIG_DAYS = 120

    fun stored(ctx: Context, date: String): JSONObject? = LocalStore.get(ctx).getDaily(date, date).firstOrNull()

    /** Computes (and stores) [date] unless a frozen row from an earlier day exists. Returns the stored row. */
    fun compute(ctx: Context, date: String): JSONObject = compute(ctx, date, false)

    fun compute(ctx: Context, date: String, force: Boolean): JSONObject {
        computeDates(ctx, listOf(LocalDate.parse(date)), force)
        return stored(ctx, date) ?: JSONObject()
    }

    /**
     * Recomputes the last [days] days (days older than 2 days are only filled in when missing) and repairs older rows that are
     * incomplete, i.e. rows computed before their sleep / steps / heart-rate inputs had been synced.
     */
    fun recomputeRecent(ctx: Context, days: Int) {
        val today = LocalDate.now(ZoneId.systemDefault())
        computeDates(ctx, (days.coerceAtLeast(1) - 1 downTo 0).map { today.minusDays(it.toLong()) }, false)
        repairIncomplete(ctx, today.minusDays(MIG_DAYS.toLong()), today.minusDays(2))
    }

    /** True when the app has completed at least one sync (so a recompute can see real data). */
    private fun hasSynced(ctx: Context): Boolean =
        ctx.getSharedPreferences(SyncPrefs.FILE, Context.MODE_PRIVATE).getLong(SyncPrefs.LAST, 0L) > 0L

    /**
     * One-time (meta flag) forced recompute of the last 120 days after the readiness v3 change. Call from an IO thread.
     * It does nothing before the first completed sync: running it earlier would store (and freeze) empty rows.
     */
    fun migrateIfNeeded(ctx: Context) = synchronized(lock) {
        val store = LocalStore.get(ctx)
        if (store.metaGet(MIG_READINESS) != null) return@synchronized
        if (!hasSynced(ctx) || !hasInputs(store)) { AppLog.d("readiness v3 migration: waiting for the first sync"); return@synchronized }
        val today = LocalDate.now(ZoneId.systemDefault())
        AppLog.d("readiness v3: recomputing last $MIG_DAYS days")
        computeDates(ctx, (MIG_DAYS - 1 downTo 0).map { today.minusDays(it.toLong()) }, true)
        store.metaSet(MIG_READINESS, ReadinessMath.VERSION.toString())
    }

    private fun hasInputs(store: LocalStore): Boolean =
        listOf("sleep", "hr_30s", "steps").any { t -> store.db.rawQuery("SELECT 1 FROM $t LIMIT 1", null).use { it.moveToFirst() } }

    /** Forced recompute of every date from [from] to today (used after a Drive restore edits history). */
    fun recomputeFrom(ctx: Context, from: LocalDate) {
        val today = LocalDate.now(ZoneId.systemDefault())
        val start = maxOf(from, today.minusDays(MIG_DAYS.toLong() + 90))
        computeDates(ctx, LocalApi.daysBetween(start, today), true)
    }

    /** Earliest local date with any sleep / hr_30s / steps row, or null on an empty database. */
    fun earliestDataDate(ctx: Context): LocalDate? {
        val db = LocalStore.get(ctx).db
        var min = Long.MAX_VALUE
        for ((t, c) in listOf("sleep" to "end_ms", "hr_30s" to "t30", "steps" to "start_ms")) {
            db.rawQuery("SELECT MIN($c) FROM $t", null).use { if (it.moveToFirst() && !it.isNull(0)) min = minOf(min, it.getLong(0)) }
        }
        if (min == Long.MAX_VALUE || min < 946684800000L) return null
        return java.time.Instant.ofEpochMilli(min).atZone(ZoneId.systemDefault()).toLocalDate()
    }

    /**
     * Forced recompute (30-day chunks, oldest first) of daily_metrics and load_day from the earliest data to today.
     * [onProgress] gets 0..100. Returns the number of days. Logs "rebuild: N days in X ms".
     */
    fun rebuildAll(ctx: Context, onProgress: (Int) -> Unit = {}): Int {
        val t0 = System.currentTimeMillis()
        val today = LocalDate.now(ZoneId.systemDefault())
        val first = earliestDataDate(ctx) ?: return 0
        val all = LocalApi.daysBetween(first, today)
        val chunks = all.chunked(30)
        onProgress(0)
        for ((i, ch) in chunks.withIndex()) {
            computeDates(ctx, ch, true)
            onProgress(((i + 1) * 100) / chunks.size)
        }
        LocalStore.get(ctx).metaSet(MIG_READINESS, ReadinessMath.VERSION.toString())  // everything is at the current version now
        AppLog.d("rebuild: ${all.size} days in ${System.currentTimeMillis() - t0} ms")
        return all.size
    }

    /** Dates in [from, to] whose stored row lacks inputs that exist now (sleep, steps or heart rate), oldest first. */
    fun incompleteDates(ctx: Context, from: LocalDate, to: LocalDate): List<LocalDate> {
        if (to.isBefore(from)) return emptyList()
        val store = LocalStore.get(ctx)
        val z = ZoneId.systemDefault()
        val rows = store.getDaily(from.toString(), to.toString()).associateBy { it.optString("date") }
        val load = LoadDao.rows(ctx, from, to).associateBy { it.date }
        val out = ArrayList<LocalDate>()
        fun has(sql: String, a: String, b: String) = store.db.rawQuery(sql, arrayOf(a, b)).use { it.moveToFirst() }
        for (d in LocalApi.daysBetween(from, to)) {
            val row = rows[d.toString()] ?: continue
            val (lo, hi) = LocalApi.bounds(d, z)
            val l = lo.toString(); val h = hi.toString()
            val bad = (!row.has("sleep_min") && has("SELECT 1 FROM sleep WHERE end_ms>=? AND end_ms<? LIMIT 1", l, h)) ||
                (row.optLong("steps", 0L) == 0L && has("SELECT 1 FROM steps WHERE start_ms>=? AND start_ms<? LIMIT 1", l, h)) ||
                (((load[d.toString()]?.coverage ?: 0.0) <= 0.0 && (load[d.toString()]?.cardio ?: 0.0) <= 0.0) &&
                    has("SELECT 1 FROM hr_30s WHERE t30>=? AND t30<? LIMIT 1", l, h))
            if (bad) out.add(d)
        }
        return out
    }

    private fun repairIncomplete(ctx: Context, from: LocalDate, to: LocalDate) {
        try {
            val bad = incompleteDates(ctx, from, to)
            if (bad.isNotEmpty()) { AppLog.d("DailyMetrics: repairing ${bad.size} incomplete day(s)"); computeDates(ctx, bad, true) }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e
        } catch (e: Exception) { AppLog.e("DailyMetrics repair failed", e) }
    }

    /** Fills missing rows in [from, to] and refreshes rows of the last 2 days when older than 10 min. */
    fun ensure(ctx: Context, from: String, to: String) {
        val z = ZoneId.systemDefault()
        val today = LocalDate.now(z)
        val have = LocalStore.get(ctx).getDaily(from, to).associateBy { it.optString("date") }
        val now = System.currentTimeMillis()
        val todo = LocalApi.daysBetween(LocalDate.parse(from), LocalDate.parse(to)).filter { d ->
            val row = have[d.toString()]
            if (d.isAfter(today)) false
            else if (row == null) true
            else today.toEpochDay() - d.toEpochDay() <= 1 && now - row.optLong("computed_ms", 0) > STALE_MS
        }.takeLast(120)
        if (todo.isNotEmpty()) computeDates(ctx, todo, false)
        repairIncomplete(ctx, LocalDate.parse(from), minOf(LocalDate.parse(to), today.minusDays(2)))
    }

    // ---------- core ----------

    private class Ctx(val ser: Map<String, LocalApi.Day>, val mh: Double, val z: ZoneId, val db: android.database.sqlite.SQLiteDatabase) {
        val sleepMain = HashMap<String, Pair<Long, Long>?>()
        val resp = HashMap<String, Double?>()
        fun day(d: LocalDate) = ser[d.toString()]
    }

    /** [trimp] = old exercise TRIMP (information); acute/chronic/ratio = whole-day cardio load from load_day. */
    private class Load(val trimp: Double, val acute: Double?, val chronic: Double?, val ratio: Double?)

    private fun computeDates(ctx: Context, dates: List<LocalDate>, force: Boolean) = synchronized(lock) {
        computeDatesLocked(ctx, dates, force)
    }

    private fun computeDatesLocked(ctx: Context, dates: List<LocalDate>, force: Boolean) {
        if (dates.isEmpty()) return
        val t0 = System.currentTimeMillis()
        val store = LocalStore.get(ctx)
        val z = ZoneId.systemDefault()
        val today = LocalDate.now(z)
        val existing = store.getDaily(dates.min().toString(), dates.max().toString()).associateBy { it.optString("date") }
        val todo = dates.filter { d ->
            force || existing[d.toString()] == null || today.toEpochDay() - d.toEpochDay() <= 1
        }
        if (todo.isEmpty()) return
        val mh = LoadDao.hrMax(ctx).value
        try { LoadDao.compute(ctx, todo, force) } catch (e: kotlinx.coroutines.CancellationException) { throw e
        } catch (e: Exception) { AppLog.e("load_day failed (readiness load driver will be missing)", e) }
        val ser = LocalApi.series(store.db, z, todo.min().minusDays(HIST_DAYS.toLong()), todo.max(), mh)
        val c = Ctx(ser, mh, z, store.db)
        val loads = loadSeries(c, LoadDao.rows(ctx, todo.min(), todo.max()).associateBy { it.date })
        for (d in todo) {
            try {
                store.upsertDaily(row(c, d, loads, z))
            } catch (e: Exception) {
                AppLog.e("DailyMetrics $d failed", e)
            }
        }
        AppLog.d("DailyMetrics: ${todo.size} day(s) in ${System.currentTimeMillis() - t0} ms")
    }

    private fun jn(x: Double?, n: Int = 2): Any = LocalApi.j(LocalApi.r(x, n))

    private fun row(c: Ctx, d: LocalDate, loads: Map<String, Load>, z: ZoneId): JSONObject {
        val day = c.day(d)!!
        val ld = loads[d.toString()]
        val acwr = ld?.ratio
        val (sleepScore, sleepJson) = sleepScore(c, d)
        val extras = LocalApi.Extras(sleepScore, acwr, ld?.acute, ld?.chronic, checkinMean(c, d))
        val ready = LocalApi.readiness(c.ser, d, c.mh, extras)
        val insights = insights(c, d, ld, acwr, sleepJson)
        return JSONObject()
            .put("date", d.toString()).put("tz", z.id).put("computed_ms", System.currentTimeMillis())
            .put("readiness", LocalApi.j(ready.score?.toDouble())).put("readiness_json", ready.json.toString())
            .put("sleep_score", jn(sleepScore, 1)).put("sleep_json", sleepJson.toString())
            .put("load_trimp", jn(ld?.trimp, 1)).put("acute_load", jn(ld?.acute, 1)).put("chronic_load", jn(ld?.chronic, 1))
            .put("acwr", jn(acwr, 2))
            .put("rhr", LocalApi.j(day.restingHr)).put("hrv", LocalApi.j(day.hrv))
            .put("sleep_min", LocalApi.j(day.sleepMin?.let { Math.round(it) })).put("steps", day.steps)
            .put("insights_json", insights.toString())
    }

    /** Mean of the day's check-in answers (1..5, 5 = best), or null without a check-in. */
    private fun checkinMean(c: Ctx, d: LocalDate): Double? = try {
        c.db.rawQuery("SELECT energy, soreness, mood, stress FROM checkin WHERE date=?", arrayOf(d.toString())).use { cur ->
            if (!cur.moveToFirst()) null
            else (0..3).filter { !cur.isNull(it) }.map { cur.getDouble(it) }.let { Num.mean(it) }
        }
    } catch (e: Exception) { null }

    // ---------- training load ----------

    private fun loadSeries(c: Ctx, rows: Map<String, com.fitair.app.data.dao.LoadDayRow>): Map<String, Load> {
        val dates = c.ser.keys.map { LocalDate.parse(it) }.sorted()
        val lo = LocalApi.bounds(dates.first(), c.z).first
        val hi = LocalApi.bounds(dates.last(), c.z).second
        val perDay = HashMap<String, Double>()
        for (ex in LocalApi.exercises(c.db, lo, hi)) {  // flagged (not real exercise) sessions are left out
            val ds = java.time.Instant.ofEpochMilli(ex.start).atZone(c.z).toLocalDate()
            val rest = restingBaseline(c, ds)
            perDay[ds.toString()] = (perDay[ds.toString()] ?: 0.0) + trimp(c.db, ex, rest, c.mh)
        }
        val out = HashMap<String, Load>()
        for (d in dates) {
            val r = rows[d.toString()]
            out[d.toString()] = Load(perDay[d.toString()] ?: 0.0, r?.acute, r?.chronic, r?.ratio)
        }
        return out
    }

    private fun restingBaseline(c: Ctx, d: LocalDate): Double {
        val v = (1..28).mapNotNull { c.day(d.minusDays(it.toLong()))?.restingHr }
        return if (v.size >= 3) v.average() else c.day(d)?.restingHr ?: 60.0
    }

    private fun trimp(db: android.database.sqlite.SQLiteDatabase, ex: LocalApi.Ex, rest: Double, hrMax: Double): Double {
        fun inc(bpm: Double, minutes: Double): Double {
            val hrr = ((bpm - rest) / maxOf(hrMax - rest, 1.0)).coerceIn(0.0, 1.0)
            return minutes * hrr * 0.64 * Math.exp(1.92 * hrr)
        }
        val ts = ArrayList<Long>(); val bs = ArrayList<Double>()
        db.rawQuery("SELECT t, bpm FROM heart_rate WHERE t BETWEEN ${ex.start} AND ${ex.end} ORDER BY t", null).use {
            while (it.moveToNext()) { ts.add(it.getLong(0)); bs.add(it.getDouble(1)) }
        }
        if (ts.isNotEmpty()) {
            var sum = 0.0
            for (i in ts.indices) {
                val nxt = if (i + 1 < ts.size) ts[i + 1] else ex.end
                sum += inc(bs[i], minOf(maxOf(nxt - ts[i], 0L), 120_000L) / 60000.0)
            }
            return sum
        }
        var sum = 0.0; var any = false
        db.rawQuery("SELECT sum(mean*n)/sum(n) FROM hr_30s WHERE t30+30000>${ex.start} AND t30<=${ex.end} GROUP BY t30", null).use {
            while (it.moveToNext()) { any = true; sum += inc(it.getDouble(0), 0.5) }
        }
        return if (any) sum else inc(rest + 0.5 * (hrMax - rest), (ex.end - ex.start) / 60000.0)  // no HR: assume 50% HRR
    }

    // ---------- sleep ----------

    private fun mainSleep(c: Ctx, d: LocalDate): Pair<Long, Long>? = c.sleepMain.getOrPut(d.toString()) {
        val (lo, hi) = LocalApi.bounds(d, c.z)
        c.db.rawQuery("SELECT start_ms, end_ms FROM sleep WHERE end_ms>=$lo AND end_ms<$hi ORDER BY end_ms-start_ms DESC LIMIT 1", null).use {
            if (it.moveToFirst()) it.getLong(0) to it.getLong(1) else null
        }
    }

    /** Sleep midpoint as minutes around midnight (-720..720). */
    private fun midpoint(c: Ctx, d: LocalDate): Double? {
        val s = mainSleep(c, d) ?: return null
        val mid = (s.first + s.second) / 2
        val m = java.time.Instant.ofEpochMilli(mid).atZone(c.z).let { it.hour * 60 + it.minute }.toDouble()
        return if (m >= 720) m - 1440 else m
    }

    private fun sd(v: List<Double>): Double = Num.sd(v)

    private fun sleepScore(c: Ctx, d: LocalDate): Pair<Double?, JSONObject> {
        val day = c.day(d)!!
        val notes = ArrayList<String>()
        val comps = JSONObject()
        val o = JSONObject()
        val asleep = day.sleepMin
        val typical = (1..28).mapNotNull { c.day(d.minusDays(it.toLong()))?.sleepMin }.let { if (it.size >= MIN_BASE) it.average() else null }
        val need = SleepMath.need(typical)
        val debtDays = (0..6).mapNotNull { c.day(d.minusDays(it.toLong()))?.sleepMin }
        val debt = debtDays.sumOf { maxOf(0.0, need - it) }
        o.put("need_min", LocalApi.r(need)).put("typical_28d_min", jn(typical, 1))
            .put("sleep_debt_min", LocalApi.r(debt)).put("sleep_debt_nights", debtDays.size)
        if (asleep == null || asleep <= 0) {
            notes.add("No sleep session ends on this date.")
            return null to o.put("score", JSONObject.NULL).put("components", comps).put("notes", JSONArray(notes))
        }
        val scores = LinkedHashMap<String, Double>()
        fun add(k: String, score: Double, extra: JSONObject) {
            val sc = LocalApi.r(LocalApi.clip(score))!!
            scores[k] = sc
            comps.put(k, extra.put("score", sc).put("weight", SLEEP_W[k]))
        }
        add("duration", SleepMath.durationScore(asleep, need), JSONObject().put("asleep_min", asleep).put("need_min", LocalApi.r(need)))
        notes.add(String.format(Locale.US, "Slept %.1f h vs need %.1f h%s.", asleep / 60, need / 60,
            if (typical != null) " (adapted to your 28-day typical)" else " (default need; adapts after 7 nights of history)"))

        val st = day.stages
        val awake = (st["awake"] ?: 0.0) + (st["awake_in_bed"] ?: 0.0) + (st["out_of_bed"] ?: 0.0)
        val deep = st["deep"] ?: 0.0; val rem = st["rem"] ?: 0.0
        val staged = st.containsKey("light") || st.containsKey("deep") || st.containsKey("rem")
        if (staged) {
            val eff = SleepMath.efficiency(asleep, awake)
            add("efficiency", SleepMath.efficiencyScore(eff), JSONObject().put("efficiency", LocalApi.r(eff, 3)).put("awake_min", LocalApi.r(awake)))
            if (st.containsKey("deep") || st.containsKey("rem")) {
                val prop = SleepMath.restorativeFraction(deep, rem, asleep)
                add("restorative", SleepMath.restorativeScore(prop), JSONObject().put("deep_rem_fraction", LocalApi.r(prop, 3))
                    .put("deep_min", LocalApi.r(deep)).put("rem_min", LocalApi.r(rem)))
            } else notes.add("Restorative skipped: no deep/REM stages.")
        } else notes.add("Efficiency and restorative skipped: no sleep stages for this night.")

        val mids = (0..6).mapNotNull { midpoint(c, d.minusDays(it.toLong())) }
        if (mids.size >= 4) {
            val s = sd(mids)
            add("consistency", SleepMath.consistencyScore(s), JSONObject().put("midpoint_sd_min", LocalApi.r(s)).put("nights", mids.size))
        } else notes.add("Consistency skipped: needs >= 4 nights with sleep in the last 7 days.")

        if (debt > 0) notes.add(String.format(Locale.US, "Sleep debt over the last %d nights with data: %.1f h.", debtDays.size, debt / 60))
        val score = SleepMath.combine(scores)
        o.put("score", LocalApi.j(score)).put("components", comps).put("notes", JSONArray(notes))
        return score to o
    }

    // ---------- insights ----------

    private fun insight(id: String, sev: String, title: String, detail: String) =
        JSONObject().put("id", id).put("severity", sev).put("title", title).put("detail", detail)

    private fun base(c: Ctx, d: LocalDate, sel: (LocalLoc) -> Double?): Triple<Double, Double, Int>? {
        val v = (1..28).mapNotNull { sel(LocalLoc(c, d.minusDays(it.toLong()))) }
        if (v.size < MIN_BASE) return null
        return Triple(v.average(), sd(v), v.size)
    }

    private class LocalLoc(val c: Ctx, val d: LocalDate)

    private fun respOf(c: Ctx, d: LocalDate): Double? = c.resp.getOrPut(d.toString()) {
        val s = mainSleep(c, d) ?: return@getOrPut null
        c.db.rawQuery("SELECT avg(rate) FROM respiratory_rate WHERE t>=${s.first} AND t<=${s.second}", null).use {
            if (it.moveToFirst() && !it.isNull(0)) it.getDouble(0) else null
        }
    }

    private fun insights(c: Ctx, d: LocalDate, ld: Load?, acwr: Double?, sleepJson: JSONObject): JSONArray {
        val out = JSONArray()
        val history = (1..28).count { k ->
            val x = c.day(d.minusDays(k.toLong())); x != null && (x.steps != 0L || x.hrMean != null || x.sleepMin != null)
        }
        if (history < MIN_BASE) return out  // too little data: stay quiet
        fun f(fmt: String, vararg a: Any) = String.format(Locale.US, fmt, *a)

        val rhrSel = { l: LocalLoc -> l.c.day(l.d)?.restingHr }
        val hrvSel = { l: LocalLoc -> l.c.day(l.d)?.hrv }
        fun z(dd: LocalDate, sel: (LocalLoc) -> Double?, floorAbs: Double, floorRel: Double): Double? {
            val x = sel(LocalLoc(c, dd)) ?: return null
            val b = base(c, dd, sel) ?: return null
            return (x - b.first) / maxOf(b.second, floorAbs, floorRel * b.first)
        }
        val rz0 = z(d, rhrSel, 1.0, 0.02); val rz1 = z(d.minusDays(1), rhrSel, 1.0, 0.02)
        if (rz0 != null && rz1 != null && rz0 >= 2 && rz1 >= 2) {
            out.put(insight("rhr_elevated", "watch", "Resting heart rate elevated for 2 days",
                f("Resting HR was at least 2 SD above your 28-day baseline on both days (z=%+.1f, %+.1f). Common causes include poor sleep, stress, alcohol, heat, hard training or illness.", rz0, rz1)))
        }
        val hz = z(d, hrvSel, 0.0, 0.05)
        if (hz != null && hz <= -1.5) {
            out.put(insight("hrv_low", "watch", "Overnight HRV low",
                f("Overnight HRV is %.1f SD below your 28-day baseline. One low night is common; look at the trend over several days.", -hz)))
        }
        val debt = sleepJson.optDouble("sleep_debt_min", 0.0)
        if (debt > 180) {
            out.put(insight("sleep_debt", if (debt > 360) "alert" else "watch", "Sleep debt building up",
                f("You are about %.1f h short of your sleep need over the last %d nights with data (need %.1f h/night).", debt / 60,
                    sleepJson.optInt("sleep_debt_nights"), sleepJson.optDouble("need_min") / 60)))
        }
        if (acwr != null && ld != null) {
            if (acwr > 1.5) out.put(insight("acwr_high", if (acwr > 2.0) "alert" else "watch", "Training load ramping up fast",
                f("Acute:chronic load ratio is %.2f (7-day load %.0f vs 28-day %.0f). Rapid increases are associated with higher injury and fatigue risk; consider an easier day.", acwr, ld.acute ?: 0.0, ld.chronic ?: 0.0)))
            else if (acwr < 0.8) out.put(insight("acwr_low", "info", "Training load below your recent norm",
                f("Acute:chronic load ratio is %.2f (7-day load %.0f vs 28-day %.0f). Fine during a planned rest or deload; if not planned, you may be losing fitness.", acwr, ld.acute ?: 0.0, ld.chronic ?: 0.0)))
        }
        if (rz0 != null && rz0 >= 1.5 && hz != null && hz <= -1.5) {
            val rr = respOf(c, d)
            val rb = base(c, d) { respOf(it.c, it.d) }
            if (rr != null && rb != null && (rr - rb.first) / maxOf(rb.second, 0.5) >= 1.5) {
                out.put(insight("strain_pattern", "watch", "Possible illness/strain pattern",
                    f("Resting HR (z=%+.1f), overnight HRV (z=%+.1f) and breathing rate (%.1f vs %.1f /min) all deviate from your baseline together. This can accompany illness, stress or heavy strain; the app cannot tell which. Rest and see a clinician if you feel unwell.", rz0, hz, rr, rb.first)))
            }
        }
        val mids = (0..2).mapNotNull { midpoint(c, d.minusDays(it.toLong())) }
        val prior = (3..30).mapNotNull { midpoint(c, d.minusDays(it.toLong())) }
        if (mids.size >= 3 && prior.size >= MIN_BASE) {
            val diff = mids.average() - prior.average()
            if (Math.abs(diff) >= 60) out.put(insight("sleep_timing_drift", "info", "Sleep timing drifting",
                f("Your last 3 sleep midpoints are on average %.0f min %s than your previous month.", Math.abs(diff), if (diff > 0) "later" else "earlier")))
        }
        return out
    }
}
