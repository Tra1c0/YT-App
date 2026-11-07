package com.github.libretube.ui.yt.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YtChapterTapTest {
    private val starts = longArrayOf(0L, 45_000L, 90_000L)

    @Test
    fun modeFromStoredValue() {
        assertEquals(YtChapterTapMode.OFF, YtChapterTapMode.of(null))
        assertEquals(YtChapterTapMode.OFF, YtChapterTapMode.of("nonsense"))
        assertEquals(YtChapterTapMode.CHAPTERS, YtChapterTapMode.of("chapters"))
        assertEquals(YtChapterTapMode.EDGE, YtChapterTapMode.of("edge"))
    }

    @Test
    fun whereItApplies() {
        assertFalse(YtChapterTap.appliesAt(YtChapterTapMode.OFF, 0.05f))
        assertTrue(YtChapterTap.appliesAt(YtChapterTapMode.CHAPTERS, 0.3f))
        assertTrue(YtChapterTap.appliesAt(YtChapterTapMode.CHAPTERS, 0.7f))
        assertTrue(YtChapterTap.appliesAt(YtChapterTapMode.EDGE, 0.1f))
        assertTrue(YtChapterTap.appliesAt(YtChapterTapMode.EDGE, 0.9f))
        assertFalse(YtChapterTap.appliesAt(YtChapterTapMode.EDGE, 0.2f))
        assertFalse(YtChapterTap.appliesAt(YtChapterTapMode.EDGE, 0.8f))
    }

    @Test
    fun forwardGoesToTheNextStart() {
        assertEquals(45_000L, YtChapterTap.targetMs(10_000L, starts, forward = true))
        assertEquals(90_000L, YtChapterTap.targetMs(45_000L, starts, forward = true))
        // in the last chapter there is nothing ahead: the seek step stays
        assertNull(YtChapterTap.targetMs(95_000L, starts, forward = true))
    }

    @Test
    fun backGoesToThisStartThenThePreviousWithinThreeSeconds() {
        assertEquals(45_000L, YtChapterTap.targetMs(60_000L, starts, forward = false))
        assertEquals(0L, YtChapterTap.targetMs(47_000L, starts, forward = false))
        assertEquals(0L, YtChapterTap.targetMs(2_000L, starts, forward = false))
    }

    @Test
    fun repeatedTapsWalkThroughTheChapters() {
        var position = 100_000L
        val back = mutableListOf<Long>()
        repeat(3) {
            position = YtChapterTap.decide(YtChapterTapMode.CHAPTERS, 0.1f, position, starts, forward = false)!!
            back += position
        }
        assertEquals(listOf(90_000L, 45_000L, 0L), back)

        position = 0L
        val ahead = mutableListOf<Long?>()
        repeat(3) {
            val next = YtChapterTap.decide(YtChapterTapMode.CHAPTERS, 0.9f, position, starts, forward = true)
            ahead += next
            if (next != null) position = next
        }
        assertEquals(listOf(45_000L, 90_000L, null), ahead)
    }

    @Test
    fun keepsTheSeekStepWhereNoJumpFits() {
        assertNull(YtChapterTap.decide(YtChapterTapMode.OFF, 0.9f, 10_000L, starts, forward = true))
        assertNull(YtChapterTap.decide(YtChapterTapMode.CHAPTERS, 0.9f, 10_000L, longArrayOf(), forward = true))
        assertNull(YtChapterTap.decide(YtChapterTapMode.CHAPTERS, 0.9f, 10_000L, starts, forward = true, live = true))
        assertNull(YtChapterTap.decide(YtChapterTapMode.EDGE, 0.7f, 10_000L, starts, forward = true))
        assertEquals(45_000L, YtChapterTap.decide(YtChapterTapMode.EDGE, 0.95f, 10_000L, starts, forward = true))
        // a negative position (before the player knows it) counts as the start
        assertEquals(0L, YtChapterTap.decide(YtChapterTapMode.CHAPTERS, 0.1f, -1L, starts, forward = false))
    }
}
