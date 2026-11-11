package com.github.libretube.util

import com.github.libretube.util.YtUpdateNotifyRules.Notice
import com.github.libretube.util.YtUpdateNotifyRules.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YtUpdateNotifyRulesTest {
    private fun body(notes: String) =
        "$notes\n\n${YtReleaseNotes.DETAILS_MARKER}\nBuild 128 · commit abcdef1"

    @Test
    fun `interval maps the offered choices and falls back to six hours`() {
        assertEquals(3, YtUpdateNotifyRules.intervalHours("3"))
        assertEquals(6, YtUpdateNotifyRules.intervalHours("6"))
        assertEquals(12, YtUpdateNotifyRules.intervalHours(" 12 "))
        assertEquals(24, YtUpdateNotifyRules.intervalHours("24"))
        assertEquals(6, YtUpdateNotifyRules.intervalHours(null))
        assertEquals(6, YtUpdateNotifyRules.intervalHours(""))
        assertEquals(6, YtUpdateNotifyRules.intervalHours("5"))
        assertEquals(6, YtUpdateNotifyRules.intervalHours("abc"))
        assertEquals(180L, YtUpdateNotifyRules.intervalMinutes(3))
        assertEquals(1440L, YtUpdateNotifyRules.intervalMinutes(24))
        // never below WorkManager's minimum
        assertEquals(15L, YtUpdateNotifyRules.intervalMinutes(0))
    }

    @Test
    fun `only a newer build than installed and than the last notified one is told`() {
        assertTrue(YtUpdateNotifyRules.shouldNotify(installed = 127, offered = 128, lastNotified = null))
        assertTrue(YtUpdateNotifyRules.shouldNotify(127, 129, lastNotified = 128))
        // the same build once only
        assertFalse(YtUpdateNotifyRules.shouldNotify(127, 128, lastNotified = 128))
        // installed or older: never
        assertFalse(YtUpdateNotifyRules.shouldNotify(128, 128, null))
        assertFalse(YtUpdateNotifyRules.shouldNotify(130, 128, null))
        // a pulled release, older than the one told before
        assertFalse(YtUpdateNotifyRules.shouldNotify(120, 125, lastNotified = 128))
        assertFalse(YtUpdateNotifyRules.shouldNotify(127, null, null))
        // an unnumbered local build takes any numbered release, like the in-app check
        assertTrue(YtUpdateNotifyRules.shouldNotify(null, 128, null))
    }

    @Test
    fun `a notification is outdated once its build is installed`() {
        assertTrue(YtUpdateNotifyRules.isOutdated(installed = 128, notified = 128))
        assertTrue(YtUpdateNotifyRules.isOutdated(129, 128))
        assertFalse(YtUpdateNotifyRules.isOutdated(127, 128))
        assertFalse(YtUpdateNotifyRules.isOutdated(127, null))
        assertFalse(YtUpdateNotifyRules.isOutdated(null, 128))
    }

    @Test
    fun `the schedule is replaced only for another interval`() {
        assertFalse(YtUpdateNotifyRules.mustReplace(null, 6))
        assertFalse(YtUpdateNotifyRules.mustReplace(6, 6))
        assertTrue(YtUpdateNotifyRules.mustReplace(6, 12))
    }

    @Test
    fun `notification text takes the first bullets and up to five for the long view`() {
        val notes = """
            ## Neu
            - **Update-Hinweis**: YT meldet neue Builds
            - Zweiter Punkt
            ## Behoben
            - Dritter
            - Vierter
            - Fünfter
            - Sechster
        """.trimIndent()
        val text = YtUpdateNotifyRules.notificationText(body(notes))!!
        assertEquals("Update-Hinweis: YT meldet neue Builds · Zweiter Punkt", text.short)
        assertEquals(
            "• Update-Hinweis: YT meldet neue Builds\n• Zweiter Punkt\n• Dritter\n• Vierter\n• Fünfter\n…",
            text.long
        )
        // the technical part below the marker never shows
        assertFalse(text.long.contains("commit"))
    }

    @Test
    fun `one bullet and no ellipsis when nothing more follows`() {
        val text = YtUpdateNotifyRules.notificationText(body("## Behoben\n- Nur ein Fix"))!!
        assertEquals("Nur ein Fix", text.short)
        assertEquals("• Nur ein Fix", text.long)
    }

    @Test
    fun `plain text lines count when there are no bullets`() {
        val text = YtUpdateNotifyRules.notificationText(body("Kleiner Fix.\nNoch ein Satz."))!!
        assertEquals("Kleiner Fix. · Noch ein Satz.", text.short)
    }

    @Test
    fun `no notes give no text, so the fallback line is used`() {
        assertNull(YtUpdateNotifyRules.notificationText(null))
        assertNull(YtUpdateNotifyRules.notificationText(""))
        // an old body without the marker carries only technical lines
        assertNull(YtUpdateNotifyRules.notificationText("Build 90 · commit abcdef1"))
        assertNull(YtUpdateNotifyRules.notificationText(body("Keine Notizen.")))
        assertNull(YtUpdateNotifyRules.notificationText(body("## Neu")))
    }

    @Test
    fun `outcomes read as diagnosis text`() {
        assertEquals("none", YtUpdateNotifyRules.outcomeText(Outcome.None))
        assertEquals("build 128 (notified)", YtUpdateNotifyRules.outcomeText(Outcome.Found(128, Notice.POSTED)))
        assertEquals("build 128 (already notified)", YtUpdateNotifyRules.outcomeText(Outcome.Found(128, Notice.ALREADY)))
        assertEquals("build 128 (notifications blocked)", YtUpdateNotifyRules.outcomeText(Outcome.Found(128, Notice.BLOCKED)))
        assertEquals("error: HTTP 403", YtUpdateNotifyRules.outcomeText(Outcome.Error("HTTP 403")))
    }

    @Test
    fun `next run is one interval after the last run, or due at once before the first`() {
        assertEquals(1_000L + 6 * 3_600_000L, YtUpdateNotifyRules.nextRunEstimate(1_000L, 500L, 360))
        assertEquals(500L, YtUpdateNotifyRules.nextRunEstimate(null, 500L, 360))
        assertEquals(500L, YtUpdateNotifyRules.nextRunEstimate(0L, 500L, 360))
        assertNull(YtUpdateNotifyRules.nextRunEstimate(null, 0L, 360))
    }
}
