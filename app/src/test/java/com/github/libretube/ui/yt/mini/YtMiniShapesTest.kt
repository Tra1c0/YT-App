package com.github.libretube.ui.yt.mini

import com.github.libretube.ui.yt.YtFeedback
import com.github.libretube.ui.yt.YtPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class YtMiniShapesTest {
    private val spec = YtShapesLayout.spec(YtMiniSpecs.SHAPES)
    private val px = 2.75f

    /** Screens 360, 393 and 430 reference dp wide, and a landscape one. */
    private val boxes = listOf(360f, 393f, 430f, 860f).map { (it - spec.start - spec.end) * px }

    private fun forAll(block: (Float, Boolean) -> Unit) {
        for (width in boxes) for (hasNext in listOf(true, false)) block(width, hasNext)
    }

    @Test
    fun theDockedPictureIsASquareInsideTheBody() {
        for (width in boxes) {
            val g = YtMiniLayout.rest(spec, width, px)
            assertEquals(g.player.width, g.player.height, 0.01f)
            assertTrue(g.player.left >= g.bodyStart && g.player.top >= 0f && g.player.bottom <= g.content + 0.01f)
            // the outline rounds it into a circle, which the cookie fits into
            assertEquals(g.player.width / 2f, spec.thumbRadius * px, 0.01f)
            assertTrue("text has room", g.text.width > 60f * px)
        }
    }

    @Test
    fun thePreviewGrowsUpwardAndKeepsItsPartsApart() = forAll { width, hasNext ->
        val rest = YtMiniLayout.rest(spec, width, px)
        val p = YtShapesLayout.peek(spec, width, px, hasNext)
        val f = p.frame
        assertTrue("the box grows", f.boxHeight > rest.boxHeight)
        assertTrue("the picture grows", f.player.width > rest.player.width && f.player.height > rest.player.height)
        assertEquals("4:3", 4f / 3f, f.player.width / f.player.height, 0.02f)
        // about 1.8 times the docked height (less on a narrow screen, where the share caps it)
        assertTrue(f.player.height <= spec.thumbHeight * YtShapesLayout.GROWTH * px + 0.01f)
        assertTrue(f.player.height >= spec.thumbHeight * 1.4f * px)
        val parts = listOfNotNull(f.player, f.pill, f.title, f.next)
        for (i in parts.indices) for (j in i + 1 until parts.size) {
            assertFalse("${parts[i]} overlaps ${parts[j]}", parts[i].overlaps(parts[j]))
        }
        for (part in parts) {
            assertTrue("$part inside the body", part.left >= rest.bodyStart - 0.01f && part.right <= rest.bodyEnd + 0.01f)
            assertTrue("$part above the bar", part.top >= 0f && part.bottom <= f.boxHeight - spec.overlap * px + 0.01f)
        }
        if (hasNext) {
            val thumb = assertNotNullAnd(p.thumb)
            val next = f.next!!
            assertTrue(thumb.left >= next.left && thumb.right <= next.right && thumb.top >= next.top && thumb.bottom <= next.bottom)
            assertEquals(YtMiniTarget.NEXT, YtMiniLayout.target(f, next.centerX, next.centerY, 8f * px))
        } else {
            assertNull(p.thumb)
            assertNull(f.next)
        }
        assertEquals(YtMiniTarget.PILL, YtMiniLayout.target(f, f.pill.centerX, f.pill.centerY, 8f * px))
        assertEquals(YtMiniTarget.NONE, YtMiniLayout.target(f, f.player.centerX, f.player.centerY, 0f))
    }

    private fun assertNotNullAnd(box: YtMiniBox?): YtMiniBox {
        assertNotNull(box)
        return box!!
    }

    @Test
    fun aRoundRectRayMeetsItsEdges() {
        // straight edges
        assertEquals(50f, YtShapesWindow.roundRect(0f, -1f, 80f, 50f, 20f), 0.01f)
        assertEquals(80f, YtShapesWindow.roundRect(1f, 0f, 80f, 50f, 20f), 0.01f)
        // a square with the corner of half its side is a circle
        for (i in 0 until 36) {
            val a = 2f * PI.toFloat() * i / 36
            assertEquals(31f, YtShapesWindow.roundRect(sin(a), -cos(a), 31f, 31f, 31f), 0.01f)
        }
        // the diagonal of a square corner
        val d = 1f / sqrt(2f)
        assertEquals(sqrt(2f) * 40f, YtShapesWindow.roundRect(d, d, 40f, 40f, 0f), 0.01f)
        // and of a rounded one: on the corner's circle
        val r = YtShapesWindow.roundRect(d, d, 40f, 40f, 10f)
        assertEquals(10f, sqrt(2f) * (r * d - 30f), 0.01f)
    }

    @Test
    fun theCookieHasTwelveLobesInsideTheRing() {
        val a = 31f * px
        val depth = YtShapesLayout.DEPTH
        var peaks = 0
        val n = 720
        var last = YtShapesWindow.cookie(-2f * PI.toFloat() / n, a, a, depth, 0f, YtShapesLayout.COOKIE_SCALE)
        var now = YtShapesWindow.cookie(0f, a, a, depth, 0f, YtShapesLayout.COOKIE_SCALE)
        for (i in 1..n) {
            val next = YtShapesWindow.cookie(2f * PI.toFloat() * i / n, a, a, depth, 0f, YtShapesLayout.COOKIE_SCALE)
            if (now > last && now >= next) peaks++
            last = now
            now = next
        }
        assertEquals(12, peaks)
        // the wave of progress runs outside the cookie and inside the round outline
        val ring = a * YtShapesLayout.COOKIE_SCALE + YtShapesLayout.RING_GAP * px
        for (i in 0 until 360) {
            val angle = 2f * PI.toFloat() * i / 360
            val cookie = YtShapesWindow.cookie(angle, a, a, depth * 1.5f, 7f, YtShapesLayout.COOKIE_SCALE)
            val wave = YtShapesWindow.ring(angle, ring, YtShapesLayout.RING_AMPLITUDE * px, 0.3f)
            assertTrue(cookie < wave - 1.5f * px)
            assertTrue(wave + 1.25f * px < a)
        }
        // the scallops repeat every 30°: a quarter turn in detents ends where it began
        for (i in 0 until 90) {
            val angle = 2f * PI.toFloat() * i / 90
            assertEquals(
                YtShapesWindow.cookie(angle, a, a, depth, 0f, 0.8f),
                YtShapesWindow.cookie(angle, a, a, depth, 90f, 0.8f),
                0.01f,
            )
        }
    }

    @Test
    fun theWindowMorphsFromThePictureToTheCookieAndBack() {
        val a = 31f * px
        val outline = 31f * px
        // on the page: no cookie, nothing covers the video
        assertEquals(0f, YtShapesWindow.shape(0.5f, 0f, a, a, outline, 0.07f, 0f).cookie, 0f)
        // docked: the cookie
        val docked = YtShapesWindow.shape(1f, 0f, a, a, outline, 0.07f, 0f)
        assertEquals(1f, docked.cookie, 0f)
        assertEquals(0f, docked.turn, 0.001f)
        // turning in: from a quarter turn back
        assertTrue(YtShapesWindow.shape(0.8f, 0f, a, a, outline, 0.07f, 0f).turn < -10f)
        // the preview smooths the scallops away in its first half
        assertTrue(YtShapesWindow.shape(1f, 0.25f, a, a, outline, 0.07f, 0f).cookie in 0.05f..0.95f)
        assertEquals(0f, YtShapesWindow.shape(1f, 0.6f, a, a, outline, 0.07f, 0f).cookie, 0f)
        assertEquals(0f, YtShapesWindow.shape(1f, 1.1f, a, a, outline, 0.07f, 0f).cookie, 0f)
        // the hole never reaches past the picture's outline (it would show the corners)
        val out = FloatArray(2 * YtShapesLayout.POINTS)
        for (peek in listOf(0f, 0.2f, 0.4f, 0.7f, 1f)) {
            val w = 62f * px * (1f + peek)
            val h = 62f * px * (1f + 0.6f * peek)
            val shape = YtShapesWindow.shape(1f, peek, w / 2f, h / 2f, outline, 0.1f, 13f)
            YtShapesWindow.outline(w, h, shape, out)
            for (i in 0 until YtShapesLayout.POINTS) {
                val x = out[2 * i]
                val y = out[2 * i + 1]
                assertTrue("$x in $w", x >= -0.01f && x <= w + 0.01f)
                assertTrue("$y in $h", y >= -0.01f && y <= h + 0.01f)
            }
        }
    }

    @Test
    fun theDropTurnsInThreeDetents() {
        assertEquals(0f, YtShapesWindow.detents(0f), 0f)
        assertEquals(90f, YtShapesWindow.detents(1f), 0f)
        // each stop is reached and held before the next
        assertEquals(30f, YtShapesWindow.detents(0.3f), 0.01f)
        assertEquals(60f, YtShapesWindow.detents(0.63f), 0.01f)
        // with a small overshoot on the way into a stop
        val most = (1..100).maxOf { YtShapesWindow.detents(it / 300f) }
        assertTrue(most > 30f && most < 34f)
        assertEquals(0f, acceptStep(0.1f), 0f)
        assertEquals(1f, acceptStep(1f), 0f)
    }

    @Test
    fun everyMomentHasItsOwnFeelAndAFallback() {
        val names = mutableSetOf<String>()
        for (moment in YtMoment.entries) {
            val texture = YtMiniShapesTextures.of(moment)
            assertTrue(moment.name, texture.steps.isNotEmpty())
            assertNotEquals(YtFeedback.NO_HAPTICS, texture.fallback)
            assertTrue(texture.name, texture.name.startsWith(YtMiniTextures.neutral(moment).name))
            assertTrue(texture.name.endsWith("_shapes"))
            assertTrue(names.add(texture.name))
            assertFalse(texture.composableOn(29))
        }
        // the drop: a spin, then a tick for each of the three 30° detents
        val accept = YtMiniShapesTextures.of(YtMoment.ACCEPT)
        assertEquals(YtPrimitive.SPIN, accept.steps.first().primitive)
        assertEquals(3, accept.steps.count { it.primitive == YtPrimitive.TICK && abs(it.scale - 0.5f) < 0.001f })
        // SPIN came with Android 12: before, the constant plays
        assertFalse(accept.composableOn(30))
        assertTrue(accept.composableOn(31))
        assertTrue(YtMiniShapesTextures.of(YtMoment.DOCK).composableOn(30))
    }
}
