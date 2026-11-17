package com.github.libretube.util

import com.github.libretube.ui.yt.feed.YtSeenHide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YtSeenRulesTest {
    private val min = 60_000L
    private fun entry(id: String = "a", share: Float = 1f, durationSec: Long? = 600) = YtSeenRules.Entry(id, 1L, share, durationSec)

    /** A player as the position ledger sees it: a snapshot every 5 s while playing, one after each seek. */
    private class Play(private val durationMs: Long) {
        var track: YtSeenRules.Track? = null
        private var now = 0L
        private var position = 0L

        /** Plays [ms] at normal speed; true when one of its snapshots marks the video. */
        fun play(ms: Long): Boolean {
            var marked = false
            var left = ms
            while (left > 0) {
                val step = minOf(5_000L, left)
                now += step
                position += step
                left -= step
                if (snap(seek = false)) marked = true
            }
            return marked
        }

        fun seekTo(ms: Long): Boolean {
            now += 200
            position = ms
            return snap(seek = true)
        }

        fun pause(ms: Long) {
            now += ms
        }

        /** The player reports the end at the position it holds. */
        fun ended(): Boolean = snap(seek = false, ended = true)

        private fun snap(seek: Boolean, ended: Boolean = false): Boolean {
            track = YtSeenRules.track(track, position, durationMs, now, seek)
            return YtSeenRules.shouldMark(position, durationMs, ended, isLive = false, track = track)
        }
    }

    /** The mark's rule on the player's numbers: at most 60 s left after at least 75 %. */
    @Test fun reachedIsTheWatchedMarksRule() {
        assertTrue(YtSeenRules.reached(9 * min, 10 * min))
        assertFalse(YtSeenRules.reached(9 * min - 1_000, 10 * min))
        assertTrue(YtSeenRules.reached(4 * min, 5 * min))
        assertTrue(YtSeenRules.reached(45_000, 60_000))
        assertFalse(YtSeenRules.reached(44_000, 60_000))
        assertFalse(YtSeenRules.reached(5_000, 0))
        // 92 % of an hour with five minutes left is still a started video, as the mark always said
        assertFalse(YtSeenRules.reached(55 * min, 60 * min))
    }

    @Test fun watchedFromTheStartMarks() {
        assertTrue(Play(2 * min).play(2 * min))
        // a 20 s clip: the first snapshots are enough
        assertTrue(Play(20_000).play(20_000))
        // stopped half way: nothing
        assertFalse(Play(20 * min).play(10 * min))
    }

    /** Cause a: a finished video re-opened at 0 and saved small: the old rule drops, the ledger holds. */
    @Test fun reopenedVideoKeepsMark() {
        val durationMs = 600_000L
        // the old rule saw it before (stored at its full duration) and would not now (30 s)
        assertTrue(YtSeenRules.preventedUnmark(true, durationMs, 30_000, durationMs))
        assertFalse(YtSeenRules.preventedUnmark(false, durationMs, 30_000, durationMs))
        // marking again with a small position keeps the best share
        val old = YtSeenRules.mark(null, "a", 590_000, durationMs, ended = false, now = 1)
        val again = YtSeenRules.mark(old, "a", 30_000, durationMs, ended = false, now = 2)
        assertTrue(again.share >= old.share)
        assertTrue(YtSeenRules.isSeen(again, 30_000, 600))
    }

    /** Cause b: a card without duration (null, 0, negative) is never seen by position, but by the ledger. */
    @Test fun cardWithoutDurationUsesLedger() {
        for (d in listOf(null, 0L, -1L)) {
            assertFalse(YtSeenRules.isSeen(null, 590_000, d))
            assertFalse(YtSeenRules.isSeen(null, 590_000, d, strict = true))
            assertTrue(YtSeenRules.isSeen(entry(), 590_000, d))
            assertTrue(YtSeenRules.isSeen(entry(), 590_000, d, strict = true))
            assertTrue(YtSeenHide.finished(null, d, ledgerSeen = true))
        }
        assertNotNull(YtSeenRules.markOf(entry(), null, null))
        assertEquals(true, YtSeenRules.markOf(entry(), null, null)?.watched)
        assertNull(YtSeenRules.markOf(null, null, null))
    }

    /** Cause c: the credits skipped to the end (a sponsor segment, the time bar) after watching the video. */
    @Test fun skipOverTheCreditsToTheEndMarks() {
        val player = Play(20 * min)
        assertFalse(player.play(16 * min))
        assertTrue(player.seekTo(20 * min))
        assertTrue(player.ended())
    }

    /** Cause c: the queue went on; the old video's last snapshot (up to 5 s old) carries "ended". */
    @Test fun endedByQueueAdvanceMarks() {
        val player = Play(3 * min)
        assertFalse(player.play(115_000))
        assertTrue(player.ended())
        assertEquals(1f, YtSeenRules.mark(null, "a", 115_000, 3 * min, true, 1).share, 0f)
    }

    /** Dragging the time bar to the end and back marks nothing, not even when the player reports the end. */
    @Test fun jumpToTheEndMarksNothing() {
        val player = Play(20 * min)
        assertFalse(player.play(5_000))
        assertFalse(player.seekTo(19 * min + 30_000))
        assertFalse(player.seekTo(20 * min))
        assertFalse(player.ended())
        assertFalse(player.seekTo(2 * min))
        assertFalse(player.play(min))
    }

    /** A jump into the last minute marks only when the video then plays into its end. */
    @Test fun jumpThenPlayingToTheEnd() {
        val player = Play(20 * min)
        player.play(5_000)
        assertFalse(player.seekTo(19 * min + 10_000))
        assertFalse(player.play(50_000))
        assertTrue(player.ended())
    }

    /** Continued in a new process near the end: the stretch played from there counts. */
    @Test fun resumedVideoMarksAfterAStretch() {
        val player = Play(20 * min)
        assertFalse(player.seekTo(17 * min))
        assertTrue(player.play(2 * min))
    }

    /** Double taps, sponsor skips and short rewinds do not break the stretch. */
    @Test fun shortSeeksKeepTheStretch() {
        val player = Play(20 * min)
        assertFalse(player.play(18 * min + 40_000))
        assertFalse(player.seekTo(18 * min + 50_000))
        assertTrue(player.seekTo(19 * min))
    }

    /** A long pause does not turn a drag to the end into playing. */
    @Test fun dragAfterALongPauseIsAJump() {
        val player = Play(20 * min)
        player.play(5 * min)
        player.pause(10 * min)
        assertFalse(player.seekTo(19 * min + 30_000))
        assertEquals(0L, player.track!!.runMs)
    }

    /** A repeated section counts once: looping two minutes is not half of the video. */
    @Test fun repeatedSectionCountsOnce() {
        val player = Play(20 * min)
        repeat(10) {
            player.play(2 * min)
            player.seekTo(0)
        }
        assertTrue(YtSeenRules.playedShare(player.track!!) < 0.2)
        assertFalse(player.seekTo(19 * min + 30_000))
    }

    @Test fun liveNeverMarks() {
        val track = YtSeenRules.Track(600_000, 0, 600_000, -1L)
        assertTrue(YtSeenRules.shouldMark(600_000, 600_000, ended = true, isLive = false, track = track))
        assertFalse(YtSeenRules.shouldMark(600_000, 600_000, ended = true, isLive = true, track = track))
        assertFalse(YtSeenRules.shouldMark(600_000, 600_000, ended = true, isLive = false, track = null))
    }

    /** "Gesehenes ausblenden" keeps its stricter rule for an entry that was marked a minute before the end. */
    @Test fun hideRuleStaysStricter() {
        val early = entry(share = 0.8f, durationSec = 300)
        assertTrue(YtSeenRules.isSeen(early, null, 300))
        assertFalse(YtSeenRules.isSeen(early, null, 300, strict = true))
        assertTrue(YtSeenRules.finished(entry(share = 0.9f, durationSec = 3600)))
        assertTrue(YtSeenRules.finished(entry(share = 0.8f, durationSec = 100)))
        assertFalse(YtSeenRules.finished(entry(share = 0.8f, durationSec = null)))
        // an entry that is not finished leaves the decision to the position, as before
        assertTrue(YtSeenRules.isSeen(early, 285_000, 300, strict = true))
    }

    /** Cause d: deleting the watch position does not touch the ledger decision. */
    @Test fun deletedPositionKeepsMark() {
        assertFalse(YtSeenRules.isSeen(null, null, 600))
        assertTrue(YtSeenRules.isSeen(entry(), null, 600))
    }

    @Test fun seedingTakesWhatWasWatched() {
        val seeded = YtSeenRules.seed(
            mapOf("a" to 570_000L, "b" to 100_000L, "c" to 570_000L, "d" to 0L, "e" to Long.MAX_VALUE, "f" to Long.MAX_VALUE),
            mapOf("a" to 600L, "b" to 600L, "d" to 600L, "f" to 120L),
            now = 5,
        )
        assertEquals(listOf("a", "e", "f"), seeded.map { it.id })
        assertEquals(600L, seeded[0].durationSec)
        // the manual "watched" counts without a duration
        assertNull(seeded[1].durationSec)
        assertEquals(1f, seeded[2].share, 0f)
    }

    @Test fun legacyRuleWhenNoEntry() {
        assertTrue(YtSeenRules.isSeen(null, 590_000, 600))
        assertFalse(YtSeenRules.isSeen(null, 100_000, 600))
    }
}
