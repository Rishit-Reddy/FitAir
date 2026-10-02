package com.fitair.app.coach

import com.fitair.app.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

class OpenAiClient(private val key: String, private val quirks: ModelQuirks) : LlmClient {
    override val provider = "openai"

    override suspend fun generate(req: LlmRequest): LlmResponse = withContext(Dispatchers.IO) {
        LlmHttp.withFallbacks(req, provider, quirks) { model, withThinking, withSchema ->
            doCall(req, model, withThinking, withSchema) to model
        }
    }

    private fun isReasoning(model: String) = model.startsWith("gpt-5") || Regex("^o\\d").containsMatchIn(model)

    private fun doCall(req: LlmRequest, model: String, withThinking: Boolean, withSchema: Boolean): LlmResponse {
        val toolArr = JSONArray()
        for (t in req.tools) {
            val p = JSONObject(); val r = JSONArray()
            t.props.forEach { (n, ty, ds) -> p.put(n, JSONObject().put("type", ty).put("description", ds)); r.put(n) }
            toolArr.put(JSONObject().put("type", "function").put("function", JSONObject()
                .put("name", t.name).put("description", t.desc)
                .put("parameters", JSONObject().put("type", "object").put("properties", p).put("required", r))))
        }
        val messages = JSONArray().put(JSONObject().put("role", "system").put("content", req.system))
        for (m in req.messages) when (m) {
            is LlmMsg.User -> messages.put(JSONObject().put("role", "user").put("content", m.text))
            is LlmMsg.Assistant -> if (m.raw != null) messages.put(JSONObject(m.raw))
                else messages.put(JSONObject().put("role", "assistant").put("content", m.text))
            is LlmMsg.ToolResults -> m.results.forEach {
                messages.put(JSONObject().put("role", "tool").put("tool_call_id", it.call.id).put("content", it.content))
            }
        }
        val body = JSONObject().put("model", model).put("messages", messages).put("max_completion_tokens", req.maxOutputTokens)
        if (req.tools.isNotEmpty()) body.put("tools", toolArr)
        if (req.forceFinal) body.put("tool_choice", "none")
        if (withThinking && isReasoning(model) && req.thinking != Thinking.Default) {
            body.put("reasoning_effort", if (req.thinking == Thinking.Off) (if (model.startsWith("gpt-5")) "minimal" else "low") else "low")
        }
        if (withSchema && req.jsonSchema != null) {
            body.put("response_format", JSONObject().put("type", "json_schema").put("json_schema",
                JSONObject().put("name", "coach_answer").put("strict", true).put("schema", req.jsonSchema)))
        }
        AppLog.d("coach openai request: model=$model thinking=$withThinking schema=$withSchema final=${req.forceFinal}")
        val resp = LlmHttp.post("https://api.openai.com/v1/chat/completions", mapOf("Authorization" to "Bearer $key"), body, "OpenAI")
        val choice = resp.optJSONArray("choices")?.optJSONObject(0) ?: throw IOException("OpenAI returned no answer")
        val msg = choice.optJSONObject("message") ?: throw IOException("OpenAI returned no answer")
        val tc = msg.optJSONArray("tool_calls")
        val calls = ArrayList<ToolCall>()
        if (tc != null) for (i in 0 until tc.length()) {
            val c = tc.getJSONObject(i); val fn = c.getJSONObject("function")
            val args = try { JSONObject(fn.optString("arguments").ifBlank { "{}" }) } catch (e: Exception) { JSONObject() }
            calls.add(ToolCall(c.getString("id"), fn.getString("name"), args))
        }
        val text = (if (msg.isNull("content")) "" else msg.optString("content")).trim()
        val u = resp.optJSONObject("usage")
        val usage = Usage(u?.optInt("prompt_tokens") ?: 0, u?.optInt("completion_tokens") ?: 0,
            u?.optJSONObject("completion_tokens_details")?.optInt("reasoning_tokens") ?: 0,
            u?.optJSONObject("prompt_tokens_details")?.optInt("cached_tokens") ?: 0)
        val fr = choice.optString("finish_reason")
        val finish = if (calls.isNotEmpty()) "tool_calls" else if (fr.isEmpty()) "stop" else fr
        return LlmResponse(text, calls, if (calls.isNotEmpty()) msg.toString() else null, usage, finish, model)
    }
}
