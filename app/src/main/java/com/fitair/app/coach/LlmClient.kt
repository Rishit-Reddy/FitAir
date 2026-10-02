package com.fitair.app.coach

import com.fitair.app.AppLog
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class ToolSpec(val name: String, val desc: String, val props: List<Triple<String, String, String>>)

data class ToolCall(val id: String, val name: String, val args: JSONObject)
data class ToolResult(val call: ToolCall, val content: String)

sealed class LlmMsg {
    data class User(val text: String) : LlmMsg()
    /** [raw] is the provider's own JSON of this turn (needed to echo thought signatures); null for plain history. */
    data class Assistant(val text: String, val calls: List<ToolCall> = emptyList(), val raw: String? = null) : LlmMsg()
    data class ToolResults(val results: List<ToolResult>) : LlmMsg()
}

enum class Thinking { Off, Low, Default }

class LlmRequest(
    val model: String,
    val system: String,
    val messages: List<LlmMsg>,
    val tools: List<ToolSpec>,
    val maxOutputTokens: Int,
    val thinking: Thinking = Thinking.Off,
    val jsonSchema: JSONObject? = null,
    val forceFinal: Boolean = false,
)

data class Usage(val inTok: Int = 0, val outTok: Int = 0, val thoughtTok: Int = 0, val cachedTok: Int = 0) {
    operator fun plus(o: Usage) = Usage(inTok + o.inTok, outTok + o.outTok, thoughtTok + o.thoughtTok, cachedTok + o.cachedTok)
}

class LlmResponse(
    val text: String,
    val calls: List<ToolCall>,
    val raw: String?,
    val usage: Usage,
    /** Normalised: "stop", "length", "tool_calls" or the provider's own value. */
    val finish: String,
    /** The model that actually answered (differs from the request after a model-name fallback). */
    val model: String,
)

class LlmHttpException(val code: Int, message: String) : IOException(message)

interface LlmClient {
    val provider: String
    suspend fun generate(req: LlmRequest): LlmResponse
}

internal object LlmHttp {
    fun post(url: String, headers: Map<String, String>, body: JSONObject, label: String): JSONObject {
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
                throw LlmHttpException(code, "$label HTTP $code: $msg")
            }
            return try { JSONObject(text) } catch (e: Exception) { throw IOException("$label returned invalid JSON") }
        } catch (e: LlmHttpException) {
            throw e
        } catch (e: IOException) {
            AppLog.d("coach $label failed after ${System.currentTimeMillis() - t} ms: ${e.message}")
            throw IOException("$label network error: ${e.message ?: e.javaClass.simpleName}", e)
        } finally {
            c.disconnect()
        }
    }

    private val MODEL_WORDS = listOf("not found", "not supported", "does not exist", "invalid model", "unknown model", "no access", "is not available")
    fun isModelError(e: LlmHttpException): Boolean {
        if (e.code != 400 && e.code != 404) return false
        val m = e.message.orEmpty().lowercase()
        return m.contains("model") && MODEL_WORDS.any { m.contains(it) } && !m.contains("thinking") && !m.contains("schema")
    }

    /**
     * Doc 3.7 robustness rule: on a model-name 400/404 retry once with the provider default; on any other 400 retry
     * once without thinking config / JSON schema and remember that per model. [call] gets (model, withThinking, withSchema).
     */
    fun <T> withFallbacks(req: LlmRequest, provider: String, quirks: ModelQuirks, call: (String, Boolean, Boolean) -> Pair<T, String>): T {
        var model = req.model
        var thinking = !quirks.dropThinking(model)
        var schema = req.jsonSchema != null && !quirks.dropSchema(model)
        var triedModel = false
        var triedField = false
        var droppedT = false
        var droppedS = false
        while (true) {
            try {
                val (res, usedModel) = call(model, thinking, schema)
                if (droppedT || droppedS) quirks.remember(usedModel, droppedT, droppedS)
                return res
            } catch (e: LlmHttpException) {
                val def = Models.default(provider)
                if (isModelError(e) && !triedModel && model != def) {
                    AppLog.d("coach: model '$model' rejected, retrying with default '$def'")
                    triedModel = true; model = def
                    thinking = !quirks.dropThinking(model); schema = req.jsonSchema != null && !quirks.dropSchema(model)
                    continue
                }
                if (e.code == 400 && !triedField && (thinking || schema)) {
                    triedField = true
                    val m = e.message.orEmpty().lowercase()
                    val tHit = m.contains("thinking") || m.contains("thought") || m.contains("reasoning")
                    val sHit = m.contains("schema") || m.contains("mime") || m.contains("response_format") || m.contains("responsejson") || m.contains("function calling")
                    val dropT = thinking && (tHit || !sHit)
                    val dropS = schema && (sHit || !tHit)
                    AppLog.d("coach: 400 from $provider, retrying without ${if (dropT) "thinking " else ""}${if (dropS) "schema" else ""}")
                    if (dropT) { thinking = false; droppedT = true }
                    if (dropS) { schema = false; droppedS = true }
                    continue
                }
                throw e
            }
        }
    }
}
