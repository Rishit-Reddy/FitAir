package com.fitair.app.diag

/** Masks secrets before text leaves the app (debug bundle, logs shown to the owner). Pure; unit-tested. */
object Redact {
    private val PATTERNS = listOf(
        Regex("AIza[0-9A-Za-z_\\-]{20,}"),                     // Google API keys
        Regex("sk-[A-Za-z0-9_\\-]{16,}"),                     // OpenAI keys (also sk-proj-...)
        Regex("(?i)(bearer\\s+)[A-Za-z0-9._~+/\\-]{8,}=*"),   // Authorization: Bearer tokens
        Regex("(?i)([?&](?:key|api_key|access_token)=)[^&\\s\"']+"),  // keys in URLs
        Regex("ya29\\.[0-9A-Za-z_\\-]{20,}"),                 // Google OAuth access tokens
    )

    fun text(s: String): String {
        var out = s
        out = PATTERNS[0].replace(out, "AIza***")
        out = PATTERNS[1].replace(out, "sk-***")
        out = PATTERNS[2].replace(out) { it.groupValues[1] + "***" }
        out = PATTERNS[3].replace(out) { it.groupValues[1] + "***" }
        out = PATTERNS[4].replace(out, "ya29.***")
        return out
    }
}
