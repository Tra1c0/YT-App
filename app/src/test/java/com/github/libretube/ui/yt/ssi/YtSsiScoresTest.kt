package com.github.libretube.ui.yt.ssi

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId

class YtSsiScoresTest {
    private fun intent(text: String) = YtSsiIntentParser.parse(text, 1_800_000_000_000L, ZoneId.of("UTC"))

    private fun hit(
        text: String = "Ameisen tragen schwere Lasten",
        highlights: List<IntRange> = emptyList(),
        matches: Set<YtSsiMatch> = setOf(YtSsiMatch.TRANSCRIPT_LEXICAL),
        ranks: Map<YtSsiMethod, Int> = mapOf(YtSsiMethod.FTS_BM25 to 1),
        segmentId: Long? = 1,
        ai: YtSsiAiGrade? = null,
    ) = YtSsiHit(
        videoId = "v", title = "t", channelName = null, uploaded = null, language = null, segmentId = segmentId, startMs = 0, endMs = 1,
        hitMs = 0, contextStartMs = 0, contextEndMs = 1, text = text, highlights = highlights, matches = matches, methods = ranks.keys,
        timing = YtSsiTiming.WORD, availability = YtSsiAvailability.PUBLIC, rank = 1, fused = 1.0, ranks = ranks, ai = ai,
    )

    @Test
    fun theAiGradeDecidesWhenThereIsOne() {
        val q = intent("Was tragen Ameisen")
        assertEquals(YtSsiBand.HIGH, YtSsiScores.band(hit(ai = YtSsiAiGrade(3), ranks = mapOf(YtSsiMethod.FTS_BM25 to 40)), q))
        assertEquals(YtSsiBand.MEDIUM, YtSsiScores.band(hit(ai = YtSsiAiGrade(2)), q))
        assertEquals(YtSsiBand.LOW, YtSsiScores.band(hit(ai = YtSsiAiGrade(1)), q))
        assertEquals(YtSsiBand.LOW, YtSsiScores.band(hit(ai = YtSsiAiGrade(0)), q))
    }

    @Test
    fun aQuoteFoundWordForWordIsHigh() {
        assertEquals(YtSsiBand.HIGH, YtSsiScores.band(hit(ranks = mapOf(YtSsiMethod.FTS_BM25 to 30)), intent("\"schwere Lasten\"")))
    }

    @Test
    fun evidenceBands() {
        val q = intent("Ameisen Lasten")
        val both = listOf(0..6, 23..28)
        // top place and all words in it
        assertEquals(YtSsiBand.HIGH, YtSsiScores.band(hit(highlights = both), q))
        // top place, half the words, methods do not agree
        assertEquals(YtSsiBand.MEDIUM, YtSsiScores.band(hit(highlights = listOf(0..6)), q))
        // both methods in the first ten and one at the top
        val agree = mapOf(YtSsiMethod.FTS_BM25 to 2, YtSsiMethod.EMBEDDING to 8)
        assertEquals(YtSsiBand.HIGH, YtSsiScores.band(hit(ranks = agree, matches = setOf(YtSsiMatch.TRANSCRIPT_LEXICAL, YtSsiMatch.TRANSCRIPT_SEMANTIC)), q))
        // far down, few words
        assertEquals(YtSsiBand.LOW, YtSsiScores.band(hit(ranks = mapOf(YtSsiMethod.EMBEDDING to 25), matches = setOf(YtSsiMatch.TRANSCRIPT_SEMANTIC)), q))
        // a title match alone is at most medium
        assertEquals(YtSsiBand.MEDIUM, YtSsiScores.band(hit(segmentId = null, matches = setOf(YtSsiMatch.TITLE)), q))
        assertEquals(YtSsiBand.LOW, YtSsiScores.band(hit(segmentId = null, matches = setOf(YtSsiMatch.TITLE), ranks = mapOf(YtSsiMethod.FTS_BM25 to 9)), q))
    }

    @Test
    fun coverageCountsQueryWords() {
        val q = intent("Ameisen Lasten Wald")
        assertEquals(2.0 / 3, YtSsiScores.coverage(hit(highlights = listOf(0..6, 23..28)), q), 1e-9)
        assertEquals(0.0, YtSsiScores.coverage(hit(), q), 0.0)
    }
}
