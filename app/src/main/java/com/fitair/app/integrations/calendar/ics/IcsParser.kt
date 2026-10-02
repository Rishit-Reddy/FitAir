package com.fitair.app.integrations.calendar.ics

import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters

/**
 * One concrete event instance inside the requested window. For all-day instances [beginMs]/[endMs] are UTC midnights of the
 * calendar dates (end exclusive), like Android's CalendarContract; timed ones are real instants.
 * [recKey] is "" for a plain event, otherwise the key of the recurrence instance (unique together with [uid]).
 */
class IcsInstance(
    val uid: String, val recKey: String, val beginMs: Long, val endMs: Long, val allDay: Boolean,
    val title: String, val location: String?, val busy: Boolean,
)

/** Pure RFC 5545 subset parser and recurrence expander. No android.* imports, never throws on junk input. */
object IcsParser {
    const val MAX_EVENTS = 20_000
    const val MAX_INSTANCES = 20_000
    private const val MAX_PERIODS = 30_000
    private const val MAX_CANDIDATES = 60_000
    private const val DAY_MS = 86_400_000L

    // ---------- lines ----------

    internal class Prop(val name: String, val params: Map<String, String>, val value: String)

    internal fun unfold(text: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var have = false
        for (raw in text.replace("﻿", "").split("\r\n", "\n", "\r")) {
            if (raw.isNotEmpty() && (raw[0] == ' ' || raw[0] == '\t')) {
                if (have) sb.append(raw, 1, raw.length)
            } else {
                if (have) out.add(sb.toString())
                sb.setLength(0); sb.append(raw); have = true
            }
        }
        if (have) out.add(sb.toString())
        return out.filter { it.isNotBlank() }
    }

    private fun splitOutsideQuotes(s: String, sep: Char, limit: Int = Int.MAX_VALUE): List<String> {
        val out = ArrayList<String>()
        var q = false; var start = 0
        for (i in s.indices) {
            val c = s[i]
            if (c == '"') q = !q
            else if (c == sep && !q && out.size < limit - 1) { out.add(s.substring(start, i)); start = i + 1 }
        }
        out.add(s.substring(start))
        return out
    }

    internal fun parseProp(line: String): Prop? {
        val parts = splitOutsideQuotes(line, ':', 2)
        if (parts.size < 2) return null
        val head = splitOutsideQuotes(parts[0], ';')
        val name = head[0].trim().uppercase()
        if (name.isEmpty()) return null
        val params = HashMap<String, String>()
        for (p in head.drop(1)) {
            val eq = p.indexOf('=')
            if (eq > 0) params[p.substring(0, eq).trim().uppercase()] = p.substring(eq + 1).trim().trim('"')
        }
        return Prop(name, params, parts[1])
    }

    internal fun unescape(s: String): String {
        if ('\\' !in s) return s
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (val n = s[i + 1]) { 'n', 'N' -> sb.append('\n'); else -> sb.append(n) }
                i += 2
            } else { sb.append(c); i++ }
        }
        return sb.toString()
    }

    // ---------- time zones ----------

    private val WINDOWS_ZONES = mapOf(
        "w. europe standard time" to "Europe/Berlin", "romance standard time" to "Europe/Paris",
        "central europe standard time" to "Europe/Budapest", "central european standard time" to "Europe/Warsaw",
        "gmt standard time" to "Europe/London", "greenwich standard time" to "Atlantic/Reykjavik",
        "fle standard time" to "Europe/Helsinki", "e. europe standard time" to "Europe/Chisinau",
        "gtb standard time" to "Europe/Bucharest", "russian standard time" to "Europe/Moscow", "turkey standard time" to "Europe/Istanbul",
        "eastern standard time" to "America/New_York", "central standard time" to "America/Chicago",
        "mountain standard time" to "America/Denver", "pacific standard time" to "America/Los_Angeles",
        "alaskan standard time" to "America/Anchorage", "hawaiian standard time" to "Pacific/Honolulu",
        "us eastern standard time" to "America/Indiana/Indianapolis", "canada central standard time" to "America/Regina",
        "india standard time" to "Asia/Kolkata", "china standard time" to "Asia/Shanghai", "tokyo standard time" to "Asia/Tokyo",
        "korea standard time" to "Asia/Seoul", "singapore standard time" to "Asia/Singapore", "arab standard time" to "Asia/Riyadh",
        "arabian standard time" to "Asia/Dubai", "aus eastern standard time" to "Australia/Sydney",
        "e. australia standard time" to "Australia/Brisbane", "new zealand standard time" to "Pacific/Auckland",
        "south africa standard time" to "Africa/Johannesburg", "e. south america standard time" to "America/Sao_Paulo",
        "utc" to "UTC", "tzone0" to "UTC", "greenwich" to "UTC",
    )

    /** java.time zone for a TZID (IANA, "/vendor/…/Europe/Stockholm" or a common Windows name), or null when unknown. */
    internal fun zoneOf(tzid: String?): ZoneId? {
        if (tzid.isNullOrBlank()) return null
        val t = tzid.trim().trim('"')
        try { return ZoneId.of(t) } catch (e: Exception) { /* fall through */ }
        WINDOWS_ZONES[t.lowercase()]?.let { return try { ZoneId.of(it) } catch (e: Exception) { null } }
        val segs = t.split('/').filter { it.isNotEmpty() }
        for (n in 3 downTo 1) {
            if (segs.size < n) continue
            try { return ZoneId.of(segs.takeLast(n).joinToString("/")) } catch (e: Exception) { /* next */ }
        }
        return null
    }

    // ---------- date-times ----------

    /** A parsed DTSTART/DTEND/EXDATE/RDATE value. [date] set = all-day; otherwise [zdt] is the resolved zoned time. */
    internal class Dt(val date: LocalDate?, val local: LocalDateTime?, val zone: ZoneId?, val utc: Boolean) {
        val allDay get() = date != null
    }

    private fun parseDigits(s: String, from: Int, n: Int): Int? {
        if (from + n > s.length) return null
        var v = 0
        for (i in from until from + n) { val c = s[i]; if (c !in '0'..'9') return null; v = v * 10 + (c - '0') }
        return v
    }

    internal fun parseDt(p: Prop, defaultZone: ZoneId): Dt? = parseDtValue(p.value.trim(), p.params, defaultZone)

    private fun parseDtValue(v: String, params: Map<String, String>, defaultZone: ZoneId): Dt? {
        try {
            val y = parseDigits(v, 0, 4) ?: return null
            val mo = parseDigits(v, 4, 2) ?: return null
            val d = parseDigits(v, 6, 2) ?: return null
            val date = LocalDate.of(y, mo, d)
            if (params["VALUE"].equals("DATE", true) || v.length == 8) return Dt(date, null, null, false)
            if (v.length < 15 || v[8] != 'T') return null
            val h = parseDigits(v, 9, 2) ?: return null
            val mi = parseDigits(v, 11, 2) ?: return null
            val s = parseDigits(v, 13, 2) ?: return null
            val ldt = LocalDateTime.of(date, LocalTime.of(h.coerceAtMost(23), mi, s.coerceAtMost(59)))
            if (v.endsWith("Z", true)) return Dt(null, ldt, ZoneOffset.UTC, true)
            val tz = zoneOf(params["TZID"]) ?: defaultZone
            return Dt(null, ldt, tz, false)
        } catch (e: Exception) { return null }
    }

    /** ISO-8601-ish iCal DURATION ("P1DT2H", "-PT15M", "P2W") or null. */
    internal fun parseDuration(v: String): Duration? {
        val m = Regex("^([+-])?P(?:(\\d+)W)?(?:(\\d+)D)?(?:T(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+)S)?)?$").matchEntire(v.trim().uppercase()) ?: return null
        fun g(i: Int) = m.groupValues[i].toLongOrNull() ?: 0L
        val d = Duration.ofDays(g(2) * 7 + g(3)).plusHours(g(4)).plusMinutes(g(5)).plusSeconds(g(6))
        return if (m.groupValues[1] == "-") d.negated() else d
    }

    // ---------- RRULE ----------

    internal class Rule(
        val freq: String, val interval: Int, val count: Int?, val untilDate: LocalDate?, val untilInstant: Long?,
        val byDay: List<Pair<Int?, DayOfWeek>>, val byMonthDay: List<Int>, val byMonth: List<Int>, val bySetPos: List<Int>,
    )

    private val DOW = mapOf("MO" to DayOfWeek.MONDAY, "TU" to DayOfWeek.TUESDAY, "WE" to DayOfWeek.WEDNESDAY, "TH" to DayOfWeek.THURSDAY,
        "FR" to DayOfWeek.FRIDAY, "SA" to DayOfWeek.SATURDAY, "SU" to DayOfWeek.SUNDAY)

    internal fun parseRule(v: String, start: Dt): Rule? {
        val m = HashMap<String, String>()
        for (part in v.split(';')) {
            val eq = part.indexOf('=')
            if (eq > 0) m[part.substring(0, eq).trim().uppercase()] = part.substring(eq + 1).trim()
        }
        val freq = m["FREQ"]?.uppercase() ?: return null
        if (freq !in setOf("DAILY", "WEEKLY", "MONTHLY", "YEARLY")) return null
        val interval = (m["INTERVAL"]?.toIntOrNull() ?: 1).coerceIn(1, 1000)
        val count = m["COUNT"]?.toIntOrNull()?.takeIf { it > 0 }
        var ud: LocalDate? = null; var ui: Long? = null
        m["UNTIL"]?.let { u ->
            val dt = parseDtValue(u, emptyMap(), start.zone ?: ZoneOffset.UTC)
            if (dt != null) {
                if (dt.allDay) ud = dt.date
                else ui = dt.local!!.atZone(dt.zone!!).toInstant().toEpochMilli()
            }
        }
        val byDay = ArrayList<Pair<Int?, DayOfWeek>>()
        m["BYDAY"]?.split(',')?.forEach { tok ->
            val mm = Regex("^([+-]?\\d{1,2})?(MO|TU|WE|TH|FR|SA|SU)$").matchEntire(tok.trim().uppercase()) ?: return@forEach
            val ord = mm.groupValues[1].toIntOrNull()?.takeIf { it != 0 && Math.abs(it) <= 53 }
            byDay.add(ord to DOW.getValue(mm.groupValues[2]))
        }
        fun ints(k: String, lo: Int, hi: Int) = m[k]?.split(',')?.mapNotNull { it.trim().toIntOrNull() }?.filter { it in lo..hi && it != 0 } ?: emptyList()
        return Rule(freq, interval, count, ud, ui, byDay, ints("BYMONTHDAY", -31, 31), ints("BYMONTH", 1, 12), ints("BYSETPOS", -366, 366))
    }

    private fun monthDates(ym: YearMonth, r: Rule, startDom: Int, defaultToDom: Boolean): List<LocalDate> {
        val len = ym.lengthOfMonth()
        var out: List<LocalDate>
        if (r.byMonthDay.isNotEmpty()) {
            out = r.byMonthDay.mapNotNull { n -> val d = if (n > 0) n else len + n + 1; if (d in 1..len) ym.atDay(d) else null }
            if (r.byDay.isNotEmpty()) out = out.filter { d -> r.byDay.any { it.second == d.dayOfWeek } }
        } else if (r.byDay.isNotEmpty()) {
            val set = LinkedHashSet<LocalDate>()
            for ((ord, dow) in r.byDay) {
                if (ord == null) {
                    var d = ym.atDay(1).with(TemporalAdjusters.firstInMonth(dow))
                    while (d.month == ym.month) { set.add(d); d = d.plusWeeks(1) }
                } else {
                    val d = if (ord > 0) ym.atDay(1).with(TemporalAdjusters.firstInMonth(dow)).plusWeeks((ord - 1).toLong())
                    else ym.atEndOfMonth().with(TemporalAdjusters.lastInMonth(dow)).minusWeeks((-ord - 1).toLong())
                    if (d.year == ym.year && d.month == ym.month) set.add(d)
                }
            }
            out = set.toList()
        } else {
            out = if (defaultToDom && startDom <= len) listOf(ym.atDay(startDom)) else emptyList()
        }
        out = out.distinct().sorted()
        if (r.bySetPos.isNotEmpty() && out.isNotEmpty()) {
            out = r.bySetPos.mapNotNull { p -> val i = if (p > 0) p - 1 else out.size + p; out.getOrNull(i) }.distinct().sorted()
        }
        return out
    }

    /**
     * Occurrence start dates of the rule, in order, from [start] on, lazily bounded by [lastDate] (stop when periods begin after it)
     * and by the hard caps. [consume] returns false to stop early.
     */
    private fun generate(r: Rule, startDate: LocalDate, lastDate: LocalDate, consume: (LocalDate) -> Boolean) {
        var produced = 0
        var periods = 0
        fun emit(d: LocalDate): Boolean {
            if (d < startDate) return true
            if (r.byMonth.isNotEmpty() && r.freq != "YEARLY" && d.monthValue !in r.byMonth) return true
            produced++
            if (produced > MAX_CANDIDATES) return false
            return consume(d)
        }
        val startMonday = startDate.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val startYm = YearMonth.from(startDate)
        var k = 0L
        while (periods++ < MAX_PERIODS) {
            val dates: List<LocalDate>; val periodFirst: LocalDate
            when (r.freq) {
                "DAILY" -> {
                    val d = startDate.plusDays(k * r.interval); periodFirst = d
                    dates = if ((r.byDay.isEmpty() || r.byDay.any { it.second == d.dayOfWeek }) &&
                        (r.byMonthDay.isEmpty() || r.byMonthDay.any { n -> if (n > 0) d.dayOfMonth == n else d.dayOfMonth == d.lengthOfMonth() + n + 1 })) listOf(d) else emptyList()
                }
                "WEEKLY" -> {
                    val ws = startMonday.plusWeeks(k * r.interval); periodFirst = ws
                    val dows = if (r.byDay.isEmpty()) listOf(startDate.dayOfWeek) else r.byDay.map { it.second }.distinct()
                    dates = dows.map { ws.plusDays((it.value - 1).toLong()) }.sorted()
                }
                "MONTHLY" -> {
                    val ym = startYm.plusMonths(k * r.interval); periodFirst = ym.atDay(1)
                    dates = monthDates(ym, r, startDate.dayOfMonth, true)
                }
                else -> {
                    val y = startDate.year + k * r.interval
                    if (y > 9998) return
                    periodFirst = LocalDate.of(y.toInt(), 1, 1)
                    val months = if (r.byMonth.isEmpty()) listOf(startDate.monthValue) else r.byMonth.sorted()
                    val l = ArrayList<LocalDate>()
                    for (mo in months) {
                        val ym = YearMonth.of(y.toInt(), mo)
                        l.addAll(if (r.byMonthDay.isEmpty() && r.byDay.isEmpty()) (if (startDate.dayOfMonth <= ym.lengthOfMonth()) listOf(ym.atDay(startDate.dayOfMonth)) else emptyList())
                        else monthDates(ym, r, startDate.dayOfMonth, false))
                    }
                    dates = l
                }
            }
            if (periodFirst > lastDate.plusDays(1)) return
            for (d in dates) if (!emit(d)) return
            k++
        }
    }

    // ---------- events ----------

    /**
     * Parses [text] and returns the instances overlapping [winStartMs, winEndMs). [zone] is the zone of floating times and the
     * "local date" for all-day windows (an X-WR-TIMEZONE header, when valid, wins for floating times). Never throws.
     */
    fun parse(text: String, winStartMs: Long, winEndMs: Long, zone: ZoneId = ZoneId.systemDefault()): List<IcsInstance> {
        try {
            return parseInner(text, winStartMs, winEndMs, zone)
        } catch (e: Exception) {
            return emptyList()
        } catch (e: StackOverflowError) {
            return emptyList()
        }
    }

    private fun parseInner(text: String, winStartMs: Long, winEndMs: Long, zone0: ZoneId): List<IcsInstance> {
        val lines = unfold(text)
        var zone = zone0
        val events = ArrayList<List<Prop>>()
        var cur: ArrayList<Prop>? = null
        var depth = 0   // nested components inside a VEVENT (VALARM)
        for (line in lines) {
            val p = parseProp(line) ?: continue
            when {
                p.name == "BEGIN" -> {
                    val v = p.value.trim().uppercase()
                    if (cur != null) depth++
                    else if (v == "VEVENT" && events.size < MAX_EVENTS) cur = ArrayList()
                }
                p.name == "END" -> {
                    if (cur != null) {
                        if (depth > 0) depth-- else { events.add(cur); cur = null }
                    }
                }
                cur != null -> if (depth == 0) cur.add(p)
                p.name == "X-WR-TIMEZONE" -> zoneOf(p.value)?.let { zone = it }
            }
        }

        class Ev(
            val uid: String, val start: Dt, val durationMs: Long, val allDayDays: Long, val title: String, val location: String?,
            val busy: Boolean, val cancelled: Boolean, val rrule: Rule?, val exdates: List<Dt>, val rdates: List<Dt>, val recId: Dt?,
        )

        val masters = ArrayList<Ev>()
        val overrides = ArrayList<Ev>()
        for ((idx, props) in events.withIndex()) {
            fun first(n: String) = props.firstOrNull { it.name == n }
            val ds = first("DTSTART") ?: continue
            val start = parseDt(ds, zone) ?: continue
            val title = first("SUMMARY")?.let { unescape(it.value).trim() }?.takeIf { it.isNotEmpty() } ?: "(no title)"
            val loc = first("LOCATION")?.let { unescape(it.value).trim() }?.takeIf { it.isNotEmpty() }
            val de = first("DTEND")?.let { parseDt(it, zone) }
            val dur = first("DURATION")?.let { parseDuration(it.value) }
            var durMs = 0L; var days = 1L
            if (start.allDay) {
                days = when {
                    de?.date != null -> java.time.temporal.ChronoUnit.DAYS.between(start.date, de.date)
                    de != null -> java.time.temporal.ChronoUnit.DAYS.between(start.date, de.local!!.toLocalDate())
                    dur != null -> dur.toDays()
                    else -> 1
                }.coerceIn(1, 366)
            } else {
                val s = start.local!!.atZone(start.zone!!)
                durMs = when {
                    de != null && !de.allDay -> Duration.between(s, de.local!!.atZone(de.zone!!)).toMillis()
                    de != null -> Duration.between(s, de.date!!.atStartOfDay(start.zone)).toMillis()
                    dur != null -> dur.toMillis()
                    else -> 0L
                }.coerceIn(0L, 366 * DAY_MS)
            }
            val uid = first("UID")?.value?.trim()?.takeIf { it.isNotEmpty() } ?: "noid-${title.hashCode()}-${start.hashCode()}-$idx"
            val cancelled = first("STATUS")?.value?.trim().equals("CANCELLED", true)
            val busy = !first("TRANSP")?.value?.trim().equals("TRANSPARENT", true)
            val tz = start.zone ?: zone
            val rr = first("RRULE")?.let { parseRule(it.value, start) }
            fun dates(name: String): List<Dt> = props.filter { it.name == name }.flatMap { p ->
                p.value.split(',').mapNotNull { v ->
                    // RDATE;VALUE=PERIOD is not supported: take the start part
                    parseDtValue(v.substringBefore('/').trim(), p.params, tz)
                }
            }
            val recId = first("RECURRENCE-ID")?.let { parseDt(it, zone) }
            val ev = Ev(uid, start, durMs, days, title, loc, busy, cancelled, rr, dates("EXDATE"), dates("RDATE"), recId)
            if (recId != null) overrides.add(ev) else masters.add(ev)
        }

        fun keyOf(dt: Dt): String = if (dt.allDay) "d" + dt.date.toString() else dt.local!!.atZone(dt.zone!!).toInstant().toEpochMilli().toString()

        val zoneForAllDay = zone
        fun overlaps(b: Long, e: Long, allDay: Boolean): Boolean {
            val lo: Long; val hi: Long
            if (allDay) {
                val (bl, el) = allDayLocalMs(b, e, zoneForAllDay); lo = bl; hi = el
            } else { lo = b; hi = e }
            return lo < winEndMs && (hi > winStartMs || (hi == lo && lo >= winStartMs))
        }

        val out = ArrayList<IcsInstance>()
        val seen = HashSet<String>()
        fun add(i: IcsInstance) {
            if (out.size >= MAX_INSTANCES) return
            var key = i.recKey; var n = 1
            while (!seen.add(i.uid + "\u0000" + key)) key = i.recKey + "#" + (n++)
            out.add(if (key == i.recKey) i else IcsInstance(i.uid, key, i.beginMs, i.endMs, i.allDay, i.title, i.location, i.busy))
        }

        // overrides by uid -> set of original keys
        val overriddenKeys = HashMap<String, MutableSet<String>>()
        for (o in overrides) overriddenKeys.getOrPut(o.uid) { HashSet() }.add(keyOf(o.recId!!))

        fun instanceOf(ev: Ev, startDt: Dt, recKey: String): IcsInstance? {
            return if (ev.start.allDay) {
                val d = startDt.date!!
                val b = d.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
                val e = b + ev.allDayDays * DAY_MS
                if (!overlaps(b, e, true)) null else IcsInstance(ev.uid, recKey, b, e, true, ev.title, ev.location, ev.busy)
            } else {
                val b = startDt.local!!.atZone(startDt.zone!!).toInstant().toEpochMilli()
                val e = b + ev.durationMs
                if (!overlaps(b, e, false)) null else IcsInstance(ev.uid, recKey, b, e, false, ev.title, ev.location, ev.busy)
            }
        }

        val lastDate = java.time.Instant.ofEpochMilli(winEndMs).atZone(ZoneOffset.UTC).toLocalDate().plusDays(2)
        for (ev in masters) {
            if (out.size >= MAX_INSTANCES) break
            if (ev.cancelled) continue
            val ovr = overriddenKeys[ev.uid] ?: emptySet<String>()
            val rule = ev.rrule
            if (rule == null && ev.rdates.isEmpty()) {
                if (keyOf(ev.start) in ovr) continue
                instanceOf(ev, ev.start, "")?.let { add(it) }
                continue
            }
            val ex = HashSet<String>()
            for (x in ev.exdates) {
                if (ev.start.allDay) ex.add("d" + (x.date ?: x.local!!.toLocalDate()))
                else if (x.allDay) { /* all-day EXDATE on a timed event: exclude that local date */ ex.add("D" + x.date) }
                else ex.add(keyOf(x))
            }
            val tz = ev.start.zone
            fun excluded(startDt: Dt, key: String): Boolean {
                if (key in ex || key in ovr) return true
                if (tz != null && "D" + startDt.local!!.toLocalDate() in ex) return true
                return false
            }
            fun consider(startDt: Dt) {
                val key = keyOf(startDt)
                if (excluded(startDt, key)) return
                instanceOf(ev, startDt, key)?.let { add(it) }
            }
            val startDate = if (ev.start.allDay) ev.start.date!! else ev.start.local!!.toLocalDate()
            val time = ev.start.local?.toLocalTime()
            var n = 0
            if (rule != null) {
                generate(rule, startDate, lastDate) { d ->
                    n++
                    if (rule.count != null && n > rule.count) return@generate false
                    val startDt = if (ev.start.allDay) Dt(d, null, null, false) else {
                        // DST-safe: re-resolve the local wall time in the zone of DTSTART
                        Dt(null, LocalDateTime.of(d, time), tz, ev.start.utc)
                    }
                    if (rule.untilDate != null && d > rule.untilDate) return@generate false
                    if (rule.untilInstant != null) {
                        val ms = if (ev.start.allDay) d.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
                        else ZonedDateTime.of(LocalDateTime.of(d, time), tz).toInstant().toEpochMilli()
                        if (ms > rule.untilInstant) return@generate false
                    }
                    if (out.size >= MAX_INSTANCES) return@generate false
                    consider(startDt)
                    true
                }
            } else consider(ev.start)
            for (r in ev.rdates) {
                val rd = if (ev.start.allDay) (if (r.allDay) r else Dt(r.local!!.toLocalDate(), null, null, false))
                else if (r.allDay) Dt(null, LocalDateTime.of(r.date!!, time), tz, ev.start.utc)
                else r
                consider(rd)
            }
        }

        for (o in overrides) {
            if (o.cancelled) continue
            instanceOf(o, o.start, keyOf(o.recId!!))?.let { add(it) }
        }
        return out.sortedWith(compareBy({ it.beginMs }, { it.uid }))
    }

    /** Local-zone [begin, end) in epoch ms of an all-day event stored as UTC midnights. */
    internal fun allDayLocalMs(beginUtcMs: Long, endUtcMs: Long, zone: ZoneId): Pair<Long, Long> {
        val d0 = java.time.Instant.ofEpochMilli(beginUtcMs).atZone(ZoneOffset.UTC).toLocalDate()
        var d1 = java.time.Instant.ofEpochMilli(endUtcMs).atZone(ZoneOffset.UTC).toLocalDate()
        if (!d1.isAfter(d0)) d1 = d0.plusDays(1)
        return d0.atStartOfDay(zone).toInstant().toEpochMilli() to d1.atStartOfDay(zone).toInstant().toEpochMilli()
    }
}
