package com.github.libretube.ui.yt.ssi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class YtSsiResearchTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val key = { h: YtSsiHit -> "${h.videoId}:${h.segmentId ?: "v"}" }

    private fun hit(id: String, seg: Long?) = YtSsiHit(
        videoId = id, title = "Video $id", channelName = "K", uploaded = 1_600_000_000_000L, language = "de", segmentId = seg, startMs = 1000, endMs = 9000,
        hitMs = 2000, contextStartMs = 0, contextEndMs = 0, text = "Text $id", highlights = listOf(0..3), matches = setOf(YtSsiMatch.TRANSCRIPT_LEXICAL),
        methods = setOf(YtSsiMethod.FTS_BM25), timing = YtSsiTiming.WORD, availability = YtSsiAvailability.PUBLIC, rank = 1, fused = 0.1, ranks = mapOf(YtSsiMethod.FTS_BM25 to 1),
        span = YtSsiSpan(1000, 9000, 700, YtSsiTiming.WORD, true),
    )

    private val stances = mapOf(
        1L to YtSsiStance(1, YtSsiRelation.REFUTES, YtSsiEvidence("das ist falsch so", 1, 1500, YtSsiTiming.WORD), YtSsiOpposition.REASONED,
            reason = YtSsiEvidence("weil die Daten zeigen", 1, 2500, YtSsiTiming.WORD),
            flags = mapOf(YtSsiContextFlag.IRONIC to YtSsiEvidence("na klar doch immer", 1, 1800, YtSsiTiming.WORD))),
        2L to YtSsiStance(2, YtSsiRelation.UNCERTAIN, null, claimed = YtSsiRelation.SUPPORTS, unsure = YtSsiUnsure.NO_QUOTE),
    )

    @Test
    fun savesLoadsListsAndDeletes() {
        val store = YtSsiResearchStore(File(tmp.root, "research"))
        val hits = listOf(hit("a", 1), hit("b", 2), hit("c", 3))
        val first = YtSsiResearchStore.build("r1", 100, 100, "Fleisch?", "Menschen müssen Fleisch essen", hits, stances, emptyList(), key, "Notiz")
        store.save(first)
        val second = YtSsiResearchStore.build("r2", 200, 300, "Atomkraft", "", hits, emptyMap(), listOf("c:3"), key, "")
        store.save(second)
        assertEquals(listOf("r2", "r1"), store.list().map { it.id })
        val back = store.load("r1")!!
        assertEquals(first, back)
        assertEquals(stances.getValue(1L), back.stanceMap.getValue(1L))
        assertEquals(YtSsiOpposition.REASONED, back.stanceMap.getValue(1L).opposition)
        assertEquals(setOf(YtSsiContextFlag.IRONIC), back.stanceMap.getValue(1L).flags.keys)
        // an update keeps one file
        store.save(back.copy(notes = "neu", updatedAt = 400))
        assertEquals(2, store.count())
        assertEquals("neu", store.list().first().notes)
        assertTrue(store.delete("r2"))
        assertNull(store.load("r2"))
        assertEquals(1, store.count())
    }

    @Test
    fun skipsUnreadableFiles() {
        val dir = File(tmp.root, "research").apply { mkdirs() }
        File(dir, "broken.json").writeText("{ not json")
        val store = YtSsiResearchStore(dir)
        store.save(YtSsiResearchStore.build("ok", 1, 1, "q", "", listOf(hit("a", 1)), emptyMap(), emptyList(), key, ""))
        assertEquals(listOf("ok"), store.list().map { it.id })
        assertTrue(File(dir, "broken.json").exists())
    }

    @Test
    fun keepsTheChosenOrTheAnalysedHits() {
        val hits = listOf(hit("a", 1), hit("b", 2), hit("c", 3), hit("t", null))
        val chosen = YtSsiResearchStore.build("x", 1, 1, "q", "c", hits, stances, listOf("c:3", "zz:9"), key, "")
        assertEquals(listOf("c"), chosen.hits.map { it.videoId })
        assertEquals(listOf("c:3"), chosen.chosen)
        assertTrue(chosen.stances.isEmpty())
        val analysed = YtSsiResearchStore.build("x", 1, 1, "q", "c", hits, stances, emptyList(), key, "")
        assertEquals(listOf("a", "b"), analysed.hits.map { it.videoId })
        assertEquals(2, analysed.stances.size)
        val plain = YtSsiResearchStore.build("x", 1, 1, "q", "c", hits, emptyMap(), emptyList(), key, "")
        assertEquals(4, plain.hits.size)
    }

    @Test
    fun aSavedHitComesBackPlayable() {
        val saved = YtSsiSavedHit.of(hit("a", 1))
        val back = saved.toHit(3)
        assertEquals("a", back.videoId)
        assertEquals(1L, back.segmentId)
        assertEquals(3, back.rank)
        assertEquals(700L, back.span!!.playStartMs)
        assertEquals(2000L, back.hitMs)
        assertEquals(YtSsiAvailability.PUBLIC, back.availability)
        assertNotNull(YtSsiPlayRules.playStart(back, null))
    }

    @Test
    fun idsAreFileSafe() {
        assertTrue(YtSsiResearchStore.newId(1_700_000_000_000L).matches(Regex("[a-z0-9-]+")))
    }
}
