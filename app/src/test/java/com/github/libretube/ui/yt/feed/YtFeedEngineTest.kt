package com.github.libretube.ui.yt.feed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YtFeedEngineTest {
    private val now = 1_000L * 24 * 60 * 60 * 1000
    private val day = 24L * 60 * 60 * 1000
    private val signals = YtFeedSignals(nowMs = now)
    private val personal = YtFeedPersonal()

    private fun video(
        id: String,
        source: YtFeedSource = YtFeedSource.SUBSCRIPTIONS,
        rank: Int = 0,
        channel: String = "c$id",
        title: String = "Video $id",
        minutes: Long = 10,
        ageDays: Long = 5,
        views: Long = 0,
        isShort: Boolean = false,
        isLive: Boolean = false,
    ) = YtFeedItem(
        id = id, source = source, rank = rank, channelId = channel, channelName = "Name $channel", title = title,
        durationSec = if (isLive) 0 else minutes * 60, uploadedMs = now - ageDays * day, views = views,
        isShort = isShort, isLive = isLive,
    )

    private fun ids(ranked: List<YtRanked>) = ranked.map { it.item.id }

    private val plain = YtFeedRules(
        weights = mapOf(YtFeedSource.SUBSCRIPTIONS to 1, YtFeedSource.MUSIC to 1),
        maxPerChannel = 0,
        channelGap = 0,
    )

    @Test
    fun `equal weights alternate like the old interleave`() {
        val subs = (0 until 3).map { video("s$it", rank = it) }
        val music = (0 until 3).map { video("m$it", YtFeedSource.MUSIC, rank = it) }
        val ranked = YtFeedEngine.rank(subs + music, plain, personal, signals)
        // the higher weight would go first on a tie; equal weights go by the source's order
        assertEquals(listOf("s0", "m0", "s1", "m1", "s2", "m2"), ids(ranked))
    }

    @Test
    fun `weights decide how many places a source gets`() {
        val rules = plain.withWeight(YtFeedSource.SUBSCRIPTIONS, 3)
        val subs = (0 until 9).map { video("s$it", rank = it) }
        val music = (0 until 9).map { video("m$it", YtFeedSource.MUSIC, rank = it) }
        val first8 = ids(YtFeedEngine.rank(subs + music, rules, personal, signals)).take(8)
        assertEquals(6, first8.count { it.startsWith("s") })
        assertEquals(2, first8.count { it.startsWith("m") })
    }

    @Test
    fun `browse puts a subscription and a chart in turn, as the home did`() {
        val subs = (0 until 4).map { video("s$it", rank = it) }
        val charts = listOf(YtFeedSource.MUSIC, YtFeedSource.PODCASTS, YtFeedSource.TRAILERS, YtFeedSource.GAMING)
            .mapIndexed { i, source -> video("c$i", source) }
        val rules = YtFeedRules.preset(YtIntent.BROWSE).copy(maxPerChannel = 0, channelGap = 0)
        val ranked = ids(YtFeedEngine.rank(subs + charts, rules, personal, signals))
        assertEquals(listOf("s0", "c0", "s1", "c1", "s2", "c2", "s3", "c3"), ranked)
    }

    @Test
    fun `a source with weight 0 is off and an empty one just stops`() {
        val rules = plain.withWeight(YtFeedSource.MUSIC, 0)
        val ranked = YtFeedEngine.rank(listOf(video("s0"), video("m0", YtFeedSource.MUSIC)), rules, personal, signals)
        assertEquals(listOf("s0"), ids(ranked))
        val one = YtFeedEngine.rank(listOf(video("s0"), video("s1", rank = 1), video("m0", YtFeedSource.MUSIC)), plain, personal, signals)
        assertEquals(listOf("s0", "m0", "s1"), ids(one))
    }

    @Test
    fun `the same video comes once, from the source with the higher weight`() {
        val rules = plain.withWeight(YtFeedSource.MUSIC, 5)
        val ranked = YtFeedEngine.rank(listOf(video("x"), video("x", YtFeedSource.MUSIC)), rules, personal, signals)
        assertEquals(1, ranked.size)
        assertEquals(YtFeedSource.MUSIC, ranked[0].item.source)
    }

    @Test
    fun `formats, length and age filter`() {
        val items = listOf(
            video("short", isShort = true),
            video("live", isLive = true),
            video("long", minutes = 120),
            video("tiny", minutes = 1),
            video("old", ageDays = 40),
            video("ok"),
        )
        val rules = plain.copy(shorts = false, live = false, minMinutes = 2, maxMinutes = 60, maxAgeDays = 30)
        assertEquals(listOf("ok"), ids(YtFeedEngine.rank(items, rules, personal, signals)))
        val all = plain.copy(shorts = true, live = true)
        assertEquals(6, YtFeedEngine.rank(items, all, personal, signals).size)
        // without regular videos only the others stay
        assertEquals(listOf("short", "live"), ids(YtFeedEngine.rank(items, all.copy(videos = false), personal, signals)))
    }

    @Test
    fun `a live stream has no length and is not cut by the length rules`() {
        val rules = plain.copy(minMinutes = 5)
        assertEquals(listOf("live"), ids(YtFeedEngine.rank(listOf(video("live", isLive = true)), rules, personal, signals)))
    }

    @Test
    fun `never words hide, wanted words lift or filter`() {
        val items = listOf(
            video("a", rank = 0, title = "Unboxing the new phone"),
            video("b", rank = 1, title = "Quantum Kurse erklärt"),
            video("c", rank = 2, title = "Exkurs zum Wetter"),
        )
        val never = personal.copy(neverWords = listOf("unboxing"))
        assertEquals(listOf("b", "c"), ids(YtFeedEngine.rank(items, plain, never, signals)))
        val wanted = plain.copy(includeWords = listOf("kurs"))
        val lifted = YtFeedEngine.rank(items, wanted, personal, signals)
        // "kurs" finds "Kurse" at a word start, not "Exkurs"
        assertEquals("b", lifted.first().item.id)
        assertTrue(lifted.first().reasons.contains(YtReason(YtReasonKind.WORD, "kurs")))
        assertEquals(listOf("b"), ids(YtFeedEngine.rank(items, wanted.copy(onlyIncluded = true), personal, signals)))
    }

    @Test
    fun `word match starts at a word`() {
        assertTrue(YtFeedEngine.matches("ein kurs für alle", "kurs"))
        assertTrue(YtFeedEngine.matches("(kurs)", "kurs"))
        assertFalse(YtFeedEngine.matches("exkurs", "kurs"))
        assertFalse(YtFeedEngine.matches("anything", ""))
    }

    @Test
    fun `boost lifts a channel, less lowers it, with reasons`() {
        val items = (0 until 4).map { video("v$it", rank = it, channel = "k$it") }
        val boosted = personal.copy(channels = mapOf("k3" to YtChannelRule.BOOST, "k0" to YtChannelRule.LESS))
        val ranked = YtFeedEngine.rank(items, plain, boosted, signals)
        assertEquals("v3", ranked.first().item.id)
        assertEquals("v0", ranked.last().item.id)
        assertTrue(ranked.first().reasons.any { it.kind == YtReasonKind.BOOSTED })
        assertTrue(ranked.last().reasons.any { it.kind == YtReasonKind.LESS })
    }

    @Test
    fun `a channel known only by name is matched by name`() {
        val item = YtFeedItem(id = "n", source = YtFeedSource.SUBSCRIPTIONS, channelName = "Only Name")
        val ranked = YtFeedEngine.rank(listOf(item), plain, personal.copy(channels = mapOf("Only Name" to YtChannelRule.BOOST)), signals)
        assertTrue(ranked.single().reasons.any { it.kind == YtReasonKind.BOOSTED })
    }

    @Test
    fun `newest and most viewed orders`() {
        val items = listOf(
            video("old", rank = 0, ageDays = 9, views = 900),
            video("new", rank = 1, ageDays = 1, views = 10),
            video("mid", rank = 2, ageDays = 4, views = 5000),
        )
        assertEquals(listOf("new", "mid", "old"), ids(YtFeedEngine.rank(items, plain.copy(order = YtFeedOrder.NEWEST), personal, signals)))
        val popular = YtFeedEngine.rank(items, plain.copy(order = YtFeedOrder.POPULAR), personal, signals)
        assertEquals(listOf("mid", "old", "new"), ids(popular))
        assertTrue(popular.first().reasons.any { it.kind == YtReasonKind.POPULAR })
    }

    @Test
    fun `a channel keeps its distance and its cap, nothing is lost`() {
        // five of one channel, then three others
        val items = (0 until 5).map { video("a$it", rank = it, channel = "A") } +
            (0 until 3).map { video("o$it", rank = 5 + it, channel = "O$it") }
        val rules = plain.copy(channelGap = 1, maxPerChannel = 0)
        val ranked = ids(YtFeedEngine.rank(items, rules, personal, signals))
        assertEquals(8, ranked.size)
        assertEquals(listOf("a0", "o0", "a1", "o1", "a2", "o2", "a3", "a4"), ranked)
        val capped = YtFeedEngine.rank(items, plain.copy(channelGap = 0, maxPerChannel = 2), personal, signals)
        assertEquals(8, capped.size)
        // among the first 10 (all 8 here) only two of A come before the others ran out
        assertEquals(listOf("a0", "a1", "o0", "o1", "o2", "a2", "a3", "a4"), ids(capped))
        assertTrue(capped[5].reasons.any { it.kind == YtReasonKind.SPACED })
    }

    @Test
    fun `watched videos go last, hide or stay`() {
        val items = listOf(video("w", rank = 0), video("x", rank = 1), video("y", rank = 2))
        val watched = signals.copy(watched = mapOf("w" to 0.97f))
        val last = YtFeedEngine.rank(items, plain.copy(watched = YtWatchedMode.LAST), personal, watched)
        assertEquals(listOf("x", "y", "w"), ids(last))
        assertTrue(last.last().reasons.any { it.kind == YtReasonKind.WATCHED_LAST })
        assertEquals(listOf("x", "y"), ids(YtFeedEngine.rank(items, plain.copy(watched = YtWatchedMode.HIDE), personal, watched)))
        assertEquals(listOf("w", "x", "y"), ids(YtFeedEngine.rank(items, plain.copy(watched = YtWatchedMode.SHOW), personal, watched)))
    }

    @Test
    fun `a started video goes up a little`() {
        val items = listOf(video("a", rank = 0), video("b", rank = 1))
        val started = signals.copy(watched = mapOf("b" to 0.4f))
        val ranked = YtFeedEngine.rank(items, plain, personal, started)
        assertEquals("b", ranked.first().item.id)
        assertTrue(ranked.first().reasons.any { it.kind == YtReasonKind.STARTED })
    }

    @Test
    fun `groups narrow the subscriptions only`() {
        val items = listOf(video("in", channel = "G1"), video("out", channel = "X"), video("m", YtFeedSource.MUSIC, channel = "X"))
        val grouped = signals.copy(groupChannels = setOf("G1"))
        assertEquals(listOf("in", "m"), ids(YtFeedEngine.rank(items, plain, personal, grouped)))
    }

    @Test
    fun `the chart the topic row chose goes up`() {
        val items = listOf(video("s", rank = 0), video("m", YtFeedSource.MUSIC, rank = 0))
        val rules = plain.withWeight(YtFeedSource.SUBSCRIPTIONS, 1)
        val ranked = YtFeedEngine.rank(items, rules, personal, signals.copy(chosenChart = YtFeedSource.MUSIC))
        assertTrue(ranked.first { it.item.id == "m" }.reasons.any { it.kind == YtReasonKind.CHOSEN_CHART })
    }

    @Test
    fun `music keeps music only`() {
        val items = listOf(
            video("chart", YtFeedSource.MUSIC),
            video("topic", channel = "x1").copy(channelName = "Artist - Topic"),
            video("vevo", channel = "x2").copy(channelName = "ArtistVEVO"),
            video("marked", channel = "mine"),
            video("talk", channel = "x3").copy(channelName = "Talk Show"),
        )
        val rules = YtFeedRules.preset(YtIntent.MUSIC).copy(channelGap = 0, maxPerChannel = 0)
        val ranked = YtFeedEngine.rank(items, rules, personal.copy(musicChannels = setOf("mine")), signals)
        assertEquals(setOf("chart", "topic", "vevo", "marked"), ids(ranked).toSet())
    }

    @Test
    fun `learn keeps long videos, learning words and marked channels`() {
        val items = listOf(
            video("long", minutes = 25, title = "Ein Tag am See"),
            video("word", minutes = 5, title = "Warum der Himmel blau ist"),
            video("marked", minutes = 5, channel = "teach", title = "Folge 3"),
            video("clip", minutes = 5, title = "Lustiger Clip"),
            video("topic", YtFeedSource.TOPICS, minutes = 5, title = "irgendwas").copy(topic = "Physik"),
        )
        val rules = YtFeedRules.preset(YtIntent.LEARN).copy(channelGap = 0, maxPerChannel = 0)
        val ranked = YtFeedEngine.rank(items, rules, personal.copy(learnChannels = setOf("teach")), signals)
        assertEquals(setOf("long", "word", "marked", "topic"), ids(ranked).toSet())
        val topic = ranked.first { it.item.id == "topic" }
        assertTrue(topic.reasons.contains(YtReason(YtReasonKind.TOPIC, "Physik")))
    }

    @Test
    fun `subscriptions preset is the subscription feed, newest first`() {
        val items = listOf(
            video("s-old", rank = 0, ageDays = 9),
            video("s-new", rank = 1, ageDays = 1),
            video("m", YtFeedSource.MUSIC),
        )
        val ranked = YtFeedEngine.rank(items, YtFeedRules.preset(YtIntent.SUBSCRIPTIONS), personal, signals)
        assertEquals(listOf("s-new", "s-old"), ids(ranked))
    }

    @Test
    fun `every ranked video has a reason`() {
        val items = (0 until 20).map { video("v$it", YtFeedSource.entries[it % YtFeedSource.entries.size], rank = it / 3, channel = "c${it % 4}") }
        val rules = YtFeedRules().let { r -> YtFeedSource.entries.fold(r) { acc, s -> acc.withWeight(s, 3) } }
        val ranked = YtFeedEngine.rank(items, rules, personal, signals)
        assertEquals(20, ranked.size)
        assertTrue(ranked.all { it.reasons.isNotEmpty() })
        assertEquals(20, ranked.map { it.item.id }.toSet().size)
    }
}
