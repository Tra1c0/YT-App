package com.github.libretube.ui.yt.news

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class YtNewsChannelRulesTest {
    private val empty = YtNewsChannelList()

    @Test
    fun markAndUnmarkByIdOrName() {
        val marked = YtNewsChannelRules.mark(empty, "UC1", "Tagesschau")
        assertTrue(YtNewsChannelRules.isNews(marked, "UC1", null))
        assertTrue(YtNewsChannelRules.isNews(marked, "UC1", "Other name"))
        // two channels may share a name: the id decides where both have one
        assertFalse(YtNewsChannelRules.isNews(marked, "UC2", "Tagesschau"))
        // a video without channel URL is matched by the name
        assertTrue(YtNewsChannelRules.isNews(marked, null, " tagesschau "))
        val unmarked = YtNewsChannelRules.unmark(marked, "UC1", "Tagesschau")
        assertFalse(YtNewsChannelRules.isNews(unmarked, "UC1", "Tagesschau"))
        assertEquals(listOf("UC1"), unmarked.refused)
    }

    @Test
    fun aNameOnlyChannelIsMatchedByName() {
        val marked = YtNewsChannelRules.mark(empty, null, "Kleiner Kanal")
        assertTrue(YtNewsChannelRules.isNews(marked, "UCX", "kleiner kanal"))
        assertFalse(YtNewsChannelRules.isNews(marked, "UCX", "Anderer"))
    }

    @Test
    fun toggleFlipsAndMarkingAgainForgetsTheRefusal() {
        val on = YtNewsChannelRules.toggle(empty, "UC1", "A")
        assertTrue(YtNewsChannelRules.isNews(on, "UC1", "A"))
        val off = YtNewsChannelRules.toggle(on, "UC1", "A")
        assertFalse(YtNewsChannelRules.isNews(off, "UC1", "A"))
        assertTrue("UC1" in off.refused)
        val again = YtNewsChannelRules.toggle(off, "UC1", "A")
        assertTrue(YtNewsChannelRules.isNews(again, "UC1", "A"))
        assertFalse("UC1" in again.refused)
        assertEquals(1, again.channels.size)
    }

    @Test
    fun theCategoryMarksOnlyChannelsNotRefused() {
        assertTrue(YtNewsChannelRules.isNewsCategory("News & Politics"))
        assertTrue(YtNewsChannelRules.isNewsCategory(" news and politics "))
        assertFalse(YtNewsChannelRules.isNewsCategory("Music"))
        assertFalse(YtNewsChannelRules.isNewsCategory(null))

        val detected = YtNewsChannelRules.detect(empty, "UC1", "A", "News & Politics")
        assertTrue(detected.channels.single().auto)
        // other categories and known channels change nothing
        assertSame(empty, YtNewsChannelRules.detect(empty, "UC1", "A", "Gaming"))
        assertSame(detected, YtNewsChannelRules.detect(detected, "UC1", "A", "News & Politics"))
        // unmarked by the user: never marked again by the category
        val refused = YtNewsChannelRules.unmark(detected, "UC1", "A")
        assertSame(refused, YtNewsChannelRules.detect(refused, "UC1", "A", "News & Politics"))
        // a channel without any name or id is nothing
        assertSame(empty, YtNewsChannelRules.detect(empty, null, " ", "News & Politics"))
    }

    @Test
    fun theListIsCapped() {
        var list = empty
        repeat(YtNewsChannelRules.CAP + 20) { list = YtNewsChannelRules.mark(list, "UC$it", "C$it") }
        assertEquals(YtNewsChannelRules.CAP, list.channels.size)
        // the oldest went first
        assertFalse(YtNewsChannelRules.isNews(list, "UC0", null))
        assertTrue(YtNewsChannelRules.isNews(list, "UC${YtNewsChannelRules.CAP + 19}", null))
    }

    @Test
    fun theStoredTextRoundTrips() {
        val json = Json { ignoreUnknownKeys = true }
        val list = YtNewsChannelRules.unmark(YtNewsChannelRules.mark(YtNewsChannelRules.mark(empty, "UC1", "A"), null, "B"), "UC9", "Z")
        val text = json.encodeToString(YtNewsChannelList.serializer(), list)
        assertEquals(list, json.decodeFromString(YtNewsChannelList.serializer(), text))
        // older or partial texts read with defaults
        assertEquals(YtNewsChannelList(listOf(YtNewsChannel("UC1"))), json.decodeFromString(YtNewsChannelList.serializer(), """{"channels":[{"id":"UC1"}]}"""))
    }
}
