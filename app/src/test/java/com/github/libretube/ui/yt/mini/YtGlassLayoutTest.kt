package com.github.libretube.ui.yt.mini

import com.github.libretube.ui.yt.YtFeedback
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YtGlassLayoutTest {
    /** Screens 360, 393 and 430 reference dp wide, the slab between the bar's margins. */
    private val widths = listOf(360f, 393f, 430f).map { it - YtGlassLayout.START - YtGlassLayout.END }
    private val d = 2.75f

    @Test
    fun theStripSpansTheSlabAndTucksUnderTheRim() {
        for (w in widths) {
            val g = YtGlassLayout.rest(w * d, d)
            assertEquals(6f * d, g.picture.left, 0.01f)
            assertEquals(w * d - 6f * d, g.picture.right, 0.01f)
            assertEquals(74f * d, g.picture.height, 0.01f)
            // its foot is the box's foot, 6 dp of it under the tab row's rim
            assertEquals(g.boxHeight, g.picture.bottom, 0.01f)
            assertEquals(g.boxHeight - 6f * d, g.rim, 0.01f)
            // a wide crop, as the reference (about 4.9:1 at 393 dp)
            val visible = g.picture.width / g.picture.height
            assertTrue("aspect $visible", visible in 4.3f..5.4f)
        }
    }

    @Test
    fun theHandleSitsOnTheGlassAboveThePicture() {
        val g = YtGlassLayout.rest(369f * d, d)
        assertEquals(8f * d, g.handle.top, 0.01f)
        assertEquals(36f * d, g.handle.width, 0.01f)
        assertEquals(4f * d, g.handle.height, 0.01f)
        assertEquals(g.boxWidth / 2f, g.handle.centerX, 0.01f)
        assertTrue(g.handle.bottom < g.picture.top)
    }

    @Test
    fun buttonsAndTitleKeepApart() {
        for (w in widths) {
            val g = YtGlassLayout.rest(w * d, d)
            assertEquals(36f * d, g.prev.width, 0.01f)
            assertEquals(g.picture.left + 10f * d, g.prev.left, 0.01f)
            assertEquals(g.picture.right - 10f * d, g.next.right, 0.01f)
            // centred on the part of the strip above the rim
            assertEquals((g.picture.top + g.rim) / 2f, g.prev.centerY, 0.01f)
            assertFalse(g.prev.overlaps(g.title))
            assertFalse(g.next.overlaps(g.title))
            assertEquals(g.rim - 6f * d, g.title.bottom, 0.01f)
            assertTrue("title room ${g.title.width / d} dp", g.title.width > 200f * d)
            // touch targets of at least 36 dp (the reference's circles)
            assertTrue(g.prev.height >= 36f * d - 0.01f)
        }
    }

    @Test
    fun theSharedGeometryAgreesWithGlas() {
        val g = YtGlassLayout.geometry(369f * d, d)
        val spec = YtGlassLayout.spec
        // the box, the dock offset and endPlayerRectPx read the spec: they must agree
        assertEquals((spec.height + spec.overlap) * d, g.boxHeight, 0.01f)
        assertEquals(g.boxHeight, g.margins.boxHeight, 0.01f)
        assertEquals(YtGlassLayout.rest(369f * d, d).picture, g.player)
        assertNull(g.play)
        assertNull(g.close)
        assertEquals(YtGlassLayout.geometry(900f, 2.5f).margins, YtGlassLayout.geometry(1200f, 2.5f).margins)
        assertFalse(spec.play)
        assertFalse(spec.close)
        assertEquals(2.3f, spec.peekGrowth, 0f)
    }

    @Test
    fun thePreviewGrowsTheStripUpward() {
        for (w in widths) {
            for (hasNext in listOf(true, false)) {
                val rest = YtGlassLayout.rest(w * d, d)
                val p = YtGlassLayout.peek(w * d, d, hasNext)
                assertEquals(74f * 2.3f * d, p.player.height, 0.01f)
                assertEquals(rest.picture.width, p.player.width, 0.01f)
                assertEquals(rest.picture.top, p.player.top, 0.01f)
                assertEquals(p.boxHeight, p.player.bottom, 0.01f)
                // the pill and the row lie on the picture, above the rim
                val rim = p.boxHeight - 6f * d
                assertTrue(p.player.contains(p.pill.centerX, p.pill.centerY))
                assertEquals(w * d / 2f, p.pill.centerX, 0.01f)
                if (hasNext) {
                    val row = assertNotNullBox(p.next)
                    assertTrue(row.bottom <= rim)
                    assertTrue(row.left >= p.player.left && row.right <= p.player.right)
                    assertFalse("pill over row", p.pill.overlaps(row))
                    assertTrue(p.pill.bottom <= row.top)
                } else {
                    assertNull(p.next)
                }
                assertTrue(p.pill.width >= 80f * d)
                assertTrue(p.pill.height >= 40f * d - 0.01f)
            }
        }
    }

    private fun assertNotNullBox(box: YtBox?): YtBox {
        assertNotNull(box)
        return box!!
    }

    @Test
    fun theFingerFindsThePillAndTheRow() {
        val p = YtGlassLayout.peek(369f * d, d, true)
        assertEquals(YtMiniTarget.PILL, YtMiniLayout.target(p, p.pill.centerX, p.pill.centerY, 0f))
        assertEquals(YtMiniTarget.NEXT, YtMiniLayout.target(p, p.next!!.centerX, p.next!!.centerY, 0f))
        // high on the picture there is nothing to let go on
        assertEquals(YtMiniTarget.NONE, YtMiniLayout.target(p, p.player.centerX, p.player.top + 10f * d, 0f))
    }

    @Test
    fun theOverlayHangsFromTheRim() {
        // the parts keep their distance to the foot while the picture grows
        val small = YtGlassLayout.over(357f * d, 74f * d, d)
        val tall = YtGlassLayout.over(357f * d, 170f * d, d)
        assertEquals(small.height - small.rim, tall.height - tall.rim, 0.01f)
        assertEquals(small.height - small.prev.centerY, tall.height - tall.prev.centerY, 0.01f)
        assertEquals(small.height - small.row.top, tall.height - tall.row.top, 0.01f)
        assertTrue(small.pillCenterYWithoutRow > small.pillCenterY)
    }

    @Test
    fun theMotionHasNoJumps() {
        val curves = listOf<(Float) -> Float>(
            YtGlassMath::slabRise,
            { YtGlassMath.titleAlpha(1f, it) },
            { YtGlassMath.titleAlpha(it, 0f) },
            YtGlassMath::buttonAlpha,
            YtGlassMath::buttonScale,
            YtGlassMath::pillShown,
            YtGlassMath::pillMorph,
            YtGlassMath::labelAlpha,
            YtGlassMath::rowShown,
            YtGlassMath::spacing,
        )
        for ((n, curve) in curves.withIndex()) {
            for (i in 0 until 200) {
                val a = curve(i / 200f)
                val b = curve((i + 1) / 200f)
                assertTrue("curve $n at $i: $a -> $b", kotlin.math.abs(b - a) < 0.05f)
            }
        }
        assertEquals(0f, YtGlassMath.slabRise(0f), 0f)
        assertEquals(1f, YtGlassMath.slabRise(1f), 0f)
        assertEquals(0.8f, YtGlassMath.buttonScale(0.3f), 0.0001f)
        assertEquals(0f, YtGlassMath.buttonAlpha(0.3f), 0f)
    }

    @Test
    fun theLabelTightensAtRest() {
        assertEquals(0.30f, YtGlassMath.spacing(0.3f), 0.0001f)
        assertEquals(0.06f, YtGlassMath.spacing(1f), 0.0001f)
        // a spring swinging past full size keeps it tight
        assertEquals(0.06f, YtGlassMath.spacing(1.1f), 0.0001f)
        // steps of 0.005 em
        val s = YtGlassMath.spacing(0.8f)
        assertEquals(0f, (s * 1000f).let { kotlin.math.round(it) % 5f }, 0.001f)
    }

    @Test
    fun theRowComesAfterThePillAndGoesFirst() {
        // growing, the pill is there before the row moves; shrinking (the same curve backwards)
        // the row is gone before the pill turns back into the title
        assertEquals(1f, YtGlassMath.pillShown(0.35f), 0f)
        assertEquals(0f, YtGlassMath.rowShown(0.35f), 0f)
        assertTrue(YtGlassMath.labelAlpha(0.5f) == 1f && YtGlassMath.rowShown(0.5f) < 0.5f)
    }

    @Test
    fun theSlabCornersRoundFromTheBarsToTheSlabs() {
        assertEquals(29.5f, YtGlassMath.slabTop(0f, 29.5f, 32f), 0f)
        assertEquals(32f, YtGlassMath.slabTop(200f, 29.5f, 32f), 0f)
        var last = 29.5f
        for (i in 0..64) {
            val v = YtGlassMath.slabTop(i.toFloat(), 29.5f, 32f)
            assertTrue(v >= last)
            last = v
        }
    }

    @Test
    fun theSlabGrowsFromNothingWithoutAStep() {
        val top = 6f * d
        assertEquals(0f, YtGlassMath.slabGrowth(0f, top), 0f)
        // docked: the player's extent above the navigation view plus the bar's top margin
        assertEquals(78f * d + top, YtGlassMath.slabGrowth(78f * d, top), 0.01f)
        var last = 0f
        for (i in 1..400) {
            val v = YtGlassMath.slabGrowth(i * 0.25f, top)
            assertTrue(v > last)
            // never more than twice the step: no jump near the start
            assertTrue(v - last <= 0.5f + 0.01f)
            last = v
        }
    }

    /** Every char 1 unit wide: fits when at most [room] chars. */
    private fun fits(room: Int): (String) -> Boolean = { it.length <= room }

    @Test
    fun theTitleShortensInItsMiddleAndKeepsTheChannel() {
        val title = "Warum Musik uns Gänsehaut macht"
        val channel = "Klanglabor"
        assertEquals("$title · $channel", YtGlassMath.titleLine(title, channel, fits(100)))
        val short = YtGlassMath.titleLine(title, channel, fits(30))
        assertTrue(short, short.endsWith(" · Klanglabor"))
        assertTrue(short, short.contains("…"))
        assertTrue(short, short.startsWith("Warum"))
        assertTrue(short, short.length <= 30)
        // the longest that fits: one more char would not
        assertTrue(short.length >= 29)
        // too narrow for the channel: the title alone, shortened
        val tiny = YtGlassMath.titleLine(title, channel, fits(12))
        assertFalse(tiny, tiny.contains("Klanglabor"))
        assertTrue(tiny, tiny.length <= 12 && tiny.contains("…"))
        // no channel, no title
        assertEquals(title, YtGlassMath.titleLine(title, "", fits(100)))
        assertEquals(channel, YtGlassMath.titleLine("", channel, fits(100)))
        assertEquals("", YtGlassMath.titleLine("", "", fits(100)))
    }

    @Test
    fun cutKeepsBothEnds() {
        assertEquals("abc…hij", YtGlassMath.cut("abcdefghij", 6))
        assertEquals("abcdefghij", YtGlassMath.cut("abcdefghij", 10))
        assertEquals("…", YtGlassMath.cut("abcdefghij", 0))
    }

    @Test
    fun glassHasItsOwnFeelWithFallbacks() {
        for (moment in YtMoment.entries) {
            val t = YtMiniTextures.glass(moment)
            assertTrue(moment.name, t.name.endsWith("_glass"))
            assertTrue(moment.name, t.steps.isNotEmpty())
            assertTrue(moment.name, t.fallback != YtFeedback.NO_HAPTICS)
            assertFalse(t.composableOn(29))
            assertTrue(moment.name, t.composableOn(31))
        }
        // the reference's textures
        val dock = YtMiniTextures.glass(YtMoment.DOCK)
        assertEquals(60, dock.steps[1].delayMs)
        assertEquals(YtFeedback.CONFIRM, dock.fallback)
        val accept = YtMiniTextures.glass(YtMoment.ACCEPT)
        assertEquals(90, accept.steps[1].delayMs)
        assertEquals(YtFeedback.GESTURE_START, YtMiniTextures.glass(YtMoment.PEEK_START).fallback)
        assertEquals(YtFeedback.CLOCK_TICK, YtMiniTextures.glass(YtMoment.PEEK_FULL).fallback)
        assertEquals(YtFeedback.GESTURE_END, YtMiniTextures.glass(YtMoment.PEEK_RELEASE).fallback)
        // the emulator checks find them
        assertTrue(dock.name.contains("mini_dock"))
        assertTrue(YtMiniTextures.glass(YtMoment.PEEK_START).name.contains("mini_peek"))
        assertEquals(YtMoment.entries.size, YtMoment.entries.map { YtMiniTextures.glass(it).name }.toSet().size)
    }
}
