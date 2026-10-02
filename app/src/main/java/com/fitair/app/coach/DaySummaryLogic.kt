package com.fitair.app.coach

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** Pure parts of the evening Day summary (docs/PLAN_081.md 7.2): prompt, number validator, reply parsing. No android.* imports. */
/** Today's work window (a shift from a work-flagged calendar feed) as the summaries may mention it. Stats are null when heart-rate coverage was too low. */
class WorkFact(
    val startClock: String, val endClock: String, val hours: Double, val avgHr: Int?, val avgPctHrr: Int?, val zone2Min: Int?, val load: Int?,
) {
    fun toJson(): JSONObject = JSONObject().put("from", startClock).put("to", endClock).put("hours", hours).also { o ->
        avgHr?.let { o.put("avg_hr", it) }; avgPctHrr?.let { o.put("avg_percent_of_heart_rate_reserve", it) }
        zone2Min?.let { o.put("minutes_zone2_plus", it) }; load?.let { o.put("cardio_load", it) }
    }

    companion object {
        const val NOTE = "physical work shift (cycling deliveries), not a workout; intensity is relative to his own heart-rate reserve"

        fun listJson(w: List<WorkFact>): JSONObject = JSONObject().put("note", NOTE).put("windows", JSONArray(w.map { it.toJson() }))

        /** Numbers (and unit variants) a text may quote about [w]. Clock times are handled by the clock validator. */
        fun numbers(w: List<WorkFact>): List<Double> {
            val out = ArrayList<Double>()
            fun add(v: Double?) { if (v != null) { out.add(v); out.add(Math.rint(v)); out.add(Math.round(v * 10) / 10.0) } }
            for (x in w) {
                add(x.hours); add(x.avgHr?.toDouble()); add(x.avgPctHrr?.toDouble()); add(x.load?.toDouble())
                x.zone2Min?.let { add(it.toDouble()); add((it / 60).toDouble()); add((it % 60).toDouble()); add(it / 60.0) }
                add(Math.floor(x.hours)); add(Math.round((x.hours - Math.floor(x.hours)) * 60).toDouble())
            }
            return out
        }
    }
}

object DaySummaryLogic {
    /** The user's own typical values, passed to the model so it can compare "with his usual". */
    data class Usual(val steps: Long?, val cardio: Double?)

    const val MAX_WORDS = 60
    const val MAX_TOKENS = 120
    const val TIMEOUT_MS = 8_000L

    val schema: JSONObject get() = JSONObject(
        """{"type":"object","additionalProperties":false,"required":["text"],"properties":{"text":{"type":"string","description":"2-3 plain sentences, at most 60 words"}}}"""
    )

    val system = """
You write a short end-of-day note for one person from the JSON facts you are given. Reply with ONE JSON object: {"text": "..."}.
Rules: 2-3 plain sentences, at most 60 words. Lead with something that went well. Offer at most one "you could have..." and only if the facts support it.
Compare with his own "usual" values, never with norms. Use at most two numbers and copy them exactly from the facts; do not mention clock times, dates or any other number.
No guilt, no exclamation marks, no medical claims or diagnoses, no new plans for tomorrow beyond one short sentence. If "partial_day" is true, say the numbers may be incomplete.
""".trim()

    fun factsJson(f: DayFacts, u: Usual): JSONObject {
        fun r1(x: Double) = Math.round(x * 10) / 10.0
        val o = JSONObject()
            .put("steps", f.steps).put("distance_km", r1(f.distanceM / 1000.0)).put("cardio_load", Math.round(f.cardio))
            .put("vigorous_or_peak_minutes", f.zoneMin)
            .put("water_litres", r1(f.waterMl / 1000.0)).put("water_goal_litres", r1(f.waterGoalMl / 1000.0))
            .put("workouts", JSONArray(f.workouts)).put("partial_day", f.partial)
        f.rhr?.let { o.put("resting_hr", Math.round(it)) }
        f.hrAvg?.let { o.put("avg_hr", Math.round(it)) }
        f.hrMax?.let { o.put("max_hr", Math.round(it)) }
        if (f.work.isNotEmpty()) o.put("work_shift", WorkFact.listJson(f.work))
        val usual = JSONObject()
        u.steps?.let { usual.put("steps", it) }
        u.cardio?.let { usual.put("cardio_load", Math.round(it)) }
        o.put("usual", usual)
        return o
    }

    private val NUM = Regex("""(\d{1,3}(?:,\d{3})+|\d+)(?:\.(\d+))?\s*(k\b)?""", RegexOption.IGNORE_CASE)

    class Num(val value: Double, val tol: Double)

    /** Every number in [text]: "8,234" -> 8234, "2.1" -> 2.1, "10.2k" -> 10200 (tolerance 50), "1h 05" -> 1 and 5. */
    fun numbersIn(text: String): List<Num> = NUM.findAll(text).map { m ->
        val whole = m.groupValues[1].replace(",", "")
        val frac = m.groupValues[2]
        val v = (if (frac.isEmpty()) whole else "$whole.$frac").toDouble()
        if (m.groupValues[3].isNotEmpty()) Num(v * 1000, 50.5) else Num(v, 0.0501)
    }.toList()

    /** All values the text may quote: each fact value in every sensible rounding / unit, plus numbers inside workout labels. */
    fun allowed(f: DayFacts, u: Usual): List<Double> {
        val out = ArrayList<Double>()
        fun add(v: Double?) {
            if (v == null) return
            out.add(v); out.add(Math.rint(v)); out.add(Math.round(v * 10) / 10.0)
        }
        add(f.steps.toDouble()); add(f.distanceM); add(f.distanceM / 1000.0); add(f.cardio); add(f.zoneMin.toDouble())
        add(f.waterMl.toDouble()); add(f.waterMl / 1000.0); add(f.waterGoalMl.toDouble()); add(f.waterGoalMl / 1000.0)
        add(f.rhr); add(f.hrAvg); add(f.hrMax)
        add(u.steps?.toDouble()); add(u.cardio)
        WorkFact.numbers(f.work).forEach { out.add(it) }
        for (w in f.workouts) NUM.findAll(w).forEach { m ->
            val frac = m.groupValues[2]
            out.add((m.groupValues[1].replace(",", "") + if (frac.isEmpty()) "" else ".$frac").toDouble())
        }
        return out
    }

    /** True when every number in [text] matches an allowed value. Digits written as words ("two") are not checked. */
    fun numbersValid(text: String, allowed: List<Double>): Boolean =
        numbersIn(text).all { n -> allowed.any { Math.abs(it - n.value) <= n.tol } }

    internal val BANNED = Regex("diagnos|disease|infection|illness|medicat|doctor|clinic|symptom|!", RegexOption.IGNORE_CASE)

    /** The full acceptance test for a model reply: non-empty, <= 60 words, no exclamation / medical words, only known numbers. */
    fun accept(text: String?, allowed: List<Double>): Boolean {
        if (text.isNullOrBlank()) return false
        val words = text.trim().split(Regex("\\s+")).size
        if (words > MAX_WORDS + 10) return false
        if (BANNED.containsMatchIn(text)) return false
        return numbersValid(text, allowed)
    }

    /** {"text": "..."} -> the text; null on any schema error. */
    fun parseReply(raw: String?): String? = try {
        val s = raw?.trim()?.removePrefix("```json")?.removePrefix("```")?.removeSuffix("```")?.trim()
        if (s.isNullOrEmpty()) null else JSONObject(s).optString("text").trim().ifEmpty { null }
    } catch (e: Exception) { null }

    fun userMessage(facts: JSONObject, variant: Int): String =
        facts.toString() + if (variant > 0) "\nWrite it differently from before (variation $variant)." else ""

    fun fmtL(ml: Int) = String.format(Locale.US, "%.1f", ml / 1000.0)
}
