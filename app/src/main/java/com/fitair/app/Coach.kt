package com.fitair.app

import android.content.Context
import com.fitair.app.coach.*
import com.fitair.app.integrations.calendar.*
import com.fitair.app.data.dao.AiCallDao
import com.fitair.app.data.dao.AiCallRow
import com.fitair.app.secure.SecretStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * [text] is always displayable (markdown for answers). [json] is the structured CoachAnswer (assistant only),
 * [trace] the "Used: ..." line, [long] marks an "Explain more" answer, [truncated] a cut-off one.
 */
data class ChatMsg(
    val role: String /* "user" | "assistant" | "note" */, val text: String,
    val json: String? = null, val trace: String = "", val long: Boolean = false, val truncated: Boolean = false,
)

object ServerApi {
    /** GET [path] (including query string), computed on-device from the local database. @throws IOException with readable message. */
    suspend fun get(ctx: Context, path: String): JSONObject = withContext(Dispatchers.IO) {
        try {
            LocalApi.get(ctx, path)
        } catch (e: IllegalArgumentException) {
            throw IOException("Bad request (${path.substringBefore('?')}): ${e.message}", e)
        }
    }
}

object ChatStore {
    private const val KEY = "coach_chat"
    private const val MAX = 60

    fun load(ctx: Context): List<ChatMsg> = try {
        val s = ctx.getSharedPreferences(SyncPrefs.FILE, Context.MODE_PRIVATE).getString(KEY, null)
        if (s.isNullOrEmpty()) emptyList() else {
            val a = JSONArray(s)
            (0 until a.length()).map {
                val o = a.getJSONObject(it)
                ChatMsg(o.getString("role"), o.getString("text"), o.optString("json").ifEmpty { null }, o.optString("trace"),
                    o.optBoolean("long"), o.optBoolean("truncated"))
            }
        }
    } catch (e: Exception) {
        AppLog.d("ChatStore.load failed: ${e.message}")
        emptyList()
    }

    fun save(ctx: Context, msgs: List<ChatMsg>) {
        val a = JSONArray()
        msgs.takeLast(MAX).forEach { a.put(JSONObject().put("role", it.role).put("text", it.text).put("json", it.json ?: "")
            .put("trace", it.trace).put("long", it.long).put("truncated", it.truncated)) }
        ctx.getSharedPreferences(SyncPrefs.FILE, Context.MODE_PRIVATE).edit().putString(KEY, a.toString()).apply()
    }

    fun clear(ctx: Context) {
        ctx.getSharedPreferences(SyncPrefs.FILE, Context.MODE_PRIVATE).edit().remove(KEY).apply()
    }
}

/** Result of one coach turn. [text] is the markdown rendering (always displayable); [answerJson] the structured answer or null for plain text. */
data class CoachReply(val text: String, val answerJson: String?, val trace: String, val truncated: Boolean, val long: Boolean)

class CoachRepo(private val ctx: Context, private val clientOverride: LlmClient? = null) {
    private val prefs = ctx.getSharedPreferences(SyncPrefs.FILE, Context.MODE_PRIVATE)
    private val zone: ZoneId = ZoneId.systemDefault()

    companion object {
        const val MAX_ROUNDS = 4
        const val MAX_RESULT = 3_000
        const val HISTORY = 6
    }

    // (param name, json type, description)
    private val tools = listOf(
        ToolSpec("get_day_summary", "Daily summary (steps, distance, resting HR, HRV, HR stats, sleep, exercise count, kcal, readiness) for one date.",
            listOf(Triple("date", "string", "Date YYYY-MM-DD"))),
        ToolSpec("get_range_summary", "Array of daily summaries for an inclusive date range.",
            listOf(Triple("from", "string", "Start date YYYY-MM-DD"), Triple("to", "string", "End date YYYY-MM-DD"))),
        ToolSpec("get_heart_rate", "Heart rate buckets (min/mean/max/n) between two epoch-ms timestamps.",
            listOf(Triple("from_ms", "integer", "Start, epoch milliseconds UTC"), Triple("to_ms", "integer", "End, epoch milliseconds UTC"),
                Triple("bucket_s", "integer", "Bucket size in seconds (multiple of 30; heart rate is stored at 30 s resolution; 0 = raw samples, only available inside workouts)"))),
        ToolSpec("get_workouts", "Workouts in a date range with duration, HR mean/max, 1-min HR recovery, drift and time in zones.",
            listOf(Triple("from", "string", "Start date YYYY-MM-DD"), Triple("to", "string", "End date YYYY-MM-DD"))),
        ToolSpec("get_readiness", "The app's own readiness score (0-100) with components, baseline and notes for a date.",
            listOf(Triple("date", "string", "Date YYYY-MM-DD"))),
        ToolSpec("get_baselines", "28-day baselines (resting HR, HRV, sleep) with standard deviations.", emptyList()),
        ToolSpec("get_daily_metrics", "The app's own stored daily metrics per date: sleep_score (with components, need and sleep debt), readiness (with full breakdown), TRIMP load, acute/chronic load, ACWR, resting HR, HRV, sleep minutes, steps and insights.",
            listOf(Triple("from", "string", "Start date YYYY-MM-DD"), Triple("to", "string", "End date YYYY-MM-DD"))),
        ToolSpec("get_insights", "Rule-based insights (info/watch/alert) for one date, e.g. elevated resting HR, low HRV, sleep debt, load spikes.",
            listOf(Triple("date", "string", "Date YYYY-MM-DD"))),
        ToolSpec("get_load", "Daily training load: TRIMP, acute (7-day EWMA), chronic (28-day EWMA) and ACWR for a date range.",
            listOf(Triple("from", "string", "Start date YYYY-MM-DD"), Triple("to", "string", "End date YYYY-MM-DD"))),
        ToolSpec("get_agenda", "The user's calendar for one date: events (start/end local ISO, title, all_day, busy) and free_gaps (from/to, minutes) of 45+ min between 07:00 and 22:00 after now. Titles may be redacted as 'Busy'. Empty if calendar access is off.",
            listOf(Triple("date", "string", "Date YYYY-MM-DD"))),
    )

    /** Legacy entry used by MainViewModel: returns the answer as displayable text. */
    suspend fun ask(history: List<ChatMsg>, onNote: (String) -> Unit): String = answer(history, false, onNote).text

    private fun provider() = (prefs.getString("coach_provider", "gemini") ?: "gemini").lowercase().let { if (it == "openai") "openai" else "gemini" }

    /**
     * One coach turn: tool loop (max [MAX_ROUNDS] tool rounds, then a forced final answer). [long] = "Explain more".
     * Logs one ai_call row. @throws IOException with a readable message.
     */
    suspend fun answer(history: List<ChatMsg>, long: Boolean, onNote: (String) -> Unit): CoachReply = withContext(Dispatchers.IO) {
        val provider = clientOverride?.provider ?: provider()
        val model = Models.selected(ctx, provider)
        val client = clientOverride ?: run {
            val key = SecretStore.get(ctx, if (provider == "openai") "openai_key" else "gemini_key").trim()
            if (key.isEmpty()) throw IOException("No ${if (provider == "openai") "OpenAI" else "Gemini"} API key set. Add it in Settings to use the coach.")
            val q = ModelQuirks(ctx)
            if (provider == "openai") OpenAiClient(key, q) else GeminiClient(key, q)
        }
        var msgs = history.filter { it.role == "user" || it.role == "assistant" }.takeLast(HISTORY).dropWhile { it.role != "user" }
        if (msgs.isEmpty()) throw IOException("Nothing to send")
        val t0 = System.currentTimeMillis()
        AppLog.d("coach ask: provider=$provider model=$model messages=${msgs.size} long=$long")

        val system = buildSystemPrompt(long)
        val conv = ArrayList<LlmMsg>()
        msgs.forEach { conv.add(if (it.role == "user") LlmMsg.User(it.text) else LlmMsg.Assistant(it.text)) }
        val schema = Prompt.answerSchema
        val cap = if (long) Models.CAP_LONG else Models.CAP_CHAT
        val thinking = if (long) Thinking.Low else Thinking.Off

        var usage = Usage(); var calls = 0; var finish: String? = null; var usedModel = model
        var err: String? = null
        val used = LinkedHashSet<String>()
        try {
            var rounds = 0
            while (true) {
                val force = rounds >= MAX_ROUNDS
                val resp = client.generate(LlmRequest(model, system, conv, tools, cap, thinking, schema, force))
                calls++; usage += resp.usage; finish = resp.finish; usedModel = resp.model
                if (resp.usage.inTok > Prompt.TOTAL_WARN_TOKENS) AppLog.d("WARN coach: ${resp.usage.inTok} input tokens in one call")
                if (resp.calls.isEmpty() || force) {
                    if (resp.text.isEmpty()) throw IOException("The model returned an empty answer (finish=${resp.finish})")
                    val parsed = AnswerParser.parse(resp.text)
                    val truncated = resp.finish == "length"
                    val reply = CoachReply(
                        text = AnswerParser.toMarkdown(parsed),
                        answerJson = if (parsed.plain == null) AnswerParser.toJson(parsed) else null,
                        trace = if (used.isEmpty()) "" else "Used: " + used.joinToString(", "),
                        truncated = truncated, long = long,
                    )
                    AppLog.d("coach done in ${System.currentTimeMillis() - t0} ms, ${reply.text.length} chars, $calls call(s), finish=$finish")
                    return@withContext reply
                }
                rounds++
                conv.add(LlmMsg.Assistant(resp.text, resp.calls, resp.raw))
                val results = resp.calls.map { c ->
                    onNote(noteFor(c.name, c.args))
                    used.add(c.name.removePrefix("get_").replace('_', ' '))
                    ToolResult(c, runTool(c.name, c.args))
                }
                conv.add(LlmMsg.ToolResults(results))
            }
            @Suppress("UNREACHABLE_CODE") throw IllegalStateException()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            err = e.message ?: e.javaClass.simpleName
            throw e
        } finally {
            AiCallDao.insert(ctx, AiCallRow(System.currentTimeMillis(), if (long) "chat_long" else "chat", provider, usedModel, calls,
                usage.inTok, usage.outTok, usage.thoughtTok, usage.cachedTok, System.currentTimeMillis() - t0, err == null, finish, err))
        }
    }

    // ---------- context ----------

    private suspend fun buildSystemPrompt(long: Boolean): String {
        val now = java.time.ZonedDateTime.now(zone)
        val today = now.toLocalDate()
        val fmt = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy, HH:mm", Locale.ENGLISH)
        val block = try {
            val t = System.currentTimeMillis()
            val j = ServerApi.get(ctx, "/daily?from=${today.minusDays(6)}&to=$today&tz=${enc(zone.id)}")
            val arr = j.optJSONArray("days") ?: JSONArray()
            AppLog.d("coach: context prefetched in ${System.currentTimeMillis() - t} ms")
            Prompt.context((0 until arr.length()).map { arr.getJSONObject(it) }, today.toString())
        } catch (e: IOException) {
            AppLog.d("coach: context prefetch failed: ${e.message}")
            Prompt.context(null, today.toString(), e.message)
        }
        val rules = Prompt.rules(now.format(fmt), zone.id, today.toString(),
            today.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, Locale.ENGLISH), long)
        return Prompt.system(block, rules)
    }

    // ---------- tool execution ----------

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun noteFor(name: String, a: JSONObject): String {
        val df = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)
        fun d(s: String): String = try { LocalDate.parse(s).format(df) } catch (e: Exception) { s }
        fun ms(k: String): String = try { Instant.ofEpochMilli(a.getLong(k)).atZone(zone).toLocalDate().format(df) } catch (e: Exception) { "?" }
        return when (name) {
            "get_day_summary" -> "looked up daily summary for ${d(a.optString("date"))}"
            "get_range_summary" -> "looked up summaries ${d(a.optString("from"))} to ${d(a.optString("to"))}"
            "get_heart_rate" -> {
                val f = ms("from_ms"); val t = ms("to_ms")
                "looked up heart rate for ${if (f == t) f else "$f to $t"}"
            }
            "get_workouts" -> "looked up workouts ${d(a.optString("from"))} to ${d(a.optString("to"))}"
            "get_readiness" -> "looked up readiness for ${d(a.optString("date"))}"
            "get_baselines" -> "looked up 28-day baselines"
            "get_daily_metrics" -> "looked up daily metrics ${d(a.optString("from"))} to ${d(a.optString("to"))}"
            "get_insights" -> "looked up insights for ${d(a.optString("date"))}"
            "get_load" -> "looked up training load ${d(a.optString("from"))} to ${d(a.optString("to"))}"
            "get_agenda" -> "looked up calendar for ${d(a.optString("date"))}"
            else -> "called $name"
        }
    }

    /** Drops *_json blobs, then hard-truncates to [MAX_RESULT] chars. */
    private fun compact(j: JSONObject): String {
        fun strip(x: Any?): Any? = when (x) {
            is JSONObject -> JSONObject().also { o -> x.keys().forEach { k -> if (!k.endsWith("_json")) o.put(k, strip(x.get(k))) } }
            is JSONArray -> JSONArray().also { a -> for (i in 0 until x.length()) a.put(strip(x.get(i))) }
            else -> x
        }
        var s = j.toString()
        if (s.length > MAX_RESULT) s = (strip(j) as JSONObject).toString()
        return if (s.length <= MAX_RESULT) s else s.take(MAX_RESULT) + "...[truncated, ${s.length} chars total]"
    }

    private suspend fun agenda(dateStr: String): JSONObject {
        val day = try { LocalDate.parse(dateStr) } catch (e: Exception) { throw org.json.JSONException("date must be YYYY-MM-DD") }
        val share = prefs.getBoolean("coach_share_titles", false)
        val repo = CalendarRepo(ctx)
        val evs = repo.eventsForDay(day, zone)
        val ds = day.atStartOfDay(zone).toInstant()
        val items = buildAgenda(evs, Instant.now(), ds, day.plusDays(1).atStartOfDay(zone).toInstant(), zone = zone)
        fun t(i: Instant) = i.atZone(zone).toLocalDateTime().toString()
        val events = JSONArray(); val gaps = JSONArray()
        for (it in items) when (it) {
            is AgendaItem.Event -> events.put(JSONObject().put("start", t(it.e.begin)).put("end", t(it.e.end))
                .put("title", if (share) it.e.title else "Busy").put("all_day", it.e.allDay).put("busy", it.e.busy)
                .also { o -> if (share && it.e.location != null) o.put("location", it.e.location) })
            is AgendaItem.Gap -> gaps.put(JSONObject().put("from", t(it.from)).put("to", t(it.to))
                .put("minutes", java.time.Duration.between(it.from, it.to).toMinutes()))
        }
        return JSONObject().put("date", day.toString()).put("events", events).put("free_gaps", gaps)
    }

    /** Runs a tool; never throws except cancellation. Returns the (<= 3k chars) result JSON string. */
    private suspend fun runTool(name: String, args: JSONObject): String {
        val t = System.currentTimeMillis()
        AppLog.d("coach tool: $name $args")
        return try {
            if (name == "get_agenda") {
                val out = compact(agenda(args.getString("date")))
                AppLog.d("coach tool: $name ok in ${System.currentTimeMillis() - t} ms (${out.length} chars)")
                return out
            }
            val tz = "tz=${enc(zone.id)}"
            val path = when (name) {
                "get_day_summary" -> "/summary/day?date=${enc(args.getString("date"))}&$tz"
                "get_range_summary" -> "/summary/range?from=${enc(args.getString("from"))}&to=${enc(args.getString("to"))}&$tz"
                "get_heart_rate" -> {
                    val f = args.getLong("from_ms"); val to = args.getLong("to_ms")
                    var b = args.optLong("bucket_s", 60)
                    val shortRange = (to - f) < 2 * 3600_000L
                    if (b < 0) b = 0
                    if (b == 0L && !shortRange) { AppLog.d("coach tool: bucket_s 0 on long range -> 30"); b = 30 }
                    if (b in 1..29) { AppLog.d("coach tool: bucket_s $b clamped to 30"); b = 30 }
                    "/hr?from_ms=$f&to_ms=$to&bucket_s=$b"
                }
                "get_workouts" -> "/workouts?from=${enc(args.getString("from"))}&to=${enc(args.getString("to"))}&$tz"
                "get_readiness" -> "/readiness?date=${enc(args.getString("date"))}&$tz"
                "get_baselines" -> "/baselines?$tz"
                "get_daily_metrics" -> "/daily?from=${enc(args.getString("from"))}&to=${enc(args.getString("to"))}"
                "get_insights" -> "/insights?date=${enc(args.getString("date"))}"
                "get_load" -> "/load?from=${enc(args.getString("from"))}&to=${enc(args.getString("to"))}"
                else -> return JSONObject().put("error", "unknown tool $name").toString()
            }
            val out = compact(ServerApi.get(ctx, path))
            AppLog.d("coach tool: $name ok in ${System.currentTimeMillis() - t} ms (${out.length} chars)")
            out
        } catch (e: IOException) {
            AppLog.d("coach tool: $name failed in ${System.currentTimeMillis() - t} ms: ${e.message}")
            JSONObject().put("error", e.message ?: "request failed").toString()
        } catch (e: org.json.JSONException) {
            AppLog.d("coach tool: $name bad args: ${e.message}")
            JSONObject().put("error", "invalid or missing arguments: ${e.message}").toString()
        }
    }
}
