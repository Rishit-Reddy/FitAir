package com.fitair.app.analytics

import android.content.Context
import com.fitair.app.AppLog
import com.fitair.app.HealthRepo
import com.fitair.app.LocalApi
import com.fitair.app.LocalStore
import com.fitair.app.SyncPrefs
import com.fitair.app.core.Format
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

enum class CheckStatus { PASS, WARN, FAIL }

class Check(val id: String, val title: String, val status: CheckStatus, val detail: String)

/**
 * Data-correctness checks (docs/ARCHITECTURE.md 3.8). Read-only: it observes the DB and Health Connect,
 * it never repairs anything. Run from an IO coroutine.
 */
object SelfCheck {
    private const val H = 3_600_000L

    suspend fun execute(ctx: Context): List<Check> {
        val out = ArrayList<Check>()
        val store = LocalStore.get(ctx)
        val z = ZoneId.systemDefault()
        val today = LocalDate.now(z)
        val prefs = ctx.getSharedPreferences(SyncPrefs.FILE, Context.MODE_PRIVATE)
        val lastSync = prefs.getLong(SyncPrefs.LAST, 0L)
        fun add(id: String, title: String, s: CheckStatus, d: String) { out.add(Check(id, title, s, d)) }
        fun guarded(id: String, title: String, f: () -> Unit) {
            try { f() } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (e: Exception) {
                AppLog.e("selfcheck $id failed", e)
                add(id, title, CheckStatus.FAIL, "check crashed: ${e.javaClass.simpleName}: ${e.message}")
            }
        }

        guarded("db", "Database integrity") {
            val r = store.db.rawQuery("PRAGMA quick_check", null).use { if (it.moveToFirst()) it.getString(0) else "no result" }
            add("db", "Database integrity", if (r == "ok") CheckStatus.PASS else CheckStatus.FAIL,
                if (r == "ok") "quick_check ok, schema v${LocalStore.VERSION}" else r)
        }

        guarded("sync", "Last sync") {
            val age = System.currentTimeMillis() - lastSync
            when {
                lastSync <= 0 -> add("sync", "Last sync", CheckStatus.FAIL, "never synced")
                age > H -> add("sync", "Last sync", CheckStatus.WARN, Format.freshness(System.currentTimeMillis(), lastSync).text + " (limit 1 h)")
                else -> add("sync", "Last sync", CheckStatus.PASS, Format.freshness(System.currentTimeMillis(), lastSync).text)
            }
        }

        // 1. steps once across origins: DB (de-duplicated per minute) vs Health Connect's own aggregate
        run {
            val hc = HealthRepo(ctx)
            for ((label, d) in listOf("yesterday" to today.minusDays(1), "today" to today)) {
                val id = "steps_$label"; val title = "Steps $label vs Health Connect"
                try {
                    val (lo, hi) = LocalApi.bounds(d, z)
                    val ser = LocalApi.series(store.db, z, d, d, LocalApi.maxHr())
                    val db = ser[d.toString()]?.steps ?: 0L
                    val hcN = hc.stepsTotal(Instant.ofEpochMilli(lo), Instant.ofEpochMilli(minOf(hi, System.currentTimeMillis())))
                    when {
                        hcN == null -> add(id, title, CheckStatus.WARN, "could not read Health Connect (DB ${Format.thousands(db)})")
                        SelfCheckLogic.stepsDiffer(db, hcN) -> add(id, title, CheckStatus.WARN,
                            "DB ${Format.thousands(db)} vs Health Connect ${Format.thousands(hcN)}" + if (d == today) " (new steps may not be synced yet)" else "")
                        else -> add(id, title, CheckStatus.PASS, "DB ${Format.thousands(db)} vs Health Connect ${Format.thousands(hcN)}")
                    }
                } catch (e: kotlinx.coroutines.CancellationException) { throw e
                } catch (e: Exception) { add(id, title, CheckStatus.FAIL, "check crashed: ${e.message}") }
            }
        }

        // 2. one main sleep per wake date
        guarded("sleep", "One main sleep per night") {
            var cross = 0; var multi = 0; var none = 0
            for (k in 0..13) {
                val (lo, hi) = LocalApi.bounds(today.minusDays(k.toLong()), z)
                val sess = ArrayList<SelfCheckLogic.Sess>()
                store.db.rawQuery("SELECT start_ms,end_ms,origin FROM sleep WHERE end_ms>=? AND end_ms<?", arrayOf(lo.toString(), hi.toString())).use {
                    while (it.moveToNext()) sess.add(SelfCheckLogic.Sess(it.getLong(0), it.getLong(1), it.getString(2)))
                }
                if (sess.isEmpty()) { if (k > 0) none++; continue }
                val i = SelfCheckLogic.sleepIssues(sess)
                if (i.crossOriginOverlap) cross++
                if (i.sameOriginMulti) multi++
            }
            val bad = cross + multi
            add("sleep", "One main sleep per night", if (bad > 0) CheckStatus.WARN else CheckStatus.PASS,
                buildString {
                    append("last 14 days: ")
                    if (bad == 0) append("one session per night") else {
                        if (cross > 0) append("$cross night(s) with overlapping sessions from different sources (counted once). ")
                        if (multi > 0) append("$multi night(s) with several sessions from one source (summed).")
                    }
                    if (none > 0) append(" $none night(s) without sleep data.")
                })
        }

        // 3. heart-rate coverage while awake (07:00-22:00), last 3 days
        guarded("hr_gaps", "Heart-rate coverage (waking hours)") {
            val gaps = ArrayList<String>()
            for (k in 0..2) {
                val d = today.minusDays(k.toLong())
                val from = d.atTime(7, 0).atZone(z).toInstant().toEpochMilli()
                val to = minOf(d.atTime(22, 0).atZone(z).toInstant().toEpochMilli(), System.currentTimeMillis())
                if (to - from <= 2 * H) continue
                val ts = ArrayList<Long>()
                store.db.rawQuery("SELECT DISTINCT t30 FROM hr_30s WHERE t30>=? AND t30<=? ORDER BY t30", arrayOf(from.toString(), to.toString())).use {
                    while (it.moveToNext()) ts.add(it.getLong(0))
                }
                for (g in SelfCheckLogic.gaps(ts, from, to, 2 * H)) {
                    val a = Instant.ofEpochMilli(g[0]).atZone(z); val b = Instant.ofEpochMilli(g[1]).atZone(z)
                    gaps.add("$d ${Format.clock(a.hour, a.minute)}-${Format.clock(b.hour, b.minute)}")
                }
            }
            add("hr_gaps", "Heart-rate coverage (waking hours)", if (gaps.isEmpty()) CheckStatus.PASS else CheckStatus.WARN,
                if (gaps.isEmpty()) "no gap over 2 h in the last 3 days" else "gap over 2 h: " + gaps.joinToString(", "))
        }

        // 4. daily_metrics freshness
        guarded("daily", "Daily metrics up to date") {
            val rows = store.getDaily(today.minusDays(27).toString(), today.toString())
            val have = rows.map { it.optString("date") }.toSet()
            val missing = (0..27).map { today.minusDays(it.toLong()).toString() }.filter { it !in have }
            val todayRow = rows.firstOrNull { it.optString("date") == today.toString() }
            val computed = todayRow?.optLong("computed_ms", 0L) ?: 0L
            val stale = todayRow != null && lastSync > 0 && computed < lastSync - 5 * 60_000L
            val status = when {
                todayRow == null -> CheckStatus.FAIL
                missing.isNotEmpty() || stale -> CheckStatus.WARN
                else -> CheckStatus.PASS
            }
            add("daily", "Daily metrics up to date", status, buildString {
                append("${28 - missing.size}/28 days present")
                if (todayRow == null) append("; today missing")
                if (stale) append("; today computed before the last sync")
            })
        }

        // 5. readiness structure
        guarded("readiness", "Readiness structure") {
            val row = store.getDaily(today.toString(), today.toString()).firstOrNull()
            val js = row?.optString("readiness_json")?.takeIf { it.isNotEmpty() && it != "null" }?.let { JSONObject(it) }
            val sumOk = SelfCheckLogic.weightsOk(ReadinessMath.weightsSum())
            if (js == null) add("readiness", "Readiness structure", if (sumOk) CheckStatus.WARN else CheckStatus.FAIL,
                "no readiness row for today; nominal weights ${if (sumOk) "sum to 100 %" else "DO NOT sum to 100 %"}")
            else {
                val ver = js.optInt("readiness_version", 1)
                val comps = js.optJSONObject("components")
                val n = ReadinessMath.WEIGHTS.keys.count { comps != null && !comps.isNull(it) }
                val drivers = js.optJSONArray("drivers")
                var pct = 0
                if (drivers != null) for (i in 0 until drivers.length()) pct += drivers.getJSONObject(i).optInt("weight_pct")
                val noScore = js.isNull("score")
                val ok = sumOk && ver == ReadinessMath.VERSION && (noScore || (n >= ReadinessMath.MIN_COMPONENTS && pct == 100))
                add("readiness", "Readiness structure", if (ok) CheckStatus.PASS else if (noScore) CheckStatus.WARN else CheckStatus.FAIL,
                    "v$ver, $n components" + (if (noScore) ", no score today" else ", weights $pct %") +
                        ", nominal weights ${if (sumOk) "sum to 100 %" else "DO NOT sum to 100 %"}")
            }
        }

        // 6. plausible HRV / RHR
        guarded("ranges", "HRV and resting HR plausible") {
            val rows = store.getDaily(today.minusDays(27).toString(), today.toString())
            val hrv = rows.filter { it.has("hrv") }.map { it.getDouble("hrv") }
            val rhr = rows.filter { it.has("rhr") }.map { it.getDouble("rhr") }
            val badH = SelfCheckLogic.outOfRange(hrv, 10.0, 250.0); val badR = SelfCheckLogic.outOfRange(rhr, 30.0, 120.0)
            add("ranges", "HRV and resting HR plausible", if (badH.isEmpty() && badR.isEmpty()) CheckStatus.PASS else CheckStatus.WARN,
                if (badH.isEmpty() && badR.isEmpty()) "${hrv.size} HRV and ${rhr.size} resting-HR days within 10-250 ms / 30-120 bpm"
                else "out of range: HRV ${badH.map { Math.round(it) }}, RHR ${badR.map { Math.round(it) }}")
        }

        // 7. origins per type
        guarded("origins", "Data sources per type (7 days)") {
            val since = System.currentTimeMillis() - 7 * 24 * H
            val parts = ArrayList<String>()
            for ((table, col) in listOf("steps" to "start_ms", "hr_30s" to "t30", "sleep" to "start_ms", "hrv" to "t", "resting_hr" to "t", "exercise" to "start_ms")) {
                val o = ArrayList<String>()
                store.db.rawQuery("SELECT DISTINCT origin FROM $table WHERE $col>=?", arrayOf(since.toString())).use { while (it.moveToNext()) o.add(it.getString(0).ifEmpty { "?" }) }
                parts.add("$table: " + (if (o.isEmpty()) "none" else o.joinToString(", ")))
            }
            add("origins", "Data sources per type (7 days)", CheckStatus.PASS, parts.joinToString("\n"))
        }
        return out
    }

    fun summary(checks: List<Check>): String {
        val w = checks.count { it.status == CheckStatus.WARN }; val f = checks.count { it.status == CheckStatus.FAIL }
        return "${checks.size - w - f} pass, $w warn, $f fail"
    }

    fun asText(checks: List<Check>): String = checks.joinToString("\n") {
        "[${it.status.name}] ${it.title}: ${it.detail.replace("\n", "; ")}"
    }
}
