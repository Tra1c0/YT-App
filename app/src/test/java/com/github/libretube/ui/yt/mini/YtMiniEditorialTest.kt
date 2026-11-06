package com.github.libretube.ui.yt.mini

import com.github.libretube.ui.yt.YtFeedback
import com.github.libretube.ui.yt.YtPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YtMiniEditorialTest {
    private val spec = YtEditorialLayout.spec
    private val densities = listOf(2.0f, 2.75f, 3.5f)

    // box widths in dp: the bar between its 14 dp margins on a 320, 393 and 480 dp wide screen
    private val widths = listOf(292f, 365f, 452f)

    private fun forAll(block: (width: Float, density: Float) -> Unit) {
        for (d in densities) for (w in widths) block(w * d, d)
    }

    @Test
    fun theDockedPictureIsASquare() {
        assertEquals(spec.thumbWidth, spec.thumbHeight)
        assertEquals(0, spec.radius)
        assertEquals(2.0f, spec.peekGrowth, 0f)
    }

    @Test
    fun thePreviewUnfoldsUpwardWithTheParts169AndApart() = forAll { width, d ->
        for (hasNext in listOf(true, false)) {
            val rest = YtMiniLayout.rest(spec, width, d)
            val block = YtEditorialLayout.peek(spec, width, d, hasNext)
            val peek = block.geometry
            assertTrue(peek.boxHeight > rest.boxHeight)
            assertEquals(16f / 9f, peek.player.width / peek.player.height, 0.01f)
            assertTrue(peek.player.height <= spec.thumbHeight * spec.peekGrowth * d + 0.5f)
            // the picture is at least twice as high as docked where the screen is wide enough
            if (width / d >= 360f) assertEquals(spec.thumbHeight * 2f * d, peek.player.height, 0.5f)
            for (box in listOfNotNull(peek.player, peek.title, peek.pill, peek.next, block.column, block.headline)) {
                assertTrue(box.left >= 0f && box.right <= width + 0.01f)
                assertTrue(box.top >= 0f && box.bottom <= peek.boxHeight + 0.01f)
            }
            // the masthead rule stays clear of everything
            val masthead = YtEditorialLayout.MASTHEAD * d
            assertTrue((peek.next ?: peek.player).top > masthead)
            assertFalse(peek.pill.overlaps(peek.player))
            assertFalse(block.column.overlaps(peek.pill))
            assertFalse(block.headline.overlaps(peek.player))
            assertFalse(block.headline.overlaps(peek.pill))
            assertTrue(peek.pill.width >= 40f * d)
            assertEquals(peek.title, block.headline)
            // the headline under the picture, the seam under the headline
            assertTrue(block.headline.top >= peek.player.bottom)
            assertTrue(block.headline.bottom <= peek.boxHeight - spec.overlap * d)
            val next = peek.next
            assertEquals(hasNext, next != null)
            if (next != null) {
                val rule = block.rule!!
                assertTrue(rule > next.bottom && rule < peek.player.top)
                assertFalse(next.overlaps(peek.pill))
            } else {
                assertNull(block.rule)
            }
        }
    }

    @Test
    fun theFingerThatTookThePictureRestsOnNoTarget() = forAll { width, d ->
        // the finger stays where it took the docked picture; the box grows above it
        val rest = YtMiniLayout.rest(spec, width, d)
        for (hasNext in listOf(true, false)) {
            val peek = YtEditorialLayout.peek(spec, width, d, hasNext).geometry
            val fromBottom = rest.boxHeight - rest.player.centerY
            val target = YtMiniLayout.target(peek, rest.player.centerX, peek.boxHeight - fromBottom, 8f * d)
            assertEquals(YtMiniTarget.NONE, target)
        }
    }

    @Test
    fun theLinesPrintInOrderAndAreDoneAtFull() {
        for (f in listOf<(Float) -> Float>(YtEditorialMath::skyboxPrint, YtEditorialMath::headlinePrint, YtEditorialMath::timePrint, YtEditorialMath::rulesShown)) {
            assertEquals(0f, f(0f), 0f)
            assertEquals(1f, f(1f), 0f)
            var last = 0f
            for (i in 0..20) {
                val v = f(i / 20f)
                assertTrue(v >= last)
                last = v
            }
        }
        // the rules come before the type
        assertTrue(YtEditorialMath.rulesShown(0.4f) >= YtEditorialMath.headlinePrint(0.4f))
        assertEquals(0f, YtEditorialMath.wipe(0.5f), 0f)
        assertEquals(1f, YtEditorialMath.wipe(1f), 0f)
    }

    @Test
    fun theKickerIsTypedInAtMostEightStrokes() {
        assertEquals(8, YtEditorialMath.typeSteps(20))
        assertEquals(5, YtEditorialMath.typeSteps(5))
        assertEquals(1, YtEditorialMath.typeSteps(0))
        val len = 19
        val steps = YtEditorialMath.typeSteps(len)
        val ms = YtEditorialMath.TYPE_MS
        // the first stroke lands at once, each stroke adds letters, the last stands at (steps - 1) * ms
        assertTrue(YtEditorialMath.typedChars(len, steps, 0f, ms) in 1 until len)
        var last = 0
        for (i in 0 until steps) {
            val n = YtEditorialMath.typedChars(len, steps, (i * ms).toFloat(), ms)
            assertTrue(n > last)
            last = n
        }
        assertEquals(len, last)
        assertEquals(len, YtEditorialMath.typedChars(len, steps, YtEditorialMath.typeMs(steps, ms).toFloat(), ms))
        // short texts: one letter per stroke
        for (i in 0 until 5) assertEquals(i + 1, YtEditorialMath.typedChars(5, 5, (i * ms).toFloat(), ms))
    }

    @Test
    fun thePressTicksOncePerStroke() {
        for (letters in listOf(3, 8, 30)) {
            val texture = YtMiniEditorialTextures.of(YtMoment.PEEK_START, YtEditorialMath.typeSteps(letters))
            assertEquals(minOf(letters, 8), texture.steps.size)
            assertTrue(texture.steps.all { it.primitive == YtPrimitive.TICK && it.scale == 0.3f })
            assertEquals(0, texture.steps.first().delayMs)
            assertTrue(texture.steps.drop(1).all { it.delayMs == 28 })
        }
    }

    @Test
    fun theTexturesAreTheSpecsAndFallBack() {
        val dock = YtMiniEditorialTextures.of(YtMoment.DOCK)
        assertEquals(listOf(YtPrimitive.CLICK), dock.steps.map { it.primitive })
        assertEquals(1f, dock.steps.single().scale, 0f)
        val accept = YtMiniEditorialTextures.of(YtMoment.ACCEPT)
        assertEquals(listOf(YtPrimitive.CLICK), accept.steps.map { it.primitive })
        assertEquals(1f, accept.steps.single().scale, 0f)
        for (moment in YtMoment.values()) {
            val texture = YtMiniEditorialTextures.of(moment)
            assertEquals(YtMiniTextures.neutral(moment).fallback, texture.fallback)
            assertTrue(texture.name.startsWith(YtMiniTextures.neutral(moment).name))
            assertTrue(texture.steps.isNotEmpty())
            // every step plays on Android 11 already
            assertTrue(texture.composableOn(30))
            assertFalse(texture.composableOn(29))
        }
        assertEquals(YtFeedback.REJECT, YtMiniEditorialTextures.of(YtMoment.REJECT).fallback)
    }

    @Test
    fun theDropNumberCountsUpToItsPosition() {
        assertEquals(1, YtEditorialMath.countAt(1, 0f))
        assertEquals(1, YtEditorialMath.countAt(3, 0f))
        assertEquals(3, YtEditorialMath.countAt(3, YtEditorialMath.countMs(3).toFloat()))
        assertEquals(3, YtEditorialMath.countAt(3, 10_000f))
        // a long queue counts only the last few numbers
        assertEquals(40 - YtEditorialMath.COUNT_SPAN, YtEditorialMath.countAt(40, 0f))
        assertEquals(YtEditorialMath.COUNT_SPAN * YtEditorialMath.COUNT_MS, YtEditorialMath.countMs(40))
        var last = 0
        for (ms in 0..600 step 10) {
            val n = YtEditorialMath.countAt(7, ms.toFloat())
            assertTrue(n >= last && n <= 7)
            last = n
        }
        assertEquals("03", YtEditorialMath.number(3))
        assertEquals("12", YtEditorialMath.number(12))
    }

    @Test
    fun theTimeIsPrintedAsTheHomePrintsDurations() {
        assertEquals("0:00", YtEditorialMath.clock(0))
        assertEquals("4:07", YtEditorialMath.clock(247_900))
        assertEquals("1:02:09", YtEditorialMath.clock(3_729_000))
        assertEquals("4:07 / 12:30", YtEditorialMath.time(247_000, 750_000))
        assertEquals("12:30 / 12:30", YtEditorialMath.time(900_000, 750_000))
        assertEquals("0:05", YtEditorialMath.time(5_000, 0))
    }

    @Test
    fun theStrikeAndTheJamStartAndEndAtRest() {
        assertEquals(0f, YtEditorialMath.strike(-1f, 2f, 40, 90), 0f)
        assertEquals(2f, YtEditorialMath.strike(0f, 2f, 40, 90), 0f)
        assertEquals(1f, YtEditorialMath.strike(85f, 2f, 40, 90), 0.001f)
        assertEquals(0f, YtEditorialMath.strike(130f, 2f, 40, 90), 0f)
        assertEquals(0f, YtEditorialMath.jam(YtEditorialMath.jamMs.toFloat()), 0f)
        assertEquals(1f, YtEditorialMath.jam(0f), 0f)
        assertEquals(-1f, YtEditorialMath.jam(45f), 0f)
    }
}
