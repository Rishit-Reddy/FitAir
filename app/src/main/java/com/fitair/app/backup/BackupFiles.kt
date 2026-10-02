package com.fitair.app.backup

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.fitair.app.AppLog
import com.fitair.app.LocalStore
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Building per-year snapshot zips and merging snapshots back (no network, no UI). */
object BackupFiles {
    const val MAX_ZIP = 200L * 1024 * 1024
    private const val TARGET_PART = 170L * 1024 * 1024

    private class Col(val name: String, val isDate: Boolean)

    /** Time column per table; tables not listed (meta, pref, sqlite_*) are neither backed up nor restored. */
    private val TIME = mapOf(
        "heart_rate" to Col("t", false), "resting_hr" to Col("t", false), "hrv" to Col("t", false),
        "respiratory_rate" to Col("t", false), "hr_30s" to Col("t30", false),
        "steps" to Col("start_ms", false), "distance" to Col("start_ms", false), "total_calories" to Col("start_ms", false),
        "exercise" to Col("start_ms", false), "sleep" to Col("start_ms", false), "sleep_stage" to Col("start_ms", false),
        "daily_metrics" to Col("date", true), "checkin" to Col("date", true), "plan_item" to Col("date", true),
        "cal_event" to Col("begin_ms", false), "task" to Col("created_ms", false),
        "ai_call" to Col("ts", false), "chat_msg" to Col("ts", false),
        "load_day" to Col("date", true), "exercise_flag" to Col("start_ms", false), "weight" to Col("t", false),
        "chat_session" to Col("created_ms", false), "chat_turn" to Col("ts", false), "water" to Col("t", false),
        "day_summary" to Col("date", true),
    )

    fun tablesOf(db: SQLiteDatabase): List<String> {
        val out = ArrayList<String>()
        db.rawQuery("SELECT name FROM sqlite_master WHERE type='table'", null).use { while (it.moveToNext()) out.add(it.getString(0)) }
        return out.filter { it in TIME }
    }

    /** Years (local zone) that contain data in [snapshot], ascending. */
    fun yearsWithData(snapshot: File, zone: ZoneId): Set<Int> {
        val out = sortedSetOf<Int>()
        val db = SQLiteDatabase.openDatabase(snapshot.path, null, SQLiteDatabase.OPEN_READONLY)
        try {
            for (t in tablesOf(db)) {
                val c = TIME.getValue(t)
                for (agg in listOf("MIN", "MAX")) db.rawQuery("SELECT $agg(${c.name}) FROM $t", null).use { cur ->
                    if (!cur.moveToFirst() || cur.isNull(0)) return@use
                    val y = try {
                        if (c.isDate) cur.getString(0).substring(0, 4).toInt()
                        else Instant.ofEpochMilli(cur.getLong(0)).atZone(zone).year
                    } catch (e: Exception) { return@use }
                    if (y in 2000..2200) out.add(y)
                }
            }
        } finally { db.close() }
        return out
    }

    class Part(val index: Int, val zip: File, val rows: Long)

    /**
     * Builds the zip(s) for [year] from the full snapshot [full]: copy, delete everything outside the year (or outside this part's
     * day range), VACUUM, add backup.json. One part normally; more if a part would exceed [MAX_ZIP]. Returns parts with rows > 0.
     */
    fun buildYear(ctx: Context, full: File, year: Int, zone: ZoneId, work: File, onFrac: (Float) -> Unit): List<Part> {
        var n = 1
        while (true) {
            val parts = ArrayList<Part>()
            var tooBig = 0L
            for (i in 0 until n) {
                val start = LocalDate.of(year, 1, 1); val end = LocalDate.of(year + 1, 1, 1)
                val days = ChronoUnit.DAYS.between(start, end)
                val from = start.plusDays(days * i / n); val to = start.plusDays(days * (i + 1) / n)
                val zip = File(work, "fitair-backup-$year-${i + 1}.zip.tmp")
                val rows = buildPart(ctx, full, year, i + 1, n, from, to, zone, zip, work)
                if (rows > 0) parts.add(Part(i + 1, zip, rows)) else zip.delete()
                if (zip.exists() && zip.length() > MAX_ZIP) tooBig = maxOf(tooBig, zip.length())
                onFrac((i + 1f) / n)
            }
            if (tooBig == 0L || n >= 12) {
                if (tooBig > 0) AppLog.d("backup: year $year part still ${tooBig} B with $n parts")
                return parts
            }
            val total = parts.sumOf { it.zip.length() }
            parts.forEach { it.zip.delete() }
            n = maxOf(n + 1, ((total + TARGET_PART - 1) / TARGET_PART).toInt())
            AppLog.d("backup: year $year is ${total} B zipped, splitting into $n parts")
        }
    }

    private fun buildPart(ctx: Context, full: File, year: Int, part: Int, parts: Int, from: LocalDate, to: LocalDate,
                          zone: ZoneId, zip: File, work: File): Long {
        val tmp = File(work, "fitair-part.db")
        tmp.delete()
        full.copyTo(tmp, overwrite = true)
        val fromMs = from.atStartOfDay(zone).toInstant().toEpochMilli()
        val toMs = to.atStartOfDay(zone).toInstant().toEpochMilli()
        val counts = JSONObject()
        var rows = 0L
        var dbVersion = 0
        val db = SQLiteDatabase.openDatabase(tmp.path, null, SQLiteDatabase.OPEN_READWRITE)
        try {
            dbVersion = db.version
            val keep = tablesOf(db)
            val all = ArrayList<String>()
            db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name<>'android_metadata'", null)
                .use { while (it.moveToNext()) all.add(it.getString(0)) }
            for (t in all) if (t !in keep) db.execSQL("DELETE FROM $t") // meta, pref: device state, not data
            for (t in keep) {
                val c = TIME.getValue(t)
                if (c.isDate) db.execSQL("DELETE FROM $t WHERE ${c.name} IS NULL OR ${c.name} < ? OR ${c.name} >= ?", arrayOf<Any>(from.toString(), to.toString()))
                else db.execSQL("DELETE FROM $t WHERE ${c.name} IS NULL OR ${c.name} < ? OR ${c.name} >= ?", arrayOf<Any>(fromMs, toMs))
                val cnt = db.rawQuery("SELECT COUNT(*) FROM $t", null).use { if (it.moveToFirst()) it.getLong(0) else 0L }
                counts.put(t, cnt); rows += cnt
            }
            if (rows > 0) db.execSQL("VACUUM")
        } finally { db.close() }
        if (rows == 0L) { tmp.delete(); return 0 }
        val appVersion = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: ""
        val meta = JSONObject().put("version", 3).put("created_ms", System.currentTimeMillis()).put("app_version", appVersion)
            .put("db_version", dbVersion).put("year", year).put("part", part).put("parts", parts)
            .put("from", from.toString()).put("to_exclusive", to.toString()).put("zone", zone.id).put("row_counts", counts).toString()
        ZipOutputStream(zip.outputStream().buffered(64 * 1024)).use { z ->
            z.setLevel(Deflater.DEFAULT_COMPRESSION)
            z.putNextEntry(ZipEntry("fitair.db")); tmp.inputStream().use { it.copyTo(z, 64 * 1024) }; z.closeEntry()
            z.putNextEntry(ZipEntry("backup.json")); z.write(meta.toByteArray(Charsets.UTF_8)); z.closeEntry()
        }
        tmp.delete()
        return rows
    }

    /** Extracts fitair.db from a backup zip into [dst]. */
    fun extractDb(zip: File, dst: File) {
        ZipFile(zip).use { z ->
            val e = z.getEntry("fitair.db") ?: throw IllegalArgumentException("Not a FitAir backup (no fitair.db)")
            z.getInputStream(e).use { i -> dst.outputStream().buffered(64 * 1024).use { o -> i.copyTo(o, 64 * 1024) } }
        }
    }

    /**
     * Merges every known table of [snapshot] into the live database: ATTACH, then per table INSERT OR REPLACE for the columns both
     * sides have, all in one transaction. Returns the row count per table (rows read from the snapshot).
     */
    fun merge(ctx: Context, snapshot: File): Map<String, Long> {
        val db = LocalStore.get(ctx).db
        db.execSQL("ATTACH DATABASE ? AS snap", arrayOf<Any>(snapshot.absolutePath))
        val counts = LinkedHashMap<String, Long>()
        try {
            fun cols(schema: String, t: String): List<String> {
                val o = ArrayList<String>()
                db.rawQuery("PRAGMA $schema.table_info($t)", null).use { while (it.moveToNext()) o.add(it.getString(1)) }
                return o
            }
            val snapTables = ArrayList<String>()
            db.rawQuery("SELECT name FROM snap.sqlite_master WHERE type='table'", null).use { while (it.moveToNext()) snapTables.add(it.getString(0)) }
            val live = tablesOf(db)
            db.beginTransaction()
            try {
                for (t in snapTables) {
                    if (t !in TIME || t !in live) continue
                    val mainCols = cols("main", t)
                    val common = cols("snap", t).filter { it in mainCols }
                    if (common.isEmpty()) continue
                    val list = common.joinToString(",") { "\"$it\"" }
                    db.execSQL("INSERT OR REPLACE INTO main.$t($list) SELECT $list FROM snap.$t")
                    counts[t] = db.rawQuery("SELECT COUNT(*) FROM snap.$t", null).use { if (it.moveToFirst()) it.getLong(0) else 0L }
                }
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
        } finally {
            try { db.execSQL("DETACH DATABASE snap") } catch (e: Exception) { AppLog.e("restore: detach failed", e) }
        }
        return counts
    }
}
