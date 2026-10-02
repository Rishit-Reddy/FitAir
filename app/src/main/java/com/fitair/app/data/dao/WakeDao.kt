package com.fitair.app.data.dao

import android.content.Context
import com.fitair.app.AppLog
import com.fitair.app.LocalStore
import com.fitair.app.analytics.WakeLogic
import java.time.Instant
import java.time.ZoneId

/**
 * [wakeMs] = end of last night's main sleep (>= 90 min, within 18 h), null if none synced yet. [lastKnownWakeMs] = the latest main-sleep end
 * ever seen. [usualWakeMin]/[usualBedMin] = 28-day medians (minutes after midnight; defaults 07:00 / 23:00).
 * [waiting] = no session yet and it is before 10:00 local (Morning with a quiet "waiting for your sleep data" state).
 */
class WakeInfo(val wakeMs: Long?, val lastKnownWakeMs: Long?, val usualWakeMin: Int, val usualBedMin: Int, val waiting: Boolean)

object WakeDao {
    fun get(ctx: Context, nowMs: Long): WakeInfo {
        val z = ZoneId.systemDefault()
        return try {
            val db = LocalStore.get(ctx).db
            val recent = ArrayList<WakeLogic.Sess>()
            db.rawQuery("SELECT start_ms,end_ms FROM sleep WHERE end_ms>=? AND end_ms<=?",
                arrayOf((nowMs - WakeLogic.LOOKBACK_MS).toString(), nowMs.toString())).use {
                while (it.moveToNext()) recent.add(WakeLogic.Sess(it.getLong(0), it.getLong(1)))
            }
            val wake = WakeLogic.wake(recent, nowMs)
            // main session per wake date over the last 28 days (longest of >= 90 min)
            val since = nowMs - 28L * 86_400_000L
            val mains = LinkedHashMap<String, WakeLogic.Sess>()
            val all = ArrayList<WakeLogic.Sess>()
            db.rawQuery("SELECT start_ms,end_ms FROM sleep WHERE end_ms>=? AND end_ms<=? ORDER BY end_ms", arrayOf(since.toString(), nowMs.toString())).use {
                while (it.moveToNext()) all.add(WakeLogic.Sess(it.getLong(0), it.getLong(1)))
            }
            for (s in all) {
                if (s.end - s.start < WakeLogic.MIN_MAIN_MS) continue
                val d = Instant.ofEpochMilli(s.end).atZone(z).toLocalDate().toString()
                val cur = mains[d]
                if (cur == null || s.end - s.start > cur.end - cur.start) mains[d] = s
            }
            fun minOfDay(ms: Long) = Instant.ofEpochMilli(ms).atZone(z).let { it.hour * 60 + it.minute }
            val usualWake = WakeLogic.medianMinutes(mains.values.map { minOfDay(it.end) }, WakeLogic.DEFAULT_WAKE_MIN)
            val usualBed = WakeLogic.medianMinutes(mains.values.map { minOfDay(it.start) }, WakeLogic.DEFAULT_BED_MIN, wrapNoon = true)
            val lastKnown = db.rawQuery("SELECT MAX(end_ms) FROM sleep WHERE end_ms<=? AND end_ms-start_ms>=?",
                arrayOf(nowMs.toString(), WakeLogic.MIN_MAIN_MS.toString())).use { if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else null }
            val hour = Instant.ofEpochMilli(nowMs).atZone(z).hour
            WakeInfo(wake, lastKnown, usualWake, usualBed, waiting = wake == null && hour < 10)
        } catch (e: Exception) {
            AppLog.d("WakeDao.get failed: ${e.message}")
            WakeInfo(null, null, WakeLogic.DEFAULT_WAKE_MIN, WakeLogic.DEFAULT_BED_MIN, Instant.ofEpochMilli(nowMs).atZone(z).hour < 10)
        }
    }
}
