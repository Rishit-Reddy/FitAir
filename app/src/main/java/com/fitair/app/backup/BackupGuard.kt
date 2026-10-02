package com.fitair.app.backup

import java.util.Locale

/** Pure decisions around Drive backups (no android.* imports; unit-tested). */
object BackupGuard {
    enum class Decision { Upload, NeedsConfirm }

    /**
     * A device that has never completed a backup or a restore ([firstDone] false) must not silently replace an existing remote backup:
     * its database may be incomplete (e.g. a new tablet). Without a remote file there is nothing to lose. Once the device has done a
     * first backup or restore, automatic backups stay silent.
     */
    fun decide(firstDone: Boolean, remoteExists: Boolean, confirmed: Boolean): Decision =
        if (firstDone || !remoteExists || confirmed) Decision.Upload else Decision.NeedsConfirm

    /** "A backup from 12 Sep 2026, 41.3 MB already exists. Replace it with this device's data? Restore first if this is a new device." */
    fun confirmText(dateText: String, sizeBytes: Long): String =
        "A backup from $dateText, ${sizeText(sizeBytes)} already exists. Replace it with this device's data? Restore first if this is a new device."

    fun sizeText(b: Long): String = when {
        b >= 1_048_576L -> String.format(Locale.US, "%.1f MB", b / 1_048_576.0)
        b >= 1024L -> String.format(Locale.US, "%d KB", b / 1024)
        else -> "$b B"
    }

    private val ORDER = listOf("sleep", "hr_30s", "steps", "daily_metrics", "load_day", "exercise", "hrv", "resting_hr", "water")

    /** "Restored 2 files: sleep 412 · hr_30s 98,210 · daily_metrics 40" (tables with rows only, known tables first, then by size). */
    fun restoreNotice(files: Int, counts: Map<String, Long>, maxTables: Int = 8): String {
        val nz = counts.filterValues { it > 0 }
        val known = ORDER.filter { it in nz }
        val rest = nz.keys.filter { it !in ORDER }.sortedByDescending { nz.getValue(it) }
        val all = known + rest
        val shown = all.take(maxTables).joinToString(" · ") { "$it ${String.format(Locale.US, "%,d", nz.getValue(it))}" }
        val more = if (all.size > maxTables) " · +${all.size - maxTables} more" else ""
        return "Restored $files file${if (files == 1) "" else "s"}" + if (shown.isEmpty()) " (no rows)" else ": $shown$more"
    }
}
