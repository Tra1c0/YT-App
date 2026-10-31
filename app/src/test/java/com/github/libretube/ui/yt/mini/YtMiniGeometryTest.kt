package com.github.libretube.ui.yt.mini

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YtMiniGeometryTest {
    private val designs = mapOf(
        "youtube" to YtMiniSpecs.YOUTUBE,
        "movo" to YtMiniSpecs.MOVO,
        "after" to YtMiniSpecs.AFTER,
        "side" to YtMiniSpecs.SIDE,
        "clay" to YtMiniSpecs.CLAY,
        "editorial" to YtMiniSpecs.EDITORIAL,
        "glass" to YtMiniSpecs.GLASS,
        "pop" to YtMiniSpecs.POP,
        "shapes" to YtMiniSpecs.SHAPES,
        "frost" to YtMiniSpecs.frost(4),
        "kiesel" to YtMiniSpecs.KIESEL,
        "standard" to YtMiniSpecs.STANDARD,
    )

    /** Screens 360, 393 and 430 reference dp wide (the bar's dp, which scale with the screen). */
    private val screens = listOf(360f, 393f, 430f)
    private val px = 2.75f

    private fun forAll(block: (String, YtMiniSpec, Float, Float) -> Unit) {
        for ((name, spec) in designs) {
            for (screen in screens) {
                val box = spec.width?.toFloat() ?: (screen - spec.start - spec.end)
                block(name, spec, box * px, px)
            }
        }
    }

    @Test
    fun restPartsDoNotOverlap() = forAll { name, spec, width, density ->
        val g = YtMiniLayout.rest(spec, width, density)
        val parts = listOfNotNull(g.player, g.text, g.play, g.close)
        for (i in parts.indices) {
            for (j in i + 1 until parts.size) {
                assertFalse("$name: ${parts[i]} overlaps ${parts[j]}", parts[i].overlaps(parts[j]))
            }
        }
        assertTrue("$name: text has room", g.text.width > 40f * density)
        for (part in parts) {
            assertTrue("$name: $part inside the body", part.left >= g.bodyStart - 0.01f && part.right <= g.bodyEnd + 0.01f)
            assertTrue("$name: $part above the bar", part.top >= -0.01f && part.bottom <= g.content + 0.01f)
        }
    }

    @Test
    fun buttonsAreAtLeastFortyDp() = forAll { name, spec, width, density ->
        val g = YtMiniLayout.rest(spec, width, density)
        listOfNotNull(g.play, g.close).forEach {
            assertTrue("$name: button ${it.width / density} dp", it.width >= 40f * density - 0.01f && it.height >= 40f * density - 0.01f)
        }
        assertTrue("$name: pill ${YtMiniLayout.peek(spec, width, density, true).pill.height / density} dp", YtMiniLayout.peek(spec, width, density, true).pill.height >= 40f * density - 0.01f)
    }

    @Test
    fun theRoundTenLookIsKept() {
        // the numbers attach() set by hand before the geometry was one function
        val d = 3f
        val g = YtMiniLayout.rest(YtMiniSpecs.SIDE, 1000f, d)
        assertEquals((26 + 12 + 8) * d, g.margins.playerStart, 0.001f)
        assertEquals((26 + 12 + 6) * d, g.margins.closeEnd, 0.001f)
        assertEquals(2 * d, g.margins.playEnd, 0.001f)
        assertEquals(61 * d, g.margins.boxHeight, 0.001f)
        assertEquals(12 * d, g.margins.boxStart, 0.001f)
        // the composable's thumb rect, close center and text bounds
        assertEquals((26 + 12 + 8) * d, g.player.left, 0.001f)
        assertEquals((60 * d - 45 * d) / 2f, g.player.top, 0.001f)
        val closeCenter = 1000f - (26 + 12) * d - (6 + 20) * d
        assertEquals(closeCenter, g.close!!.centerX, 0.001f)
        assertEquals(closeCenter - 42 * d, g.play!!.centerX, 0.001f)
        assertEquals(g.player.right + 12 * d, g.text.left, 0.001f)
        assertEquals(g.play!!.centerX - 24 * d, g.text.right, 0.001f)

        val frost = YtMiniLayout.rest(YtMiniSpecs.frost(4), 292 * d, d)
        assertEquals(292 * d, frost.margins.boxWidth!!, 0.001f)
        assertEquals(0f, frost.margins.boxStart, 0.001f)
    }

    @Test
    fun constraintsDoNotDependOnTheWidth() {
        for ((name, spec) in designs) {
            assertEquals(name, YtMiniLayout.rest(spec, 900f, 2.5f).margins, YtMiniLayout.rest(spec, 1200f, 2.5f).margins)
        }
    }

    @Test
    fun previewGrowsUpwardAndKeepsItsPartsApart() = forAll { name, spec, width, density ->
        for (hasNext in listOf(true, false)) {
            val rest = YtMiniLayout.rest(spec, width, density)
            val p = YtMiniLayout.peek(spec, width, density, hasNext)
            assertTrue("$name: the box grows", p.boxHeight > rest.boxHeight)
            assertTrue("$name: the picture grows", p.player.height > rest.player.height && p.player.width > rest.player.width)
            // the cut of the picture stays
            assertEquals("$name: aspect", rest.player.width / rest.player.height, p.player.width / p.player.height, 0.01f)
            val parts = listOfNotNull(p.player, p.title, p.pill, p.next)
            for (i in parts.indices) {
                for (j in i + 1 until parts.size) {
                    if (parts[i] == p.title && parts[j] == p.pill) continue
                    assertFalse("$name: ${parts[i]} overlaps ${parts[j]}", parts[i].overlaps(parts[j]))
                }
            }
            assertFalse("$name: title over pill", p.title.overlaps(p.pill))
            for (part in parts) {
                assertTrue("$name: $part inside the body", part.left >= spec.body * density - 0.01f && part.right <= width - spec.body * density + 0.01f)
                assertTrue("$name: $part above the bar", part.top >= 0f && part.bottom <= p.boxHeight - spec.overlap * density + 0.01f)
            }
            if (hasNext) assertNotNull(p.next) else assertNull(p.next)
            assertTrue("$name: pill wide enough for its label", p.pill.width >= 80f * density)
        }
    }

    @Test
    fun theFingerThatHoldsThePictureStaysOnIt() = forAll { name, spec, width, density ->
        // the box grows from its foot: a point keeps its distance to the bottom
        val rest = YtMiniLayout.rest(spec, width, density)
        for (hasNext in listOf(true, false)) {
            val p = YtMiniLayout.peek(spec, width, density, hasNext)
            val x = rest.player.centerX
            val y = p.boxHeight - (rest.boxHeight - rest.player.centerY)
            assertTrue("$name: still on the picture", p.player.contains(x, y))
            assertEquals("$name: no part acts where the finger already is", YtMiniTarget.NONE, YtMiniLayout.target(p, x, y, 8f * density))
        }
    }

    @Test
    fun theFingerFindsThePartsOfThePreview() {
        val d = 3f
        val p = YtMiniLayout.peek(YtMiniSpecs.GLASS, 369 * d, d, true)
        assertEquals(YtMiniTarget.PILL, YtMiniLayout.target(p, p.pill.centerX, p.pill.centerY, 0f))
        assertEquals(YtMiniTarget.NEXT, YtMiniLayout.target(p, p.next!!.centerX, p.next!!.centerY, 0f))
        assertEquals(YtMiniTarget.NONE, YtMiniLayout.target(p, p.player.centerX, p.player.centerY, 0f))
        // a little grace around the targets
        assertEquals(YtMiniTarget.PILL, YtMiniLayout.target(p, p.pill.right + 4 * d, p.pill.centerY, 6 * d))
        assertEquals(YtMiniTarget.NONE, YtMiniLayout.target(p, p.pill.right + 4 * d, p.pill.centerY, 0f))
        val none = YtMiniLayout.peek(YtMiniSpecs.GLASS, 369 * d, d, false)
        assertEquals(YtMiniTarget.NONE, YtMiniLayout.target(none, p.next!!.centerX, p.next!!.centerY, 0f))
    }

    @Test
    fun rightToLeftMirrors() {
        val g = YtMiniLayout.rest(YtMiniSpecs.YOUTUBE, 1000f, 2f)
        val m = YtMiniLayout.directed(g.player, 1000f, true)
        assertEquals(1000f - g.player.right, m.left, 0.001f)
        assertEquals(g.player.width, m.width, 0.001f)
        assertEquals(g.player, YtMiniLayout.directed(g.player, 1000f, false))
    }

    @Test
    fun theBarSquaresAsThePlayerArrives() {
        assertEquals(0f, YtMiniDockMath.square(0f), 0f)
        assertEquals(0f, YtMiniDockMath.square(0.3f), 0f)
        assertEquals(1f, YtMiniDockMath.square(1f), 0f)
        assertEquals(1f, YtMiniDockMath.square(0.97f), 0f)
        var last = 0f
        for (i in 0..100) {
            val v = YtMiniDockMath.square(i / 100f)
            assertTrue("monotonic at $i", v >= last)
            last = v
        }
        // no jump: small steps in progress give small steps in the corner
        for (i in 0 until 100) {
            assertTrue(YtMiniDockMath.square((i + 1) / 100f) - YtMiniDockMath.square(i / 100f) < 0.05f)
        }
    }

    @Test
    fun aDraggedVideoComesNear() {
        // box 100..200 x 100..150, reach 80
        assertEquals(1f, YtMiniDockMath.nearness(150f, 120f, 100f, 100f, 200f, 150f, 80f), 0f)
        assertEquals(1f, YtMiniDockMath.nearness(200f, 150f, 100f, 100f, 200f, 150f, 80f), 0f)
        assertEquals(0.5f, YtMiniDockMath.nearness(150f, 60f, 100f, 100f, 200f, 150f, 80f), 0.001f)
        assertEquals(0f, YtMiniDockMath.nearness(150f, 0f, 100f, 100f, 200f, 150f, 80f), 0f)
        assertEquals(0f, YtMiniDockMath.nearness(400f, 400f, 100f, 100f, 200f, 150f, 80f), 0f)
        assertEquals(0f, YtMiniDockMath.nearness(150f, 0f, 100f, 100f, 200f, 150f, 0f), 0f)
    }
}
