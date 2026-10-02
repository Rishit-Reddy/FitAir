package com.fitair.app

import android.content.Context
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
 *  - load: Banister TRIMP per exercise (male coefficients 0.64 * e^(1.92 * HRr)) integrated over the in-window heart rate,
 *    HRr = (HR - rest) / (HRmax - rest), rest = mean resting HR of the prior 28 days (60 if unknown).
 *    acute = EWMA 7 d, chronic = EWMA 28 d, acwr = acute / chronic (null if chronic < 1 or < 14 days of history).
 *  - readiness: LocalApi.readiness with extra sleep_quality + acwr components; frozen for days older than 2 days.
 *  - insights: conservative rules, suppressed with < 7 days of history. Not medical advice.
 */
object DailyMetrics {
    private const val HIST_DAYS = 90
    private const val CHRONIC_EPS = 1.0
    private const val MIN_HISTORY = 14
    private const val MIN_BASE = 7
    private const val STALE_MS = 10 * 60_000L
    private val SLEEP_W = linkedMapOf("duration" to 0.40, "efficiency" to 0.25, "restorative" to 0.20, "consistency" to 0.15)

    fun stored(ctx: Context, date: String): JSONObject? = LocalStore.get(ctx).getDaily(date, date).firstOrNull()

    /** Computes (and stores) [date] unless a frozen row from an earlier day exists. Returns the stored row. */
    fun compute(ctx: Context, date: String): JSONObject = compute(ctx, date, false)

    fun compute(ctx: Context, date: String, force: Boolean): JSONObject {
        computeDates(ctx, listOf(LocalDate.parse(date)), force)
        return stored(ctx, date) ?: JSONObject()
    }

    /** Recomputes the last [days] days (days older than 2 days are only filled in when missing). */
    fun recomputeRecent(ctx: Context, days: Int) {
        val today = LocalDate.now(ZoneId.systemDefault())
        computeDates(ctx, (days.coerceAtLeast(1) - 1 downTo 0).map { today.minusDays(it.toLong()) }, false)
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
    }

    // ---------- core ----------

    private class Ctx(val ser: Map<String, LocalApi.Day>, val mh: Double, val z: ZoneId, val db: android.database.sqlite.SQLiteDatabase) {
        val sleepMain = HashMap<String, Pair<Long, Long>?>()
        val resp = HashMap<String, Double?>()
        fun day(d: LocalDate) = ser[d.toString()]
    }

    private class Load(val trimp: Double, val acute: Double, val chronic: Double, val history: Int)

    private fun computeDates(ctx: Context, dates: List<LocalDate>, force: Boolean) {
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
        val mh = LocalApi.maxHr()
        val ser = LocalApi.series(store.db, z, todo.min().minusDays(HIST_DAYS.toLong()), todo.max(), mh)
        val c = Ctx(ser, mh, z, store.db)
        val loads = loadSeries(c)
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
        val acwr = if (ld != null && ld.history >= MIN_HISTORY && ld.chronic >= CHRONIC_EPS) ld.acute / ld.chronic else null
        val (sleepScore, sleepJson) = sleepScore(c, d)
        val extras = LocalApi.Extras(sleepScore, acwr, ld?.acute, ld?.chronic)
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

    // ---------- training load ----------

    private fun loadSeries(c: Ctx): Map<String, Load> {
        val dates = c.ser.keys.map { LocalDate.parse(it) }.sorted()
        val lo = LocalApi.bounds(dates.first(), c.z).first
        val hi = LocalApi.bounds(dates.last(), c.z).second
        val perDay = HashMap<String, Double>()
        for (ex in LocalApi.exercises(c.db, lo, hi)) {
            val ds = java.time.Instant.ofEpochMilli(ex.start).atZone(c.z).toLocalDate()
            val rest = restingBaseline(c, ds)
            perDay[ds.toString()] = (perDay[ds.toString()] ?: 0.0) + trimp(c.db, ex, rest, c.mh)
        }
        val out = HashMap<String, Load>()
        val a7 = 2.0 / 8; val a28 = 2.0 / 29
        var acute = 0.0; var chronic = 0.0; var first = -1
        for ((i, d) in dates.withIndex()) {
            val day = c.ser[d.toString()]!!
            val x = perDay[d.toString()] ?: 0.0
            if (first < 0 && (day.steps != 0L || day.hrMean != null || day.sleepMin != null || x > 0)) {
                first = i; acute = x; chronic = x
            } else if (first >= 0) {
                acute += a7 * (x - acute); chronic += a28 * (x - chronic)
            }
            if (first >= 0) out[d.toString()] = Load(x, acute, chronic, i - first + 1)
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

    private fun sd(v: List<Double>): Double {
        if (v.size < 2) return 0.0
        val m = v.average()
        return Math.sqrt(v.sumOf { (it - m) * (it - m) } / (v.size - 1))
    }

    private fun sleepScore(c: Ctx, d: LocalDate): Pair<Double?, JSONObject> {
        val day = c.day(d)!!
        val notes = ArrayList<String>()
        val comps = JSONObject()
        val o = JSONObject()
        val asleep = day.sleepMin
        val typical = (1..28).mapNotNull { c.day(d.minusDays(it.toLong()))?.sleepMin }.let { if (it.size >= MIN_BASE) it.average() else null }
        val need = if (typical != null) (450.0 + 0.5 * (typical - 450.0)).coerceIn(360.0, 540.0) else 450.0
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
        add("duration", asleep / need * 100, JSONObject().put("asleep_min", asleep).put("need_min", LocalApi.r(need)))
        notes.add(String.format(Locale.US, "Slept %.1f h vs need %.1f h%s.", asleep / 60, need / 60,
            if (typical != null) " (adapted to your 28-day typical)" else " (default need; adapts after 7 nights of history)"))

        val st = day.stages
        val awake = (st["awake"] ?: 0.0) + (st["awake_in_bed"] ?: 0.0) + (st["out_of_bed"] ?: 0.0)
        val deep = st["deep"] ?: 0.0; val rem = st["rem"] ?: 0.0
        val staged = st.containsKey("light") || st.containsKey("deep") || st.containsKey("rem")
        if (staged) {
            val eff = asleep / (asleep + awake)
            add("efficiency", (eff - 0.65) / 0.25 * 100, JSONObject().put("efficiency", LocalApi.r(eff, 3)).put("awake_min", LocalApi.r(awake)))
            if (st.containsKey("deep") || st.containsKey("rem")) {
                val prop = (deep + rem) / asleep
                add("restorative", prop / 0.38 * 100, JSONObject().put("deep_rem_fraction", LocalApi.r(prop, 3))
                    .put("deep_min", LocalApi.r(deep)).put("rem_min", LocalApi.r(rem)))
            } else notes.add("Restorative skipped: no deep/REM stages.")
        } else notes.add("Efficiency and restorative skipped: no sleep stages for this night.")

        val mids = (0..6).mapNotNull { midpoint(c, d.minusDays(it.toLong())) }
        if (mids.size >= 4) {
            val s = sd(mids)
            add("consistency", 100 - (s - 20) / 70 * 100, JSONObject().put("midpoint_sd_min", LocalApi.r(s)).put("nights", mids.size))
        } else notes.add("Consistency skipped: needs >= 4 nights with sleep in the last 7 days.")

        val wsum = scores.keys.sumOf { SLEEP_W[it]!! }
        val total = scores.entries.sumOf { SLEEP_W[it.key]!! * it.value } / wsum
        if (debt > 0) notes.add(String.format(Locale.US, "Sleep debt over the last %d nights with data: %.1f h.", debtDays.size, debt / 60))
        val score = LocalApi.r(total, 1)
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
                f("Acute:chronic load ratio is %.2f (7-day load %.0f vs 28-day %.0f). Rapid increases are associated with higher injury and fatigue risk; consider an easier day.", acwr, ld.acute, ld.chronic)))
            else if (acwr < 0.8) out.put(insight("acwr_low", "info", "Training load below your recent norm",
                f("Acute:chronic load ratio is %.2f (7-day load %.0f vs 28-day %.0f). Fine during a planned rest or deload; if not planned, you may be losing fitness.", acwr, ld.acute, ld.chronic)))
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
