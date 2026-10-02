package com.fitair.app

import com.fitair.app.backup.BackupGuard
import com.fitair.app.backup.BackupGuard.Decision
import org.junit.Assert.*
import org.junit.Test

class BackupGuardTest {
    @Test fun table() = listOf(
        // firstDone, remoteExists, confirmed -> decision
        Triple(false, true, false) to Decision.NeedsConfirm,   // new device, backup exists: ask
        Triple(false, true, true) to Decision.Upload,          // user said replace
        Triple(false, false, false) to Decision.Upload,        // nothing to lose
        Triple(true, true, false) to Decision.Upload,          // daily backups stay silent
        Triple(true, false, false) to Decision.Upload,
    ).forEach { (i, e) -> assertEquals("$i", e, BackupGuard.decide(i.first, i.second, i.third)) }

    @Test fun confirmText() =
        assertEquals("A backup from 12 Sep 2026, 41.3 MB already exists. Replace it with this device's data? Restore first if this is a new device.",
            BackupGuard.confirmText("12 Sep 2026", 43_300_000L))

    @Test fun restoreNoticeListsPerTableCounts() {
        val t = BackupGuard.restoreNotice(2, linkedMapOf("hr_30s" to 98210L, "sleep" to 412L, "daily_metrics" to 40L, "pref" to 0L, "ai_call" to 3L))
        assertEquals("Restored 2 files: sleep 412 · hr_30s 98,210 · daily_metrics 40 · ai_call 3", t)
        assertEquals("Restored 1 file (no rows)", BackupGuard.restoreNotice(1, emptyMap()))
    }

    @Test fun sizes() {
        assertEquals("512 B", BackupGuard.sizeText(512)); assertEquals("2 KB", BackupGuard.sizeText(2048)); assertEquals("1.0 MB", BackupGuard.sizeText(1_048_576))
    }
}
