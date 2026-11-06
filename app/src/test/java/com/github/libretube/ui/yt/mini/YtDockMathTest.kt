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
}
