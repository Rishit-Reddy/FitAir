package com.fitair.app.notify

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId

/**
 * Pure rules for the hair routine (minoxidil morning and evening, ketoconazole shampoo on chosen weekdays). No Android types.
 * A dose is never made up and never doubled: a slot is done, skipped, or simply not logged; nothing here ever schedules a catch-up.
 */
object RoutineSchedule {
    const val AM = "am"
    const val PM = "pm"
    const val AFTER_WAKE_MIN = 30
    const val AM_EARLIEST_MIN = 5 * 60
    const val AM_LATEST_MIN = 12 * 60
    /** Unanswered morning reminder is cleared at this minute of the day. */
    const val AM_CUTOFF_MIN = 13 * 60
    const val DEFAULT_PM_MIN = 19 * 60
    const val PM_BEFORE_BED_CUTOFF_MIN = 30
    const val SNOOZE_MIN = 15
    const val MAX_SNOOZES = 2
    /** A morning dose taken after the cutoff pushes the evening one at least this far on. */
    const val MIN_GAP_MIN = 6 * 60
    /** Mon/Wed/Fri; bit 0 = Monday. */
    const val DEFAULT_KETO_MASK = 0b0010101
    const val MIN_MS = 60_000L

    fun ketoToday(day: DayOfWeek, mask: Int): Boolean = (mask shr (day.value - 1)) and 1 == 1

    /** Morning reminder minute of the day: 30 min after the wake time (detected if known, else usual), kept between 05:00 and 12:00. */
    fun amMinute(usualWakeMin: Int, detectedWakeMin: Int?): Int =
        ((detectedWakeMin ?: usualWakeMin) + AFTER_WAKE_MIN).coerceIn(AM_EARLIEST_MIN, AM_LATEST_MIN)

    /** Bed time as minutes on a day axis: a bed time before noon (e.g. 01:30) belongs to the next night, so it counts past 24:00. */
    private fun bedAxis(usualBedMin: Int) = if (usualBedMin < 12 * 60) usualBedMin + 1440 else usualBedMin

    fun pmCutoffMin(usualBedMin: Int): Int = bedAxis(usualBedMin) - PM_BEFORE_BED_CUTOFF_MIN

    /**
     * Evening reminder minute, or null when the dose is dropped for today: [pmMin] normally, at least [MIN_GAP_MIN] after a morning dose
     * taken at [amDoneMin] (minute of day), and not after the cutoff before bed.
     */
    fun pmMinute(pmMin: Int, usualBedMin: Int, amDoneMin: Int?): Int? {
        val t = if (amDoneMin != null && amDoneMin >= AM_CUTOFF_MIN) maxOf(pmMin, amDoneMin + MIN_GAP_MIN) else pmMin
        return t.takeIf { it < pmCutoffMin(usualBedMin) }
    }

    /** Where a slot stands today, as read from the log and the reminder bookkeeping. */
    class SlotState(val resolved: Boolean, val alerted: Boolean, val snoozeUntilMs: Long?)

    class Due(val slot: String, val atMs: Long, val cutoffMs: Long)

    private fun at(date: LocalDate, z: ZoneId, minute: Int): Long = date.atStartOfDay().plusMinutes(minute.toLong()).atZone(z).toInstant().toEpochMilli()

    /**
     * The next reminder to arm. Slots already done or skipped are passed over; a slot that was alerted and not snoozed waits silently
     * for its cutoff; an overdue slot fires one minute from [nowMs]; past the cutoff it is left alone (shown as "not logged").
     * Falls back to tomorrow morning.
     */
    fun next(
        nowMs: Long, z: ZoneId, usualWakeMin: Int, usualBedMin: Int, detectedWakeMin: Int?, pmMin: Int,
        am: SlotState, pm: SlotState, amDoneMin: Int?,
    ): Due {
        val today = java.time.Instant.ofEpochMilli(nowMs).atZone(z).toLocalDate()
        val amMin = amMinute(usualWakeMin, detectedWakeMin)
        val pmDay = pmMinute(pmMin, usualBedMin, amDoneMin)
        val cands = ArrayList<Due>()
        fun consider(slot: String, st: SlotState, minute: Int?, cutoffMin: Int) {
            if (minute == null || st.resolved) return
            val cutoff = at(today, z, cutoffMin)
            if (nowMs >= cutoff) return
            val t = when {
                st.snoozeUntilMs != null && st.snoozeUntilMs > nowMs -> st.snoozeUntilMs
                st.alerted -> return
                else -> maxOf(at(today, z, minute), nowMs + MIN_MS)
            }
            if (t < cutoff) cands.add(Due(slot, t, cutoff))
        }
        consider(AM, am, amMin, AM_CUTOFF_MIN)
        consider(PM, pm, pmDay, pmCutoffMin(usualBedMin))
        cands.minByOrNull { it.atMs }?.let { return it }
        val tomorrow = today.plusDays(1)
        val tm = amMinute(usualWakeMin, null)
        return Due(AM, at(tomorrow, z, tm), at(tomorrow, z, AM_CUTOFF_MIN))
    }

    /** "done", "skipped", or "not logged" for a day's slot once its cutoff has passed, null while it can still be answered. */
    fun shown(status: String?, cutoffPassed: Boolean): String? = status ?: if (cutoffPassed) "not logged" else null

    /** Wording for a reminder; shampoo comes first on its days, minoxidil goes on a dry scalp. */
    fun text(slot: String, keto: Boolean): String = when {
        slot == AM && keto -> "Shampoo first, leave 3–5 min, dry your scalp, then minoxidil"
        slot == AM -> "Minoxidil, morning"
        else -> "Minoxidil, evening"
    }
}
