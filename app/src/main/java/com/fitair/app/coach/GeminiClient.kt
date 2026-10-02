package com.fitair.app.coach

import com.fitair.app.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder

class GeminiClient(private val key: String, private val quirks: ModelQuirks) : LlmClient {
    override val provider = "gemini"

    override suspend fun generate(req: LlmRequest): LlmResponse = withContext(Dispatchers.IO) {
        LlmHttp.withFallbacks(req, provider, quirks) { model, withThinking, withSchema ->
            doCall(req, model, withThinking, withSchema) to model
        }
    }

    private fun doCall(req: LlmRequest, model: String, withThinking: Boolean, withSchema: Boolean): LlmResponse {
        val url = "https://generativelanguage.googleapis.com/v1beta/models/${URLEncoder.encode(model, "UTF-8")}:generateContent"
        val decls = JSONArray()
        for (t in req.tools) {
            val d = JSONObject().put("name", t.name).put("description", t.desc)
            if (t.props.isNotEmpty()) {
                val p = JSONObject(); val r = JSONArray()
                t.props.forEach { (n, ty, ds) -> p.put(n, JSONObject().put("type", ty.uppercase()).put("description", ds)); r.put(n) }
                d.put("parameters", JSONObject().put("type", "OBJECT").put("properties", p).put("required", r))
            }
            decls.put(d)
        }
        val contents = JSONArray()
        for (m in req.messages) when (m) {
            is LlmMsg.User -> contents.put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", m.text))))
            is LlmMsg.Assistant -> {
                val parts = if (m.raw != null) JSONArray(m.raw) else JSONArray().also { a ->
                    if (m.text.isNotEmpty()) a.put(JSONObject().put("text", m.text))
                    m.calls.forEach { a.put(JSONObject().put("functionCall", JSONObject().put("name", it.name).put("args", it.args))) }
                }
                contents.put(JSONObject().put("role", "model").put("parts", parts))
            }
            is LlmMsg.ToolResults -> {
                val parts = JSONArray()
                m.results.forEach { r ->
                    val rj = try { JSONObject(r.content) } catch (e: Exception) { JSONObject().put("text", r.content) }
                    parts.put(JSONObject().put("functionResponse", JSONObject().put("name", r.call.name).put("response", JSONObject().put("result", rj))))
                }
                contents.put(JSONObject().put("role", "user").put("parts", parts))
            }
        }
        val gen = JSONObject().put("maxOutputTokens", req.maxOutputTokens)
        if (withThinking && req.thinking != Thinking.Default) gen.put("thinkingConfig", thinkingConfig(model, req.thinking))
        if (withSchema && req.jsonSchema != null) {
            gen.put("responseMimeType", "application/json").put("responseJsonSchema", req.jsonSchema)
        }
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", req.system))))
            .put("contents", contents)
            .put("generationConfig", gen)
        if (req.tools.isNotEmpty()) body.put("tools", JSONArray().put(JSONObject().put("functionDeclarations", decls)))
        if (req.forceFinal) body.put("toolConfig", JSONObject().put("functionCallingConfig", JSONObject().put("mode", "NONE")))

        AppLog.d("coach gemini request: model=$model thinking=${withThinking} schema=${withSchema} final=${req.forceFinal}")
        val resp = LlmHttp.post(url, mapOf("x-goog-api-key" to key), body, "Gemini")
        val cand = resp.optJSONArray("candidates")?.optJSONObject(0)
        if (cand == null) {
            val br = resp.optJSONObject("promptFeedback")?.optString("blockReason").orEmpty()
            throw IOException("Gemini returned no answer" + if (br.isNotEmpty()) " (blocked: $br)" else "")
        }
        val parts = cand.optJSONObject("content")?.optJSONArray("parts") ?: JSONArray()
        val calls = ArrayList<ToolCall>()
        val sb = StringBuilder()
        for (i in 0 until parts.length()) {
            val p = parts.getJSONObject(i)
            val fc = p.optJSONObject("functionCall")
            if (fc != null) {
                calls.add(ToolCall(fc.optString("id").ifEmpty { "call_$i" }, fc.getString("name"), fc.optJSONObject("args") ?: JSONObject()))
            } else if (!p.optBoolean("thought", false) && p.has("text")) sb.append(p.getString("text"))
        }
        val u = resp.optJSONObject("usageMetadata")
        val usage = Usage(u?.optInt("promptTokenCount") ?: 0, u?.optInt("candidatesTokenCount") ?: 0,
            u?.optInt("thoughtsTokenCount") ?: 0, u?.optInt("cachedContentTokenCount") ?: 0)
        val fr = cand.optString("finishReason")
        val finish = when {
            calls.isNotEmpty() -> "tool_calls"
            fr == "MAX_TOKENS" -> "length"
            fr == "STOP" || fr.isEmpty() -> "stop"
            else -> fr.lowercase()
        }
        return LlmResponse(sb.toString().trim(), calls, if (calls.isNotEmpty()) parts.toString() else null, usage, finish, model)
    }

    private fun thinkingConfig(model: String, t: Thinking): JSONObject {
        val v3 = model.startsWith("gemini-3")
        return if (v3) JSONObject().put("thinkingLevel", if (t == Thinking.Off) "minimal" else "low")
        else JSONObject().put("thinkingBudget", if (t == Thinking.Off) 0 else 1024)
    }
}
