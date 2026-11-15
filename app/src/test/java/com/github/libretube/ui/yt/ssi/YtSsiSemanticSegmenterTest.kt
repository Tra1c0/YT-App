package com.github.libretube.ui.yt.ssi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YtSsiSemanticSegmenterTest {
    /** Each sentence spoken word by word, 300 ms a word, 600 ms between sentences. */
    private fun speak(sentences: List<String>, start: Long = 0): List<YtSsiUnit> {
        var at = start
        val out = ArrayList<YtSsiUnit>()
        for (s in sentences) {
            for (w in s.split(' ')) {
                out += YtSsiUnit(at, at + 300, w, word = true)
                at += 300
            }
            at += 300
        }
        return out
    }

    private val ants = listOf(
        "Ameisen leben in großen Kolonien.",
        "Eine Kolonie hat oft eine Königin.",
        "Die Arbeiterinnen suchen Futter.",
        "Sie tragen dabei schwere Lasten.",
    )
    private val coffee = listOf(
        "Kaffee enthält viel Koffein.",
        "Koffein hält uns am Morgen wach.",
        "Zu viel Kaffee macht aber nervös.",
    )

    /** Two topics as two directions: the sentences of a topic point the same way. */
    private fun vectors(sentences: List<YtSsiSemanticSegmenter.Sentence>): List<FloatArray> = sentences.mapIndexed { i, s ->
        val a = s.text.contains("Ameise") || s.text.contains("Kolonie") || s.text.contains("Arbeiterinnen") || s.text.contains("Lasten")
        // a little noise so not every gap is equal
        if (a) floatArrayOf(1f, 0.05f * (i % 2), 0f) else floatArrayOf(0f, 0.05f * (i % 2), 1f)
    }

    @Test
    fun sentencesEndAtPunctuationPausesAndChapters() {
        val units = speak(listOf("Erster Satz hier.", "zweiter ohne Punkt")) +
            listOf(YtSsiUnit(10_000, 10_300, "nach", true), YtSsiUnit(10_300, 10_600, "Pause", true))
        val sentences = YtSsiSemanticSegmenter.sentences(units)
        assertEquals(listOf("Erster Satz hier.", "zweiter ohne Punkt", "nach Pause"), sentences.map { it.text })
        val withChapter = YtSsiSemanticSegmenter.sentences(speak(listOf("eins zwei drei vier")), listOf(YtSsiChapter(600, "B")))
        assertEquals(listOf("eins zwei", "drei vier"), withChapter.map { it.text })
    }

    @Test
    fun aRunOnSentenceIsCutAtItsLongestPause() {
        val words = (1..60).map { "wort$it" }
        var at = 0L
        val units = words.mapIndexed { i, w ->
            val u = YtSsiUnit(at, at + 300, w, true)
            at += if (i == 40) 900 else 300
            u
        }
        val sentences = YtSsiSemanticSegmenter.sentences(units)
        assertTrue(sentences.size >= 2)
        sentences.forEach { assertTrue(it.chars <= YtSsiSemanticSegmenter.SENTENCE_MAX_CHARS + 10) }
    }

    @Test
    fun aTopicChangeIsAPassageBorder() {
        val units = speak(ants + coffee)
        val sentences = YtSsiSemanticSegmenter.sentences(units)
        assertEquals(7, sentences.size)
        val segments = YtSsiSemanticSegmenter.segments("v", YtSsiTrack(units, YtSsiTiming.WORD), sentences, vectors(sentences))
        assertEquals(2, segments.size)
        assertEquals(ants.joinToString(" "), segments[0].text)
        assertEquals(coffee.joinToString(" "), segments[1].text)
        assertEquals(listOf(0, 1), segments.map { it.ord })
        // the marks still point at each word's start
        val second = segments[1]
        assertEquals(second.startMs, second.marks.first().ms)
        assertEquals(units.first { it.text == "Kaffee" }.startMs, second.startMs)
    }

    @Test
    fun theRulesMixWhatMeaningSeparates() {
        // the rules close after three sentences: the last ant sentence lands with the coffee ones
        val units = speak(ants + coffee)
        val rules = YtSsiSegmenter.segments("v", YtSsiTrack(units, YtSsiTiming.WORD))
        assertTrue(rules.any { it.text.contains("Lasten") && it.text.contains("Kaffee") })
        val sentences = YtSsiSemanticSegmenter.sentences(units)
        val meaning = YtSsiSemanticSegmenter.segments("v", YtSsiTrack(units, YtSsiTiming.WORD), sentences, vectors(sentences))
        assertTrue(meaning.none { it.text.contains("Lasten") && it.text.contains("Kaffee") })
    }

    @Test
    fun withoutVectorsTheRulesCut() {
        val units = speak(ants + coffee)
        val track = YtSsiTrack(units, YtSsiTiming.WORD)
        val sentences = YtSsiSemanticSegmenter.sentences(units)
        val rules = YtSsiSegmenter.segments("v", track)
        assertEquals(rules, YtSsiSemanticSegmenter.segments("v", track, sentences, null))
        // vectors that do not fit the sentences count as none
        assertEquals(rules, YtSsiSemanticSegmenter.segments("v", track, sentences, listOf(floatArrayOf(1f))))
    }

    @Test
    fun hardLimitsHold() {
        // one topic for a long time: passages still end at MAX_CHARS, and never cross a chapter
        val long = (1..30).map { "Ameisen sind wirklich sehr fleißige kleine Tiere Nummer $it." }
        val units = speak(long)
        val chapterAt = units[units.size / 2].startMs
        val chapters = listOf(YtSsiChapter(0, "A"), YtSsiChapter(chapterAt, "B"))
        val sentences = YtSsiSemanticSegmenter.sentences(units, chapters)
        val vectors = sentences.map { floatArrayOf(1f, 0f, 0f) }
        val segments = YtSsiSemanticSegmenter.segments("v", YtSsiTrack(units, YtSsiTiming.WORD), sentences, vectors, chapters)
        segments.forEach {
            assertTrue(it.text.length <= YtSsiSemanticSegmenter.MAX_CHARS)
            assertTrue(it.endMs - it.startMs <= YtSsiSemanticSegmenter.MAX_SPAN_MS + 1_000)
            assertFalse(it.startMs < chapterAt && it.endMs > chapterAt + 300)
        }
        // nothing lost, nothing doubled
        assertEquals(units.joinToString(" ") { it.text }, segments.joinToString(" ") { it.text })
    }

    @Test
    fun depthsFindTheDip() {
        val c = doubleArrayOf(0.9, 0.88, 0.5, 0.87, 0.9)
        val depth = YtSsiSemanticSegmenter.depths(c)
        assertEquals(0.0, depth[0], 1e-9)
        assertEquals((0.9 - 0.5) + (0.9 - 0.5), depth[2], 1e-9)
        assertEquals(setOf(2), YtSsiSemanticSegmenter.borders(c))
        // a flat curve has no border
        assertTrue(YtSsiSemanticSegmenter.borders(doubleArrayOf(0.9, 0.89, 0.9, 0.895)).isEmpty())
    }

    @Test
    fun storedPassagesGiveTheirUnitsBack() {
        val units = speak(ants + coffee)
        val passages = YtSsiSegmenter.segments("v", YtSsiTrack(units, YtSsiTiming.WORD))
        val back = YtSsiSemanticSegmenter.fromPassages(passages)
        assertEquals(YtSsiTiming.WORD, back.timing)
        assertEquals(units.map { it.text }, back.units.map { it.text })
        assertEquals(units.map { it.startMs }, back.units.map { it.startMs })
        // the rules cut them the same again
        assertTrue(YtSsiSemanticSegmenter.same(passages, YtSsiSegmenter.segments("v", back)))
        assertFalse(YtSsiSemanticSegmenter.same(passages, passages.drop(1)))
    }
}
