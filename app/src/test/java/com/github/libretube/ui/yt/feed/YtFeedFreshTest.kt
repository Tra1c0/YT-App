package com.github.libretube.ui.yt.feed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class YtFeedFreshTest {
    private val now = 1_000L * 24 * 60 * 60 * 1000
    private val day = 24L * 60 * 60 * 1000
    private val hour = 60L * 60 * 1000
    private val personal = YtFeedPersonal()

    private fun video(id: String, source: YtFeedSource = YtFeedSource.SUBSCRIPTIONS, rank: Int = 0, channel: String = "c$id", title: String = "Video $id") =
        YtFeedItem(
            id = id, source = source, rank = rank, channelId = channel, channelName = "Name $channel", title = title,
            durationSec = 600, uploadedMs = now - 5 * day,
        )

    private fun ids(ranked: List<YtRanked>) = ranked.map { it.item.id }

    private fun seen(ranked: List<YtRanked>, count: Int = YtSeenMemory.TOP) =
        ranked.take(count).map { YtSeen(it.item.id, it.item.channelName, it.item.title) }

    /** A home of three sources with many channels: subscriptions (some channels twice), music, gaming. */
    private fun home(): List<YtFeedItem> {
        val subs = (0 until 60).map { video("s$it", rank = it, channel = "sc${it % 25}") }
        val music = (0 until 40).map { video("m$it", YtFeedSource.MUSIC, rank = it, channel = "mc$it") }
        val gaming = (0 until 40).map { video("g$it", YtFeedSource.GAMING, rank = it, channel = "gc$it") }
        return subs + music + gaming
    }

    private val rules = YtFeedRules()

    @Test
    fun `without a refresh the order is the engine's as before`() {
        val items = home()
        val a = YtFeedEngine.rank(items, rules, personal, YtFeedSignals(nowMs = now))
        val b = YtFeedEngine.rank(items, rules, personal, YtFeedSignals(nowMs = now, seen = YtSeenMemory()))
        assertEquals(ids(a), ids(b))
    }

    @Test
    fun `after a refresh the first twenty are videos not shown on top before`() {
        val items = home()
        val before = YtFeedEngine.rank(items, rules, personal, YtFeedSignals(nowMs = now))
        val memory = YtSeenMemory().remember(seen(before), now)
        val after = YtFeedEngine.rank(items, rules, personal, YtFeedSignals(nowMs = now, seen = memory, shuffle = 7L))
        val oldTop = ids(before).take(50).toSet()
        val newTop = ids(after).take(20)
        assertTrue("new top $newTop overlaps $oldTop", newTop.none { it in oldTop })
        // nothing is lost: the same videos, only in another order
        assertEquals(ids(before).toSet(), ids(after).toSet())
        assertTrue(after.take(20).all { r -> r.reasons.any { it.kind == YtReasonKind.NEW_ON_TOP } })
    }

    @Test
    fun `three refreshes in a row each bring a new top`() {
        val items = home()
        var memory = YtSeenMemory()
        val tops = ArrayList<List<String>>()
        var shown = YtFeedEngine.rank(items, rules, personal, YtFeedSignals(nowMs = now))
        tops += ids(shown).take(10)
        repeat(3) { round ->
            memory = memory.remember(seen(shown), now + round)
            shown = YtFeedEngine.rank(items, rules, personal, YtFeedSignals(nowMs = now + round, seen = memory, shuffle = round + 1L))
            tops += ids(shown).take(10)
        }
        // every top of ten differs from all tops before it
        for (i in 1 until tops.size) for (j in 0 until i) {
            assertTrue("top $i ${tops[i]} repeats top $j ${tops[j]}", tops[i].none { it in tops[j] })
        }
    }

    @Test
    fun `the new top has at most one video per channel among ten and mixes the sources`() {
        // one channel with many unseen videos at the top of its source
        val subs = (0 until 30).map { video("s$it", rank = it, channel = if (it < 15) "loud" else "sc$it") }
        val music = (0 until 20).map { video("m$it", YtFeedSource.MUSIC, rank = it, channel = "mc$it") }
        val items = subs + music
        val weighted = rules.copy(weights = mapOf(YtFeedSource.SUBSCRIPTIONS to 10, YtFeedSource.MUSIC to 1), maxPerChannel = 0, channelGap = 0)
        // the first refresh remembered something unrelated, so the freshness pass runs
        val memory = YtSeenMemory().remember(listOf(YtSeen("x", "Name x", "Something else")), now)
        val ranked = YtFeedEngine.rank(items, weighted, personal, YtFeedSignals(nowMs = now, seen = memory))
        val top10 = ranked.take(10)
        assertEquals(1, top10.count { it.item.channelId == "loud" })
        // never three of one source in a row while the other has videos left
        val sources = ranked.take(20).map { it.item.source }
        for (i in 2 until sources.size) {
            assertFalse("three in a row at $i: $sources", sources[i] == sources[i - 1] && sources[i] == sources[i - 2])
        }
    }

    @Test
    fun `when the data runs out, the top takes as many new ones as there are`() {
        val items = (0 until 12).map { video("v$it", rank = it) }
        val plain = YtFeedRules(weights = mapOf(YtFeedSource.SUBSCRIPTIONS to 1), maxPerChannel = 0, channelGap = 0)
        val before = YtFeedEngine.rank(items, plain, personal, YtFeedSignals(nowMs = now))
        // the first 8 were on top: only 4 are new
        val memory = YtSeenMemory().remember(seen(before, 8), now)
        val after = YtFeedEngine.rank(items, plain, personal, YtFeedSignals(nowMs = now, seen = memory))
        assertEquals(listOf("v8", "v9", "v10", "v11"), ids(after).take(4))
        assertEquals(12, after.size)
        assertEquals(ids(before).take(8), ids(after).drop(4))
    }

    @Test
    fun `a re-upload with the same channel and title counts as seen`() {
        val memory = YtSeenMemory().remember(listOf(YtSeen("old", "Name c1", "The Same Title")), now)
        assertTrue(memory.contains(video("new", channel = "c1", title = "the same title ")))
        assertFalse(memory.contains(video("new", channel = "c2", title = "The Same Title")))
    }

    @Test
    fun `the order newest keeps its dates`() {
        val items = (0 until 30).map { video("v$it", rank = it).copy(uploadedMs = now - it * hour * 30) }
        val newest = YtFeedRules(weights = mapOf(YtFeedSource.SUBSCRIPTIONS to 1), order = YtFeedOrder.NEWEST, maxPerChannel = 0, channelGap = 0)
        val before = YtFeedEngine.rank(items, newest, personal, YtFeedSignals(nowMs = now))
        val memory = YtSeenMemory().remember(seen(before, 10), now)
        val after = YtFeedEngine.rank(items, newest, personal, YtFeedSignals(nowMs = now, seen = memory, shuffle = 3L))
        assertEquals(ids(before), ids(after))
    }

    @Test
    fun `memory keeps three refreshes of fifty for half a day`() {
        var memory = YtSeenMemory()
        repeat(5) { r -> memory = memory.remember((0 until 80).map { YtSeen("r${r}v$it", "c", "Title $r $it") }, now + r) }
        assertEquals(YtSeenMemory.KEEP, memory.refreshes.size)
        assertTrue(memory.refreshes.all { it.items.size == YtSeenMemory.TOP })
        // newest first: refreshes 4, 3 and 2
        assertEquals("r4v0", memory.refreshes.first().items.first().id)
        assertTrue(memory.contains(video("r2v49")))
        assertFalse(memory.contains(video("r1v0")))
        assertFalse(memory.contains(video("r4v50")))
        assertTrue(memory.within(now + YtSeenMemory.MAX_AGE_MS + 10).isEmpty)
    }

    @Test
    fun `memory survives its text form, tabs and line breaks in titles included`() {
        val memory = YtSeenMemory()
            .remember(listOf(YtSeen("a", "Chan\tnel", "Line\nbreak"), YtSeen("b", null, "")), now)
            .remember(listOf(YtSeen("c", "C", "Third")), now + 1)
        val back = YtSeenMemory.decode(memory.encode())
        assertEquals(2, back.refreshes.size)
        assertEquals(listOf("c"), back.refreshes[0].items.map { it.id })
        assertEquals(now, back.refreshes[1].atMs)
        assertEquals(YtSeen("a", "Chan nel", "Line break"), back.refreshes[1].items[0])
        assertEquals(YtSeen("b", null, ""), back.refreshes[1].items[1])
        assertTrue(YtSeenMemory.decode("").isEmpty)
        assertTrue(YtSeenMemory.decode("garbage\nmore").isEmpty)
    }

    @Test
    fun `a rule change ranks again at once`() {
        val items = home()
        val before = ids(YtFeedEngine.rank(items, rules, personal, YtFeedSignals(nowMs = now))).take(10)
        val musicHeavy = rules.withWeight(YtFeedSource.MUSIC, 10)
        val after = YtFeedEngine.rank(items, musicHeavy, personal, YtFeedSignals(nowMs = now)).take(10)
        assertNotEquals(before, ids(after))
        assertTrue(after.count { it.item.source == YtFeedSource.MUSIC } >= 5)
    }

    @Test
    fun `the shuffle moves near-equal places but not past a boost`() {
        val items = home()
        val a = ids(YtFeedEngine.rank(items, rules, personal, YtFeedSignals(nowMs = now, shuffle = 1L)))
        val b = ids(YtFeedEngine.rank(items, rules, personal, YtFeedSignals(nowMs = now, shuffle = 2L)))
        assertNotEquals(a, b)
        // the same shuffle gives the same order
        assertEquals(a, ids(YtFeedEngine.rank(items, rules, personal, YtFeedSignals(nowMs = now, shuffle = 1L))))
        val boosted = personal.copy(channels = mapOf("sc24" to YtChannelRule.BOOST))
        val top = YtFeedEngine.rank(items, rules, boosted, YtFeedSignals(nowMs = now, shuffle = 5L))
        assertEquals("sc24", top.first().item.channelId)
    }

    @Test
    fun `rotation windows wrap round`() {
        val list = (0 until 10).toList()
        assertEquals(listOf(0, 1, 2), YtRotation.window(list, 0, 3))
        assertEquals(listOf(3, 4, 5), YtRotation.window(list, 1, 3))
        assertEquals(listOf(9, 0, 1), YtRotation.window(list, 3, 3))
        assertEquals(list, YtRotation.window(list, 5, 20))
        assertEquals(0.0, YtRotation.jitter("x", 0L, 0.12), 0.0)
        repeat(50) { assertTrue(kotlin.math.abs(YtRotation.jitter("v$it", 99L, 0.12)) <= 0.12) }
    }
}
