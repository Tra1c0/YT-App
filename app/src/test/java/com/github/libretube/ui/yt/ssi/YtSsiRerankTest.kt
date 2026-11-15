package com.github.libretube.ui.yt.ssi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class YtSsiRerankTest {
    private val passages = listOf(
        YtSsiRerank.Passage("p1", 11, "A", "eins zwei drei vier fünf sechs", null, null),
        YtSsiRerank.Passage("p2", 22, "B", "Ameisen tragen viel", "davor", "danach"),
        YtSsiRerank.Passage("p3", 33, "C", "noch ein Satz hier", null, null),
    )

    private fun intent(text: String) = YtSsiIntentParser.parse(text, 1_800_000_000_000L, ZoneId.of("UTC"))

    private fun hit(rank: Int, seg: Long?) = YtSsiHit(
        videoId = "v$rank", title = "t", channelName = null, uploaded = null, language = null, segmentId = seg, startMs = 0, endMs = 1000,
        hitMs = 0, contextStartMs = 0, contextEndMs = 1000, text = "x", highlights = emptyList(), matches = setOf(YtSsiMatch.TRANSCRIPT_LEXICAL),
        methods = setOf(YtSsiMethod.FTS_BM25), timing = YtSsiTiming.WORD, availability = YtSsiAvailability.PUBLIC, rank = rank, fused = 1.0 / rank,
        ranks = mapOf(YtSsiMethod.FTS_BM25 to rank),
    )

    @Test
    fun readsStrictAnswers() {
        val grades = YtSsiRerank.parse("""{"items":[{"id":"p1","relevant":3,"start":1,"end":3},{"id":"p2","relevant":0,"start":-1,"end":-1}]}""", passages)!!
        assertEquals(YtSsiAiGrade(3, 1..3), grades[11L])
        assertEquals(YtSsiAiGrade(0, null), grades[22L])
        assertFalse(33L in grades)
    }

    @Test
    fun readsLooseAnswersButNeverInventsPassages() {
        val answer = """
            Here you go:
            ```json
            {"results": [
              {"id": 2, "relevance": "2.6", "span": {"start": 0, "end": 99}},
              {"id": "P3", "score": 7},
              {"id": "p9", "relevant": 3},
              {"id": "p2", "relevant": 0},
              {"relevant": 3}
            ]}
            ```
        """.trimIndent()
        val grades = YtSsiRerank.parse(answer, passages)!!
        // "2" is p2; 2.6 rounds to 3; the span end is clamped into the passage (3 words)
        assertEquals(YtSsiAiGrade(3, 0..2), grades[22L])
        // a grade out of range is clamped
        assertEquals(3, grades[33L]!!.relevant)
        // an unknown id is dropped, the second answer for p2 does not count
        assertEquals(setOf(22L, 33L), grades.keys)
    }

    @Test
    fun aBareArrayIsReadAndGarbageIsNot() {
        assertEquals(1, YtSsiRerank.parse("""[{"id":"p1","relevant":1,"start":-1,"end":-1}]""", passages)!!.size)
        assertNull(YtSsiRerank.parse("nicht verfügbar", passages))
        assertNull(YtSsiRerank.parse("""{"items":[{"id":"p7","relevant":2}]}""", passages))
        assertNull(YtSsiRerank.parse(null, passages))
    }

    @Test
    fun spansOutsideThePassageAreDropped() {
        assertNull(YtSsiRerank.words(5.0, 2.0, 6))
        assertNull(YtSsiRerank.words(6.0, 7.0, 6))
        assertNull(YtSsiRerank.words(-1.0, -1.0, 6))
        assertEquals(2..5, YtSsiRerank.words(2.0, 9.0, 6))
    }

    @Test
    fun wordNumbersMatchTheWordRanges() {
        val text = "Ameisen tragen das Fünfzigfache ihres eigenen Körpergewichts, sagt man."
        val marked = YtSsiRerank.marked(text)
        assertTrue(marked.startsWith("⟨0⟩Ameisen tragen"))
        // word 5 is "eigenen"
        assertTrue(marked.contains("⟨5⟩eigenen"))
        assertEquals("eigenen", text.substring(YtSsiText.wordRanges(text)[5].first, YtSsiText.wordRanges(text)[5].last + 1))
    }

    @Test
    fun theUserMessageHoldsEveryPassageAndItsContext() {
        val user = YtSsiRerank.user(intent("Wie viel tragen Ameisen"), passages)
        assertTrue(user.contains("### p1"))
        assertTrue(user.contains("### p2"))
        assertTrue(user.contains("Davor: …davor"))
        assertTrue(user.contains("Danach: danach…"))
    }

    @Test
    fun fusionMovesHitsButDoesNotReplaceTheLocalOrder() {
        val hits = (1..25).map { hit(it, it.toLong()) }
        val grades = mapOf(
            1L to YtSsiAiGrade(2),
            6L to YtSsiAiGrade(3),
            12L to YtSsiAiGrade(3),
            2L to YtSsiAiGrade(0),
        )
        val fused = YtSsiRerank.fuse(hits, grades, intent("Wie viel tragen Ameisen"))
        val order = fused.map { it.segmentId }
        // place 6 graded 3 passes place 1 graded 2; place 12 graded 3 does not
        assertEquals(6L, order[0])
        assertEquals(1L, order[1])
        assertTrue(order.indexOf(12L) > order.indexOf(1L))
        // "does not fit" sinks within the window but stays in the list
        assertTrue(order.indexOf(2L) > order.indexOf(3L))
        assertTrue(order.indexOf(2L) < 20)
        // everything after the window stays behind it, in its order
        assertEquals(listOf(21L, 22L, 23L, 24L, 25L), order.takeLast(5))
        // places are new, the local one is kept, graded hits are marked
        assertEquals((1..25).toList(), fused.map { it.rank })
        val six = fused.first { it.segmentId == 6L }
        assertEquals(6, six.localRank)
        assertTrue(YtSsiMethod.AI_RERANK in six.methods)
        assertFalse(YtSsiMethod.AI_RERANK in fused.first { it.segmentId == 3L }.methods)
    }

    @Test
    fun aQuoteGivesTheAiLessSay() {
        val hits = (1..5).map { hit(it, it.toLong()) }
        val grades = mapOf(1L to YtSsiAiGrade(2), 4L to YtSsiAiGrade(3))
        val question = YtSsiRerank.fuse(hits, grades, intent("Wie viel tragen Ameisen")).map { it.segmentId }
        val quote = YtSsiRerank.fuse(hits, grades, intent("\"Ameisen tragen\"")).map { it.segmentId }
        assertEquals(4L, question[0])
        // with half the weight the quote's first place holds against a grade-3 fourth place
        assertEquals(1L, quote[0])
    }

    @Test
    fun cacheKeysAndStorage() {
        val a = YtSsiRerank.cacheKey("ameisen", listOf(3L, 1L, 2L), "openai:m")
        assertEquals(a, YtSsiRerank.cacheKey("ameisen", listOf(1L, 2L, 3L), "openai:m"))
        assertFalse(a == YtSsiRerank.cacheKey("ameisen", listOf(1L, 2L, 3L), "gemini:m"))
        assertTrue(a.startsWith("rr|"))
        val grades = mapOf(5L to YtSsiAiGrade(2, 1..4), 7L to YtSsiAiGrade(0))
        val back = YtSsiRerank.decode(YtSsiRerank.encode(grades))!!
        assertEquals(YtSsiAiGrade(2, 1..4, cached = true), back[5L])
        assertEquals(YtSsiAiGrade(0, null, cached = true), back[7L])
        assertNull(YtSsiRerank.decode("kaputt"))
    }

    @Test
    fun dailyBudget() {
        var day = YtSsiRerank.Day()
        repeat(3) { day = YtSsiRerank.spend(day, 100, cap = 3)!! }
        assertNull(YtSsiRerank.spend(day, 100, cap = 3))
        assertEquals(3, YtSsiRerank.used(day, 100))
        // a new day starts at zero
        assertNotNull(YtSsiRerank.spend(day, 101, cap = 3))
        assertEquals(0, YtSsiRerank.used(day, 101))
    }

    @Test
    fun schemaIsStrict() {
        val schema = YtSsiRerank.schema().toString()
        assertTrue(schema.contains("\"additionalProperties\":false"))
        assertTrue(schema.contains("\"required\":[\"id\",\"relevant\",\"start\",\"end\"]"))
    }
}
