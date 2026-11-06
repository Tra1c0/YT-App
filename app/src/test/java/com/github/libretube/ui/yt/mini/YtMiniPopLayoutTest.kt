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
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

class YtMiniPopLayoutTest {
    private val spec = YtMiniSpecs.POP
    private val px = 2.75f

    /** Screens 360, 393 and 430 reference dp wide, and a landscape one. */
    private val boxes = listOf(360f, 393f, 430f, 860f).map { (it - spec.start - spec.end) * px }

    private fun forAll(block: (Float, Boolean) -> Unit) {
        for (width in boxes) for (hasNext in listOf(true, false)) block(width, hasNext)
    }

    /** The corners of [box] turned by [degrees] around its centre (screen y down). */
    private fun turned(box: YtMiniBox, degrees: Float): List<Pair<Float, Float>> {
        val a = degrees * PI.toFloat() / 180f
        val cx = box.centerX
        val cy = box.centerY
        return listOf(box.left to box.top, box.right to box.top, box.right to box.bottom, box.left to box.bottom).map { (x, y) ->
            val dx = x - cx
            val dy = y - cy
            (cx + dx * cos(a) - dy * sin(a)) to (cy + dx * sin(a) + dy * cos(a))
        }
    }

    @Test
    fun theBalloonGrowsUpwardAndKeepsItsPartsApart() = forAll { width, hasNext ->
        val rest = YtMiniLayout.rest(spec, width, px)
        val p = YtPopLayout.peek(spec, width, px, hasNext)
        assertTrue("the box grows", p.boxHeight > rest.boxHeight)
        assertTrue("the picture grows", p.player.width > rest.player.width && p.player.height > rest.player.height)
        assertEquals("aspect", rest.player.width / rest.player.height, p.player.width / p.player.height, 0.01f)
        val parts = listOfNotNull(p.player, p.pill, p.title, p.next)
        for (i in parts.indices) {
            for (j in i + 1 until parts.size) {
                assertFalse("${parts[i]} overlaps ${parts[j]}", parts[i].overlaps(parts[j]))
            }
        }
        for (part in parts) {
            assertTrue("$part inside", part.left >= 0f && part.right <= width + 0.01f)
            assertTrue("$part above the bar", part.top >= 0f && part.bottom <= p.boxHeight - spec.overlap * px + 0.01f)
        }
        if (hasNext) assertNotNull(p.next) else assertNull(p.next)
        assertTrue("pill tall enough", p.pill.height >= 40f * px - 0.01f)
        assertTrue("pill wide enough: ${p.pill.width / px} dp", p.pill.width >= 80f * px)
        assertEquals("the pill stands at the picture's foot", p.player.bottom, p.pill.bottom, 0.01f)
        assertTrue("room for two title lines", p.title.height >= 36f * px)
    }

    @Test
    fun theTurnedStickerStaysInTheBalloonAndOffThePicture() {
        for (width in boxes) {
            val p = YtPopLayout.peek(spec, width, px, true)
            val sticker = p.next!!
            for ((x, y) in turned(sticker, YtPopLayout.STICKER_TURN)) {
                assertTrue("corner $x inside", x >= 0f && x <= width)
                assertTrue("corner $y below the top", y >= YtPopLayout.PAD * px - 0.5f)
                assertTrue("corner $y above the picture", y <= p.player.top)
            }
        }
    }

    @Test
    fun thePictureGrowsByTheFactorWhereThereIsRoom() {
        val p = YtPopLayout.peek(spec, 393 * px, px, true)
        assertEquals(YtPopLayout.GROWTH, p.player.height / (spec.thumbHeight * px), 0.02f)
    }

    @Test
    fun theFingerThatHoldsThePictureStaysOnIt() = forAll { width, hasNext ->
        val rest = YtMiniLayout.rest(spec, width, px)
        val p = YtPopLayout.peek(spec, width, px, hasNext)
        val x = rest.player.centerX
        val y = p.boxHeight - (rest.boxHeight - rest.player.centerY)
        assertTrue("still on the picture", p.player.contains(x, y))
        assertEquals(YtMiniTarget.NONE, YtMiniLayout.target(p, x, y, 8f * px))
        assertEquals(YtMiniTarget.PILL, YtMiniLayout.target(p, p.pill.centerX, p.pill.centerY, 0f))
        p.next?.let { assertEquals(YtMiniTarget.NEXT, YtMiniLayout.target(p, it.centerX, it.centerY, 0f)) }
    }

    @Test
    fun theWidthLagsAndSwingsWithinBounds() {
        assertEquals(1f, YtPopMath.squash(0f, 0f), 0f)
        assertEquals(1f, YtPopMath.squash(1f, 1f), 0f)
        // behind the height: narrower; past it: wider; never more than a few percent
        assertTrue(YtPopMath.squash(0.2f, 0.6f) < 1f)
        assertTrue(YtPopMath.squash(1.3f, 1f) > 1f)
        assertTrue(YtPopMath.squash(-5f, 1f) >= 0.965f)
        assertTrue(YtPopMath.squash(5f, 0f) <= 1.035f)
    }

    @Test
    fun theMouthFacesTheCardFromAbove() {
        assertEquals(-90f, YtPopMath.mouthFacing(0f, 0f, 0f, -100f), 0.01f)
        assertEquals(-45f, YtPopMath.mouthFacing(0f, 0f, 100f, -100f), 0.01f)
        // beside or below: kept to the upper half, on the card's side
        assertEquals(-20f, YtPopMath.mouthFacing(0f, 0f, 100f, 0f), 0.01f)
        assertEquals(-20f, YtPopMath.mouthFacing(0f, 0f, 100f, 50f), 0.01f)
        assertEquals(-160f, YtPopMath.mouthFacing(0f, 0f, -100f, 50f), 0.01f)
        assertEquals(-90f, YtPopMath.mouthFacing(5f, 5f, 5f, 5f), 0.01f)
        assertEquals(0f, YtPopMath.mouthFromHover(0f), 0f)
        assertEquals(1f, YtPopMath.mouthFromHover(1f), 0f)
    }

    @Test
    fun theGulpFliesChompsAndSwallows() {
        assertEquals(0f, YtPopMath.gulpFlight(0f), 0f)
        assertEquals(1f, YtPopMath.gulpFlight(0.13f), 0.0001f)
        assertTrue(YtPopMath.inFlight(0.05f))
        assertFalse(YtPopMath.inFlight(0.3f))
        assertFalse(YtPopMath.inFlight(0f))
        // wide open while it flies, shut once it is in
        assertEquals(1f, YtPopMath.gulpMouth(0.05f), 0f)
        assertEquals(0f, YtPopMath.gulpMouth(0.25f), 0f)
        assertEquals(0f, YtPopMath.gulpMouth(0f), 0f)
        // it swells after the chomp, not before, and is at rest at the end
        assertEquals(0f, YtPopMath.gulpSwell(0.1f), 0f)
        assertTrue(YtPopMath.gulpSwell(0.35f) > 0f)
        assertEquals(0f, YtPopMath.gulpSwell(1f), 0f)
        assertTrue(abs(YtPopMath.gulpSwell(0.99f)) < 0.05f)
    }

    @Test
    fun theBlobIsClosedOrHasAMouthTowardsItsFacing() {
        val out = FloatArray(2 * 41)
        val closed = YtPopMath.blobPoints(100f, 100f, 20f, 20f, 2f, 0f, 0f, 0f, 0f, -90f, 40, out)
        assertEquals(80, closed)
        // without wobble an exponent of 2 is a circle
        for (i in 0 until closed step 2) assertEquals(20f, hypot(out[i] - 100f, out[i + 1] - 100f), 0.01f)

        val open = YtPopMath.blobPoints(100f, 100f, 20f, 20f, 2f, 0f, 0f, 0f, 1f, -90f, 40, out)
        assertEquals(82, open)
        // no rim point inside the mouth's wedge (straight up)
        for (i in 0 until open - 2 step 2) {
            val angle = atan2(out[i + 1] - 100f, out[i] - 100f) * 180f / PI.toFloat()
            assertTrue("rim at $angle", abs(angle + 90f) >= YtPopMath.MOUTH / 2f - 0.01f)
        }
        // the wedge's tip lies towards the mouth, short of the centre
        assertEquals(100f, out[open - 2], 0.01f)
        assertEquals(100f - 0.35f * 20f, out[open - 1], 0.01f)
    }

    @Test
    fun theBlobStretchesIntoAStadium() {
        val out = FloatArray(2 * 61)
        // a pill 120 x 40: every point lies on the flat sides or on the round ends
        val count = YtPopMath.blobPoints(0f, 0f, 60f, 20f, 2.2f, 1f, 0f, 0f, 0f, -90f, 60, out)
        for (i in 0 until count step 2) {
            val x = out[i]
            val y = out[i + 1]
            if (abs(x) <= 40f + 0.01f) {
                assertEquals("flat at $x", 20f, abs(y), 0.01f)
            } else {
                val end = if (x > 0f) 40f else -40f
                assertEquals("round at $x", 20f, hypot(x - end, y), 0.01f)
            }
        }
        // upright, the same turned
        val single = FloatArray(2)
        YtPopMath.stadium(20f, 60f, 0f, 1f, single, 0)
        assertEquals(0f, single[0], 0.01f)
        assertEquals(60f, single[1], 0.01f)
        YtPopMath.stadium(20f, 60f, 1f, 0f, single, 0)
        assertEquals(20f, single[0], 0.01f)
        assertEquals(0f, single[1], 0.01f)
    }

    @Test
    fun theStickerSlapsOnAndSettlesTurned() {
        assertEquals(1f, YtPopMath.slapScale(1f), 0f)
        assertTrue(YtPopMath.slapScale(0f) > 1.2f)
        assertEquals(YtPopLayout.STICKER_TURN, YtPopMath.slapTurn(1f, 0f), 0.0001f)
        assertTrue("lifted it turns back a little", YtPopMath.slapTurn(1f, 1f) > YtPopLayout.STICKER_TURN)
        assertEquals(0f, YtPopMath.slapAlpha(0f), 0f)
        assertEquals(1f, YtPopMath.slapAlpha(0.5f), 0f)
        assertEquals(1f, YtPopMath.flashScale(1f), 0.0001f)
        assertTrue(YtPopMath.flashScale(0.2f) > 1f)
        assertEquals(0f, YtPopMath.flashAlpha(1f), 0.0001f)
    }

    @Test
    fun everyMomentFeelsLikePopWithAFallback() {
        for (moment in YtMoment.entries) {
            val texture = YtMiniPopTextures.of(moment)
            assertNotEquals(moment.name, YtFeedback.NO_HAPTICS, texture.fallback)
            assertTrue(moment.name, texture.steps.isNotEmpty())
            assertTrue(moment.name, texture.name.endsWith("_pop"))
            assertFalse(moment.name, texture.composableOn(29))
            val old = YtFeedback.compat(texture.fallback, 26)
            assertTrue("${moment.name}: $old", old in listOf(YtFeedback.CLOCK_TICK, YtFeedback.CONTEXT_CLICK, YtFeedback.LONG_PRESS))
        }
        assertTrue(YtMiniPopTextures.of(YtMoment.PEEK_START).name.contains("peek"))
        assertTrue(YtMiniPopTextures.of(YtMoment.DOCK).name.contains("dock"))
        assertEquals(YtMoment.entries.size, YtMoment.entries.map { YtMiniPopTextures.of(it).name }.toSet().size)
        // the inflate, the gulp and the jelly dock of the spec
        val peek = YtMiniPopTextures.of(YtMoment.PEEK_START).steps.single()
        assertEquals(YtPrimitive.QUICK_RISE, peek.primitive)
        assertEquals(0.6f, peek.scale, 0f)
        val gulp = YtMiniPopTextures.of(YtMoment.ACCEPT).steps
        assertEquals(listOf(YtPrimitive.CLICK, YtPrimitive.SPIN), gulp.map { it.primitive })
        assertEquals(listOf(0.7f, 0.4f), gulp.map { it.scale })
        assertEquals(40, gulp[1].delayMs)
        val dock = YtMiniPopTextures.of(YtMoment.DOCK).steps
        assertEquals(YtMiniTextures.neutral(YtMoment.DOCK).steps, dock.take(2))
        assertEquals(YtPrimitive.TICK, dock.last().primitive)
        assertEquals(0.5f, dock.last().scale, 0f)
        // the spin of the gulp needs Android 12; elsewhere the fallback plays
        assertFalse(YtMiniPopTextures.of(YtMoment.ACCEPT).composableOn(30))
        assertTrue(YtMiniPopTextures.of(YtMoment.ACCEPT).composableOn(31))
        assertTrue(YtMiniPopTextures.of(YtMoment.DOCK).composableOn(30))
    }
}
