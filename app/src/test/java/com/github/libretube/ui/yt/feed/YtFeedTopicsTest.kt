package com.github.libretube.ui.yt.feed

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YtFeedTopicsTest {
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }
    private val now = 1_000L * 24 * 60 * 60 * 1000
    private val signals = YtFeedSignals(nowMs = now)
    private val plain = YtFeedRules(weights = mapOf(YtFeedSource.SUBSCRIPTIONS to 1), maxPerChannel = 0, channelGap = 0)

    private fun topic(id: String = "t1", name: String = "Raumfahrt", keywords: List<String> = listOf("Raumfahrt"), share: Int = 20) =
        YtFeedTopic(id = id, name = name, keywords = keywords, share = share)

    private fun item(id: String, title: String = "Video $id", source: YtFeedSource = YtFeedSource.SUBSCRIPTIONS, channel: String = "c$id", minutes: Long = 10) =
        YtFeedItem(id = id, source = source, channelId = channel, channelName = "Name $channel", title = title, durationSec = minutes * 60, uploadedMs = now - 5 * 24 * 3600_000L)

    private fun ranked(id: String, title: String = "Video $id") = YtRanked(item(id, title), 1.0, listOf(YtReason(YtReasonKind.SOURCE, "SUBSCRIPTIONS")))

    private fun pool(topic: YtFeedTopic, vararg ids: String) =
        YtTopicPool(topic, ids.map { YtTopicCandidate(item(it, source = YtFeedSource.TOPICS), topic.keywords.first()) })

    // ---------------------------------------------------------------------------------------------
    // Matching

    @Test
    fun `keywords match without case and umlauts, at the start of a word`() {
        assertTrue(YtFeedTopics.matches("Käse aus dem Allgäu", "kaese"))
        assertTrue(YtFeedTopics.matches("Kaese aus dem Allgaeu", "Käse"))
        assertTrue(YtFeedTopics.matches("KÄSE-Fondue", "käse"))
        assertTrue(YtFeedTopics.matches("Über die Straße", "strasse"))
        assertTrue(YtFeedTopics.matches("Crème brûlée easy", "creme brulee"))
        assertTrue(YtFeedTopics.matches("Kurse für alle", "kurs"))
        assertFalse(YtFeedTopics.matches("Ein Exkurs", "kurs"))
        assertFalse(YtFeedTopics.matches("anything", "  "))
        assertEquals("Mond", YtFeedTopics.keywordIn("Die MOND-Landung", listOf("Mars", "Mond")))
        assertNull(YtFeedTopics.keywordIn("Kochen", listOf("Mars", "Mond")))
        assertTrue(YtFeedTopics.same("Käse", "kaese"))
        assertFalse(YtFeedTopics.same("Käse", "Kasse"))
    }

    // ---------------------------------------------------------------------------------------------
    // Editing

    @Test
    fun `keywords are cleaned, kept once and capped`() {
        var t = topic(keywords = emptyList())
        t = YtFeedTopics.withKeywords(t, " #Mond ,  Mars  landung ;; mond")
        assertEquals(listOf("Mond", "Mars landung"), t.keywords)
        t = YtFeedTopics.withKeyword(t, "MOND")
        assertEquals(2, t.keywords.size)
        repeat(20) { t = YtFeedTopics.withKeyword(t, "Wort$it") }
        assertEquals(YtFeedTopics.MAX_KEYWORDS, t.keywords.size)
        t = YtFeedTopics.withoutKeyword(t, "mond")
        assertFalse(t.keywords.any { it == "Mond" })
    }

    @Test
    fun `a dismissed word stays dismissed until it is added by hand`() {
        var t = YtFeedTopics.dismiss(topic(), "Apollo")
        t = YtFeedTopics.dismiss(t, "apollo")
        assertEquals(listOf("Apollo"), t.dismissed)
        t = YtFeedTopics.withKeyword(t, "Apollo")
        assertTrue(t.dismissed.isEmpty())
        assertEquals(listOf("Raumfahrt", "Apollo"), t.keywords)
    }

    @Test
    fun `a new topic takes its name as first keyword`() {
        val t = YtFeedTopics.create("x", "  Raum   fahrt ", now, 7L)!!
        assertEquals("Raum fahrt", t.name)
        assertEquals(listOf("Raum fahrt"), t.keywords)
        assertTrue(t.enabled)
        assertNull(YtFeedTopics.create("y", "   ", now, 7L))
    }

    @Test
    fun `the share snaps to the slider`() {
        assertEquals(0, YtFeedTopics.snapShare(-3))
        assertEquals(20, YtFeedTopics.snapShare(22))
        assertEquals(25, YtFeedTopics.snapShare(23))
        assertEquals(YtFeedTopics.MAX_SHARE, YtFeedTopics.snapShare(90))
    }

    // ---------------------------------------------------------------------------------------------
    // Expiry and session

    @Test
    fun `expiry by time, by session and never`() {
        val day = YtFeedTopics.withExpiry(topic(), YtTopicExpiry.DAY, now, 1L)
        assertEquals(now + YtFeedTopics.DAY_MS, day.expiresAt)
        assertTrue(YtFeedTopics.active(day, now + YtFeedTopics.DAY_MS - 1, 1L))
        assertFalse(YtFeedTopics.active(day, now + YtFeedTopics.DAY_MS, 1L))
        assertEquals(1L, YtFeedTopics.remainingMs(day, now + YtFeedTopics.DAY_MS - 1))

        val week = YtFeedTopics.withExpiry(topic(), YtTopicExpiry.WEEK, now, 1L)
        assertTrue(YtFeedTopics.active(week, now + 6 * YtFeedTopics.DAY_MS, 1L))
        assertFalse(YtFeedTopics.active(week, now + YtFeedTopics.WEEK_MS + 1, 1L))

        val session = YtFeedTopics.withExpiry(topic(), YtTopicExpiry.SESSION, now, 5L)
        assertTrue(YtFeedTopics.active(session, now + YtFeedTopics.WEEK_MS, 5L))
        assertFalse(YtFeedTopics.active(session, now, 6L))
        assertNull(YtFeedTopics.remainingMs(session, now))

        val never = YtFeedTopics.withExpiry(topic(), YtTopicExpiry.NEVER, now, 5L)
        assertTrue(YtFeedTopics.active(never, Long.MAX_VALUE, 99L))
        assertFalse(YtFeedTopics.active(never.copy(enabled = false), now, 5L))
    }

    @Test
    fun `switching an expired topic on starts its time again`() {
        val day = YtFeedTopics.withExpiry(topic(), YtTopicExpiry.DAY, now, 1L)
        val later = now + 2 * YtFeedTopics.DAY_MS
        assertTrue(YtFeedTopics.expired(day, later, 1L))
        val again = YtFeedTopics.switchedOn(day, later, 1L)
        assertTrue(YtFeedTopics.active(again, later + 1000, 1L))
        val session = YtFeedTopics.withExpiry(topic(), YtTopicExpiry.SESSION, now, 1L)
        assertTrue(YtFeedTopics.active(YtFeedTopics.switchedOn(session, later, 2L), later, 2L))
    }

    @Test
    fun `a session ends after half an hour away`() {
        val away = YtFeedTopics.SESSION_AWAY_MS
        assertEquals(now, YtFeedTopics.nextSession(now, null, 0L))
        assertEquals(100L, YtFeedTopics.nextSession(now, null, 100L))
        assertEquals(100L, YtFeedTopics.nextSession(now, now - away + 1, 100L))
        assertEquals(now, YtFeedTopics.nextSession(now, now - away, 100L))
        // a clock that went back starts a new one
        assertEquals(now, YtFeedTopics.nextSession(now, now + 5000, 100L))
        // already started after the last stop (the process start looked first): it goes on
        assertEquals(now - 10, YtFeedTopics.nextSession(now, now - away - 50, now - 10))
    }

    // ---------------------------------------------------------------------------------------------
    // Share and mixing

    @Test
    fun `shares are fractions, capped together`() {
        assertEquals(listOf(0.2), YtFeedTopics.fractions(listOf(topic(share = 20))))
        val capped = YtFeedTopics.fractions(listOf(topic("a", share = 50), topic("b", share = 50), topic("c", share = 20)))
        assertEquals(0.6, capped.sum(), 1e-9)
        assertEquals(capped[0], capped[1], 1e-9)
        assertEquals(4, YtFeedTopics.perPage(0.2))
        assertEquals(6, YtFeedTopics.perPage(0.3))
        assertEquals(0, YtFeedTopics.perPage(0.0))
    }

    @Test
    fun `topic videos are spread by their share, never on top`() {
        val main = (0 until 40).map { ranked("m$it") }
        val t = topic(share = 25)
        val mixed = YtFeedTopics.mix(main, listOf(pool(t, *Array(30) { "t$it" })))
        val places = mixed.withIndex().filter { it.value.item.id.startsWith("t") }.map { it.index }
        // 25 %: every fourth place, the first at place 3 (0-based)
        assertEquals(3, places.first())
        assertTrue(places.zipWithNext().all { (a, b) -> b - a == 4 })
        val first20 = mixed.take(20).count { it.focus != null }
        assertEquals(YtFeedTopics.perPage(0.25), first20)
        // the main order is kept
        assertEquals(main.map { it.item.id }, mixed.filter { it.focus == null }.map { it.item.id })
        // nothing after the feed ends
        assertEquals("m39", mixed.last().item.id)
        // each topic video says why
        val reason = mixed[3].reasons.first()
        assertEquals(YtReasonKind.FOCUS, reason.kind)
        assertEquals("Raumfahrt", reason.detail)
        assertEquals("Raumfahrt", reason.extra)
    }

    @Test
    fun `found videos the feed has are marked, not added twice`() {
        val main = (0 until 10).map { ranked("m$it") }
        val t = topic(share = 50)
        val mixed = YtFeedTopics.mix(main, listOf(pool(t, "m4", "t1", "t1", "t2")))
        assertEquals(mixed.map { it.item.id }.distinct().size, mixed.size)
        assertEquals("t1", mixed.single { it.item.id == "t1" }.item.id)
        assertEquals("t1", mixed.single { it.item.id == "t1" }.focus?.topicId)
        assertEquals("t1", mixed.first { it.item.id == "m4" }.focus?.topicId)
    }

    @Test
    fun `feed videos naming a keyword count towards the share`() {
        // every other feed video is already about the topic: 25 % needs no extra video
        val main = (0 until 20).map { if (it % 2 == 0) ranked("m$it", "Raumfahrt heute $it") else ranked("m$it") }
        val t = topic(share = 25)
        val mixed = YtFeedTopics.mix(main, listOf(pool(t, "t1", "t2", "t3")))
        assertEquals(20, mixed.size)
        assertEquals(10, mixed.count { it.focus != null })
        assertEquals(YtReasonKind.FOCUS, mixed[0].reasons.first().kind)
    }

    @Test
    fun `two topics share the places, a video found by both belongs to the first`() {
        val main = (0 until 30).map { ranked("m$it") }
        val a = topic("a", "A", listOf("alpha"), share = 20)
        val b = topic("b", "B", listOf("beta"), share = 20)
        val mixed = YtFeedTopics.mix(main, listOf(pool(a, "x", "a1", "a2", "a3", "a4"), pool(b, "x", "b1", "b2", "b3", "b4")))
        assertEquals("a", mixed.single { it.item.id == "x" }.focus?.topicId)
        val first20 = mixed.take(20)
        val inA = first20.count { it.focus?.topicId == "a" }
        val inB = first20.count { it.focus?.topicId == "b" }
        assertTrue(inA in 3..4 && inB in 3..4 && inA + inB in 7..8)
        // never two added videos in a row at 40 % together
        assertFalse(mixed.zipWithNext().any { (p, q) -> p.focus != null && q.focus != null })
    }

    @Test
    fun `share 0 only marks`() {
        val main = listOf(ranked("m0", "Raumfahrt"), ranked("m1"))
        val mixed = YtFeedTopics.mix(main, listOf(pool(topic(share = 0), "t1")))
        assertEquals(listOf("m0", "m1"), mixed.map { it.item.id })
        assertEquals("t1", mixed[0].focus?.topicId)
    }

    @Test
    fun `the engine filters topic videos like the rest`() {
        val feed = (0 until 12).map { item("m$it") }
        val t = topic(share = 50)
        val personal = YtFeedPersonal(neverWords = listOf("unboxing"))
        val candidates = listOf(
            YtTopicCandidate(item("ok1", "Raumfahrt eins", YtFeedSource.TOPICS), "Raumfahrt"),
            YtTopicCandidate(item("bad", "Raumfahrt unboxing", YtFeedSource.TOPICS), "Raumfahrt"),
            YtTopicCandidate(item("short", "Raumfahrt kurz", YtFeedSource.TOPICS, minutes = 1), "Raumfahrt"),
            YtTopicCandidate(item("seen", "Raumfahrt gesehen", YtFeedSource.TOPICS), "Raumfahrt"),
            YtTopicCandidate(item("ok2", "Raumfahrt zwei", YtFeedSource.TOPICS), "Raumfahrt"),
        )
        val rules = plain.copy(minMinutes = 4)
        val ranked = YtFeedEngine.rank(feed, rules, personal, signals.copy(watched = mapOf("seen" to 1f)), listOf(YtTopicPool(t, candidates)))
        val ids = ranked.map { it.item.id }
        assertTrue("ok1" in ids && "ok2" in ids)
        assertFalse("bad" in ids || "short" in ids || "seen" in ids)
        assertEquals(1, ids.indexOf("ok1"))
        // without topics the engine is as before
        assertEquals(feed.map { it.id }, YtFeedEngine.rank(feed, rules, personal, signals).map { it.item.id })
    }

    @Test
    fun `a watched video belongs to the topics its title or tags name, or that marked it`() {
        val a = topic("a", "Raumfahrt", listOf("Raumfahrt", "Rakete"))
        val b = topic("b", "Kochen", listOf("Kochen"))
        assertEquals(listOf("a"), YtFeedTopics.topicsOf(listOf(a, b), "Die neue RAKETE", emptyList()).map { it.id })
        assertEquals(listOf("b"), YtFeedTopics.topicsOf(listOf(a, b), "Pasta", listOf("kochen lernen")).map { it.id })
        assertEquals(listOf("a"), YtFeedTopics.topicsOf(listOf(a, b), "Etwas anderes", emptyList(), markedTopic = "a").map { it.id })
        assertTrue(YtFeedTopics.topicsOf(listOf(a, b), "Etwas anderes", emptyList()).isEmpty())
    }

    @Test
    fun `the suggestion starts with the second video of a topic, once per video`() {
        var log = YtTopicWatchLog().inSession(1L)
        log = log.watched("a", "v1")
        assertFalse(log.mayAsk("a", "v1"))
        log = log.watched("a", "v2")
        assertTrue(log.mayAsk("a", "v2"))
        assertFalse(log.mayAsk("a", "v3"))
        log = log.asked("v2")
        assertFalse(log.mayAsk("a", "v2"))
        log = log.watched("a", "v2")
        assertEquals(2, log.count("a"))
        // a new session forgets
        assertEquals(0, log.inSession(2L).count("a"))
        assertEquals(log, log.inSession(1L))
    }

    @Test
    fun `old texts without topics still read, topics survive the round trip`() {
        val old = """{"rules":{},"personal":{"neverWords":["x"]}}"""
        val profile = json.decodeFromString(YtFeedProfile.serializer(), old)
        assertTrue(profile.personal.feedTopics.isEmpty())
        val withTopic = profile.copy(
            personal = profile.personal.copy(
                feedTopics = listOf(
                    YtFeedTopic("a", "Raumfahrt", listOf("Raumfahrt", "Mond"), 30, true, YtTopicExpiry.DAY, now, 0L, listOf("Apollo")),
                ),
            ),
        )
        val text = json.encodeToString(YtFeedProfile.serializer(), withTopic)
        assertEquals(withTopic, json.decodeFromString(YtFeedProfile.serializer(), text))
        // a partial topic gets its defaults, an unknown expiry falls back
        val partial = json.decodeFromString(YtFeedTopic.serializer(), """{"id":"z","expiry":"FOREVER","extra":1}""")
        assertEquals(YtTopicExpiry.NEVER, partial.expiry)
        assertEquals(YtFeedTopics.DEFAULT_SHARE, partial.share)
        assertTrue(partial.enabled)
    }
}
