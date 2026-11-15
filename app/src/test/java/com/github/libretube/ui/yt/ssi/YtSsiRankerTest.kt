package com.github.libretube.ui.yt.ssi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class YtSsiRankerTest {
    private fun doc(id: String, title: String, channel: String = "Kanal", vanished: Boolean = false) = YtSsiDoc(
        videoId = id, title = title, channelId = "UC$id", channelName = channel, description = null, uploaded = 1_700_000_000_000L,
        durationSec = 600, language = "de", autoCaptions = true, timing = YtSsiTiming.WORD, source = YtSsiSource.CAPTIONS, vanished = vanished,
    )

    private fun seg(id: Long, video: String, ord: Int, text: String, start: Long, timing: YtSsiTiming = YtSsiTiming.WORD): YtSsiSegment {
        // one mark per word, 400 ms apart
        val marks = YtSsiText.wordRanges(text).mapIndexed { i, r -> YtSsiMark(r.first, start + i * 400L) }
        return YtSsiSegment(video, ord, start, start + marks.size * 400L, text, YtSsiSegmentKind.SPEECH, timing, marks, id)
    }

    private val docs = listOf(doc("a", "Über Ameisen"), doc("b", "Pilze im Wald", vanished = true), doc("c", "Ameisenstaaten erklärt")).associateBy { it.videoId }
    private val segments = listOf(
        seg(1, "a", 0, "Heute geht es um Insekten.", 0),
        seg(2, "a", 1, "Ameisen tragen das Zwanzigfache ihres Gewichts.", 2_000),
        seg(3, "a", 2, "Das ist wirklich erstaunlich.", 6_000),
        seg(4, "b", 0, "Pilze wachsen nach dem Regen.", 10_000, YtSsiTiming.LINE),
        seg(5, "a", 3, "Ameisen leben in Kolonien.", 9_000),
        seg(6, "a", 4, "Ameisen kommunizieren mit Duft.", 12_000),
    ).associateBy { it.id }

    private val source = object : YtSsiRanker.Source {
        override fun segment(id: Long) = segments[id]
        override fun neighbour(videoId: String, ord: Int) = segments.values.firstOrNull { it.videoId == videoId && it.ord == ord }
        override fun doc(videoId: String) = docs[videoId]
    }

    private fun intent(text: String) = YtSsiIntentParser.parse(text, 1_800_000_000_000L, ZoneId.of("UTC"))

    @Test
    fun fusedHitsCarryTheirDetails() {
        val hits = YtSsiRanker.rank(intent("Ameisen Gewicht"), YtSsiRanker.Lists(lexical = listOf(2, 5), semantic = listOf(2, 4), titles = listOf("a", "c"), inTitle = setOf("a", "c"), model = "m/1"), source)
        val first = hits.first()
        assertEquals(2L, first.segmentId)
        assertEquals(setOf(YtSsiMatch.TRANSCRIPT_LEXICAL, YtSsiMatch.TRANSCRIPT_SEMANTIC, YtSsiMatch.TITLE), first.matches)
        // the hit is the first matched word ("Ameisen", the passage start), the context its neighbours
        assertEquals(2_000L, first.hitMs)
        assertEquals(0L, first.contextStartMs)
        assertEquals(6_000L + 4 * 400L, first.contextEndMs)
        assertEquals("Ameisen", first.text.substring(first.highlights.first().first, first.highlights.first().last + 1))
        assertTrue(first.highlights.any { first.text.substring(it.first, it.last + 1) == "Gewichts" })
        // a title match without a matching passage is a hit of its own
        val titleOnly = hits.single { it.videoId == "c" }
        assertEquals(null, titleOnly.segmentId)
        assertEquals(YtSsiTiming.APPROX, titleOnly.timing)
        // the vanished video's passage is marked
        val pilz = hits.single { it.videoId == "b" }
        assertEquals(YtSsiAvailability.VANISHED_ARCHIVED, pilz.availability)
        assertEquals(setOf(YtSsiMethod.EMBEDDING), pilz.methods)
        assertEquals("m/1", pilz.modelVersion)
        assertEquals((1..hits.size).toList(), hits.map { it.rank })
    }

    @Test
    fun anExactQuoteDropsHitsWithoutIt() {
        val hits = YtSsiRanker.rank(intent("\"leben in Kolonien\""), YtSsiRanker.Lists(lexical = listOf(5), semantic = listOf(6, 2), titles = emptyList()), source)
        assertEquals(listOf(5L), hits.map { it.segmentId })
        assertEquals("leben in Kolonien", hits.single().let { it.text.substring(it.highlights.single().first, it.highlights.single().last + 1) })
    }

    @Test
    fun aQuoteAcrossAPassageBorderIsFound() {
        // "erstaunlich. Pilze" no; "Kolonien. Ameisen kommunizieren": across passages 5 and 6
        val hits = YtSsiRanker.rank(intent("\"Kolonien Ameisen kommunizieren\""), YtSsiRanker.Lists(lexical = listOf(6, 5), semantic = emptyList(), titles = emptyList()), source)
        assertEquals(setOf(5L, 6L), hits.map { it.segmentId }.toSet())
    }

    @Test
    fun atMostThreePerVideo() {
        val hits = YtSsiRanker.rank(intent("Ameisen"), YtSsiRanker.Lists(lexical = listOf(2, 5, 6, 1, 3), semantic = emptyList(), titles = emptyList()), source)
        assertEquals(YtSsiRanker.PER_VIDEO, hits.count { it.videoId == "a" })
    }

    @Test
    fun exclusionsAndNoMatch() {
        val hits = YtSsiRanker.rank(intent("Ameisen ohne Duft"), YtSsiRanker.Lists(lexical = listOf(6, 5), semantic = emptyList(), titles = emptyList()), source)
        // passage 6 says "Duft" and goes; passage 5 only has it in the passage after: it stays
        assertEquals(listOf(5L), hits.map { it.segmentId })
        assertTrue(YtSsiRanker.rank(intent("Vulkane"), YtSsiRanker.Lists(emptyList(), emptyList(), emptyList()), source).isEmpty())
    }

    @Test
    fun matchedWordsForTheExplanation() {
        val hit = YtSsiRanker.rank(intent("Ameisen Gewicht"), YtSsiRanker.Lists(listOf(2), emptyList(), emptyList()), source).single()
        assertEquals(listOf("ameisen", "gewichts"), YtSsiRanker.matchedWords(hit))
        assertFalse(YtSsiNote.CHANNEL_GUESSED in hit.notes)
    }
}
