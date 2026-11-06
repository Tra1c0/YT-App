package com.github.libretube.ui.yt.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class YtHoldLayoutTest {
    // 10 px per character, a space included
    private val measure: (String) -> Float = { it.length * 10f }
    private val noRoom = YtHoldBox(0f, 0f, 0f, 0f)

    @Test
    fun `the video shrinks to 45 percent around the finger`() {
        val frame = YtHoldBox(0f, 0f, 1600f, 900f)
        val v = YtHoldLayout.video(1600f, 900f, frame, 800f, 450f, noRoom, 0f)
        assertEquals(720f, v.width, 0.01f)
        assertEquals(405f, v.height, 0.01f)
        assertEquals(800f, v.centerX, 0.01f)
        assertEquals(450f, v.centerY, 0.01f)
    }

    @Test
    fun `it goes towards the finger but stays on the screen`() {
        val frame = YtHoldBox(0f, 0f, 1600f, 900f)
        val v = YtHoldLayout.video(1600f, 900f, frame, 1500f, 100f, noRoom, 20f)
        assertEquals(1580f, v.right, 0.01f)
        assertEquals(20f, v.top, 0.01f)
    }

    @Test
    fun `it leaves room for the tiles`() {
        val frame = YtHoldBox(0f, 0f, 1600f, 900f)
        val room = YtHoldBox(200f, 100f, 200f, 100f)
        val v = YtHoldLayout.video(1600f, 900f, frame, 1600f, 0f, room, 20f)
        assertEquals(1600f - 20f - 200f, v.right, 0.01f)
        assertEquals(20f + 100f, v.top, 0.01f)
    }

    @Test
    fun `a small player shrinks it further for the tiles, down to the minimum`() {
        val frame = YtHoldBox(0f, 0f, 1080f, 608f)
        // 2 × 200 px of tiles above and below leave 168 px of 608
        val v = YtHoldLayout.video(1080f, 608f, frame, 540f, 304f, YtHoldBox(0f, 200f, 0f, 200f), 20f)
        assertEquals(608f * YtHoldLayout.MIN_SCALE, v.height, 0.01f)
        // no room on either side: it sits in the middle
        assertEquals(304f, v.centerY, 0.01f)
        // 2 × 170 px leave 268 px: between the minimum and 45 %
        val w = YtHoldLayout.video(1080f, 608f, frame, 540f, 304f, YtHoldBox(0f, 170f, 0f, 170f), 0f)
        assertEquals(608f - 340f, w.height, 0.01f)
        assertTrue(w.height < 608f * YtHoldLayout.SCALE)
        // with room enough it stays at 45 %
        val x = YtHoldLayout.video(1080f, 608f, frame, 540f, 304f, YtHoldBox(0f, 120f, 0f, 120f), 0f)
        assertEquals(608f * YtHoldLayout.SCALE, x.height, 0.01f)
    }

    @Test
    fun `a zoomed frame bigger than the view shrinks against the view`() {
        val frame = YtHoldBox(-400f, 0f, 2000f, 900f)
        val v = YtHoldLayout.video(1600f, 900f, frame, 800f, 450f, noRoom, 0f)
        // 2400 wide: 1600 / 2400 of it is what is visible
        assertEquals(2400f * (1600f / 2400f) * YtHoldLayout.SCALE, v.width, 0.01f)
    }

    @Test
    fun `the tiles sit around the video`() {
        val video = YtHoldBox(500f, 300f, 1100f, 600f)
        val sizes = YtHoldDir.entries.associateWith { 100f to 50f }
        val t = YtHoldLayout.tiles(1600f, 900f, video, sizes, 10f, 20f)
        assertEquals(YtHoldBox(750f, 240f, 850f, 290f), t[YtHoldDir.UP])
        assertEquals(YtHoldBox(750f, 610f, 850f, 660f), t[YtHoldDir.DOWN])
        assertEquals(YtHoldBox(390f, 425f, 490f, 475f), t[YtHoldDir.LEFT])
        assertEquals(YtHoldBox(1110f, 425f, 1210f, 475f), t[YtHoldDir.RIGHT])
    }

    @Test
    fun `tiles stay inside the view and only where there is an action`() {
        val video = YtHoldBox(20f, 20f, 120f, 180f)
        val t = YtHoldLayout.tiles(1000f, 600f, video, mapOf(YtHoldDir.UP to (200f to 50f), YtHoldDir.LEFT to (80f to 60f)), 10f, 8f)
        assertEquals(2, t.size)
        assertEquals(8f, t.getValue(YtHoldDir.UP).top, 0.01f)
        assertEquals(8f, t.getValue(YtHoldDir.UP).left, 0.01f)
        assertEquals(8f, t.getValue(YtHoldDir.LEFT).left, 0.01f)
    }

    @Test
    fun `labels wrap at spaces and end with an ellipsis`() {
        assertEquals(listOf("Previous", "chapter"), YtHoldLayout.wrap("Previous chapter", 100f, 2, measure))
        assertEquals(listOf("Play"), YtHoldLayout.wrap("Play", 100f, 2, measure))
        assertEquals(listOf("Loop the", "last 10 s"), YtHoldLayout.wrap("Loop the last 10 s", 90f, 2, measure))
        val cut = YtHoldLayout.wrap("Save to a playlist now", 90f, 2, measure)
        assertEquals(2, cut.size)
        assertTrue(cut[1].endsWith("…"))
        assertTrue(cut.all { measure(it) <= 90f })
        val one = YtHoldLayout.wrap("Picture-in-picture", 60f, 2, measure)
        assertEquals(1, one.size)
        assertTrue(one[0].endsWith("…") && measure(one[0]) <= 60f)
        assertEquals(emptyList<String>(), YtHoldLayout.wrap("  ", 60f, 2, measure))
    }
}
