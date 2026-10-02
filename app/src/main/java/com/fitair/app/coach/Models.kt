package com.fitair.app.coach

import android.content.Context
import com.fitair.app.SyncPrefs

/** Model defaults, output caps and per-model quirks learned at runtime (doc 3.7). */
object Models {
    const val GEMINI_DEFAULT = "gemini-3.5-flash-lite"
    const val OPENAI_DEFAULT = "gpt-4o-mini"
    const val GEMINI_REVIEW = "gemini-3.8-flash"

    const val CAP_CHAT = 400
    const val CAP_LONG = 1200
    const val CAP_PLAN = 200
    const val CAP_REVIEW = 1500

    fun default(provider: String) = if (provider == "openai") OPENAI_DEFAULT else GEMINI_DEFAULT

    /** The user's configured model for [provider], else the default. */
    fun selected(ctx: Context, provider: String): String {
        val p = ctx.getSharedPreferences(SyncPrefs.FILE, Context.MODE_PRIVATE)
        return (p.getString(if (provider == "openai") "coach_model_openai" else "coach_model_gemini", "") ?: "").trim()
            .ifEmpty { default(provider) }
    }
}

/** Remembers, per model, which optional request fields the API rejected (prefs, so it survives restarts). */
open class ModelQuirks(private val ctx: Context?) {
    private fun prefs() = ctx?.getSharedPreferences(SyncPrefs.FILE, Context.MODE_PRIVATE)
    open fun dropThinking(model: String) = prefs()?.getBoolean("quirk_nothink_$model", false) ?: false
    open fun dropSchema(model: String) = prefs()?.getBoolean("quirk_noschema_$model", false) ?: false
    open fun remember(model: String, thinking: Boolean, schema: Boolean) {
        val e = prefs()?.edit() ?: return
        if (thinking) e.putBoolean("quirk_nothink_$model", true)
        if (schema) e.putBoolean("quirk_noschema_$model", true)
        e.apply()
    }
}
