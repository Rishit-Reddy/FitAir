package com.fitair.app.integrations.calendar.ics

import android.content.ContentValues
import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.fitair.app.AppLog
import com.fitair.app.LocalStore
import com.fitair.app.integrations.calendar.CalEvent
import com.fitair.app.integrations.calendar.CalendarInfo
import com.fitair.app.integrations.calendar.CalendarPrefs
import com.fitair.app.integrations.calendar.allDayToLocal
import com.fitair.app.secure.SecretStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit

data class FeedInfo(
    val id: Long, val label: String, val color: Int, val work: Boolean, val shareTitles: Boolean,
    val lastOkMs: Long?, val lastError: String?, val eventCount: Int,
)

class PreviewResult(val events: Int, val next: List<CalEvent>, val error: String?)

sealed class AddResult {
    class Ok(val feed: FeedInfo) : AddResult()
    class Fail(val message: String) : AddResult()
}

class RefreshResult(val feedId: Long, val ok: Boolean, val changed: Boolean, val message: String?)

/**
 * Subscribed iCal (ICS) calendars. The URL is a secret: it lives only in SecretStore ("feed_url_<id>") and is never logged,
 * stored in the database, put in a message, a backup or the debug bundle. Events are expanded for [today-35 d, today+120 d].
 */
object IcsFeeds {
    const val PAST_DAYS = 35L
    const val FUTURE_DAYS = 120L
    const val STALE_MS = 30 * 60_000L
    private const val WORK_NAME = "ics_feeds"
    private const val DAY = 86_400_000L

    private val mutex = Mutex()
    @Volatile private var lastAttemptMs = 0L

    private val _changes = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    /** Emits whenever stored feed events or feed settings changed (the calendar repo merges this into its own change flow). */
    val changes: SharedFlow<Unit> get() = _changes

    /** Calendar id used for a feed's events and its entry in the calendar list: always negative, unique per feed. */
    fun calId(feedId: Long): Long = -feedId
    fun feedIdOf(calId: Long): Long? = if (calId < 0) -calId else null

    private fun secretName(id: Long) = "feed_url_$id"

    private fun window(zone: ZoneId = ZoneId.systemDefault()): Pair<Long, Long> {
        val today = LocalDate.now(zone)
        return today.minusDays(PAST_DAYS).atStartOfDay(zone).toInstant().toEpochMilli() to
            today.plusDays(FUTURE_DAYS).atStartOfDay(zone).toInstant().toEpochMilli()
    }

    // ---------- reads ----------

    internal fun listBlocking(ctx: Context): List<FeedInfo> {
        val out = ArrayList<FeedInfo>()
        LocalStore.get(ctx).db.rawQuery(
            "SELECT id,label,color,work,share_titles,last_ok_ms,last_error,event_count FROM cal_feed ORDER BY id", null,
        ).use {
            while (it.moveToNext()) out.add(FeedInfo(
                it.getLong(0), it.getString(1) ?: "", it.getInt(2), it.getInt(3) == 1, it.getInt(4) == 1,
                if (it.isNull(5)) null else it.getLong(5), it.getString(6), it.getInt(7),
            ))
        }
        return out
    }

    suspend fun list(ctx: Context): List<FeedInfo> = withContext(Dispatchers.IO) {
        try { listBlocking(ctx) } catch (e: Exception) { AppLog.d("feeds: list failed: ${e.javaClass.simpleName}"); emptyList() }
    }

    /** Feeds as selectable calendars (id = [calId]). */
    suspend fun asCalendars(ctx: Context): List<CalendarInfo> =
        list(ctx).map { CalendarInfo(calId(it.id), it.label, "Subscribed", it.color, true) }

    /**
     * Feed events overlapping [fromMs, toMs) (local-zone instants; all-day events are placed on their local dates).
     * [calIds] null = all feeds; otherwise only feeds whose [calId] is in the set. [workOnly] keeps work-flagged feeds.
     */
    suspend fun events(ctx: Context, fromMs: Long, toMs: Long, zone: ZoneId, calIds: Set<Long>?, workOnly: Boolean = false): List<CalEvent> =
        withContext(Dispatchers.IO) {
            try { eventsBlocking(ctx, fromMs, toMs, zone, calIds, workOnly) }
            catch (e: Exception) { AppLog.d("feeds: events failed: ${e.javaClass.simpleName}"); emptyList() }
        }

    internal fun eventsBlocking(ctx: Context, fromMs: Long, toMs: Long, zone: ZoneId, calIds: Set<Long>?, workOnly: Boolean): List<CalEvent> {
        val feeds = listBlocking(ctx).filter { (calIds == null || calId(it.id) in calIds) && (!workOnly || it.work) }
        if (feeds.isEmpty()) return emptyList()
        val byId = feeds.associateBy { it.id }
        val ids = feeds.joinToString(",") { it.id.toString() }
        val out = ArrayList<CalEvent>()
        LocalStore.get(ctx).db.rawQuery(
            "SELECT feed_id,uid,rec_key,begin_ms,end_ms,all_day,title,location,busy FROM cal_feed_event " +
                "WHERE feed_id IN ($ids) AND begin_ms < ? AND end_ms >= ? ORDER BY begin_ms",
            arrayOf((toMs + DAY).toString(), (fromMs - 8 * DAY).toString()),   // wide; precise filter below (all-day are UTC midnights)
        ).use {
            while (it.moveToNext()) {
                val f = byId[it.getLong(0)] ?: continue
                val allDay = it.getInt(5) == 1
                val rb = it.getLong(3); val re = it.getLong(4)
                val (b, e) = if (allDay) allDayToLocal(rb, re, zone) else Instant.ofEpochMilli(rb) to Instant.ofEpochMilli(re)
                val bm = b.toEpochMilli(); val em = e.toEpochMilli()
                if (!(bm < toMs && (em > fromMs || (em == bm && bm >= fromMs)))) continue
                out.add(CalEvent(
                    instanceId = ((f.id * 1_000_003L) xor (it.getString(1).hashCode().toLong() shl 20) xor it.getString(2).hashCode().toLong()),
                    calId = calId(f.id), title = it.getString(6)?.takeIf { s -> s.isNotBlank() } ?: "(no title)",
                    begin = b, end = e, allDay = allDay, busy = it.getInt(8) == 1, location = it.getString(7)?.takeIf { s -> s.isNotBlank() },
                    work = f.work, feedId = f.id,
                ))
            }
        }
        return out.sortedBy { it.begin }
    }

    // ---------- writes ----------

    suspend fun preview(url: String): PreviewResult = withContext(Dispatchers.IO) {
        val n = IcsFetcher.normalize(url)
        val u = n.url ?: return@withContext PreviewResult(0, emptyList(), n.error)
        when (val r = IcsFetcher.fetch(u)) {
            is IcsFetcher.Fetch.Err -> PreviewResult(0, emptyList(), r.message)
            is IcsFetcher.Fetch.NotModified -> PreviewResult(0, emptyList(), "The calendar returned no data.")
            is IcsFetcher.Fetch.Ok -> {
                val zone = ZoneId.systemDefault()
                val (lo, hi) = window(zone)
                val inst = IcsParser.parse(r.text, lo, hi, zone)
                val now = System.currentTimeMillis()
                val next = inst.filter { it.endMs >= now || it.allDay }.sortedBy { it.beginMs }.take(5).map { i ->
                    val (b, e) = if (i.allDay) allDayToLocal(i.beginMs, i.endMs, zone) else Instant.ofEpochMilli(i.beginMs) to Instant.ofEpochMilli(i.endMs)
                    CalEvent(i.beginMs, 0, i.title, b, e, i.allDay, i.busy, i.location)
                }
                PreviewResult(inst.size, next, null)
            }
        }
    }

    suspend fun add(ctx: Context, url: String, label: String, color: Int, work: Boolean, share: Boolean): AddResult = withContext(Dispatchers.IO) {
        val n = IcsFetcher.normalize(url)
        val u = n.url ?: return@withContext AddResult.Fail(n.error ?: "Invalid link.")
        mutex.withLock {
            val r = IcsFetcher.fetch(u)
            if (r is IcsFetcher.Fetch.Err) return@withContext AddResult.Fail(r.message)
            val ok = r as? IcsFetcher.Fetch.Ok ?: return@withContext AddResult.Fail("The calendar returned no data.")
            val db = LocalStore.get(ctx).db
            val cv = ContentValues().apply {
                put("label", label.trim().ifEmpty { "Calendar" }.take(60)); put("color", color); put("work", if (work) 1 else 0)
                put("share_titles", if (share) 1 else 0)
            }
            val id = db.insert("cal_feed", null, cv)
            if (id < 0) return@withContext AddResult.Fail("Could not save the calendar.")
            try {
                SecretStore.set(ctx, secretName(id), u)
            } catch (e: Exception) {
                db.delete("cal_feed", "id=?", arrayOf(id.toString()))
                return@withContext AddResult.Fail("Secure storage is unavailable on this device, so the calendar link cannot be saved.")
            }
            store(ctx, id, ok)
            // keep a custom calendar selection compatible: a new feed starts selected
            CalendarPrefs.selectedIds(ctx)?.let { CalendarPrefs.setSelected(ctx, it + calId(id)) }
            _changes.tryEmit(Unit)
            AppLog.d("feeds: added feed #$id")
            AddResult.Ok(listBlocking(ctx).first { it.id == id })
        }
    }

    suspend fun update(ctx: Context, id: Long, label: String, color: Int, work: Boolean, share: Boolean) {
        withContext(Dispatchers.IO) {
            try {
                val cv = ContentValues().apply {
                    put("label", label.trim().ifEmpty { "Calendar" }.take(60)); put("color", color); put("work", if (work) 1 else 0)
                    put("share_titles", if (share) 1 else 0)
                }
                LocalStore.get(ctx).db.update("cal_feed", cv, "id=?", arrayOf(id.toString()))
                _changes.tryEmit(Unit)
            } catch (e: Exception) { AppLog.d("feeds: update failed: ${e.javaClass.simpleName}") }
        }
    }

    suspend fun remove(ctx: Context, id: Long) {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                try {
                    val db = LocalStore.get(ctx).db
                    db.beginTransaction()
                    try {
                        db.delete("cal_feed_event", "feed_id=?", arrayOf(id.toString()))
                        db.delete("cal_feed", "id=?", arrayOf(id.toString()))
                        db.setTransactionSuccessful()
                    } finally { db.endTransaction() }
                    try { SecretStore.set(ctx, secretName(id), "") } catch (e: Exception) { /* nothing stored */ }
                    CalendarPrefs.selectedIds(ctx)?.let { CalendarPrefs.setSelected(ctx, it - calId(id)) }
                    _changes.tryEmit(Unit)
                    AppLog.d("feeds: removed feed #$id")
                } catch (e: Exception) { AppLog.d("feeds: remove failed: ${e.javaClass.simpleName}") }
            }
        }
    }

    /** Replaces the stored events of [id] with the parsed [r]; returns whether anything changed. Caller holds the mutex. */
    private fun store(ctx: Context, id: Long, r: IcsFetcher.Fetch.Ok): Boolean {
        val zone = ZoneId.systemDefault()
        val (lo, hi) = window(zone)
        val inst = IcsParser.parse(r.text, lo, hi, zone)
        val db = LocalStore.get(ctx).db
        fun sig(uid: String, key: String, b: Long, e: Long, ad: Boolean, t: String?, l: String?, busy: Boolean) = "$uid|$key|$b|$e|$ad|$t|$l|$busy"
        val old = HashSet<String>()
        db.rawQuery("SELECT uid,rec_key,begin_ms,end_ms,all_day,title,location,busy FROM cal_feed_event WHERE feed_id=?", arrayOf(id.toString())).use {
            while (it.moveToNext()) old.add(sig(it.getString(0), it.getString(1), it.getLong(2), it.getLong(3), it.getInt(4) == 1, it.getString(5), it.getString(6), it.getInt(7) == 1))
        }
        val new = inst.map { sig(it.uid, it.recKey, it.beginMs, it.endMs, it.allDay, it.title, it.location, it.busy) }.toSet()
        val changed = old != new
        db.beginTransaction()
        try {
            if (changed) {
                db.delete("cal_feed_event", "feed_id=?", arrayOf(id.toString()))
                val st = db.compileStatement("INSERT OR REPLACE INTO cal_feed_event(feed_id,uid,rec_key,begin_ms,end_ms,all_day,title,location,busy) VALUES(?,?,?,?,?,?,?,?,?)")
                for (i in inst) {
                    st.clearBindings()
                    st.bindLong(1, id); st.bindString(2, i.uid); st.bindString(3, i.recKey); st.bindLong(4, i.beginMs); st.bindLong(5, i.endMs)
                    st.bindLong(6, if (i.allDay) 1 else 0); st.bindString(7, i.title)
                    if (i.location != null) st.bindString(8, i.location) else st.bindNull(8)
                    st.bindLong(9, if (i.busy) 1 else 0)
                    st.executeInsert()
                }
            }
            val cv = ContentValues().apply {
                put("etag", r.etag); put("last_modified", r.lastModified); put("last_ok_ms", System.currentTimeMillis())
                putNull("last_error"); put("event_count", inst.size)
            }
            db.update("cal_feed", cv, "id=?", arrayOf(id.toString()))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return changed
    }

    /** Refreshes one feed ([id]) or all. Failures keep the previously stored events and record a message (never the URL). */
    suspend fun refresh(ctx: Context, id: Long? = null): List<RefreshResult> = withContext(Dispatchers.IO) {
        mutex.withLock {
            lastAttemptMs = System.currentTimeMillis()
            val db = LocalStore.get(ctx).db
            val feeds = try { listBlocking(ctx).filter { id == null || it.id == id } } catch (e: Exception) { emptyList() }
            val results = ArrayList<RefreshResult>()
            for (f in feeds) {
                val url = SecretStore.get(ctx, secretName(f.id))
                var etag: String? = null; var lastMod: String? = null
                db.rawQuery("SELECT etag,last_modified FROM cal_feed WHERE id=?", arrayOf(f.id.toString())).use {
                    if (it.moveToFirst()) { etag = it.getString(0); lastMod = it.getString(1) }
                }
                if (url.isEmpty()) { results.add(fail(db, f.id, "The calendar link is missing. Remove the calendar and add it again.")); continue }
                // an event-less store (e.g. after an interrupted add) must not be answered with 304
                if (f.eventCount == 0) { etag = null; lastMod = null }
                when (val r = IcsFetcher.fetch(url, etag, lastMod)) {
                    is IcsFetcher.Fetch.Err -> results.add(fail(db, f.id, r.message))
                    is IcsFetcher.Fetch.NotModified -> {
                        val cv = ContentValues().apply { put("last_ok_ms", System.currentTimeMillis()); putNull("last_error") }
                        db.update("cal_feed", cv, "id=?", arrayOf(f.id.toString()))
                        results.add(RefreshResult(f.id, true, false, null))
                    }
                    is IcsFetcher.Fetch.Ok -> {
                        val ch = try { store(ctx, f.id, r) } catch (e: Exception) {
                            results.add(fail(db, f.id, "Could not read the calendar data.")); continue
                        }
                        results.add(RefreshResult(f.id, true, ch, null))
                    }
                }
            }
            if (results.any { it.changed || !it.ok } ) _changes.tryEmit(Unit)
            AppLog.d("feeds: refreshed ${results.size} feed(s), ${results.count { it.ok }} ok, ${results.count { it.changed }} changed")
            results
        }
    }

    private fun fail(db: android.database.sqlite.SQLiteDatabase, id: Long, msg: String): RefreshResult {
        try {
            val cv = ContentValues().apply { put("last_error", msg) }
            db.update("cal_feed", cv, "id=?", arrayOf(id.toString()))
        } catch (e: Exception) { /* ignore */ }
        return RefreshResult(id, false, false, msg)
    }

    /** Call on app open: refreshes feeds whose last success is older than 30 min (attempts are throttled to one per 5 min). */
    suspend fun refreshIfStale(ctx: Context) {
        val now = System.currentTimeMillis()
        if (now - lastAttemptMs < 5 * 60_000L) return
        val stale = list(ctx).any { it.lastOkMs == null || now - it.lastOkMs > STALE_MS }
        if (stale) refresh(ctx)
    }

    /** Enqueues the 2-hourly background refresh (any network, KEEP). Safe to call on every start. */
    fun schedule(ctx: Context) {
        try {
            val req = PeriodicWorkRequestBuilder<IcsWorker>(2, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
            WorkManager.getInstance(ctx.applicationContext).enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, req)
        } catch (e: Exception) { AppLog.d("feeds: schedule failed: ${e.javaClass.simpleName}") }
    }
}
