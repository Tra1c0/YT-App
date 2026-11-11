package com.github.libretube.ui.yt.news

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YtNewsSummaryModelTest {
    private val doc = YtNewsTranscriptDoc.build(
        listOf(
            YtNewsCue(4.0, "Der Bundestag hat das Gesetz beschlossen."),
            YtNewsCue(70.0, "Die Opposition kündigt eine Klage an."),
            YtNewsCue(130.0, "In Kraft tritt es im Januar."),
        ),
    )
    private val sources = listOf(YtNewsSource("vid1", "Kanal: Titel", doc))

    private fun cite(quote: String, document: Int = 0) =
        YtNewsCite(document, doc.text.codePointCount(0, doc.text.indexOf(quote)), quote)

    @Test
    fun linesWithCitationsBecomeStatements() {
        // the answer is split at every cited passage, as the API does
        val blocks = listOf(
            YtNewsAnswerBlock("- "),
            YtNewsAnswerBlock("Der Bundestag beschließt das Gesetz.", listOf(cite("Der Bundestag hat das Gesetz beschlossen."))),
            YtNewsAnswerBlock("\n- **Die Opposition** "),
            YtNewsAnswerBlock("will klagen.", listOf(cite("Die Opposition kündigt eine Klage an."))),
            YtNewsAnswerBlock("\n- Eine Aussage ohne Beleg.\n"),
            YtNewsAnswerBlock("- Gilt ab Januar.", listOf(cite("In Kraft tritt es im Januar."))),
        )
        val statements = YtNewsCitations.statements(blocks, sources)
        assertEquals(
            listOf(
                YtNewsStatement("Der Bundestag beschließt das Gesetz.", "vid1", 4),
                YtNewsStatement("Die Opposition will klagen.", "vid1", 70),
                YtNewsStatement("Gilt ab Januar.", "vid1", 130),
            ),
            statements,
        )
    }

    @Test
    fun aLineCountsOnceAtItsFirstCitation() {
        // no line breaks at all
        val blocks = listOf(
            YtNewsAnswerBlock("Kernaussagen: "),
            YtNewsAnswerBlock("Gesetz beschlossen.", listOf(cite("Der Bundestag hat das Gesetz beschlossen."))),
            YtNewsAnswerBlock(" "),
            YtNewsAnswerBlock("Klage angekündigt.", listOf(cite("Die Opposition kündigt eine Klage an."))),
        )
        val statements = YtNewsCitations.statements(blocks, sources)
        assertEquals(1, statements.size)
        assertEquals(4L, statements[0].seconds)
    }

    @Test
    fun nothingCitedMeansNoStatements() {
        val blocks = listOf(YtNewsAnswerBlock("- Eins.\n- Zwei."))
        assertTrue(YtNewsCitations.statements(blocks, sources).isEmpty())
    }

    @Test
    fun citationsOfUnknownDocumentsAreIgnored() {
        val blocks = listOf(YtNewsAnswerBlock("- Etwas.", listOf(YtNewsCite(3, 0, "x"))))
        assertTrue(YtNewsCitations.statements(blocks, sources).isEmpty())
    }

    @Test
    fun atMostFiveStatements() {
        val one = cite("In Kraft tritt es im Januar.")
        val blocks = (1..8).map { YtNewsAnswerBlock("- Aussage $it\n", listOf(one)) }
        assertEquals(YtNewsCitations.MAX_SINGLE, YtNewsCitations.statements(blocks, sources).size)
    }

    @Test
    fun storyStatementsNameTheirVideo() {
        val other = YtNewsTranscriptDoc.build(listOf(YtNewsCue(12.0, "Wir sehen das anders.")))
        val story = listOf(YtNewsSource("a", "Sender A", doc), YtNewsSource("b", "Sender B", other))
        val blocks = listOf(
            YtNewsAnswerBlock("- Sender A: "),
            YtNewsAnswerBlock("Gesetz beschlossen.", listOf(cite("Der Bundestag hat das Gesetz beschlossen."))),
            YtNewsAnswerBlock("\n- Sender B: "),
            YtNewsAnswerBlock("Widerspruch.", listOf(YtNewsCite(1, 0, "[0:12] Wir sehen das anders."))),
        )
        val statements = YtNewsCitations.statements(blocks, story, YtNewsCitations.MAX_STORY)
        assertEquals(listOf("a" to 4L, "b" to 12L), statements.map { it.videoId to it.seconds })
        assertEquals("Sender B: Widerspruch.", statements[1].text)
    }

    @Test
    fun cacheRules() {
        val now = 1_000_000_000_000L
        val day = 24 * 60 * 60 * 1000L
        assertFalse(YtNewsSummaryCacheRules.expired(now - 6 * day, now))
        assertTrue(YtNewsSummaryCacheRules.expired(now - 8 * day, now))
        assertEquals(
            listOf("old.json"),
            YtNewsSummaryCacheRules.toDelete(listOf("old.json" to now - 9 * day, "new.json" to now - day), now),
        )
        assertEquals("dQw4w9WgXcQ.json", YtNewsSummaryCacheRules.fileName("dQw4w9WgXcQ"))
        val story = YtNewsSummaryCacheRules.storyKey(listOf("b", "a", "b"))
        assertEquals("story:a,b", story)
        assertTrue(YtNewsSummaryCacheRules.fileName(story).matches(Regex("[A-Za-z0-9]+\\.json")))
    }

    @Test
    fun cacheRoundTrip() {
        val now = 1_000_000_000_000L
        val digest = YtNewsDigest("vid1", listOf(YtNewsStatement("Aussage.", "vid1", 42)), now - 1000, cut = true)
        val text = YtNewsSummaryCacheRules.encode(digest)
        assertEquals(digest, YtNewsSummaryCacheRules.decode(text, "vid1", now))
        assertNull(YtNewsSummaryCacheRules.decode(text, "other", now))
        assertNull(YtNewsSummaryCacheRules.decode(text, "vid1", now + 8L * 24 * 60 * 60 * 1000))
        assertNull(YtNewsSummaryCacheRules.decode("kaputt", "vid1", now))
    }
}
