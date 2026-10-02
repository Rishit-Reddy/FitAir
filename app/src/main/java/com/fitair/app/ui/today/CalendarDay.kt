package com.fitair.app.ui.today

import com.fitair.app.integrations.calendar.CalEvent
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Which day the calendar panel opens on. Today from 00:00; from 21:00 it moves to tomorrow only when nothing timed is left today.
 * (The Evening card mode starts earlier and also covers 00:00-04:00, so it must not decide this.)
 */
object CalendarDay {
    const val SWITCH_HOUR = 21

    fun showTomorrow(now: LocalDateTime, todayTimed: List<CalEvent>, z: ZoneId = ZoneId.systemDefault()): Boolean {
        if (now.hour < SWITCH_HOUR) return false
        val nowI: Instant = now.atZone(z).toInstant()
        return todayTimed.none { !it.allDay && it.end > nowI }
    }
}
