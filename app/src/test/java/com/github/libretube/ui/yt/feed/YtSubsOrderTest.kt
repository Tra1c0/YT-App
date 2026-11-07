package com.github.libretube.ui.yt.feed

import com.github.libretube.ui.yt.feed.YtSubsOrder.Seen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class YtSubsOrderTest {
    private data class V(val id: String, val seen: Seen = Seen.UNWATCHED)

    private val videos = (0 until 40).map { V("video$it") }

    private fun order(items: List<V>, seed: Long) =
        YtSubsOrder.shuffle(items, seed, id = { it.id }, seen = { it.seen })

    @Test
    fun sameSeedGivesSameOrder() {
        assertEquals(order(videos, 7), order(videos, 7))
    }

    @Test
    fun newSeedGivesNewOrder() {
        val orders = (1L..5L).map { order(videos, it) }.toSet()
        assertEquals(5, orders.size)
        // and not the incoming (date) order
        orders.forEach { assertNotEquals(videos, it) }
    }

    @Test
    fun keepsEveryVideoOnce() {
        val shuffled = order(videos, 3)
        assertEquals(videos.size, shuffled.size)
        assertEquals(videos.toSet(), shuffled.toSet())
    }

    @Test
    fun unwatchedComeFirstThenPartlyThenWatched() {
        val mixed = videos.mapIndexed { i, v ->
            v.copy(seen = when (i % 3) { 0 -> Seen.WATCHED; 1 -> Seen.PARTLY; else -> Seen.UNWATCHED })
        }
        val shuffled = order(mixed, 11)
        val groups = shuffled.map { it.seen.ordinal }
        assertEquals(groups.sorted(), groups)
        assertEquals(Seen.UNWATCHED, shuffled.first().seen)
        assertEquals(Seen.WATCHED, shuffled.last().seen)
    }

    @Test
    fun removingAVideoKeepsTheOthersInPlace() {
        val full = order(videos, 5)
        val gone = videos[17]
        val without = order(videos - gone, 5)
        assertEquals(full - gone, without)
    }

    @Test
    fun aNewVideoDoesNotReshuffleTheRest() {
        val before = order(videos, 9)
        val after = order(videos + V("fresh"), 9)
        assertEquals(before, after - V("fresh"))
    }

    @Test
    fun seenFromWatchPosition() {
        assertEquals(Seen.UNWATCHED, YtSubsOrder.seen(null, 600))
        // an accidental tap is no watch
        assertEquals(Seen.UNWATCHED, YtSubsOrder.seen(4_000, 600))
        assertEquals(Seen.PARTLY, YtSubsOrder.seen(120_000, 600))
        assertEquals(Seen.WATCHED, YtSubsOrder.seen(560_000, 600))
        assertEquals(Seen.PARTLY, YtSubsOrder.seen(120_000, null))
    }

    @Test
    fun ranksSpreadOverTheRange() {
        val ranks = videos.map { YtSubsOrder.rank(1, it.id) }
        assertTrue(ranks.any { it < 0 } && ranks.any { it > 0 })
        assertEquals(ranks.size, ranks.toSet().size)
    }
}
