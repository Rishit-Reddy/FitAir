package com.fitair.app.ui.agenda

import com.fitair.app.integrations.calendar.CalEvent
import java.time.Instant

/** One timed event placed in a column; [cols] = how many columns its overlap group needs. */
class Placed(val e: CalEvent, val col: Int, val cols: Int)

/** Pure: overlapping events sit side by side, like a day view in a calendar app. */
object TimelineLayout {
    /** Events shorter than this are drawn this tall so they stay readable and tappable. */
    const val MIN_MINUTES = 30

    fun place(events: List<CalEvent>): List<Placed> {
        val sorted = events.filter { !it.allDay }.sortedWith(compareBy({ it.begin }, { it.end }))
        val out = ArrayList<Placed>()
        var group = ArrayList<CalEvent>()
        var groupEnd = Instant.MIN
        fun flush() {
            if (group.isEmpty()) return
            val colEnds = ArrayList<Instant>()
            val cols = IntArray(group.size)
            group.forEachIndexed { i, e ->
                val end = drawnEnd(e)
                val c = colEnds.indexOfFirst { it <= e.begin }
                if (c >= 0) { colEnds[c] = end; cols[i] = c } else { colEnds.add(end); cols[i] = colEnds.lastIndex }
            }
            group.forEachIndexed { i, e -> out.add(Placed(e, cols[i], colEnds.size)) }
            group = ArrayList()
        }
        for (e in sorted) {
            if (group.isNotEmpty() && e.begin >= groupEnd) { flush(); groupEnd = Instant.MIN }
            group.add(e)
            groupEnd = maxOf(groupEnd, drawnEnd(e))
        }
        flush()
        return out
    }

    private fun drawnEnd(e: CalEvent): Instant = maxOf(e.end, e.begin.plusSeconds(MIN_MINUTES * 60L))
}
