package com.fitair.app.coach

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Pure parts of the Today summary (docs/PLAN_090 6.2): the change hash, the allowed numbers, the strict validator, reply parsing,
 * the prompt and the call-cap rule. No android.* imports. Number matching reuses [DaySummaryLogic] (same tolerances).
 */
object TodayBriefLogic {
    const val BULLETS = 3
    const val MAX_CHARS = 90
    const val MAX_CALLS_PER_DAY = 6
    const val MAX_TOKENS = 160
    const val TIMEOUT_MS = 8_000L
    /** A template that was stored only because a call failed is replaced by a new try after this long. */
    const val RETRY_TEMPLATE_AFTER_MS = 30 * 60_000L
    const val TASK = "today_brief"

    // ---- change hash --------------------------------------------------------------------------------------------

    private fun bucket(v: Number?, size: Int): String = v?.let { Math.floorDiv(Math.round(it.toDouble()), size.toLong()).toString() } ?: "-"
    private fun eventId(e: BriefEvent?): String = e?.let { it.title + "@" + it.startClock } ?: "-"

    /**
     * Mode + readiness/5 + sleep minutes/15 + load/10 + water/250 ml + next event + insight set (+ tomorrow's first event, which the
     * evening bullets name). Small moves stay inside one bucket, so they never trigger a new call.
     */
    fun hash(f: BriefFacts): String = (listOf(
        f.mode.name, bucket(f.readiness, 5), bucket(f.sleepAsleepMin, 15), bucket(f.loadSoFar, 10), bucket(f.waterMl, 250),
        eventId(f.nextEvent), f.insightIds.sorted().joinToString("+").ifEmpty { "-" }, eventId(f.tomorrowFirst),
    ) + (if (f.work.isEmpty()) emptyList() else listOf("w" + f.work.joinToString(",") { it.startClock + "-" + it.endClock + "/" + bucket(it.avgPctHrr, 5) }))).joinToString("|")

    // ---- what to do on open -------------------------------------------------------------------------------------

    class Cached(val hash: String, val source: String, val createdMs: Long)

    enum class Plan { UseCache, Generate, Template }

    /**
     * [cached] = the stored row for (date, part). Matching hash: use it (a template is retried once after 30 minutes when a key exists
     * and the cap allows). Different hash or nothing stored: ask the model when there is a key and calls are left, else rules.
     */
    fun plan(cached: Cached?, hash: String, hasKey: Boolean, callsToday: Int, nowMs: Long): Plan {
        val canCall = hasKey && callsToday < MAX_CALLS_PER_DAY
        if (cached != null && cached.hash == hash) {
            val retry = cached.source == "template" && nowMs - cached.createdMs > RETRY_TEMPLATE_AFTER_MS && canCall
            return if (retry) Plan.Generate else Plan.UseCache
        }
        return if (canCall) Plan.Generate else Plan.Template
    }

    // ---- allowed numbers ----------------------------------------------------------------------------------------

    private fun clockOf(s: String): String? = Regex("""^(\d{1,2}):(\d{2})$""").find(s.trim())?.let { "%02d:%s".format(it.groupValues[1].toInt(), it.groupValues[2]) }

    /** Clock times the text may quote: event starts, bedtime, and the current time (exact and rounded down to the hour). */
    fun allowedClocks(f: BriefFacts, nowClock: String?): Set<String> {
        val out = HashSet<String>()
        (listOfNotNull(f.nextEvent?.startClock, f.tomorrowFirst?.startClock, f.bedtime, nowClock) + f.work.flatMap { listOf(it.startClock, it.endClock) }).forEach { c -> clockOf(c)?.let { out.add(it) } }
        nowClock?.let { c -> clockOf(c)?.let { out.add(it.substring(0, 2) + ":00") } }
        return out
    }

    /** Every value the text may quote, in each sensible rounding / unit. */
    fun allowedNumbers(f: BriefFacts): List<Double> {
        val out = ArrayList<Double>()
        fun add(v: Double?) {
            if (v == null) return
            out.add(v); out.add(Math.rint(v)); out.add(Math.round(v * 10) / 10.0); out.add(Math.round(v * 100) / 100.0)
        }
        fun add(v: Int?) = add(v?.toDouble())
        fun duration(min: Int?) {
            if (min == null) return
            val a = Math.abs(min)
            add(a); add(a / 60); add(a % 60); add(a / 60.0)
        }
        fun litres(ml: Int?) { if (ml != null) { add(ml); add(ml / 1000.0) } }
        add(f.readiness)
        duration(f.sleepAsleepMin); duration(f.sleepNeedMin); add(f.sleepScore); duration(f.sleepDebtMin)
        if (f.sleepAsleepMin != null && f.sleepNeedMin != null) duration(f.sleepNeedMin - f.sleepAsleepMin)
        add(f.hrv); add(f.hrvUsual); add(f.rhr); add(f.rhrUsual)
        add(f.loadSoFar); add(f.loadTypicalByNow); add(f.loadRatio); add(f.zoneMin)
        litres(f.waterMl); litres(f.waterGoalMl)
        if (f.waterMl != null && f.waterGoalMl != null && f.waterGoalMl > f.waterMl) litres(f.waterGoalMl - f.waterMl)
        f.steps?.let { add(it.toDouble()); add(it / 1000.0); out.add(Math.round(it / 100.0) * 100.0) }
        add(f.distanceKm); add(f.workouts)
        for (e in listOfNotNull(f.nextEvent, f.tomorrowFirst)) {
            duration(e.minutesAway)
            DaySummaryLogic.numbersIn(e.title).forEach { out.add(it.value) }
        }
        duration(f.bedtimeForMin)
        WorkFact.numbers(f.work).forEach { out.add(it) }
        return out
    }

    // ---- validator ----------------------------------------------------------------------------------------------

    private val FORBIDDEN = listOf(Regex("""\bSD\b"""), Regex("baseline", RegexOption.IGNORE_CASE), Regex("""\bz\b""", RegexOption.IGNORE_CASE),
        Regex("TRIMP", RegexOption.IGNORE_CASE), Regex("ACWR", RegexOption.IGNORE_CASE))
    private val ILLNESS = Regex("illness", RegexOption.IGNORE_CASE)
    private val CLOCK = Regex("""\b(\d{1,2}):(\d{2})\b""")

    /** Why [bullets] cannot be shown, or null when they pass. */
    fun problem(bullets: List<String>?, allowed: List<Double>, clocks: Set<String>): String? {
        if (bullets == null || bullets.size != BULLETS) return "need exactly $BULLETS bullets"
        for (b in bullets) {
            val t = b.trim()
            if (t.isEmpty()) return "empty bullet"
            if (t.length > MAX_CHARS) return "bullet longer than $MAX_CHARS characters"
            if (t.contains('!')) return "exclamation mark"
            if (FORBIDDEN.any { it.containsMatchIn(t) }) return "forbidden word"
            // "illness" is allowed only when hedged with "maybe"; every other medical word is always rejected
            val illnessOk = ILLNESS.containsMatchIn(t) && t.contains("maybe", ignoreCase = true)
            if (DaySummaryLogic.BANNED.containsMatchIn(if (illnessOk) ILLNESS.replace(t, " ") else t)) return "medical word"
            if (ILLNESS.containsMatchIn(t) && !illnessOk) return "medical word"
            var rest = t
            for (m in CLOCK.findAll(t)) {
                val c = "%02d:%s".format(m.groupValues[1].toInt(), m.groupValues[2])
                if (c !in clocks) return "unknown clock time"
            }
            rest = CLOCK.replace(rest, " ")
            if (!DaySummaryLogic.numbersValid(rest, allowed)) return "unknown number"
        }
        return null
    }

    fun accept(bullets: List<String>?, f: BriefFacts, nowClock: String?): Boolean =
        problem(bullets, allowedNumbers(f), allowedClocks(f, nowClock)) == null

    // ---- model I/O ----------------------------------------------------------------------------------------------

    val schema: JSONObject get() = JSONObject(
        """{"type":"object","required":["bullets"],"properties":{"bullets":{"type":"array","minItems":3,"maxItems":3,"items":{"type":"string","description":"one plain sentence, at most 90 characters"}}}}"""
    )

    val system = """
You write the 3-bullet summary at the top of a personal health app's Today screen, from the JSON facts you are given.
Reply with ONE JSON object: {"bullets": ["...", "...", "..."]}. Exactly 3 bullets, each one plain sentence of at most 90 characters.
Bullet 1, 2 and 3 cover what "focus" lists, in that order, using only facts that are present. Skip a fact that is missing; never invent one.
Copy every number and clock time exactly from the facts; do not add any other number, percentage or time.
Compare only with his own usual values, never with norms. Lead with what went well; at most one gentle "could". No guilt, no exclamation marks,
no medical claims, no diagnoses, no plans beyond what the facts already say. Do not use the words SD, baseline, z-score, TRIMP or ACWR.
If "partial_day" is true, say the numbers may be incomplete in one bullet.
""".trim()

    private fun focus(mode: com.fitair.app.ui.today.Mode): String = when (mode) {
        com.fitair.app.ui.today.Mode.Morning -> "1 recovery verdict and its main driver; 2 sleep against his need; 3 the first event and what the readiness verdict says about the day"
        com.fitair.app.ui.today.Mode.Day -> "1 load so far against his usual by now; 2 water against the pace; 3 the next event or free time"
        com.fitair.app.ui.today.Mode.Evening -> "1 how the day went (load against usual, workouts); 2 a water or steps fact; 3 tomorrow's first event and bedtime"
    }

    private fun r1(x: Double) = Math.round(x * 10) / 10.0

    /** The JSON sent to the model: only values that are known, in plain units, about 300 tokens at most. */
    fun factsJson(f: BriefFacts, nowClock: String?): JSONObject {
        val o = JSONObject().put("mode", f.mode.name.lowercase()).put("focus", focus(f.mode))
        nowClock?.let { o.put("now", it) }
        f.readiness?.let { o.put("readiness_out_of_100", it) }
        f.verdictKey?.let { o.put("readiness_verdict", when (it) { "well" -> "well recovered"; "partly" -> "partly recovered"; else -> "not recovered" }) }
        f.driverKey?.let { o.put("main_driver", it.replace('_', ' ')) }
        val sleep = JSONObject()
        f.sleepAsleepMin?.let { sleep.put("asleep_min", it) }; f.sleepNeedMin?.let { sleep.put("need_min", it) }
        f.sleepScore?.let { sleep.put("score", it) }; f.sleepDebtMin?.let { sleep.put("debt_min", it) }
        if (sleep.length() > 0) o.put("sleep", sleep)
        val vit = JSONObject()
        f.hrv?.let { vit.put("recovery_signal_ms", it) }; f.hrvUsual?.let { vit.put("recovery_signal_usual_ms", it) }
        f.rhr?.let { vit.put("resting_hr", it) }; f.rhrUsual?.let { vit.put("resting_hr_usual", it) }
        if (vit.length() > 0) o.put("vitals", vit)
        val load = JSONObject()
        f.loadSoFar?.let { load.put("so_far", it) }; f.loadTypicalByNow?.let { load.put("usual_by_now", it) }
        f.loadRatio?.let { load.put("week_vs_usual_ratio", r1(it)) }; f.zoneMin?.let { load.put("hard_minutes", it) }
        if (load.length() > 0) o.put("cardio_load", load)
        if (f.waterMl != null) o.put("water", JSONObject().put("litres", r1(f.waterMl / 1000.0)).also { w ->
            f.waterGoalMl?.let { w.put("goal_litres", r1(it / 1000.0)) }; f.waterPace?.let { w.put("pace", it.replace('_', ' ')) } })
        f.steps?.let { o.put("steps", it) }; f.distanceKm?.let { o.put("distance_km", r1(it)) }
        if (f.workouts > 0) o.put("workouts", f.workouts)
        fun ev(e: BriefEvent) = JSONObject().put("title", e.title).put("at", e.startClock).also { j -> e.minutesAway?.let { j.put("in_min", it) } }
        f.nextEvent?.let { o.put("next_event", ev(it)) }
        f.tomorrowFirst?.let { o.put("tomorrow_first_event", ev(it)) }
        f.bedtime?.let { b -> o.put("bedtime", JSONObject().put("at", b).also { j -> f.bedtimeForMin?.let { j.put("for_min", it) } }) }
        if (f.insightIds.isNotEmpty()) o.put("notable", JSONArray(f.insightIds.map { it.replace('_', ' ') }))
        if (f.work.isNotEmpty()) o.put("work_shift", WorkFact.listJson(f.work))
        o.put("partial_day", f.partial)
        return o
    }

    /** {"bullets": [...]} -> the strings; null on any schema error. */
    fun parseReply(raw: String?): List<String>? = try {
        val s = raw?.trim()?.removePrefix("```json")?.removePrefix("```")?.removeSuffix("```")?.trim()
        if (s.isNullOrEmpty()) null else JSONObject(s).optJSONArray("bullets")?.let { a -> (0 until a.length()).map { a.optString(it).trim() } }
    } catch (e: Exception) { null }

    /** Local clock "14:05" from hour and minute. */
    fun clock(h: Int, m: Int): String = String.format(Locale.US, "%02d:%02d", h, m)
}
