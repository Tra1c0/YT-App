package com.github.libretube.ui.yt.news

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class YtNewsMergeTest {
    private val hour = 60L * 60 * 1000
    private val now = 1_800_000_000_000L

    private fun item(
        id: String,
        hoursAgo: Long = 1,
        channel: String = "c1",
        title: String = "Title $id",
        description: String = "",
        origin: YtNewsOrigin = YtNewsOrigin.CHANNEL,
        topics: List<String> = emptyList(),
        isShort: Boolean = false,
        isLive: Boolean = false,
        uploaded: Long? = null,
    ) = YtNewsItem(
        id = id,
        title = title,
        channelId = channel,
        channelName = "Name $channel",
        uploadedMs = uploaded ?: (now - hoursAgo * hour),
        description = description,
        isShort = isShort,
        isLive = isLive,
        origin = origin,
        topics = topics,
    )

    @Test
    fun newestFirstAndUnknownAgeLast() {
        val result = YtNewsMerge.merge(
            listOf(item("a", 5, "x"), item("b", 1, "y"), item("c", uploaded = 0L, channel = "z"), item("d", 3, "w")),
            never = emptyList(),
            nowMs = now,
        )
        assertEquals(listOf("b", "d", "a", "c"), result.map { it.id })
    }

    @Test
    fun theSameVideoOnceTheChannelCopyWinsAndTopicsJoin() {
        val result = YtNewsMerge.merge(
            listOf(
                item("a", origin = YtNewsOrigin.TOPIC, topics = listOf("Wahl")),
                item("a", origin = YtNewsOrigin.CHANNEL, description = "Text"),
                item("a", origin = YtNewsOrigin.TOPIC, topics = listOf("Klima", "Wahl")),
            ),
            never = emptyList(),
            nowMs = now,
        )
        assertEquals(1, result.size)
        assertEquals(YtNewsOrigin.CHANNEL, result[0].origin)
        assertEquals(listOf("Wahl", "Klima"), result[0].topics)
        assertEquals("Text", result[0].description)
    }

    @Test
    fun shortsLiveUpcomingAndOldVideosStayOut() {
        val result = YtNewsMerge.merge(
            listOf(
                item("short", isShort = true, channel = "a"),
                item("live", isLive = true, channel = "b"),
                item("future", uploaded = now + hour, channel = "c"),
                item("old", hoursAgo = 24 * 8, channel = "d"),
                item("ok", hoursAgo = 24 * 6, channel = "e"),
            ),
            never = emptyList(),
            nowMs = now,
        )
        assertEquals(listOf("ok"), result.map { it.id })
    }

    @Test
    fun neverWordsFilterTitleAndDescription() {
        val result = YtNewsMerge.merge(
            listOf(
                item("a", title = "Fußball heute", channel = "a"),
                item("b", description = "Alles über Fußball", channel = "b"),
                item("c", title = "Wetter", channel = "c"),
            ),
            never = listOf(" fußball "),
            nowMs = now,
        )
        assertEquals(listOf("c"), result.map { it.id })
    }

    @Test
    fun capsPerChannelAndInAll() {
        val many = (1..10).map { item("a$it", hoursAgo = it.toLong(), channel = "busy") } +
            (1..40).map { item("b$it", hoursAgo = 20L + it, channel = "c$it") }
        val result = YtNewsMerge.merge(many, never = emptyList(), nowMs = now)
        assertEquals(YtNewsMerge.MAX_ITEMS, result.size)
        assertEquals(YtNewsMerge.MAX_PER_CHANNEL, result.count { it.channelId == "busy" })
        // the busy channel's newest five, then the others in their order
        assertEquals((1..5).map { "a$it" }, result.take(5).map { it.id })
    }

    @Test
    fun collectTakesNewsChannelsAndTopicTitles() {
        val subscriptions = listOf(
            item("news", channel = "tagesschau", title = "Nachrichten am Morgen"),
            item("topic", channel = "other", title = "Neue Studie zum Klimawandel", hoursAgo = 2),
            item("none", channel = "other", title = "Kochen mit Oma", hoursAgo = 3),
        )
        val finds = listOf(item("found", channel = "x", hoursAgo = 4, topics = listOf("Klima"), origin = YtNewsOrigin.CHANNEL))
        val result = YtNewsMerge.collect(
            subscriptions = subscriptions,
            isNewsChannel = { it.channelId == "tagesschau" },
            topics = listOf(YtNewsTopic("Klima", listOf("klima"))),
            topicFinds = finds,
            never = emptyList(),
            nowMs = now,
        )
        assertEquals(listOf("news", "topic", "found"), result.map { it.id })
        assertEquals(YtNewsOrigin.CHANNEL, result[0].origin)
        assertEquals(YtNewsOrigin.TOPIC, result[1].origin)
        assertEquals(listOf("Klima"), result[1].topics)
        // a topic find is a topic item, whatever it said before
        assertEquals(YtNewsOrigin.TOPIC, result[2].origin)
    }

    @Test
    fun topicKeywordsMatchAsTheFeedTopicsDo() {
        val subscriptions = listOf(
            item("umlaut", channel = "a", title = "Käsepreise steigen"),
            item("wide", channel = "b", title = "Kaesepreise im Vergleich", hoursAgo = 2),
            item("inside", channel = "c", title = "Exkurs zur Lage", hoursAgo = 3),
            item("start", channel = "d", title = "Kurse fallen", hoursAgo = 4),
        )
        val result = YtNewsMerge.collect(
            subscriptions = subscriptions,
            isNewsChannel = { false },
            topics = listOf(YtNewsTopic("Käse", listOf(" käse ")), YtNewsTopic("Börse", listOf("kurs")), YtNewsTopic("Leer", listOf(" "))),
            topicFinds = emptyList(),
            never = emptyList(),
            nowMs = now,
        )
        assertEquals(listOf("umlaut", "wide", "start"), result.map { it.id })
        assertEquals(listOf("Käse"), result[0].topics)
        assertEquals(listOf("Börse"), result[2].topics)
    }

    @Test
    fun cardsAreOnePerItemWithTheVideoIdAsKey() {
        val cards = YtNewsMerge.cards(listOf(item("a"), item("b")))
        assertEquals(listOf("a", "b"), cards.map { it.key })
        assertTrue(cards.all { it.items.size == 1 && it.items[0] === it.lead })
    }

    @Test
    fun theChannelKeyIsTheIdElseTheName() {
        assertEquals("id", YtNewsItem("v", "t", channelId = "id", channelName = "Name").channelKey)
        assertEquals("Name", YtNewsItem("v", "t", channelId = " ", channelName = " Name ").channelKey)
        assertEquals(null, YtNewsItem("v", "t").channelKey)
    }
}
