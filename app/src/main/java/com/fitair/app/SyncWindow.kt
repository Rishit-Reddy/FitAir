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

    /** Sessions (sleep, exercise) can be extended or replaced after they were first stored, so they are always re-read for the last [lookbackH] hours. */
    fun sessionFrom(wm: Long?, now: Instant, defaultDays: Long, overlap: Duration, lookbackH: Long = 48): Instant =
        minOf(from(wm, now, defaultDays, overlap), now.minus(Duration.ofHours(lookbackH)))
}

/** Stored sessions that Health Connect no longer returns for the window (the record was replaced), so they can be removed. */
object SessionPrune {
    /** [local] = (start, origin) of stored rows; [seen] = what Health Connect returned for [fromMs, now]. Only rows starting inside the window count. */
    fun orphans(local: List<Pair<Long, String>>, seen: Set<Pair<Long, String>>, fromMs: Long): List<Pair<Long, String>> =
        if (seen.isEmpty()) emptyList() else local.filter { it.first >= fromMs && it !in seen }
}
