package com.github.libretube.ui.yt.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YtRewindMemoryTest {
    private val duration = 600_000L

    private fun seek(
        from: Long,
        to: Long,
        now: Long,
        cause: YtRewindCause = YtRewindCause.PLAIN,
        video: String? = "v1",
        live: Boolean = false,
    ) = YtRewindEvent.Seek(from, to, duration, video, live, cause, now)

    private fun YtRewindState.then(event: YtRewindEvent) = YtRewindMemory.reduce(this, event)

    @Test
    fun aJumpBackOffersTheWayBackOnceItSettled() {
        val s = YtRewindState().then(seek(300_000, 290_000, now = 1_000))
        assertEquals(300_000L, s.returnMs)
        // the double tap animates first
        assertFalse(s.visible(1_000))
        assertFalse(s.visible(1_000 + YtRewindMemory.SETTLE_MS - 1))
        assertTrue(s.visible(1_000 + YtRewindMemory.SETTLE_MS))
        // for five seconds
        assertTrue(s.visible(1_000 + YtRewindMemory.SETTLE_MS + YtRewindMemory.SHOW_MS - 1))
        assertFalse(s.visible(1_000 + YtRewindMemory.SETTLE_MS + YtRewindMemory.SHOW_MS))
    }

    @Test
    fun aSmallStepBackAloneIsNotWorthIt() {
        val s = YtRewindState().then(seek(300_000, 296_000, now = 0))
        assertFalse(s.hasReturn)
        // exactly five seconds is enough (the shortest seek increment)
        assertTrue(YtRewindState().then(seek(300_000, 295_000, now = 0)).hasReturn)
    }

    @Test
    fun aSeriesKeepsThePointBeforeItsFirstJump() {
        val s = YtRewindState()
            .then(seek(300_000, 290_000, now = 0))
            .then(seek(290_000, 280_000, now = 400))
            .then(seek(280_000, 270_000, now = 800))
        assertEquals(300_000L, s.returnMs)
        // the chip waits for the end of the series
        assertFalse(s.visible(800 + YtRewindMemory.SETTLE_MS - 1))
        assertTrue(s.visible(800 + YtRewindMemory.SETTLE_MS))
        // a small step back inside the series also keeps it and makes it wait
        val step = s.then(seek(270_000, 268_000, now = 1_200))
        assertEquals(300_000L, step.returnMs)
        assertFalse(step.visible(1_200 + YtRewindMemory.SETTLE_MS - 1))
    }

    @Test
    fun aJumpAfterTheOfferEndedStartsAFreshOne() {
        val first = YtRewindState().then(seek(300_000, 290_000, now = 0))
        val later = first.expireAtMs + 10
        val s = first.then(YtRewindEvent.Tick(291_000, "v1", later)).then(seek(295_000, 280_000, now = later + 5))
        assertEquals(295_000L, s.returnMs)
    }

    @Test
    fun seeksMadeOnPurposeAreNoJumps() {
        for (cause in listOf(YtRewindCause.SCRUB, YtRewindCause.CHIP, YtRewindCause.LOOP)) {
            assertFalse(cause.name, YtRewindState().then(seek(300_000, 200_000, now = 0, cause = cause)).hasReturn)
        }
    }

    @Test
    fun aReplayFromTheEndIsNoJump() {
        val s = YtRewindState().then(seek(duration - 500, 0, now = 0))
        assertFalse(s.hasReturn)
        assertTrue(YtRewindState().then(seek(duration - 2_000, 0, now = 0)).hasReturn)
    }

    @Test
    fun liveStreamsGetNoChip() {
        val offered = YtRewindState().then(seek(300_000, 290_000, now = 0))
        assertFalse(offered.then(seek(300_000, 290_000, now = 10, live = true)).hasReturn)
        assertFalse(YtRewindState().then(seek(300_000, 290_000, now = 0, live = true)).hasReturn)
    }

    @Test
    fun goingForwardToThePointEndsTheOffer() {
        val s = YtRewindState().then(seek(300_000, 290_000, now = 0))
        assertFalse(s.then(seek(291_000, 299_500, now = 2_000)).hasReturn)
        assertFalse(s.then(seek(291_000, 400_000, now = 2_000)).hasReturn)
        // a hop forward that stays well before it keeps it
        assertEquals(300_000L, s.then(seek(291_000, 295_000, now = 2_000)).returnMs)
    }

    @Test
    fun theOfferEndsWithItsTimeOrWhenPlaybackReachesThePoint() {
        val s = YtRewindState().then(seek(300_000, 290_000, now = 0))
        val shown = s.showAtMs
        assertTrue(s.then(YtRewindEvent.Tick(292_000, "v1", shown + 100)).hasReturn)
        assertFalse(s.then(YtRewindEvent.Tick(292_000, "v1", s.expireAtMs)).hasReturn)
        // at double speed the video gets there before the five seconds are over
        assertFalse(s.then(YtRewindEvent.Tick(299_800, "v1", shown + 100)).hasReturn)
        assertTrue(s.then(YtRewindEvent.Tick(299_000, "v1", shown + 100)).hasReturn)
    }

    @Test
    fun aWholeScrubCountsAsOneJump() {
        var s = YtRewindState().then(YtRewindEvent.ScrubStart(nowMs = 0))
        // the live picture's seeks and the final seek come while it runs, and late after it
        s = s.then(seek(300_000, 250_000, now = 100))
        s = s.then(seek(250_000, 240_000, now = 200))
        assertFalse(s.hasReturn)
        s = s.then(YtRewindEvent.ScrubStop(300_000, 240_000, false, duration, "v1", false, nowMs = 1_000))
        assertEquals(300_000L, s.returnMs)
        assertTrue(s.visible(1_000 + YtRewindMemory.SETTLE_SCRUB_MS))
        // an echo of the scrub's seek right after it changes nothing
        val echo = s.then(seek(300_000, 240_000, now = 1_200))
        assertEquals(s, echo)
        // a scrub forward is no jump back
        val forward = YtRewindState()
            .then(YtRewindEvent.ScrubStart(nowMs = 0))
            .then(YtRewindEvent.ScrubStop(100_000, 200_000, false, duration, "v1", false, nowMs = 500))
        assertFalse(forward.hasReturn)
    }

    @Test
    fun theChipHidesWhileScrubbingAndAnAbandonedScrubBringsItBack() {
        val offered = YtRewindState().then(seek(300_000, 290_000, now = 0))
        val scrubbing = offered.then(YtRewindEvent.ScrubStart(nowMs = 1_000))
        assertFalse(scrubbing.visible(1_000))
        // the scrub takes longer than the offer would have lasted
        val longAfter = offered.expireAtMs + 3_000
        assertTrue(scrubbing.then(YtRewindEvent.Tick(290_500, "v1", longAfter)).hasReturn)
        val back = scrubbing.then(YtRewindEvent.ScrubStop(290_500, 290_500, true, duration, "v1", false, longAfter))
        assertEquals(300_000L, back.returnMs)
        assertTrue(back.visible(longAfter + YtRewindMemory.SETTLE_SCRUB_MS))
        assertTrue(back.visible(longAfter + YtRewindMemory.SETTLE_SCRUB_MS + YtRewindMemory.SHOW_MS - 1))
    }

    @Test
    fun theTapClearsAndItsOwnSeekIsSwallowed() {
        val s = YtRewindState().then(seek(300_000, 290_000, now = 0))
        val tapped = s.then(YtRewindEvent.Tap(nowMs = 1_000))
        assertFalse(tapped.hasReturn)
        // the chip's seek forward and, should it come untagged, its echo
        assertFalse(tapped.then(seek(291_000, 300_000, now = 1_050)).hasReturn)
        assertFalse(tapped.then(seek(310_000, 300_000, now = 1_100)).hasReturn)
        // later jumps count again
        assertTrue(tapped.then(seek(310_000, 300_000, now = 1_000 + YtRewindMemory.QUIET_MS)).hasReturn)
    }

    @Test
    fun anotherVideoForgetsThePoint() {
        val s = YtRewindState().then(seek(300_000, 290_000, now = 0))
        assertFalse(s.then(YtRewindEvent.Video("v2", nowMs = 100)).hasReturn)
        assertEquals(300_000L, s.then(YtRewindEvent.Video("v1", nowMs = 100)).returnMs)
        assertFalse(s.then(YtRewindEvent.Tick(10_000, "v2", 900)).hasReturn)
        // a jump in the other video starts from scratch, not as a series of the old one
        val other = s.then(seek(100_000, 50_000, now = 300, video = "v2"))
        assertEquals(100_000L, other.returnMs)
        assertEquals("v2", other.videoId)
    }

    @Test
    fun theRingRunsDown() {
        val s = YtRewindState().then(seek(300_000, 290_000, now = 0))
        assertEquals(1f, s.remaining(s.showAtMs), 0.0001f)
        assertEquals(0.5f, s.remaining(s.showAtMs + YtRewindMemory.SHOW_MS / 2), 0.0001f)
        assertEquals(0f, s.remaining(s.expireAtMs + 100), 0.0001f)
    }

    @Test
    fun aScreenReaderGetsMoreTime() {
        val s = YtRewindMemory.reduce(YtRewindState(), seek(300_000, 290_000, now = 0), showMs = 20_000)
        assertTrue(s.visible(s.showAtMs + 19_000))
    }

    @Test
    fun unknownPositionsAreIgnored() {
        assertFalse(YtRewindState().then(seek(-1, 0, now = 0)).hasReturn)
        assertFalse(YtRewindState().then(seek(300_000, -9_223_372_036_854_775_807L, now = 0)).hasReturn)
    }
}
