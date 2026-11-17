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
    private fun entry(id: String = "a") = YtSeenRules.Entry(id, 1L, 1f, 600)

    @Test fun reachedAtNinetyPercent() {
        assertTrue(YtSeenRules.reached(90_000, 100_000))
        assertFalse(YtSeenRules.reached(89_000, 100_000))
        assertFalse(YtSeenRules.reached(5_000, 0))
    }

    /** Cause e: a long video with long credits: 60 s left counts from 10 minutes on, not below. */
    @Test fun longVideoLastMinute() {
        assertTrue(YtSeenRules.reached(10 * min - 60_000, 10 * min))
        assertFalse(YtSeenRules.reached(10 * min - 61_000, 10 * min))
        // a 5 minute video with 60 s left is only 80 %: not marked
        assertFalse(YtSeenRules.reached(4 * min, 5 * min))
        // 85 % of 20 minutes (3 min of credits) stays unmarked, and the old rules call it unseen too
        assertFalse(YtSeenRules.reached(17 * min, 20 * min))
        assertFalse(YtSeenRules.isSeen(null, 17 * min, 20 * 60L, strict = true))
    }

    @Test fun endedMarksWhateverThePosition() {
        assertTrue(YtSeenRules.shouldMark(10_000, 600_000, ended = true, isLive = false))
        assertFalse(YtSeenRules.shouldMark(600_000, 600_000, ended = true, isLive = true))
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
            assertTrue(YtSeenHide.finished(null, d, ledgerSeen = true))
        }
        assertNotNull(YtSeenRules.markOf(entry(), null, null))
        assertEquals(true, YtSeenRules.markOf(entry(), null, null)?.watched)
        assertNull(YtSeenRules.markOf(null, null, null))
    }

    /** Cause c: playback that ended by autoplay, queue advance or a skip to the end marks by "ended". */
    @Test fun endedByQueueAdvanceMarks() {
        // the last snapshot was 40 s before the end of a 5 minute video (86 %): reached() says no, ended says yes
        assertFalse(YtSeenRules.reached(260_000, 300_000))
        assertTrue(YtSeenRules.shouldMark(260_000, 300_000, ended = true, isLive = false))
        assertEquals(1f, YtSeenRules.mark(null, "a", 260_000, 300_000, true, 1).share, 0f)
    }

    /** Cause d: deleting the watch position (history removal) does not touch the ledger decision. */
    @Test fun deletedPositionKeepsMark() {
        assertFalse(YtSeenRules.isSeen(null, null, 600))
        assertTrue(YtSeenRules.isSeen(entry(), null, 600))
    }

    @Test fun seedingTakesWhatWasWatched() {
        val seeded = YtSeenRules.seed(
            mapOf("a" to 570_000L, "b" to 100_000L, "c" to 570_000L, "d" to 0L),
            mapOf("a" to 600L, "b" to 600L, "d" to 600L),
            now = 5,
        )
        assertEquals(listOf("a"), seeded.map { it.id })
        assertEquals(600L, seeded[0].durationSec)
    }

    @Test fun legacyRuleWhenNoEntry() {
        assertTrue(YtSeenRules.isSeen(null, 590_000, 600))
        assertFalse(YtSeenRules.isSeen(null, 100_000, 600))
    }
}
