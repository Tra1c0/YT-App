package com.github.libretube.ui.yt.mini

import com.github.libretube.ui.yt.YtFeedback
import com.github.libretube.ui.yt.YtPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YtMiniKieselTest {
    private val spec = YtMiniSpecs.KIESEL

    /** Box widths of phones from 320 to 480 dp wide, less the bar's insets, at 2.75 px per dp. */
    private val density = 2.75f
    private val widths = listOf(320, 360, 393, 412, 480).map { (it - spec.start - spec.end) * density }

    @Test
    fun thePeekStaysInsideThePebbleAndGrowsThePicture() {
        for (w in widths) {
            for (hasNext in listOf(true, false)) {
                val p = YtMiniKieselLayout.peek(spec, w, density, hasNext)
                val g = p.geometry
                val left = spec.body * density
                val right = w - spec.body * density
                for (box in listOfNotNull(g.player, g.pill, g.next, p.byline)) {
                    assertTrue("$w $box", box.left >= left - 0.5f && box.right <= right + 0.5f)
                    assertTrue("$w $box", box.top >= 0f && box.bottom <= p.joint + 0.5f)
                }
                // the picture grows by the design's factor in height (or as far as 16:9 allows)
                val grown = g.player.height / (spec.thumbHeight * density)
                assertTrue("$w $grown", grown > 1.5f && grown <= YtMiniKieselLayout.GROWTH + 0.001f)
                assertEquals(g.player.height / 2f, p.pictureRadius, 0.01f)
                assertEquals(p.pictureRadius + YtMiniKieselLayout.PAD * density, p.bodyRadius, 0.01f)
                assertTrue(g.boxHeight >= p.joint + spec.overlap * density - 0.01f)
                if (hasNext) assertNotNull(g.next) else assertNull(g.next)
                assertFalse(g.pill.overlaps(p.byline))
                g.next?.let { assertFalse(it.overlaps(g.pill)) }
            }
        }
    }

    @Test
    fun theFingerOnTheDockedPictureIsOnNoTarget() {
        for (w in widths) {
            val rest = YtMiniLayout.rest(spec, w, density)
            val p = YtMiniKieselLayout.peek(spec, w, density, true).geometry
            // the docked picture's middle and corners, seen from the grown box's foot
            val shift = p.boxHeight - rest.boxHeight
            val points = listOf(
                rest.player.centerX to rest.player.centerY,
                rest.player.left to rest.player.bottom,
                rest.player.right to rest.player.top,
                rest.player.right to rest.player.bottom,
            )
            for ((x, y) in points) {
                assertEquals("$w $x $y", YtMiniTarget.NONE, YtMiniLayout.target(p, x, y + shift, 8f * density))
            }
        }
    }

    @Test
    fun theBouncesStartAndEndAtRestAndSquashAtTheImpacts() {
        for (run in listOf(YtMiniKieselLayout.DOCK, YtMiniKieselLayout.ACCEPT)) {
            assertEquals(0f, run.value(0f), 1e-4f)
            assertEquals(0f, run.value(run.duration), 1e-4f)
            assertEquals(0f, run.dip(run.duration + 1f), 1e-4f)
            var ms = 0f
            while (ms <= run.duration) {
                val v = run.value(ms)
                assertTrue("$ms $v", v > -0.1f && v < 0.1f)
                val dip = run.dip(ms)
                assertTrue("$ms $dip", dip in 0f..1f)
                ms += 2f
            }
        }
        // the docked landing squashes just after each impact (where the haptic ticks) and
        // stretches between them, each hop lower than the one before
        val dock = YtMiniKieselLayout.DOCK
        for (impact in listOf(0f, 140f, 240f, 320f)) assertTrue(dock.value(impact + 12f) < 0f)
        val hops = listOf(95f, 205f, 290f).map { dock.value(it) }
        assertTrue(hops.all { it > 0f })
        assertTrue(hops[0] > hops[1] && hops[1] > hops[2])
    }

    @Test
    fun thePeekSqueezesFirstThenStretches() {
        assertEquals(0f, YtMiniKieselLayout.squeeze(0f), 0f)
        assertTrue(YtMiniKieselLayout.squeeze(0.15f) > 0.9f)
        assertEquals(0f, YtMiniKieselLayout.squeeze(0.5f), 0f)
        assertEquals(0f, YtMiniKieselLayout.stretch(1f), 0f)
        assertEquals(1f, YtMiniKieselLayout.stretch(1.15f), 1e-4f)
        // the capsule rolls in from out of sight into place
        assertEquals(1f, YtMiniKieselLayout.roll(0.3f), 1e-4f)
        assertEquals(0f, YtMiniKieselLayout.roll(1f), 1e-4f)
        assertEquals(0f, YtMiniKieselLayout.merge(0.5f), 0f)
        assertEquals(1f, YtMiniKieselLayout.merge(1f), 0f)
    }

    @Test
    fun theDropTimelinePopsOpensAndSinks() {
        assertEquals(0f, YtMiniKieselLayout.counter(0f), 0f)
        assertEquals(1f, YtMiniKieselLayout.counter(0.5f), 0f)
        assertEquals(0f, YtMiniKieselLayout.open(0.1f), 0f)
        assertEquals(1f, YtMiniKieselLayout.open(YtMiniKieselLayout.DROP_STILL), 1e-4f)
        assertEquals(0f, YtMiniKieselLayout.open(0.95f), 0f)
        assertEquals(0f, YtMiniKieselLayout.sink(0.5f), 0f)
        assertEquals(1f, YtMiniKieselLayout.sink(1f), 0f)
    }

    @Test
    fun theTexturesHaveFallbacksAndTheBounceTicks() {
        for (moment in YtMoment.entries) {
            val texture = YtMiniKieselTextures.of(moment)
            assertTrue(moment.name, texture.steps.isNotEmpty())
            assertTrue(texture.name, texture.name.endsWith("_kiesel"))
            assertFalse(moment.name, texture.composableOn(29))
            val old = YtFeedback.compat(texture.fallback, 26)
            assertTrue("${moment.name}: $old", old in listOf(YtFeedback.CLOCK_TICK, YtFeedback.CONTEXT_CLICK, YtFeedback.LONG_PRESS))
        }
        assertEquals(YtMoment.entries.size, YtMoment.entries.map { YtMiniKieselTextures.of(it).name }.toSet().size)
        val dock = YtMiniKieselTextures.of(YtMoment.DOCK)
        assertTrue(dock.name.contains("mini_dock"))
        assertTrue(YtMiniKieselTextures.of(YtMoment.PEEK_START).name.contains("mini_peek"))
        assertEquals(listOf(YtPrimitive.THUD, YtPrimitive.TICK, YtPrimitive.TICK, YtPrimitive.TICK), dock.steps.map { it.primitive })
        assertEquals(listOf(0.9f, 0.6f, 0.35f, 0.15f), dock.steps.map { it.scale })
        // the ticks at 140, 240 and 320 ms: each delay counts from the step before
        assertEquals(listOf(140, 240, 320), dock.steps.drop(1).runningFold(0) { at, s -> at + s.delayMs }.drop(1))
    }
}
