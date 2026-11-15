package com.github.libretube.ui.yt.builder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class YtColorTest {
    @Test
    fun `hex in all lengths`() {
        assertEquals(0xFFFF0000.toInt(), YtColor.parse("#F00"))
        assertEquals(0x88FF0000.toInt(), YtColor.parse("#8F00"))
        assertEquals(0xFF112233.toInt(), YtColor.parse("112233"))
        assertEquals(0x40112233, YtColor.parse("#40112233"))
        assertEquals("#40112233", YtColor.hex(0x40112233))
        assertEquals("#112233", YtColor.hexRgb(0xFF112233.toInt()))
    }

    @Test
    fun `bad hex is null`() {
        assertNull(YtColor.parse(""))
        assertNull(YtColor.parse("#GG0000"))
        assertNull(YtColor.parse("#12345"))
        assertNull(YtColor.parse(null))
    }

    @Test
    fun `hsl round trip`() {
        for (c in listOf(0xFFFF334B.toInt(), 0xFF101014.toInt(), 0xFFC9F26F.toInt(), 0xFF5260ED.toInt(), 0xFFFFFFFF.toInt(), 0xFF808080.toInt())) {
            val back = YtColor.fromHsl(YtColor.toHsl(c))
            for (shift in listOf(16, 8, 0)) {
                assertTrue(YtColor.hex(c), abs(((c shr shift) and 0xFF) - ((back shr shift) and 0xFF)) <= 1)
            }
        }
        assertEquals(0xFFFF0000.toInt(), YtColor.fromHsl(YtColor.Hsl(0f, 1f, 0.5f)))
        assertEquals(0xFF00FF00.toInt(), YtColor.fromHsl(YtColor.Hsl(120f, 1f, 0.5f)))
    }

    @Test
    fun `alpha and contrast`() {
        assertEquals(0x80FFFFFF.toInt(), YtColor.withAlpha(0xFFFFFFFF.toInt(), 0.5f))
        assertEquals(21.0, YtColor.contrast(0xFF000000.toInt(), 0xFFFFFFFF.toInt()), 0.01)
        assertEquals(0xFF808080.toInt(), YtColor.over(0x80FFFFFF.toInt(), 0xFF000000.toInt()))
    }

    @Test
    fun `stops are sorted, clamped and never fewer than two`() {
        val stops = YtColor.normalizeStops(listOf(YtStop(1.4f, 1), YtStop(-0.2f, 2), YtStop(0.5f, 3)), 0)
        assertEquals(listOf(0f, 0.5f, 1f), stops.map { it.at })
        assertEquals(listOf(2, 3, 1), stops.map { it.color })
        assertEquals(2, YtColor.normalizeStops(emptyList(), 7).size)
        assertEquals(listOf(0f, 1f), YtColor.normalizeStops(listOf(YtStop(0.3f, 5)), 0).map { it.at })
    }

    @Test
    fun `gradient sample`() {
        val stops = listOf(YtStop(0f, 0xFF000000.toInt()), YtStop(1f, 0xFFFFFFFF.toInt()))
        assertEquals(0xFF808080.toInt(), YtColor.sample(stops, 0.5f))
        assertEquals(0xFF000000.toInt(), YtColor.sample(stops, -1f))
    }

    @Test
    fun `gradients as text and back`() {
        val linear = YtPaint(type = YtPaintType.LINEAR, angle = 45f, stops = listOf(YtStop(0f, 0xFFFF0000.toInt()), YtStop(1f, 0xFF0000FF.toInt())))
        val text = YtColor.formatGradient(linear)
        assertEquals("linear 45 #FFFF0000 0, #FF0000FF 1", text)
        assertEquals(linear, YtColor.parseGradient(text))
        val radial = YtPaint(type = YtPaintType.RADIAL, centerX = 0.25f, centerY = 0.5f, radius = 0.75f, stops = linear.stops)
        assertEquals(radial, YtColor.parseGradient(YtColor.formatGradient(radial)))
        assertEquals(YtPaint.solid(0xFF00FF00.toInt()), YtColor.parseGradient("#00FF00"))
        assertNull(YtColor.parseGradient("conic 1 #FFF 0"))
        assertNull(YtColor.parseGradient("linear x #FFFFFFFF 0"))
    }
}
