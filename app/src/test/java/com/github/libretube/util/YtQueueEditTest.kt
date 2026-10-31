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
    fun aCurrentVideoNotInTheQueueCountsAsTheFirst() {
        // as PlayingQueue.currentIndex: not found means index 0
        assertEquals(0, YtQueueEdit.currentIndex(listOf("a", "b"), "gone", key))
        val list = mutableListOf("a", "b")
        assertEquals(1, YtQueueEdit.insertNext(list, "n", currentKey = "gone", key = key))
        assertEquals(listOf("a", "n", "b"), list)
    }
}
