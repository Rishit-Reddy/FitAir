package com.fitair.app.integrations.calendar

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import com.fitair.app.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

object CalendarPrefs {
    private const val FILE = "fitair"
    private const val KEY = "calendar_selected_ids"

    /** null = never chosen (treat as all visible + synced calendars). */
    fun selectedIds(ctx: Context): Set<Long>? = try {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getStringSet(KEY, null)?.mapNotNull { it.toLongOrNull() }?.toSet()
    } catch (e: Exception) { null }

    fun setSelected(ctx: Context, ids: Set<Long>) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putStringSet(KEY, ids.map { it.toString() }.toSet()).apply()
    }
}

class CalendarRepo(private val ctx: Context) {
    private val app = ctx.applicationContext

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(app, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    suspend fun calendars(): List<CalendarInfo> = withContext(Dispatchers.IO) {
        if (!hasPermission()) return@withContext emptyList()
        try {
            queryCalendars(onlyVisibleSynced = false).map { it.first }
        } catch (e: SecurityException) { emptyList() }
        catch (e: Exception) { AppLog.d("calendar: calendars() failed: ${e.message}"); emptyList() }
    }

    /** Selected (or, with no choice yet, all) calendars that are not syncing to this phone. Blocking; call off the main thread. */
    fun unsyncedSelected(): List<CalendarInfo> {
        if (!hasPermission()) return emptyList()
        return try {
            val sel = CalendarPrefs.selectedIds(app)
            queryCalendars(onlyVisibleSynced = false).map { it.first }.filter { !it.syncing && (sel == null || it.id in sel) }
        } catch (e: SecurityException) { emptyList() }
        catch (e: Exception) { AppLog.d("calendar: unsyncedSelected failed: ${e.message}"); emptyList() }
    }

    /**
     * Asks Android to sync the calendar accounts now (same as pulling to refresh in Google Calendar). Needs READ_CALENDAR only:
     * the account names come from the Calendars table. Returns how many accounts were asked; 0 when none/permission missing.
     * Note: this pulls what Google already has. URL-subscribed (ICS) calendars are re-fetched by Google on its own schedule.
     */
    fun requestSync(): Int {
        if (!hasPermission()) return 0
        return try {
            val accounts = LinkedHashSet<Pair<String, String>>()
            app.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                arrayOf(CalendarContract.Calendars.ACCOUNT_NAME, CalendarContract.Calendars.ACCOUNT_TYPE), null, null, null,
            )?.use { c ->
                while (c.moveToNext()) {
                    val n = c.getString(0) ?: continue
                    val t = c.getString(1) ?: continue
                    // local-only calendars have no sync adapter
                    if (t != CalendarContract.ACCOUNT_TYPE_LOCAL) accounts.add(n to t)
                }
            }
            val extras = android.os.Bundle().apply {
                putBoolean(android.content.ContentResolver.SYNC_EXTRAS_MANUAL, true)
                putBoolean(android.content.ContentResolver.SYNC_EXTRAS_EXPEDITED, true)
            }
            var n = 0
            for ((name, type) in accounts) {
                try {
                    android.content.ContentResolver.requestSync(android.accounts.Account(name, type), CalendarContract.AUTHORITY, extras)
                    n++
                } catch (e: Exception) { AppLog.d("calendar: requestSync failed for a $type account: ${e.javaClass.simpleName}") }
            }
            AppLog.d("calendar: sync requested for $n account(s)")
            n
        } catch (e: SecurityException) { 0 }
        catch (e: Exception) { AppLog.d("calendar: requestSync failed: ${e.message}"); 0 }
    }

    suspend fun eventsForDay(day: LocalDate, zone: ZoneId = ZoneId.systemDefault()): List<CalEvent> = withContext(Dispatchers.IO) {
        if (!hasPermission()) return@withContext emptyList()
        try {
            val dayStart = day.atStartOfDay(zone).toInstant()
            val dayEnd = day.plusDays(1).atStartOfDay(zone).toInstant()
            val selected = CalendarPrefs.selectedIds(app)
            val ids = selected ?: queryCalendars(onlyVisibleSynced = true).map { it.first.id }.toSet()
            if (ids.isEmpty()) return@withContext emptyList()

            // widen by a day each side so UTC-stored all-day events are never missed; filter precisely below
            val b = dayStart.toEpochMilli() - 86_400_000L
            val e = dayEnd.toEpochMilli() + 86_400_000L
            val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
                android.content.ContentUris.appendId(it, b); android.content.ContentUris.appendId(it, e)
            }.build()
            val proj = arrayOf(
                CalendarContract.Instances.EVENT_ID, CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN,
                CalendarContract.Instances.END, CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.AVAILABILITY,
                CalendarContract.Instances.EVENT_LOCATION, CalendarContract.Instances.CALENDAR_ID,
            )
            val out = ArrayList<CalEvent>()
            app.contentResolver.query(uri, proj, null, null, "${CalendarContract.Instances.BEGIN} ASC")?.use { c ->
                while (c.moveToNext()) {
                    val calId = c.getLong(7)
                    if (calId !in ids) continue
                    val allDay = c.getInt(4) == 1
                    val rawB = c.getLong(2); val rawE = c.getLong(3)
                    val (begin, end) = if (allDay) allDayToLocal(rawB, rawE, zone) else Instant.ofEpochMilli(rawB) to Instant.ofEpochMilli(rawE)
                    if (!(begin < dayEnd && (end > dayStart || (end == begin && begin >= dayStart)))) continue
                    out.add(CalEvent(
                        instanceId = c.getLong(0) * 1_000_003L + (rawB / 60_000L) % 1_000_003L,
                        calId = calId, title = c.getString(1)?.takeIf { it.isNotBlank() } ?: "(no title)",
                        begin = begin, end = end, allDay = allDay,
                        busy = c.getInt(5) != CalendarContract.Instances.AVAILABILITY_FREE &&
                            c.getInt(5) != CalendarContract.Instances.AVAILABILITY_TENTATIVE,
                        location = c.getString(6)?.takeIf { it.isNotBlank() },
                    ))
                }
            }
            out
        } catch (e: SecurityException) { emptyList() }
        catch (e: Exception) { AppLog.d("calendar: eventsForDay failed: ${e.message}"); emptyList() }
    }

    /** Emits Unit (conflated, debounced ~500 ms) whenever calendar data changes; unregisters on cancel. */
    @OptIn(FlowPreview::class)
    fun changes(): Flow<Unit> = callbackFlow<Unit> {
        val obs = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { trySend(Unit) }
            override fun onChange(selfChange: Boolean, uri: Uri?) { trySend(Unit) }
        }
        val observed = try {
            app.contentResolver.registerContentObserver(CalendarContract.Events.CONTENT_URI, true, obs)
            app.contentResolver.registerContentObserver(CalendarContract.Instances.CONTENT_URI, true, obs)
            true
        } catch (e: SecurityException) { false }
        awaitClose { if (observed) app.contentResolver.unregisterContentObserver(obs) }
    }.buffer(kotlinx.coroutines.channels.Channel.CONFLATED).conflate().debounce(500)

    private fun queryCalendars(onlyVisibleSynced: Boolean): List<Pair<CalendarInfo, Boolean>> {
        val proj = arrayOf(
            CalendarContract.Calendars._ID, CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.CALENDAR_COLOR, CalendarContract.Calendars.VISIBLE, CalendarContract.Calendars.SYNC_EVENTS,
        )
        val sel = if (onlyVisibleSynced) "${CalendarContract.Calendars.VISIBLE}=1 AND ${CalendarContract.Calendars.SYNC_EVENTS}=1" else null
        val out = ArrayList<Pair<CalendarInfo, Boolean>>()
        app.contentResolver.query(CalendarContract.Calendars.CONTENT_URI, proj, sel, null, "${CalendarContract.Calendars.ACCOUNT_NAME} ASC")?.use { c ->
            while (c.moveToNext()) {
                val sync = c.getInt(4) == 1 && c.getInt(5) == 1
                out.add(CalendarInfo(c.getLong(0), c.getString(1) ?: "", c.getString(2) ?: "", c.getInt(3), sync) to sync)
            }
        }
        return out
    }
}
