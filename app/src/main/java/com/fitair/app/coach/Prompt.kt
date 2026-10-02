package com.fitair.app.coach

import org.json.JSONArray
import org.json.JSONObject

/** Builds the system prompt within the budgets of doc 3.7 (tokens approximated as chars/4). Pure Kotlin. */
object Prompt {
    const val SYSTEM_BUDGET_TOKENS = 900
    const val CONTEXT_BUDGET_TOKENS = 1200
    const val TOTAL_WARN_TOKENS = 12_000

    fun tokens(s: String) = (s.length + 3) / 4

    /** JSON schema of the answer. All fields required so it is valid for OpenAI strict mode; empty = absent. */
    val answerSchema: JSONObject get() = JSONObject(
        """{"type":"object","additionalProperties":false,
"required":["headline","bullets","numbers","detail","follow_ups"],
"properties":{
"headline":{"type":"string","description":"The verdict in one short sentence"},
"bullets":{"type":"array","maxItems":3,"items":{"type":"string"}},
"numbers":{"type":"array","maxItems":4,"items":{"type":"object","additionalProperties":false,"required":["label","value","ref"],
 "properties":{"label":{"type":"string"},"value":{"type":"string"},"ref":{"type":"string","description":"baseline or comparison, or empty"}}}},
"detail":{"type":"string","description":"Longer explanation; empty unless depth is long"},
"follow_ups":{"type":"array","maxItems":2,"items":{"type":"string"}}}}"""
    )

    /** Role, answer contract and metric glossary. Must stay <= SYSTEM_BUDGET_TOKENS. */
    fun rules(nowText: String, zoneId: String, today: String, weekday: String, long: Boolean): String = """
You are FitAir Coach for one user: a Fitbit Air wearer and data scientist who likes numbers. You give no medical diagnosis; suggest a clinician only if symptoms are mentioned.
Now: $nowText ($zoneId). Today is $today ($weekday). Use this timezone for dates; times from tools are epoch ms UTC.

ANSWER CONTRACT: reply with ONE JSON object and nothing else: {"headline","bullets","numbers","detail","follow_ups"}.
- headline: the verdict, one sentence. bullets: at most 3 short lines. numbers: at most 4 {label,value,ref} chips (ref = baseline/comparison or "").
- ${if (long) "Depth is LONG: the user asked for more. Keep headline/bullets as the summary and put the fuller explanation in detail (up to 220 words, still concrete)." else "Total visible text (headline + bullets) 70 words or less. detail must be an empty string. Lead with the verdict, numbers over adjectives, one actionable suggestion. No preamble, no restating the question, no disclaimers."}
- follow_ups: at most 2 short questions the user might tap next, or [].
- get_agenda gives the user's calendar and free gaps (titles may be redacted as "Busy"); use it to suggest workout timing.
- Use tools for specifics instead of guessing; never invent numbers. If data is missing or a tool fails, say so in the headline.

GLOSSARY (app's own metrics, not clinical): readiness 0-100 from sleep score, HRV, resting HR and load (get_readiness / get_daily_metrics give components); sleep_score from duration, efficiency and stages vs need; TRIMP = HR-based session load; acute/chronic load = 7/28-day EWMA of TRIMP; ACWR = acute/chronic (above 1.5 = spike, soft flag only); baselines = 28-day mean +- SD; sleep debt in minutes vs need.
""".trim()

    /** Context block: last 7 days table + today's readiness drivers. Must stay <= CONTEXT_BUDGET_TOKENS. */
    fun context(days: List<JSONObject>?, today: String, error: String? = null): String {
        if (days == null) return "DATA UNAVAILABLE: the phone's database could not be read (${error ?: "unknown"}). Tell the user their data is currently unavailable; do not guess numbers."
        fun n(o: JSONObject, k: String, digits: Int = 0): String {
            if (o.isNull(k) || !o.has(k)) return "-"
            val d = o.optDouble(k, Double.NaN)
            if (d.isNaN()) return "-"
            return if (digits == 0) Math.round(d).toString() else String.format(java.util.Locale.US, "%.${digits}f", d)
        }
        val sb = StringBuilder("LAST 7 DAYS (date, readiness, sleep h, HRV ms, RHR bpm, steps, TRIMP load):\n")
        for (o in days.takeLast(7)) {
            val sleepH = if (o.isNull("sleep_min") || !o.has("sleep_min")) "-" else String.format(java.util.Locale.US, "%.1f", o.optDouble("sleep_min") / 60.0)
            sb.append(o.optString("date")).append(' ').append(n(o, "readiness")).append(' ').append(sleepH).append(' ')
                .append(n(o, "hrv")).append(' ').append(n(o, "rhr")).append(' ').append(n(o, "steps")).append(' ').append(n(o, "load_trimp")).append('\n')
        }
        val todayRow = days.lastOrNull { it.optString("date") == today }
        val rb = todayRow?.opt("readiness_breakdown")
        if (rb != null && rb != JSONObject.NULL) sb.append("TODAY READINESS DETAIL: ").append(rb.toString().take(1500)).append('\n')
        return clamp(sb.toString().trimEnd(), CONTEXT_BUDGET_TOKENS)
    }

    /** Context first (stable prefix for implicit caching), then the rules. */
    fun system(contextBlock: String, rules: String): String = contextBlock.trim() + "\n\n" + clamp(rules, SYSTEM_BUDGET_TOKENS)

    private fun clamp(s: String, budgetTokens: Int): String = if (tokens(s) <= budgetTokens) s else s.take(budgetTokens * 4)
}
