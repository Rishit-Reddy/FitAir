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
import com.fitair.app.integrations.calendar.buildAgenda
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Agenda state shared by the Today block and the full-screen day timeline (activity-scoped). */
class AgendaVm(app: Application) : AndroidViewModel(app) {
    private val repo = CalendarRepo(app)

    var hasPerm by mutableStateOf(repo.hasPermission()); private set
    var day by mutableStateOf(LocalDate.now()); private set
    var items by mutableStateOf<List<AgendaItem>>(emptyList()); private set
    /** calendar id -> ARGB colour, for the dot on each row. */
    var colors by mutableStateOf<Map<Long, Int>>(emptyMap()); private set
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

    /** Asks Android/Google to sync the calendars now, then re-reads the events a few times as they arrive. */
    fun resync() {
        if (syncing) return
        viewModelScope.launch {
            syncing = true; syncNote = null
            val n = withContext(Dispatchers.IO) { repo.requestSync() }
            if (n == 0) {
                syncNote = if (repo.hasPermission()) "No synced calendar account found" else "Calendar access is off"
                syncing = false
                return@launch
            }
            syncNote = "Syncing with Google\u2026"
            kotlinx.coroutines.delay(4_000); refresh()
            kotlinx.coroutines.delay(6_000); refresh()
            val t = java.time.LocalTime.now().withSecond(0).withNano(0)
            syncNote = "Checked at $t. Subscribed (URL) calendars update on Google's side every few hours."
            syncing = false
        }
    }

    fun changes(): Flow<Unit> = repo.changes()

    fun goToday() { if (day != LocalDate.now()) showDay(LocalDate.now()) }
    fun showDay(d: LocalDate) { day = d; refresh() }
    fun shift(days: Long) = showDay(day.plusDays(days))

    fun refresh() {
        hasPerm = repo.hasPermission()
        if (!hasPerm) { items = emptyList(); todayItems = emptyList(); tomorrow = emptyList(); unsynced = 0; loaded = true; return }
        job?.cancel()
        job = viewModelScope.launch {
            loading = true
            try {
                val d = day
                val z = ZoneId.systemDefault()
                val start = d.atStartOfDay(z).toInstant()
                val end = d.plusDays(1).atStartOfDay(z).toInstant()
                val now = if (d == LocalDate.now(z)) Instant.now() else start
                val today = LocalDate.now(z)
                val r = withContext(Dispatchers.IO) {
                    val ev = repo.eventsForDay(d, z)
                    val cals = runCatching { repo.calendars() }.getOrDefault(emptyList())
                    val t0 = today.atStartOfDay(z).toInstant()
                    val todayBuilt = if (d == today) buildAgenda(ev, now, start, end, zone = z)
                        else buildAgenda(repo.eventsForDay(today, z), Instant.now(), t0, today.plusDays(1).atStartOfDay(z).toInstant(), zone = z)
                    val tom = if (d == today.plusDays(1)) ev else repo.eventsForDay(today.plusDays(1), z)
                    val bad = runCatching { repo.unsyncedSelected().size }.getOrDefault(0)
                    Triple(Triple(buildAgenda(ev, now, start, end, zone = z), cals.associate { it.id to it.color }, todayBuilt), tom, bad)
                }
                items = r.first.first; colors = r.first.second; todayItems = r.first.third; tomorrow = r.second; unsynced = r.third
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
