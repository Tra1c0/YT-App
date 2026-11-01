package com.github.libretube.ui.yt.mini

import com.github.libretube.ui.yt.YtFeedback
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YtMiniFrostTest {
    private val densities = listOf(2f, 2.75f, 3.5f)
    private val tabs = listOf(3, 4, 5)

    private fun inside(inner: YtMiniBox, outer: YtMiniBox, slop: Float = 0.01f) =
        inner.left >= outer.left - slop && inner.right <= outer.right + slop &&
            inner.top >= outer.top - slop && inner.bottom <= outer.bottom + slop

    @Test
    fun thePaneIsAsWideAsTheBar() {
        assertEquals(292, YtFrostLayout.barWidth(4))
        val spec = YtFrostLayout.spec(4)
        assertEquals(292, spec.width)
        // a 16:9 window
        assertEquals(16f / 9f, spec.thumbWidth.toFloat() / spec.thumbHeight, 0.01f)
    }

    @Test
    fun theDockedRowDoesNotOverlap() {
        for (d in densities) for (t in tabs) {
            val spec = YtFrostLayout.spec(t)
            val g = YtMiniLayout.rest(spec, spec.width!! * d, d)
            val play = g.play!!
            val close = g.close!!
            assertFalse(g.player.overlaps(play))
            assertFalse(play.overlaps(close))
            assertFalse(g.text.overlaps(g.player))
            assertFalse(g.text.overlaps(play))
            assertTrue("text room $t tabs", g.text.width > 40f * d)
            // the window sits inside the pane
            assertTrue(g.player.top > 0f && g.player.bottom < g.content)
        }
    }

    @Test
    fun thePreviewGrowsUpwardAndKeepsTheFingerOnThePicture() {
        for (d in densities) for (t in tabs) for (hasNext in listOf(true, false)) {
            val spec = YtFrostLayout.spec(t)
            val width = spec.width!! * d
            val rest = YtMiniLayout.rest(spec, width, d)
            val p = YtFrostLayout.peek(width, d, hasNext)
            assertTrue(p.boxHeight > rest.boxHeight)
            // the picture keeps its cut and grows
            assertEquals(16f / 9f, p.player.width / p.player.height, 0.01f)
            assertTrue(p.player.height > rest.player.height * 1.3f)
            assertTrue(p.player.height <= rest.player.height * YtFrostLayout.GROWTH + 0.01f)
            // the docked picture's middle, seen from the foot, lies on the grown picture
            val fromFoot = rest.boxHeight - rest.player.centerY
            assertTrue(p.player.contains(rest.player.centerX, p.boxHeight - fromFoot))
            // everything inside the box, nothing on top of another
            val box = YtMiniBox(0f, 0f, width, p.boxHeight)
            for (part in listOfNotNull(p.player, p.pill, p.title, p.next)) assertTrue(inside(part, box))
            assertFalse(p.player.overlaps(p.pill))
            assertFalse(p.title.overlaps(p.pill))
            if (hasNext) {
                val card = assertNotNullBox(p.next)
                assertFalse(card.overlaps(p.player))
                assertFalse(card.overlaps(p.pill))
                assertTrue("card $t tabs", card.width > 50f * d)
            } else {
                assertNull(p.next)
            }
        }
    }

    private fun assertNotNullBox(box: YtMiniBox?): YtMiniBox {
        assertNotNull(box)
        return box!!
    }

    @Test
    fun theFrostFollowsProgressAndThaws() {
        assertEquals(0f, YtFrostMath.veil(0.3f, 1f, 0f), 0f)
        assertEquals(1f, YtFrostMath.veil(1f, 1f, 0f), 0.0001f)
        // settling reaches a little further in and comes back
        assertTrue(YtFrostMath.veil(1f, 0.4f, 0f) > 1f)
        // the preview thaws the window to a rim
        assertEquals(0.3f, YtFrostMath.veil(1f, 1f, 1f), 0.0001f)
        assertEquals(0f, YtFrostMath.thaw(0f), 0f)
        assertEquals(1f, YtFrostMath.thaw(1.1f), 0f)
        assertEquals(1f, YtFrostMath.cover(0.2f), 0f)
        assertEquals(100f, YtFrostMath.reach(60f, 80f, 0f, 0f), 0.001f)
    }

    @Test
    fun theCrystalsStayPutAndInside() {
        val d = 3f
        val w = 144f * d
        val h = 81f * d
        val a = YtFrostMath.crystals(w, h, d, 14f * d)
        val b = YtFrostMath.crystals(w, h, d, 14f * d)
        assertArrayEquals(a, b, 0f)
        assertTrue(a.isNotEmpty())
        assertEquals(0, a.size % YtFrostMath.STRIDE)
        var i = 0
        while (i < a.size) {
            val x = a[i]
            val y = a[i + 1]
            val tipX = x + a[i + 2] * a[i + 4]
            val tipY = y + a[i + 3] * a[i + 4]
            assertTrue(tipX in 0f..w && tipY in 0f..h)
            // within the veil
            assertTrue(a[i + 4] <= YtFrostLayout.VEIL * d)
            assertTrue(a[i + 5] + 0.25f <= 1f)
            i += YtFrostMath.STRIDE
        }
        assertEquals(1f, YtFrostMath.grown(0.5f, 1f), 0f)
        assertEquals(0f, YtFrostMath.grown(0.5f, 0.4f), 0f)
        assertEquals(0, YtFrostMath.crystals(0f, 10f, d, 0f).size)
    }

    @Test
    fun everyMomentFeelsLikeIceAndFallsBack() {
        for (moment in YtMoment.entries) {
            val texture = YtFrostTextures.of(moment)
            assertNotEquals(YtFeedback.NO_HAPTICS, texture.fallback)
            assertTrue(texture.steps.isNotEmpty())
            assertFalse(texture.composableOn(29))
            assertTrue(moment.name, texture.name.startsWith(YtMiniTextures.neutral(moment).name))
        }
        val dock = YtFrostTextures.of(YtMoment.DOCK).steps
        assertEquals(listOf(0.5f, 0.35f, 0.2f), dock.map { it.scale })
        assertEquals(listOf(0, 40, 40), dock.map { it.delayMs })
        val accept = YtFrostTextures.of(YtMoment.ACCEPT).steps
        assertEquals(4, accept.size)
        assertEquals(listOf(0, 40, 40, 40), accept.map { it.delayMs })
    }
}
