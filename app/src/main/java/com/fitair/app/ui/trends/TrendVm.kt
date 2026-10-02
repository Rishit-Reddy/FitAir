package com.fitair.app.ui.trends

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.fitair.app.AppLog
import com.fitair.app.LocalApi
import com.fitair.app.analytics.ReadinessView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.LocalDate

enum class TrendMetric(val title: String, val unit: String, val higherBetter: Boolean) {
    /** Names match everywhere (Copy): HRV is the "Recovery signal", load is "Cardio load". */
    Readiness("Readiness", "out of 100", true), Hrv("Recovery signal", "ms", true), RestingHr("Resting heart rate", "bpm", false), Load("Cardio load", "", false),
}

class TrendDay(
    val date: LocalDate, val value: Double?, val drivers: List<String>,
    val acute: Double?, val chronic: Double?, val acwr: Double?,
)

class TrendUi(val metric: TrendMetric, val days: List<TrendDay>, val band: Band?, val fullSeries: List<Double?>)

class TrendVm(app: Application) : AndroidViewModel(app) {
    var ui by mutableStateOf<TrendUi?>(null); private set
    var loading by mutableStateOf(true); private set
    var error by mutableStateOf<String?>(null); private set
    private var job: Job? = null

    fun load(metric: TrendMetric, span: Int) {
        job?.cancel()
        loading = true; error = null
        job = viewModelScope.launch {
            try {
                ui = withContext(Dispatchers.IO) { build(metric, span) }
            } catch (e: Exception) {
                AppLog.e("Trend load failed", e)
                error = e.message ?: e.javaClass.simpleName
            }
            loading = false
        }
    }

    private fun build(metric: TrendMetric, span: Int): TrendUi {
        val ctx = getApplication<Application>()
        val today = LocalDate.now()
        val from = today.minusDays((span - 1 + TrendMath.BASELINE_DAYS).toLong())
        val q = "from=$from&to=$today"
        val daily = LocalApi.get(ctx, "/daily?$q").getJSONArray("days")
        val byDate = HashMap<String, JSONObject>()
        for (i in 0 until daily.length()) daily.getJSONObject(i).let { byDate[it.getString("date")] = it }
        val loadByDate = HashMap<String, JSONObject>()
        if (metric == TrendMetric.Load) {
            val l = LocalApi.get(ctx, "/load?$q").getJSONArray("days")
            for (i in 0 until l.length()) l.getJSONObject(i).let { loadByDate[it.getString("date")] = it }
        }
        fun num(o: JSONObject?, k: String): Double? = o?.takeIf { it.has(k) && !it.isNull(k) }?.optDouble(k)?.takeIf { !it.isNaN() }

        val all = (0 until span + TrendMath.BASELINE_DAYS).map { from.plusDays(it.toLong()) }.map { d ->
            val ds = d.toString(); val row = byDate[ds]; val ld = loadByDate[ds]
            val value = when (metric) {
                TrendMetric.Readiness -> num(row, "readiness")
                TrendMetric.Hrv -> num(row, "hrv")
                TrendMetric.RestingHr -> num(row, "rhr")
                TrendMetric.Load -> num(ld, "trimp") ?: num(row, "load_trimp")
            }
            val drivers = if (metric == TrendMetric.Readiness)
                ReadinessView.parse(row?.opt("readiness_breakdown")?.toString())?.drivers?.take(2)?.map { com.fitair.app.ui.copy.Copy.driver(it.key, it.score, it.text).headline } ?: emptyList()
            else emptyList()
            TrendDay(d, value, drivers, num(ld, "acute"), num(ld, "chronic"), num(ld, "acwr"))
        }
        val series = all.map { it.value }
        val shown = all.takeLast(span)
        val li = TrendMath.lastIndex(series)
        val band = if (li != null && metric != TrendMetric.Load) TrendMath.baselineBefore(series, li) else null
        return TrendUi(metric, shown, band, series)
    }
}
