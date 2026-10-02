package com.fitair.app.integrations.calendar.ics

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URL
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * Downloads an iCal feed. The URL is a secret: it is never logged and never appears in any message produced here.
 * Pure helpers ([normalize], [httpMessage]) are unit-tested; [fetch] does the blocking network call.
 */
object IcsFetcher {
    const val MAX_BYTES = 5 * 1024 * 1024
    const val CONNECT_MS = 10_000
    const val READ_MS = 20_000
    const val MAX_REDIRECTS = 5

    /** [url] is the https URL to use, or null with a user-facing [error] (never containing the input). */
    class Norm(val url: String?, val error: String?)

    sealed class Fetch {
        class Ok(val text: String, val etag: String?, val lastModified: String?) : Fetch()
        object NotModified : Fetch()
        class Err(val message: String) : Fetch()
    }

    private const val NEED_HTTPS = "Only secure https:// (or webcal://) links are accepted."

    fun normalize(raw: String): Norm {
        var s = raw.trim().trim('"', '\'', '<', '>').trim()
        if (s.isEmpty()) return Norm(null, "Paste the calendar's iCal link first.")
        if (s.any { it.isWhitespace() }) return Norm(null, "That does not look like a link: it contains spaces.")
        val m = Regex("^([A-Za-z][A-Za-z0-9+.-]*)://(.*)$").matchEntire(s)
            ?: return Norm(null, "That does not look like a link. It should start with https:// or webcal://.")
        val scheme = m.groupValues[1].lowercase()
        val rest = m.groupValues[2]
        s = when (scheme) {
            "webcal", "webcals", "https" -> "https://$rest"
            "http" -> return Norm(null, NEED_HTTPS)
            else -> return Norm(null, NEED_HTTPS)
        }
        return try {
            val u = URI(s)
            if (u.host.isNullOrBlank()) Norm(null, "That link has no web address in it.") else Norm(s, null)
        } catch (e: Exception) {
            Norm(null, "That does not look like a valid link.")
        }
    }

    fun httpMessage(code: Int): String = when {
        code == 401 || code == 403 -> "The calendar refused access ($code). Use the secret iCal address, and make sure it has not been reset."
        code == 404 || code == 410 -> "The calendar was not found ($code). Check that you copied the whole iCal address."
        code == 429 -> "The calendar server asked us to slow down (429). Try again later."
        code in 500..599 -> "The calendar server has a problem ($code). Try again later."
        else -> "The calendar server answered with an error ($code)."
    }

    /** Blocking. Call off the main thread. Conditional when [etag] / [lastModified] are given. */
    fun fetch(url: String, etag: String? = null, lastModified: String? = null): Fetch {
        var cur = url
        try {
            for (hop in 0..MAX_REDIRECTS) {
                val n = normalize(cur)
                val safe = n.url ?: return Fetch.Err(if (hop == 0) n.error ?: "Invalid link." else "The calendar redirected somewhere that is not secure (https).")
                val conn = URL(safe).openConnection() as HttpURLConnection
                try {
                    conn.instanceFollowRedirects = false
                    conn.connectTimeout = CONNECT_MS
                    conn.readTimeout = READ_MS
                    conn.setRequestProperty("User-Agent", "FitAir-calendar")
                    conn.setRequestProperty("Accept", "text/calendar, text/plain, */*")
                    if (hop == 0) {
                        if (!etag.isNullOrEmpty()) conn.setRequestProperty("If-None-Match", etag)
                        if (!lastModified.isNullOrEmpty()) conn.setRequestProperty("If-Modified-Since", lastModified)
                    }
                    val code = conn.responseCode
                    when {
                        code == 304 -> return Fetch.NotModified
                        code in 300..399 && code != 304 -> {
                            val loc = conn.getHeaderField("Location") ?: return Fetch.Err("The calendar redirected without saying where.")
                            cur = try { URI(safe).resolve(loc).toString() } catch (e: Exception) { return Fetch.Err("The calendar redirected somewhere invalid.") }
                            continue
                        }
                        code != 200 -> return Fetch.Err(httpMessage(code))
                    }
                    val len = conn.contentLengthLong
                    if (len > MAX_BYTES) return Fetch.Err(tooBig())
                    val buf = ByteArrayOutputStream()
                    conn.inputStream.use { ins ->
                        val b = ByteArray(16 * 1024)
                        while (true) {
                            val r = ins.read(b)
                            if (r < 0) break
                            buf.write(b, 0, r)
                            if (buf.size() > MAX_BYTES) return Fetch.Err(tooBig())
                        }
                    }
                    val text = buf.toString("UTF-8")
                    if (!looksLikeCalendar(text)) return Fetch.Err("That link did not return a calendar (iCal) file.")
                    return Fetch.Ok(text, conn.getHeaderField("ETag"), conn.getHeaderField("Last-Modified"))
                } finally { conn.disconnect() }
            }
            return Fetch.Err("The calendar redirected too many times.")
        } catch (e: SocketTimeoutException) {
            return Fetch.Err("The calendar server took too long to answer.")
        } catch (e: UnknownHostException) {
            return Fetch.Err("Cannot reach the calendar server. Check your connection and the address.")
        } catch (e: SSLException) {
            return Fetch.Err("Could not make a secure connection to the calendar server.")
        } catch (e: IOException) {
            return Fetch.Err("Could not download the calendar. Check your connection.")
        } catch (e: Exception) {
            return Fetch.Err("Could not download the calendar.")
        }
    }

    private fun tooBig() = "The calendar file is too large (over 5 MB)."

    fun looksLikeCalendar(text: String): Boolean = text.trimStart('﻿', ' ', '\r', '\n', '\t').startsWith("BEGIN:VCALENDAR", ignoreCase = true)
}
