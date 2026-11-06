package com.github.libretube.ui.yt.mini

import com.github.libretube.ui.yt.YtFeedback
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class YtMiniAfterTest {
    private val spec = YtAfterLayout.spec
    private val densities = listOf(2.0f, 2.75f, 3.5f)

    // box widths in dp: the bar between its 12 dp margins on a 320, 393 and 480 dp wide screen
    private val widths = listOf(296f, 369f, 456f)

    private fun forAll(block: (width: Float, density: Float) -> Unit) {
        for (d in densities) for (w in widths) block(w * d, d)
    }

    @Test
    fun theSprocketRowStaysBetweenTheCornersAndAboveThePicture() = forAll { width, d ->
        val rest = YtMiniLayout.rest(spec, width, d)
        val holes = YtAfterLayout.sprockets(width, spec.radius * d, d)
        assertTrue(holes.count > 10)
        assertTrue(holes.left(0) >= spec.radius * d - 0.01f)
        assertTrue(holes.left(holes.count - 1) + holes.width <= width - spec.radius * d + 0.01f)
        assertTrue("holes reach the picture", holes.top + holes.height < rest.player.top)
    }

    @Test
    fun theDockedPictureIsCinemascope() {
        val scope = YtAfterLayout.scope(spec)
        assertEquals(2.38f, scope, 0.02f)
        // at rest the gate has nothing to cover
        val rest = YtMiniLayout.rest(spec, 369f * 3f, 3f)
        assertEquals(0f, YtAfterMath.gateBar(rest.player.width, rest.player.height, scope, 1f), 0.01f)
    }

    @Test
    fun thePreviewGrowsUpwardTo169AndKeepsItsPartsApart() = forAll { width, d ->
        for (hasNext in listOf(true, false)) {
            val rest = YtMiniLayout.rest(spec, width, d)
            val peek = YtAfterLayout.peek(spec, width, d, hasNext)
            assertTrue(peek.boxHeight > rest.boxHeight)
            assertEquals(16f / 9f, peek.player.width / peek.player.height, 0.01f)
            assertTrue(peek.player.height <= spec.thumbHeight * spec.peekGrowth * d + 0.5f)
            // under the sprocket row, inside the box
            val holes = YtAfterLayout.sprockets(width, spec.radius * d, d)
            assertTrue(peek.player.top > holes.top + holes.height)
            for (box in listOfNotNull(peek.player, peek.title, peek.pill, peek.next)) {
                assertTrue(box.left >= 0f && box.right <= width + 0.01f)
                assertTrue(box.top >= 0f && box.bottom <= peek.boxHeight + 0.01f)
            }
            assertFalse(peek.pill.overlaps(peek.player))
            assertFalse(peek.title.overlaps(peek.pill))
            assertTrue(peek.pill.width >= 40f * d)
            val next = peek.next
            assertEquals(hasNext, next != null)
            if (next != null) {
                // the credits line runs under the frame
                assertTrue(next.top >= peek.player.bottom)
                assertFalse(next.overlaps(peek.pill))
            }
        }
    }

    @Test
    fun theFingerFindsTheTicketAndTheCredits() {
        val d = 3f
        val peek = YtAfterLayout.peek(spec, 369f * d, d, hasNext = true)
        assertEquals(YtMiniTarget.PILL, YtMiniLayout.target(peek, peek.pill.centerX, peek.pill.centerY, 8f * d))
        assertEquals(YtMiniTarget.NEXT, YtMiniLayout.target(peek, peek.next!!.centerX, peek.next!!.centerY, 8f * d))
        assertEquals(YtMiniTarget.NONE, YtMiniLayout.target(peek, peek.player.centerX, peek.player.top + 4f, 8f * d))
    }

    @Test
    fun theGateClosesWhileMinimizingAndOpensInThePreview() {
        val scope = 100f / 42f
        // the full player's 16:9 frame: bars once the gate has closed
        assertEquals(0f, YtAfterMath.gateBar(1600f, 900f, scope, YtAfterMath.gateClosed(0f, 0f)), 0.01f)
        val closed = YtAfterMath.gateBar(1600f, 900f, scope, YtAfterMath.gateClosed(0.7f, 0f))
        assertEquals((900f - 1600f / scope) / 2f, closed, 0.5f)
        // the preview: closed at its start, open at full size
        assertEquals(1f, YtAfterMath.gateClosed(1f, 0.01f), 0.001f)
        assertEquals(0f, YtAfterMath.gateClosed(1f, 1f), 0.001f)
        var last = 2f
        for (i in 1..100) {
            val c = YtAfterMath.gateClosed(1f, i / 100f)
            assertTrue(c <= last)
            last = c
        }
        // a picture narrower than the cut gets no bars
        assertEquals(0f, YtAfterMath.gateBar(100f, 20f, scope, 1f), 0f)
    }

    @Test
    fun theHolesFillWithTheVideo() {
        val count = 30
        assertEquals(0f, (0 until count).map { YtAfterMath.holeFill(it, count, 0f) }.sum(), 0f)
        assertEquals(count.toFloat(), (0 until count).map { YtAfterMath.holeFill(it, count, 1f) }.sum(), 0.001f)
        assertEquals(15f, (0 until count).map { YtAfterMath.holeFill(it, count, 0.5f) }.sum(), 0.001f)
        assertEquals(0.5f, YtAfterMath.holeFill(3, 10, 0.35f), 0.001f)
    }

    @Test
    fun theLandingThreadsLeftToRight() {
        val count = 20
        for (i in 0 until count) {
            assertEquals(0f, YtAfterMath.thread(i, count, 0f), 0f)
            assertEquals(1f, YtAfterMath.thread(i, count, 1f), 0f)
        }
        assertTrue(YtAfterMath.thread(0, count, 0.3f) > YtAfterMath.thread(count - 1, count, 0.3f))
    }

    @Test
    fun theFrameIsPulledInJerks() {
        assertEquals(0f, YtAfterMath.intermittent(0f, 3), 0f)
        assertEquals(1f, YtAfterMath.intermittent(1f, 3), 0f)
        var last = 0f
        for (i in 0..300) {
            val v = YtAfterMath.intermittent(i / 300f, 3)
            assertTrue(v >= last - 1e-6f)
            last = v
        }
        // it stands between the pulls
        assertEquals(YtAfterMath.intermittent(0.25f, 3), YtAfterMath.intermittent(0.32f, 3), 1e-5f)
        assertEquals(1f / 3f, YtAfterMath.intermittent(0.3f, 3), 1e-5f)
    }

    @Test
    fun theRattleAndTheJudderEndAtRest() {
        assertNotEquals(0f, YtAfterMath.rattle(0f))
        assertEquals(0f, YtAfterMath.rattle((YtAfterMath.RATTLE_STEP * YtAfterMath.RATTLE_STEPS).toFloat()), 0f)
        assertEquals(0f, YtAfterMath.flicker(500f), 0f)
        assertEquals(0f, YtAfterMath.judder(0f), 0f)
        assertEquals(0f, YtAfterMath.judder(1f), 0f)
        assertEquals(0f, YtAfterMath.judder(0.05f), 0f)
    }

    @Test
    fun theProjectorHasAFeelForEveryMoment() {
        for (moment in YtMoment.entries) {
            val texture = YtMiniAfterTextures.of(moment)
            assertTrue(moment.name, texture.steps.isNotEmpty())
            assertEquals(moment.name, YtMiniTextures.neutral(moment).fallback, texture.fallback)
            assertFalse(moment.name, texture.composableOn(29))
            assertTrue(moment.name, texture.composableOn(31))
            assertTrue(moment.name, texture.steps.all { it.scale in 0f..1f && it.delayMs >= 0 })
        }
        assertTrue(YtMiniAfterTextures.of(YtMoment.DOCK).name.contains("mini_dock"))
        assertTrue(YtMiniAfterTextures.of(YtMoment.PEEK_START).name.contains("mini_peek"))
        assertEquals(YtMoment.entries.size, YtMoment.entries.map { YtMiniAfterTextures.of(it).name }.toSet().size)
        // docking: four ticks 35 ms apart, then the thud
        val dock = YtMiniAfterTextures.of(YtMoment.DOCK).steps
        assertEquals(5, dock.size)
        assertEquals(YtFeedback.CONFIRM, YtMiniAfterTextures.of(YtMoment.DOCK).fallback)
        assertTrue(dock.drop(1).all { it.delayMs == 35 })
        // a drop: three ticks, then the click
        assertEquals(4, YtMiniAfterTextures.of(YtMoment.ACCEPT).steps.size)
    }
}
