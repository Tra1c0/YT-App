package com.github.libretube.ui.yt.mini

import com.github.libretube.helpers.YtDesign
import com.github.libretube.ui.yt.palette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** YT (round 29): the seamless minimize's decisions (window, page fade, cut) and Formen's numbers. */
class YtMiniMergeMathTest {
    private val density = 3f

    // Formen's: bar corner 32, body inset 44, body corner 30, fillet 12 (dp)
    private val merge = YtMiniMerge(
        barRadius = 32f,
        bodyInset = 44f,
        bodyRadius = 30f,
        fillet = 12f,
        barColor = 0xFF1E1D44.toInt(),
        pageColor = 0xFF12122A.toInt(),
    )

    private fun window(p: Float, motion: Boolean = true, width: Float = 1000f, height: Float = 400f) =
        YtMiniMergeMath.window(merge, p, width, height, density, motion)

    @Test
    fun theWindowStartsAsThePage() {
        assertEquals(YtMiniWindow.PAGE, window(0f))
        assertEquals(YtMiniWindow.PAGE, window(-0.2f))
    }

    @Test
    fun onItsWayDownItTakesOnTheBarsCornersAndColourAtTheBarsWidth() {
        val w = window(YtMiniMergeMath.APPROACH_TO)
        assertEquals(0f, w.inset, 0.001f)
        assertEquals(32f * density, w.top, 0.001f)
        assertEquals(32f * density, w.foot, 0.001f)
        assertEquals(0f, w.fillet, 0.001f)
        assertEquals(1f, w.tint, 0.001f)
        assertEquals(merge.barColor, YtMiniMergeMath.color(merge, w.tint))
        // and it stays bar-shaped until it narrows
        assertEquals(w, window(YtMiniMergeMath.NARROW_FROM))
    }

    @Test
    fun dockedItIsTheBulgeTheBodyDrawsAtRest() {
        val w = window(1f, height = 67f * density)
        assertEquals(44f * density, w.inset, 0.001f)
        assertEquals(30f * density, w.top, 0.001f)
        assertEquals(12f * density, w.fillet, 0.001f)
        assertEquals(0f, w.foot, 0.001f)
        assertEquals(1f, w.tint, 0.001f)
    }

    @Test
    fun theFootIsConvexOrConcaveNeverBoth() {
        var p = 0f
        while (p <= 1f) {
            val w = window(p)
            assertTrue("p=$p $w", w.foot == 0f || w.fillet == 0f)
            assertTrue("p=$p fillet fits the inset", w.fillet <= w.inset + 0.001f)
            p += 0.005f
        }
    }

    @Test
    fun theWindowMorphsWithoutJumps() {
        var last = window(0f)
        var p = 0.002f
        while (p <= 1f) {
            val w = window(p)
            // 2 ‰ of the progress moves nothing by more than a few px (no step anywhere)
            assertTrue("inset at $p", abs(w.inset - last.inset) < 3f)
            assertTrue("top at $p", abs(w.top - last.top) < 3f)
            assertTrue("foot at $p", abs(w.foot - last.foot) < 3f)
            assertTrue("fillet at $p", abs(w.fillet - last.fillet) < 3f)
            assertTrue("tint at $p", abs(w.tint - last.tint) < 0.02f)
            last = w
            p += 0.002f
        }
    }

    @Test
    fun cornersFitASmallBox() {
        val w = window(0.75f, width = 100f, height = 60f)
        assertTrue(w.top <= 50f)
        assertTrue(w.top + w.foot <= 60f + 0.001f)
        assertEquals(YtMiniWindow.PAGE, window(0.5f, width = 0f, height = 0f))
    }

    @Test
    fun withoutMotionTheWindowChangesInSteps() {
        assertEquals(YtMiniWindow.PAGE, window(0.49f, motion = false))
        val bar = window(0.5f, motion = false)
        assertEquals(32f * density, bar.top, 0.001f)
        assertEquals(1f, bar.tint, 0.001f)
        assertEquals(0f, bar.inset, 0.001f)
        assertEquals(bar, window(0.999f, motion = false))
        assertEquals(44f * density, window(1f, motion = false).inset, 0.001f)
    }

    @Test
    fun theColourRunsFromThePageToTheBar() {
        assertEquals(merge.pageColor, YtMiniMergeMath.color(merge, 0f))
        assertEquals(merge.barColor, YtMiniMergeMath.color(merge, 1f))
        assertEquals(merge.barColor, YtMiniMergeMath.color(merge, 3f))
        // half way: each channel half way (0x12→0x1E, 0x12→0x1D, 0x2A→0x44)
        assertEquals(0xFF181837.toInt(), YtMiniMergeMath.color(merge, 0.5f))
        assertEquals(0x80FF0000.toInt(), YtMiniMergeMath.lerpArgb(0x00FF0000, 0xFFFF0000.toInt(), 0.5f))
    }

    @Test
    fun thePageBelowFadesOutSoftlyAndEarly() {
        assertEquals(1f, YtMiniMergeMath.pageAlpha(0f, true), 0f)
        assertEquals(1f, YtMiniMergeMath.pageAlpha(YtMiniMergeMath.FADE_FROM, true), 0f)
        assertEquals(0f, YtMiniMergeMath.pageAlpha(YtMiniMergeMath.FADE_TO, true), 0f)
        assertEquals(0f, YtMiniMergeMath.pageAlpha(1f, true), 0f)
        var last = 1f
        var p = 0f
        while (p <= 1f) {
            val a = YtMiniMergeMath.pageAlpha(p, true)
            assertTrue("never rises at $p", a <= last)
            assertTrue("soft at $p", last - a < 0.03f)
            last = a
            p += 0.005f
        }
        // half way through the fade it is half gone
        assertEquals(0.5f, YtMiniMergeMath.pageAlpha((YtMiniMergeMath.FADE_FROM + YtMiniMergeMath.FADE_TO) / 2f, true), 0.001f)
    }

    @Test
    fun withoutMotionThePageIsThereOrGone() {
        assertEquals(1f, YtMiniMergeMath.pageAlpha(0.3f, false), 0f)
        assertEquals(0f, YtMiniMergeMath.pageAlpha(0.5f, false), 0f)
    }

    @Test
    fun theWindowIsOpaqueAsSoonAsThePageMoves() {
        assertEquals(0f, YtMiniMergeMath.underAlpha(0f), 0f)
        assertEquals(1f, YtMiniMergeMath.underAlpha(0.01f), 0f)
        assertEquals(1f, YtMiniMergeMath.underAlpha(1f), 0f)
    }

    @Test
    fun aDragFromThePageHitsNoInvisibleWall() {
        // the full page dragged past its docked place: rubber band down, the transition at its edge
        val wall = YtDockMath.clipsToBar(40f, barProgress = 0.999f, pageProgress = 0.999f, peeking = false, boxHeight = 200, pageWidth = 1080)
        assertTrue("the old rule cuts it", wall)
        assertFalse(wall && YtMiniMergeMath.clipAllowed(merging = true, fromPage = true))
        // the throw away (the docked player dragged down and let go) and the rise keep their cut
        assertTrue(YtMiniMergeMath.clipAllowed(merging = true, fromPage = false))
        // designs without the merge are cut as before
        assertTrue(YtMiniMergeMath.clipAllowed(merging = false, fromPage = true))
        assertTrue(YtMiniMergeMath.clipAllowed(merging = false, fromPage = false))
    }

    @Test
    fun thePictureIsRoundedWhereItTouchesARoundWindowCorner() {
        val w = YtMiniWindow(inset = 0f, top = 90f, foot = 90f, fillet = 0f, tint = 1f)
        // the picture fills the box's height at its start: its corners need the window's
        assertEquals(90f, YtMiniMergeMath.pictureCorner(10f, w, 1000f, 400f, left = 0f, top = 0f, right = 500f, bottom = 0f), 0.001f)
        // 30 px in from the side: 30 px less
        assertEquals(60f, YtMiniMergeMath.pictureCorner(10f, w, 1000f, 400f, left = 30f, top = 0f, right = 500f, bottom = 0f), 0.001f)
        // 30 px in from both sides: the window's arc is 90·(√2−1) ≈ 37 px from its corner, so
        // the picture needs 90 − 60 − √1800 ≈ −12: nothing more than its own
        assertEquals(10f, YtMiniMergeMath.pictureCorner(10f, w, 1000f, 400f, left = 30f, top = 30f, right = 500f, bottom = 30f), 0.001f)
        // 10 px in from both: 90 − 20 − √200
        assertEquals(90f - 20f - kotlin.math.sqrt(200f), YtMiniMergeMath.pictureCorner(0f, w, 1000f, 400f, left = 10f, top = 10f, right = 500f, bottom = 10f), 0.001f)
        // well inside the window: its own corner
        assertEquals(10f, YtMiniMergeMath.pictureCorner(10f, w, 1000f, 400f, left = 120f, top = 100f, right = 500f, bottom = 100f), 0.001f)
        // never more than half its shorter side
        assertEquals(50f, YtMiniMergeMath.pictureCorner(10f, w, 1000f, 100f, left = 0f, top = 0f, right = 500f, bottom = 0f), 0.001f)
        // the window's inset counts: a picture beside a narrower body is measured from the body's edge
        val narrow = w.copy(inset = 40f)
        assertEquals(90f, YtMiniMergeMath.pictureCorner(0f, narrow, 1000f, 400f, left = 40f, top = 0f, right = 500f, bottom = 0f), 0.001f)
        // no picture: its own corner
        assertEquals(5f, YtMiniMergeMath.pictureCorner(5f, w, 100f, 100f, left = 60f, top = 0f, right = 60f, bottom = 0f), 0.001f)
    }

    @Test
    fun onlyFormenMergesThisRound() {
        for (design in YtDesign.entries) {
            val palette = design.palette ?: continue
            val m = YtMiniDesigns.of(design).merge(palette)
            if (design == YtDesign.SHAPES) assertNotNull(m) else assertNull("$design", m)
        }
    }

    @Test
    fun formenMergesIntoItsOwnBarAndEndsInItsBulge() {
        val m = YtMiniShapes.merge(YtDesign.SHAPES.palette!!)
        val spec = YtMiniShapes.spec
        // the bar's surface of ShapesNav: 32 dp corners, the night blue the body is painted in
        assertEquals(32f, m.barRadius, 0f)
        assertEquals(0xFF1E1D44.toInt(), m.barColor)
        // docked it is exactly the bulge the body draws at rest
        assertEquals(spec.body.toFloat(), m.bodyInset, 0f)
        assertEquals(spec.radius.toFloat(), m.bodyRadius, 0f)
        assertEquals(spec.fillet.toFloat(), m.fillet, 0f)
        assertEquals(44f, m.bodyInset, 0f)
    }
}
