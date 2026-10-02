package com.fitair.app.ui.today

import com.fitair.app.data.dao.WakeInfo
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/** What Today shows now (docs/PLAN_081.md 3.5 and 7.1). */
enum class Mode { Morning, Day, Evening }

private const val H = 60

/** Minutes after midnight; may exceed 1440 for a bedtime past midnight. */
private fun minuteOf(t: LocalDateTime) = t.hour * H + t.minute

/**
 * Pure mode rule.
 * - Evening: `now >= max(18:00, usualBed - 3 h)` or `now < 04:00`.
 * - Morning: `now < max(min(wake + 3 h, 12:00), wake + 1 h)`; with no wake yet, before 10:00 (and, if yesterday's wake is known,
 *   before that clock time + 3 h).
 * - Day: otherwise.
 */
fun todayMode(now: LocalDateTime, w: WakeInfo, zone: ZoneId = ZoneId.systemDefault()): Mode {
    val nowMin = minuteOf(now)
    var bed = w.usualBedMin
    if (bed < 12 * H) bed += 24 * H // 00:30 means 24:30
    val eveningStart = maxOf(18 * H, bed - 3 * H).coerceAtMost(24 * H - 1)
    if (nowMin < 4 * H || nowMin >= eveningStart) return Mode.Evening

    val wake = w.wakeMs?.let { LocalDateTime.ofInstant(Instant.ofEpochMilli(it), zone) }
    if (wake != null) {
        val noon = wake.toLocalDate().atTime(LocalTime.NOON)
        val plus3 = wake.plusHours(3)
        val end = maxOf(if (plus3.isBefore(noon)) plus3 else noon, wake.plusHours(1))
        return if (now.isBefore(end)) Mode.Morning else Mode.Day
    }
    // no main sleep synced yet
    if (nowMin >= 10 * H) return Mode.Day
    val last = w.lastKnownWakeMs?.let { LocalDateTime.ofInstant(Instant.ofEpochMilli(it), zone) }
    if (last != null && nowMin >= minuteOf(last) + 3 * H) return Mode.Day
    return Mode.Morning
}

/** Bed time for the wind-down line: usual wake minus need minus 15 min, 30 min earlier with a debt of an hour or more. */
class WindDown(val needMin: Int, val bedMin: Int, val short: Boolean)

fun windDown(usualWakeMin: Int, needMin: Double?, debtMin: Double?): WindDown? {
    val need = needMin?.takeIf { it in 240.0..720.0 }?.toInt() ?: return null
    val short = (debtMin ?: 0.0) >= 60.0
    val bed = usualWakeMin - need - 15 - if (short) 30 else 0
    return WindDown(need, ((bed % (24 * H)) + 24 * H) % (24 * H), short)
}
