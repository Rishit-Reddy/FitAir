package com.fitair.app

import com.fitair.app.diag.Redact
import org.junit.Assert.*
import org.junit.Test

class RedactTest {
    @Test fun masksGoogleKey() {
        val k = "AIzaSyA1234567890abcdefghijklmnopqrstuv"
        val out = Redact.text("key=$k used")
        assertFalse(out.contains(k)); assertTrue(out.contains("AIza***"))
    }

    @Test fun masksOpenAiKeys() {
        for (k in listOf("sk-abcdefghijklmnopqrstuvwx", "sk-proj-AbC_123-xyzxyzxyzxyzxyz")) {
            val out = Redact.text("Authorization failed for $k.")
            assertFalse(out, out.contains(k)); assertTrue(out.contains("sk-***"))
        }
    }

    @Test fun masksBearerTokens() {
        val out = Redact.text("Authorization: Bearer ya29.a0AfH6SMBxxxxxxxxxxxxxxxxxxxxxxxx")
        assertEquals("Authorization: Bearer ***", out)
        assertEquals("bearer ***", Redact.text("bearer abcdefgh12345678"))
    }

    @Test fun masksKeyInUrl() {
        val out = Redact.text("GET https://x/y?alt=json&key=SECRET123&z=1")
        assertFalse(out.contains("SECRET123")); assertTrue(out.contains("key=***&z=1"))
    }

    @Test fun leavesOrdinaryTextAlone() {
        val s = "sync finished OK in 1200 ms, wrote 53 rows (task sk is fine)"
        assertEquals(s, Redact.text(s))
    }
}
