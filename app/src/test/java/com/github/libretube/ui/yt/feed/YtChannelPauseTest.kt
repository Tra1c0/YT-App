package com.github.libretube.ui.yt.feed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class YtChannelPauseTest {
    private val now = 1_700_000_000_000L

    @Test
    fun aPauseRunsSevenDays() {
        val paused = YtPauseRules.pause(emptyList(), "UC1", "Kanal", null, now)
        assertEquals(now + 7 * YT_DAY_MILLIS, paused.single().untilMillis)
        assertNotNull(YtPauseRules.find(paused, "UC1", "Kanal", now + 7 * YT_DAY_MILLIS - 1))
        assertNull(YtPauseRules.find(paused, "UC1", "Kanal", now + 7 * YT_DAY_MILLIS))
        assertTrue(YtPauseRules.prune(paused, now + 7 * YT_DAY_MILLIS).isEmpty())
    }

    @Test
    fun pausingAgainStartsOverOnce() {
        var paused = YtPauseRules.pause(emptyList(), "UC1", "Kanal", null, now)
        paused = YtPauseRules.pause(paused, "UC1", "Kanal", "a.png", now + YT_DAY_MILLIS)
        assertEquals(1, paused.size)
        assertEquals(now + 8 * YT_DAY_MILLIS, paused.single().untilMillis)
        assertEquals("a.png", paused.single().avatar)
    }

    @Test
    fun channelsWithIdsAreMatchedByIdOnly() {
        val entry = YtPausedChannel(id = "UC1", name = "Musik", untilMillis = now + 1)
        assertTrue(YtPauseRules.same(entry, "UC1", "anders"))
        assertFalse(YtPauseRules.same(entry, "UC2", "Musik"))
        // without an id on one side, the name decides (case and spaces do not matter)
        assertTrue(YtPauseRules.same(entry, null, " musik"))
        assertTrue(YtPauseRules.same(YtPausedChannel(id = null, name = "Musik"), "UC9", "MUSIK"))
        assertFalse(YtPauseRules.same(YtPausedChannel(id = null, name = ""), null, ""))
    }

    @Test
    fun aChannelWithoutIdOrNameIsNotPaused() {
        assertTrue(YtPauseRules.pause(emptyList(), null, "  ", null, now).isEmpty())
    }

    @Test
    fun resumeAndRestore() {
        val paused = YtPauseRules.pause(emptyList(), "UC1", "Kanal", null, now)
        val entry = paused.single()
        val resumed = YtPauseRules.resume(paused, "UC1", "Kanal")
        assertTrue(resumed.isEmpty())
        assertEquals(listOf(entry), YtPauseRules.restore(resumed, entry, now + 1))
        // a pause that ran out meanwhile does not come back
        assertTrue(YtPauseRules.restore(resumed, entry, entry.untilMillis).isEmpty())
    }

    @Test
    fun daysLeftRoundsUp() {
        assertEquals(7, YtPauseRules.daysLeft(now + 7 * YT_DAY_MILLIS, now))
        assertEquals(5, YtPauseRules.daysLeft(now + 4 * YT_DAY_MILLIS + 1, now))
        assertEquals(1, YtPauseRules.daysLeft(now + 1, now))
        assertEquals(0, YtPauseRules.daysLeft(now, now))
    }

    @Test
    fun pruneKeepsTheSameListWhenNothingRanOut() {
        val paused = YtPauseRules.pause(emptyList(), "UC1", "Kanal", null, now)
        assertSame(paused, YtPauseRules.prune(paused, now + 1))
    }
}
