package com.fitair.app.data.dao

import android.content.ContentValues
import android.content.Context
import com.fitair.app.AppLog
import com.fitair.app.LocalStore

/** One row of table ai_call (doc 3.2). Telemetry only: never prompt text or health values. */
data class AiCallRow(
    val ts: Long, val task: String /* chat|chat_long|plan|review */, val provider: String?, val model: String?,
    val rounds: Int, val inTok: Int, val outTok: Int, val thoughtTok: Int, val cachedTok: Int,
    val latencyMs: Long, val ok: Boolean, val finish: String?, val error: String?,
)

object AiCallDao {
    private val SECRET = Regex("(AIza[0-9A-Za-z_\\-]{10,}|sk-[0-9A-Za-z_\\-]{8,}|Bearer\\s+[0-9A-Za-z._\\-]{8,})")

    /** Best-effort: telemetry must never break the coach. */
    fun insert(ctx: Context, r: AiCallRow) {
        try {
            val v = ContentValues()
            v.put("ts", r.ts); v.put("task", r.task); v.put("provider", r.provider); v.put("model", r.model)
            v.put("rounds", r.rounds); v.put("in_tok", r.inTok); v.put("out_tok", r.outTok); v.put("thought_tok", r.thoughtTok)
            v.put("cached_tok", r.cachedTok); v.put("latency_ms", r.latencyMs); v.put("ok", if (r.ok) 1 else 0)
            v.put("finish", r.finish); v.put("error", r.error?.replace(SECRET, "***")?.take(200))
            LocalStore.get(ctx).db.insert("ai_call", null, v)
        } catch (e: Exception) {
            AppLog.d("ai_call insert failed: ${e.message}")
        }
    }

    /** Most recent rows, newest first (for Diagnostics). */
    fun recent(ctx: Context, n: Int = 20): List<AiCallRow> = try {
        val out = ArrayList<AiCallRow>()
        LocalStore.get(ctx).db.rawQuery(
            "SELECT ts,task,provider,model,rounds,in_tok,out_tok,thought_tok,cached_tok,latency_ms,ok,finish,error FROM ai_call ORDER BY id DESC LIMIT ?",
            arrayOf(n.toString())
        ).use { c ->
            while (c.moveToNext()) out.add(AiCallRow(c.getLong(0), c.getString(1), c.getString(2), c.getString(3), c.getInt(4), c.getInt(5),
                c.getInt(6), c.getInt(7), c.getInt(8), c.getLong(9), c.getInt(10) == 1, c.getString(11), c.getString(12)))
        }
        out
    } catch (e: Exception) { AppLog.d("ai_call read failed: ${e.message}"); emptyList() }
}
