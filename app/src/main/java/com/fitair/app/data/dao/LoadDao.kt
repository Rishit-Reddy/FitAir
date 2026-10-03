package com.fitair.app.data.dao

import android.content.ContentValues
import android.content.Context
import com.fitair.app.AppLog
import com.fitair.app.DailyMetrics
import com.fitair.app.LocalApi
import com.fitair.app.LocalStore
import com.fitair.app.analytics.CardioLoad
import com.fitair.app.analytics.SessionCheck
import org.json.JSONArray
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Today's cardio load: [soFar] up to now, [typicalByNow] = 28-day median at this hour (null with < 7 days), [ratio] = acute/chronic. */
class LoadToday(val soFar: Double, val typicalByNow: Double?, val ratio: Double?, val coverage: Double?, val partial: Boolean)

/** One stored load_day row. */
class LoadDayRow(
    val date: String, val cardio: Double, val zLight: Int, val zMod: Int, val zVig: Int, val zPeak: Int,
    val hourly: DoubleArray, val coverage: Double?, val hrMax: Double?, val hrRest: Double?,
    val acute: Double?, val chronic: Double?, val ratio: Double?,
)

enum class SessionState { Normal, AutoFlagged, UserNotExercise, UserKept }

class SessionRow(
    val startMs: Long, val endMs: Long, val origin: String, val type: Int, val title: String,
    val durationMin: Double, val meanHrr: Double?, val state: SessionState,
) { val excluded get() = state == SessionState.AutoFlagged || state == SessionState.UserNotExercise }

/** Whole-day cardio load storage (table load_day), session flags (exercise_flag) and HRmax resolution. */
object LoadDao {
    private const val DAY = 86_400_000L
    private const val HIST = 90

    // ---------- HRmax ----------

    const val PREF_HR_MAX = "hr_max"
    const val PREF_BIRTH_YEAR = "birth_year"
    @Volatile private var hrCache: Pair<Long, CardioLoad.HrMax>? = null

    fun hrMax(ctx: Context): CardioLoad.HrMax {
        val c = hrCache
        val now = System.currentTimeMillis()
        if (c != null && now - c.first < 5 * 60_000L) return c.second
        val r = try {
            val pref = PrefDao.double(ctx, PREF_HR_MAX)
            val by = PrefDao.get(ctx, PREF_BIRTH_YEAR)?.trim()?.toIntOrNull()
            val year = LocalDate.now().year
            val obs = if (pref == null && (by == null || year - by !in 10..100)) observedP995(ctx) else null
            CardioLoad.hrMax(pref, by, year, obs)
        } catch (e: Exception) { AppLog.d("hrMax failed: ${e.message}"); CardioLoad.HrMax(190.0, CardioLoad.HrMaxSource.Default) }
        hrCache = now to r
        return r
    }

    /** Set (or clear with null) the measured max heart rate and/or birth year, then forget the cached value. */
    fun setHrMax(ctx: Context, v: Double?) { PrefDao.set(ctx, PREF_HR_MAX, v?.let { Math.round(it).toString() }); hrCache = null }
    fun setBirthYear(ctx: Context, y: Int?) { PrefDao.set(ctx, PREF_BIRTH_YEAR, y?.toString()); hrCache = null }

    private fun observedP995(ctx: Context): Double? {
        val since = System.currentTimeMillis() - 180 * DAY
        val h = HashMap<Int, Long>()
        LocalStore.get(ctx).db.rawQuery("SELECT \"max\", COUNT(*) FROM hr_30s WHERE t30>=? GROUP BY \"max\"", arrayOf(since.toString())).use {
            while (it.moveToNext()) h[it.getInt(0)] = it.getLong(1)
        }
        return CardioLoad.percentile(h, 0.995)
    }

    // ---------- helpers ----------

    /** Mean resting HR of the 28 days before [d] (>= 3 readings), else that day's own, else 60. */
    fun restFor(ctx: Context, d: LocalDate, z: ZoneId = ZoneId.systemDefault()): Double {
        val db = LocalStore.get(ctx).db
        val lo = LocalApi.bounds(d.minusDays(28), z).first
        val hi = LocalApi.bounds(d, z).first
        db.rawQuery("SELECT avg(bpm), count(*) FROM resting_hr WHERE t>=? AND t<?", arrayOf(lo.toString(), hi.toString())).use {
            if (it.moveToFirst() && it.getInt(1) >= 3 && !it.isNull(0)) return it.getDouble(0)
        }
        db.rawQuery("SELECT avg(bpm) FROM resting_hr WHERE t>=? AND t<?", arrayOf(hi.toString(), LocalApi.bounds(d, z).second.toString())).use {
            if (it.moveToFirst() && !it.isNull(0)) return it.getDouble(0)
        }
        return 60.0
    }

    private fun buckets(ctx: Context, lo: Long, hi: Long): List<CardioLoad.Bucket> {
        val out = ArrayList<CardioLoad.Bucket>()
        LocalStore.get(ctx).db.rawQuery("SELECT t30, sum(mean*n)/sum(n) FROM hr_30s WHERE t30>=? AND t30<? GROUP BY t30 ORDER BY t30",
            arrayOf(lo.toString(), hi.toString())).use { while (it.moveToNext()) out.add(CardioLoad.Bucket(it.getLong(0), it.getDouble(1))) }
        return out
    }

    /** [start, end] windows of sessions that currently count as NOT exercise (user verdict or automatic flag). */
    fun excludedWindows(ctx: Context, lo: Long, hi: Long): List<LongArray> {
        val out = ArrayList<LongArray>()
        LocalStore.get(ctx).db.rawQuery("SELECT start_ms,end_ms FROM exercise_flag WHERE verdict=? AND end_ms>=? AND start_ms<=?",
            arrayOf(SessionCheck.NOT_EXERCISE, lo.toString(), hi.toString())).use { while (it.moveToNext()) out.add(longArrayOf(it.getLong(0), it.getLong(1))) }
        return out
    }

    private class Sess(val start: Long, val end: Long, val type: Int, val title: String, val origin: String)

    private fun sessions(ctx: Context, lo: Long, hi: Long): List<Sess> {
        val rows = ArrayList<Sess>()
        LocalStore.get(ctx).db.rawQuery("SELECT start_ms,end_ms,type,title,origin FROM exercise WHERE start_ms>=? AND start_ms<? ORDER BY start_ms, end_ms DESC",
            arrayOf(lo.toString(), hi.toString())).use {
            while (it.moveToNext()) rows.add(Sess(it.getLong(0), it.getLong(1), it.getInt(2), it.getString(3) ?: "", it.getString(4) ?: ""))
        }
        val out = ArrayList<Sess>(); var lastEnd = -1L
        for (s in rows) { if (s.start < lastEnd) continue; lastEnd = s.end; out.add(s) }  // same workout from two apps counted once
        return out
    }

    // ---------- session flags ----------

    /** Runs SessionCheck over sessions starting in [lo, hi) and updates automatic flags (user verdicts are never touched). */
    fun flagSessions(ctx: Context, lo: Long, hi: Long, z: ZoneId = ZoneId.systemDefault()) {
        val db = LocalStore.get(ctx).db
        val hrMax = hrMax(ctx).value
        val now = System.currentTimeMillis()
        for (s in sessions(ctx, lo, hi)) {
            val dur = (s.end - s.start) / 60000.0
            if (dur < SessionCheck.MIN_MIN) continue
            val d = Instant.ofEpochMilli(s.start).atZone(z).toLocalDate()
            val rest = restFor(ctx, d, z)
            val means = buckets(ctx, s.start - s.start % 30000, s.end).map { it.mean }
            val r = SessionCheck.check(dur, s.type, means, rest, hrMax)
            var existingAuto: Boolean? = null; var user = false
            db.rawQuery("SELECT auto FROM exercise_flag WHERE start_ms=? AND origin=?", arrayOf(s.start.toString(), s.origin)).use {
                if (it.moveToFirst()) { if (it.getInt(0) == 1) existingAuto = true else user = true }
            }
            when (SessionCheck.autoAction(existingAuto, user, r.suspicious)) {
                "set" -> if (existingAuto != true) db.execSQL("INSERT OR REPLACE INTO exercise_flag(start_ms,origin,end_ms,verdict,auto,set_ms) VALUES(?,?,?,?,1,?)",
                    arrayOf<Any>(s.start, s.origin, s.end, SessionCheck.NOT_EXERCISE, now))
                "clear" -> db.execSQL("DELETE FROM exercise_flag WHERE start_ms=? AND origin=? AND auto=1", arrayOf<Any>(s.start, s.origin))
            }
        }
    }

    /** Sessions of [from]..[to] (inclusive local dates) for the Load screen, newest first. */
    fun sessionRows(ctx: Context, from: LocalDate, to: LocalDate, z: ZoneId = ZoneId.systemDefault()): List<SessionRow> = try {
        val db = LocalStore.get(ctx).db
        val hrMax = hrMax(ctx).value
        val lo = LocalApi.bounds(from, z).first; val hi = LocalApi.bounds(to, z).second
        sessions(ctx, lo, hi).map { s ->
            var state = SessionState.Normal
            db.rawQuery("SELECT verdict, auto FROM exercise_flag WHERE start_ms=? AND origin=?", arrayOf(s.start.toString(), s.origin)).use {
                if (it.moveToFirst()) {
                    val ne = it.getString(0) == SessionCheck.NOT_EXERCISE; val auto = it.getInt(1) == 1
                    state = if (auto) SessionState.AutoFlagged else if (ne) SessionState.UserNotExercise else SessionState.UserKept
                }
            }
            val d = Instant.ofEpochMilli(s.start).atZone(z).toLocalDate()
            val dur = (s.end - s.start) / 60000.0
            val means = buckets(ctx, s.start - s.start % 30000, s.end).map { it.mean }
            val mh = if (means.isEmpty()) null else SessionCheck.check(maxOf(dur, SessionCheck.MIN_MIN.toDouble()), s.type, means, restFor(ctx, d, z), hrMax).meanHrr
            SessionRow(s.start, s.end, s.origin, s.type, s.title, dur, mh, state)
        }.sortedByDescending { it.startMs }
    } catch (e: Exception) { AppLog.d("sessionRows failed: ${e.message}"); emptyList() }

    /** Stores the user's verdict (wins over the automatic check) and recomputes load and readiness from that date. Call off the main thread. */
    fun setVerdict(ctx: Context, s: SessionRow, exercise: Boolean) {
        val verdict = if (exercise) SessionCheck.EXERCISE else SessionCheck.NOT_EXERCISE
        LocalStore.get(ctx).db.execSQL("INSERT OR REPLACE INTO exercise_flag(start_ms,origin,end_ms,verdict,auto,set_ms) VALUES(?,?,?,?,0,?)",
            arrayOf<Any>(s.startMs, s.origin, s.endMs, verdict, System.currentTimeMillis()))
        val d = Instant.ofEpochMilli(s.startMs).atZone(ZoneId.systemDefault()).toLocalDate()
        DailyMetrics.recomputeFrom(ctx, d)
    }

    // ---------- load_day ----------

    private fun readRow(c: android.database.Cursor): LoadDayRow {
        fun dbl(i: Int) = if (c.isNull(i)) null else c.getDouble(i)
        val hourly = DoubleArray(24)
        try { val a = JSONArray(c.getString(6) ?: "[]"); for (i in 0 until minOf(24, a.length())) hourly[i] = a.optDouble(i, 0.0) } catch (e: Exception) { }
        return LoadDayRow(c.getString(0), c.getDouble(1), c.getInt(2), c.getInt(3), c.getInt(4), c.getInt(5), hourly, dbl(7), dbl(8), dbl(9), dbl(10), dbl(11), dbl(12))
    }

    private const val COLS = "date,cardio,z_light,z_mod,z_vig,z_peak,hourly_json,coverage,hr_max,hr_rest,acute,chronic,ratio"

    fun rows(ctx: Context, from: LocalDate, to: LocalDate): List<LoadDayRow> = try {
        val out = ArrayList<LoadDayRow>()
        LocalStore.get(ctx).db.rawQuery("SELECT $COLS FROM load_day WHERE date>=? AND date<=? AND cardio IS NOT NULL ORDER BY date",
            arrayOf(from.toString(), to.toString())).use { while (it.moveToNext()) out.add(readRow(it)) }
        out
    } catch (e: Exception) { AppLog.d("load rows failed: ${e.message}"); emptyList() }

    fun row(ctx: Context, d: LocalDate): LoadDayRow? = rows(ctx, d, d).firstOrNull()

    /**
     * (Re)computes load_day for [dates] plus up to 90 days of history that are missing, then the acute/chronic EWMA over the window.
     * Rows of earlier days are reused unless [force]; the last 2 days are always recomputed. Never throws except cancellation.
     */
    fun compute(ctx: Context, dates: List<LocalDate>, force: Boolean) {
        if (dates.isEmpty()) return
        val z = ZoneId.systemDefault()
        val today = LocalDate.now(z)
        val db = LocalStore.get(ctx).db
        val hm = hrMax(ctx).value
        val first = dates.min().minusDays(HIST.toLong())
        val last = minOf(dates.max(), today)
        if (last.isBefore(first)) return
        flagSessions(ctx, LocalApi.bounds(first, z).first, LocalApi.bounds(last, z).second, z)
        val excl = excludedWindows(ctx, LocalApi.bounds(first, z).first, LocalApi.bounds(last, z).second)
        val have = rows(ctx, first, last).associateBy { it.date }
        val wanted = dates.map { it.toString() }.toSet()
        val days = LocalApi.daysBetween(first, last)
        val cardio = DoubleArray(days.size)
        val hasData = BooleanArray(days.size)
        val touched = HashSet<String>()
        val now = System.currentTimeMillis()
        for ((i, d) in days.withIndex()) {
            val key = d.toString()
            val old = have[key]
            val need = old == null || (key in wanted && (force || today.toEpochDay() - d.toEpochDay() <= 1))
            if (!need) {
                cardio[i] = old!!.cardio; hasData[i] = d != today && (old.coverage ?: 0.0) >= CardioLoad.PARTIAL_BELOW; continue
            }
            val (lo, hi) = LocalApi.bounds(d, z)
            val rest = restFor(ctx, d, z)
            val all = buckets(ctx, lo, hi)
            val b = if (excl.isEmpty()) all else all.filter { x -> excl.none { x.t30 + 30000 > it[0] && x.t30 <= it[1] } }
            val until = if (d == today) maxOf(all.lastOrNull()?.t30 ?: lo, lo) else hi
            val r = CardioLoad.day(b, rest, hm, z, lo, until)
            val v = ContentValues()
            v.put("date", key); v.put("cardio", Math.round(r.cardio * 100) / 100.0)
            v.put("z_light", r.zLight); v.put("z_mod", r.zMod); v.put("z_vig", r.zVig); v.put("z_peak", r.zPeak)
            v.put("hourly_json", JSONArray(r.hourly.map { Math.round(it * 10) / 10.0 }).toString())
            if (all.isEmpty() && r.coverage == null) v.put("coverage", 0.0) else if (r.coverage == null) v.putNull("coverage") else v.put("coverage", r.coverage)
            v.put("hr_max", hm); v.put("hr_rest", rest); v.putNull("acute"); v.putNull("chronic"); v.putNull("ratio"); v.put("computed_ms", now)
            db.insertWithOnConflict("load_day", null, v, android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE)
            touched.add(key)
            cardio[i] = r.cardio; hasData[i] = d != today && all.isNotEmpty() && (r.coverage ?: 0.0) >= CardioLoad.PARTIAL_BELOW
        }
        val ew = CardioLoad.ewma(cardio.toList(), hasData.toList())
        db.beginTransaction()
        try {
            for ((i, d) in days.withIndex()) {
                val key = d.toString()
                if (key !in wanted && key !in touched) continue
                val e = ew[i] ?: continue
                val v = ContentValues()
                v.put("acute", Math.round(e.acute * 100) / 100.0); v.put("chronic", Math.round(e.chronic * 100) / 100.0)
                e.ratio?.let { v.put("ratio", Math.round(it * 1000) / 1000.0) } ?: v.putNull("ratio")
                db.update("load_day", v, "date=?", arrayOf(key))
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    // ---------- today ----------

    /** Load of [d] computed from the heart-rate buckets now (flagged sessions left out); null without any bucket. */
    fun live(ctx: Context, d: LocalDate): CardioLoad.DayLoad? {
        val z = ZoneId.systemDefault()
        val (lo, hi) = LocalApi.bounds(d, z)
        val all = buckets(ctx, lo, hi)
        if (all.isEmpty()) return null
        val excl = excludedWindows(ctx, lo, hi)
        val b = if (excl.isEmpty()) all else all.filter { x -> excl.none { it[0] < x.t30 + 30000 && x.t30 <= it[1] } }
        val until = if (d == LocalDate.now(z)) all.last().t30 else hi
        return CardioLoad.day(b, restFor(ctx, d, z), hrMax(ctx).value, z, lo, until)
    }

    fun today(ctx: Context): LoadToday {
        return try {
            val z = ZoneId.systemDefault()
            val d = LocalDate.now(z)
            val live = live(ctx, d) ?: return LoadToday(0.0, null, rows(ctx, d.minusDays(3), d).lastOrNull { it.ratio != null }?.ratio, null, true)
            val ratio = rows(ctx, d.minusDays(3), d).lastOrNull { it.ratio != null }?.ratio
            val hist = rows(ctx, d.minusDays(28), d.minusDays(1)).filter { (it.coverage ?: 0.0) >= CardioLoad.PARTIAL_BELOW }.map { it.hourly }
            val zdt = Instant.now().atZone(z)
            val typical = CardioLoad.typicalByNow(hist, zdt.hour, zdt.minute / 60.0)
            LoadToday(live.cardio, typical, ratio, live.coverage, CardioLoad.isPartial(live.coverage))
        } catch (e: Exception) {
            AppLog.d("LoadDao.today failed: ${e.message}")
            LoadToday(0.0, null, null, null, true)
        }
    }
}
