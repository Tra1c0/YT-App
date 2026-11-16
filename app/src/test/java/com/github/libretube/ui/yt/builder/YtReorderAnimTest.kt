package com.github.libretube.ui.yt.builder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YtReorderAnimTest {
    @Test
    fun `siblings start where they were and the dropped one where it was let go`() {
        // a row of three, 100 px apart: "a" dragged 230 px to the right and dropped after "c"
        val old = mapOf("a" to (0f to 0f), "b" to (100f to 0f), "c" to (200f to 0f))
        val new = mapOf("b" to (0f to 0f), "c" to (100f to 0f), "a" to (200f to 0f))
        val starts = YtReorderAnim.starts(old, new, dropped = "a", dropX = 230f, dropY = 4f, motion = true)
        assertEquals(100f to 0f, starts["b"])
        assertEquals(100f to 0f, starts["c"])
        // old 0 + drag 230 = drawn at 230, laid out at 200: starts 30 px right of its place
        assertEquals(30f to 4f, starts["a"])
    }

    @Test
    fun `children that did not move, new ones and motion off get no shift`() {
        assertNull(YtReorderAnim.start(10f, 10f, 10.2f, 10f, dropped = false, dropX = 0f, dropY = 0f, motion = true))
        assertNull(YtReorderAnim.start(null, null, 10f, 10f, dropped = false, dropX = 0f, dropY = 0f, motion = true))
        assertNull(YtReorderAnim.start(0f, 0f, 100f, 0f, dropped = false, dropX = 0f, dropY = 0f, motion = false))
        assertTrue(YtReorderAnim.starts(mapOf("a" to (0f to 0f)), mapOf("a" to (50f to 0f)), null, 0f, 0f, motion = false).isEmpty())
    }

    @Test
    fun `a drop back at its own place springs home from the finger`() {
        assertEquals(-12f to 6f, YtReorderAnim.start(40f, 0f, 40f, 0f, dropped = true, dropX = -12f, dropY = 6f, motion = true))
    }

    @Test
    fun `the shift runs to zero with the spring's fraction`() {
        val s = 100f to -40f
        assertEquals(100f to -40f, YtReorderAnim.at(s, 1f))
        assertEquals(50f to -20f, YtReorderAnim.at(s, 0.5f))
        assertEquals(0f to -0f, YtReorderAnim.at(s, 0f))
        // a spring that passes its rest point shifts the other way for a moment
        assertEquals(-10f to 4f, YtReorderAnim.at(s, -0.1f).let { (x, y) -> Math.round(x).toFloat() to Math.round(y).toFloat() })
    }

    @Test
    fun `the whole-screen preview scrolls on a drag that moves nothing selected`() {
        assertTrue(YtEditorDrag.scrollsFeed(inFeed = true, onSelected = false, dx = 2f, dy = -30f))
        assertFalse(YtEditorDrag.scrollsFeed(inFeed = true, onSelected = true, dx = 2f, dy = -30f))
        assertFalse(YtEditorDrag.scrollsFeed(inFeed = false, onSelected = false, dx = 2f, dy = -30f))
        // sideways: a marquee or a drag, not a scroll
        assertFalse(YtEditorDrag.scrollsFeed(inFeed = true, onSelected = false, dx = 30f, dy = 5f))
    }
}
