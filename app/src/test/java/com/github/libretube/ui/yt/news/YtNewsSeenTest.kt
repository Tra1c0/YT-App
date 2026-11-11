package com.github.libretube.ui.yt.news

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class YtNewsSeenTest {
    private val hour = 60L * 60 * 1000
    private val now = 1_800_000_000_000L

    @Test
    fun nothingIsNewBeforeTheFirstLook() {
        assertTrue(YtNewsSeenRules.fresh(emptySet(), listOf("a", "b")).isEmpty())
    }

    @Test
    fun videosOutsideTheLastLookAreNew() {
        assertEquals(setOf("c"), YtNewsSeenRules.fresh(setOf("a", "b"), listOf("a", "c", "b")))
    }

    @Test
    fun recordAddsAndPrunes() {
        val old = YtNewsSeenList(mapOf("old" to now - 9 * 24 * hour, "a" to now - 2 * hour))
        val next = YtNewsSeenRules.record(old, listOf("b"), now)
        assertEquals(setOf("a", "b"), next.seen.keys)
        assertEquals(now, next.seen["b"])
    }

    @Test
    fun recentlySeenWritesNothing() {
        val list = YtNewsSeenList(mapOf("a" to now - 1000, "b" to now - 2000))
        assertSame(list, YtNewsSeenRules.record(list, listOf("a", "b"), now))
    }

    @Test
    fun capKeepsTheNewest() {
        val many = (0 until YtNewsSeenRules.CAP + 10).associate { "v$it" to now - (YtNewsSeenRules.CAP + 10 - it) * 1000L }
        val pruned = YtNewsSeenRules.prune(YtNewsSeenList(many), now)
        assertEquals(YtNewsSeenRules.CAP, pruned.seen.size)
        assertTrue("v0" !in pruned.seen && "v${YtNewsSeenRules.CAP + 9}" in pruned.seen)
    }

    @Test
    fun storyCountsSourcesByChannel() {
        val a = YtNewsItem("a", "x", channelId = "c1", uploadedMs = now)
        val b = YtNewsItem("b", "x", channelId = "c1", uploadedMs = now - hour)
        val c = YtNewsItem("c", "x", channelId = "c2", uploadedMs = now - 2 * hour)
        val story = YtNewsCard.Story("story:c", listOf(a, b, c))
        assertEquals(2, story.sources)
        assertEquals("a", story.lead.id)
        assertEquals(story.items, story.timeline)
    }
}
