package com.fitair.app.ui.load

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.fitair.app.AppLog
import com.fitair.app.DailyMetrics
import com.fitair.app.analytics.CardioLoad
import com.fitair.app.data.Rebuild
import com.fitair.app.data.dao.LoadDao
import com.fitair.app.data.dao.LoadDayRow
import com.fitair.app.data.dao.LoadToday
import com.fitair.app.data.dao.SessionRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId

class LoadUi(
    val today: LoadToday,
    val zones: IntArray,                 // light, moderate, vigorous, peak minutes today
    val days: List<Pair<LocalDate, LoadDayRow?>>,   // last 28 days, oldest first
    val ratio: Double?,
    val hrMax: CardioLoad.HrMax,
    val sessions: List<SessionRow>,
)

/** State of the Load screen. Everything is read from the local DB; verdict wording comes from Copy. */
class LoadVm(app: Application) : AndroidViewModel(app) {
    var ui by mutableStateOf<LoadUi?>(null); private set
    var error by mutableStateOf<String?>(null); private set
    var busy by mutableStateOf(false); private set

    init { load() }

    fun load() {
        viewModelScope.launch {
            try {
                ui = withContext(Dispatchers.IO) { build() }
                error = null
            } catch (e: Exception) {
                AppLog.e("Load screen failed", e)
                error = e.message ?: e.javaClass.simpleName
            }
        }
    }

    private fun build(): LoadUi {
        val ctx = getApplication<Application>()
        val today = LocalDate.now(ZoneId.systemDefault())
        DailyMetrics.ensure(ctx, today.minusDays(28).toString(), today.toString())
        val rows = LoadDao.rows(ctx, today.minusDays(27), today).associateBy { it.date }
        val live = LoadDao.live(ctx, today)
        val days = (27 downTo 0).map { k -> today.minusDays(k.toLong()).let { it to rows[it.toString()] } }
        val lt = LoadDao.today(ctx)
        return LoadUi(
            today = lt,
            zones = intArrayOf(live?.zLight ?: 0, live?.zMod ?: 0, live?.zVig ?: 0, live?.zPeak ?: 0),
            days = days, ratio = lt.ratio ?: rows.values.lastOrNull { it.ratio != null }?.ratio,
            hrMax = LoadDao.hrMax(ctx), sessions = LoadDao.sessionRows(ctx, today.minusDays(13), today),
        )
    }

    /** Stores the user's verdict for [s] and recomputes load and readiness from that date. */
    fun setVerdict(s: SessionRow, exercise: Boolean) {
        if (busy) return
        busy = true
        viewModelScope.launch {
            try { withContext(Dispatchers.IO) { LoadDao.setVerdict(getApplication(), s, exercise) } }
            catch (e: Exception) { AppLog.e("set verdict failed", e); error = e.message ?: e.javaClass.simpleName }
            busy = false
            load()
        }
    }

    /** Sets (or clears with null) the max heart rate and recomputes all days in the background. */
    fun saveHrMax(v: Int?) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                LoadDao.setHrMax(getApplication(), v?.takeIf { it in 120..230 }?.toDouble())
                Rebuild.start(getApplication())
            }
            load()
        }
    }
}
