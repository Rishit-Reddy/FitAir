package com.fitair.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject

/** Local SQLite copy of the health data plus the app tables (schema v6, see docs/ARCHITECTURE.md 3.2 and docs/PLAN_081.md 4.1). */
class LocalStore private constructor(private val ctx: Context) :
    SQLiteOpenHelper(ctx.applicationContext, FILE, null, VERSION) {

    val db: SQLiteDatabase get() = writableDatabase

    companion object {
        const val FILE = "fitair.db"
        const val VERSION = 6
        /** Tables added in schema v3 (not part of the sync/backup health tables in [TABLES]). */
        val APP_TABLES = listOf("cal_event", "task", "checkin", "plan_item", "pref", "ai_call", "chat_msg",
            "load_day", "exercise_flag", "weight", "chat_session", "chat_turn", "water", "day_summary", "day_brief")
        val TABLES = listOf(
            "heart_rate", "steps", "distance", "total_calories", "resting_hr",
            "hrv", "respiratory_rate", "sleep", "sleep_stage", "exercise",
            "hr_30s", "daily_metrics",
        )
        private const val WIN = 5 * 60_000L
        private const val MIG_AGG = "hr30_built"
        private const val MIG_DONE = "hr30_done"
        val DAILY_COLS = listOf(
            "date", "tz", "computed_ms", "readiness", "readiness_json", "sleep_score", "sleep_json",
            "load_trimp", "acute_load", "chronic_load", "acwr", "rhr", "hrv", "sleep_min", "steps", "insights_json",
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
        createV2(db)
        createV3(db)
        createV4(db)
        createV5(db)
        createV6(db)
        // fresh install: nothing to migrate
        db.execSQL("INSERT OR REPLACE INTO meta(k,v) VALUES('$MIG_DONE','1')")
    }

    private fun createV2(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS meta(k TEXT PRIMARY KEY, v TEXT)")
        db.execSQL("CREATE TABLE IF NOT EXISTS hr_30s(t30 INTEGER NOT NULL, mean REAL NOT NULL, min INTEGER NOT NULL, max INTEGER NOT NULL, n INTEGER NOT NULL, origin TEXT NOT NULL, PRIMARY KEY(t30,origin))")
        db.execSQL("CREATE TABLE IF NOT EXISTS daily_metrics(date TEXT PRIMARY KEY, tz TEXT, computed_ms INTEGER, readiness REAL, readiness_json TEXT, sleep_score REAL, sleep_json TEXT, load_trimp REAL, acute_load REAL, chronic_load REAL, acwr REAL, rhr REAL, hrv REAL, sleep_min INTEGER, steps INTEGER, insights_json TEXT)")
    }

    /**
     * Tables for the planned features (calendar cache, tasks, check-in, plan log, prefs, AI telemetry, chat).
     * Created now so later phases need no migration. All IF NOT EXISTS; upgrades only ever add.
     */
    private fun createV3(db: SQLiteDatabase) {
        val s = listOf(
            "CREATE TABLE IF NOT EXISTS cal_event(instance_id INTEGER PRIMARY KEY, event_id INTEGER, cal_id INTEGER, title TEXT, " +
                "begin_ms INTEGER NOT NULL, end_ms INTEGER NOT NULL, all_day INTEGER NOT NULL, busy INTEGER NOT NULL, fetched_ms INTEGER NOT NULL)",
            "CREATE INDEX IF NOT EXISTS idx_cal_begin ON cal_event(begin_ms)",
            "CREATE TABLE IF NOT EXISTS task(id TEXT PRIMARY KEY, title TEXT NOT NULL, notes TEXT, due_date TEXT, est_min INTEGER, " +
                "status TEXT NOT NULL DEFAULT 'open', created_ms INTEGER NOT NULL, done_ms INTEGER, source TEXT NOT NULL DEFAULT 'local', updated_ms INTEGER NOT NULL)",
            "CREATE TABLE IF NOT EXISTS checkin(date TEXT PRIMARY KEY, energy INTEGER, soreness INTEGER, mood INTEGER, stress INTEGER, " +
                "note TEXT, created_ms INTEGER NOT NULL)",
            "CREATE TABLE IF NOT EXISTS plan_item(id TEXT PRIMARY KEY, date TEXT NOT NULL, kind TEXT NOT NULL, start_ms INTEGER, end_ms INTEGER, " +
                "title TEXT NOT NULL, reason TEXT NOT NULL, intensity TEXT, source TEXT NOT NULL, inputs_json TEXT NOT NULL, status TEXT NOT NULL, " +
                "dismiss_reason TEXT, created_ms INTEGER NOT NULL, decided_ms INTEGER)",
            "CREATE INDEX IF NOT EXISTS idx_plan_date ON plan_item(date)",
            "CREATE TABLE IF NOT EXISTS pref(k TEXT PRIMARY KEY, v TEXT NOT NULL)",
            "CREATE TABLE IF NOT EXISTS ai_call(id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER NOT NULL, task TEXT NOT NULL, provider TEXT, model TEXT, " +
                "rounds INTEGER, in_tok INTEGER, out_tok INTEGER, thought_tok INTEGER, cached_tok INTEGER, latency_ms INTEGER, ok INTEGER, finish TEXT, error TEXT)",
            "CREATE TABLE IF NOT EXISTS chat_msg(id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER, role TEXT, text TEXT, json TEXT)",
        )
        s.forEach { db.execSQL(it) }
    }

    /** Schema v4 (docs/PLAN_081.md 4.1): whole-day load, session flags, weight, chat sessions, water, day summary. Additive only. */
    private fun createV4(db: SQLiteDatabase) {
        val s = listOf(
            "CREATE TABLE IF NOT EXISTS load_day(date TEXT PRIMARY KEY, cardio REAL, z_light INTEGER, z_mod INTEGER, z_vig INTEGER, z_peak INTEGER, " +
                "hourly_json TEXT, coverage REAL, hr_max REAL, hr_rest REAL, acute REAL, chronic REAL, ratio REAL, computed_ms INTEGER)",
            "CREATE TABLE IF NOT EXISTS exercise_flag(start_ms INTEGER NOT NULL, origin TEXT NOT NULL, end_ms INTEGER NOT NULL, " +
                "verdict TEXT NOT NULL, auto INTEGER NOT NULL, set_ms INTEGER NOT NULL, PRIMARY KEY(start_ms, origin))",
            "CREATE TABLE IF NOT EXISTS weight(t INTEGER NOT NULL, kg REAL NOT NULL, fat_pct REAL, origin TEXT NOT NULL, PRIMARY KEY(t, origin))",
            "CREATE TABLE IF NOT EXISTS chat_session(id TEXT PRIMARY KEY, title TEXT, created_ms INTEGER NOT NULL, updated_ms INTEGER NOT NULL)",
            "CREATE TABLE IF NOT EXISTS chat_turn(id INTEGER PRIMARY KEY AUTOINCREMENT, session_id TEXT NOT NULL, ts INTEGER NOT NULL, " +
                "role TEXT NOT NULL, text TEXT, answer_json TEXT, trace TEXT, long INTEGER, truncated INTEGER)",
            "CREATE INDEX IF NOT EXISTS idx_chat_turn_session ON chat_turn(session_id, id)",
            "CREATE TABLE IF NOT EXISTS water(t INTEGER NOT NULL, ml REAL NOT NULL, origin TEXT NOT NULL, hc_id TEXT, PRIMARY KEY(t, origin))",
            "CREATE TABLE IF NOT EXISTS day_summary(date TEXT PRIMARY KEY, facts_json TEXT NOT NULL, text TEXT NOT NULL, source TEXT NOT NULL, " +
                "model TEXT, created_ms INTEGER NOT NULL)",
        )
        s.forEach { db.execSQL(it) }
    }

    /** Schema v5 (docs/PLAN_090 6.2): the Today summary cache, one row per (date, part of day). Additive only. */
    private fun createV5(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS day_brief(date TEXT NOT NULL, part TEXT NOT NULL, facts_hash TEXT NOT NULL, facts_json TEXT, " +
            "bullets_json TEXT NOT NULL, source TEXT NOT NULL, model TEXT, created_ms INTEGER NOT NULL, PRIMARY KEY(date, part))")
    }

    /**
     * Schema v6: subscribed calendar (ICS) feeds. The feed URL is a secret and lives only in SecretStore ('feed_url_<id>'),
     * never in these tables. Not part of backups or the debug bundle. Additive only.
     */
    private fun createV6(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS cal_feed(id INTEGER PRIMARY KEY AUTOINCREMENT, label TEXT NOT NULL, color INTEGER NOT NULL, " +
            "work INTEGER NOT NULL DEFAULT 0, share_titles INTEGER NOT NULL DEFAULT 0, etag TEXT, last_modified TEXT, last_ok_ms INTEGER, " +
            "last_error TEXT, event_count INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE TABLE IF NOT EXISTS cal_feed_event(feed_id INTEGER NOT NULL, uid TEXT NOT NULL, rec_key TEXT NOT NULL, " +
            "begin_ms INTEGER NOT NULL, end_ms INTEGER NOT NULL, all_day INTEGER NOT NULL, title TEXT, location TEXT, busy INTEGER NOT NULL, " +
            "PRIMARY KEY(feed_id, uid, rec_key))")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_cal_feed_event_begin ON cal_feed_event(begin_ms)")
    }

    /** Cheap schema-only upgrade; the heavy heart-rate rebuild runs later in [migrateHeartRate] (IO thread). */
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createV2(db)
        if (oldVersion < 3) createV3(db)
        if (oldVersion < 4) createV4(db)
        if (oldVersion < 5) createV5(db)
        if (oldVersion < 6) createV6(db)
        // never drop anything here
    }

    /** Value stored under [k] in the meta table, or null. */
    fun metaGet(k: String): String? =
        db.rawQuery("SELECT v FROM meta WHERE k=?", arrayOf(k)).use { if (it.moveToFirst()) it.getString(0) else null }

    fun metaSet(k: String, v: String) = db.execSQL("INSERT OR REPLACE INTO meta(k,v) VALUES(?,?)", arrayOf(k, v))

    private fun meta(k: String): Boolean =
        db.rawQuery("SELECT 1 FROM meta WHERE k=?", arrayOf(k)).use { it.moveToFirst() }
    private fun setMeta(k: String) = db.execSQL("INSERT OR REPLACE INTO meta(k,v) VALUES(?,'1')", arrayOf(k))

    /**
     * One-time, resumable tiering migration; call from an IO thread before heart_rate sync.
     * Step 1 builds hr_30s from raw rows (idempotent), step 2 deletes raw rows outside exercise
     * windows, step 3 VACUUMs. Each step sets a flag when done, so an interruption just repeats the step.
     */
    @Synchronized
    fun migrateHeartRate() {
        if (meta(MIG_DONE)) return
        val d = db
        val t0 = System.currentTimeMillis()
        if (!meta(MIG_AGG)) {
            AppLog.d("migrate: building hr_30s from raw heart_rate")
            d.execSQL("INSERT OR REPLACE INTO hr_30s(t30,mean,min,max,n,origin) " +
                "SELECT t-(t%30000), AVG(bpm), MIN(bpm), MAX(bpm), COUNT(*), origin FROM heart_rate GROUP BY t-(t%30000), origin")
            setMeta(MIG_AGG)
            AppLog.d("migrate: hr_30s has ${count("hr_30s")} rows (${System.currentTimeMillis() - t0} ms)")
        }
        val before = count("heart_rate")
        AppLog.d("migrate: deleting raw heart_rate outside exercise windows ($before rows)")
        d.execSQL("DELETE FROM heart_rate WHERE NOT EXISTS (SELECT 1 FROM exercise e WHERE heart_rate.t>=e.start_ms-$WIN AND heart_rate.t<=e.end_ms+$WIN)")
        AppLog.d("migrate: raw heart_rate ${before} -> ${count("heart_rate")} rows")
        try {
            AppLog.d("migrate: VACUUM")
            d.execSQL("VACUUM")
        } catch (e: Exception) { AppLog.e("migrate: vacuum failed (ignored)", e) }
        setMeta(MIG_DONE)
        AppLog.d("migrate: done in ${System.currentTimeMillis() - t0} ms")
    }

    private fun count(t: String): Long =
        db.rawQuery("SELECT COUNT(*) FROM $t", null).use { if (it.moveToFirst()) it.getLong(0) else 0L }

    /** Exercise windows [start-5min, end+5min] intersecting [from, to]. */
    fun exerciseWindows(from: Long, to: Long): List<LongArray> {
        val out = ArrayList<LongArray>()
        db.rawQuery("SELECT start_ms,end_ms FROM exercise WHERE end_ms+$WIN>=? AND start_ms-$WIN<=? ORDER BY start_ms",
            arrayOf(from.toString(), to.toString())).use { c ->
            while (c.moveToNext()) out.add(longArrayOf(c.getLong(0) - WIN, c.getLong(1) + WIN))
        }
        return out
    }

    /**
     * Aggregates [samples] (JSON {t,bpm,origin}) with t in [from,to) (from must be 30 s aligned) into 30 s
     * buckets and REPLACES those buckets wholesale (callers read the whole range, so no merge is needed).
     * Samples inside an exercise window are also stored raw. Returns bucket count.
     */
    fun ingestHeartRate(samples: List<JSONObject>, from: Long, to: Long, windows: List<LongArray>) {
        class Agg(var sum: Long = 0, var min: Int = Int.MAX_VALUE, var max: Int = Int.MIN_VALUE, var n: Int = 0)
        val buckets = HashMap<Pair<Long, String>, Agg>()
        val raw = ArrayList<JSONObject>()
        for (r in samples) {
            val t = r.getLong("t")
            if (t < from || t >= to) continue
            val bpm = r.getInt("bpm")
            val origin = r.optString("origin", "")
            val a = buckets.getOrPut((t - t % 30000) to origin) { Agg() }
            a.sum += bpm; a.n++; if (bpm < a.min) a.min = bpm; if (bpm > a.max) a.max = bpm
            if (windows.any { t >= it[0] && t <= it[1] }) raw.add(r)
        }
        val d = db
        d.beginTransaction()
        try {
            for ((k, a) in buckets) d.replace("hr_30s", cv().p("t30", k.first).p("mean", a.sum.toDouble() / a.n)
                .p("min", a.min).p("max", a.max).p("n", a.n).p("origin", k.second))
            d.setTransactionSuccessful()
        } finally { d.endTransaction() }
        upsert("heart_rate", raw)
    }

    /** True if raw heart_rate rows exist in [from, to]. */
    fun hasRawHr(from: Long, to: Long): Boolean =
        db.rawQuery("SELECT 1 FROM heart_rate WHERE t>=? AND t<=? LIMIT 1", arrayOf(from.toString(), to.toString())).use { it.moveToFirst() }

    fun hasHr30(from: Long, to: Long): Boolean =
        db.rawQuery("SELECT 1 FROM hr_30s WHERE t30>=? AND t30<=? LIMIT 1", arrayOf((from - from % 30000).toString(), to.toString())).use { it.moveToFirst() }

    /** Exercise sessions (start_ms,end_ms) ending at or after [since]. */
    fun exerciseSince(since: Long): List<LongArray> {
        val out = ArrayList<LongArray>()
        db.rawQuery("SELECT DISTINCT start_ms,end_ms FROM exercise WHERE end_ms>=? ORDER BY start_ms", arrayOf(since.toString())).use { c ->
            while (c.moveToNext()) out.add(longArrayOf(c.getLong(0), c.getLong(1)))
        }
        return out
    }

    /** Insert or replace one daily_metrics row; keys are column names, JSON-valued columns are strings. */
    fun upsertDaily(row: JSONObject) {
        val v = ContentValues()
        for (k in DAILY_COLS) {
            if (!row.has(k) || row.isNull(k)) { v.putNull(k); continue }
            when (val x = row.get(k)) {
                is Long -> v.put(k, x); is Int -> v.put(k, x); is Double -> v.put(k, x)
                is Number -> v.put(k, x.toDouble()); is String -> v.put(k, x)
                else -> v.put(k, x.toString())
            }
        }
        if (v.getAsString("date") == null) throw IllegalArgumentException("daily row needs date")
        db.replace("daily_metrics", v)
    }

    /** Rows with fromDate <= date <= toDate (YYYY-MM-DD), ascending; null columns are omitted. */
    fun getDaily(fromDate: String, toDate: String): List<JSONObject> {
        val out = ArrayList<JSONObject>()
        db.rawQuery("SELECT ${DAILY_COLS.joinToString(",")} FROM daily_metrics WHERE date>=? AND date<=? ORDER BY date",
            arrayOf(fromDate, toDate)).use { c ->
            while (c.moveToNext()) {
                val o = JSONObject()
                for (i in DAILY_COLS.indices) when (c.getType(i)) {
                    android.database.Cursor.FIELD_TYPE_INTEGER -> o.put(DAILY_COLS[i], c.getLong(i))
                    android.database.Cursor.FIELD_TYPE_FLOAT -> o.put(DAILY_COLS[i], c.getDouble(i))
                    android.database.Cursor.FIELD_TYPE_STRING -> o.put(DAILY_COLS[i], c.getString(i))
                    else -> {}
                }
                out.add(o)
            }
        }
        return out
    }

    /** One transaction, INSERT OR REPLACE. [records] are JSON objects: t/bpm/origin, start/end/count, etc. (see HealthRepo.readForSync). */
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
                    "water" -> d.replace(type, cv().p("t", r.getLong("t")).p("ml", r.getDouble("ml")).p("origin", origin)
                        .also { v -> if (r.has("hc_id") && !r.isNull("hc_id")) v.put("hc_id", r.getString("hc_id")) })
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
        if (type == "heart_rate") {
            db.rawQuery("SELECT MAX(t30) FROM hr_30s", null).use { c ->
                return if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
            }
        }
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
