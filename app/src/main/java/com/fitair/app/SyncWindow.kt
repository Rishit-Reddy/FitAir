package com.fitair.app

import java.time.Duration
import java.time.Instant

/** Where a Health Connect read starts. Pure so it can be tested. */
object SyncWindow {
    /**
     * [wm] = newest stored time of the type (null = nothing stored yet). Reads start [overlap] before it, but never after
     * `now - overlap`: Health Connect can hold records that end in the future (calorie records cover the current 15 minutes), and a read
     * whose start is after its end throws "end time needs be after start time", which used to block every type behind it.
     */
    fun from(wm: Long?, now: Instant, defaultDays: Long, overlap: Duration): Instant {
        val base = if (wm == null) now.minus(Duration.ofDays(defaultDays)) else Instant.ofEpochMilli(wm).minus(overlap)
        return minOf(base, now.minus(overlap))
    }
}
