package com.github.libretube.ui.yt.ssi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class YtSsiSegmenterTest {
    /** Words 300 ms apart from [start]; a gap of [pauseAfter] ms after the word at that index. */
    private fun words(text: String, start: Long = 0, pauses: Map<Int, Long> = emptyMap()): List<YtSsiUnit> {
        var at = start
        return text.split(' ').mapIndexed { i, w ->
            val unit = YtSsiUnit(at, at + 300, w, word = true)
            at += 300 + (pauses[i] ?: 0)
            unit
        }
    }

    private val sentence = "Das ist ein ganz gewöhnlicher Satz mit genug Wörtern, damit er die Mindestlänge einer Passage sicher und wirklich erreicht."

    @Test
    fun closesAtSentenceEndsOnceLongEnough() {
        val units = words("$sentence $sentence Kurz.")
        val segments = YtSsiSegmenter.segments("v", YtSsiTrack(units, YtSsiTiming.WORD))
        assertEquals(listOf(sentence, sentence, "Kurz."), segments.map { it.text })
        assertEquals(listOf(0, 1, 2), segments.map { it.ord })
        segments.forEach { assertEquals(YtSsiTiming.WORD, it.timing) }
    }

    @Test
    fun withoutPunctuationAPauseCloses() {
        val plain = sentence.replace(Regex("[.,]"), "")
        val count = plain.split(' ').size
        val units = words("$plain $plain", pauses = mapOf(count - 1 to 1_200L))
        val segments = YtSsiSegmenter.segments("v", YtSsiTrack(units, YtSsiTiming.WORD))
        assertEquals(2, segments.size)
        assertEquals(plain, segments[0].text)
        // the second passage starts after the pause
        assertEquals(units[count].startMs, segments[1].startMs)
    }

    @Test
    fun aShortPauseDoesNotCloseAShortPassage() {
        val units = words("eins zwei drei vier", pauses = mapOf(1 to 1_500L))
        assertEquals(1, YtSsiSegmenter.segments("v", YtSsiTrack(units, YtSsiTiming.WORD)).size)
    }

    @Test
    fun chapterStartsCut() {
        val units = words("eins zwei drei vier fünf sechs")
        val segments = YtSsiSegmenter.segments("v", YtSsiTrack(units, YtSsiTiming.WORD), listOf(YtSsiChapter(0, "A"), YtSsiChapter(900, "B")))
        assertEquals(listOf("eins zwei drei", "vier fünf sechs"), segments.map { it.text })
    }

    @Test
    fun longRunsAreCutAtTheLongestLatePause() {
        val long = (1..120).joinToString(" ") { "wort$it" }
        val units = words(long, pauses = mapOf(40 to 600L, 50 to 900L))
        val segments = YtSsiSegmenter.segments("v", YtSsiTrack(units, YtSsiTiming.WORD))
        assertTrue(segments.all { it.text.length <= YtSsiSegmenter.MAX_CHARS + 10 })
        assertTrue(segments.first().text.endsWith("wort51"))
        // nothing lost, nothing doubled
        assertEquals(long, segments.joinToString(" ") { it.text })
    }

    @Test
    fun linesKeepLineTimingAndMarks() {
        val lines = listOf(YtSsiUnit(0, 2000, "Erste Zeile", false), YtSsiUnit(2000, 4000, "zweite Zeile.", false))
        val segment = YtSsiSegmenter.segments("v", YtSsiTrack(lines, YtSsiTiming.LINE)).single()
        assertEquals(YtSsiTiming.LINE, segment.timing)
        assertEquals(listOf(YtSsiMark(0, 0), YtSsiMark(12, 2000)), segment.marks)
        assertEquals(2000L, YtSsiSegmenter.timeAt(segment.marks, 15, segment.startMs))
        assertEquals(0L, YtSsiSegmenter.timeAt(segment.marks, 3, segment.startMs))
    }

    @Test
    fun marksSurviveTheStorage() {
        val marks = listOf(YtSsiMark(0, 5000), YtSsiMark(4, 5320), YtSsiMark(11, 6100))
        val text = YtSsiSegmenter.encodeMarks(marks, 5000)
        assertEquals("0:0,4:320,11:1100", text)
        assertEquals(marks, YtSsiSegmenter.decodeMarks(text, 5000))
        assertEquals(emptyList<YtSsiMark>(), YtSsiSegmenter.decodeMarks("x,1:", 0))
    }

    @Test
    fun chaptersArePassagesOfTheirOwn() {
        val chapters = YtSsiSegmenter.chapterSegments("v", listOf(YtSsiChapter(60_000, "Zwei"), YtSsiChapter(0, "Eins")), 120_000, firstOrd = 5)
        assertEquals(listOf("Eins", "Zwei"), chapters.map { it.text })
        assertEquals(listOf(0L to 60_000L, 60_000L to 120_000L), chapters.map { it.startMs to it.endMs })
        assertEquals(listOf(5, 6), chapters.map { it.ord })
        chapters.forEach { assertEquals(YtSsiTiming.APPROX, it.timing) }
    }
}
