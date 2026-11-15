package com.github.libretube.ui.yt.ssi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class YtSsiLearningTest {
    private val day = 24L * 60 * 60 * 1000
    private val t0 = 1_800_000_000_000L

    private fun event(
        verdict: YtSsiVerdict,
        lexical: Int? = null,
        semantic: Int? = null,
        title: Int? = null,
        kind: YtSsiIntentKind = YtSsiIntentKind.QUESTION,
        at: Long = t0,
        semanticUsed: Boolean = true,
    ) = YtSsiFeedback(at, "q", kind, "v", 1, verdict, lexical, semantic, title, null, semanticUsed, YtSsiTiming.WORD)

    @Test
    fun aPerfectHitRaisesTheMethodThatFoundIt() {
        val s = YtSsiLearning.learn(YtSsiLearnState(), event(YtSsiVerdict.PERFECT, lexical = 1))
        val m = s.kinds["QUESTION"]!!
        assertTrue(m.lexical > 1.0)
        assertTrue(m.semantic < 1.0)
        assertTrue(m.title < 1.0)
        assertEquals(1, s.events)
        // other kinds are untouched
        val base = YtSsiWeights(1.0, 0.2, 0.5)
        assertEquals(base, YtSsiLearning.weights(base, YtSsiIntentKind.EXACT_QUOTE, s, t0))
        assertTrue(YtSsiLearning.weights(base, YtSsiIntentKind.QUESTION, s, t0).lexical > 1.0)
    }

    @Test
    fun aWrongHitLowersTheMethodThatRankedItHigh() {
        val s = YtSsiLearning.learn(YtSsiLearnState(), event(YtSsiVerdict.NOT_RELEVANT, semantic = 1))
        val m = s.kinds["QUESTION"]!!
        assertTrue(m.semantic < 1.0)
        assertTrue(m.lexical > 1.0)
        // found by meaning alone: the cut-off goes up
        assertEquals(YtSsiLearning.CUT_STEP, s.cutOffset, 1e-12)
        assertTrue(YtSsiLearning.minCosine(s, t0) > YtSsiVectorIndex.MIN_COSINE)
    }

    @Test
    fun rightVideoWrongPlaceCreditsTheTitle() {
        val s = YtSsiLearning.learn(YtSsiLearnState(), event(YtSsiVerdict.WRONG_SPOT, lexical = 1, title = 1))
        val m = s.kinds["QUESTION"]!!
        assertTrue(m.title > 1.0)
        assertTrue(m.lexical < 1.0)
    }

    @Test
    fun timeOffOnlyCounts() {
        val s = YtSsiLearning.learn(YtSsiLearnState(), event(YtSsiVerdict.TIME_OFF, lexical = 1, semantic = 1))
        assertTrue(s.kinds.isEmpty())
        assertEquals(0.0, s.cutOffset, 0.0)
        assertEquals(1, s.verdicts["TIME_OFF"])
    }

    @Test
    fun withoutTheMeaningSearchItsWeightStays() {
        val s = YtSsiLearning.learn(YtSsiLearnState(), event(YtSsiVerdict.PERFECT, lexical = 1, semanticUsed = false))
        assertEquals(1.0, s.kinds["QUESTION"]!!.semantic, 0.0)
        assertEquals(0.0, s.cutOffset, 0.0)
    }

    @Test
    fun everythingStaysInBounds() {
        var s = YtSsiLearnState()
        repeat(500) { s = YtSsiLearning.learn(s, event(YtSsiVerdict.NOT_RELEVANT, semantic = 1, at = t0 + it)) }
        val m = s.kinds["QUESTION"]!!
        assertEquals(YtSsiLearning.MIN_MULT, m.semantic, 1e-9)
        assertEquals(YtSsiLearning.MAX_MULT, m.lexical, 1e-9)
        assertEquals(YtSsiLearning.MAX_CUT, s.cutOffset, 1e-9)
        assertEquals(500, s.events)
    }

    @Test
    fun oldFeedbackFades() {
        var s = YtSsiLearnState()
        repeat(20) { s = YtSsiLearning.learn(s, event(YtSsiVerdict.PERFECT, lexical = 1, at = t0)) }
        val now = s.kinds["QUESTION"]!!.lexical - 1.0
        val later = YtSsiLearning.decay(s, t0 + 30 * day).kinds["QUESTION"]!!.lexical - 1.0
        assertEquals(now / 2, later, 1e-9)
        val much = YtSsiLearning.decay(s, t0 + 365 * day).kinds["QUESTION"]!!.lexical
        assertEquals(1.0, much, 1e-3)
    }

    @Test
    fun storedAndReset() {
        val s = YtSsiLearning.learn(YtSsiLearnState(), event(YtSsiVerdict.PERFECT, lexical = 2, semantic = 4))
        assertEquals(s, YtSsiLearning.decode(YtSsiLearning.encode(s)))
        assertEquals(YtSsiLearnState(), YtSsiLearning.decode("kaputt"))
        assertEquals(YtSsiLearnState(), YtSsiLearning.decode(null))
        assertTrue(YtSsiLearning.summary(s, t0).contains("1 feedback"))
        assertEquals("no feedback", YtSsiLearning.summary(YtSsiLearnState(), t0))
    }

    @Test
    fun creditFalls() {
        assertEquals(1.0, YtSsiLearning.credit(1), 0.0)
        assertTrue(YtSsiLearning.credit(4) < YtSsiLearning.credit(2))
        assertEquals(0.0, YtSsiLearning.credit(21), 0.0)
        assertEquals(0.0, YtSsiLearning.credit(null), 0.0)
    }

    @Test
    fun feedbackFromAHit() {
        val hit = YtSsiHit(
            videoId = "v", title = "t", channelName = null, uploaded = null, language = null, segmentId = 5, startMs = 0, endMs = 1,
            hitMs = 0, contextStartMs = 0, contextEndMs = 1, text = "", highlights = emptyList(),
            matches = setOf(YtSsiMatch.TRANSCRIPT_SEMANTIC, YtSsiMatch.TITLE), methods = setOf(YtSsiMethod.EMBEDDING, YtSsiMethod.FTS_BM25),
            timing = YtSsiTiming.LINE, availability = YtSsiAvailability.PUBLIC, rank = 1, fused = 1.0,
            ranks = mapOf(YtSsiMethod.EMBEDDING to 2, YtSsiMethod.FTS_BM25 to 4),
        )
        val intent = YtSsiIntentParser.parse("Warum ist der Himmel blau", t0, java.time.ZoneId.of("UTC"))
        val f = YtSsiLearning.feedback(hit, intent, YtSsiVerdict.PERFECT, t0, semanticUsed = true)
        // the full-text place belongs to the title here, not to the passage
        assertEquals(null, f.lexicalRank)
        assertEquals(4, f.titleRank)
        assertEquals(2, f.semanticRank)
        assertEquals(intent.kind, f.kind)
    }
}
