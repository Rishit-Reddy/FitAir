package com.fitair.app.backup

import android.content.Context
import android.util.Base64
import com.fitair.app.secure.SecretStore
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class WrongPassphraseException : Exception("Wrong passphrase, or the backup file is damaged")
class NeedsPassphraseException : Exception("This backup is encrypted: enter its passphrase")

/**
 * Optional backup encryption. File layout: "FABK" | version(1) | iterations(4) | salt(16) | noncePrefix(8) | chunks.
 * Each chunk is up to 1 MiB of plaintext sealed with AES-256-GCM (nonce = prefix + chunk counter, AAD = header + last-chunk flag,
 * so reordering or truncation is detected). Key = PBKDF2-HMAC-SHA256(passphrase, salt, >= 200k iterations).
 * The passphrase itself is never stored; only the derived key (wrapped by [SecretStore]) so the daily backup can run unattended.
 */
object BackupCrypto {
    private val MAGIC = byteArrayOf('F'.code.toByte(), 'A'.code.toByte(), 'B'.code.toByte(), 'K'.code.toByte())
    private const val VERSION: Byte = 1
    const val ITERATIONS = 210_000
    private const val HEADER = 4 + 1 + 4 + 16 + 8
    private const val CHUNK = 1 shl 20
    private const val TAG = 16
    private const val SECRET = "backup_enc"
    private val rng = SecureRandom()

    class Key(val iterations: Int, val salt: ByteArray, val key: ByteArray)

    fun derive(passphrase: String, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(passphrase.toCharArray(), salt, iterations, 256)
        try { return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded } finally { spec.clearPassword() }
    }

    fun isEnabled(ctx: Context) = SecretStore.get(ctx, SECRET).isNotEmpty()

    /** Derives a key from [passphrase] (new random salt) and stores it wrapped by the Keystore. */
    fun enable(ctx: Context, passphrase: String) {
        val salt = ByteArray(16).also { rng.nextBytes(it) }
        val k = derive(passphrase, salt, ITERATIONS)
        SecretStore.set(ctx, SECRET, "$ITERATIONS:${b64(salt)}:${b64(k)}")
    }

    fun disable(ctx: Context) = SecretStore.set(ctx, SECRET, "")

    fun loadKey(ctx: Context): Key? {
        val p = SecretStore.get(ctx, SECRET).split(':')
        if (p.size != 3) return null
        return Key(p[0].toInt(), Base64.decode(p[1], Base64.NO_WRAP), Base64.decode(p[2], Base64.NO_WRAP))
    }

    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)

    fun isEncrypted(f: File): Boolean = f.inputStream().use { i ->
        val h = ByteArray(4)
        i.read(h) == 4 && h.contentEquals(MAGIC)
    }

    private fun nonce(prefix: ByteArray, counter: Int) = prefix + ByteBuffer.allocate(4).putInt(counter).array()

    private fun cipher(mode: Int, key: ByteArray, nonce: ByteArray, header: ByteArray, last: Boolean): Cipher {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG * 8, nonce))
        c.updateAAD(header); c.updateAAD(byteArrayOf(if (last) 1 else 0))
        return c
    }

    fun encrypt(src: File, dst: File, k: Key) {
        val prefix = ByteArray(8).also { rng.nextBytes(it) }
        val header = ByteBuffer.allocate(HEADER).put(MAGIC).put(VERSION).putInt(k.iterations).put(k.salt).put(prefix).array()
        val total = src.length()
        val chunks = maxOf(1L, (total + CHUNK - 1) / CHUNK)
        dst.outputStream().buffered(64 * 1024).use { out ->
            out.write(header)
            src.inputStream().use { ins ->
                val buf = ByteArray(CHUNK)
                for (i in 0 until chunks) {
                    val n = minOf(CHUNK.toLong(), total - i * CHUNK).toInt()
                    readFully(ins, buf, n)
                    val c = cipher(Cipher.ENCRYPT_MODE, k.key, nonce(prefix, i.toInt()), header, i == chunks - 1)
                    out.write(c.doFinal(buf, 0, n))
                }
            }
        }
    }

    /** @throws WrongPassphraseException if the passphrase is wrong or the file was altered. */
    fun decrypt(src: File, dst: File, passphrase: String) {
        src.inputStream().buffered(64 * 1024).use { ins ->
            val header = ByteArray(HEADER)
            readFully(ins, header, HEADER)
            val bb = ByteBuffer.wrap(header)
            val magic = ByteArray(4); bb.get(magic)
            if (!magic.contentEquals(MAGIC) || bb.get() != VERSION) throw IllegalArgumentException("Not a FitAir encrypted backup")
            val iter = bb.getInt()
            val salt = ByteArray(16); bb.get(salt)
            val prefix = ByteArray(8); bb.get(prefix)
            require(iter in 1000..10_000_000) { "Bad backup header" }
            val key = derive(passphrase, salt, iter)
            val body = src.length() - HEADER
            val chunks = maxOf(1L, (body + CHUNK + TAG - 1) / (CHUNK + TAG))
            dst.outputStream().buffered(64 * 1024).use { out ->
                val buf = ByteArray(CHUNK + TAG)
                for (i in 0 until chunks) {
                    val n = minOf((CHUNK + TAG).toLong(), body - i * (CHUNK + TAG)).toInt()
                    if (n < TAG) throw WrongPassphraseException()
                    readFully(ins, buf, n)
                    try {
                        out.write(cipher(Cipher.DECRYPT_MODE, key, nonce(prefix, i.toInt()), header, i == chunks - 1).doFinal(buf, 0, n))
                    } catch (e: AEADBadTagException) { throw WrongPassphraseException() }
                }
            }
        }
    }

    private fun readFully(i: InputStream, b: ByteArray, n: Int) {
        var off = 0
        while (off < n) {
            val r = i.read(b, off, n - off)
            if (r < 0) throw java.io.EOFException("Backup file is truncated")
            off += r
        }
    }
}
