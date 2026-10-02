package com.fitair.app.analytics

import android.content.Context
import com.fitair.app.AppLog
import com.fitair.app.LocalApi
import com.fitair.app.LocalStore
import com.fitair.app.data.dao.LoadDao
import com.fitair.app.integrations.calendar.ics.IcsFeeds
import java.time.LocalDate
import java.time.ZoneId

/**
 * Heart-rate intensity inside one work window (a shift from a work-flagged calendar feed).
 * Percent of heart-rate reserve and the zone-2+ minutes (reserve >= 40 %, "moderate" and above) use the same HRmax and resting baseline as [CardioLoad].
 * [avgHr], [avgPctHrr], [minutesZone2Plus] and [load] are null when [coverage] (share of the window with heart-rate data) is under 0.5.
 */
data class WorkWindowStats(
    val startMs: Long, val endMs: Long, val label: String, val hours: Double, val avgHr: Int?, val avgPctHrr: Int?,
    val minutesZone2Plus: Int, val load: Double?, val coverage: Double?,
)

object WorkIntensity {
    const val MIN_COVERAGE = 0.5
    const val ZONE2_HRR = 0.40
    private const val BUCKET = CardioLoad.BUCKET_MS

    /** A work window before statistics. */
    class Win(val startMs: Long, val endMs: Long, val label: String)

    /** Sorts and merges overlapping or touching windows; labels of merged windows are joined (distinct, in order). */
    fun merge(wins: List<Win>): List<Win> {
        val out = ArrayList<Win>()
        for (w in wins.filter { it.endMs > it.startMs }.sortedBy { it.startMs }) {
            val last = out.lastOrNull()
            if (last != null && w.startMs <= last.endMs) {
                val labels = (last.label.split(" + ") + w.label.split(" + ")).filter { it.isNotEmpty() }.distinct().joinToString(" + ")
                out[out.size - 1] = Win(last.startMs, maxOf(last.endMs, w.endMs), labels)
            } else out.add(w)
        }
        return out
    }

    /** Pure: statistics of the (merged) [wins] from 30 s [buckets] (any order, duplicates by time ignored). */
    fun compute(wins: List<Win>, buckets: List<CardioLoad.Bucket>, rest: Double, hrMax: Double): List<WorkWindowStats> {
        val sorted = buckets.sortedBy { it.t30 }.distinctBy { it.t30 }
        return merge(wins).map { w ->
            val inside = sorted.filter { it.t30 >= w.startMs - w.startMs % BUCKET && it.t30 + BUCKET > w.startMs && it.t30 < w.endMs }
            val expected = maxOf(1.0, (w.endMs - w.startMs).toDouble() / BUCKET)
            val coverage = (inside.size / expected).coerceIn(0.0, 1.0)
            val hours = Math.round((w.endMs - w.startMs) / 3_600_000.0 * 100) / 100.0
            if (coverage < MIN_COVERAGE || inside.isEmpty()) {
                WorkWindowStats(w.startMs, w.endMs, w.label, hours, null, null, 0, null, round2(coverage))
            } else {
                val mean = inside.sumOf { it.mean } / inside.size
                val counting = CardioLoad.counting(inside, rest, hrMax)
                var load = 0.0; var z2 = 0
                for (i in inside.indices) {
                    if (!counting[i]) continue
                    val h = CardioLoad.hrr(inside[i].mean, rest, hrMax)
                    load += CardioLoad.increment(h)
                    if (h >= ZONE2_HRR) z2++
                }
                WorkWindowStats(
                    w.startMs, w.endMs, w.label, hours, Math.round(mean).toInt(),
                    Math.round(CardioLoad.hrr(mean, rest, hrMax) * 100).toInt().coerceAtLeast(0),
                    Math.round(z2 * 0.5).toInt(), Math.round(load * 10) / 10.0, round2(coverage),
                )
            }
        }
    }

    private fun round2(x: Double) = Math.round(x * 100) / 100.0

    /** Work windows (timed events of work-flagged feeds that START on [date]) with their heart-rate statistics. Empty without a work feed. */
    fun forDay(ctx: Context, date: LocalDate): List<WorkWindowStats> = try {
        val zone = ZoneId.systemDefault()
        val (lo, hi) = LocalApi.bounds(date, zone)
        val feeds = IcsFeeds.listBlocking(ctx).filter { it.work }.associateBy { it.id }
        if (feeds.isEmpty()) emptyList() else {
            val wins = IcsFeeds.eventsBlocking(ctx, lo, hi, zone, null, workOnly = true)
                .filter { !it.allDay && it.begin.toEpochMilli() >= lo && it.begin.toEpochMilli() < hi }
                .map { e ->
                    val f = feeds[e.feedId]
                    Win(e.begin.toEpochMilli(), e.end.toEpochMilli(), if (f?.shareTitles == true) e.title else (f?.label ?: "Work"))
                }
            if (wins.isEmpty()) emptyList() else {
                val merged = merge(wins)
                val from = merged.first().startMs; val to = merged.last().endMs
                val buckets = ArrayList<CardioLoad.Bucket>()
                LocalStore.get(ctx).db.rawQuery(
                    "SELECT t30, sum(mean*n)/sum(n) FROM hr_30s WHERE t30>=? AND t30<? GROUP BY t30 ORDER BY t30",
                    arrayOf((from - from % BUCKET).toString(), to.toString()),
                ).use { while (it.moveToNext()) buckets.add(CardioLoad.Bucket(it.getLong(0), it.getDouble(1))) }
                compute(merged, buckets, LoadDao.restFor(ctx, date, zone), LoadDao.hrMax(ctx).value)
            }
        }
    } catch (e: Exception) { AppLog.d("work intensity failed: ${e.javaClass.simpleName}"); emptyList() }
}
