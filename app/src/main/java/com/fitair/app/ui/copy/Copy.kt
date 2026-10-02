package com.fitair.app.ui.copy

import com.fitair.app.coach.DayFacts
import com.fitair.app.core.Format
import com.fitair.app.data.dao.Pace
import com.fitair.app.ui.components.Tone
import java.util.Locale

/**
 * A metric in everyday words: [headline] first (at most 48 characters), [detail] second and dimmer.
 * [glyph] is an optional "▲" / "▼" for tiles that show direction against your normal.
 */
class Verdict(val headline: String, val detail: String? = null, val tone: Tone = Tone.Neutral, val glyph: String? = null)

/**
 * The one plain-language layer (docs/PLAN_081.md 3.6). Pure Kotlin, no Android types.
 * Rules: verdict first, number second; compare with "your normal"; no jargon outside Details / What is this?;
 * no alarm words. Stored JSON is never rewritten; this maps from keys, component scores (75 = your normal) and raw values.
 */
object Copy {
    const val OUT_OF_100 = "out of 100 · compared with your own normal"

    // ---- readiness -------------------------------------------------------------------------------------------

    fun readiness(score: Int?): Verdict = when {
        score == null -> Verdict("No readiness yet", "after your sleep syncs")
        score >= 70 -> Verdict("Well recovered", "a hard day is fine", Tone.Good)
        score >= 50 -> Verdict("Partly recovered", "keep it moderate", Tone.Caution)
        else -> Verdict("Not recovered", "take it easy today", Tone.Alert)
    }

    /** One readiness driver, from its component [key] and score (0-100, 75 = your normal). [rawText] only helps the load driver find its ratio. */
    fun driver(key: String, score: Double, rawText: String? = null): Verdict = when (key) {
        "hrv" -> Verdict("Recovery signal is " + band3(score, 80, 60, "strong", "normal", "low") + " for you", null, bandTone(score, 80, 60))
        "resting_hr" -> Verdict("Resting heart rate is " + band3(score, 80, 60, "low", "normal", "high") + " for you", null, bandTone(score, 80, 60))
        "sleep" -> sleepNight(score)
        "load" -> {
            val ratio = rawText?.let { Regex("""ratio\s+(\d+(?:\.\d+)?)""").find(it)?.groupValues?.get(1)?.toDoubleOrNull() }
            if (ratio != null) load(ratio) else Verdict(
                if (score >= 90) "Normal week for you" else "Training load is off your normal", null, if (score >= 90) Tone.Good else Tone.Caution)
        }
        "subjective" -> Verdict("You said you feel " + band3(score, 75, 50, "good", "okay", "rough"), null, bandTone(score, 75, 50))
        else -> Verdict(key.replace('_', ' ').replaceFirstChar { it.uppercase() })
    }

    private fun band3(s: Double, hi: Int, mid: Int, a: String, b: String, c: String) = when { s >= hi -> a; s >= mid -> b; else -> c }
    private fun bandTone(s: Double, hi: Int, mid: Int) = when { s >= hi -> Tone.Good; s >= mid -> Tone.Neutral; else -> Tone.Caution }

    // ---- sleep -----------------------------------------------------------------------------------------------

    /** "Good night" / "OK night" / "Poor night" from the sleep score (75 / 60). */
    fun sleepNight(score: Double?): Verdict = when {
        score == null -> Verdict("")
        score >= 75 -> Verdict("Good night", null, Tone.Good)
        score >= 60 -> Verdict("OK night", null, Tone.Caution)
        else -> Verdict("Poor night", null, Tone.Alert)
    }

    /** Difference to the personal need: "2h 47m less than you need" / "Met your need" / "32m more than you need". Null when unknown. */
    fun needDiff(diffMin: Long?): String? = when {
        diffMin == null -> null
        Math.abs(diffMin) <= 15 -> "Met your need"
        diffMin < 0 -> "${span(-diffMin)} less than you need"
        else -> "${span(diffMin)} more than you need"
    }

    private fun span(min: Long) = if (min >= 60) Format.duration(min) else "${min}m"

    /** 7-night debt, only from 60 minutes: "Short on sleep" with "2h 10m over 7 nights". */
    fun sleepDebt(debtMin: Double?, nights: Int = 7): Verdict? {
        val d = debtMin ?: return null
        if (d < 60) return null
        return Verdict("Short on sleep", "${Format.duration(Math.round(d))} over $nights nights", Tone.Caution)
    }

    /** Collapsed sleep line: "Slept 7h 12m · Good night". */
    fun sleepLine(durationMin: Long, score: Double?): String {
        val v = sleepNight(score).headline
        return "Slept ${Format.duration(durationMin)}" + if (v.isNotEmpty()) " · $v" else ""
    }

    const val SLEEP_WAITING = "Waiting for your sleep data"
    const val SLEEP_NONE = "No sleep recorded last night"

    /** Display names of the four sleep score components, same everywhere. */
    val SLEEP_COMPONENTS = listOf(
        "duration" to "Enough sleep", "efficiency" to "Restful", "restorative" to "Deep + dream sleep", "consistency" to "Same-time sleep",
    )

    // ---- vitals ----------------------------------------------------------------------------------------------

    /**
     * HRV ("Recovery signal") or resting heart rate tile against your normal. [minDelta] is the smallest gap that counts.
     * HRV: higher is better. Resting HR: lower is better.
     */
    fun vital(hrv: Boolean, value: Double, usual: Double?, minDelta: Double): Verdict {
        val unit = if (hrv) "ms" else "bpm"
        val num = "${Math.round(value)} $unit"
        if (usual == null) return Verdict("Not enough history yet", num)
        val d = value - usual
        val detail = "$num · usual ${Math.round(usual)}"
        if (Math.abs(d) < minDelta) return Verdict("Normal for you", detail)
        val up = d > 0
        val good = up == hrv
        val head = if (hrv) (if (up) "Higher than usual" else "Lower than usual") else (if (up) "A bit high" else "Lower than usual")
        return Verdict(head, detail, if (good) Tone.Good else Tone.Caution, if (up) "▲" else "▼")
    }

    // ---- load ------------------------------------------------------------------------------------------------

    /** Week verdict from the acute:chronic ratio (stored as "ratio"). */
    fun load(ratio: Double?): Verdict = when {
        ratio == null -> Verdict("Not enough history yet")
        ratio < 0.8 -> Verdict("Lighter week than usual", null, Tone.Neutral)
        ratio <= 1.3 -> Verdict("Normal week for you", null, Tone.Good)
        ratio <= 1.5 -> Verdict("Harder week than usual", null, Tone.Caution)
        else -> Verdict("Much harder than usual: ease off", null, Tone.Alert)
    }

    /** "Load so far" card: [soFar] against the usual value for this hour. */
    fun loadSoFar(soFar: Double, typicalByNow: Double?, partial: Boolean): Verdict {
        val num = Math.round(soFar).toString()
        val suffix = if (partial) " · partial day" else ""
        if (typicalByNow == null) return Verdict("Building your usual", num + suffix)
        if (typicalByNow < 1.0 && soFar < 1.0) return Verdict("Quiet so far", num + suffix)
        val r = soFar / maxOf(typicalByNow, 1.0)
        val head = when { r < 0.75 -> "Lighter than usual so far"; r <= 1.25 -> "About usual for this time"; else -> "Heavier than usual so far" }
        return Verdict(head, num + suffix)
    }

    // ---- water -----------------------------------------------------------------------------------------------

    fun waterLine(ml: Int, goalMl: Int) = "${litres(ml)} of ${litres(goalMl)} L"

    fun litres(ml: Int): String = String.format(Locale.US, "%.2f", ml / 1000.0).trimEnd('0').trimEnd('.')

    /** Neutral pace wording; never red, no streaks. */
    fun waterPace(pace: Pace, extraMl: Int): String = when {
        pace == Pace.Reached -> "Goal reached"
        extraMl > 0 && pace == Pace.OnPace -> "+${litres(extraMl)} L for a hard day"
        pace == Pace.OnPace -> "On pace"
        else -> "A glass would help"
    }

    // ---- trend / chart notes ---------------------------------------------------------------------------------

    const val BAND_NOTE = "Shaded: your usual range"
    const val BAND_NOTE_DETAIL = "(28-day mean ± 1 SD)"

    /** Headline for a trend value against the band side: -1 below, 0 inside, 1 above. */
    fun trendVerdict(name: String, side: Int?, higherBetter: Boolean): String = when (side) {
        null -> "Not enough history yet"
        0 -> "Normal for you"
        else -> if ((side > 0) == higherBetter) "Better than usual" else if (name == "Resting heart rate") "A bit high" else "Lower than usual"
    }

    // ---- insights --------------------------------------------------------------------------------------------

    fun insight(id: String, title: String): String = when (id) {
        "rhr_elevated" -> "Resting heart rate high 2 days in a row"
        "hrv_low" -> "Recovery signal low last night"
        "sleep_debt" -> "Sleep is catching up on you"
        "acwr_high" -> "Much harder than usual: ease off"
        "acwr_low" -> "Lighter week than usual"
        "strain_pattern" -> "Your body looks under strain"
        "sleep_timing_drift" -> "Your sleep times are drifting"
        else -> title
    }

    // ---- explainers ("What is this?", at most 2 sentences) --------------------------------------------------

    fun explain(key: String): String? = when (key) {
        "readiness" -> "A score out of 100 for how recovered you look today, made from last night's sleep, your recovery signal, resting heart rate and recent load. 75 means your own normal."
        "hrv", "recovery_signal" -> "The tiny variation between heartbeats while you sleep, in milliseconds. Higher than your normal usually means recovered; compare only with yourself."
        "resting_hr" -> "Your heart rate at its calmest, in beats per minute. A few beats above your normal can follow poor sleep, stress, heat or a hard day."
        "sleep" -> "A score out of 100 for last night: enough sleep, how restful, deep and dream sleep, and how regular your times are. It is compared with your own need."
        "sleep_need" -> "The sleep you personally seem to need, learned from your own nights. Falling short builds up as a debt over the last 7 nights."
        "load" -> "How hard your heart worked over the whole day, counted from heart rate whatever app logged it. A scooter ride at a calm heart rate adds almost nothing."
        "subjective" -> "How you said you feel in your check-in, from rough to great. It counts a little, because you know things the band cannot."
        else -> null
    }

    /** Honest limits shown under some explainers, in a separate "Good to know" paragraph. */
    fun explainMore(key: String): String? = when (key) {
        "load" -> "Heat, caffeine, stress and illness raise heart rate without work and can add a little. Wrist heart rate drops out during hard arm movement, and a day with the band off counts as partial. " +
            "It is an estimate of training stress, not calories and not fitness, and the zones are only as good as your max heart rate. The data arrives with the band's sync delay."
        "hrv", "recovery_signal" -> "One low night is common. Look at several days, not one."
        else -> null
    }

    // ---- freshness / header ----------------------------------------------------------------------------------

    fun dataTo(clock: String) = "data to $clock"

    // ---- evening: day summary and wind-down ------------------------------------------------------------------

    /**
     * Deterministic day summary used offline, without a key, or when the model text fails validation.
     * It never contains a number: only words chosen from the facts.
     */
    fun daySummary(f: DayFacts): String {
        if (!f.hasData) return "Not enough data from today yet."
        val reached = f.waterGoalMl > 0 && f.waterMl >= f.waterGoalMl
        val nearly = f.waterGoalMl > 0 && f.waterMl >= f.waterGoalMl * 0.8
        val first = when {
            f.workouts.isNotEmpty() -> "You fitted in some exercise today."
            reached -> "You reached your water goal."
            f.cardio > 0.0 -> "A steady working day."
            else -> "A quiet day."
        }
        val second = when {
            f.workouts.isNotEmpty() && reached -> "Water goal reached too."
            reached -> null
            nearly -> "Water is nearly there."
            f.waterGoalMl > 0 -> "A glass or two more would help."
            else -> null
        }
        return listOfNotNull(first, second, "An early night would help tomorrow.").joinToString(" ")
    }

    /** "For your usual 7h 30m, be in bed by 23:15". */
    fun windDown(needMin: Int, bedMin: Int, short: Boolean): String {
        val m = ((bedMin % 1440) + 1440) % 1440
        val clock = Format.clock(m / 60, m % 60)
        return "For your usual ${Format.duration(needMin.toLong())}, be in bed by $clock" + if (short) " (you're short on sleep)" else ""
    }
}
