package com.github.libretube.ui.yt.mini

import com.github.libretube.ui.yt.mini.YtSoftCloseRules.Route
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YtSoftCloseRulesTest {
    private val t = 5_000_000L
    private val hold = YtSoftCloseRules.HOLD_MS

    @Test
    fun keepsOnlyALoadedOnlineVideo() {
        val ready = YtSoftCloseRules.STATE_READY
        assertTrue(YtSoftCloseRules.keeps(connected = true, offline = false, live = false, hasMedia = true, playbackState = ready))
        assertTrue(YtSoftCloseRules.keeps(true, false, false, true, YtSoftCloseRules.STATE_BUFFERING))
        assertFalse(YtSoftCloseRules.keeps(false, false, false, true, ready))
        assertFalse(YtSoftCloseRules.keeps(true, true, false, true, ready))
        assertFalse(YtSoftCloseRules.keeps(true, false, true, true, ready))
        assertFalse(YtSoftCloseRules.keeps(true, false, false, false, ready))
        assertFalse(YtSoftCloseRules.keeps(true, false, false, true, YtSoftCloseRules.STATE_ENDED))
        assertFalse(YtSoftCloseRules.keeps(true, false, false, true, YtSoftCloseRules.STATE_IDLE))
    }

    @Test
    fun resumesTheSameVideoWithinTheMinute() {
        assertEquals(Route.RESUME, YtSoftCloseRules.route("a", t, "a", t))
        assertEquals(Route.RESUME, YtSoftCloseRules.route("a", t, "a", t + 30_000))
        assertEquals(Route.RESUME, YtSoftCloseRules.route("a", t, "a", t + hold))
    }

    @Test
    fun restartsAfterTheMinute() {
        assertEquals(Route.RESTART, YtSoftCloseRules.route("a", t, "a", t + hold + 1))
        assertEquals(Route.RESTART, YtSoftCloseRules.route("a", t, "a", t + 9 * hold))
    }

    @Test
    fun restartsWithoutAKeptPlayerOrForAnotherVideo() {
        assertEquals(Route.RESTART, YtSoftCloseRules.route(null, t, "a", t))
        assertEquals(Route.RESTART, YtSoftCloseRules.route("b", t, "a", t))
        assertEquals(Route.RESTART, YtSoftCloseRules.route("a", t, null, t))
    }

    @Test
    fun anotherClockCountsAsExpired() {
        assertTrue(YtSoftCloseRules.expired(t, t - 1))
        assertEquals(Route.RESTART, YtSoftCloseRules.route("a", t, "a", t - 1))
    }

    @Test
    fun remainingTime() {
        assertEquals(hold, YtSoftCloseRules.remainingMs(t, t))
        assertEquals(15_000L, YtSoftCloseRules.remainingMs(t, t + hold - 15_000))
        assertEquals(0L, YtSoftCloseRules.remainingMs(t, t + hold + 1))
        assertEquals(0L, YtSoftCloseRules.remainingMs(t, t - 5))
    }

    @Test
    fun onlyTheAskedPagePlaysOnAttach() {
        assertTrue(YtSoftCloseRules.playsOnAttach("a", t, "a", t + 300))
        assertFalse(YtSoftCloseRules.playsOnAttach("a", t, "b", t + 300))
        assertFalse(YtSoftCloseRules.playsOnAttach(null, t, "a", t + 300))
        assertFalse(YtSoftCloseRules.playsOnAttach("a", t, "a", t + YtSoftCloseRules.ATTACH_MS + 1))
        assertFalse(YtSoftCloseRules.playsOnAttach("a", t, "a", t - 1))
    }

    @Test
    fun backgroundWarningsKeepThePlayer() {
        // UI_HIDDEN (20) and BACKGROUND (40) come whenever the app leaves the screen
        assertFalse(YtSoftCloseRules.releasesOnTrim(20))
        assertFalse(YtSoftCloseRules.releasesOnTrim(40))
        assertFalse(YtSoftCloseRules.releasesOnTrim(5))
        assertTrue(YtSoftCloseRules.releasesOnTrim(10))
        assertTrue(YtSoftCloseRules.releasesOnTrim(15))
        assertTrue(YtSoftCloseRules.releasesOnTrim(60))
        assertTrue(YtSoftCloseRules.releasesOnTrim(80))
    }
}
