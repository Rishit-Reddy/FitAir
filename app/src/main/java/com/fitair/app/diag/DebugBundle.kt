package com.fitair.app.diag

import android.content.Context
import com.fitair.app.AppLog
import com.fitair.app.LocalStore
import com.fitair.app.SyncPrefs
import com.fitair.app.analytics.Check
import com.fitair.app.analytics.SelfCheck
import com.fitair.app.core.Format
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Redacted, copyable text with everything the builder needs to debug the app remotely. */
object DebugBundle {
    fun version(ctx: Context): String = try {
        val p = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        "${p.versionName} (${if (android.os.Build.VERSION.SDK_INT >= 28) p.longVersionCode else 0})"
    } catch (e: Exception) { "unknown" }

    /** Last [n] ai_call rows, newest first, one line each (no prompt text, no health values). */
    fun recentAiCalls(ctx: Context, n: Int): List<String> = try {
        val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)
        val out = ArrayList<String>()
        LocalStore.get(ctx).db.rawQuery(
            "SELECT ts,task,model,in_tok,out_tok,thought_tok,latency_ms,ok,finish,error FROM ai_call ORDER BY ts DESC LIMIT $n", null
        ).use { c ->
            while (c.moveToNext()) {
                fun i(k: Int) = if (c.isNull(k)) "-" else c.getLong(k).toString()
                out.add("${fmt.format(Date(c.getLong(0)))} ${c.getString(1)} ${c.getString(2) ?: "?"} " +
                    "in ${i(3)} out ${i(4)} think ${i(5)} ${i(6)} ms ${if (!c.isNull(7) && c.getInt(7) == 1) "ok" else "FAIL"} ${c.getString(8) ?: ""}" +
                    (c.getString(9)?.takeIf { it.isNotEmpty() }?.let { " err=" + it.take(120) } ?: ""))
            }
        }
        out
    } catch (e: Exception) { emptyList() }

    fun build(ctx: Context, checks: List<Check>?): String {
        val store = LocalStore.get(ctx)
        val prefs = ctx.getSharedPreferences(SyncPrefs.FILE, Context.MODE_PRIVATE)
        val sb = StringBuilder()
        sb.append("FitAir debug bundle\n")
        sb.append("app ${version(ctx)}  android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})  ${android.os.Build.MODEL}\n")
        sb.append("time ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date())}\n")
        val last = prefs.getLong(SyncPrefs.LAST, 0L)
        sb.append("last sync: ${Format.freshness(System.currentTimeMillis(), last).text}  status=${prefs.getString(SyncPrefs.STATUS, null) ?: "-"}\n")
        sb.append("db: ${"%.1f".format(store.sizeBytes() / 1048576.0)} MB, schema v${LocalStore.VERSION}\n")
        sb.append("\n== self-check ==\n")
        sb.append(if (checks == null) "not run\n" else SelfCheck.summary(checks) + "\n" + SelfCheck.asText(checks) + "\n")
        sb.append("\n== row counts ==\n")
        try {
            store.counts().forEach { (k, v) -> sb.append("$k $v\n") }
            for (t in LocalStore.APP_TABLES) store.db.rawQuery("SELECT COUNT(*) FROM $t", null).use { if (it.moveToFirst()) sb.append("$t ${it.getInt(0)}\n") }
        } catch (e: Exception) { sb.append("counts failed: ${e.message}\n") }
        sb.append("\n== last 5 ai_call ==\n")
        recentAiCalls(ctx, 5).ifEmpty { listOf("none") }.forEach { sb.append(it).append('\n') }
        sb.append("\n== last 200 log lines ==\n")
        sb.append(AppLog.text().lines().takeLast(200).joinToString("\n"))
        return Redact.text(sb.toString())
    }
}
