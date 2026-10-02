package com.fitair.app.ui.agenda

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.fitair.app.AppLog
import com.fitair.app.integrations.calendar.AgendaItem
import com.fitair.app.integrations.calendar.CalEvent
import com.fitair.app.integrations.calendar.CalendarRepo
import com.fitair.app.integrations.calendar.ics.IcsFeeds
import com.fitair.app.analytics.WorkIntensity
import com.fitair.app.analytics.WorkWindowStats
import com.fitair.app.ui.copy.Copy
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import com.fitair.app.integrations.calendar.buildAgenda
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Bumped when a calendar link is added, edited, removed or refreshed, so open agenda screens re-read. */
object FeedSignal {
    val tick = MutableStateFlow(0)
    fun bump() { tick.value = tick.value + 1 }
}

/** Agenda state shared by the Today block and the full-screen day timeline (activity-scoped). */
class AgendaVm(app: Application) : AndroidViewModel(app) {
    private val repo = CalendarRepo(app)

    var hasPerm by mutableStateOf(repo.hasPermission()); private set
    var day by mutableStateOf(LocalDate.now()); private set
    var items by mutableStateOf<List<AgendaItem>>(emptyList()); private set
    /** calendar id -> ARGB colour, for the dot on each row. */
    var colors by mutableStateOf(AgendaColors()); private set
    /** True when at least one calendar link is subscribed: events show even without the device-calendar permission. */
    var hasFeeds by mutableStateOf(false); private set
    /** Device calendar permission or at least one link: the agenda has something to show. */
    val active: Boolean get() = hasPerm || hasFeeds
    /** Today's work windows with heart-rate figures (empty when none). */
    var shifts by mutableStateOf<List<WorkWindowStats>>(emptyList()); private set
    /** True while a pull-to-refresh is fetching the links. */
    var feedBusy by mutableStateOf(false); private set
    /** Today's items (always today, whatever day the Calendar tab shows): Next up, the Today agenda. */
    var todayItems by mutableStateOf<List<AgendaItem>>(emptyList()); private set
    /** Tomorrow's events (Evening Tomorrow block, Next up when today is done). */
    var tomorrow by mutableStateOf<List<CalEvent>>(emptyList()); private set
    /** Selected calendars that do not sync to this phone (an ICS subscription that is ticked but empty). */
    var unsynced by mutableStateOf(0); private set
    var loaded by mutableStateOf(false); private set
    var loading by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    /** "Sync" button state: true while a manual calendar sync is in progress; [syncNote] is the quiet result line. */
    var syncing by mutableStateOf(false); private set
    var syncNote by mutableStateOf<String?>(null); private set
    private var job: Job? = null

    /** Fetches the calendar links, asks Android/Google to sync, then re-reads the events a few times as they arrive. */
    fun resync() {
        if (syncing) return
        viewModelScope.launch {
            syncing = true; syncNote = null
            val feeds = async(Dispatchers.IO) { runCatching { if (IcsFeeds.list(getApplication()).isEmpty()) null else IcsFeeds.refresh(getApplication()) }.getOrNull() }
            val n = withContext(Dispatchers.IO) { repo.requestSync() }
            val fr = feeds.await()
            val feedNote = fr?.let { r -> val bad = r.count { !it.ok }; if (bad == 0) "Calendar links are up to date." else if (bad == 1) "1 calendar link could not be updated." else "$bad calendar links could not be updated." }
            refresh()
            if (n == 0) {
                syncNote = feedNote ?: if (repo.hasPermission()) "No synced calendar account found" else "Calendar access is off"
                syncing = false
                return@launch
            }
            syncNote = "Syncing with Google\u2026"
            kotlinx.coroutines.delay(4_000); refresh()
            kotlinx.coroutines.delay(6_000); refresh()
            val t = java.time.LocalTime.now().withSecond(0).withNano(0)
            syncNote = (feedNote?.let { "$it " } ?: "") + "Checked at $t. Subscribed (URL) calendars in Google update on Google's side every few hours."
            syncing = false
        }
    }

    /** Pull-to-refresh: fetch the calendar links now, then re-read. */
    fun pullRefresh() {
        if (feedBusy) return
        viewModelScope.launch {
            feedBusy = true
            withContext(Dispatchers.IO) { runCatching { if (IcsFeeds.list(getApplication()).isNotEmpty()) IcsFeeds.refresh(getApplication()) } }
            refresh()
            feedBusy = false
        }
    }

    /** Device events and link events for [d]; a failure reading either leaves the other intact. */
    private suspend fun safeDay(d: LocalDate, z: ZoneId): List<CalEvent> =
        try { repo.eventsForDay(d, z) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { AppLog.d("agenda: events failed: ${e.message}"); emptyList() }

    /** The shift line for Today: the running or next work window with a figure, else the last one of the day. */
    fun shiftToday(nowMs: Long): WorkWindowStats? {
        val withFigure = shifts.filter { it.avgPctHrr != null }
        return withFigure.filter { it.endMs > nowMs }.minByOrNull { it.startMs } ?: withFigure.maxByOrNull { it.endMs }
    }

    fun changes(): Flow<Unit> = repo.changes()

    fun goToday() { if (day != LocalDate.now()) showDay(LocalDate.now()) }
    fun showDay(d: LocalDate) { day = d; refresh() }
    fun shift(days: Long) = showDay(day.plusDays(days))

    fun refresh() {
        hasPerm = repo.hasPermission()
        job?.cancel()
        job = viewModelScope.launch {
            loading = true
            try {
                val feedList = withContext(Dispatchers.IO) { runCatching { IcsFeeds.list(getApplication()) }.getOrDefault(emptyList()) }
                hasFeeds = feedList.isNotEmpty()
                if (!hasPerm && !hasFeeds) {
                    items = emptyList(); todayItems = emptyList(); tomorrow = emptyList(); unsynced = 0; shifts = emptyList(); loaded = true; loading = false
                    return@launch
                }
                val d = day
                val z = ZoneId.systemDefault()
                val start = d.atStartOfDay(z).toInstant()
                val end = d.plusDays(1).atStartOfDay(z).toInstant()
                val now = if (d == LocalDate.now(z)) Instant.now() else start
                val today = LocalDate.now(z)
                val r = withContext(Dispatchers.IO) {
                    val ev = safeDay(d, z)
                    val cals = runCatching { repo.calendars() }.getOrDefault(emptyList())
                    val t0 = today.atStartOfDay(z).toInstant()
                    val todayBuilt = if (d == today) buildAgenda(ev, now, start, end, zone = z)
                        else buildAgenda(safeDay(today, z), Instant.now(), t0, today.plusDays(1).atStartOfDay(z).toInstant(), zone = z)
                    val tom = if (d == today.plusDays(1)) ev else safeDay(today.plusDays(1), z)
                    val bad = runCatching { repo.unsyncedSelected().size }.getOrDefault(0)
                    val sh = runCatching { WorkIntensity.forDay(getApplication(), today) }.getOrDefault(emptyList())
                    val colors = AgendaColors(cals.associate { it.id to it.color }, feedList.associate { it.id to it.color })
                    Pair(Triple(Triple(buildAgenda(ev, now, start, end, zone = z), colors, todayBuilt), tom, bad), sh)
                }
                val q = r.first
                items = q.first.first; colors = q.first.second; todayItems = q.first.third; tomorrow = q.second; unsynced = q.third; shifts = r.second
                error = null
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.e("agenda load failed", e)
                error = e.message ?: e.javaClass.simpleName
            }
            loaded = true; loading = false
        }
    }
}
