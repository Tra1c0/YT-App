package com.github.libretube.ui.yt.ssi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YtSsiBenchmarkTest {
    private val id = "dQw4w9WgXcQ"

    @Test
    fun readsTheDocumentedFormat() {
        val set = YtSsiBenchmark.parse(
            """
            {"name": "Meine Beispiele", "toleranceMs": 2000, "examples": [
              {"query": "„genau dieser Satz“", "videoId": "$id", "startMs": 61000, "endMs": 68000, "kind": "quote"},
              {"query": "Warum macht Kaffee wach?", "videoId": "https://youtu.be/$id", "start": "2:05", "end": "2:20", "kind": "question"},
              {"query": "Wo kommt das vor", "videoId": "https://www.youtube.com/watch?v=$id&t=5", "start": 12.5, "end": "1:02:03.5"},
              {"query": "\"zitiert ohne kind\"", "video": "$id", "startMs": 0},
              {"query": "nirgends gesagt", "kind": "none"}
            ]}
            """.trimIndent(),
        )
        assertEquals("Meine Beispiele", set.name)
        assertEquals(2000L, set.toleranceMs)
        assertEquals(5, set.examples.size)
        assertTrue(set.skipped.isEmpty())
        val (quote, question, plain, unmarked, none) = set.examples
        assertEquals(YtSsiBenchmark.Example("„genau dieser Satz“", id, 61_000, 68_000, YtSsiBenchmark.Kind.QUOTE), quote)
        assertEquals(125_000L, question.startMs)
        assertEquals(140_000L, question.endMs)
        assertEquals(id, question.videoId)
        assertEquals(12_500L, plain.startMs)
        assertEquals(3_723_500L, plain.endMs)
        assertEquals(YtSsiBenchmark.Kind.QUESTION, plain.kind)
        assertEquals(YtSsiBenchmark.Kind.QUOTE, unmarked.kind)
        // no end: ten seconds
        assertEquals(10_000L, unmarked.endMs)
        assertEquals(YtSsiBenchmark.Kind.NONE, none.kind)
        assertNull(none.videoId)
        assertEquals(2, set.quotes)
        assertEquals(2, set.questions)
        assertEquals(1, set.negatives)
    }

    @Test
    fun aBareArrayAndSkippedEntries() {
        val set = YtSsiBenchmark.parse(
            """[
              {"query": "gut", "videoId": "$id", "startMs": 1000, "endMs": 2000},
              {"query": "", "videoId": "$id", "startMs": 1000},
              {"query": "ohne Video", "startMs": 1000},
              {"query": "falsche Zeit", "videoId": "$id", "startMs": 5000, "endMs": 1000},
              {"query": "falsche Art", "videoId": "$id", "startMs": 5000, "kind": "bild"},
              {"query": "kaputte ID", "videoId": "abc", "startMs": 5000},
              "kein Objekt"
            ]""",
        )
        assertEquals(1, set.examples.size)
        assertEquals(6, set.skipped.size)
        assertTrue(set.skipped[0].startsWith("#2"))
        assertEquals(YtSsiBenchmark.DEFAULT_TOLERANCE_MS, set.toleranceMs)
    }

    @Test(expected = YtSsiBenchmark.Invalid::class)
    fun notJson() {
        YtSsiBenchmark.parse("query;videoId")
    }

    @Test(expected = YtSsiBenchmark.Invalid::class)
    fun noUsableExample() {
        YtSsiBenchmark.parse("""{"examples": [{"query": "x"}]}""")
    }

    @Test
    fun theHashFollowsTheExamplesNotTheName() {
        val a = YtSsiBenchmark.parse("""{"name": "a", "examples": [{"query": "x", "videoId": "$id", "startMs": 1}]}""")
        val b = YtSsiBenchmark.parse("""{"name": "b", "examples": [{"query": "x", "videoId": "$id", "startMs": 1}]}""")
        val c = YtSsiBenchmark.parse("""{"name": "a", "examples": [{"query": "y", "videoId": "$id", "startMs": 1}]}""")
        assertEquals(a.hash, b.hash)
        assertFalse(a.hash == c.hash)
    }

    private fun found(video: String, hitMs: Long, play: Long = hitMs, span: LongRange? = null) =
        YtSsiBenchmark.Found(video, hitMs, span?.first, span?.last, play)

    @Test
    fun measures() {
        val set = YtSsiBenchmark.Examples(
            name = null,
            toleranceMs = 1_000,
            examples = listOf(
                YtSsiBenchmark.Example("a", "v1", 10_000, 12_000, YtSsiBenchmark.Kind.QUOTE),
                YtSsiBenchmark.Example("b", "v2", 50_000, 55_000, YtSsiBenchmark.Kind.QUESTION),
                YtSsiBenchmark.Example("c", "v3", 0, 5_000, YtSsiBenchmark.Kind.QUESTION),
                YtSsiBenchmark.Example("d", "v4", 0, 5_000, YtSsiBenchmark.Kind.QUESTION),
                YtSsiBenchmark.Example("e", null, 0, 0, YtSsiBenchmark.Kind.NONE),
            ),
        )
        val answers = listOf(
            // right at place 1, starts 400 ms early
            YtSsiBenchmark.Answer(listOf(found("v1", 10_200, play = 9_600)), 100),
            // right at place 3 by its span only (the hit time is outside the tolerance)
            YtSsiBenchmark.Answer(listOf(found("v9", 1), found("v2", 30_000), found("v2", 40_000, play = 44_000, span = 44_000L..51_000L)), 200),
            // wrong video only
            YtSsiBenchmark.Answer(listOf(found("v9", 1_000)), 300),
            // nothing at all
            YtSsiBenchmark.Answer(emptyList(), 400),
            // a none example answered with a hit
            YtSsiBenchmark.Answer(listOf(found("v1", 1)), 500),
        )
        val m = YtSsiBenchmark.evaluate(set, answers, at = 7)
        assertEquals(4, m.positives)
        assertEquals(0.25, m.recall1, 1e-9)
        assertEquals(0.5, m.recall3, 1e-9)
        assertEquals(0.5, m.recall5, 1e-9)
        assertEquals((1.0 + 1.0 / (Math.log(4.0) / Math.log(2.0))) / 4, m.ndcg5, 1e-9)
        assertEquals(1.0, m.recall3Quotes!!, 1e-9)
        assertEquals(1.0 / 3, m.recall3Questions!!, 1e-9)
        assertEquals(400L, m.deviationP50Ms)
        assertEquals(6_000L, m.deviationP95Ms)
        assertEquals(0.25, m.noMatch, 1e-9)
        assertEquals(0.0, m.emptyRight!!, 1e-9)
        assertEquals(300L, m.latencyP50Ms)
        assertEquals(500L, m.latencyP95Ms)
        assertEquals(7L, m.at)
        assertEquals(set.hash, m.hash)
    }

    @Test
    fun percentilesByNearestRank() {
        assertNull(YtSsiBenchmark.percentile(emptyList(), 0.5))
        assertEquals(5L, YtSsiBenchmark.percentile(listOf(5), 0.95))
        val values = (1L..20L).toList().shuffled()
        assertEquals(10L, YtSsiBenchmark.percentile(values, 0.5))
        assertEquals(19L, YtSsiBenchmark.percentile(values, 0.95))
        assertEquals(20L, YtSsiBenchmark.percentile(values, 1.0))
    }

    @Test
    fun runsAreStoredAndCompared() {
        val set = YtSsiBenchmark.Examples(null, 1_000, listOf(YtSsiBenchmark.Example("a", "v1", 0, 1, YtSsiBenchmark.Kind.QUOTE)))
        val m = YtSsiBenchmark.evaluate(set, listOf(YtSsiBenchmark.Answer(emptyList(), 5)), at = 1)
        val run = YtSsiBenchmark.Run(m, m.copy(recall3 = 0.5), fetched = 2, missing = 1, name = "x")
        assertEquals(run, YtSsiBenchmark.decode(YtSsiBenchmark.encode(run)))
        assertNull(YtSsiBenchmark.decode("{"))
        assertTrue(YtSsiBenchmark.comparable(m, m.copy(at = 2)))
        assertFalse(YtSsiBenchmark.comparable(m.copy(hash = "other"), m))
        assertFalse(YtSsiBenchmark.comparable(null, m))
    }

    @Test
    fun videoIds() {
        assertEquals(id, YtSsiBenchmark.videoId(id))
        assertEquals(id, YtSsiBenchmark.videoId("https://m.youtube.com/shorts/$id"))
        assertEquals(id, YtSsiBenchmark.videoId("https://www.youtube.com/live/$id?feature=share"))
        assertNull(YtSsiBenchmark.videoId("https://example.com/video"))
    }
}
