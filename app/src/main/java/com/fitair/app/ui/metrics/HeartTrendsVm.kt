package com.fitair.app.ui.metrics

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.fitair.app.AppLog
import com.fitair.app.LocalApi
import com.fitair.app.LocalStore
import com.fitair.app.analytics.WorkIntensity
import com.fitair.app.data.dao.LoadDao
import com.fitair.app.data.metrics.HeartExtras
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId

/** Shift days vs off days over the last four weeks; averages are null when a group has fewer than two days with data. */
class ShiftCompare(val shiftDays: Int, val offDays: Int, val shiftAvg: Double?, val offAvg: Double?, val restAfterShift: Double?, val restAfterOff: Double?)

/** The trend sections of the Heart rate page, always ending today: resting 30 days, zones of the last 7 days, shift comparison. */
class HeartTrendsUi(
    val days30: List<LocalDate>, val rest30: List<Double?>,
    val days7: List<LocalDate>, val zones7: List<IntArray?>,
    val shifts: ShiftCompare?,
)

class HeartTrendsVm(app: Application) : AndroidViewModel(app) {
    var ui by mutableStateOf<HeartTrendsUi?>(null); private set

    fun load() {
        if (ui != null) return
        viewModelScope.launch {
            ui = try { withContext(Dispatchers.IO) { build(getApplication()) } } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (e: Exception) { AppLog.e("HeartTrends load failed", e); null }
        }
    }

    private fun build(ctx: Context): HeartTrendsUi {
        val z = ZoneId.systemDefault()
        val today = LocalDate.now(z)
        val days30 = (29 downTo 0).map { today.minusDays(it.toLong()) }
        val daily = try { LocalStore.get(ctx).getDaily(today.minusDays(30).toString(), today.toString()) } catch (e: Exception) { emptyList() }
        val rhr = daily.associate { it.optString("date") to it.optDouble("rhr").takeIf { v -> !v.isNaN() && v > 0 } }
        val days7 = days30.takeLast(7)
        val stored = try { LoadDao.rows(ctx, days7.first(), today).associateBy { it.date } } catch (e: Exception) { emptyMap() }
        val zones7 = days7.map { d ->
            if (d == today) (try { LoadDao.live(ctx, d) } catch (e: Exception) { null })?.let { intArrayOf(it.zLight, it.zMod, it.zVig, it.zPeak) }
            else stored[d.toString()]?.let { intArrayOf(it.zLight, it.zMod, it.zVig, it.zPeak) }
        }
        return HeartTrendsUi(days30, days30.map { rhr[it.toString()] }, days7, zones7, shifts(ctx, z, today, rhr))
    }

    /** Completed days of the last four weeks, split by whether the work calendar has a shift on them. */
    private fun shifts(ctx: Context, z: ZoneId, today: LocalDate, rhr: Map<String, Double?>): ShiftCompare? = try {
        val db = LocalStore.get(ctx).db
        val on = ArrayList<LocalDate>(); val off = ArrayList<LocalDate>()
        for (i in 28 downTo 1) {
            val d = today.minusDays(i.toLong())
            if (WorkIntensity.forDay(ctx, d).isNotEmpty()) on.add(d) else off.add(d)
        }
        fun dayAvg(d: LocalDate): Double? {
            val (lo, hi) = LocalApi.bounds(d, z)
            return db.rawQuery("SELECT SUM(mean*n), SUM(n) FROM hr_30s WHERE t30>=? AND t30<?", arrayOf(lo.toString(), hi.toString())).use {
                if (it.moveToFirst() && !it.isNull(1) && it.getLong(1) > 0) it.getDouble(0) / it.getLong(1) else null
            }
        }
        if (on.size < 2) null else ShiftCompare(
            on.size, off.size,
            HeartExtras.meanOf(on.map(::dayAvg), 2), HeartExtras.meanOf(off.map(::dayAvg), 2),
            HeartExtras.meanOf(on.map { rhr[it.plusDays(1).toString()] }, 2), HeartExtras.meanOf(off.map { rhr[it.plusDays(1).toString()] }, 2),
        )
    } catch (e: Exception) { AppLog.d("HeartTrends shifts: ${e.message}"); null }
}
