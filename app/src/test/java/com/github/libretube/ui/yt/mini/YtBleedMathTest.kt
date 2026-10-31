package com.github.libretube.ui.yt.mini

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class YtBleedMathTest {
    private fun red(c: Int) = (c shr 16) and 0xFF
    private fun green(c: Int) = (c shr 8) and 0xFF
    private fun blue(c: Int) = c and 0xFF

    @Test
    fun theCopyKeepsTheFramesShape() {
        assertEquals(48 to 27, YtBleedMath.copySize(16f / 9f))
        assertEquals(27 to 48, YtBleedMath.copySize(9f / 16f))
        assertEquals(48 to 48, YtBleedMath.copySize(1f))
        // nonsense falls back to 16:9
        assertEquals(48 to 27, YtBleedMath.copySize(Float.NaN))
        assertEquals(48 to 27, YtBleedMath.copySize(0f))
    }

    @Test
    fun aWideStripShowsTheMiddleOfTheFrame() {
        // 357 x 74 dp strip of a 16:9 video: the width fills, the height is cut, centred
        val f = YtBleedMath.zoomFrame(357f, 74f, 16f / 9f)
        assertEquals(0f, f[0], 0.001f)
        assertEquals(357f, f[2], 0.001f)
        assertEquals(357f * 9f / 16f, f[3], 0.01f)
        assertEquals((74f - f[3]) / 2f, f[1], 0.001f)
        // a tall view: the height fills
        val t = YtBleedMath.zoomFrame(100f, 400f, 16f / 9f)
        assertEquals(400f, t[3], 0.001f)
        assertEquals((100f - t[2]) / 2f, t[0], 0.001f)
    }

    @Test
    fun blurSpreadsAPointAndKeepsAFlatField() {
        val w = 9
        val h = 5
        val flat = IntArray(w * h) { 0xFF336699.toInt() }
        val copy = flat.copyOf()
        YtBleedMath.blur(copy, w, h, 2)
        assertArrayEquals(flat, copy)

        val point = IntArray(w * h) { 0xFF000000.toInt() }
        point[2 * w + 4] = 0xFFFFFFFF.toInt()
        YtBleedMath.blur(point, w, h, 1, passes = 1)
        // spread over its 3 x 3 neighbourhood, gone beyond
        assertTrue(red(point[2 * w + 4]) in 25..31)
        assertTrue(red(point[1 * w + 3]) in 25..31)
        assertEquals(0, red(point[0]))
        point.forEach { assertEquals(0xFF, (it ushr 24) and 0xFF) }
    }

    @Test
    fun saturationLeavesGreysAndStrengthensColours() {
        val px = intArrayOf(0xFF808080.toInt(), 0xFFC08040.toInt())
        YtBleedMath.saturate(px, 1.3f)
        assertEquals(0x80, red(px[0]))
        assertEquals(0x80, green(px[0]))
        assertEquals(0x80, blue(px[0]))
        assertTrue(red(px[1]) > 0xC0)
        assertTrue(blue(px[1]) < 0x40)
    }

    @Test
    fun easingKeepsPartOfTheOldColour() {
        val old = intArrayOf(0xFF000000.toInt())
        val new = intArrayOf(0xFFC8C8C8.toInt())
        YtBleedMath.ease(old, new, 0.5f)
        assertEquals(100, red(new[0]))
        // a different size (a new video shape) starts fresh
        val other = intArrayOf(0xFFC8C8C8.toInt(), 0)
        YtBleedMath.ease(old, other, 0.5f)
        assertEquals(200, red(other[0]))
    }
}
