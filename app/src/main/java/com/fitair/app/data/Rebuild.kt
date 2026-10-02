package com.fitair.app.data

import android.content.Context
import com.fitair.app.AppLog
import com.fitair.app.DailyMetrics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** [pct] 0..100 while [running]; [lastResult] is e.g. "Rebuilt 212 days in 14 s" after a run, or an error line. */
data class RebuildState(val running: Boolean = false, val pct: Int = 0, val detail: String = "", val lastResult: String? = null)

/**
 * Forced recompute of daily_metrics and load_day for every date from the earliest data to today (docs/PLAN_081.md 7.4), in the
 * background on a process-wide scope. Used after a Drive restore, after the first sync of an empty database and from
 * Settings > Diagnostics > Rebuild analytics.
 */
object Rebuild {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(RebuildState())
    val state: StateFlow<RebuildState> = _state

    @Volatile private var active = false

    fun start(ctx: Context) {
        val app = ctx.applicationContext
        synchronized(this) { if (active) return; active = true }
        _state.value = RebuildState(running = true, pct = 0, detail = "Rebuilding analytics 0 %")
        scope.launch {
            val t0 = System.currentTimeMillis()
            try {
                AppLog.init(app)
                val n = DailyMetrics.rebuildAll(app) { p -> _state.value = RebuildState(true, p, "Rebuilding analytics $p %") }
                val sec = (System.currentTimeMillis() - t0) / 1000
                _state.value = RebuildState(false, 100, "", if (n == 0) "Nothing to rebuild yet" else "Rebuilt $n days in $sec s")
            } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (e: Throwable) {
                AppLog.e("rebuild failed", e)
                _state.value = RebuildState(false, 0, "", "Rebuild failed: ${e.message ?: e.javaClass.simpleName}")
            } finally { active = false }
        }
    }
}
