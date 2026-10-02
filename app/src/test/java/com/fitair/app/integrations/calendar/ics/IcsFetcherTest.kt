package com.fitair.app.integrations.calendar.ics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IcsFetcherTest {
    private val google = "https://calendar.google.com/calendar/ical/abc%40gmail.com/private-0123456789abcdef/basic.ics"

    @Test fun httpsPassesThrough() {
        val n = IcsFetcher.normalize("  $google \n")
        assertEquals(google, n.url); assertNull(n.error)
    }

    @Test fun webcalBecomesHttps() {
        assertEquals("https://example.com/a.ics?x=1", IcsFetcher.normalize("webcal://example.com/a.ics?x=1").url)
        assertEquals("https://example.com/a.ics", IcsFetcher.normalize("WEBCAL://example.com/a.ics").url)
        assertEquals("https://example.com/a.ics", IcsFetcher.normalize("webcals://example.com/a.ics").url)
    }

    @Test fun quotesAndBracketsAreStripped() {
        assertEquals("https://example.com/a.ics", IcsFetcher.normalize("<https://example.com/a.ics>").url)
        assertEquals("https://example.com/a.ics", IcsFetcher.normalize("\"https://example.com/a.ics\"").url)
    }

    @Test fun plainHttpAndOtherSchemesAreRefused() {
        for (u in listOf("http://example.com/a.ics", "HTTP://example.com/a.ics", "ftp://example.com/a.ics", "file:///etc/passwd", "javascript://x")) {
            val n = IcsFetcher.normalize(u)
            assertNull(u, n.url); assertNotNull(n.error)
        }
    }

    @Test fun garbageIsRefused() {
        for (u in listOf("", "   ", "example.com/a.ics", "https://", "https:///a.ics", "https://exa mple.com/a.ics", "not a link")) {
            assertNull(u, IcsFetcher.normalize(u).url)
        }
    }

    @Test fun errorMessagesNeverContainTheUrl() {
        val secret = "private-0123456789abcdef"
        for (u in listOf("http://calendar.google.com/calendar/ical/x/$secret/basic.ics", "ftp://h/$secret")) {
            assertFalse(IcsFetcher.normalize(u).error!!.contains(secret))
        }
        for (c in listOf(401, 403, 404, 410, 429, 500, 503, 418)) assertTrue(IcsFetcher.httpMessage(c).isNotBlank())
        assertTrue(IcsFetcher.httpMessage(404).contains("404"))
    }

    @Test fun looksLikeCalendar() {
        assertTrue(IcsFetcher.looksLikeCalendar("﻿\r\nBEGIN:VCALENDAR\r\n"))
        assertFalse(IcsFetcher.looksLikeCalendar("<html>sign in</html>"))
    }
}
