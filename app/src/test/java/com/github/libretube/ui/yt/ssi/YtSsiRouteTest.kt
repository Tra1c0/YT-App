package com.github.libretube.ui.yt.ssi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class YtSsiRouteTest {
    private fun intent(q: String, known: List<String> = emptyList()) = YtSsiIntentParser.parse(q, 1_800_000_000_000L, ZoneId.of("UTC"), known)

    private fun hit(id: String, seg: Long?) = YtSsiHit(
        videoId = id, title = id, channelName = null, uploaded = null, language = null, segmentId = seg, startMs = 0, endMs = 1,
        hitMs = 0, contextStartMs = 0, contextEndMs = 0, text = "x", highlights = emptyList(), matches = emptySet(), methods = emptySet(),
        timing = YtSsiTiming.WORD, availability = YtSsiAvailability.PUBLIC, rank = 1, fused = 0.0, ranks = emptyMap(),
    )

    private val key = { h: YtSsiHit -> "${h.videoId}:${h.segmentId ?: "v"}" }

    @Test
    fun theFlaggedAsksOpenTheirViews() {
        assertEquals(YtSsiView.EVIDENCE, YtSsiRoute.view(intent("Videos, die widerlegen, dass Menschen Fleisch essen müssen")))
        assertEquals(YtSsiView.EVIDENCE, YtSsiRoute.view(intent("Wo hat er vor drei Jahren etwas anderes behauptet")))
        assertEquals(YtSsiView.COMPARE, YtSsiRoute.view(intent("vergleiche die Aussagen zu Atomkraft")))
        assertEquals(YtSsiView.TIMELINE, YtSsiRoute.view(intent("Atomkraft im Laufe der Zeit")))
        assertEquals(YtSsiView.TIMELINE, YtSsiRoute.view(intent("hat Anna ihre Meinung geändert")))
        assertEquals(YtSsiView.HITS, YtSsiRoute.view(intent("wie funktioniert ein Kernreaktor")))
        assertEquals(YtSsiView.HITS, YtSsiRoute.view(null))
    }

    @Test
    fun theyStartTheAnalysisPlainQueriesDoNot() {
        assertTrue(YtSsiRoute.autoStance(intent("Gegenargumente zu Tempolimit")))
        assertTrue(YtSsiRoute.autoStance(intent("vergleiche Atomkraft und Kohle")))
        assertFalse(YtSsiRoute.autoStance(intent("Tempolimit Autobahn")))
    }

    @Test
    fun aPersonTimelineNeedsANamedChannel() {
        assertTrue(YtSsiRoute.personTimeline(intent("Atomkraft im Laufe der Zeit von Kanal Eins", known = listOf("Kanal Eins"))))
        assertFalse(YtSsiRoute.personTimeline(intent("Atomkraft im Laufe der Zeit")))
    }

    @Test
    fun comparesTheChosenOrOnePerRelation() {
        val hits = listOf(hit("a", 1), hit("b", 2), hit("c", 3), hit("d", 4), hit("e", 5), hit("t", null))
        assertEquals(listOf("c", "a"), YtSsiRoute.compared(hits, listOf("c:3", "a:1"), key, emptyMap()).map { it.videoId })
        val ev = { s: Long -> YtSsiEvidence("q", s, 0, YtSsiTiming.WORD) }
        val stances = mapOf(
            2L to YtSsiStance(2, YtSsiRelation.SUPPORTS, ev(2)),
            4L to YtSsiStance(4, YtSsiRelation.REFUTES, ev(4), YtSsiOpposition.CONTRADICTS),
            5L to YtSsiStance(5, YtSsiRelation.UNCERTAIN, null, unsure = YtSsiUnsure.QUOTE_NOT_FOUND, claimed = YtSsiRelation.SUPPORTS),
        )
        val auto = YtSsiRoute.compared(hits, emptyList(), key, stances)
        assertEquals(listOf("b", "d", "a", "c"), auto.map { it.videoId })
        assertEquals(YtSsiRoute.MAX_COMPARE, auto.size)
        // one chosen: it comes first
        assertEquals("e", YtSsiRoute.compared(hits, listOf("e:5"), key, stances).first().videoId)
    }
}
