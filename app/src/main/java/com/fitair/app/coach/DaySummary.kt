package com.fitair.app.coach

import android.content.ContentValues
import android.content.Context
import androidx.health.connect.client.records.ExerciseSessionRecord
import com.fitair.app.AppLog
import com.fitair.app.LocalApi
import com.fitair.app.LocalStore
import com.fitair.app.SyncPrefs
import com.fitair.app.analytics.CardioLoad
import com.fitair.app.core.Format
import com.fitair.app.data.dao.AiCallDao
import com.fitair.app.data.dao.AiCallRow
import com.fitair.app.data.dao.LoadDao
import com.fitair.app.data.dao.WaterDao
import com.fitair.app.secure.SecretStore
import com.fitair.app.ui.copy.Copy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId

/**
 * What the evening Day summary is built from. All numbers come from the local DB. [zoneMin] = vigorous + peak minutes.
 * [hasData] false = nothing synced for the day ("Not enough data from today yet").
 */
data class DayFacts(
    val date: String, val steps: Long, val distanceM: Double, val cardio: Double, val zoneMin: Int,
    val waterMl: Int, val waterGoalMl: Int, val rhr: Double?, val hrAvg: Double?, val hrMax: Double?,
    val workouts: List<String>, val partial: Boolean, val hasData: Boolean = true,
)

/** [source] is "llm" or "template". */
class SummaryText(val text: String, val source: String, val createdMs: Long)

/**
 * Evening Day summary: facts from the DB, at most one cheap LLM call per evening (cached in day_summary), a validator that rejects
 * invented numbers, and the deterministic Copy.daySummary template as the fallback (offline, no key, error, timeout, mismatch).
 */
object DaySummary {
    private const val RETRY_TEMPLATE_AFTER_MS = 30 * 60_000L

    fun facts(ctx: Context, date: String): DayFacts = facts(ctx, LocalDate.parse(date))

    fun facts(ctx: Context, date: LocalDate): DayFacts {
        val z = ZoneId.systemDefault()
        val db = LocalStore.get(ctx).db
        val ser = try { LocalApi.series(db, z, date, date, LoadDao.hrMax(ctx).value)[date.toString()] } catch (e: Exception) { null }
        val live = try { LoadDao.live(ctx, date) } catch (e: Exception) { null }
        val (lo, hi) = LocalApi.bounds(date, z)
        val workouts = try {
            LocalApi.exercises(db, lo, hi).map { ex ->
                val type = ExerciseSessionRecord.EXERCISE_TYPE_INT_TO_STRING_MAP[ex.type]?.replace('_', ' ')?.lowercase()
                val name = ex.title.ifBlank { (type ?: "workout") }.replaceFirstChar { it.uppercase() }
                "$name ${Format.duration(Math.round((ex.end - ex.start) / 60000.0))}"
            }
        } catch (e: Exception) { emptyList() }
        val rhr = try {
            db.rawQuery("SELECT rhr FROM daily_metrics WHERE date=?", arrayOf(date.toString())).use { if (it.moveToFirst() && !it.isNull(0)) it.getDouble(0) else null }
        } catch (e: Exception) { null } ?: ser?.restingHr
        val steps = ser?.steps ?: 0L
        return DayFacts(
            date = date.toString(), steps = steps, distanceM = ser?.distance ?: 0.0,
            cardio = live?.cardio?.let { Math.round(it * 10) / 10.0 } ?: 0.0,
            zoneMin = (live?.zVig ?: 0) + (live?.zPeak ?: 0),
            waterMl = WaterDao.total(ctx, date, z), waterGoalMl = WaterDao.goalMl(ctx, date).first,
            rhr = rhr, hrAvg = ser?.hrMean, hrMax = ser?.hrMax, workouts = workouts,
            partial = live == null || CardioLoad.isPartial(live.coverage),
            hasData = steps > 0 || ser?.hrMean != null,
        )
    }

    private fun usual(ctx: Context, date: LocalDate): DaySummaryLogic.Usual {
        fun median(v: List<Double>): Double? = if (v.size < 7) null else v.sorted().let { if (it.size % 2 == 1) it[it.size / 2] else (it[it.size / 2 - 1] + it[it.size / 2]) / 2 }
        return try {
            val rows = LocalStore.get(ctx).getDaily(date.minusDays(28).toString(), date.minusDays(1).toString())
            val steps = median(rows.filter { it.has("steps") }.map { it.getDouble("steps") })
            val cardio = median(LoadDao.rows(ctx, date.minusDays(28), date.minusDays(1)).map { it.cardio })
            DaySummaryLogic.Usual(steps?.let { Math.round(it) }, cardio)
        } catch (e: Exception) { DaySummaryLogic.Usual(null, null) }
    }

    private fun cached(ctx: Context, date: LocalDate): SummaryText? = try {
        LocalStore.get(ctx).db.rawQuery("SELECT text, source, created_ms FROM day_summary WHERE date=?", arrayOf(date.toString())).use {
            if (it.moveToFirst()) SummaryText(it.getString(0), it.getString(1), it.getLong(2)) else null
        }
    } catch (e: Exception) { null }

    private fun store(ctx: Context, date: LocalDate, facts: JSONObject, t: SummaryText, model: String?) {
        try {
            val v = ContentValues()
            v.put("date", date.toString()); v.put("facts_json", facts.toString()); v.put("text", t.text)
            v.put("source", t.source); v.put("model", model); v.put("created_ms", t.createdMs)
            LocalStore.get(ctx).db.insertWithOnConflict("day_summary", null, v, android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE)
        } catch (e: Exception) { AppLog.d("day_summary store failed: ${e.message}") }
    }

    /**
     * The summary text for [date]. Cached; asks the model only for a new day or when [regenerate] is set (or, at most every 30 min,
     * to replace a template that was used only because the call failed). Never throws except cancellation.
     */
    suspend fun text(ctx: Context, date: String, regenerate: Boolean): SummaryText = text(ctx, LocalDate.parse(date), regenerate)

    suspend fun text(ctx: Context, date: LocalDate, regenerate: Boolean): SummaryText = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val old = cached(ctx, date)
        if (old != null && !regenerate && !(old.source == "template" && now - old.createdMs > RETRY_TEMPLATE_AFTER_MS && hasKey(ctx))) return@withContext old
        val f = facts(ctx, date)
        val template = { SummaryText(Copy.daySummary(f), "template", System.currentTimeMillis()) }
        if (!f.hasData) return@withContext template()   // not stored: the card shows "Not enough data from today yet"
        val u = usual(ctx, date)
        val fj = DaySummaryLogic.factsJson(f, u)
        val result = try {
            askModel(ctx, fj, DaySummaryLogic.allowed(f, u), if (regenerate) (old?.createdMs?.rem(97)?.toInt() ?: 1).coerceAtLeast(1) else 0)
        } catch (e: kotlinx.coroutines.CancellationException) { throw e
        } catch (e: Exception) { AppLog.d("day summary: model failed: ${e.javaClass.simpleName}: ${e.message}"); null }
        if (result == null && regenerate && old != null && old.source == "llm") return@withContext old  // keep the good text
        val out = if (result != null) SummaryText(result.first, "llm", System.currentTimeMillis()) else template()
        store(ctx, date, fj, out, result?.second)
        out
    }

    private fun hasKey(ctx: Context): Boolean = try {
        val p = ctx.getSharedPreferences(SyncPrefs.FILE, Context.MODE_PRIVATE).getString("coach_provider", "gemini")
        SecretStore.get(ctx, if (p == "openai") "openai_key" else "gemini_key").trim().isNotEmpty()
    } catch (e: Exception) { false }

    /** One cheap call (default small model, thinking off, 120 tokens, 8 s). Returns (text, model) or null on any failure. */
    private suspend fun askModel(ctx: Context, facts: JSONObject, allowed: List<Double>, variant: Int): Pair<String, String>? {
        val prefs = ctx.getSharedPreferences(SyncPrefs.FILE, Context.MODE_PRIVATE)
        val provider = (prefs.getString("coach_provider", "gemini") ?: "gemini").lowercase().let { if (it == "openai") "openai" else "gemini" }
        val key = SecretStore.get(ctx, if (provider == "openai") "openai_key" else "gemini_key").trim()
        if (key.isEmpty()) return null
        val model = Models.default(provider)
        val client: LlmClient = if (provider == "openai") OpenAiClient(key, ModelQuirks(ctx)) else GeminiClient(key, ModelQuirks(ctx))
        val t0 = System.currentTimeMillis()
        var err: String? = null; var usage = Usage(); var finish: String? = null; var usedModel = model
        try {
            val req = LlmRequest(model, DaySummaryLogic.system, listOf(LlmMsg.User(DaySummaryLogic.userMessage(facts, variant))),
                emptyList(), DaySummaryLogic.MAX_TOKENS, Thinking.Off, DaySummaryLogic.schema)
            val call = CoroutineScope(Dispatchers.IO).async { client.generate(req) }
            val resp = withTimeoutOrNull(DaySummaryLogic.TIMEOUT_MS) { call.await() }
            if (resp == null) { err = "timeout"; return null }
            usage = resp.usage; finish = resp.finish; usedModel = resp.model
            val text = DaySummaryLogic.parseReply(resp.text)
            if (!DaySummaryLogic.accept(text, allowed)) { err = "rejected: schema or unknown number"; AppLog.d("day summary: reply rejected, using template"); return null }
            return text!! to usedModel
        } catch (e: kotlinx.coroutines.CancellationException) { throw e
        } catch (e: Exception) { err = e.message ?: e.javaClass.simpleName; throw e
        } finally {
            AiCallDao.insert(ctx, AiCallRow(System.currentTimeMillis(), "day_summary", provider, usedModel, 1, usage.inTok, usage.outTok,
                usage.thoughtTok, usage.cachedTok, System.currentTimeMillis() - t0, err == null, finish, err))
        }
    }
}
