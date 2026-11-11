package com.github.libretube.ui.yt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YtSentimentTest {
    private val green = 0xFF00AA00.toInt()
    private val red = 0xFFAA0000.toInt()

    @Test
    fun `no bar without RYD numbers or likes`() {
        assertNull(YtSentiment.share(1000, -1))
        assertNull(YtSentiment.share(0, 50))
        assertNull(YtSentiment.share(-1, 50))
    }

    @Test
    fun `share is likes over all votes`() {
        assertEquals(0.9f, YtSentiment.share(900, 100)!!, 1e-6f)
        assertEquals(1f, YtSentiment.share(10, 0)!!, 1e-6f)
        assertEquals(0.25f, YtSentiment.share(1, 3)!!, 1e-6f)
    }

    @Test
    fun `percent is cut, never rounded up to 100`() {
        assertEquals(99, YtSentiment.percent(0.996f))
        assertEquals(100, YtSentiment.percent(1f))
        assertEquals(0, YtSentiment.percent(0f))
    }

    @Test
    fun `a positive video is mostly green, a negative one mostly red`() {
        val good = YtSentiment.stops(0.9f, green, red)
        assertEquals(green, good.first().second)
        assertEquals(red, good.last().second)
        // the green part ends close to the liked share
        assertEquals(0.9f - YtSentiment.BLEND, good[1].first, 1e-6f)
        val bad = YtSentiment.stops(0.2f, green, red)
        assertEquals(0.2f + YtSentiment.BLEND, bad[2].first, 1e-6f)
        assertTrue(bad[1].first < 0.2f)
    }

    @Test
    fun `stops stay in order and inside the bar, and both ends keep a trace`() {
        for (share in listOf(0f, 0.01f, 0.5f, 0.99f, 1f)) {
            val stops = YtSentiment.stops(share, green, red)
            assertEquals(0f, stops.first().first, 0f)
            assertEquals(1f, stops.last().first, 0f)
            stops.zipWithNext().forEach { (a, b) -> assertTrue(a.first <= b.first) }
            // red still shows at a 100 % video, green at a 0 % one
            assertTrue(stops[2].first < 1f)
            assertTrue(stops[1].first > 0f)
        }
    }

    @Test
    fun `right to left mirrors the bar`() {
        val stops = YtSentiment.stops(0.8f, green, red, rtl = true)
        assertEquals(red, stops.first().second)
        assertEquals(green, stops.last().second)
        assertEquals(0f, stops.first().first, 0f)
        assertEquals(1f, stops.last().first, 0f)
        stops.zipWithNext().forEach { (a, b) -> assertTrue(a.first <= b.first) }
        assertEquals(1f - (0.8f + YtSentiment.BLEND), stops[1].first, 1e-6f)
    }

    /** The pages the bar is drawn on: the day and night backgrounds of every design. */
    private val backgrounds = listOf(
        YtSignature.YOUTUBE to 0xFF101014.toInt(),
        YtSignature.MOVO to 0xFF101112.toInt(),
        YtSignature.AFTER to 0xFF1A0B0F.toInt(),
        YtSignature.SIDE to 0xFF292A2C.toInt(),
        YtSignature.CLAY to 0xFFF5D8C7.toInt(),
        YtSignature.CLAY to 0xFF2A201C.toInt(),
        YtSignature.EDITORIAL to 0xFFFFFFFF.toInt(),
        YtSignature.EDITORIAL to 0xFF121212.toInt(),
        YtSignature.GLASS to 0xFFD1EAE3.toInt(),
        YtSignature.GLASS to 0xFF0E1F1F.toInt(),
        YtSignature.POP to 0xFFE6E5FD.toInt(),
        YtSignature.POP to 0xFF1C1326.toInt(),
        YtSignature.SHAPES to 0xFF12122A.toInt(),
        YtSignature.FROST to 0xFF6F8A80.toInt(),
        YtSignature.KIESEL to 0xFFE9E5E0.toInt(),
        YtSignature.KIESEL to 0xFF1E1C1A.toInt(),
        YtSignature.FREQUENCY to 0xFF0B0A18.toInt(),
        YtSignature.SYNTHWAVE to 0xFF0D0620.toInt(),
    )

    @Test
    fun `green and red read on every page and apart from each other`() {
        for ((signature, back) in backgrounds) {
            val (g, r) = YtSentiment.colors(signature, back)
            assertTrue("$signature green on page", YtContrast.ratio(g, back) >= YtSentiment.MIN_CONTRAST - 0.01)
            assertTrue("$signature red on page", YtContrast.ratio(r, back) >= YtSentiment.MIN_CONTRAST - 0.01)
            assertTrue("$signature green vs red ${YtContrast.ratio(g, r)}", YtContrast.ratio(g, r) >= YtSentiment.MIN_APART - 0.01)
            // the hues stay what they are: green has more green than red, red the other way round
            assertTrue("$signature green hue", (g ushr 8 and 0xFF) > (g ushr 16 and 0xFF))
            assertTrue("$signature red hue", (r ushr 16 and 0xFF) > (r ushr 8 and 0xFF))
        }
    }

    @Test
    fun `every design has a form for the bar`() {
        YtSignature.entries.forEach { signature ->
            val style = YtSentimentStyle.of(signature)
            assertTrue(style.thicknessDp in 1f..5f)
            assertTrue(style.widthFraction in 0.3f..1f)
            // stays in the room under the button: captions start 6 dp below, tile rows are 10 dp apart
            if (style.placement != YtSentimentPlacement.INSIDE) {
                assertTrue("$signature", style.gapDp + style.thicknessDp + 2 * style.amplitudeDp <= 9.5f)
            }
        }
    }

    @Test
    fun `the reveal is due after resting on the tile`() {
        val timer = YtRevealTimer(600)
        assertNull(timer.remaining(0))
        timer.hover(true, 1000)
        assertEquals(600L, timer.remaining(1000))
        // moving on the same tile does not restart it
        timer.hover(true, 1300)
        assertEquals(300L, timer.remaining(1300))
        assertTrue(!timer.due(1599))
        assertTrue(timer.due(1600))
        // leaving starts over
        timer.hover(false, 1700)
        assertNull(timer.remaining(1700))
        timer.hover(true, 2000)
        assertTrue(!timer.due(2500))
        assertTrue(timer.due(2600))
    }
}
