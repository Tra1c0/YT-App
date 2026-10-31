package com.github.libretube.util

import org.junit.Assert.assertEquals
import org.junit.Test

class YtQueueEditTest {
    private val key: (String) -> String? = { it.takeIf { id -> id.isNotEmpty() } }

    @Test
    fun upcomingNeverNegative() {
        assertEquals(0, YtQueueEdit.upcoming(size = 0, currentIndex = 0))
        assertEquals(0, YtQueueEdit.upcoming(size = 1, currentIndex = 0))
        assertEquals(2, YtQueueEdit.upcoming(size = 3, currentIndex = 0))
        assertEquals(0, YtQueueEdit.upcoming(size = 3, currentIndex = 2))
        assertEquals(0, YtQueueEdit.upcoming(size = 3, currentIndex = 5))
    }

    @Test
    fun insertNextIntoAnEmptyQueue() {
        val list = mutableListOf<String>()
        assertEquals(0, YtQueueEdit.insertNext(list, "a", currentKey = null, key = key))
        assertEquals(listOf("a"), list)
    }

    @Test
    fun insertNextGoesRightAfterTheCurrentVideo() {
        val list = mutableListOf("x", "cur", "y")
        assertEquals(2, YtQueueEdit.insertNext(list, "n", currentKey = "cur", key = key))
        assertEquals(listOf("x", "cur", "n", "y"), list)
    }

    @Test
    fun insertNextMovesAnEarlierCopy() {
        val list = mutableListOf("n", "cur", "y")
        // the copy before the current video goes, so the current index moves down by one
        assertEquals(1, YtQueueEdit.insertNext(list, "n", currentKey = "cur", key = key))
        assertEquals(listOf("cur", "n", "y"), list)

        val later = mutableListOf("cur", "y", "n")
        assertEquals(1, YtQueueEdit.insertNext(later, "n", currentKey = "cur", key = key))
        assertEquals(listOf("cur", "n", "y"), later)
    }

    @Test
    fun theCurrentVideoAndItemsWithoutIdStayOut() {
        val list = mutableListOf("cur", "y")
        assertEquals(-1, YtQueueEdit.insertNext(list, "cur", currentKey = "cur", key = key))
        assertEquals(-1, YtQueueEdit.append(list, "cur", currentKey = "cur", key = key))
        assertEquals(-1, YtQueueEdit.insertNext(list, "", currentKey = "cur", key = key))
        assertEquals(-1, YtQueueEdit.append(list, "", currentKey = "cur", key = key))
        assertEquals(listOf("cur", "y"), list)
    }

    @Test
    fun appendGoesToTheEndOnce() {
        val empty = mutableListOf<String>()
        assertEquals(0, YtQueueEdit.append(empty, "a", currentKey = null, key = key))
        assertEquals(listOf("a"), empty)

        val list = mutableListOf("cur", "a", "b")
        assertEquals(2, YtQueueEdit.append(list, "a", currentKey = "cur", key = key))
        assertEquals(listOf("cur", "b", "a"), list)
        assertEquals(3, YtQueueEdit.append(list, "c", currentKey = "cur", key = key))
        assertEquals(listOf("cur", "b", "a", "c"), list)
    }

    @Test
    fun withNothingPlayingEveryItemComesUp() {
        // a queue put together before playing: no current video
        val list = mutableListOf("a", "b", "c")
        assertEquals(-1, YtQueueEdit.anchor(list, currentKey = null, key = key))
        assertEquals(3, YtQueueEdit.upcoming(list, currentKey = null, key = key))
        assertEquals(0, YtQueueEdit.upcoming(emptyList<String>(), currentKey = null, key = key))
        assertEquals(0, YtQueueEdit.insertNext(list, "n", currentKey = null, key = key))
        assertEquals(listOf("n", "a", "b", "c"), list)
        assertEquals(0, YtQueueEdit.insertNext(list, "c", currentKey = null, key = key))
        assertEquals(listOf("c", "n", "a", "b"), list)
        assertEquals(3, YtQueueEdit.append(list, "c", currentKey = null, key = key))
        assertEquals(listOf("n", "a", "b", "c"), list)
    }

    @Test
    fun upcomingCountsFromTheCurrentVideo() {
        val list = listOf("a", "cur", "b", "c")
        assertEquals(2, YtQueueEdit.upcoming(list, currentKey = "cur", key = key))
        assertEquals(0, YtQueueEdit.upcoming(list, currentKey = "c", key = key))
        // as PlayingQueue.getNext: a current video not in the queue counts as the first item
        assertEquals(3, YtQueueEdit.upcoming(list, currentKey = "gone", key = key))
    }

    @Test
    fun keepLastOfEachDropsTheEarlierCopies() {
        // "play all" of the watch history while A plays: [A] + history [C, B, A]
        val list = mutableListOf("A", "C", "B", "A")
        assertEquals(true, YtQueueEdit.keepLastOfEach(list, key))
        assertEquals(listOf("C", "B", "A"), list)

        val more = mutableListOf("P", "A", "X", "Y", "Z", "Y", "A")
        assertEquals(true, YtQueueEdit.keepLastOfEach(more, key))
        assertEquals(listOf("P", "X", "Z", "Y", "A"), more)

        // items without an id stay, a list without copies is left alone
        val noIds = mutableListOf("", "a", "", "b")
        assertEquals(false, YtQueueEdit.keepLastOfEach(noIds, key))
        assertEquals(listOf("", "a", "", "b"), noIds)
        val empty = mutableListOf<String>()
        assertEquals(false, YtQueueEdit.keepLastOfEach(empty, key))
    }

    @Test
    fun aCurrentVideoNotInTheQueueCountsAsTheFirst() {
        // as PlayingQueue.currentIndex: not found means index 0
        assertEquals(0, YtQueueEdit.currentIndex(listOf("a", "b"), "gone", key))
        val list = mutableListOf("a", "b")
        assertEquals(1, YtQueueEdit.insertNext(list, "n", currentKey = "gone", key = key))
        assertEquals(listOf("a", "n", "b"), list)
    }
}
