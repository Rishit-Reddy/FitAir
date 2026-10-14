package com.fitair.app.data.dao

import android.content.ContentValues
import android.content.Context
import com.fitair.app.AppLog
import com.fitair.app.LocalStore
import com.fitair.app.notify.RoutineAlarm
import com.fitair.app.notify.RoutineSchedule
import java.time.LocalDate

/** Table `routine_log` (date, slot am|pm, status done|skipped, t) plus the routine settings kept in `pref`. */
object RoutineDao {
    const val PREF_ON = "routine_on"
    const val PREF_PM_MIN = "routine_pm_min"
    const val PREF_KETO_MASK = "routine_keto_mask"
    const val PREF_FULLSCREEN = "routine_fullscreen"

    fun on(ctx: Context) = PrefDao.bool(ctx, PREF_ON, false)
    fun fullScreenOn(ctx: Context) = PrefDao.bool(ctx, PREF_FULLSCREEN, true)
    fun pmMin(ctx: Context) = PrefDao.int(ctx, PREF_PM_MIN, RoutineSchedule.DEFAULT_PM_MIN).coerceIn(17 * 60, 22 * 60)
    fun ketoMask(ctx: Context) = PrefDao.int(ctx, PREF_KETO_MASK, RoutineSchedule.DEFAULT_KETO_MASK).coerceIn(0, 127)

    /** Status of each slot on [date]: slot -> (status, time). */
    fun day(ctx: Context, date: LocalDate): Map<String, Pair<String, Long>> = range(ctx, date, date)[date.toString()] ?: emptyMap()

    /** date -> slot -> (status, time) for [from]..[to]. */
    fun range(ctx: Context, from: LocalDate, to: LocalDate): Map<String, Map<String, Pair<String, Long>>> = try {
        val out = HashMap<String, HashMap<String, Pair<String, Long>>>()
        LocalStore.get(ctx).db.rawQuery("SELECT date, slot, status, t FROM routine_log WHERE date>=? AND date<=?", arrayOf(from.toString(), to.toString())).use {
            while (it.moveToNext()) out.getOrPut(it.getString(0)) { HashMap() }[it.getString(1)] = it.getString(2) to it.getLong(3)
        }
        out
    } catch (e: Exception) { AppLog.d("routine range failed: ${e.message}"); emptyMap() }

    /** Records [status] (done or skipped) for [slot] on [date]; the reminder is re-armed afterwards. */
    fun set(ctx: Context, date: LocalDate, slot: String, status: String, t: Long = System.currentTimeMillis()) {
        val v = ContentValues()
        v.put("date", date.toString()); v.put("slot", slot); v.put("status", status); v.put("t", t)
        LocalStore.get(ctx).db.insertWithOnConflict("routine_log", null, v, android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE)
        try { RoutineAlarm.afterAnswer(ctx, date, slot) } catch (e: Exception) { AppLog.d("routine: reschedule after answer failed: ${e.message}") }
    }

    /** Takes back an answer (Undo). */
    fun clear(ctx: Context, date: LocalDate, slot: String) {
        LocalStore.get(ctx).db.execSQL("DELETE FROM routine_log WHERE date=? AND slot=?", arrayOf(date.toString(), slot))
        try { RoutineAlarm.reschedule(ctx) } catch (e: Exception) { }
    }
}
