package com.fitair.app.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.fitair.app.AppLog
import com.fitair.app.LocalStore
import com.fitair.app.core.Format
import com.fitair.app.data.dao.PrefDao
import com.fitair.app.data.dao.WakeDao

/**
 * One "You slept 6h 12m" notification per night, posted after a sync that brings in the night's sleep (issue #16).
 * The Air reaches FitAir only via Google Health, so this can come well after waking; the text never claims a live time.
 */
object WakeNotice {
    const val PREF_ON = "wake_notice_on"
    private const val K_LAST = "wake_notice_last_wake_ms"
    private const val CHANNEL = "wake"
    private const val NOTIF_ID = 7102
    /** Sleep that ended longer ago than this is old news. */
    private const val MAX_AGE_MS = 6 * 3_600_000L
    /** Health Connect stage codes that count as asleep: sleeping, light, deep, REM. */
    private const val ASLEEP = "2,4,5,6"

    fun enabled(ctx: Context) = PrefDao.bool(ctx, PREF_ON, true)

    /** Called at the end of every sync. Posts at most once per wake time. */
    fun afterSync(ctx: Context, nowMs: Long = System.currentTimeMillis()) {
        try {
            if (!enabled(ctx) || !NotificationManagerCompat.from(ctx).areNotificationsEnabled()) return
            val wakeMs = WakeDao.get(ctx, nowMs).wakeMs ?: return
            if (nowMs - wakeMs > MAX_AGE_MS || wakeMs > nowMs) return
            if (PrefDao.get(ctx, K_LAST)?.toLongOrNull() == wakeMs) return
            val min = asleepMinutes(ctx, wakeMs) ?: return
            PrefDao.set(ctx, K_LAST, wakeMs.toString())
            post(ctx, "You slept ${Format.duration(min)}", "Tap to see how you slept.")
        } catch (e: Exception) { AppLog.d("wake notice failed: ${e.message}") }
    }

    /** Minutes asleep in the session that ended at [wakeMs]; the whole session when it has no stages. */
    private fun asleepMinutes(ctx: Context, wakeMs: Long): Long? {
        val db = LocalStore.get(ctx).db
        val start = db.rawQuery("SELECT MIN(start_ms) FROM sleep WHERE end_ms=?", arrayOf(wakeMs.toString())).use {
            if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else null
        } ?: return null
        val staged = db.rawQuery("SELECT SUM(end_ms-start_ms) FROM sleep_stage WHERE start_ms>=? AND end_ms<=? AND stage IN ($ASLEEP)",
            arrayOf(start.toString(), wakeMs.toString())).use { if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else null }
        val ms = staged ?: (wakeMs - start)
        return Math.round(ms / 60_000.0).takeIf { it > 0 }
    }

    private fun post(ctx: Context, title: String, text: String) {
        if (Build.VERSION.SDK_INT >= 26) ctx.getSystemService(NotificationManager::class.java)
            ?.createNotificationChannel(NotificationChannel(CHANNEL, "Sleep summary", NotificationManager.IMPORTANCE_DEFAULT))
        val open = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)?.let {
            PendingIntent.getActivity(ctx, 3, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
        val n = NotificationCompat.Builder(ctx, CHANNEL).setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title).setContentText(text).setContentIntent(open).setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS).build()
        try { NotificationManagerCompat.from(ctx).notify(NOTIF_ID, n) } catch (e: SecurityException) { AppLog.d("wake notice: no permission") }
    }
}
