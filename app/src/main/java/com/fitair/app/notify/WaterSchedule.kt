package com.fitair.app.notify

import com.fitair.app.data.dao.Pace
import java.time.LocalDate
import java.time.ZoneId

/**
 * Pure water-reminder rules (docs/PLAN_081.md 3.13). No android.* imports; unit-tested.
 * Times are epoch ms. The band cannot see drinking, so everything here is based on what the user logged.
 */
object WaterSchedule {
    const val MIN = 60_000L
    const val DEFAULT_INTERVAL = 90
    const val DEFAULT_GOAL_ML = 2500
    const val BEHIND_ML = 500
    const val BEHIND_INTERVAL = 60
    const val ACTIVE_BONUS_ML = 500
    const val ACTIVE_MIN = 30

    fun clampInterval(m: Int) = m.coerceIn(45, 180)
    fun clampGoal(ml: Int) = ml.coerceIn(1500, 4000)

    /** Goal for the day: +0.5 L when vigorous + peak minutes reach 30. */
    fun goalFor(baseMl: Int, vigorousPeakMin: Int): Int = clampGoal(baseMl) + if (vigorousPeakMin >= ACTIVE_MIN) ACTIVE_BONUS_ML else 0

    class Window(val startMs: Long, val endMs: Long)

    /**
     * Reminder window of [date]: wake (or usual wake when sleep is not synced) + 30 min until usual bedtime - 60 min.
     * [usualBedMin] is minutes after midnight; a bedtime before noon (e.g. 00:30) belongs to the next night. Never empty (>= 1 h).
     */
    fun window(date: LocalDate, zone: ZoneId, wakeMs: Long?, usualWakeMin: Int, usualBedMin: Int): Window {
        val day0 = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val wake = if (wakeMs != null && wakeMs in day0 until dayEnd) wakeMs else day0 + usualWakeMin * MIN
        val bedMin = if (usualBedMin < 12 * 60) usualBedMin + 1440 else usualBedMin
        val start = wake + 30 * MIN
        val end = maxOf(day0 + bedMin * MIN - 60 * MIN, start + 60 * MIN)
        return Window(start, end)
    }

    /** Goal reached / behind pace by >= 500 ml / on pace (pace = goal x elapsed share of the window). */
    fun pace(ml: Int, goalMl: Int, nowMs: Long, w: Window): Pace {
        if (ml >= goalMl) return Pace.Reached
        val share = ((nowMs - w.startMs).toDouble() / (w.endMs - w.startMs).coerceAtLeast(1L)).coerceIn(0.0, 1.0)
        return if (goalMl * share - ml >= BEHIND_ML) Pace.Behind else Pace.OnPace
    }

    /**
     * When the next reminder should fire, or null when none is due today (caller then asks for [firstOfDay] tomorrow).
     * A logged drink restarts the clock; a reminder also counts as the last event; behind pace shortens the interval to 60 min.
     */
    fun next(
        nowMs: Long, w: Window, lastDrinkMs: Long?, lastReminderMs: Long?, ml: Int, goalMl: Int, intervalMin: Int,
    ): Long? {
        if (ml >= goalMl) return null
        val behind = pace(ml, goalMl, nowMs, w) == Pace.Behind
        val iv = (if (behind) minOf(BEHIND_INTERVAL, intervalMin) else clampInterval(intervalMin)) * MIN
        val last = listOfNotNull(lastDrinkMs, lastReminderMs).filter { it >= w.startMs - 30 * MIN }.maxOrNull()
        var t = if (last != null) last + iv else if (nowMs <= w.startMs) w.startMs else nowMs + iv
        if (t < nowMs) t = nowMs + MIN  // missed while the phone was off or the alarm was killed: soon, once
        if (t < w.startMs) t = w.startMs
        return if (t >= w.endMs) null else t
    }

    /** First reminder of [date]: window start. */
    fun firstOfDay(date: LocalDate, zone: ZoneId, wakeMs: Long?, usualWakeMin: Int, usualBedMin: Int): Long =
        window(date, zone, wakeMs, usualWakeMin, usualBedMin).startMs

    /** Notification body. [unanswered] reminders in a row without a logged drink; [sinceClock] is "11:00" for that case. */
    fun text(ml: Int, goalMl: Int, unanswered: Int, sinceClock: String?): String {
        val l = String.format(java.util.Locale.US, "%.1f of %.1f L so far", ml / 1000.0, goalMl / 1000.0)
        return if (unanswered >= 2 && sinceClock != null) "Nothing logged since $sinceClock. $l" else l
    }

    /** True when [ml] drunk by a quiet-hours check: never inside sleep, i.e. outside the window. */
    fun insideWindow(t: Long, w: Window) = t >= w.startMs && t < w.endMs
}
