package com.fitair.app.ui.metrics

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.fitair.app.AppLog
import com.fitair.app.LocalApi
import com.fitair.app.LocalStore
import com.fitair.app.data.dao.LoadDao
import com.fitair.app.data.dao.WaterDao
import com.fitair.app.data.metrics.MetricId
import com.fitair.app.data.metrics.MetricStats
import com.fitair.app.data.metrics.MetricsRepo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId

/**
 * One metric over a period. [days]/[values] are the chosen [span] ending today (oldest first), values in the display unit (steps,
 * kcal, km, litres), null = nothing recorded. [prev] = the [span] days before. [waterGoalL] only for water.
 */
class DailyTotalUi(
    val metric: MetricId, val span: Int, val days: List<LocalDate>, val values: List<Double?>, val prev: List<Double?>,
    val waterGoalL: Double?, val entries: List<WaterDao.Entry>,
) {
    /** Complete days only: today is still filling up, so it is left out of the average and the comparison. */
    val doneValues: List<Double?> get() = values.dropLast(1)
    val since: MetricStats.Since get() = MetricStats.since(doneValues, prev)
}

class DailyTotalVm(app: Application) : AndroidViewModel(app) {
    var ui by mutableStateOf<DailyTotalUi?>(null); private set
    var error by mutableStateOf<String?>(null); private set
    private var job: Job? = null

    fun load(metric: MetricId, span: Int) {
        job?.cancel()
        error = null
        job = viewModelScope.launch {
            try {
                ui = withContext(Dispatchers.IO) { build(metric, span) }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (e: Exception) {
                AppLog.e("DailyTotal load failed", e)
                error = e.message ?: e.javaClass.simpleName
            }
        }
    }

    /** Removes one water entry (long press) and reloads. */
    fun deleteWater(e: WaterDao.Entry, metric: MetricId, span: Int) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { WaterDao.delete(getApplication(), e.t, e.origin); MetricsRepo.invalidate() }
            load(metric, span)
        }
    }

    private fun build(metric: MetricId, span: Int): DailyTotalUi {
        val ctx = getApplication<Application>()
        val z = ZoneId.systemDefault()
        val today = LocalDate.now(z)
        val from = today.minusDays(2L * span - 1)
        val all = (0 until 2 * span).map { from.plusDays(it.toLong()) }
        val raw: List<Double?> = when (metric) {
            MetricId.Water -> {
                val t = WaterDao.dailyTotals(ctx, from, today, z)
                all.map { d -> t[d.toString()]?.first?.let { it / 1000.0 } }
            }
            else -> {
                val ser = LocalApi.series(LocalStore.get(ctx).db, z, from, today, LoadDao.hrMax(ctx).value)
                all.map { d ->
                    val s = ser[d.toString()]
                    when (metric) {
                        MetricId.Steps -> s?.steps?.toDouble()
                        MetricId.Distance -> s?.distance?.div(1000.0)
                        MetricId.Energy -> s?.kcal
                        else -> null
                    }?.takeIf { it > 0.0 }
                }
            }
        }
        val water = metric == MetricId.Water
        return DailyTotalUi(
            metric, span, all.takeLast(span), raw.takeLast(span), raw.take(span),
            if (water) WaterDao.baseGoalMl(ctx) / 1000.0 else null,
            if (water) WaterDao.entries(ctx, today, z) else emptyList(),
        )
    }
}
