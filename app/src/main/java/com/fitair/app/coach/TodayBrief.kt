package com.fitair.app.coach

import android.content.ContentValues
import android.content.Context
import com.fitair.app.AppLog
import com.fitair.app.LocalApi
import com.fitair.app.LocalStore
import com.fitair.app.SyncPrefs
import com.fitair.app.analytics.ReadinessView
import com.fitair.app.core.Format
import com.fitair.app.data.dao.AiCallDao
import com.fitair.app.data.dao.AiCallRow
import com.fitair.app.data.dao.LoadDao
import com.fitair.app.data.dao.Pace
import com.fitair.app.data.metrics.MetricsRepo
import com.fitair.app.data.metrics.MetricSnapshot
import com.fitair.app.integrations.calendar.CalendarRepo
import com.fitair.app.integrations.calendar.ics.IcsFeeds
import com.fitair.app.secure.SecretStore
import com.fitair.app.ui.copy.Copy
import com.fitair.app.ui.today.Mode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** One calendar event as the summary may mention it. */
class BriefEvent(val title: String, val startClock: String, val minutesAway: Int?)

/**
 * Facts behind the three Today bullets (docs/PLAN_090 6.2). Every number the summary may quote is a field here; Copy.brief words the
 * template from them and the validator only accepts model text whose numbers match them.
 */
class BriefFacts(
    val mode: Mode,
    val readiness: Int? = null,
    /** "well" (>= 70), "partly" (50-69), "not" (< 50) or null. */
    val verdictKey: String? = null,
    /** Readiness driver key ("hrv", "resting_hr", "sleep", "load", "subjective") or null. */
    val driverKey: String? = null,
    val sleepAsleepMin: Int? = null, val sleepNeedMin: Int? = null, val sleepScore: Int? = null, val sleepDebtMin: Int? = null,
    val hrv: Int? = null, val hrvUsual: Int? = null, val rhr: Int? = null, val rhrUsual: Int? = null,
    val loadSoFar: Int? = null, val loadTypicalByNow: Int? = null, val loadRatio: Double? = null, val zoneMin: Int? = null,
    val waterMl: Int? = null, val waterGoalMl: Int? = null,
    /** "on_pace", "behind" or "reached". */
    val waterPace: String? = null,
    val steps: Long? = null, val distanceKm: Double? = null, val workouts: Int = 0,
    val nextEvent: BriefEvent? = null, val tomorrowFirst: BriefEvent? = null,
    /** Clock "23:15" and the sleep it is for in minutes. */
    val bedtime: String? = null, val bedtimeForMin: Int? = null,
    val insightIds: List<String> = emptyList(),
    val partial: Boolean = false,
    /** Today's work-shift windows with heart-rate intensity (empty without a work calendar feed). */
    val work: List<WorkFact> = emptyList(),
)

/** [source] is "llm" or "template". */
class Brief(val bullets: List<String>, val source: String, val createdMs: Long)

/**
 * The three Today bullets (docs/PLAN_090 6.2). Facts come from [MetricsRepo] and the calendar, never from the model. One row per
 * (date, part of day) in day_brief; a new model call happens only on the first open of a part of day or when the facts hash moved, with
 * a key, and at most [TodayBriefLogic.MAX_CALLS_PER_DAY] a day. Offline, no key, error, timeout or a rejected reply: rule-based text.
 */
object TodayBrief {
    private val lock = Mutex()
    private class Cal(val atMs: Long, val next: BriefEvent?, val tomorrow: BriefEvent?)
    @Volatile private var lastCal: Cal? = null
    private const val CAL_FRESH_MS = 10 * 60_000L

    /** The bullets for [mode]: cached, generated or rule-based. Never throws except cancellation. */
    suspend fun get(ctx: Context, date: LocalDate, mode: Mode): Brief = withContext(Dispatchers.IO) {
        try {
            lock.withLock { getLocked(ctx, date, mode) }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e
        } catch (e: Exception) {
            AppLog.d("today brief failed: ${e.javaClass.simpleName}: ${e.message}")
            templateNow(ctx, date, mode)
        }
    }

    /** Rule-based bullets for the current facts, at once, no network (the calendar part comes from the last read, if recent). */
    fun templateNow(ctx: Context, date: LocalDate, mode: Mode): Brief = try {
        val cal = lastCal?.takeIf { System.currentTimeMillis() - it.atMs < CAL_FRESH_MS }
        template(buildFacts(ctx, date, mode, cal))
    } catch (e: Exception) {
        AppLog.d("today brief template failed: ${e.message}")
        Brief(emptyList(), "template", System.currentTimeMillis())
    }

    private fun template(f: BriefFacts) = Brief(Copy.brief(f.mode, f).take(TodayBriefLogic.BULLETS), "template", System.currentTimeMillis())

    private suspend fun getLocked(ctx: Context, date: LocalDate, mode: Mode): Brief {
        val now = System.currentTimeMillis()
        val cal = readCalendar(ctx, date, mode)
        val f = buildFacts(ctx, date, mode, cal)
        val hash = TodayBriefLogic.hash(f)
        val part = mode.name.lowercase()
        val row = cached(ctx, date, part)
        val plan = TodayBriefLogic.plan(row?.let { TodayBriefLogic.Cached(it.hash, it.brief.source, it.brief.createdMs) }, hash, hasKey(ctx), callsToday(ctx, date), now)
        when (plan) {
            TodayBriefLogic.Plan.UseCache -> if (row != null && row.brief.bullets.size == TodayBriefLogic.BULLETS) return row.brief
            TodayBriefLogic.Plan.Template -> return template(f)
            TodayBriefLogic.Plan.Generate -> {}
        }
        val nowClock = nowClock()
        val result = try {
            askModel(ctx, f, nowClock)
        } catch (e: kotlinx.coroutines.CancellationException) { throw e
        } catch (e: Exception) { AppLog.d("today brief: model failed: ${e.javaClass.simpleName}: ${e.message}"); null }
        val out = if (result != null) Brief(result.first, "llm", System.currentTimeMillis()) else template(f)
        store(ctx, date, part, hash, f, out, result?.second)
        return out
    }

    // ---- facts --------------------------------------------------------------------------------------------------

    private fun nowClock(): String = Instant.now().atZone(ZoneId.systemDefault()).let { TodayBriefLogic.clock(it.hour, it.minute) }

    private fun short(t: String) = t.trim().ifEmpty { "Busy" }.let { if (it.length <= 30) it else it.take(29).trimEnd() + "…" }

    /** Title of [e] as the model may see it: events of a subscribed feed that does not share titles read "Busy". */
    private fun shown(e: com.fitair.app.integrations.calendar.CalEvent, feedShare: Map<Long, Boolean>): String =
        if (e.feedId != null && feedShare[e.feedId] != true) "Busy" else e.title

    private suspend fun readCalendar(ctx: Context, date: LocalDate, mode: Mode): Cal? {
        val z = ZoneId.systemDefault()
        val repo = CalendarRepo(ctx)
        if (!repo.hasPermission() && IcsFeeds.list(ctx).isEmpty()) return null
        val feedShare = IcsFeeds.list(ctx).associate { it.id to it.shareTitles }
        return try {
            val now = Instant.now()
            fun ev(e: com.fitair.app.integrations.calendar.CalEvent): BriefEvent {
                val at = e.begin.atZone(z)
                val min = if (e.begin <= now) 0 else ((e.begin.epochSecond - now.epochSecond + 59) / 60).toInt()
                return BriefEvent(short(shown(e, feedShare)), TodayBriefLogic.clock(at.hour, at.minute), min)
            }
            val today = repo.eventsForDay(date, z).filter { !it.allDay && it.end > now }.minByOrNull { it.begin }
            val tomorrow = if (mode == Mode.Evening) repo.eventsForDay(date.plusDays(1), z).filter { !it.allDay }.minByOrNull { it.begin } else null
            Cal(System.currentTimeMillis(), today?.let(::ev), tomorrow?.let { e ->
                val at = e.begin.atZone(z); BriefEvent(short(shown(e, feedShare)), TodayBriefLogic.clock(at.hour, at.minute), null)
            }).also { lastCal = it }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e
        } catch (e: Exception) { AppLog.d("today brief: calendar failed: ${e.message}"); null }
    }

    /** The facts of [date] for [mode], from the cached metric snapshot (database only) plus [cal]. */
    private fun buildFacts(ctx: Context, date: LocalDate, mode: Mode, cal: Cal?): BriefFacts {
        val s: MetricSnapshot = MetricsRepo.snapshot(ctx, date)
        fun r(v: Double?) = v?.let { Math.round(it).toInt() }
        val readiness = r(s.readiness.lastOrNull())
        val row = try { LocalStore.get(ctx).getDaily(date.toString(), date.toString()).firstOrNull() } catch (e: Exception) { null }
        val rv = ReadinessView.parse(row?.optString("readiness_json"))
        val night = s.nights.lastOrNull()
        val lt = s.loadToday
        val live = if (mode != Mode.Morning) try { LoadDao.live(ctx, date) } catch (e: Exception) { null } else null
        val w = s.water
        val ins = try {
            val a = JSONArray(row?.optString("insights_json").takeUnless { it.isNullOrEmpty() } ?: "[]")
            (0 until a.length()).map { a.getJSONObject(it) }.filter { it.optString("severity") == "alert" || it.optString("severity") == "watch" }
                .sortedBy { if (it.optString("severity") == "alert") 0 else 1 }.take(2).map { it.optString("id") }.filter { it.isNotEmpty() }
        } catch (e: Exception) { emptyList() }
        val workouts = if (mode == Mode.Evening) try {
            val (lo, hi) = LocalApi.bounds(date, ZoneId.systemDefault())
            LocalApi.exercises(LocalStore.get(ctx).db, lo, hi).size
        } catch (e: Exception) { 0 } else 0
        val wd = s.windDown
        return BriefFacts(
            mode = mode, readiness = readiness,
            verdictKey = readiness?.let { if (it >= 70) "well" else if (it >= 50) "partly" else "not" },
            driverKey = rv?.drivers?.firstOrNull()?.key,
            sleepAsleepMin = night?.let { Math.round(it.asleepMin).toInt() }, sleepNeedMin = night?.needMin?.let { Math.round(it).toInt() },
            sleepScore = r(night?.score), sleepDebtMin = r(s.debtMin)?.takeIf { it > 0 },
            hrv = r(s.hrv.lastOrNull()), hrvUsual = s.hrvBand?.let { Math.round(it.mean).toInt() },
            rhr = r(s.rhr.lastOrNull()), rhrUsual = s.rhrBand?.let { Math.round(it.mean).toInt() },
            loadSoFar = if (mode == Mode.Morning) null else lt?.let { Math.round(it.soFar).toInt() },
            loadTypicalByNow = if (mode == Mode.Morning) null else lt?.typicalByNow?.let { Math.round(it).toInt() },
            loadRatio = s.ratio, zoneMin = live?.let { it.zVig + it.zPeak },
            waterMl = w?.ml, waterGoalMl = w?.goalMl,
            waterPace = w?.pace?.let { when (it) { Pace.Reached -> "reached"; Pace.Behind -> "behind"; Pace.OnPace -> "on_pace" } },
            steps = s.steps.lastOrNull()?.toLong().takeIf { mode == Mode.Evening },
            distanceKm = s.distanceM.lastOrNull()?.let { Math.round(it / 100.0) / 10.0 }.takeIf { mode == Mode.Evening },
            workouts = workouts, nextEvent = cal?.next, tomorrowFirst = cal?.tomorrow,
            bedtime = wd?.let { val m = ((it.bedMin % 1440) + 1440) % 1440; Format.clock(m / 60, m % 60) }, bedtimeForMin = wd?.needMin,
            insightIds = ins, partial = lt?.partial ?: false,
            work = DaySummary.workFacts(ctx, date, ZoneId.systemDefault()),
        )
    }

    // ---- storage ------------------------------------------------------------------------------------------------

    private class Row(val hash: String, val brief: Brief)

    private fun cached(ctx: Context, date: LocalDate, part: String): Row? = try {
        LocalStore.get(ctx).db.rawQuery("SELECT facts_hash, bullets_json, source, created_ms FROM day_brief WHERE date=? AND part=?",
            arrayOf(date.toString(), part)).use {
            if (!it.moveToFirst()) null else {
                val a = JSONArray(it.getString(1))
                Row(it.getString(0), Brief((0 until a.length()).map { i -> a.getString(i) }, it.getString(2), it.getLong(3)))
            }
        }
    } catch (e: Exception) { null }

    private fun store(ctx: Context, date: LocalDate, part: String, hash: String, f: BriefFacts, b: Brief, model: String?) {
        try {
            val v = ContentValues()
            v.put("date", date.toString()); v.put("part", part); v.put("facts_hash", hash)
            v.put("facts_json", TodayBriefLogic.factsJson(f, null).toString()); v.put("bullets_json", JSONArray(b.bullets).toString())
            v.put("source", b.source); v.put("model", model); v.put("created_ms", b.createdMs)
            LocalStore.get(ctx).db.insertWithOnConflict("day_brief", null, v, android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE)
        } catch (e: Exception) { AppLog.d("day_brief store failed: ${e.message}") }
    }

    /** Model calls made for the brief on [date] (failures count: the cap is on calls, not on successes). */
    private fun callsToday(ctx: Context, date: LocalDate): Int = try {
        val lo = LocalApi.bounds(date, ZoneId.systemDefault()).first
        LocalStore.get(ctx).db.rawQuery("SELECT count(*) FROM ai_call WHERE task=? AND ts>=?", arrayOf(TodayBriefLogic.TASK, lo.toString()))
            .use { if (it.moveToFirst()) it.getInt(0) else 0 }
    } catch (e: Exception) { TodayBriefLogic.MAX_CALLS_PER_DAY }

    private fun hasKey(ctx: Context): Boolean = try {
        val p = ctx.getSharedPreferences(SyncPrefs.FILE, Context.MODE_PRIVATE).getString("coach_provider", "gemini")
        SecretStore.get(ctx, if (p == "openai") "openai_key" else "gemini_key").trim().isNotEmpty()
    } catch (e: Exception) { false }

    // ---- model --------------------------------------------------------------------------------------------------

    /** One cheap call (default small model, thinking off, 160 tokens, 8 s). Returns (bullets, model) or null on any failure or rejection. */
    private suspend fun askModel(ctx: Context, f: BriefFacts, nowClock: String): Pair<List<String>, String>? {
        val prefs = ctx.getSharedPreferences(SyncPrefs.FILE, Context.MODE_PRIVATE)
        val provider = (prefs.getString("coach_provider", "gemini") ?: "gemini").lowercase().let { if (it == "openai") "openai" else "gemini" }
        val key = SecretStore.get(ctx, if (provider == "openai") "openai_key" else "gemini_key").trim()
        if (key.isEmpty()) return null
        val model = Models.default(provider)
        val client: LlmClient = if (provider == "openai") OpenAiClient(key, ModelQuirks(ctx)) else GeminiClient(key, ModelQuirks(ctx))
        val t0 = System.currentTimeMillis()
        var err: String? = null; var usage = Usage(); var finish: String? = null; var usedModel = model
        try {
            val req = LlmRequest(model, TodayBriefLogic.system, listOf(LlmMsg.User(TodayBriefLogic.factsJson(f, nowClock).toString())),
                emptyList(), TodayBriefLogic.MAX_TOKENS, Thinking.Off, TodayBriefLogic.schema)
            val call = CoroutineScope(Dispatchers.IO).async { client.generate(req) }
            val resp = withTimeoutOrNull(TodayBriefLogic.TIMEOUT_MS) { call.await() }
            if (resp == null) { call.cancel(); err = "timeout"; return null }
            usage = resp.usage; finish = resp.finish; usedModel = resp.model
            val bullets = TodayBriefLogic.parseReply(resp.text)
            val why = TodayBriefLogic.problem(bullets, TodayBriefLogic.allowedNumbers(f), TodayBriefLogic.allowedClocks(f, nowClock))
            if (why != null) { err = "rejected: $why"; AppLog.d("today brief: reply rejected ($why), using rules"); return null }
            return bullets!!.map { it.trim() } to usedModel
        } catch (e: kotlinx.coroutines.CancellationException) { throw e
        } catch (e: Exception) { err = e.message ?: e.javaClass.simpleName; throw e
        } finally {
            AiCallDao.insert(ctx, AiCallRow(System.currentTimeMillis(), TodayBriefLogic.TASK, provider, usedModel, 1, usage.inTok, usage.outTok,
                usage.thoughtTok, usage.cachedTok, System.currentTimeMillis() - t0, err == null, finish, err))
        }
    }
}
