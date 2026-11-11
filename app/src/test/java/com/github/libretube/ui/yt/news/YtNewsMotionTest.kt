package com.github.libretube.ui.yt.news

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YtNewsMotionTest {
    private val full = YtNewsRect(0f, 0f, 1080f, 2400f)
    private val card = YtNewsRect(40f, 900f, 1040f, 1500f)

    private fun assertRect(expected: YtNewsRect, actual: YtNewsRect, delta: Float = 0.01f) {
        assertEquals("left", expected.left, actual.left, delta)
        assertEquals("top", expected.top, actual.top, delta)
        assertEquals("right", expected.right, actual.right, delta)
        assertEquals("bottom", expected.bottom, actual.bottom, delta)
    }

    @Test
    fun theWindowStartsAtTheCardAndEndsFull() {
        assertRect(card, YtNewsMotion.window(card, full, 0f))
        assertRect(full, YtNewsMotion.window(card, full, 1f))
        // a spring overshooting stays full
        assertRect(full, YtNewsMotion.window(card, full, 1.08f))
        val half = YtNewsMotion.window(card, full, 0.5f)
        assertRect(YtNewsRect(20f, 450f, 1060f, 1950f), half)
    }

    @Test
    fun theWindowFollowsTheFingersLessAsItOpens() {
        val start = YtNewsMotion.window(card, full, 0f, followX = 30f, followY = -60f)
        assertRect(card.offset(30f, -60f), start)
        val open = YtNewsMotion.window(card, full, 1f, followX = 30f, followY = -60f)
        assertRect(full, open)
        val between = YtNewsMotion.window(card, full, 0.25f, followX = 40f, followY = 0f)
        assertEquals(YtNewsMotion.lerp(card.left, full.left, 0.25f) + 30f, between.left, 0.01f)
    }

    @Test
    fun withoutACardTheWindowGrowsFromTheFingersCentre() {
        val start = YtNewsMotion.centreStart(full, 540f, 1200f)
        assertEquals(full.width * YtNewsMotion.CENTRE_START, start.width, 0.01f)
        assertEquals(540f, start.centerX, 0.01f)
        assertEquals(1200f, start.centerY, 0.01f)
        // the centre stays on the screen
        val corner = YtNewsMotion.centreStart(full, -100f, 5000f)
        assertEquals(0f, corner.left, 0.01f)
        assertEquals(full.bottom, corner.bottom, 0.01f)
    }

    @Test
    fun thePinchPicksTheSmallestCardUnderTheFingers() {
        val carousel = YtNewsRect(0f, 800f, 1080f, 1600f)
        val inner = YtNewsRect(40f, 850f, 500f, 1500f)
        val other = YtNewsRect(0f, 1700f, 1080f, 2200f)
        val cards = listOf(carousel, inner, other)
        assertEquals(1, YtNewsMotion.pick(cards, 100f, 900f))
        assertEquals(0, YtNewsMotion.pick(cards, 800f, 900f))
        assertEquals(2, YtNewsMotion.pick(cards, 10f, 1800f))
        // the gap between cards, the header
        assertEquals(-1, YtNewsMotion.pick(cards, 10f, 1650f))
        assertEquals(-1, YtNewsMotion.pick(cards, 10f, 100f))
        // an empty (scrolled away) card never counts
        assertEquals(-1, YtNewsMotion.pick(listOf(YtNewsRect(0f, 0f, 0f, 0f)), 0f, 0f))
    }

    @Test
    fun aCardMostlyGoneIsNotGoneBackInto() {
        assertTrue(YtNewsMotion.stillThere(card, card))
        assertTrue(YtNewsMotion.stillThere(card, card.intersect(YtNewsRect(0f, 1200f, 1080f, 2400f))))
        assertFalse(YtNewsMotion.stillThere(card, card.intersect(YtNewsRect(0f, 1300f, 1080f, 2400f))))
        assertFalse(YtNewsMotion.stillThere(card, null))
        assertFalse(YtNewsMotion.stillThere(card, card.intersect(YtNewsRect(0f, 0f, 10f, 10f))))
    }

    @Test
    fun theCardsPictureHandsOverToTheWindow() {
        // at the start only the card shows, open only the window
        assertEquals(1f, YtNewsMotion.pictureAlpha(0f), 0f)
        assertEquals(0f, YtNewsMotion.windowAlpha(0f, picture = true), 0f)
        assertEquals(0f, YtNewsMotion.pictureAlpha(1f), 0f)
        assertEquals(1f, YtNewsMotion.windowAlpha(1f, picture = true), 0f)
        // the window is opaque before the picture has gone, so nothing behind shows through
        assertEquals(1f, YtNewsMotion.windowAlpha(0.3f, picture = true), 0f)
        assertTrue(YtNewsMotion.pictureAlpha(0.3f) > 0f)
        // without a picture as in stage 1
        assertEquals(0.9f, YtNewsMotion.windowAlpha(0.5f, picture = false), 0.001f)
        // the corners: the card's, then square
        assertEquals(48f, YtNewsMotion.corner(48f, 0f), 0f)
        assertEquals(0f, YtNewsMotion.corner(48f, 1f), 0f)
    }

    @Test
    fun theDraggedWindowShrinksAboutItsCentreAndFollows() {
        val d = YtNewsMotion.dragged(full, 0.5f, 300f)
        assertEquals(full.width * 0.95f, d.width, 0.01f)
        assertEquals(full.centerX, d.centerX, 0.01f)
        assertEquals(full.centerY + 300f, d.centerY, 0.01f)
        assertRect(full, YtNewsMotion.dragged(full, 0f, 0f))
    }

    @Test
    fun pagesRecedeWithDepthAndOnlyDimWithout() {
        val middle = YtNewsMotion.page(0f, depth = true)
        assertEquals(1f, middle.scale, 0f)
        assertEquals(1f, middle.alpha, 0f)
        assertEquals(1f, middle.elevation, 0f)
        val neighbour = YtNewsMotion.page(-1f, depth = true)
        assertEquals(0.88f, neighbour.scale, 0.001f)
        assertEquals(0.6f, neighbour.alpha, 0.001f)
        assertEquals(0f, neighbour.elevation, 0f)
        // further than one page counts as one
        assertEquals(neighbour, YtNewsMotion.page(2.5f, depth = true))
        val flat = YtNewsMotion.page(1f, depth = false)
        assertEquals(1f, flat.scale, 0f)
        assertEquals(0f, flat.elevation, 0f)
        assertTrue(flat.alpha < 1f)
    }

    @Test
    fun theThumbnailLagsWithinItsOverscan() {
        assertEquals(0f, YtNewsMotion.parallax(0f, 1000f), 0f)
        val shift = YtNewsMotion.parallax(1f, 1000f)
        assertEquals(60f, shift, 0.01f)
        assertEquals(-60f, YtNewsMotion.parallax(-3f, 1000f), 0.01f)
        // the overscan covers the shift on both sides: (scale - 1) / 2 of the width
        assertTrue((YtNewsMotion.PARALLAX_SCALE - 1f) / 2f * 1000f >= shift - 0.01f)
    }

    @Test
    fun aDraggedCardTiltsALittle() {
        val rest = YtNewsMotion.tilt(0f, 300f)
        assertEquals(0f, rest.rotationX, 0f)
        assertEquals(1f, rest.scale, 0f)
        val up = YtNewsMotion.tilt(-300f, 300f)
        assertTrue(up.rotationX > 0f)
        assertTrue(up.scale > 1f)
        val down = YtNewsMotion.tilt(300f, 300f)
        assertTrue(down.rotationX < 0f)
        assertEquals(1f, down.scale, 0f)
        // never more than a few degrees
        assertTrue(YtNewsMotion.tilt(-5000f, 300f).rotationX <= 7f)
        assertEquals(1f, YtNewsMotion.tilt(100f, 0f).scale, 0f)
    }

    @Test
    fun theHomeSinksBackAsTheWindowOpens() {
        val closed = YtNewsMotion.home(0f, depth = true, blurMax = 24f)
        assertEquals(1f, closed.scale, 0f)
        assertEquals(0f, closed.blur, 0f)
        assertEquals(0f, closed.scrim, 0f)
        val open = YtNewsMotion.home(1f, depth = true, blurMax = 24f)
        assertTrue(open.scale < 1f)
        assertEquals(24f, open.blur, 0f)
        assertEquals(0.6f, open.scrim, 0.001f)
        // whole px of blur only
        assertEquals(10f, YtNewsMotion.home(0.45f, depth = true, blurMax = 24f).blur, 0f)
        // dim only where blur is not cheap, nothing but the scrim without the motion
        assertEquals(0f, YtNewsMotion.home(1f, depth = true, blurMax = 0f).blur, 0f)
        val still = YtNewsMotion.home(1f, depth = false, blurMax = 24f)
        assertEquals(1f, still.scale, 0f)
        assertEquals(0f, still.blur, 0f)
        assertEquals(0.6f, still.scrim, 0.001f)
    }

    @Test
    fun rectHelpers() {
        val r = YtNewsRect(10f, 20f, 110f, 220f).inset(5f, 10f, 15f, 20f)
        assertRect(YtNewsRect(15f, 30f, 95f, 200f), r)
        assertTrue(YtNewsRect(0f, 0f, 10f, 10f).intersect(YtNewsRect(20f, 20f, 30f, 30f)).isEmpty)
        assertEquals(0f, YtNewsMotion.smooth(0.2f, 0.4f, 0.1f), 0f)
        assertEquals(1f, YtNewsMotion.smooth(0.2f, 0.4f, 0.5f), 0f)
        assertEquals(0.5f, YtNewsMotion.smooth(0.2f, 0.4f, 0.3f), 0.001f)
    }
}
