package com.fitair.app.ui.metrics

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.fitair.app.AppLog
import com.fitair.app.SyncScheduler
import com.fitair.app.data.metrics.MetricSnapshot
import com.fitair.app.data.metrics.MetricsRepo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

/** State of the Metrics tab: the shared snapshot, the saved layout and Edit mode. */
class MetricsVm(app: Application) : AndroidViewModel(app) {
    var snapshot by mutableStateOf<MetricSnapshot?>(null); private set
    var layout by mutableStateOf<List<LayoutEntry>>(MetricsLayout.defaults(false)); private set
    var syncing by mutableStateOf(false); private set
    var editing by mutableStateOf(false); private set
    /** The list being edited; saved on Done. */
    var draft by mutableStateOf<List<LayoutEntry>>(emptyList()); private set

    init {
        load()
        viewModelScope.launch {
            var was = false
            SyncScheduler.nowFlow(app).collect { infos ->
                val now = infos.any { !it.state.isFinished }
                syncing = now
                if (was && !now) load()
                was = now
            }
        }
    }

    /** Rebuild from the local database (shared cache with Today). */
    fun load() {
        viewModelScope.launch {
            try {
                val ctx = getApplication<Application>()
                val snap = withContext(Dispatchers.IO) { MetricsRepo.snapshot(ctx, LocalDate.now()) }
                val hasWeight = snap.weight.isNotEmpty()
                val l = withContext(Dispatchers.IO) { MetricsLayout.read(ctx, hasWeight) }
                snapshot = snap
                layout = l
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { AppLog.e("Metrics load failed", e) }
        }
    }

    fun refresh() { AppLog.d("Metrics: pull to refresh"); SyncScheduler.syncNow(getApplication()) }

    fun startEdit() { draft = layout; editing = true }
    fun move(index: Int, delta: Int) { draft = MetricsLayout.move(draft, index, delta) }
    fun setOn(e: LayoutEntry, on: Boolean) { draft = MetricsLayout.setOn(draft, e.id, on) }

    fun done() {
        val d = draft
        layout = d; editing = false
        viewModelScope.launch { withContext(Dispatchers.IO) { MetricsLayout.write(getApplication(), d) } }
    }

    fun reset() {
        val hasWeight = snapshot?.weight?.isNotEmpty() == true
        val d = MetricsLayout.defaults(hasWeight)
        draft = d; layout = d
        viewModelScope.launch { withContext(Dispatchers.IO) { MetricsLayout.reset(getApplication()) } }
    }
}
