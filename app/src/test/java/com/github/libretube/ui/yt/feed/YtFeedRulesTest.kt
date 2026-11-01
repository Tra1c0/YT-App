package com.github.libretube.ui.yt.feed

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YtFeedRulesTest {
    // as the app's JsonHelper
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    @Test
    fun `a profile survives the round trip`() {
        val profile = YtFeedProfile()
            .withRules(YtIntent.LEARN, YtFeedRules.preset(YtIntent.LEARN).copy(topics = listOf(YtTopic("Physik", 4)), includeWords = listOf("erklärt")))
            .copy(personal = YtFeedPersonal(channels = mapOf("UC1" to YtChannelRule.BOOST), channelNames = mapOf("UC1" to "Eins"), neverWords = listOf("unboxing")))
        val text = json.encodeToString(YtFeedProfile.serializer(), profile)
        assertEquals(profile, json.decodeFromString(YtFeedProfile.serializer(), text))
    }

    @Test
    fun `an older or broken text still reads`() {
        val old = """{"rules":{"BROWSE":{"order":"NEWEST","unknown":1}},"personal":{"channels":{"UC1":"BOOST"}}}"""
        val profile = json.decodeFromString(YtFeedProfile.serializer(), old)
        assertEquals(YtFeedOrder.NEWEST, profile.rulesFor(YtIntent.BROWSE).order)
        // missing fields keep their defaults
        assertEquals(YtFeedRules().weights, profile.rulesFor(YtIntent.BROWSE).weights)
        assertEquals(YtChannelRule.BOOST, profile.personal.channels["UC1"])
        // an unknown order falls back to the default
        val odd = json.decodeFromString(YtFeedRules.serializer(), """{"order":"SIDEWAYS"}""")
        assertEquals(YtFeedOrder.MIXED, odd.order)
    }

    @Test
    fun `missing intents are their preset, reset brings it back`() {
        val profile = YtFeedProfile()
        YtIntent.entries.forEach { assertEquals(YtFeedRules.preset(it), profile.rulesFor(it)) }
        val changed = profile.withRules(YtIntent.MUSIC, YtFeedRules(order = YtFeedOrder.POPULAR))
        assertEquals(YtFeedOrder.POPULAR, changed.rulesFor(YtIntent.MUSIC).order)
        assertEquals(YtFeedRules.preset(YtIntent.MUSIC), changed.reset(YtIntent.MUSIC).rulesFor(YtIntent.MUSIC))
    }

    @Test
    fun `browse is today's home and the presets differ`() {
        val browse = YtFeedRules.preset(YtIntent.BROWSE)
        assertTrue(browse.weight(YtFeedSource.SUBSCRIPTIONS) > 0)
        assertTrue(browse.weight(YtFeedSource.MUSIC) > 0)
        assertEquals(10, YtFeedRules.preset(YtIntent.SUBSCRIPTIONS).weight(YtFeedSource.SUBSCRIPTIONS))
        assertEquals(0, YtFeedRules.preset(YtIntent.SUBSCRIPTIONS).weight(YtFeedSource.MUSIC))
        assertTrue(YtFeedRules.preset(YtIntent.MUSIC).onlyMusic)
        assertTrue(YtFeedRules.preset(YtIntent.LEARN).onlyLearning)
        assertEquals(4, YtIntent.entries.map { YtFeedRules.preset(it) }.toSet().size)
    }

    @Test
    fun `weights stay between 0 and 10`() {
        val rules = YtFeedRules().withWeight(YtFeedSource.LIVE, 42).withWeight(YtFeedSource.MUSIC, -3)
        assertEquals(10, rules.weight(YtFeedSource.LIVE))
        assertEquals(0, rules.weight(YtFeedSource.MUSIC))
        assertEquals(10, YtFeedRules(weights = mapOf(YtFeedSource.LIVE to 99)).weight(YtFeedSource.LIVE))
    }

    @Test
    fun `words are cleaned`() {
        assertEquals(listOf("a", "B c"), YtFeedRules.parseWords(" a, ,B c; A\n"))
        assertEquals(YtFeedRules.MAX_WORDS, YtFeedRules.cleanWords((0 until 50).map { "w$it" }).size)
    }

    @Test
    fun `the chooser asks after 30 minutes away`() {
        val after = YtIntentAsk.Mode.AFTER_30
        val now = 10_000_000L
        assertFalse(YtIntentAsk.shouldAsk(after, now, now - 29 * 60_000, blocked = false))
        assertTrue(YtIntentAsk.shouldAsk(after, now, now - 30 * 60_000, blocked = false))
        // a clock set back counts as away
        assertTrue(YtIntentAsk.shouldAsk(after, now, now + 5_000, blocked = false))
    }

    @Test
    fun aColdStartAsksAfterAMinute() {
        val after = YtIntentAsk.Mode.AFTER_30
        val now = 10_000_000L
        assertTrue(YtIntentAsk.shouldAsk(after, now, now - 5 * 60_000, blocked = false, coldStart = true))
        // back from a share sheet that let the process go
        assertFalse(YtIntentAsk.shouldAsk(after, now, now - 20_000, blocked = false, coldStart = true))
        assertFalse(YtIntentAsk.shouldAsk(YtIntentAsk.Mode.NEVER, now, now - 5 * 60_000, blocked = false, coldStart = true))
        assertFalse(YtIntentAsk.shouldAsk(after, now, null, blocked = false, coldStart = true))
        assertFalse(YtIntentAsk.shouldAsk(after, now, now - 5 * 60_000, blocked = true, coldStart = true))
    }

    @Test
    fun `always skips a short trip, never never asks, the first start is the welcome's`() {
        val now = 10_000_000L
        assertFalse(YtIntentAsk.shouldAsk(YtIntentAsk.Mode.ALWAYS, now, now - 20_000, blocked = false))
        assertTrue(YtIntentAsk.shouldAsk(YtIntentAsk.Mode.ALWAYS, now, now - 61_000, blocked = false))
        assertFalse(YtIntentAsk.shouldAsk(YtIntentAsk.Mode.NEVER, now, 0, blocked = false))
        assertFalse(YtIntentAsk.shouldAsk(YtIntentAsk.Mode.ALWAYS, now, null, blocked = false))
    }

    @Test
    fun `links, quiet starts and an open player block`() {
        val now = 10_000_000L
        assertFalse(YtIntentAsk.shouldAsk(YtIntentAsk.Mode.ALWAYS, now, 0, blocked = true))
    }

    @Test
    fun `modes read from their keys`() {
        assertEquals(YtIntentAsk.Mode.ALWAYS, YtIntentAsk.Mode.of("always"))
        assertEquals(YtIntentAsk.Mode.NEVER, YtIntentAsk.Mode.of("never"))
        assertEquals(YtIntentAsk.Mode.AFTER_30, YtIntentAsk.Mode.of(null))
        assertEquals(YtIntentAsk.Mode.AFTER_30, YtIntentAsk.Mode.of("bogus"))
    }
}
