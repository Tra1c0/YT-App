package com.github.libretube.ui.yt.mini

import com.github.libretube.ui.yt.YtFeedback
import com.github.libretube.ui.yt.YtPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YtMiniClayLayoutTest {
    private val spec = YtMiniSpecs.CLAY
    private val px = 2.75f

    /** Screens 360, 393 and 430 reference dp wide (the bar's dp). */
    private fun forWidths(block: (Float) -> Unit) {
        for (screen in listOf(360f, 393f, 430f)) block((screen - spec.start - spec.end) * px)
    }

    @Test
    fun theDoughStretchesUpwardAndKeepsItsPartsApart() = forWidths { width ->
        val rest = YtMiniLayout.rest(spec, width, px)
        for (hasNext in listOf(true, false)) {
            val peek = YtMiniClayLayout.peek(spec, width, px, hasNext)
            val p = peek.geometry
            assertTrue("the box grows", p.boxHeight > rest.boxHeight)
            assertEquals("the picture's height grows by the factor", spec.thumbHeight * YtMiniClayLayout.GROWTH * px, p.player.height, 0.5f)
            assertEquals("the picture keeps its cut", spec.thumbWidth / spec.thumbHeight.toFloat(), p.player.width / p.player.height, 0.01f)
            assertEquals("the body at the foot is the docked box", rest.boxHeight, peek.base.height, 0.01f)
            assertEquals("the foot stays on the bar", p.boxHeight, peek.base.bottom, 0.01f)
            assertTrue("the head lies around the picture", peek.head.contains(p.player.left, p.player.top) && peek.head.contains(p.player.right, p.player.bottom))
            assertTrue("a neck between head and body", peek.base.top - peek.head.bottom >= YtMiniClayLayout.NECK * px - 0.01f)
            assertTrue("the head inside the box", peek.head.left >= 0f && peek.head.right <= width && peek.head.top >= 0f)
            val parts = listOfNotNull(peek.head, p.next, p.pill, p.title)
            for (i in parts.indices) {
                for (j in i + 1 until parts.size) {
                    assertFalse("${parts[i]} overlaps ${parts[j]}", parts[i].overlaps(parts[j]))
                }
            }
            assertTrue("the pill in the body", p.pill.top >= peek.base.top && p.pill.bottom <= peek.base.top + spec.height * px + 0.01f)
            assertTrue("the pill is at least 40 dp high", p.pill.height >= 40f * px - 0.01f)
            assertTrue("the pill inside the box", p.pill.right <= width + 0.01f)
            assertTrue("the title has room", p.title.width >= 120f * px)
            if (hasNext) {
                assertNotNull(p.next)
                val next = p.next!!
                assertTrue("the next ball beside the head", next.left >= peek.head.right)
                assertTrue("the next ball inside the box", next.right <= width + 0.01f && next.top >= 0f)
                assertTrue("the next ball above the body", next.bottom <= peek.base.top)
                assertTrue("the next ball has its least width", next.width >= YtMiniClayLayout.NEXT_MIN * px - 0.01f)
                val thumb = peek.nextThumb!!
                val text = peek.nextText!!
                assertTrue("the thumbnail inside the ball", thumb.left >= next.left && thumb.right <= next.right && thumb.top >= next.top)
                assertTrue("the text under the thumbnail", text.top >= thumb.bottom && text.bottom <= next.bottom + 0.01f)
                assertTrue("a thumbnail to see", thumb.height >= 30f * px)
            } else {
                assertNull(p.next)
                assertNull(peek.nextThumb)
            }
        }
    }

    @Test
    fun theFingerThatHeldThePictureActsOnNothing() = forWidths { width ->
        // the box grows from its foot: a point keeps its distance to the bottom; anywhere on the
        // docked picture the finger lies on no part of the stretched dough that acts
        val rest = YtMiniLayout.rest(spec, width, px)
        for (hasNext in listOf(true, false)) {
            val p = YtMiniClayLayout.peek(spec, width, px, hasNext).geometry
            for (fx in listOf(0f, 0.5f, 1f)) {
                for (fy in listOf(0f, 0.5f, 1f)) {
                    val x = rest.player.left + rest.player.width * fx
                    val y = p.boxHeight - (rest.boxHeight - (rest.player.top + rest.player.height * fy))
                    assertEquals("($fx, $fy)", YtMiniTarget.NONE, YtMiniLayout.target(p, x, y, 8f * px))
                }
            }
            // the pill and the next ball can be reached
            assertEquals(YtMiniTarget.PILL, YtMiniLayout.target(p, p.pill.centerX, p.pill.centerY, 8f * px))
            p.next?.let { assertEquals(YtMiniTarget.NEXT, YtMiniLayout.target(p, it.centerX, it.centerY, 8f * px)) }
        }
    }

    @Test
    fun theNeckThinsThenFillsOut() {
        val start = YtMiniClayLayout.waist(0f)
        val middle = YtMiniClayLayout.waist(0.5f)
        val end = YtMiniClayLayout.waist(1f)
        assertTrue("thin while pulled", middle < start - 0.2f && middle < end - 0.2f)
        assertEquals("filled out at rest", start, end, 0.001f)
        assertTrue("never cut through", (0..100).all { YtMiniClayLayout.waist(it / 100f) > 0.2f })
        assertEquals("past full size it stays filled", end, YtMiniClayLayout.waist(1.1f), 0.001f)
    }

    @Test
    fun theNextBallSqueezesOutWithAJellyOvershoot() {
        assertEquals(0f, YtMiniClayLayout.squeeze(0f), 0f)
        assertEquals(0f, YtMiniClayLayout.squeeze(0.4f), 0f)
        assertEquals(1f, YtMiniClayLayout.squeeze(1f), 0.001f)
        val peak = (0..100).maxOf { YtMiniClayLayout.squeeze(it / 100f) }
        assertTrue("overshoot $peak", peak > 1.05f && peak < 1.35f)
        assertEquals(0f, YtMiniClayLayout.puff(1f), 0f)
        assertEquals(1f, YtMiniClayLayout.puff(1.15f), 0.001f)
        assertEquals(0f, YtMiniClayLayout.puff(0.5f), 0f)
    }

    @Test
    fun theDentAndTheRippleStayInTheDough() {
        val width = 86f * px
        assertEquals(1f, YtMiniClayLayout.dent(0f, width), 0.0001f)
        assertEquals(0f, YtMiniClayLayout.dent(width / 2f, width), 0.0001f)
        assertEquals(0f, YtMiniClayLayout.dent(width, width), 0f)
        assertTrue(YtMiniClayLayout.dent(width / 4f, width) in 0.4f..0.6f)
        val reach = 350f * px
        val wave = 46f * px
        for (i in 0..100) {
            val t = i / 100f
            for (dx in listOf(-300f, -40f, 0f, 20f, 160f, 900f)) {
                val r = YtMiniClayLayout.ripple(dx * px, t, reach, wave)
                assertTrue("ripple $r at $t, $dx", r >= -1f && r <= 1f)
            }
            // nothing ahead of the front
            assertEquals(0f, YtMiniClayLayout.ripple(reach * t + 10f, t, reach, wave), 0f)
        }
        assertEquals(0f, YtMiniClayLayout.ripple(0f, 0f, reach, wave), 0f)
        assertEquals(0f, YtMiniClayLayout.ripple(0f, 1f, reach, wave), 0f)
    }

    @Test
    fun theDropGestureStartsAndEndsAtRest() {
        assertEquals(0f, YtMiniClayLayout.gulp(0f), 0f)
        assertEquals(0f, YtMiniClayLayout.gulp(1f), 0f)
        assertTrue("it presses in further first", YtMiniClayLayout.gulp(0.12f) > 1.2f)
        assertEquals("then closes", 0f, YtMiniClayLayout.gulp(0.4f), 0.001f)
        assertEquals(0f, YtMiniClayLayout.label(0f), 0f)
        assertEquals(0f, YtMiniClayLayout.label(1f), 0f)
        // without motion the still moment shows the label and nothing moves
        assertEquals(1f, YtMiniClayLayout.label(YtMiniClayLayout.DROP_STILL), 0f)
        assertEquals(0f, YtMiniClayLayout.gulp(YtMiniClayLayout.DROP_STILL), 0f)
        assertEquals(0f, YtMiniClayLayout.rippleTime(0f), 0f)
        assertEquals(1f, YtMiniClayLayout.rippleTime(0.9f), 0f)
        // the label stays a little over 1.4 s of the gesture
        val shown = (0..1000).count { YtMiniClayLayout.label(it / 1000f) > 0.5f } * YtMiniClayLayout.DROP_MS / 1000f
        assertTrue("label shown $shown ms", shown in 1250f..1600f)
    }

    @Test
    fun theLandingSquashesAndSettles() {
        assertEquals(0f, YtMiniClayLayout.squash(0f), 0.0001f)
        assertTrue("squashed first", YtMiniClayLayout.squash(0.15f) > 0.3f)
        assertTrue("springs past rest", (0..100).any { YtMiniClayLayout.squash(it / 100f) < -0.05f })
        assertTrue("settled at the end", kotlin.math.abs(YtMiniClayLayout.squash(1f)) < 0.02f)
    }

    @Test
    fun theFeelIsDough() {
        assertEquals(listOf(YtPrimitive.THUD), YtMiniClayTextures.of(YtMoment.DOCK).steps.map { it.primitive })
        assertEquals(0.8f, YtMiniClayTextures.of(YtMoment.DOCK).steps.single().scale, 0f)
        // kneading: low ticks of 0.3 every 80 ms over the first 400 ms
        val knead = YtMiniClayTextures.of(YtMoment.PEEK_START).steps
        assertEquals(6, knead.size)
        assertTrue(knead.all { it.primitive == YtPrimitive.LOW_TICK && it.scale == 0.3f })
        assertEquals(0, knead.first().delayMs)
        assertTrue(knead.drop(1).all { it.delayMs == 80 })
        assertEquals(400, knead.sumOf { it.delayMs })
        val accept = YtMiniClayTextures.of(YtMoment.ACCEPT).steps
        assertEquals(listOf(YtPrimitive.THUD, YtPrimitive.TICK), accept.map { it.primitive })
        assertEquals(0.6f, accept[0].scale, 0f)
        assertEquals(0.4f, accept[1].scale, 0f)
        assertEquals(60, accept[1].delayMs)
        val names = mutableSetOf<String>()
        for (moment in YtMoment.entries) {
            val texture = YtMiniClayTextures.of(moment)
            assertTrue(texture.name, texture.name.startsWith("mini_") && texture.name.endsWith("_clay"))
            assertTrue(texture.name, names.add(texture.name))
            // where nothing composes the constant plays, one Android 8 knows
            assertFalse(texture.name, texture.composableOn(29))
            val old = YtFeedback.compat(texture.fallback, 26)
            assertTrue("${texture.name}: $old", old in listOf(YtFeedback.CLOCK_TICK, YtFeedback.CONTEXT_CLICK, YtFeedback.LONG_PRESS))
        }
        assertTrue(YtMiniClayTextures.of(YtMoment.DOCK).name.contains("mini_dock"))
        assertTrue(YtMiniClayTextures.of(YtMoment.PEEK_START).name.contains("mini_peek"))
    }
}
