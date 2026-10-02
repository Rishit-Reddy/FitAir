package com.fitair.app.ui.copy

import com.fitair.app.coach.DayFacts
import com.fitair.app.coach.BriefEvent
import com.fitair.app.coach.BriefFacts
import com.fitair.app.core.Format
import com.fitair.app.data.metrics.MetricStats
import com.fitair.app.data.dao.Pace
import com.fitair.app.ui.components.Chip
import com.fitair.app.ui.components.Tiers
import com.fitair.app.ui.components.Tone
import com.fitair.app.ui.today.Mode
import com.fitair.app.ui.trends.Band
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

    // ---- 0.9.0 status chips (docs/PLAN_090 section 5): dot + words, colour only against his own normal ----------

    const val BAND_MIN_DAYS = 14

    fun chipLearning(days: Int) = Chip("Learning your normal · ${days.coerceIn(0, BAND_MIN_DAYS - 1)}/$BAND_MIN_DAYS", Tone.Neutral, "Learning ${days.coerceIn(0, BAND_MIN_DAYS - 1)}/$BAND_MIN_DAYS")

    val CHIP_NO_DATA = Chip("No data", Tone.Neutral)

    fun chipReadiness(score: Int?): Chip? = when {
        score == null -> null
        score >= 70 -> Chip("Well recovered", Tone.Good, "Recovered")
        score >= 50 -> Chip("Partly recovered", Tone.Caution, "Partly")
        else -> Chip("Not recovered", Tone.Alert, "Low")
    }

    /** Sleep against the personal need: "Need met" / "45m short" / "1h 30m short". */
    fun chipSleep(asleepMin: Double?, needMin: Double?): Chip? {
        if (asleepMin == null || needMin == null) return null
        val tone = Tiers.duration(asleepMin, needMin)
        if (tone == Tone.Good) return Chip("Need met", Tone.Good)
        val gap = Format.hm(Math.round(needMin - asleepMin))
        return Chip("$gap short", tone, "−$gap")
    }

    /** Resting heart rate against his usual range (A's tone edges: +1 SD amber, +2 SD red; below = good). */
    fun chipResting(v: Double?, b: Band?, bandDays: Int): Chip? {
        if (v == null) return null
        if (b == null || bandDays < BAND_MIN_DAYS) return chipLearning(bandDays)
        return when (MetricStats.rhrTone(v, b)) {
            Tone.Alert -> Chip("High for you", Tone.Alert, "High")
            Tone.Caution -> Chip("A bit high", Tone.Caution, "A bit high")
            else -> if (v < b.lo) Chip("Low", Tone.Good) else Chip("Usual for you", Tone.Good, "Usual")
        }
    }

    /** Recovery signal against his usual range (-1 SD amber, -2 SD red; above = good). */
    fun chipHrv(v: Double?, b: Band?, bandDays: Int): Chip? {
        if (v == null) return null
        if (b == null || bandDays < BAND_MIN_DAYS) return chipLearning(bandDays)
        return when (MetricStats.hrvTone(v, b)) {
            Tone.Alert -> Chip("Low for you", Tone.Alert, "Low")
            Tone.Caution -> Chip("Lower than usual", Tone.Caution, "Lower")
            else -> if (v > b.hi) Chip("Strong", Tone.Good) else Chip("Usual for you", Tone.Good, "Usual")
        }
    }

    /** Week chip from the acute:chronic ratio; the words come from [load] but shorter. */
    fun chipLoad(ratio: Double?): Chip? = when {
        ratio == null -> null
        ratio < 0.8 -> Chip("Lighter week", Tone.Neutral, "Lighter")
        ratio <= 1.3 -> Chip("Normal week", Tone.Good, "Normal")
        ratio <= 1.5 -> Chip("Harder week", Tone.Caution, "Harder")
        else -> Chip("Much harder", Tone.Alert, "Much more")
    }

    val CHIP_PARTIAL = Chip("Partial day", Tone.Neutral, "Partial")

    private val ZONE_NAMES = listOf("Resting", "Light", "Moderate", "Vigorous", "Peak")

    /** Zone of the latest heart reading from the four zone start thresholds (Light, Moderate, Vigorous, Peak). */
    fun heartZone(bpm: Int?, zoneBpm: FloatArray?): String? {
        if (bpm == null || zoneBpm == null || zoneBpm.size < 4) return null
        return ZONE_NAMES[MetricStats.zoneIndex(bpm.toDouble(), zoneBpm)]
    }

    fun chipHeart(bpm: Int?, zoneBpm: FloatArray?): Chip? = heartZone(bpm, zoneBpm)?.let { Chip(it, Tone.Neutral) }

    /** Neutral chip of a total: "7-day avg 2,310", "This week 82 km". */
    fun chipNeutral(text: String?): Chip? = text?.let { Chip(it, Tone.Neutral) }

    /** Weight: "Down 3.0 kg since 12 Sep" (neutral). */
    fun chipWeight(deltaKg: Double, sinceLabel: String): Chip? {
        if (Math.abs(deltaKg) < 0.05) return Chip("Steady since $sinceLabel", Tone.Neutral)
        return Chip((if (deltaKg < 0) "Down " else "Up ") + String.format(Locale.US, "%.1f", Math.abs(deltaKg)) + " kg since $sinceLabel", Tone.Neutral)
    }

    /** Sentence for TalkBack: "Resting heart rate, 53 beats per minute, in your usual range. Opens details." */
    fun cardA11y(title: String, valueWords: String?, chip: Chip?, stale: Boolean = false): String {
        val v = valueWords ?: "no data yet"
        val c = chip?.let { ", " + it.text.replace(" · ", ", ").replaceFirstChar { ch -> ch.lowercase() } } ?: ""
        return "$title, $v$c${if (stale) ", not up to date" else ""}. Opens details."
    }

    /** Units in words for TalkBack. */
    fun unitWords(unit: String?): String = when (unit) {
        "bpm" -> "beats per minute"; "ms" -> "milliseconds"; "kcal" -> "kilocalories"; "km" -> "kilometres"; "kg" -> "kilograms"
        "L" -> "litres"; "/100" -> "out of 100"; null, "" -> ""; else -> unit
    }

    // ---- 0.9.0 summary bullets (docs/PLAN_090 6.2): the rules text, always exactly three, each at most 90 characters -------------

    const val BULLET_MAX = 90

    private fun cap(s: String): String =
        if (s.length <= BULLET_MAX) s else s.take(BULLET_MAX - 1).trimEnd(' ', ',', '.', ';', ':').let { it.substringBeforeLast(' ', it) } + "…"

    private fun driverPhrase(key: String?) = when (key) {
        "hrv" -> "your recovery signal"; "resting_hr" -> "your resting heart rate"; "sleep" -> "your sleep"
        "load" -> "recent training load"; "subjective" -> "how you said you feel"; else -> null
    }

    private fun away(e: BriefEvent): String {
        val m = e.minutesAway
        return when { m == null -> ""; m <= 0 -> ", now"; else -> ", in " + Format.hm(m.toLong()) }
    }

    /** Three plain bullets for [mode] from [f]; the model only rewords these facts. Pure, no network. */
    fun brief(mode: Mode, f: BriefFacts): List<String> = when (mode) {
        Mode.Morning -> listOf(briefRecovery(f), briefSleep(f), briefShape(f))
        Mode.Day -> listOf(briefLoad(f), briefWater(f), briefNext(f))
        Mode.Evening -> listOf(briefDayWent(f), briefEveningFact(f), briefTomorrow(f))
    }.map { cap(it) }

    private fun briefRecovery(f: BriefFacts): String {
        val v = readiness(f.readiness)
        if (f.readiness == null) return "Readiness appears once your sleep has synced."
        val d = driverPhrase(f.driverKey)
        return "${v.headline} at ${f.readiness}" + if (d != null) ", mostly from $d." else "."
    }

    private fun briefSleep(f: BriefFacts): String {
        val a = f.sleepAsleepMin ?: return "No sleep recorded for last night yet."
        val need = f.sleepNeedMin
        val diff = need?.let { a - it }
        val head = "Slept ${Format.hm(a.toLong())}"
        val tail = when {
            diff == null -> "."
            Math.abs(diff) <= 15 -> ", your need is met."
            diff < 0 -> ", ${Format.hm((-diff).toLong())} short of your need."
            else -> ", ${Format.hm(diff.toLong())} more than you need."
        }
        val debt = f.sleepDebtMin?.takeIf { it >= 60 }?.let { " Short over the week: ${Format.hm(it.toLong())}." } ?: ""
        return head + tail + debt
    }

    private fun briefShape(f: BriefFacts): String {
        val hint = readiness(f.readiness).detail?.replaceFirstChar { it.uppercase() }?.let { "$it." }
        val e = f.nextEvent
        if (e == null) return "Nothing on your calendar yet." + (hint?.let { " $it" } ?: "")
        val base = "First up: ${e.title} at ${e.startClock}."
        return if (hint != null && base.length + 1 + hint.length <= BULLET_MAX) "$base $hint" else base
    }

    private fun briefLoad(f: BriefFacts): String {
        val so = f.loadSoFar ?: return "No load recorded yet today."
        val v = loadSoFar(so.toDouble(), f.loadTypicalByNow?.toDouble(), f.partial)
        return when {
            f.loadTypicalByNow == null -> "Load $so so far; still learning your usual."
            else -> "Load $so so far, " + v.headline.removeSuffix(" so far").replaceFirstChar { it.lowercase() }.replace("than usual", "than usual") + "."
        }
    }

    private fun briefWater(f: BriefFacts): String {
        val ml = f.waterMl ?: return "Water: log a glass when you have one."
        val goal = f.waterGoalMl ?: return "Water so far: ${litres(ml)} L."
        val tail = when (f.waterPace) { "reached" -> "goal reached"; "behind" -> "a glass would help"; else -> "on pace" }
        return "Water ${waterLine(ml, goal)}, $tail."
    }

    private fun briefNext(f: BriefFacts): String {
        val e = f.nextEvent ?: return "Nothing else on your calendar today."
        return "Next: ${e.title} at ${e.startClock}${away(e)}."
    }

    private fun briefDayWent(f: BriefFacts): String {
        val so = f.loadSoFar
        val typical = f.loadTypicalByNow
        val work = if (f.workouts > 0) "You fitted in a workout. " else ""
        return when {
            so == null -> work.ifEmpty { "A quiet day." }.trim()
            typical == null -> work + "Load $so today."
            so < typical * 0.75 -> work + "A lighter day than usual, load $so."
            so <= typical * 1.25 -> work + "About a usual day, load $so."
            else -> work + "A heavier day than usual, load $so."
        }
    }

    private fun briefEveningFact(f: BriefFacts): String {
        val water = f.waterMl?.let { ml -> f.waterGoalMl?.let { "Water ${waterLine(ml, it)}" } ?: "Water ${litres(ml)} L" }
        val steps = f.steps?.takeIf { it > 0 }?.let { "${Format.compactCount(it)} steps" }
        return listOfNotNull(water, steps).joinToString("; ").ifEmpty { "Not much logged today." } + "."
    }

    private fun briefTomorrow(f: BriefFacts): String {
        val e = f.tomorrowFirst
        val bed = f.bedtime?.let { b -> "Bed by $b" + (f.bedtimeForMin?.let { " for ${Format.hm(it.toLong())}" } ?: "") + "." }
        val ev = e?.let { "Tomorrow: ${it.title} at ${it.startClock}." } ?: "Nothing planned tomorrow."
        return if (bed != null && ev.length + 1 + bed.length <= BULLET_MAX) "$ev $bed" else ev
    }

    /** Load so far today against the usual for this hour, as a chip (colour only against his own normal). */
    fun chipLoadSoFar(soFar: Double, typicalByNow: Double?, partial: Boolean): Chip {
        if (partial) return CHIP_PARTIAL
        if (typicalByNow == null) return Chip("Building your usual", Tone.Neutral, "Building")
        if (typicalByNow < 1.0 && soFar < 1.0) return Chip("Quiet so far", Tone.Neutral, "Quiet")
        val r = soFar / maxOf(typicalByNow, 1.0)
        return when {
            r < 0.75 -> Chip("Lighter than usual", Tone.Neutral, "Lighter")
            r <= 1.25 -> Chip("About usual", Tone.Good, "Usual")
            else -> Chip("Heavier than usual", Tone.Caution, "Heavier")
        }
    }

    // ---- calendar links and shifts (0.9.1) ---------------------------------------------------------------------

    /** How hard a shift was, from the average share of the heart-rate range (0-100). Plain thresholds, no medical meaning. */
    enum class ShiftEffort(val word: String) { Easy("easy"), Steady("steady"), Hard("hard") }

    fun shiftEffort(avgPctHrr: Int): ShiftEffort = when {
        avgPctHrr < 40 -> ShiftEffort.Easy
        avgPctHrr < 60 -> ShiftEffort.Steady
        else -> ShiftEffort.Hard
    }

    /** Today's slim line: "Shift 10:00–15:00 · hard (avg 71% of your range)". Null without a figure. */
    fun shiftTodayLine(range: String, avgPctHrr: Int?): String? {
        val pct = avgPctHrr?.coerceIn(0, 100) ?: return null
        return "Shift $range \u00B7 ${shiftEffort(pct).word} (avg $pct% of your range)"
    }

    /** "2 h 10" / "2 h" / "45 min". */
    fun hoursMinutes(min: Int): String {
        val m = maxOf(0, min)
        return when {
            m >= 60 && m % 60 != 0 -> "${m / 60} h ${m % 60}"
            m >= 60 -> "${m / 60} h"
            else -> "$m min"
        }
    }

    /** Below this share of the window with heart-rate data the Load line stays hidden. */
    const val SHIFT_MIN_COVERAGE = 0.3

    /** Load screen: "Shift 10:00–15:00 · avg 128 bpm · 2 h 10 in the harder zones". Null (hidden) with no data or thin coverage. */
    fun shiftLoadLine(range: String, avgHr: Int?, minutesZone2Plus: Int, coverage: Double?): String? {
        if (avgHr == null || avgHr <= 0) return null
        if (coverage != null && coverage < SHIFT_MIN_COVERAGE) return null
        val zones = if (minutesZone2Plus <= 0) "no time in the harder zones" else "${hoursMinutes(minutesZone2Plus)} in the harder zones"
        return "Shift $range \u00B7 avg $avgHr bpm \u00B7 $zones"
    }

    /** "just now" / "12 min ago" / "3 h ago" / "2 days ago"; null when never. */
    fun updatedAgo(lastOkMs: Long?, nowMs: Long): String? {
        if (lastOkMs == null) return null
        val min = maxOf(0L, (nowMs - lastOkMs) / 60_000L)
        return when {
            min < 1 -> "just now"
            min < 60 -> "$min min ago"
            min < 24 * 60 -> "${min / 60} h ago"
            else -> (min / (24 * 60)).let { if (it == 1L) "1 day ago" else "$it days ago" }
        }
    }

    private val URL_IN_TEXT = Regex("(?i)(https?|webcals?|webcal)://\\S+")

    /** Error text for the screen: any address in it is replaced, so the secret link never shows. */
    fun hideUrls(text: String): String = text.replace(URL_IN_TEXT, "the link").trim()

    fun eventsWord(n: Int): String = if (n == 1) "1 event" else "$n events"

    /** Settings row, second line: "23 events · updated 12 min ago", or the problem in plain words. Never the URL. */
    fun feedStatus(eventCount: Int, lastOkMs: Long?, lastError: String?, nowMs: Long): String {
        val ago = updatedAgo(lastOkMs, nowMs)
        if (!lastError.isNullOrBlank()) {
            return "Could not update: ${hideUrls(lastError)}" + (ago?.let { " \u00B7 last worked $it" } ?: "")
        }
        if (ago == null) return "Not fetched yet"
        return "${eventsWord(eventCount)} \u00B7 updated $ago"
    }

    /** "Mon 10:00" for the preview line. */
    fun dayClock(begin: java.time.Instant, zone: java.time.ZoneId): String =
        begin.atZone(zone).let { java.time.format.DateTimeFormatter.ofPattern("EEE HH:mm", Locale.ENGLISH).format(it) }

    /** The "Check link" result: "Found 23 events · next: Delivery Mon 10:00", or the error. */
    fun previewLine(events: Int, nextTitle: String?, nextWhen: String?, error: String?): String {
        if (!error.isNullOrBlank()) return hideUrls(error)
        if (events <= 0) return "The link works, but it has no events yet."
        val t = nextTitle?.trim().orEmpty().ifEmpty { "Busy" }.let { if (it.length > 28) it.take(27).trimEnd() + "\u2026" else it }
        return "Found ${eventsWord(events)}" + (nextWhen?.let { " \u00B7 next: $t $it" } ?: "")
    }

    /** What the person pasted, tidied: whitespace and quotes removed, webcal:// turned into https://. Empty stays empty. */
    fun cleanLinkInput(raw: String): String {
        var s = raw.filter { !it.isWhitespace() }.trim('"', '\'', '<', '>')
        if (s.startsWith("webcal://", ignoreCase = true)) s = "https://" + s.substring(9)
        else if (s.startsWith("webcals://", ignoreCase = true)) s = "https://" + s.substring(10)
        return s
    }

    /** Null when [clean] is empty (no message yet) or looks fine; otherwise a plain-words problem. */
    fun linkInputProblem(clean: String): String? = when {
        clean.isEmpty() -> null
        clean.startsWith("https://", ignoreCase = true) && clean.length > 12 && '.' in clean -> null
        clean.startsWith("http://", ignoreCase = true) -> "Use a link that starts with https:// (or webcal://)."
        else -> "That does not look like a calendar link. It should start with https:// or webcal://."
    }
}
