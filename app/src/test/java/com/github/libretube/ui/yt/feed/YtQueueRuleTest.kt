package com.github.libretube.ui.yt.feed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class YtQueueRuleTest {
    private val id: (String) -> String? = { it.takeIf { s -> s.isNotEmpty() } }

    @Test
    fun dropPlaysNextWhenNothingIsUpcoming() {
        assertEquals(YtQueueMove.NEXT, YtQueueRule.drop(hasPlayer = true, currentId = "a", videoId = "b", upcoming = 0))
    }

    @Test
    fun dropAppendsWhenSomethingIsUpcoming() {
        assertEquals(YtQueueMove.APPEND, YtQueueRule.drop(hasPlayer = true, currentId = "a", videoId = "b", upcoming = 1))
        assertEquals(YtQueueMove.APPEND, YtQueueRule.drop(hasPlayer = true, currentId = "a", videoId = "b", upcoming = 7))
    }

    @Test
    fun dropRejectsTheSameVideoAndNoPlayer() {
        assertEquals(YtQueueMove.REJECT, YtQueueRule.drop(hasPlayer = true, currentId = "a", videoId = "a", upcoming = 0))
        assertEquals(YtQueueMove.REJECT, YtQueueRule.drop(hasPlayer = false, currentId = null, videoId = "b", upcoming = 0))
        assertEquals(YtQueueMove.REJECT, YtQueueRule.drop(hasPlayer = true, currentId = "a", videoId = null, upcoming = 0))
    }

    @Test
    fun swipeAppendsOrDrafts() {
        assertEquals(YtQueueMove.APPEND, YtQueueRule.swipe(hasPlayer = true, currentId = "a", videoId = "b"))
        assertEquals(YtQueueMove.DRAFT, YtQueueRule.swipe(hasPlayer = false, currentId = null, videoId = "b"))
        assertEquals(YtQueueMove.REJECT, YtQueueRule.swipe(hasPlayer = true, currentId = "a", videoId = "a"))
        assertEquals(YtQueueMove.REJECT, YtQueueRule.swipe(hasPlayer = false, currentId = null, videoId = null))
    }

    @Test
    fun draftKeepsEachVideoOnceAtItsLatestPlace() {
        var draft = emptyList<String>()
        draft = YtQueueRule.addToDraft(draft, "a", id)
        draft = YtQueueRule.addToDraft(draft, "b", id)
        draft = YtQueueRule.addToDraft(draft, "a", id)
        assertEquals(listOf("b", "a"), draft)
        assertSame(draft, YtQueueRule.addToDraft(draft, "", id))
    }

    @Test
    fun aTapStartsTheTappedVideoThenTheDraft() {
        assertEquals(listOf("c", "a", "b"), YtQueueRule.startWithDraft("c", listOf("a", "b"), id))
        assertEquals(listOf("b", "a"), YtQueueRule.startWithDraft("b", listOf("a", "b"), id))
    }

    @Test
    fun theQueueStaysOnlyForWhatTheUserQueued() {
        assertTrue(YtQueueRule.keepsQueue(listOf("x", "b"), setOf("b")))
        assertFalse(YtQueueRule.keepsQueue(listOf("x", "y"), setOf("b")))
        assertFalse(YtQueueRule.keepsQueue(listOf("b"), emptySet()))
        assertFalse(YtQueueRule.keepsQueue(emptyList(), setOf("b")))
        assertFalse(YtQueueRule.keepsQueue(listOf(null), setOf("b")))
    }
}
