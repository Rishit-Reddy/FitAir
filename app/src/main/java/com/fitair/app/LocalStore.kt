package com.fitair.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject

/** Local SQLite copy of the health data. Record shapes follow server/API.md. */
class LocalStore private constructor(private val ctx: Context) :
    SQLiteOpenHelper(ctx.applicationContext, FILE, null, 1) {

    val db: SQLiteDatabase get() = writableDatabase

    companion object {
        const val FILE = "fitair.db"
        val TABLES = listOf(
            "heart_rate", "steps", "distance", "total_calories", "resting_hr",
            "hrv", "respiratory_rate", "sleep", "sleep_stage", "exercise",
        )

        @Volatile private var inst: LocalStore? = null
        fun get(ctx: Context): LocalStore = inst ?: synchronized(this) {
            inst ?: LocalStore(ctx.applicationContext).also { inst = it }
        }
    }

    override fun onConfigure(db: SQLiteDatabase) {
        db.enableWriteAheadLogging()
    }

    override fun onCreate(db: SQLiteDatabase) {
        val s = listOf(
            "CREATE TABLE heart_rate(t INTEGER NOT NULL, bpm INTEGER NOT NULL, origin TEXT NOT NULL, PRIMARY KEY(t,origin))",
            "CREATE TABLE steps(start_ms INTEGER NOT NULL, end_ms INTEGER NOT NULL, count INTEGER NOT NULL, origin TEXT NOT NULL, PRIMARY KEY(start_ms,end_ms,origin))",
            "CREATE TABLE distance(start_ms INTEGER NOT NULL, end_ms INTEGER NOT NULL, meters REAL NOT NULL, origin TEXT NOT NULL, PRIMARY KEY(start_ms,end_ms,origin))",
            "CREATE TABLE total_calories(start_ms INTEGER NOT NULL, end_ms INTEGER NOT NULL, kcal REAL NOT NULL, origin TEXT NOT NULL, PRIMARY KEY(start_ms,end_ms,origin))",
            "CREATE TABLE resting_hr(t INTEGER NOT NULL, bpm INTEGER NOT NULL, origin TEXT NOT NULL, PRIMARY KEY(t,origin))",
            "CREATE TABLE hrv(t INTEGER NOT NULL, rmssd REAL NOT NULL, origin TEXT NOT NULL, PRIMARY KEY(t,origin))",
            "CREATE TABLE respiratory_rate(t INTEGER NOT NULL, rate REAL NOT NULL, origin TEXT NOT NULL, PRIMARY KEY(t,origin))",
            "CREATE TABLE sleep(start_ms INTEGER NOT NULL, end_ms INTEGER NOT NULL, origin TEXT NOT NULL, PRIMARY KEY(start_ms,origin))",
            "CREATE TABLE sleep_stage(sleep_start_ms INTEGER NOT NULL, origin TEXT NOT NULL, start_ms INTEGER NOT NULL, end_ms INTEGER NOT NULL, stage INTEGER NOT NULL, PRIMARY KEY(sleep_start_ms,origin,start_ms))",
            "CREATE TABLE exercise(start_ms INTEGER NOT NULL, end_ms INTEGER NOT NULL, type INTEGER NOT NULL, title TEXT NOT NULL DEFAULT '', origin TEXT NOT NULL, PRIMARY KEY(start_ms,end_ms,origin))",
            "CREATE INDEX idx_steps_start ON steps(start_ms)",
            "CREATE INDEX idx_distance_start ON distance(start_ms)",
            "CREATE INDEX idx_calories_start ON total_calories(start_ms)",
            "CREATE INDEX idx_sleep_start ON sleep(start_ms)",
            "CREATE INDEX idx_sleep_stage_start ON sleep_stage(start_ms)",
            "CREATE INDEX idx_exercise_start ON exercise(start_ms)",
        )
        s.forEach { db.execSQL(it) }
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    /** One transaction, INSERT OR REPLACE. [records] use server/API.md JSON shapes. */
    fun upsert(type: String, records: List<JSONObject>) {
        if (records.isEmpty()) return
        val d = db
        d.beginTransaction()
        try {
            for (r in records) {
                val origin = r.optString("origin", "")
                when (type) {
                    "heart_rate", "resting_hr" -> d.replace(type, cv().p("t", r.getLong("t")).p("bpm", r.getInt("bpm")).p("origin", origin))
                    "hrv" -> d.replace(type, cv().p("t", r.getLong("t")).p("rmssd", r.getDouble("rmssd")).p("origin", origin))
                    "respiratory_rate" -> d.replace(type, cv().p("t", r.getLong("t")).p("rate", r.getDouble("rate")).p("origin", origin))
                    "steps" -> d.replace(type, span(r, origin).p("count", r.getLong("count")))
                    "distance" -> d.replace(type, span(r, origin).p("meters", r.getDouble("meters")))
                    "total_calories" -> d.replace(type, span(r, origin).p("kcal", r.getDouble("kcal")))
                    "exercise" -> d.replace(type, span(r, origin).p("type", r.getInt("type")).p("title", r.optString("title", "")))
                    "sleep" -> {
                        val start = r.getLong("start")
                        d.replace("sleep", span(r, origin))
                        d.delete("sleep_stage", "sleep_start_ms=? AND origin=?", arrayOf(start.toString(), origin))
                        val st = r.optJSONArray("stages")
                        if (st != null) for (i in 0 until st.length()) {
                            val s = st.getJSONObject(i)
                            d.replace("sleep_stage", cv().p("sleep_start_ms", start).p("origin", origin)
                                .p("start_ms", s.getLong("start")).p("end_ms", s.getLong("end")).p("stage", s.getInt("stage")))
                        }
                    }
                    else -> throw IllegalArgumentException("unknown type $type")
                }
            }
            d.setTransactionSuccessful()
        } finally {
            d.endTransaction()
        }
    }

    private fun cv() = ContentValues()
    private fun ContentValues.p(k: String, v: Any): ContentValues {
        when (v) {
            is Long -> put(k, v); is Int -> put(k, v); is Double -> put(k, v); is String -> put(k, v)
            else -> throw IllegalArgumentException("bad type for $k")
        }
        return this
    }
    private fun span(r: JSONObject, origin: String) =
        cv().p("start_ms", r.getLong("start")).p("end_ms", r.getLong("end")).p("origin", origin)
    private fun SQLiteDatabase.replace(table: String, v: ContentValues) {
        if (insertWithOnConflict(table, null, v, SQLiteDatabase.CONFLICT_REPLACE) == -1L)
            throw IllegalStateException("insert into $table failed")
    }

    /** Latest time stored for [type] (t, or end_ms for interval types); null if empty. */
    fun maxT(type: String): Long? {
        val col = when (type) {
            "heart_rate", "resting_hr", "hrv", "respiratory_rate" -> "t"
            "steps", "distance", "total_calories", "sleep", "exercise" -> "end_ms"
            else -> throw IllegalArgumentException("unknown type $type")
        }
        db.rawQuery("SELECT MAX($col) FROM $type", null).use { c ->
            return if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
        }
    }

    fun counts(): Map<String, Int> {
        val out = LinkedHashMap<String, Int>()
        for (t in TABLES) db.rawQuery("SELECT COUNT(*) FROM $t", null).use { c ->
            out[t] = if (c.moveToFirst()) c.getInt(0) else 0
        }
        return out
    }

    /** Approximate on-disk size (db + WAL) in bytes. */
    fun sizeBytes(): Long {
        val f = ctx.applicationContext.getDatabasePath(FILE)
        return f.length() + java.io.File(f.path + "-wal").length()
    }
}
