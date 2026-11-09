package com.github.libretube.ui.yt.mini

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YtDockMathTest {
    @Test
    fun theFootBelongsOnTheBarPlusTheDockOffset() {
        // bar view from y=2100, the design reaches 28 px into it
        assertEquals(0, YtDockMath.footError(2128, 2100, 28))
        assertTrue(YtDockMath.onBar(2128, 2100, 28))
        // the classic player sits right on the bar
        assertEquals(0, YtDockMath.footError(2100, 2100, 0))
    }

    @Test
    fun aStaleShiftShowsAsTheInsetItMissed() {
        // the shift of a bar without its 63 px gesture inset: the box sits 63 px too low, under the bar
        assertEquals(63, YtDockMath.footError(2128 + 63, 2100, 28))
        assertFalse(YtDockMath.onBar(2128 + 63, 2100, 28))
        // and too high the other way round
        assertEquals(-63, YtDockMath.footError(2128 - 63, 2100, 28))
        assertFalse(YtDockMath.onBar(2128 - 63, 2100, 28))
    }

    @Test
    fun roundingIsAllowed() {
        assertTrue(YtDockMath.onBar(2131, 2100, 28))
        assertTrue(YtDockMath.onBar(2125, 2100, 28))
        assertFalse(YtDockMath.onBar(2132, 2100, 28))
    }

    @Test
    fun theCheckLineMatchesTheScript() {
        val line = YtDockMath.checkLine(2191, 2100, 28)
        assertEquals("dock check box=2191 bar=2100 dock=28 err=63", line)
        // scripts/gestures/b.sh reads the error with this pattern
        assertTrue(Regex("^dock check box=-?[0-9]+ bar=-?[0-9]+ dock=-?[0-9]+ err=(-?[0-9]+)$").matches(line))
    }

    @Test
    fun offsetsAreClearedOnlyWhenNothingMovesThem() {
        assertTrue(YtDockMath.clearsOffsets(dragging = false, closing = false))
        assertFalse(YtDockMath.clearsOffsets(dragging = true, closing = false))
        assertFalse(YtDockMath.clearsOffsets(dragging = false, closing = true))
    }

    @Test
    fun theThrownPlayerStaysCutWhileTheBarLetsGo() {
        // dragged down while docked: cut
        assertTrue(YtDockMath.clipsToBar(80f, barProgress = 1f, pageProgress = 1f, peeking = false, boxHeight = 190, pageWidth = 1080))
        // thrown: the bar's progress runs down at once, the page still rests docked: still cut
        assertTrue(YtDockMath.clipsToBar(120f, barProgress = 0.9f, pageProgress = 1f, peeking = false, boxHeight = 190, pageWidth = 1080))
        assertTrue(YtDockMath.clipsToBar(400f, barProgress = 0f, pageProgress = 1f, peeking = false, boxHeight = 190, pageWidth = 1080))
        // at its place, or during a minimize (the page between its ends), nothing is cut
        assertFalse(YtDockMath.clipsToBar(0f, barProgress = 1f, pageProgress = 1f, peeking = false, boxHeight = 190, pageWidth = 1080))
        assertFalse(YtDockMath.clipsToBar(30f, barProgress = 0.5f, pageProgress = 0.5f, peeking = false, boxHeight = 190, pageWidth = 1080))
        // a peek runs its own transition: only the bar's progress counts then (as before)
        assertFalse(YtDockMath.clipsToBar(30f, barProgress = 0.5f, pageProgress = 1f, peeking = true, boxHeight = 190, pageWidth = 1080))
        assertTrue(YtDockMath.clipsToBar(30f, barProgress = 1f, pageProgress = 0.2f, peeking = true, boxHeight = 190, pageWidth = 1080))
        // not laid out yet
        assertFalse(YtDockMath.clipsToBar(80f, barProgress = 1f, pageProgress = 1f, peeking = false, boxHeight = 0, pageWidth = 1080))
        assertFalse(YtDockMath.clipsToBar(80f, barProgress = 1f, pageProgress = 1f, peeking = false, boxHeight = 190, pageWidth = 0))
    }

    @Test
    fun turnedTheBoxKeepsOutOfTheSideBars() {
        // 2400 px wide, the three-button navigation 132 px at the right, a cutout 96 px at the left
        assertEquals(0, YtDockMath.sideError(96 + 24, 2400 - 132 - 24, 2400, 96, 132))
        // the box as wide as the window: under both
        assertEquals(96 + 132, YtDockMath.sideError(0, 2400, 2400, 96, 132))
        // upright nothing covers the sides
        assertEquals(0, YtDockMath.sideError(0, 1080, 1080, 0, 0))
        assertEquals(
            "dock side box=0,2400 window=2400 insets=96,132 err=228",
            YtDockMath.sideLine(0, 2400, 2400, 96, 132),
        )
    }
}
