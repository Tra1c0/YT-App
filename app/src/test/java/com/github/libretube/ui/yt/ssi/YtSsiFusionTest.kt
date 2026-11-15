package com.github.libretube.ui.yt.ssi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class YtSsiFusionTest {
    private val zone = ZoneId.of("UTC")
    private fun intent(text: String, known: List<String> = emptyList()) = YtSsiIntentParser.parse(text, 1_800_000_000_000L, zone, known)

    @Test
    fun rrfFusesRanksNotScores() {
        val fused = YtSsiFusion.rrf(
            listOf(
                Triple(YtSsiMethod.FTS_BM25, 1.0, listOf("a", "b", "c")),
                Triple(YtSsiMethod.EMBEDDING, 1.0, listOf("c", "b", "d")),
            ),
        )
        // b is 2nd in both, c 3rd and 1st: 1/62 + 1/62 < 1/63 + 1/61? (0.032258 vs 0.032266): c wins
        assertEquals(listOf("c", "b", "a", "d"), fused.ordered())
        assertEquals(mapOf(YtSsiMethod.FTS_BM25 to 3, YtSsiMethod.EMBEDDING to 1), fused.ranks["c"])
    }

    @Test
    fun weightsShiftTheOrderAndZeroDropsAMethod() {
        val lexicalFirst = YtSsiFusion.rrf(
            listOf(
                Triple(YtSsiMethod.FTS_BM25, 1.0, listOf("quote")),
                Triple(YtSsiMethod.EMBEDDING, 0.2, listOf("similar", "quote")),
            ),
        )
        assertEquals("quote", lexicalFirst.ordered().first())
        val off = YtSsiFusion.rrf(listOf(Triple(YtSsiMethod.EMBEDDING, 0.0, listOf("x"))))
        assertTrue(off.scores.isEmpty())
        // a key twice in one list counts once, at its first place
        assertEquals(1, YtSsiFusion.rrf(listOf(Triple(YtSsiMethod.FTS_BM25, 1.0, listOf("x", "x")))).ranks["x"]!![YtSsiMethod.FTS_BM25])
    }

    /** matchinfo('pcnalx') for one column: p phrases, then a, l, then (hits row, hits all, docs) per phrase. */
    private fun info(n: Int, avg: Int, len: Int, vararg phrases: Triple<Int, Int, Int>): IntArray =
        intArrayOf(phrases.size, 1, n, avg, len) + phrases.flatMap { listOf(it.first, it.second, it.third) }.toIntArray()

    @Test
    fun bm25FavoursRareTermsAndShortRows() {
        val rare = YtSsiFusion.bm25(info(1000, 20, 20, Triple(1, 3, 3)))
        val common = YtSsiFusion.bm25(info(1000, 20, 20, Triple(1, 900, 600)))
        assertTrue(rare > common)
        val short = YtSsiFusion.bm25(info(1000, 20, 10, Triple(1, 10, 10)))
        val long = YtSsiFusion.bm25(info(1000, 20, 60, Triple(1, 10, 10)))
        assertTrue(short > long)
        val twice = YtSsiFusion.bm25(info(1000, 20, 20, Triple(2, 10, 10)))
        assertTrue(twice > YtSsiFusion.bm25(info(1000, 20, 20, Triple(1, 10, 10))))
        // a missed phrase adds nothing; a broken blob scores 0
        assertEquals(rare, YtSsiFusion.bm25(info(1000, 20, 20, Triple(1, 3, 3), Triple(0, 50, 40))), 1e-9)
        assertEquals(0.0, YtSsiFusion.bm25(intArrayOf(1, 1)), 0.0)
    }

    @Test
    fun ftsQueryIsSafe() {
        assertEquals("\"das ist nicht wahr\"", YtSsiFusion.ftsQuery(intent("\"Das ist NICHT wahr!\"")))
        val q = YtSsiFusion.ftsQuery(intent("Warum ist der Himmel blau OR NEAR* \"x"))!!
        // every token folded, operators typed by the user are just words, words of 4+ letters as prefixes
        assertEquals("himmel* OR blau* OR near*", q)
        assertNull(YtSsiFusion.ftsQuery(intent("")))
        assertEquals("ameisen* OR insekten*", YtSsiFusion.ftsQuery(intent("Ameisen"), extra = listOf("Insekten")))
    }

    @Test
    fun hardConstraints() {
        val base = YtSsiFusion.Candidate("UCa", "Kurzgesagt", 1_700_000_000_000L, "de", "eine schwarze locher erklarung ohne werbung")
        assertNull(YtSsiFusion.passes(intent("Schwarze Löcher"), base))
        assertEquals(YtSsiFusion.Fail.TIME, YtSsiFusion.passes(intent("Schwarze Löcher 2019"), base))
        assertEquals(YtSsiFusion.Fail.TIME_UNKNOWN, YtSsiFusion.passes(intent("Schwarze Löcher 2019"), base.copy(uploaded = null)))
        assertEquals(YtSsiFusion.Fail.CHANNEL, YtSsiFusion.passes(intent("@maiLab Löcher"), base))
        assertNull(YtSsiFusion.passes(intent("@kurzgesagt Löcher"), base))
        assertEquals(YtSsiFusion.Fail.EXCLUDED_CHANNEL, YtSsiFusion.passes(intent("Löcher nicht von Kurzgesagt", listOf("Kurzgesagt")), base))
        assertEquals(YtSsiFusion.Fail.EXCLUDED_WORD, YtSsiFusion.passes(intent("Löcher -werbung"), base))
        assertEquals(YtSsiFusion.Fail.PHRASE, YtSsiFusion.passes(intent("\"weiße Löcher\""), base))
        assertNull(YtSsiFusion.passes(intent("\"Schwarze Löcher\""), base))
        // a quote is word for word: no prefix match
        assertEquals(YtSsiFusion.Fail.PHRASE, YtSsiFusion.passes(intent("\"schwarze loch\""), base))
        assertEquals(YtSsiFusion.Fail.LANGUAGE, YtSsiFusion.passes(intent("Löcher auf Englisch"), base))
        // an unknown language passes (marked on the hit)
        assertNull(YtSsiFusion.passes(intent("Löcher auf Englisch"), base.copy(language = null)))
    }
}
