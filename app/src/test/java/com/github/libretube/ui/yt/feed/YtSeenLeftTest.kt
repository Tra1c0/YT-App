package com.github.libretube.ui.yt.feed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class YtSeenLeftTest {
    @Test
    fun leftRoundsUpAndPicksTheUnit() {
        assertEquals(YtSeenMark.Left.Seconds(30), YtSeenMark.left(30_000, 60))
        assertEquals(YtSeenMark.Left.Minutes(12), YtSeenMark.left(0, 11 * 60 + 5))
        assertEquals(YtSeenMark.Left.Hours(1, 5), YtSeenMark.left(0, 65 * 60))
        // a whole number of hours keeps m = 0 (the text then leaves the minutes out)
        assertEquals(YtSeenMark.Left.Hours(1, 0), YtSeenMark.left(0, 60 * 60))
        assertNull(YtSeenMark.left(600_000, 600))
        assertNull(YtSeenMark.left(700_000, 600))
        assertNull(YtSeenMark.left(1_000, null))
        assertNull(YtSeenMark.left(1_000, 0))
    }

    @Test
    fun leftComesFromThePositionNotTheBar() {
        // 30 s into an hour: the bar shows its 4 % minimum, but 59:30 is left (rounded up: 1 h)
        val mark = YtSeenMark.of(30_000, 3600)!!
        assertEquals(0.04f, mark.progress!!, 0.0001f)
        assertEquals(YtSeenMark.Left.Hours(1, 0), YtSeenMark.left(30_000, 3600))
        // from the bar (96 % left) it would have said 58 min
        assertEquals(YtSeenMark.Left.Minutes(58), YtSeenMark.left(144_000, 3600))
        // milliseconds below a second do not count as watched
        assertEquals(YtSeenMark.Left.Seconds(1), YtSeenMark.left(59_999, 60))
        assertEquals(YtSeenMark.Left.Minutes(1), YtSeenMark.left(999, 60))
    }

    @Test
    fun pillTakesTheLongestTextThatFits() {
        // "noch 1 Std. 5 Min.", "1 Std. 5 Min.", "1:05 h"
        val widths = listOf(120, 90, 50)
        assertEquals(0, YtSeenMark.pickFit(widths, 300))
        assertEquals(0, YtSeenMark.pickFit(widths, 120))
        assertEquals(1, YtSeenMark.pickFit(widths, 119))
        assertEquals(2, YtSeenMark.pickFit(widths, 60))
        // nothing fits: no pill rather than a clipped one
        assertNull(YtSeenMark.pickFit(widths, 49))
        assertNull(YtSeenMark.pickFit(widths, 0))
        assertNull(YtSeenMark.pickFit(emptyList(), 100))
    }

    @Test
    fun heldIsPerInstance() {
        val a = Any()
        val b = Any()
        var held = emptySet<Any>()
        held = YtHeldSet.add(held, a)
        held = YtHeldSet.add(held, b)
        assertEquals(setOf(a, b), held)
        // two holds do not clobber each other: letting go of one keeps the other
        held = YtHeldSet.remove(held, a)
        assertEquals(setOf(b), held)
        // letting go twice (release and detach) is harmless and keeps the same set
        assertSame(held, YtHeldSet.remove(held, a))
        assertSame(held, YtHeldSet.add(held, b))
        held = YtHeldSet.remove(held, b)
        assertTrue(held.isEmpty())
    }
}
