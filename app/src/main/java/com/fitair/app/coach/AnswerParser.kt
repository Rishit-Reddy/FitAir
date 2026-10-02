package com.fitair.app.coach

import org.json.JSONArray
import org.json.JSONObject

data class NumberChip(val label: String, val value: String, val ref: String = "")

/**
 * The coach answer contract (doc 2.6). If [plain] is set the model did not return usable JSON and only the text is shown.
 */
data class CoachAnswer(
    val headline: String = "",
    val bullets: List<String> = emptyList(),
    val numbers: List<NumberChip> = emptyList(),
    val detail: String = "",
    val followUps: List<String> = emptyList(),
    val plain: String? = null,
)

/** Pure Kotlin (org.json only). Never returns raw JSON as display text. */
object AnswerParser {
    private const val MAX_BULLETS = 3
    private const val MAX_NUMBERS = 4
    private const val MAX_FOLLOW = 2
    const val UNREADABLE = "I couldn't format that answer. Please try again."

    fun parse(text: String): CoachAnswer {
        val src = stripFences(text.trim())
        if (src.isEmpty()) return CoachAnswer(plain = "")
        val cands = ArrayList<String>()
        cands.add(src)
        val s = src.indexOf('{'); val e = src.lastIndexOf('}')
        if (s >= 0 && e > s && (s > 0 || e < src.length - 1)) cands.add(src.substring(s, e + 1))
        for (c in cands) {
            val o = try { JSONObject(c) } catch (x: Exception) { continue }
            val a = fromObject(o)
            if (a.headline.isNotBlank() || a.bullets.isNotEmpty() || a.detail.isNotBlank()) return a
        }
        if (src.startsWith("{") || src.startsWith("[")) return salvage(src)
        return CoachAnswer(plain = src)
    }

    private fun stripFences(t: String): String {
        if (!t.startsWith("```")) return t
        val body = t.removePrefix("```").substringAfter('\n', "")
        return body.removeSuffix("```").trim()
    }

    private fun strings(a: JSONArray?, max: Int): List<String> {
        if (a == null) return emptyList()
        val out = ArrayList<String>()
        for (i in 0 until a.length()) {
            val s = a.opt(i)?.toString()?.trim().orEmpty()
            if (s.isNotEmpty() && s != "null") out.add(s)
            if (out.size == max) break
        }
        return out
    }

    private fun fromObject(o: JSONObject): CoachAnswer {
        val nums = ArrayList<NumberChip>()
        val na = o.optJSONArray("numbers")
        if (na != null) for (i in 0 until na.length()) {
            val n = na.optJSONObject(i) ?: continue
            val label = n.optString("label").trim(); val value = n.optString("value").trim()
            if (label.isEmpty() && value.isEmpty()) continue
            nums.add(NumberChip(label, value, n.optString("ref").trim()))
            if (nums.size == MAX_NUMBERS) break
        }
        return CoachAnswer(
            headline = o.optString("headline").trim(),
            bullets = strings(o.optJSONArray("bullets"), MAX_BULLETS),
            numbers = nums,
            detail = o.optString("detail").trim(),
            followUps = strings(o.optJSONArray("follow_ups"), MAX_FOLLOW),
        )
    }

    /** Truncated JSON (e.g. MAX_TOKENS): pull out what is complete. */
    private fun salvage(src: String): CoachAnswer {
        fun str(m: MatchResult?) = m?.groupValues?.get(1)?.let { unescape(it) }.orEmpty()
        val head = str(Regex("\"headline\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(src))
        val bulletsBlock = Regex("\"bullets\"\\s*:\\s*\\[(.*?)(\\]|$)", RegexOption.DOT_MATCHES_ALL).find(src)?.groupValues?.get(1).orEmpty()
        val bullets = Regex("\"((?:[^\"\\\\]|\\\\.)*)\"").findAll(bulletsBlock).map { unescape(it.groupValues[1]) }.filter { it.isNotBlank() }.take(MAX_BULLETS).toList()
        if (head.isBlank() && bullets.isEmpty()) return CoachAnswer(plain = UNREADABLE)
        return CoachAnswer(headline = head, bullets = bullets)
    }

    private fun unescape(s: String) = try { JSONArray("[\"$s\"]").getString(0) } catch (e: Exception) { s }

    fun toJson(a: CoachAnswer): String = JSONObject()
        .put("headline", a.headline).put("bullets", JSONArray(a.bullets))
        .put("numbers", JSONArray(a.numbers.map { JSONObject().put("label", it.label).put("value", it.value).put("ref", it.ref) }))
        .put("detail", a.detail).put("follow_ups", JSONArray(a.followUps)).toString()

    fun fromJson(s: String?): CoachAnswer? {
        if (s.isNullOrBlank()) return null
        return try { fromObject(JSONObject(s)) } catch (e: Exception) { null }
    }

    /** Mini-markdown rendering (headline bold, bullets, chips line, detail) for the legacy text view and for model history. */
    fun toMarkdown(a: CoachAnswer): String {
        a.plain?.let { return it }
        val sb = StringBuilder()
        if (a.headline.isNotEmpty()) sb.append("**").append(a.headline).append("**")
        a.bullets.forEach { sb.append("\n- ").append(it) }
        if (a.numbers.isNotEmpty()) sb.append("\n").append(a.numbers.joinToString(" · ") { chipText(it) })
        if (a.detail.isNotEmpty()) sb.append("\n\n").append(a.detail)
        return sb.toString().trim()
    }

    fun chipText(n: NumberChip) = listOf(n.label, n.value).filter { it.isNotEmpty() }.joinToString(" ") +
        if (n.ref.isNotEmpty()) " · ${n.ref}" else ""

    /** Rough word count of the visible answer (headline + bullets), used to check the 70-word target. */
    fun words(a: CoachAnswer) = (listOf(a.headline) + a.bullets).sumOf { it.split(Regex("\\s+")).count { w -> w.isNotEmpty() } }
}
