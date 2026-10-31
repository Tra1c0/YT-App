package com.github.libretube.ui.yt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs

class YtWaveShapeTest {
    private val bands = YtWaveShape.BANDS
    private val slopes = FloatArray(bands)

    private fun depth(u: Float, levels: FloatArray): Float {
        YtWaveShape.slopes(levels, slopes)
        return YtWaveShape.depth(u, levels, slopes)
    }

    @Test
    fun tipsArePoints() {
        for (levels in listOf(FloatArray(bands), FloatArray(bands) { 1f })) {
            assertEquals(0f, depth(0f, levels), 1e-6f)
            assertEquals(0f, depth(1f, levels), 1e-6f)
        }
    }

    @Test
    fun twiceTheEarlierThickness() {
        assertEquals(YtWaveShape.QUIET, depth(0.5f, FloatArray(bands)), 1e-6f)
        assertEquals(YtWaveShape.FULL, depth(0.5f, FloatArray(bands) { 1f }), 1e-6f)
        assertEquals(2 * 0.066f, YtWaveShape.QUIET, 1e-6f)
        assertEquals(2 * 0.267f, YtWaveShape.FULL, 1e-6f)
    }

    @Test
    fun mirroredAroundTheMiddle() {
        val r = Random(1)
        val levels = FloatArray(bands) { r.nextFloat() }
        for (i in 0..100) {
            val u = i / 200f
            assertEquals(depth(u, levels), depth(1f - u, levels), 1e-5f)
        }
    }

    @Test
    fun neverSwingsBeyondTheLevels() {
        val r = Random(0)
        repeat(200) {
            val levels = FloatArray(bands) { r.nextFloat() }
            YtWaveShape.slopes(levels, slopes)
            val top = levels.max()
            for (i in 0..800) {
                val v = YtWaveShape.level(i / 800f, levels, slopes)
                assertTrue(v >= 0f && v <= top + 1e-5f)
            }
        }
    }

    @Test
    fun aLoudBandSwellsItsOwnPlace() {
        for (j in intArrayOf(0, 5, 9, 15)) {
            val levels = FloatArray(bands).also { it[j] = 1f }
            var best = 0f
            var bestS = 0f
            for (i in 0..1000) {
                val u = 0.5f + i / 2000f
                val extra = depth(u, levels) - depth(u, FloatArray(bands))
                if (extra > best) {
                    best = extra
                    bestS = 2 * u - 1
                }
            }
            val place = 0.03f + 0.82f * j / (bands - 1)
            assertTrue("band $j swells at $bestS, expected near $place", abs(bestS - place) <= 0.03f)
        }
    }

    @Test
    fun braidedLinesStayInsideTheRibbon() {
        for (i in 0..23) {
            val c = i / 23f
            for (k in 0..200) {
                val y = YtWaveShape.line(c, k / 200f)
                assertTrue(y >= -1e-6f && y <= 1f + 1e-6f)
            }
        }
    }
}
