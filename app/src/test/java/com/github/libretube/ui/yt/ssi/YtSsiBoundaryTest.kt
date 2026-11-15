package com.github.libretube.ui.yt.ssi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YtSsiBoundaryTest {
    /** A passage with one mark per word, [step] ms apart from [start]. */
    private fun seg(text: String, start: Long, ord: Int, timing: YtSsiTiming = YtSsiTiming.WORD, step: Long = 400): YtSsiSegment {
        val marks = YtSsiText.wordRanges(text).mapIndexed { i, r -> YtSsiMark(r.first, start + i * step) }
        return YtSsiSegment("v", ord, start, start + marks.size * step, text, YtSsiSegmentKind.SPEECH, timing, marks, ord + 1L)
    }

    private fun focus(segment: YtSsiSegment, word: String): IntRange {
        val at = segment.text.indexOf(word)
        return at until at + word.length
    }

    @Test
    fun theWholeSentenceOfTheHit() {
        val s = seg("Erster Satz hier. Zweiter Satz mit dem Treffer drin.", 10_000, 1)
        val span = YtSsiBoundary.verify(null, s, null, focus(s, "Treffer"), quote = false)
        // "Zweiter" (word 3) starts the sentence; the passage ends with it
        assertEquals(11_200L, span.startMs)
        assertEquals(10_900L, span.playStartMs)
        assertEquals(s.endMs, span.endMs)
        assertTrue(span.verified)
        assertFalse(span.qualifier)
        assertEquals(YtSsiTiming.WORD, span.timing)
    }

    @Test
    fun aPassageThatStartsMidSentenceReachesBack() {
        val prev = seg("Das hier ist der Anfang eines langen", 0, 0)
        val s = seg("Satzes über Ameisen. Danach etwas anderes.", 2_800, 1)
        val span = YtSsiBoundary.verify(prev, s, null, focus(s, "Ameisen"), quote = false)
        // back to the video's first word: the sentence began in the passage before
        assertEquals(0L, span.startMs)
        assertTrue(span.verified)
        // and ends at "Ameisen." (the next word starts at 4 s)
        assertEquals(4_000L, span.endMs)
    }

    @Test
    fun anEndMidSentenceReachesForward() {
        val s = seg("Dann sagte er dass Ameisen", 0, 0)
        val next = seg("sehr stark sind. Und so weiter.", 2_000, 1)
        val span = YtSsiBoundary.verify(null, s, next, focus(s, "Ameisen"), quote = false)
        // "sind." is the third word of the next passage; "Und" starts at 3.2 s
        assertEquals(3_200L, span.endMs)
        assertTrue(span.verified)
    }

    @Test
    fun aQualifyingSentenceIsTakenIn() {
        val s = seg("Kaffee macht wach. Aber nur für kurze Zeit. Tee auch.", 0, 0)
        val span = YtSsiBoundary.verify(null, s, null, focus(s, "wach"), quote = false)
        assertTrue(span.qualifier)
        // "Zeit." is word 7; "Tee" starts at 3.2 s
        assertEquals(3_200L, span.endMs)
        val plain = seg("Kaffee macht wach. Tee auch.", 0, 0)
        val without = YtSsiBoundary.verify(null, plain, null, focus(plain, "wach"), quote = false)
        assertFalse(without.qualifier)
        assertEquals(1_200L, without.endMs)
    }

    @Test
    fun englishQualifiersCountToo() {
        val s = seg("Coffee keeps you awake. However it wears off soon.", 0, 0)
        assertTrue(YtSsiBoundary.verify(null, s, null, focus(s, "awake"), quote = false).qualifier)
    }

    @Test
    fun aQualifierTooLateIsNotTakenIn() {
        val s = seg("Kaffee macht wach.", 0, 0)
        val next = seg("Aber nicht lange.", 10_000, 1)
        val span = YtSsiBoundary.verify(null, s, next, focus(s, "wach"), quote = false)
        assertFalse(span.qualifier)
        assertEquals(s.endMs, span.endMs)
    }

    @Test
    fun aQuoteDeepInALongSentenceStartsShortlyBeforeIt() {
        val words = (1..20).joinToString(" ") { "wort$it" }
        val s = seg(words, 0, 0)
        val quote = YtSsiBoundary.verify(null, s, null, focus(s, "wort16"), quote = true)
        // the sentence started 6 s before the quote: too far for a quote, so the word about 1.2 s before it
        assertEquals(6_000L - 1_200L, quote.startMs)
        assertEquals(quote.startMs, quote.playStartMs)
        assertFalse(quote.verified)
        // a question may go back up to 8 s: the video's start
        val question = YtSsiBoundary.verify(null, s, null, focus(s, "wort16"), quote = false)
        assertEquals(0L, question.startMs)
        assertTrue(question.verified)
    }

    @Test
    fun aQuoteNeverStartsMoreThanTwoSecondsBeforeIt() {
        val near = seg("Vorher. a b c Zitat hier.", 0, 0)
        val n = YtSsiBoundary.verify(null, near, null, focus(near, "Zitat"), quote = true)
        // the sentence starts 1.2 s before the quote: taken, with the pre-roll
        assertEquals(400L, n.startMs)
        assertTrue(1_600L - n.playStartMs <= YtSsiBoundary.QUOTE_MAX_LEAD_MS)
        assertTrue(n.verified)
        val far = seg("Vorher. a b c d e f Zitat hier.", 0, 0)
        val f = YtSsiBoundary.verify(null, far, null, focus(far, "Zitat"), quote = true)
        // 2.4 s back is too far: a word start shortly before the quote
        assertEquals(1_600L, f.startMs)
        assertTrue(2_800L - f.playStartMs <= YtSsiBoundary.QUOTE_MAX_LEAD_MS)
    }

    @Test
    fun precisionIsNeverMoreThanTheMarks() {
        val line = seg("Eine Zeile. Noch eine Zeile.", 0, 0, YtSsiTiming.LINE)
        assertEquals(YtSsiTiming.LINE, YtSsiBoundary.verify(null, line, null, null, quote = false).timing)
        val chapter = YtSsiSegment("v", 3, 60_000, 120_000, "Kapitel", YtSsiSegmentKind.CHAPTER, YtSsiTiming.APPROX)
        val span = YtSsiBoundary.verify(null, chapter, null, null, quote = false)
        assertEquals(YtSsiTiming.APPROX, span.timing)
        assertFalse(span.verified)
        assertEquals(60_000L, span.startMs)
        assertEquals(120_000L, span.endMs)
    }

    @Test
    fun wordOffsetsToCharacters() {
        val text = "Ameisen tragen das Fünfzigfache."
        assertEquals("tragen das", text.substring(YtSsiBoundary.charsOfWords(text, 1..2)!!.first, YtSsiBoundary.charsOfWords(text, 1..2)!!.last + 1))
        assertNull(YtSsiBoundary.charsOfWords(text, 2..9))
        assertNull(YtSsiBoundary.charsOfWords(text, 3..1))
    }

    @Test
    fun theRankerGivesEveryPassageHitASpanAndABand() {
        val s = seg("Erster Satz hier. Zweiter Satz mit dem Treffer drin.", 10_000, 0)
        val doc = YtSsiDoc("v", "Titel", null, "Kanal", null, null, null, "de", true, YtSsiTiming.WORD, YtSsiSource.CAPTIONS, false)
        val source = object : YtSsiRanker.Source {
            override fun segment(id: Long) = s.takeIf { id == s.id }
            override fun neighbour(videoId: String, ord: Int) = s.takeIf { ord == s.ord }
            override fun doc(videoId: String) = doc
        }
        val intent = YtSsiIntentParser.parse("Treffer", 0, java.time.ZoneId.of("UTC"))
        val hit = YtSsiRanker.rank(intent, YtSsiRanker.Lists(listOf(s.id), emptyList(), emptyList()), source).single()
        assertEquals(11_200L, hit.span!!.startMs)
        assertEquals(YtSsiBand.HIGH, hit.band)
        assertEquals(10_900L, YtSsiPlayRules.playStart(hit, s))
    }
}
