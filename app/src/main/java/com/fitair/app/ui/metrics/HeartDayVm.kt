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
import com.fitair.app.data.metrics.MetricStats
import com.fitair.app.data.metrics.MetricsRepo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId

/** An exercise session drawn as a tick on the time axis; [flagged] = counted as "not exercise" (drawn dim). */
class HeartSession(val startMs: Long, val endMs: Long, val flagged: Boolean)

/** One day of heart rate: 288 five-minute means with their lowest and highest 30 s reading, zones and the numbers under the chart. */
class HeartDayUi(
    val date: LocalDate, val mean: FloatArray, val lo: FloatArray, val hi: FloatArray,
    val rest: Float, val hrMax: Float, val hrMaxSource: String, val zoneBpm: FloatArray,
    val sessions: List<HeartSession>, val zoneMin: IntArray?, val restingDay: Double?, val avg: Double?, val max: Int?,
    val latestBpm: Int?, val latestMs: Long?, val coverage: Double?,
) { val hasData: Boolean get() = mean.any { !it.isNaN() } }

class HeartDayVm(app: Application) : AndroidViewModel(app) {
    var ui by mutableStateOf<HeartDayUi?>(null); private set
    var error by mutableStateOf<String?>(null); private set
    private var job: Job? = null

    /** [back] = days before today (0 = today, at most [MAX_BACK]). */
    fun load(back: Int) {
        job?.cancel()
        error = null
        job = viewModelScope.launch {
            try {
                ui = withContext(Dispatchers.IO) { build(LocalDate.now().minusDays(back.coerceIn(0, MAX_BACK).toLong())) }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (e: Exception) {
                AppLog.e("HeartDay load failed", e)
                error = e.message ?: e.javaClass.simpleName
            }
        }
    }

    private fun build(date: LocalDate): HeartDayUi {
        val ctx = getApplication<Application>()
        val z = ZoneId.systemDefault()
        val (lo, hi) = LocalApi.bounds(date, z)
        val hm = LoadDao.hrMax(ctx)
        val rows = MetricsRepo.hrRows(ctx, z, date)
        val bins = MetricStats.downsample(rows, lo)
        val rest = LoadDao.restFor(ctx, date, z)
        val live = try { LoadDao.live(ctx, date) } catch (e: Exception) { null }
        val stored = if (live == null) LoadDao.row(ctx, date) else null
        val zones = live?.let { intArrayOf(it.zLight, it.zMod, it.zVig, it.zPeak) }
            ?: stored?.let { intArrayOf(it.zLight, it.zMod, it.zVig, it.zPeak) }
        val sessions = try {
            val flagged = LoadDao.excludedWindows(ctx, lo, hi)
            LocalApi.exercises(LocalStore.get(ctx).db, lo, hi, includeFlagged = true).map { e ->
                HeartSession(e.start, e.end, flagged.any { it[0] == e.start })
            }
        } catch (e: Exception) { emptyList() }
        val day = try { LocalStore.get(ctx).getDaily(date.toString(), date.toString()).firstOrNull() } catch (e: Exception) { null }
        val n = rows.sumOf { it.n }
        val avg = if (n > 0) rows.sumOf { it.mean * it.n } / n else null
        val last = rows.lastOrNull()
        return HeartDayUi(
            date = date, mean = bins.mean, lo = bins.lo, hi = bins.hi, rest = rest.toFloat(), hrMax = hm.value.toFloat(),
            hrMaxSource = hm.source.label, zoneBpm = MetricStats.zoneBpm(rest, hm.value), sessions = sessions, zoneMin = zones,
            restingDay = day?.takeIf { it.has("rhr") }?.optDouble("rhr")?.takeIf { !it.isNaN() },
            avg = avg, max = rows.maxOfOrNull { it.max }, latestBpm = last?.let { Math.round(it.mean).toInt() },
            latestMs = last?.let { it.t30 + 30_000L }, coverage = live?.coverage ?: stored?.coverage,
        )
    }

    companion object { const val MAX_BACK = 30 }
}
