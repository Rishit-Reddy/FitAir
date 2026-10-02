package com.fitair.app.data.dao

import android.content.Context
import com.fitair.app.AppLog
import com.fitair.app.LocalStore

/** Key/value prefs in the `pref` table (docs/PLAN_081.md 4.1). Best-effort: reads fall back to the default, writes never throw. */
object PrefDao {
    fun get(ctx: Context, k: String): String? = try {
        LocalStore.get(ctx).db.rawQuery("SELECT v FROM pref WHERE k=?", arrayOf(k)).use { if (it.moveToFirst()) it.getString(0) else null }
    } catch (e: Exception) { AppLog.d("pref get $k failed: ${e.message}"); null }

    fun set(ctx: Context, k: String, v: String?) {
        try {
            val db = LocalStore.get(ctx).db
            if (v == null) db.execSQL("DELETE FROM pref WHERE k=?", arrayOf(k))
            else db.execSQL("INSERT OR REPLACE INTO pref(k,v) VALUES(?,?)", arrayOf(k, v))
        } catch (e: Exception) { AppLog.d("pref set $k failed: ${e.message}") }
    }

    fun int(ctx: Context, k: String, def: Int): Int = get(ctx, k)?.trim()?.toIntOrNull() ?: def
    fun double(ctx: Context, k: String): Double? = get(ctx, k)?.trim()?.toDoubleOrNull()
    fun bool(ctx: Context, k: String, def: Boolean): Boolean = get(ctx, k)?.let { it == "1" || it == "true" } ?: def
    fun setBool(ctx: Context, k: String, v: Boolean) = set(ctx, k, if (v) "1" else "0")
}
