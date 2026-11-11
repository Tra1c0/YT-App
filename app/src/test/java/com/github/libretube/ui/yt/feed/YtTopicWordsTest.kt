package com.github.libretube.ui.yt.feed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YtTopicWordsTest {
    private val space = YtFeedTopic(id = "s", name = "Raumfahrt", keywords = listOf("Raumfahrt", "Mond"))

    @Test
    fun `an acronym in the tags wins over plain words`() {
        val word = YtTopicWords.suggest(
            "Artemis II: NASA bringt Astronauten zum Mond | Raumfahrt erklärt",
            listOf("Artemis", "NASA", "Raumfahrt"),
            space,
        )
        assertEquals("NASA", word)
    }

    @Test
    fun `two names in a row make a phrase`() {
        assertEquals("Raumstation ISS", YtTopicWords.suggest("Wie die Raumstation ISS im Orbit bleibt", emptyList(), space))
    }

    @Test
    fun `keywords, the topic name, turned down words and the channel are left out`() {
        val title = "Wie die Raumstation ISS im Orbit bleibt"
        val withKeyword = space.copy(keywords = space.keywords + "raumstation")
        // the pair only repeats the keyword, the keyword itself is known
        assertEquals("ISS", YtTopicWords.suggest(title, emptyList(), withKeyword))
        assertEquals("Orbit", YtTopicWords.suggest(title, emptyList(), withKeyword.copy(dismissed = listOf("iss"))))
        assertEquals("Orbit", YtTopicWords.suggest(title, emptyList(), withKeyword, channel = "ISS Tracker"))
        assertEquals("Orbit", YtTopicWords.suggest(title, emptyList(), withKeyword, skip = listOf("ISS")))
        val all = YtTopicWords.rank("Raumfahrt und Mond: Mondlandung", emptyList(), space).map { it.text }
        assertFalse(all.any { YtFeedTopics.same(it, "Raumfahrt") || YtFeedTopics.same(it, "Mond") })
        // "Mond" already finds "Mondlandung" (a keyword matches at the start of a word)
        assertFalse("Mondlandung" in all)
    }

    @Test
    fun `stop words, numbers and emoji are never suggested`() {
        assertNull(YtTopicWords.suggest("🚀🔥 2024 – Top 10 | Das ist neu 4K", emptyList(), space))
        val all = YtTopicWords.rank("The best video ever: und dann 100 Tipps für dich 😂", emptyList(), space).map { it.text.lowercase() }
        assertTrue(all.isEmpty())
        assertTrue(YtTopicWords.isStop("für"))
        assertTrue(YtTopicWords.isStop("FUER"))
        assertTrue(YtTopicWords.isStop("II"))
        assertTrue(YtTopicWords.isStop("2024"))
        assertFalse(YtTopicWords.isStop("KI"))
        assertTrue(YtTopicWords.isStop("ab"))
    }

    @Test
    fun `hashtags count as the uploader's keywords`() {
        assertEquals("Weltraumschrott", YtTopicWords.suggest("unterwegs mit freunden #Weltraumschrott #2024", emptyList(), space))
    }

    @Test
    fun `a word every title of the feed has is less distinctive`() {
        val title = "Warum Saturn Ringe hat"
        val corpus = List(10) { "Ringe der Planeten Folge $it" }
        val ranked = YtTopicWords.rank(title, emptyList(), space, corpus)
        val saturn = ranked.first { it.text == "Saturn" }.score
        val ringe = ranked.first { it.text == "Ringe" }.score
        assertTrue(saturn > ringe)
    }

    @Test
    fun `a weak candidate is not offered`() {
        assertNull(YtTopicWords.suggest("so geht das", emptyList(), space))
    }
}
