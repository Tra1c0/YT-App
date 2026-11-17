package com.github.libretube.ui.yt.bloom

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class YtBloomMathTest {
    private val w = 31
    private val h = 31

    private fun spot(): IntArray = IntArray(w * h) { 0xFF000000.toInt() }.also { it[15 * w + 15] = 0xFFFFFFFF.toInt() }
    private fun grey(c: Int) = c and 0xFF

    @Test
    fun sizeIsKept() {
        assertEquals(w * h, YtBloomMath.bloom(spot(), w, h).size)
        assertEquals(0, YtBloomMath.bloom(IntArray(0), 0, 0).size)
    }

    @Test
    fun blackStaysBlack() {
        val black = IntArray(w * h) { 0xFF000000.toInt() }
        val params = YtBloomMath.Params(accent = 0xFFFF8800.toInt())
        assertTrue(YtBloomMath.bloom(black, w, h, params).all { it == 0xFF000000.toInt() })
    }

    @Test
    fun whiteSpotSpreadsAndFades() {
        val out = YtBloomMath.bloom(spot(), w, h, YtBloomMath.Params(blurRadius = 1, glowRadius = 3, glowGain = 1f))
        val at = { d: Int -> grey(out[15 * w + 15 + d]) }
        assertTrue(at(0) > at(2))
        assertTrue(at(2) >= at(5))
        assertTrue(at(5) >= at(10))
        assertTrue(at(2) > 0)
        assertTrue(at(15) < at(0))
    }

    @Test
    fun dimAndSaturationStayInRange() {
        val white = IntArray(w * h) { 0xFFFFFFFF.toInt() }
        val dimmed = YtBloomMath.bloom(white, w, h, YtBloomMath.Params(dim = 2f, saturation = 9f))
        assertTrue(dimmed.all { grey(it) in 0..255 && (it ushr 24) == 0xFF })
        val full = YtBloomMath.bloom(white, w, h, YtBloomMath.Params(dim = 0f, tint = 0f))
        assertEquals(255, grey(full[0]))
        val some = YtBloomMath.bloom(white, w, h, YtBloomMath.Params(dim = 0.25f, tint = 0f))
        assertTrue(grey(some[0]) < 255)
        val r = floatArrayOf(1f, 0.2f); val g = floatArrayOf(0f, 0.2f); val b = floatArrayOf(0f, 0.2f)
        YtBloomMath.saturate(r, g, b, 3f)
        assertTrue(r.all { it in 0f..1f } && g.all { it in 0f..1f })
    }
}
