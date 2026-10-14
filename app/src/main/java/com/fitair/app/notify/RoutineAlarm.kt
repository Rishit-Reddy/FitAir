package com.fitair.app.notify

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.fitair.app.AppLog
import com.fitair.app.SyncPrefs
import com.fitair.app.data.dao.RoutineDao
import com.fitair.app.data.dao.WakeDao
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.concurrent.thread

/**
 * Hair routine reminders: one alarm armed for the next due dose. It uses AlarmManager.setAlarmClock (exact, fires in Doze, no special
 * permission; Android shows a small alarm icon while one is pending) because an inexact alarm arrived many minutes late,
 * a high-priority notification with Done / Snooze / Skip and, when allowed, a full-screen screen over the lock screen.
 * Own channel, notification id, request codes and receiver, so it never interferes with the water reminders.
 * One alert per dose: an unanswered one waits quietly and is cleared at its cutoff; nothing is ever made up or doubled.
 */
object RoutineAlarm {
    const val CHANNEL = "routine_alert"
    const val NOTIF_ID = 7201
    const val ACTION_FIRE = "com.fitair.app.ROUTINE_FIRE"
    const val ACTION_DONE = "com.fitair.app.ROUTINE_DONE"
    const val ACTION_SKIP = "com.fitair.app.ROUTINE_SKIP"
    const val ACTION_SNOOZE = "com.fitair.app.ROUTINE_SNOOZE"
    const val EXTRA_SLOT = "slot"
    const val EXTRA_TEST = "test"

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(SyncPrefs.FILE, Context.MODE_PRIVATE)
    private fun key(kind: String, date: LocalDate, slot: String) = "routine_${kind}_${date}_$slot"

    fun enable(ctx: Context) {
        val app = ctx.applicationContext
        com.fitair.app.data.dao.PrefDao.setBool(app, RoutineDao.PREF_ON, true)
        com.fitair.app.data.dao.PrefDao.set(app, "routine_since", LocalDate.now().toString())
        ensureChannel(app)
        reschedule(app)
    }

    fun disable(ctx: Context) {
        val app = ctx.applicationContext
        com.fitair.app.data.dao.PrefDao.setBool(app, RoutineDao.PREF_ON, false)
        cancel(app)
        try { NotificationManagerCompat.from(app).cancel(NOTIF_ID) } catch (e: Exception) { }
    }

    private fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val ch = NotificationChannel(CHANNEL, "Hair routine", NotificationManager.IMPORTANCE_HIGH)
        ch.description = "Minoxidil morning and evening, shampoo days; full screen when the phone is locked"
        ch.setSound(null, null)
        ch.enableVibration(true); ch.vibrationPattern = longArrayOf(0, 250, 120, 250)
        ctx.getSystemService(NotificationManager::class.java)?.createNotificationChannel(ch)
    }

    private fun receiverIntent(ctx: Context, action: String, data: String? = null): Intent =
        Intent(ctx, RoutineReceiver::class.java).setAction(action).also { if (data != null) it.data = Uri.parse(data) }

    private fun alarmPi(ctx: Context, slot: String, test: Boolean = false) = PendingIntent.getBroadcast(
        ctx, if (test) 22 else 21, receiverIntent(ctx, ACTION_FIRE).putExtra(EXTRA_SLOT, slot).putExtra(EXTRA_TEST, test),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun cancel(ctx: Context) {
        try { ctx.getSystemService(AlarmManager::class.java)?.cancel(alarmPi(ctx, RoutineSchedule.AM)) } catch (e: Exception) { }
    }

    private fun scheduleAt(ctx: Context, slot: String, t: Long) {
        try {
            armExact(ctx, t, alarmPi(ctx, slot))
            AppLog.d("routine: next $slot at ${Instant.ofEpochMilli(t).atZone(ZoneId.systemDefault()).toLocalTime()}")
        } catch (e: Exception) { AppLog.d("routine: schedule failed ${e.message}") }
    }

    /** Exact alarm that wakes the phone from Doze; tapping its status-bar icon opens the app. */
    private fun armExact(ctx: Context, t: Long, pi: PendingIntent) {
        val show = contentPi(ctx) ?: pi
        ctx.getSystemService(AlarmManager::class.java)?.setAlarmClock(AlarmManager.AlarmClockInfo(t, show), pi)
    }

    private fun minuteOfDay(ms: Long, z: ZoneId) = Instant.ofEpochMilli(ms).atZone(z).let { it.hour * 60 + it.minute }

    private fun slotState(ctx: Context, date: LocalDate, slot: String, logged: Map<String, Pair<String, Long>>, now: Long): RoutineSchedule.SlotState {
        val p = prefs(ctx)
        return RoutineSchedule.SlotState(
            resolved = logged.containsKey(slot), alerted = p.getBoolean(key("alerted", date, slot), false),
            snoozeUntilMs = p.getLong(key("until", date, slot), 0L).takeIf { it > now },
        )
    }

    /** The next reminder from the DB state (pure rules in [RoutineSchedule]). */
    fun nextDue(ctx: Context, now: Long): RoutineSchedule.Due {
        val z = ZoneId.systemDefault()
        val today = Instant.ofEpochMilli(now).atZone(z).toLocalDate()
        val wake = WakeDao.get(ctx, now)
        val detected = wake.wakeMs?.takeIf { Instant.ofEpochMilli(it).atZone(z).toLocalDate() == today }?.let { minuteOfDay(it, z) }
        val logged = RoutineDao.day(ctx, today)
        val amDoneMin = logged[RoutineSchedule.AM]?.takeIf { it.first == "done" }?.let { minuteOfDay(it.second, z) }
        return RoutineSchedule.next(
            now, z, wake.usualWakeMin, wake.usualBedMin, detected, RoutineDao.pmMin(ctx),
            slotState(ctx, today, RoutineSchedule.AM, logged, now), slotState(ctx, today, RoutineSchedule.PM, logged, now), amDoneMin,
        )
    }

    /** (Re)arms the next alarm, or cancels it when the routine is off. Never throws. */
    fun reschedule(ctx: Context) {
        val app = ctx.applicationContext
        try {
            if (!RoutineDao.on(app)) { cancel(app); return }
            val d = nextDue(app, System.currentTimeMillis())
            scheduleAt(app, d.slot, d.atMs)
        } catch (e: Exception) { AppLog.e("routine: reschedule failed", e) }
    }

    /** After Done or Skip anywhere: clear the notification and arm the next dose. */
    fun afterAnswer(ctx: Context, date: LocalDate, slot: String) {
        val app = ctx.applicationContext
        try { NotificationManagerCompat.from(app).cancel(NOTIF_ID) } catch (e: Exception) { }
        prefs(app).edit().remove(key("until", date, slot)).apply()
        reschedule(app)
    }

    fun snoozesUsed(ctx: Context, date: LocalDate, slot: String) = prefs(ctx).getInt(key("snoozes", date, slot), 0)
    fun canSnooze(ctx: Context, date: LocalDate, slot: String) = snoozesUsed(ctx, date, slot) < RoutineSchedule.MAX_SNOOZES

    /** Snooze 15 min (at most twice per dose). */
    fun snooze(ctx: Context, date: LocalDate, slot: String) {
        val app = ctx.applicationContext
        if (!canSnooze(app, date, slot)) return
        prefs(app).edit().putInt(key("snoozes", date, slot), snoozesUsed(app, date, slot) + 1)
            .putLong(key("until", date, slot), System.currentTimeMillis() + RoutineSchedule.SNOOZE_MIN * 60_000L).apply()
        try { NotificationManagerCompat.from(app).cancel(NOTIF_ID) } catch (e: Exception) { }
        reschedule(app)
    }

    const val TEST_DELAY_MS = 60_000L
    private const val K_TEST_ARMED = "routine_test_armed_ms"
    private const val K_TEST_FIRED = "routine_test_fired_ms"
    private const val K_TEST_RESULT = "routine_test_result"

    /** Why a notification would not show right now, or null when nothing blocks it. */
    fun blockedReason(ctx: Context): String? {
        if (!try { NotificationManagerCompat.from(ctx).areNotificationsEnabled() } catch (e: Exception) { false })
            return "Notifications are off for FitAir in Android's settings."
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = ctx.getSystemService(NotificationManager::class.java)?.getNotificationChannel(CHANNEL)
            if (ch != null && ch.importance == NotificationManager.IMPORTANCE_NONE) return "The Hair routine notification channel is turned off."
        }
        return null
    }

    /** What the last test did: how it ended and how late. [firedMs] is null until the alarm has run. */
    class TestResult(val armedMs: Long, val firedMs: Long?, val result: String?)

    fun testResult(ctx: Context): TestResult? {
        val p = prefs(ctx)
        val armed = p.getLong(K_TEST_ARMED, 0L).takeIf { it > 0 } ?: return null
        return TestResult(armed, p.getLong(K_TEST_FIRED, 0L).takeIf { it >= armed }, p.getString(K_TEST_RESULT, null))
    }

    /** Arms a test reminder in [TEST_DELAY_MS] through the same alarm call the real reminders use. Returns a reason when it cannot show. */
    fun scheduleTest(ctx: Context): String? {
        val app = ctx.applicationContext
        ensureChannel(app)
        blockedReason(app)?.let { return it }
        val now = System.currentTimeMillis()
        prefs(app).edit().putLong(K_TEST_ARMED, now).remove(K_TEST_FIRED).remove(K_TEST_RESULT).apply()
        try { armExact(app, now + TEST_DELAY_MS, alarmPi(app, RoutineSchedule.AM, test = true)) }
        catch (e: Exception) { return "Android refused the alarm: ${e.message}" }
        return null
    }

    private fun actionPi(ctx: Context, code: Int, action: String, slot: String, test: Boolean) = PendingIntent.getBroadcast(
        ctx, code, receiverIntent(ctx, action, "fitair://routine/$action/$slot/${if (test) "test" else "real"}"),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun reminderPi(ctx: Context, slot: String, test: Boolean) = PendingIntent.getActivity(
        ctx, 23, Intent(ctx, RoutineReminderActivity::class.java).putExtra(EXTRA_SLOT, slot).putExtra(EXTRA_TEST, test)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun contentPi(ctx: Context): PendingIntent? {
        val i = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName) ?: return null
        return PendingIntent.getActivity(ctx, 24, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun post(ctx: Context, slot: String, date: LocalDate, text: String, cutoffMs: Long?, test: Boolean): String {
        blockedReason(ctx)?.let { return "blocked: $it" }
        ensureChannel(ctx)
        val b = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle(if (test) "Hair routine (test)" else "Hair routine").setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(reminderPi(ctx, slot, test)).setAutoCancel(false).setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .addAction(0, "Done", actionPi(ctx, 30, ACTION_DONE, slot, test))
            .addAction(0, "Skip", actionPi(ctx, 31, ACTION_SKIP, slot, test))
        if (test || canSnooze(ctx, date, slot)) b.addAction(0, "Snooze ${RoutineSchedule.SNOOZE_MIN} min", actionPi(ctx, 32, ACTION_SNOOZE, slot, test))
        if (cutoffMs != null) b.setTimeoutAfter((cutoffMs - System.currentTimeMillis()).coerceAtLeast(60_000L))
        if (RoutineDao.fullScreenOn(ctx) && WaterAlarm.canFullScreen(ctx)) b.setFullScreenIntent(reminderPi(ctx, slot, test), true)
        return try { NotificationManagerCompat.from(ctx).notify(NOTIF_ID, b.build()); "shown" }
        catch (e: SecurityException) { AppLog.d("routine: no notification permission"); "blocked: no notification permission" }
    }

    fun fire(ctx: Context, slot: String, test: Boolean) {
        val app = ctx.applicationContext
        if (test) {
            val r = post(app, slot, LocalDate.now(), RoutineSchedule.text(slot, false), null, true)
            prefs(app).edit().putLong(K_TEST_FIRED, System.currentTimeMillis()).putString(K_TEST_RESULT, r).apply()
            return
        }
        if (!RoutineDao.on(app)) { cancel(app); return }
        val z = ZoneId.systemDefault()
        val now = System.currentTimeMillis()
        val today = LocalDate.now(z)
        val due = nextDue(app, now - RoutineSchedule.MIN_MS * 2)
        // a stale or early alarm must not alert for a dose that is already answered or not yet due
        val answered = RoutineDao.day(app, today).containsKey(slot)
        if (!answered && due.slot == slot && due.atMs <= now + 5 * 60_000L && now < due.cutoffMs) {
            val keto = slot == RoutineSchedule.AM && RoutineSchedule.ketoToday(today.dayOfWeek, RoutineDao.ketoMask(app))
            post(app, slot, today, RoutineSchedule.text(slot, keto), due.cutoffMs, false)
            prefs(app).edit().putBoolean(key("alerted", today, slot), true).remove(key("until", today, slot)).apply()
        }
        reschedule(app)
    }
}

class RoutineReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val pending = goAsync()
        thread(name = "routine-receiver") {
            try {
                AppLog.init(app)
                val parts = intent.data?.pathSegments.orEmpty() // action, slot, real|test
                val slot = parts.getOrNull(1) ?: intent.getStringExtra(RoutineAlarm.EXTRA_SLOT) ?: RoutineSchedule.AM
                val test = parts.getOrNull(2) == "test" || intent.getBooleanExtra(RoutineAlarm.EXTRA_TEST, false)
                val today = LocalDate.now()
                fun answer(status: String) {
                    if (test) { try { androidx.core.app.NotificationManagerCompat.from(app).cancel(RoutineAlarm.NOTIF_ID) } catch (e: Exception) { } }
                    else com.fitair.app.data.dao.RoutineDao.set(app, today, slot, status)
                }
                when (intent.action) {
                    RoutineAlarm.ACTION_FIRE -> RoutineAlarm.fire(app, slot, test)
                    RoutineAlarm.ACTION_DONE -> answer("done")
                    RoutineAlarm.ACTION_SKIP -> answer("skipped")
                    RoutineAlarm.ACTION_SNOOZE -> if (test) answer("skipped") else RoutineAlarm.snooze(app, today, slot)
                    else -> RoutineAlarm.reschedule(app)  // boot, time/zone change, app update
                }
            } catch (e: Throwable) {
                AppLog.e("routine receiver failed", e)
            } finally { pending.finish() }
        }
    }
}
