package com.rhecyee.firelinemap.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashLogTest {

    private fun entry(message: String) = CrashLog.format(
        threadName = "main",
        error = IllegalStateException(message),
        atMillis = 1789102940988L,
        versionName = "0.5.2"
    )

    @Test
    fun `an entry says what threw, where, and which build`() {
        val text = entry("base context was null")
        assertTrue(text, text.contains("IllegalStateException"))
        assertTrue(text, text.contains("base context was null"))
        assertTrue(text, text.contains("v0.5.2"))
        assertTrue(text, text.contains("thread=main"))
        // The stack itself, which is the part worth pasting.
        assertTrue(text, text.contains("CrashLogTest"))
    }

    @Test
    fun `the newest crash is the one at the top`() {
        // Somebody opening this has just crashed; that is the one they need.
        val log = CrashLog.trim(entry("second") + entry("first"))
        assertTrue(log.indexOf("second") < log.indexOf("first"))
    }

    @Test
    fun `only the last few are kept`() {
        val log = CrashLog.trim((1..12).joinToString("") { entry("crash $it") })
        val kept = (1..12).count { log.contains("crash $it") }
        assertEquals(CrashLog.KEEP, kept)
    }

    @Test
    fun `a crash loop cannot fill the device`() {
        val huge = entry("x".repeat(200_000))
        val log = CrashLog.trim(huge + huge)
        assertTrue("log grew to ${log.length}", log.length <= CrashLog.MAX_BYTES)
    }

    @Test
    fun `truncating drops the oldest rather than the one being read`() {
        val big = entry("older " + "x".repeat(120_000))
        val log = CrashLog.trim(entry("newest") + big)
        assertTrue("the newest crash was dropped", log.contains("newest"))
    }

    @Test
    fun `an empty log stays empty`() {
        assertEquals("", CrashLog.trim(""))
        assertEquals("", CrashLog.trim("   \n  "))
    }

    @Test
    fun `a crash with no message still records its type`() {
        val text = CrashLog.format("main", NullPointerException(), 1789102940988L, "0.5.2")
        assertTrue(text, text.contains("NullPointerException"))
    }

    @Test
    fun `a cause is carried through`() {
        // The interesting line is usually in the cause, not the wrapper.
        val error = RuntimeException("outer", IllegalArgumentException("the real reason"))
        val text = CrashLog.format("main", error, 1789102940988L, "0.5.2")
        assertTrue(text, text.contains("the real reason"))
    }
}
