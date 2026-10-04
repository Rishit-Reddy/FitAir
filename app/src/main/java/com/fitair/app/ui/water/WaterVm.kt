package com.fitair.app.ui.water

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.fitair.app.AppLog
import com.fitair.app.LocalApi
import com.fitair.app.LocalStore
import com.fitair.app.data.dao.WaterDao
import com.fitair.app.data.dao.WaterToday
import com.fitair.app.data.metrics.MetricsRepo
import com.fitair.app.notify.WaterAlarm
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Everything the Water page shows. [totals] are ml per day of [days] (oldest first, today last), null = nothing logged. */
class WaterUi(
    val today: WaterToday, val entries: List<WaterDao.Entry>, val usualByNow: Int?, val nextReminderMs: Long?,
    val span: Int, val days: List<LocalDate>, val totals: List<Int?>,
)

class WaterVm(app: Application) : AndroidViewModel(app) {
    var ui by mutableStateOf<WaterUi?>(null); private set
    var error by mutableStateOf<String?>(null); private set
    /** The drink just logged on this page, for the 10 s Undo. */
    var justLogged by mutableStateOf<Pair<Long, Int>?>(null); private set
    var span by mutableStateOf(7); private set
    private var job: Job? = null

    fun load() {
        job?.cancel()
        error = null
        job = viewModelScope.launch {
            try { ui = withContext(Dispatchers.IO) { build(span) } }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { AppLog.e("Water page load failed", e); error = e.message ?: e.javaClass.simpleName }
        }
    }

    fun chooseSpan(n: Int) { span = n; load() }

    fun add(ml: Int) {
        viewModelScope.launch {
            val t = withContext(Dispatchers.IO) { WaterDao.add(getApplication(), ml).also { MetricsRepo.invalidate() } }
            val mine = t to ml
            justLogged = mine
            load()
            delay(10_000)
            if (justLogged === mine) justLogged = null
        }
    }

    fun undo() {
        val l = justLogged ?: return
        justLogged = null
        viewModelScope.launch {
            withContext(Dispatchers.IO) { WaterDao.undo(getApplication(), l.first); MetricsRepo.invalidate() }
            load()
        }
    }

    fun delete(e: WaterDao.Entry) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { WaterDao.delete(getApplication(), e.t, e.origin); MetricsRepo.invalidate() }
            load()
        }
    }

    private fun build(span: Int): WaterUi {
        val ctx = getApplication<Application>()
        val z = ZoneId.systemDefault()
        val today = LocalDate.now(z)
        val days = (span - 1 downTo 0).map { today.minusDays(it.toLong()) }
        val t = WaterDao.dailyTotals(ctx, days.first(), today, z)
        val now = Instant.now().atZone(z)
        return WaterUi(
            WaterDao.today(ctx), WaterDao.entries(ctx, today, z), usualByNow(ctx, today, z, now.hour * 60 + now.minute),
            if (WaterDao.remindersOn(ctx)) runCatching { WaterAlarm.nextFireMs(ctx, System.currentTimeMillis()) }.getOrNull() else null,
            span, days, days.map { d -> t[d.toString()]?.first },
        )
    }

    /** Median of what was drunk by this time of day over the 14 days before today. */
    private fun usualByNow(ctx: Application, today: LocalDate, z: ZoneId, nowMin: Int): Int? = try {
        val (lo, _) = LocalApi.bounds(today.minusDays(14), z); val (start, _) = LocalApi.bounds(today, z)
        val byDay = HashMap<LocalDate, ArrayList<Pair<Int, Int>>>()
        LocalStore.get(ctx).db.rawQuery("SELECT t, ml FROM water WHERE t>=? AND t<?", arrayOf(lo.toString(), start.toString())).use {
            while (it.moveToNext()) {
                val at = Instant.ofEpochMilli(it.getLong(0)).atZone(z)
                byDay.getOrPut(at.toLocalDate()) { ArrayList() }.add((at.hour * 60 + at.minute) to Math.round(it.getDouble(1)).toInt())
            }
        }
        WaterMath.usualByNow(byDay.values.toList(), nowMin)
    } catch (e: Exception) { null }
}
