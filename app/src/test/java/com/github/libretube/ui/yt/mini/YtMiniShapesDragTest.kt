package com.github.libretube.ui.yt.mini

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI

/**
 * YT: the Formen window while the player is dragged or previewed: the mask is opaque wherever it
 * is drawn, the body fills its box and the wave's track stays on the cookie in any picture.
 */
class YtMiniShapesDragTest {
    private val px = 2.75f

    @Test
    fun theMaskIsOpaqueAtEveryProgressAndHiddenOnThePage() {
        assertEquals(0f, YtShapesLayout.overAlpha(0f), 0f)
        for (i in 1..100) assertEquals(1f, YtShapesLayout.overAlpha(i / 100f), 0f)
    }

    @Test
    fun theBodyIsOpaqueBeforeTheCookieTurnsIn() {
        assertEquals(0f, YtShapesLayout.underAlpha(0f), 0f)
        // the cookie starts to turn in at 0.72 (YtShapesWindow.shape): the body is solid by then
        for (i in 0..100) {
            val p = 0.6f + 0.4f * i / 100f
            assertEquals(1f, YtShapesLayout.underAlpha(p), 0f)
            val shape = YtShapesWindow.shape(p, 0f, 31f * px, 31f * px, 31f * px, YtShapesLayout.DEPTH, 0f)
            if (shape.cookie > 0f) assertEquals(1f, YtShapesLayout.underAlpha(p), 0f)
        }
    }

    @Test
    fun theBodyGrowsWithItsBoxAtEverySize() {
        val rest = 67f * px
        assertEquals(0f, YtShapesLayout.grow(rest, rest), 0f)
        assertEquals(100f, YtShapesLayout.grow(rest + 100f, rest), 0.001f)
        assertEquals(0f, YtShapesLayout.grow(rest - 5f, rest), 0f)
    }

    @Test
    fun theTrackFollowsTheCookieInAnyPicture() {
        val gap = YtShapesLayout.RING_GAP * px
        // the docked square, the preview's 4:3 picture and the wide picture of a drag
        for ((w, h) in listOf(62f to 62f, 149f to 112f, 300f to 169f, 120f to 160f)) {
            val a = w * px / 2f
            val b = h * px / 2f
            val shape = YtShapesWindowShape(cookie = 1f, corner = 0f, depth = YtShapesLayout.DEPTH, turn = 0f)
            for (i in 0 until 360) {
                val angle = 2f * PI.toFloat() * i / 360
                val window = YtShapesWindow.radius(angle, a, b, shape)
                val track = YtShapesWindow.ringBase(angle, a, b, gap)
                // outside the scallops, by no more than the gap past the unscalloped ellipse
                assertTrue("$w x $h at $i: $track vs $window", track >= window - 0.01f)
                assertTrue("$w x $h at $i: $track vs $window", track - window <= gap + 2f * YtShapesLayout.DEPTH * a.coerceAtLeast(b) + 0.01f)
            }
        }
    }

    @Test
    fun inTheDockedSquareTheTrackIsTheCircleItWas() {
        val a = 31f * px
        val gap = YtShapesLayout.RING_GAP * px
        for (i in 0 until 36) {
            val angle = 2f * PI.toFloat() * i / 36
            assertEquals(a * YtShapesLayout.COOKIE_SCALE + gap, YtShapesWindow.ringBase(angle, a, a, gap), 0.01f)
        }
    }
}
