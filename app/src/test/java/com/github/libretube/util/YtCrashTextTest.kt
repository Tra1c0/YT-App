package com.github.libretube.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YtCrashTextTest {

    private val header = listOf(
        "Time" to "2026-11-07 14:03:00 +0100",
        "App" to "32.1-yt.123 (7200123, build 123)",
        "Design" to "glass, dark L",
    )

    private fun trace(): String {
        val inner = IllegalStateException("root reason")
        val outer = RuntimeException("outer failure", inner)
        return outer.stackTraceToString()
    }

    @Test
    fun shortTextIsKept() {
        assertEquals("abc", YtCrashText.truncate("abc", 10))
    }

    @Test
    fun longTextKeepsHeadAndTail() {
        val text = "H".repeat(5000) + "M".repeat(5000) + "Caused by: Root"
        val cut = YtCrashText.truncate(text, 1000)
        assertTrue(cut.length <= 1000)
        assertTrue(cut.startsWith("HHH"))
        assertTrue(cut.endsWith("Caused by: Root"))
        assertTrue(cut.contains("chars cut"))
    }

    @Test
    fun reportHasHeaderTraceAndCauses() {
        val report = YtCrashText.report(header, "main", trace())
        assertEquals("main", YtCrashText.field(report, "Thread"))
        assertEquals("glass, dark L", YtCrashText.field(report, "Design"))
        assertTrue(YtCrashText.exceptionLine(report).startsWith("java.lang.RuntimeException: outer failure"))
        assertTrue(report.contains("Caused by: java.lang.IllegalStateException: root reason"))
        assertTrue(report.length <= YtCrashText.MAX_REPORT_CHARS)
    }

    @Test
    fun hugeTraceIsCapped() {
        val report = YtCrashText.report(header, "main", "x".repeat(50_000))
        assertTrue(report.length <= YtCrashText.MAX_REPORT_CHARS)
        assertEquals("main", YtCrashText.field(report, "Thread"))
    }

    @Test
    fun summaryIsOneShortLine() {
        val report = YtCrashText.report(header, "main", trace())
        val summary = YtCrashText.summary(report)
        assertTrue(summary.startsWith("2026-11-07 14:03:00 +0100 [main] java.lang.RuntimeException"))
        assertFalse(summary.contains("\n"))
        assertTrue(summary.length <= 160)
    }

    @Test
    fun secretsAreScrubbed() {
        val text = YtCrashText.scrub(
            "GET https://x.test/a?token=abc123&v=1 Cookie: SAPISID=zzz; Authorization: Bearer eyJhbGc.x"
        )
        assertFalse(text.contains("abc123"))
        assertFalse(text.contains("zzz"))
        assertFalse(text.contains("eyJhbGc"))
        assertTrue(text.contains("v=1"))
        assertTrue(text.contains("token=***"))
    }

    @Test
    fun updateResultIsNotScrubbed() {
        val line = "session 12 try 2: status 3 aborted"
        assertEquals(line, YtCrashText.scrub(line))
    }

    @Test
    fun onlyNewestReportsAreKept() {
        val names = listOf(5L, 1L, 300L, 20L).map(YtCrashText::fileName)
        val gone = YtCrashText.toDelete(names, 3)
        assertEquals(listOf(YtCrashText.fileName(1L)), gone)
    }

    @Test
    fun blockShowsDashForMissingValues() {
        val block = YtCrashText.block("YT diagnosis", listOf("Last update" to null, "Queue" to "4"))
        assertEquals("YT diagnosis\nLast update: -\nQueue: 4", block)
    }
}
