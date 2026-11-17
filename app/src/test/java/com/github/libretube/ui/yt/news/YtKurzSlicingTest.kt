package com.github.libretube.ui.yt.news

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class YtKurzSlicingTest {
    private fun cues(n: Int) = List(n) { YtNewsCue(it * 10.0, "x".repeat(90)) } // weight 100 each

    @Test fun equalPartsWithoutChapters() {
        val parts = YtKurzSlicing.slices(cues(30), emptyList(), maxChars = 1500) // 3000 / 1500 = 2 parts
        assertEquals(listOf(15, 15), parts.map { it.size })
    }

    @Test fun neverMoreThanThreeSlices() {
        val parts = YtKurzSlicing.slices(cues(100), emptyList(), maxChars = 100)
        assertEquals(3, parts.size)
        assertEquals(100, parts.sumOf { it.size })
    }

    @Test fun chaptersDecideTheCuts() {
        // 30 cues, chapters at 0, 40 s (cue 4), 150 s (cue 15) and 290 s (cue 29)
        val parts = YtKurzSlicing.slices(cues(30), listOf(0, 40, 150, 290), maxChars = 1500)
        assertEquals(2, parts.size)
        assertEquals(150.0, parts[1].first().startSec, 0.0)
    }

    @Test fun orderAndCoverageHold() {
        val all = cues(37)
        val parts = YtKurzSlicing.slices(all, listOf(55, 200), maxChars = 1200)
        assertEquals(all, parts.flatten())
        assertTrue(parts.all { it.isNotEmpty() })
    }

    @Test fun tinyInput() {
        assertEquals(1, YtKurzSlicing.slices(cues(1), emptyList(), 10).size)
    }

    @Test fun mergeTakesTurnsAndSortsByTime() {
        fun s(sec: Long) = YtNewsStatement("t$sec", "v", sec)
        val merged = YtKurzSlicing.merge(listOf(listOf(s(1), s(2), s(3), s(4)), listOf(s(10), s(11), s(12), s(13)), listOf(s(20), s(21))), max = 5)
        assertEquals(listOf(1L, 2L, 10L, 11L, 20L), merged.map { it.seconds })
        assertEquals(5, merged.size)
        assertEquals(merged.sortedBy { it.seconds }, merged)
    }
}
