package com.github.libretube.ui.yt.builder

import com.github.libretube.ui.yt.builder.YtSnap.Kind
import com.github.libretube.ui.yt.builder.YtSnap.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YtSnapTest {
    private val container = Rect(0f, 0f, 400f, 800f)
    private val settings = YtSnap.Settings(snapIn = 8f, snapOut = 16f)

    private fun box(x: Float, y: Float, w: Float = 50f, h: Float = 50f) = Rect(x, y, x + w, y + h)

    @Test
    fun `snaps to another element's edge within reach`() {
        val other = box(100f, 300f)
        // left edge at 205 is 5 px from nothing; at 155 it is 5 px from the other's right edge 150
        val r = YtSnap.snap(box(155f, 500f), listOf(other), container, settings)
        assertEquals(-5f, r.dx)
        assertEquals(Kind.EDGE, r.state.x!!.kind)
        assertTrue(r.guides.any { it.vertical && it.at == 150f })
    }

    @Test
    fun `nothing in reach, no snap`() {
        val r = YtSnap.snap(box(230f, 520f), listOf(box(100f, 300f)), container, settings)
        assertEquals(0f, r.dx)
        assertEquals(0f, r.dy)
        assertNull(r.state.x)
        assertTrue(r.guides.isEmpty())
    }

    @Test
    fun `the container wins over a closer element line`() {
        // centre 200 is the container centre (3 px off); an element edge at 176 is 1 px from the left edge 175
        val r = YtSnap.snap(box(175f, 500f, w = 56f), listOf(box(126f, 100f)), container, settings)
        assertEquals(Kind.CONTAINER, r.state.x!!.kind)
        assertEquals(200f, 175f + r.dx + 28f, 0.01f)
    }

    @Test
    fun `a held line keeps the box until the snap-out distance`() {
        val other = box(100f, 300f)
        var state = YtSnap.snap(box(152f, 500f), listOf(other), container, settings).state
        assertEquals(150f, state.x!!.at)
        // 12 px away: beyond snap-in, inside snap-out, still held
        val held = YtSnap.snap(box(162f, 500f), listOf(other), container, settings, state)
        assertEquals(-12f, held.dx)
        state = held.state
        // 17 px: let go
        val free = YtSnap.snap(box(167f, 500f), listOf(other), Rect(0f, 0f, 1000f, 2000f), settings, state)
        assertEquals(0f, free.dx)
        assertNull(free.state.x)
    }

    @Test
    fun `a held line is not given up for a nearer one`() {
        // two lines 6 px apart: the box snapped to 150 does not jump to 156 as it moves towards it
        val others = listOf(box(100f, 300f), box(106f, 100f))
        val first = YtSnap.snap(box(151f, 500f), others, container, settings)
        assertEquals(150f, first.state.x!!.at)
        val next = YtSnap.snap(box(155f, 500f), others, container, settings, first.state)
        assertEquals(150f, next.state.x!!.at)
        assertEquals(-5f, next.dx)
    }

    @Test
    fun `equal spacing to the neighbours`() {
        // a and b 40 apart; the moving box right of b snaps to the same 40 px gap
        val a = box(0f, 500f)
        val b = box(90f, 500f)
        val r = YtSnap.snap(box(183f, 500f), listOf(a, b), container, settings.copy(kinds = setOf(Kind.SPACING)))
        assertEquals(Kind.SPACING, r.state.x!!.kind)
        assertEquals(180f, 183f + r.dx)
    }

    @Test
    fun `centred between two neighbours`() {
        val a = box(0f, 500f)
        val b = box(250f, 500f)
        // room 50..250 is 200; a 50 px box is centred at left 125
        val r = YtSnap.snap(box(121f, 500f), listOf(a, b), container, settings.copy(kinds = setOf(Kind.SPACING)))
        assertEquals(125f, 121f + r.dx)
    }

    @Test
    fun `grid snapping`() {
        val r = YtSnap.snap(box(33f, 46f, 32f, 32f), emptyList(), container, settings.copy(kinds = setOf(Kind.GRID), grid = 16f))
        assertEquals(32f, 33f + r.dx)
        assertEquals(48f, 46f + r.dy)
        // the end edge snaps where it is nearer
        val end = YtSnap.snap(box(33f, 41f, 50f, 50f), emptyList(), container, settings.copy(kinds = setOf(Kind.GRID), grid = 16f))
        assertEquals(96f, 91f + end.dy)
    }

    @Test
    fun `kinds can be turned off`() {
        val other = box(100f, 300f)
        val off = YtSnap.snap(box(152f, 500f), listOf(other), container, settings.copy(kinds = setOf(Kind.GRID)))
        assertNull(off.state.x)
        val none = YtSnap.snap(box(152f, 500f), listOf(other), container, settings.copy(kinds = emptySet()))
        assertEquals(0f, none.dx)
    }

    @Test
    fun `turning a kind off releases its held line`() {
        val other = box(100f, 300f)
        val first = YtSnap.snap(box(152f, 500f), listOf(other), container, settings)
        assertNotNull(first.state.x)
        val next = YtSnap.snap(box(152f, 500f), listOf(other), container, settings.copy(kinds = setOf(Kind.CONTAINER)), first.state)
        assertNull(next.state.x)
    }

    @Test
    fun `user guides`() {
        val r = YtSnap.snap(box(60f, 500f), emptyList(), container, settings.copy(guidesX = listOf(64f)))
        assertEquals(64f, 60f + r.dx)
        assertEquals(Kind.GUIDE, r.state.x!!.kind)
    }

    @Test
    fun `both axes snap on their own`() {
        val r = YtSnap.snap(box(3f, 797f - 50f), emptyList(), container, settings)
        assertEquals(-3f, r.dx)
        assertEquals(3f, r.dy)
        assertEquals(2, r.guides.size)
    }
}
