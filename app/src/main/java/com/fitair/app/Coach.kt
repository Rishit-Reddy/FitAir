package com.fitair.app

import android.content.Context
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

data class ChatMsg(val role: String /* "user" | "assistant" | "note" */, val text: String)

object ServerApi {
    /** GET [path] (including query string) on the laptop server. @throws IOException with readable message. */
    suspend fun get(ctx: Context, path: String): JSONObject = withContext(Dispatchers.IO) {
        val prefs = ctx.getSharedPreferences(SyncPrefs.FILE, Context.MODE_PRIVATE)
        val base = SyncRepo.baseUrl(prefs.getString(SyncPrefs.ADDR, "") ?: "")
        val key = prefs.getString(SyncPrefs.KEY, "") ?: ""
        if (base.isEmpty()) throw IOException("No server address configured")
        val p = if (path.startsWith("/")) path else "/$path"
        val text = try {
            SyncRepo.request("GET", base + p, key, null)
        } catch (e: IOException) {
            throw IOException("Server request failed (${p.substringBefore('?')}): ${e.message ?: e.javaClass.simpleName}", e)
        }
        try {
            JSONObject(text)
        } catch (e: Exception) {
            throw IOException("Server returned invalid JSON for ${p.substringBefore('?')}")
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
            (0 until a.length()).map { val o = a.getJSONObject(it); ChatMsg(o.getString("role"), o.getString("text")) }
        }
    } catch (e: Exception) {
        AppLog.d("ChatStore.load failed: ${e.message}")
        emptyList()
    }

    fun save(ctx: Context, msgs: List<ChatMsg>) {
        val a = JSONArray()
        msgs.takeLast(MAX).forEach { a.put(JSONObject().put("role", it.role).put("text", it.text)) }
        ctx.getSharedPreferences(SyncPrefs.FILE, Context.MODE_PRIVATE).edit().putString(KEY, a.toString()).apply()
    }

    fun clear(ctx: Context) {
        ctx.getSharedPreferences(SyncPrefs.FILE, Context.MODE_PRIVATE).edit().remove(KEY).apply()
    }
}

class CoachRepo(private val ctx: Context) {
    private val prefs = ctx.getSharedPreferences(SyncPrefs.FILE, Context.MODE_PRIVATE)
    private val zone: ZoneId = ZoneId.systemDefault()

    private companion object {
        const val MAX_ROUNDS = 6
        const val MAX_RESULT = 12_000
    }

    private class ToolDef(val name: String, val desc: String, val props: List<Triple<String, String, String>>)

    // (param name, json type, description)
    private val tools = listOf(
        ToolDef("get_day_summary", "Daily summary (steps, distance, resting HR, HRV, HR stats, sleep, exercise count, kcal, readiness) for one date.",
            listOf(Triple("date", "string", "Date YYYY-MM-DD"))),
        ToolDef("get_range_summary", "Array of daily summaries for an inclusive date range.",
            listOf(Triple("from", "string", "Start date YYYY-MM-DD"), Triple("to", "string", "End date YYYY-MM-DD"))),
        ToolDef("get_heart_rate", "Heart rate buckets (min/mean/max/n) between two epoch-ms timestamps.",
            listOf(Triple("from_ms", "integer", "Start, epoch milliseconds UTC"), Triple("to_ms", "integer", "End, epoch milliseconds UTC"),
                Triple("bucket_s", "integer", "Bucket size in seconds (min 30 unless range under 2 h)"))),
        ToolDef("get_workouts", "Workouts in a date range with duration, HR mean/max, 1-min HR recovery, drift and time in zones.",
            listOf(Triple("from", "string", "Start date YYYY-MM-DD"), Triple("to", "string", "End date YYYY-MM-DD"))),
        ToolDef("get_readiness", "The app's own readiness score (0-100) with components, baseline and notes for a date.",
            listOf(Triple("date", "string", "Date YYYY-MM-DD"))),
        ToolDef("get_baselines", "28-day baselines (resting HR, HRV, sleep) with standard deviations.", emptyList()),
    )

    suspend fun ask(history: List<ChatMsg>, onNote: (String) -> Unit): String = withContext(Dispatchers.IO) {
        val provider = (prefs.getString("coach_provider", "gemini") ?: "gemini").lowercase().let { if (it == "openai") "openai" else "gemini" }
        val apiKey = (prefs.getString(if (provider == "openai") "openai_key" else "gemini_key", "") ?: "").trim()
        val model = (prefs.getString(if (provider == "openai") "coach_model_openai" else "coach_model_gemini", "") ?: "").trim()
            .ifEmpty { if (provider == "openai") "gpt-4o-mini" else "gemini-2.5-flash" }
        if (apiKey.isEmpty()) {
            throw IOException("No ${if (provider == "openai") "OpenAI" else "Gemini"} API key set. Add it in Settings to use the coach.")
        }
        val msgs = history.filter { it.role == "user" || it.role == "assistant" }.takeLast(40)
        if (msgs.isEmpty()) throw IOException("Nothing to send")
        val t0 = System.currentTimeMillis()
        AppLog.d("coach ask: provider=$provider model=$model messages=${msgs.size}")

        val system = buildSystemPrompt()
        val answer = if (provider == "openai") runOpenAi(apiKey, model, system, msgs, onNote)
        else runGemini(apiKey, model, system, msgs, onNote)
        AppLog.d("coach done in ${System.currentTimeMillis() - t0} ms, ${answer.length} chars")
        answer
    }

    // ---------- context ----------

    private suspend fun buildSystemPrompt(): String {
        val now = java.time.ZonedDateTime.now(zone)
        val today = now.toLocalDate()
        val fmt = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy, HH:mm", Locale.ENGLISH)
        var ctxText: String
        try {
            val t = System.currentTimeMillis()
            val range = ServerApi.get(ctx, "/summary/range?from=${today.minusDays(6)}&to=$today&tz=${enc(zone.id)}")
            val ready = try { ServerApi.get(ctx, "/readiness?date=$today&tz=${enc(zone.id)}") } catch (e: IOException) {
                AppLog.d("coach: readiness prefetch failed: ${e.message}"); null
            }
            AppLog.d("coach: context prefetched in ${System.currentTimeMillis() - t} ms")
            ctxText = "Last 7 days summary (/summary/range):\n${trunc(range.toString())}\n\n" +
                "Today's readiness (/readiness):\n${if (ready != null) trunc(ready.toString()) else "unavailable"}"
        } catch (e: IOException) {
            AppLog.d("coach: context prefetch failed: ${e.message}")
            ctxText = "DATA UNAVAILABLE: the laptop server could not be reached (${e.message}). " +
                "Tell the user their data is currently unavailable; do not guess numbers. Tools will probably fail too."
        }
        return """
You are FitAir Coach, a concise, honest, data-driven fitness and recovery coach for one user: a Fitbit Air wearer who is a data scientist and likes numbers.
Now: ${now.format(fmt)} (${zone.id}). Today is ${today} (${today.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, Locale.ENGLISH)}). Use this timezone for all dates; times from tools are epoch ms UTC.

Rules:
- Use the tools to fetch specifics (a day, a range, heart rate series, workouts, readiness, baselines) instead of guessing. Never invent numbers. If data is missing or a tool fails, say so plainly.
- Readiness is this app's own score (components: sleep, HRV, resting HR, load), not a clinical measure. You give no medical diagnosis; suggest seeing a clinician for worrying symptoms.
- Keep answers short unless the user asks for depth. Cite concrete numbers and compare against baselines where useful.

Always-available context (fetched just now):
$ctxText
""".trimIndent()
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
            else -> "called $name"
        }
    }

    private fun trunc(s: String) = if (s.length <= MAX_RESULT) s else s.take(MAX_RESULT) + "...[truncated, ${s.length} chars total]"

    /** Runs a tool; never throws except cancellation. Returns the (truncated) result JSON string. */
    private suspend fun runTool(name: String, args: JSONObject): String {
        val t = System.currentTimeMillis()
        AppLog.d("coach tool: $name $args")
        return try {
            val tz = "tz=${enc(zone.id)}"
            val path = when (name) {
                "get_day_summary" -> "/summary/day?date=${enc(args.getString("date"))}&$tz"
                "get_range_summary" -> "/summary/range?from=${enc(args.getString("from"))}&to=${enc(args.getString("to"))}&$tz"
                "get_heart_rate" -> {
                    val f = args.getLong("from_ms"); val to = args.getLong("to_ms")
                    var b = args.optLong("bucket_s", 60)
                    val shortRange = (to - f) < 2 * 3600_000L
                    if (b < 30 && !shortRange) { AppLog.d("coach tool: bucket_s $b clamped to 30"); b = 30 }
                    if (b < 0) b = 0
                    "/hr?from_ms=$f&to_ms=$to&bucket_s=$b"
                }
                "get_workouts" -> "/workouts?from=${enc(args.getString("from"))}&to=${enc(args.getString("to"))}&$tz"
                "get_readiness" -> "/readiness?date=${enc(args.getString("date"))}&$tz"
                "get_baselines" -> "/baselines?$tz"
                else -> return JSONObject().put("error", "unknown tool $name").toString()
            }
            val out = trunc(ServerApi.get(ctx, path).toString())
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

    private fun parseArgs(raw: String?): JSONObject = try { JSONObject(if (raw.isNullOrBlank()) "{}" else raw) } catch (e: Exception) { JSONObject() }

    // ---------- HTTP ----------

    private fun postJson(url: String, headers: Map<String, String>, body: JSONObject, label: String): JSONObject {
        val t = System.currentTimeMillis()
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 30_000
            c.readTimeout = 120_000
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            AppLog.d("coach $label -> HTTP $code in ${System.currentTimeMillis() - t} ms (${text.length} B)")
            if (code !in 200..299) {
                val msg = try { JSONObject(text).getJSONObject("error").optString("message") } catch (e: Exception) { "" }
                    .ifEmpty { text.take(300) }
                throw IOException("$label HTTP $code: $msg")
            }
            return try { JSONObject(text) } catch (e: Exception) { throw IOException("$label returned invalid JSON") }
        } catch (e: IOException) {
            if (e.message?.contains(" HTTP ") != true) AppLog.d("coach $label failed after ${System.currentTimeMillis() - t} ms: ${e.message}")
            if (e.message?.contains(label) == true) throw e
            throw IOException("$label network error: ${e.message ?: e.javaClass.simpleName}", e)
        } finally {
            c.disconnect()
        }
    }

    // ---------- Gemini ----------

    private fun runGemini(key: String, model: String, system: String, msgs: List<ChatMsg>, onNote: (String) -> Unit): String {
        val url = "https://generativelanguage.googleapis.com/v1beta/models/${enc(model)}:generateContent"
        val headers = mapOf("x-goog-api-key" to key)
        val decls = JSONArray()
        for (t in tools) {
            val d = JSONObject().put("name", t.name).put("description", t.desc)
            if (t.props.isNotEmpty()) {
                val p = JSONObject(); val req = JSONArray()
                t.props.forEach { (n, ty, ds) -> p.put(n, JSONObject().put("type", ty.uppercase()).put("description", ds)); req.put(n) }
                d.put("parameters", JSONObject().put("type", "OBJECT").put("properties", p).put("required", req))
            }
            decls.put(d)
        }
        val contents = JSONArray()
        msgs.forEach {
            contents.put(JSONObject().put("role", if (it.role == "user") "user" else "model")
                .put("parts", JSONArray().put(JSONObject().put("text", it.text))))
        }
        var rounds = 0
        while (true) {
            val force = rounds >= MAX_ROUNDS
            val body = JSONObject()
                .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
                .put("contents", contents)
                .put("tools", JSONArray().put(JSONObject().put("functionDeclarations", decls)))
            if (force) body.put("toolConfig", JSONObject().put("functionCallingConfig", JSONObject().put("mode", "NONE")))
            AppLog.d("coach gemini request: round=$rounds model=$model force_final=$force")
            val resp = runBlockingPost(url, headers, body, "Gemini")
            val cand = resp.optJSONArray("candidates")?.optJSONObject(0)
            if (cand == null) {
                val br = resp.optJSONObject("promptFeedback")?.optString("blockReason").orEmpty()
                throw IOException("Gemini returned no answer" + if (br.isNotEmpty()) " (blocked: $br)" else "")
            }
            val content = cand.optJSONObject("content")
            val parts = content?.optJSONArray("parts") ?: JSONArray()
            val calls = ArrayList<JSONObject>()
            val sb = StringBuilder()
            for (i in 0 until parts.length()) {
                val p = parts.getJSONObject(i)
                p.optJSONObject("functionCall")?.let { calls.add(it) }
                if (!p.has("functionCall") && !p.optBoolean("thought", false) && p.has("text")) sb.append(p.getString("text"))
            }
            if (calls.isEmpty() || force) {
                val txt = sb.toString().trim()
                if (txt.isEmpty()) throw IOException("Gemini returned an empty answer (finishReason=${cand.optString("finishReason")})")
                return txt
            }
            rounds++
            contents.put(JSONObject().put("role", "model").put("parts", parts))
            val respParts = JSONArray()
            for (fc in calls) {
                val name = fc.getString("name")
                val args = fc.optJSONObject("args") ?: JSONObject()
                onNote(noteFor(name, args))
                val result = runBlockingTool(name, args)
                val rj = try { JSONObject(result) } catch (e: Exception) { JSONObject().put("error", "bad result") }
                respParts.put(JSONObject().put("functionResponse", JSONObject().put("name", name).put("response", JSONObject().put("result", rj))))
            }
            contents.put(JSONObject().put("role", "user").put("parts", respParts))
        }
    }

    // ---------- OpenAI ----------

    private fun runOpenAi(key: String, model: String, system: String, msgs: List<ChatMsg>, onNote: (String) -> Unit): String {
        val url = "https://api.openai.com/v1/chat/completions"
        val headers = mapOf("Authorization" to "Bearer $key")
        val toolArr = JSONArray()
        for (t in tools) {
            val p = JSONObject(); val req = JSONArray()
            t.props.forEach { (n, ty, ds) -> p.put(n, JSONObject().put("type", ty).put("description", ds)); req.put(n) }
            toolArr.put(JSONObject().put("type", "function").put("function", JSONObject()
                .put("name", t.name).put("description", t.desc)
                .put("parameters", JSONObject().put("type", "object").put("properties", p).put("required", req))))
        }
        val messages = JSONArray()
        messages.put(JSONObject().put("role", "system").put("content", system))
        msgs.forEach { messages.put(JSONObject().put("role", it.role).put("content", it.text)) }
        var rounds = 0
        while (true) {
            val force = rounds >= MAX_ROUNDS
            val body = JSONObject().put("model", model).put("messages", messages).put("tools", toolArr)
            if (force) body.put("tool_choice", "none")
            AppLog.d("coach openai request: round=$rounds model=$model force_final=$force")
            val resp = runBlockingPost(url, headers, body, "OpenAI")
            val msg = resp.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
                ?: throw IOException("OpenAI returned no answer")
            val calls = msg.optJSONArray("tool_calls")
            if (calls == null || calls.length() == 0 || force) {
                val txt = (if (msg.isNull("content")) "" else msg.optString("content")).trim()
                if (txt.isEmpty()) throw IOException("OpenAI returned an empty answer")
                return txt
            }
            rounds++
            messages.put(msg)
            for (i in 0 until calls.length()) {
                val c = calls.getJSONObject(i)
                val fn = c.getJSONObject("function")
                val name = fn.getString("name")
                val args = parseArgs(fn.optString("arguments"))
                onNote(noteFor(name, args))
                val result = runBlockingTool(name, args)
                messages.put(JSONObject().put("role", "tool").put("tool_call_id", c.getString("id")).put("content", result))
            }
        }
    }

    // The provider loops are plain (already on Dispatchers.IO); bridge to suspend helpers.
    private fun runBlockingPost(url: String, headers: Map<String, String>, body: JSONObject, label: String): JSONObject =
        postJson(url, headers, body, label)

    private fun runBlockingTool(name: String, args: JSONObject): String =
        kotlinx.coroutines.runBlocking { runTool(name, args) }
}
