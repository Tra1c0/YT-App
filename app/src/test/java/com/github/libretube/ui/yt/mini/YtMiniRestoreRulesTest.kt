package com.github.libretube.ui.yt.mini

import com.github.libretube.ui.yt.mini.YtMiniRestoreRules.Verdict
import org.junit.Assert.assertEquals
import org.junit.Test

class YtMiniRestoreRulesTest {
    private val closedAt = 1_000_000L
    private val minute = 60_000L

    @Test
    fun nothingSaved() {
        assertEquals(Verdict.NOTHING_SAVED, YtMiniRestoreRules.decide(null, closedAt, playerShowing = false))
        assertEquals(Verdict.NOTHING_SAVED, YtMiniRestoreRules.decide(null, closedAt, playerShowing = true))
    }

    @Test
    fun restoresWithinTenMinutes() {
        assertEquals(Verdict.RESTORE, YtMiniRestoreRules.decide(closedAt, closedAt, playerShowing = false))
        assertEquals(Verdict.RESTORE, YtMiniRestoreRules.decide(closedAt, closedAt + 9 * minute, playerShowing = false))
        assertEquals(Verdict.RESTORE, YtMiniRestoreRules.decide(closedAt, closedAt + 10 * minute, playerShowing = false))
    }

    @Test
    fun expiresAfterTenMinutes() {
        assertEquals(Verdict.EXPIRED, YtMiniRestoreRules.decide(closedAt, closedAt + 10 * minute + 1, playerShowing = false))
        // even with a player showing the old session is dropped
        assertEquals(Verdict.EXPIRED, YtMiniRestoreRules.decide(closedAt, closedAt + 30 * minute, playerShowing = true))
    }

    @Test
    fun clockGoingBackIsExpired() {
        assertEquals(Verdict.EXPIRED, YtMiniRestoreRules.decide(closedAt, closedAt - 1, playerShowing = false))
    }

    @Test
    fun playerShowingBlocks() {
        assertEquals(Verdict.PLAYER_SHOWING, YtMiniRestoreRules.decide(closedAt, closedAt + minute, playerShowing = true))
    }

    @Test
    fun waitsForTheOldServiceToSettle() {
        assertEquals(YtMiniRestoreRules.SETTLE_MS, YtMiniRestoreRules.startDelay(closedAt, closedAt))
        assertEquals(500L, YtMiniRestoreRules.startDelay(closedAt, closedAt + YtMiniRestoreRules.SETTLE_MS - 500))
        assertEquals(0L, YtMiniRestoreRules.startDelay(closedAt, closedAt + YtMiniRestoreRules.SETTLE_MS))
        assertEquals(0L, YtMiniRestoreRules.startDelay(closedAt, closedAt + minute))
    }

    @Test
    fun startSecond() {
        assertEquals(0L, YtMiniRestoreRules.startSeconds(0, 600_000))
        assertEquals(0L, YtMiniRestoreRules.startSeconds(-5, 600_000))
        assertEquals(125L, YtMiniRestoreRules.startSeconds(125_900, 600_000))
        // unknown duration (live, not loaded): where it was
        assertEquals(125L, YtMiniRestoreRules.startSeconds(125_900, -1))
        // at the end: from the start again
        assertEquals(0L, YtMiniRestoreRules.startSeconds(598_000, 600_000))
        assertEquals(596L, YtMiniRestoreRules.startSeconds(596_999, 600_000))
    }
}
