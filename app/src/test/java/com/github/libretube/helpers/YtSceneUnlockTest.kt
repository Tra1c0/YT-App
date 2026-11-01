package com.github.libretube.helpers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YtSceneUnlockTest {
    private var wall = 1_000L
    private var position = 0L

    // plays for [ms] in readings every 250 ms, as the playback clock reads the player
    private fun YtSceneUnlock.play(ms: Long, speed: Float = 1f, playing: Boolean = true) {
        var left = ms
        while (left > 0) {
            val step = minOf(250L, left)
            wall += step
            if (playing) position += (step * speed).toLong()
            tick(position, playing, speed, wall)
            left -= step
        }
    }

    private fun YtSceneUnlock.seekTo(ms: Long) {
        position = ms
        seek(ms, wall)
    }

    @Test
    fun playbackCarriesTheMark() {
        val unlock = YtSceneUnlock()
        unlock.reset()
        unlock.play(10_000)
        assertEquals(10_000L, unlock.unlockedMs)
        unlock.play(5_000, speed = 2f)
        assertEquals(20_000L, unlock.unlockedMs)
    }

    @Test
    fun pausedPlaybackLeavesTheMark() {
        val unlock = YtSceneUnlock()
        unlock.reset()
        unlock.play(4_000)
        unlock.play(30_000, playing = false)
        assertEquals(4_000L, unlock.unlockedMs)
    }

    @Test
    fun aJumpForwardWaitsFiveSecondsOfPlayback() {
        val unlock = YtSceneUnlock()
        unlock.reset()
        unlock.play(10_000)
        unlock.seekTo(120_000)
        assertTrue(unlock.waiting)
        unlock.play(4_750)
        assertEquals(10_000L, unlock.unlockedMs)
        unlock.play(500)
        assertFalse(unlock.waiting)
        assertEquals(position, unlock.unlockedMs)
        // from there on playback carries it again
        unlock.play(3_000)
        assertEquals(position, unlock.unlockedMs)
    }

    @Test
    fun pausingAfterAJumpDoesNotCount() {
        val unlock = YtSceneUnlock()
        unlock.reset()
        unlock.seekTo(120_000)
        unlock.play(2_000)
        unlock.play(60_000, playing = false)
        unlock.play(2_000)
        assertEquals(0L, unlock.unlockedMs)
        unlock.play(1_000)
        assertTrue(unlock.unlockedMs >= 125_000L)
    }

    @Test
    fun aRunOfSeeksNeverUnlocks() {
        // a scrub seeking live every 60 ms while playing: each seek starts the wait anew
        val unlock = YtSceneUnlock()
        unlock.reset()
        unlock.play(2_000)
        repeat(200) {
            wall += 60
            position += 1_500
            unlock.seek(position, wall)
            if (it % 4 == 0) unlock.tick(position, true, 1f, wall)
        }
        assertEquals(2_000L, unlock.unlockedMs)
    }

    @Test
    fun backIntoWhatWasSeen() {
        val unlock = YtSceneUnlock()
        unlock.reset()
        unlock.play(60_000)
        unlock.seekTo(10_000)
        assertFalse(unlock.waiting)
        unlock.play(30_000)
        assertEquals(60_000L, unlock.unlockedMs)
        unlock.play(40_000)
        assertEquals(80_000L, unlock.unlockedMs)
    }

    @Test
    fun aJumpBackDuringTheWaitStartsItAgain() {
        val unlock = YtSceneUnlock()
        unlock.reset()
        unlock.seekTo(300_000)
        unlock.play(4_000)
        unlock.seekTo(200_000)
        unlock.play(4_000)
        assertEquals(0L, unlock.unlockedMs)
        unlock.play(1_000)
        assertEquals(position, unlock.unlockedMs)
    }

    @Test
    fun unreportedJumpsCountAsSeeks() {
        val unlock = YtSceneUnlock()
        unlock.reset()
        unlock.play(5_000)
        // a jump the player did not report (no seek)
        position = 200_000
        wall += 250
        unlock.tick(position, true, 1f, wall)
        assertEquals(5_000L, unlock.unlockedMs)
        assertTrue(unlock.waiting)
        unlock.play(5_000)
        assertEquals(position, unlock.unlockedMs)
    }

    @Test
    fun aLongGapOfRealPlaybackStillCounts() {
        // the screen was away for a minute while the video played on
        val unlock = YtSceneUnlock()
        unlock.reset()
        unlock.play(5_000)
        wall += 60_000
        position += 60_000
        unlock.tick(position, true, 1f, wall)
        assertEquals(65_000L, unlock.unlockedMs)
    }

    @Test
    fun theFirstReadingOfAResumedVideoWaits() {
        val unlock = YtSceneUnlock()
        unlock.reset()
        position = 600_000
        wall += 250
        unlock.tick(position, true, 1f, wall)
        assertEquals(0L, unlock.unlockedMs)
        unlock.play(5_000)
        assertEquals(position, unlock.unlockedMs)
    }

    @Test
    fun raiseAndRestart() {
        val unlock = YtSceneUnlock()
        unlock.reset()
        assertTrue(unlock.raiseTo(600_000))
        assertFalse(unlock.raiseTo(300_000))
        assertEquals(600_000L, unlock.unlockedMs)
        unlock.restart(100_000, wall)
        assertEquals(600_000L, unlock.unlockedMs)
        position = 100_000
        unlock.play(10_000)
        assertEquals(600_000L, unlock.unlockedMs)
        unlock.restart(700_000, wall)
        assertEquals(700_000L, unlock.unlockedMs)
    }

    @Test
    fun raisingPastAWaitingJumpEndsTheWait() {
        val unlock = YtSceneUnlock()
        unlock.reset()
        unlock.seekTo(400_000)
        assertTrue(unlock.waiting)
        unlock.raiseTo(500_000)
        assertFalse(unlock.waiting)
    }

    @Test
    fun theMarkNeverShrinks() {
        val unlock = YtSceneUnlock()
        unlock.reset()
        var highest = 0L
        val moves = listOf(30_000L, 5_000L, 90_000L, 1_000L, 45_000L, 300_000L, 2_000L)
        for (to in moves) {
            unlock.seekTo(to)
            unlock.play(7_000)
            assertTrue(unlock.unlockedMs >= highest)
            highest = unlock.unlockedMs
        }
    }
}
