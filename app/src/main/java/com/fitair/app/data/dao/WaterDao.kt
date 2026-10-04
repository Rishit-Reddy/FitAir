package com.fitair.app.data.dao

import android.content.ContentValues
import android.content.Context
import com.fitair.app.AppLog
import com.fitair.app.LocalApi
import com.fitair.app.LocalStore
import com.fitair.app.notify.WaterAlarm
import com.fitair.app.notify.WaterSchedule
import java.time.LocalDate
import java.time.ZoneId

/** Pace of today's drinking relative to the reminder window. Neutral wording only (no red, no streaks). */
enum class Pace { OnPace, Behind, Reached }

/** [extraMl] is the active-day bonus already included in [goalMl] (0 or 500). */
class WaterToday(val ml: Int, val goalMl: Int, val extraMl: Int, val pace: Pace, val remindersOn: Boolean)

/** Table `water` (t, ml, origin, hc_id). origin "fitair" = typed in the app/notification, anything else was imported from Health Connect. */
object WaterDao {
    const val ORIGIN = "fitair"
    const val PREF_ON = "water_on"
    const val PREF_INTERVAL = "water_interval_min"
    const val PREF_GOAL = "water_goal_ml"
    const val PREF_QUIET = "water_quiet_events"
    const val PREF_FULLSCREEN = "water_fullscreen"

    /** The three amounts offered everywhere: Today card, notification buttons and the full-screen reminder. */
    val AMOUNTS = listOf(200, 300, 500)

    fun remindersOn(ctx: Context) = PrefDao.bool(ctx, PREF_ON, false)
    fun fullScreenOn(ctx: Context) = PrefDao.bool(ctx, PREF_FULLSCREEN, true)
    fun intervalMin(ctx: Context) = WaterSchedule.clampInterval(PrefDao.int(ctx, PREF_INTERVAL, WaterSchedule.DEFAULT_INTERVAL))
    fun baseGoalMl(ctx: Context) = WaterSchedule.clampGoal(PrefDao.int(ctx, PREF_GOAL, WaterSchedule.DEFAULT_GOAL_ML))

    private fun dayBounds(date: LocalDate, z: ZoneId) = LocalApi.bounds(date, z)

    /** Total logged on [date] (local), in ml. */
    fun total(ctx: Context, date: LocalDate, z: ZoneId = ZoneId.systemDefault()): Int = try {
        val (lo, hi) = dayBounds(date, z)
        LocalStore.get(ctx).db.rawQuery("SELECT COALESCE(SUM(ml),0) FROM water WHERE t>=? AND t<?", arrayOf(lo.toString(), hi.toString()))
            .use { if (it.moveToFirst()) Math.round(it.getDouble(0)).toInt() else 0 }
    } catch (e: Exception) { AppLog.d("water total failed: ${e.message}"); 0 }

    /** Time of the latest drink logged today, or null. */
    fun lastDrinkMs(ctx: Context, date: LocalDate = LocalDate.now(), z: ZoneId = ZoneId.systemDefault()): Long? = try {
        val (lo, hi) = dayBounds(date, z)
        LocalStore.get(ctx).db.rawQuery("SELECT MAX(t) FROM water WHERE t>=? AND t<?", arrayOf(lo.toString(), hi.toString()))
            .use { if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else null }
    } catch (e: Exception) { null }

    /** Vigorous + peak minutes of [date] (from load_day), for the active-day bonus. */
    private fun vigorousPeakMin(ctx: Context, date: LocalDate): Int = try {
        LocalStore.get(ctx).db.rawQuery("SELECT COALESCE(z_vig,0)+COALESCE(z_peak,0) FROM load_day WHERE date=?", arrayOf(date.toString()))
            .use { if (it.moveToFirst()) it.getInt(0) else 0 }
    } catch (e: Exception) { 0 }

    fun goalMl(ctx: Context, date: LocalDate): Pair<Int, Int> {
        val base = baseGoalMl(ctx)
        val g = WaterSchedule.goalFor(base, vigorousPeakMin(ctx, date))
        return g to (g - base)
    }

    fun today(ctx: Context): WaterToday {
        val z = ZoneId.systemDefault()
        val date = LocalDate.now(z)
        val ml = total(ctx, date, z)
        val (goal, extra) = goalMl(ctx, date)
        val pace = try {
            val wake = WakeDao.get(ctx, System.currentTimeMillis())
            val w = WaterSchedule.window(date, z, wake.wakeMs, wake.usualWakeMin, wake.usualBedMin)
            WaterSchedule.pace(ml, goal, System.currentTimeMillis(), w)
        } catch (e: Exception) { if (ml >= goal) Pace.Reached else Pace.OnPace }
        return WaterToday(ml, goal, extra, pace, remindersOn(ctx))
    }

    /** Logs [ml] now (local row first; Health Connect write and reminder reschedule follow). Returns the row time, the key for [undo]. */
    fun add(ctx: Context, ml: Int): Long {
        val db = LocalStore.get(ctx).db
        var t = System.currentTimeMillis()
        while (db.rawQuery("SELECT 1 FROM water WHERE t=? AND origin=?", arrayOf(t.toString(), ORIGIN)).use { it.moveToFirst() }) t++
        val v = ContentValues()
        v.put("t", t); v.put("ml", ml.toDouble().coerceIn(1.0, 5000.0)); v.put("origin", ORIGIN)
        db.insertWithOnConflict("water", null, v, android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE)
        try { WaterAlarm.onLogged(ctx, t) } catch (e: Exception) { AppLog.d("water: after-log hook failed: ${e.message}") }
        return t
    }

    /** Removes the entry logged at [t] by [add] (also the Health Connect record, best-effort, on the next flush). */
    fun undo(ctx: Context, t: Long) {
        val db = LocalStore.get(ctx).db
        db.execSQL("DELETE FROM water WHERE t=? AND origin=?", arrayOf(t.toString(), ORIGIN))
        WaterAlarm.queueHcDelete(ctx, t)
        try { WaterAlarm.reschedule(ctx) } catch (e: Exception) { AppLog.d("water: reschedule after undo failed: ${e.message}") }
    }

    class Entry(val t: Long, val ml: Int, val origin: String)

    /** Entries of [date], newest first (for the long-press list and the Log tab). */
    fun entries(ctx: Context, date: LocalDate, z: ZoneId = ZoneId.systemDefault()): List<Entry> = try {
        val (lo, hi) = dayBounds(date, z)
        val out = ArrayList<Entry>()
        LocalStore.get(ctx).db.rawQuery("SELECT t, ml, origin FROM water WHERE t>=? AND t<? ORDER BY t DESC", arrayOf(lo.toString(), hi.toString()))
            .use { while (it.moveToNext()) out.add(Entry(it.getLong(0), Math.round(it.getDouble(1)).toInt(), it.getString(2))) }
        out
    } catch (e: Exception) { emptyList() }

    /** Deletes one entry of any origin (a Health Connect import is only removed locally). */
    fun delete(ctx: Context, t: Long, origin: String) {
        if (origin == ORIGIN) undo(ctx, t)
        else LocalStore.get(ctx).db.execSQL("DELETE FROM water WHERE t=? AND origin=?", arrayOf(t.toString(), origin))
    }

    /** Daily totals for [from]..[to] inclusive: date to (ml, entries). */
    fun dailyTotals(ctx: Context, from: LocalDate, to: LocalDate, z: ZoneId = ZoneId.systemDefault()): Map<String, Pair<Int, Int>> {
        val out = LinkedHashMap<String, Pair<Int, Int>>()
        var d = from
        while (!d.isAfter(to)) {
            val (lo, hi) = dayBounds(d, z)
            try {
                LocalStore.get(ctx).db.rawQuery("SELECT COALESCE(SUM(ml),0), COUNT(*) FROM water WHERE t>=? AND t<?", arrayOf(lo.toString(), hi.toString()))
                    .use { if (it.moveToFirst() && it.getInt(1) > 0) out[d.toString()] = Math.round(it.getDouble(0)).toInt() to it.getInt(1) }
            } catch (e: Exception) { }
            d = d.plusDays(1)
        }
        return out
    }
}
