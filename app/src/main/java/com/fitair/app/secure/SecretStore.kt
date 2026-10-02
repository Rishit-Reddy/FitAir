package com.fitair.app.secure

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.fitair.app.AppLog
import com.fitair.app.SyncPrefs
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Secrets (API keys, wrapped backup key) encrypted with a non-exportable AES-256-GCM key in the Android Keystore.
 * Ciphertext (base64 of iv + ct) lives in its own SharedPreferences file. Names in use: "gemini_key", "openai_key", "backup_enc".
 */
object SecretStore {
    private const val ALIAS = "fitair_secret_v1"
    private const val FILE = "fitair_secrets"
    private val LEGACY = setOf("gemini_key", "openai_key")

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        g.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build()
        )
        return g.generateKey()
    }

    private fun sp(ctx: Context) = ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Decrypted value, or "" if unset or undecryptable. Falls back to (and migrates) a legacy plaintext pref. */
    @Synchronized
    fun get(ctx: Context, name: String): String {
        if (name in LEGACY) migrateFromPrefs(ctx) // plaintext written by an older settings screen wins
        val raw = sp(ctx).getString(name, null)
        if (!raw.isNullOrEmpty()) {
            try {
                val b = Base64.decode(raw, Base64.NO_WRAP)
                val c = Cipher.getInstance("AES/GCM/NoPadding")
                c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, b, 0, 12))
                return String(c.doFinal(b, 12, b.size - 12), Charsets.UTF_8)
            } catch (e: Exception) {
                AppLog.e("SecretStore: cannot decrypt '$name'", e)
            }
        }
        return ""
    }

    @Synchronized
    fun set(ctx: Context, name: String, value: String) {
        if (value.isEmpty()) { sp(ctx).edit().remove(name).apply(); return }
        try {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, key())
            val ct = c.doFinal(value.toByteArray(Charsets.UTF_8))
            sp(ctx).edit().putString(name, Base64.encodeToString(c.iv + ct, Base64.NO_WRAP)).apply()
        } catch (e: Exception) {
            AppLog.e("SecretStore: cannot store '$name'", e)
            throw IllegalStateException("Secure storage unavailable on this device", e)
        }
    }

    /** Moves plaintext keys from the 'fitair' prefs file into the store and deletes them there. Idempotent. */
    @Synchronized
    fun migrateFromPrefs(ctx: Context) {
        val old = ctx.applicationContext.getSharedPreferences(SyncPrefs.FILE, Context.MODE_PRIVATE)
        for (n in LEGACY) {
            val v = old.getString(n, null) ?: continue
            try {
                if (v.isNotBlank()) set(ctx, n, v.trim())
                old.edit().remove(n).apply()
                AppLog.d("SecretStore: migrated $n")
            } catch (e: Exception) {
                AppLog.e("SecretStore: migrate $n failed (plaintext kept)", e)
            }
        }
    }
}
