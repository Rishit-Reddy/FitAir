package com.fitair.app.notify

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.fitair.app.AppLog
import com.fitair.app.HealthRepo
import com.fitair.app.LocalStore
import com.fitair.app.SyncPrefs
import com.fitair.app.data.dao.PrefDao
import com.fitair.app.data.dao.WakeDao
import com.fitair.app.data.dao.WaterDao
import com.fitair.app.integrations.calendar.CalendarRepo
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.concurrent.thread

/**
 * Water reminders (docs/PLAN_081.md 3.13). AlarmManager.setAndAllowWhileIdle (inexact, fires in Doze, needs no special permission):
 * a nudge needs no minute precision, and SCHEDULE_EXACT_ALARM would need a user grant on Android 14+. The receiver posts the
 * notification, then schedules the next alarm. Rescheduled on boot, time/zone change, app update, every logged drink and every sync.
 */
object WaterAlarm {
    const val CHANNEL = "water"
    const val NOTIF_ID = 7101
    const val ACTION_FIRE = "com.fitair.app.WATER_FIRE"
    const val ACTION_ADD = "com.fitair.app.WATER_ADD"
    const val ACTION_UNDO = "com.fitair.app.WATER_UNDO"
    private const val K_LAST_REMINDER = "water_last_reminder_ms"
    private const val K_UNANSWERED = "water_unanswered"
    private const val K_PENDING_DELETE = "water_hc_pending_delete"

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(SyncPrefs.FILE, Context.MODE_PRIVATE)

    fun clientId(t: Long) = "fitair-water-$t"

    /** Android 13+ needs the POST_NOTIFICATIONS grant before reminders can be seen. */
    fun needsNotificationPermission(ctx: Context): Boolean =
        Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED

    fun enable(ctx: Context) {
        val app = ctx.applicationContext
        PrefDao.setBool(app, WaterDao.PREF_ON, true)
        ensureChannel(app)
        reschedule(app)
    }

    fun disable(ctx: Context) {
        val app = ctx.applicationContext
        PrefDao.setBool(app, WaterDao.PREF_ON, false)
        cancel(app)
        try { NotificationManagerCompat.from(app).cancel(NOTIF_ID) } catch (e: Exception) { }
    }

    private fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val ch = NotificationChannel(CHANNEL, "Water reminders", NotificationManager.IMPORTANCE_DEFAULT)
        ch.description = "Gentle drink reminders in your waking hours"
        ch.setSound(null, null)
        ch.enableVibration(true); ch.vibrationPattern = longArrayOf(0, 150)
        ctx.getSystemService(NotificationManager::class.java)?.createNotificationChannel(ch)
    }

    private fun receiverIntent(ctx: Context, action: String, data: String? = null): Intent =
        Intent(ctx, WaterReceiver::class.java).setAction(action).also { if (data != null) it.data = Uri.parse(data) }

    private fun alarmIntent(ctx: Context) = PendingIntent.getBroadcast(ctx, 1, receiverIntent(ctx, ACTION_FIRE),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun cancel(ctx: Context) {
        try { ctx.getSystemService(AlarmManager::class.java)?.cancel(alarmIntent(ctx)) } catch (e: Exception) { AppLog.d("water: cancel failed ${e.message}") }
    }

    fun scheduleAt(ctx: Context, t: Long) {
        try {
            ctx.getSystemService(AlarmManager::class.java)?.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, alarmIntent(ctx))
            AppLog.d("water: next reminder at ${Instant.ofEpochMilli(t).atZone(ZoneId.systemDefault()).toLocalTime()}")
        } catch (e: Exception) { AppLog.d("water: schedule failed ${e.message}") }
    }

    /** Next fire time from the DB state (pure rules in [WaterSchedule]). */
    fun nextFireMs(ctx: Context, now: Long): Long {
        val z = ZoneId.systemDefault()
        val date = Instant.ofEpochMilli(now).atZone(z).toLocalDate()
        val wake = WakeDao.get(ctx, now)
        val w = WaterSchedule.window(date, z, wake.wakeMs, wake.usualWakeMin, wake.usualBedMin)
        val (goal, _) = WaterDao.goalMl(ctx, date)
        val last = prefs(ctx).getLong(K_LAST_REMINDER, 0L).takeIf { it > 0 }
        return WaterSchedule.next(now, w, WaterDao.lastDrinkMs(ctx, date, z), last, WaterDao.total(ctx, date, z), goal, WaterDao.intervalMin(ctx))
            ?: WaterSchedule.firstOfDay(date.plusDays(1), z, null, wake.usualWakeMin, wake.usualBedMin)
    }

    /** (Re)schedules the next alarm, or cancels it when reminders are off. Never throws. */
    fun reschedule(ctx: Context) {
        val app = ctx.applicationContext
        try {
            if (!WaterDao.remindersOn(app)) { cancel(app); return }
            scheduleAt(app, nextFireMs(app, System.currentTimeMillis()))
        } catch (e: Exception) { AppLog.e("water: reschedule failed", e) }
    }

    /** After a drink was logged anywhere: restart the clock, clear "unanswered", write to Health Connect. */
    fun onLogged(ctx: Context, t: Long) {
        val app = ctx.applicationContext
        prefs(app).edit().putInt(K_UNANSWERED, 0).apply()
        reschedule(app)
        enqueueHcFlush(app)
    }

    // ---------- Health Connect ----------

    fun queueHcDelete(ctx: Context, t: Long) {
        val p = prefs(ctx)
        val set = (p.getStringSet(K_PENDING_DELETE, emptySet()) ?: emptySet()).toMutableSet()
        set.add(clientId(t))
        p.edit().putStringSet(K_PENDING_DELETE, set).apply()
        enqueueHcFlush(ctx)
    }

    fun enqueueHcFlush(ctx: Context) {
        try {
            val req = OneTimeWorkRequestBuilder<WaterHcWorker>().setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST).build()
            WorkManager.getInstance(ctx.applicationContext).enqueueUniqueWork("fitair-water-hc", ExistingWorkPolicy.APPEND_OR_REPLACE, req)
        } catch (e: Exception) { AppLog.d("water: enqueue HC flush failed ${e.message}") }
    }

    /** Writes unsent drinks to Health Connect and applies queued deletes; a refused write just stays queued (hc_id null) for the next sync. */
    suspend fun flushHealthConnect(ctx: Context) {
        val app = ctx.applicationContext
        val repo = HealthRepo(app)
        if (!repo.canWriteHydration()) return
        val p = prefs(app)
        val pending = (p.getStringSet(K_PENDING_DELETE, emptySet()) ?: emptySet()).toSet()
        for (id in pending) {
            try {
                repo.deleteHydration(id)
                p.edit().putStringSet(K_PENDING_DELETE, (p.getStringSet(K_PENDING_DELETE, emptySet()) ?: emptySet()) - id).apply()
            } catch (e: Exception) { AppLog.d("water: HC delete failed ${e.message}") }
        }
        val db = LocalStore.get(app).db
        val rows = ArrayList<Pair<Long, Double>>()
        db.rawQuery("SELECT t, ml FROM water WHERE origin=? AND hc_id IS NULL ORDER BY t LIMIT 200", arrayOf(WaterDao.ORIGIN)).use {
            while (it.moveToNext()) rows.add(it.getLong(0) to it.getDouble(1))
        }
        for ((t, ml) in rows) {
            try {
                val still = db.rawQuery("SELECT 1 FROM water WHERE t=? AND origin=?", arrayOf(t.toString(), WaterDao.ORIGIN)).use { it.moveToFirst() }
                if (!still) continue
                val id = repo.writeHydration(t, ml, clientId(t)) ?: "written"
                db.execSQL("UPDATE water SET hc_id=? WHERE t=? AND origin=?", arrayOf(id, t.toString(), WaterDao.ORIGIN))
            } catch (e: Exception) { AppLog.d("water: HC write failed (${e.javaClass.simpleName}), will retry on next sync"); break }
        }
    }

    /** Imports drinks other apps wrote to Health Connect (last 3 days). */
    suspend fun importHealthConnect(ctx: Context) {
        val app = ctx.applicationContext
        val repo = HealthRepo(app)
        if (!repo.canReadHydration()) return
        val now = Instant.now()
        val recs = repo.readHydration(now.minusSeconds(3 * 86_400L), now.plusSeconds(60))
        if (recs.isNotEmpty()) LocalStore.get(app).upsert("water", recs)
    }

    // ---------- notifications ----------

    private fun canNotify(ctx: Context) = try { NotificationManagerCompat.from(ctx).areNotificationsEnabled() } catch (e: Exception) { false }

    private fun contentIntent(ctx: Context): PendingIntent? {
        val i = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName) ?: return null
        return PendingIntent.getActivity(ctx, 2, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun actionPi(ctx: Context, code: Int, action: String, data: String) =
        PendingIntent.getBroadcast(ctx, code, receiverIntent(ctx, action, data), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun fmtL(ml: Int) = String.format(java.util.Locale.US, "%.1f", ml / 1000.0)

    private fun post(ctx: Context, text: String, undoT: Long? = null) {
        if (!canNotify(ctx)) return
        ensureChannel(ctx)
        val glass = WaterDao.glassMl(ctx)
        val b = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("Water").setContentText(text)
            .setContentIntent(contentIntent(ctx)).setAutoCancel(false).setOnlyAlertOnce(undoT != null)
            .setCategory(NotificationCompat.CATEGORY_REMINDER).setPriority(NotificationCompat.PRIORITY_DEFAULT)
        if (undoT == null) {
            b.addAction(0, "+$glass ml", actionPi(ctx, 10, ACTION_ADD, "fitair://water/add/$glass"))
            b.addAction(0, "+500 ml", actionPi(ctx, 11, ACTION_ADD, "fitair://water/add/500"))
        } else {
            b.addAction(0, "Undo", actionPi(ctx, 12, ACTION_UNDO, "fitair://water/undo/$undoT"))
            b.setTimeoutAfter(10_000)
        }
        try { NotificationManagerCompat.from(ctx).notify(NOTIF_ID, b.build()) } catch (e: SecurityException) { AppLog.d("water: no notification permission") }
    }

    /** The alarm fired. Posts the reminder if it is inside today's window and the goal is not reached, then schedules the next one. */
    fun fire(ctx: Context) {
        val app = ctx.applicationContext
        if (!WaterDao.remindersOn(app)) { cancel(app); return }
        val z = ZoneId.systemDefault()
        val now = System.currentTimeMillis()
        val date = LocalDate.now(z)
        val wake = WakeDao.get(app, now)
        val w = WaterSchedule.window(date, z, wake.wakeMs, wake.usualWakeMin, wake.usualBedMin)
        val ml = WaterDao.total(app, date, z)
        val (goal, _) = WaterDao.goalMl(app, date)
        val p = prefs(app)
        if (WaterSchedule.insideWindow(now + 60_000L, w) && ml < goal) {
            if (PrefDao.bool(app, WaterDao.PREF_QUIET, false) && inCalendarEvent(app, now)) {
                scheduleAt(app, now + 15 * WaterSchedule.MIN); return
            }
            val lastRem = p.getLong(K_LAST_REMINDER, 0L)
            val lastDrink = WaterDao.lastDrinkMs(app, date, z) ?: 0L
            var unanswered = p.getInt(K_UNANSWERED, 0)
            if (lastDrink > lastRem) unanswered = 0
            val since = if (unanswered >= 2) {
                val ref = maxOf(lastDrink, p.getLong("water_first_unanswered_ms", w.startMs))
                Instant.ofEpochMilli(ref).atZone(z).toLocalTime().let { String.format("%02d:%02d", it.hour, it.minute) }
            } else null
            post(app, WaterSchedule.text(ml, goal, unanswered, since))
            p.edit().putLong(K_LAST_REMINDER, now).putInt(K_UNANSWERED, unanswered + 1)
                .also { if (unanswered == 0) it.putLong("water_first_unanswered_ms", maxOf(lastDrink, w.startMs)) }.apply()
        }
        reschedule(app)
    }

    private fun inCalendarEvent(ctx: Context, now: Long): Boolean = try {
        runBlocking { CalendarRepo(ctx).eventsForDay(LocalDate.now()) }
            .any { !it.allDay && it.busy && it.begin.toEpochMilli() <= now && now < it.end.toEpochMilli() }
    } catch (e: Exception) { false }

    /** A "+250 ml" / "+500 ml" notification action. */
    fun logFromNotification(ctx: Context, ml: Int) {
        val app = ctx.applicationContext
        val t = WaterDao.add(app, ml)
        val total = WaterDao.total(app, LocalDate.now())
        val goal = WaterDao.goalMl(app, LocalDate.now()).first
        post(app, "Logged $ml ml · ${fmtL(total)} of ${fmtL(goal)} L", undoT = t)
    }

    fun undoFromNotification(ctx: Context, t: Long) {
        val app = ctx.applicationContext
        WaterDao.undo(app, t)
        try { NotificationManagerCompat.from(app).cancel(NOTIF_ID) } catch (e: Exception) { }
    }
}

class WaterReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val pending = goAsync()
        thread(name = "water-receiver") {
            try {
                AppLog.init(app)
                when (intent.action) {
                    WaterAlarm.ACTION_FIRE -> WaterAlarm.fire(app)
                    WaterAlarm.ACTION_ADD -> WaterAlarm.logFromNotification(app, intent.data?.lastPathSegment?.toIntOrNull() ?: 250)
                    WaterAlarm.ACTION_UNDO -> intent.data?.lastPathSegment?.toLongOrNull()?.let { WaterAlarm.undoFromNotification(app, it) }
                    else -> WaterAlarm.reschedule(app)  // boot, time/zone change, app update
                }
            } catch (e: Throwable) {
                AppLog.e("water receiver failed", e)
            } finally { pending.finish() }
        }
    }
}

class WaterHcWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        AppLog.init(applicationContext)
        return try { WaterAlarm.flushHealthConnect(applicationContext); Result.success() }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Throwable) { AppLog.e("water HC worker failed (ignored)", e); Result.success() }
    }

    /** Only used for expedited work on API < 31. */
    override suspend fun getForegroundInfo(): ForegroundInfo {
        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel("misc", "Background work", NotificationManager.IMPORTANCE_LOW))
        val n = NotificationCompat.Builder(applicationContext, "misc").setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("Saving water to Health Connect").setOngoing(true).build()
        return ForegroundInfo(7102, n)
    }
}
