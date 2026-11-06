package com.github.libretube.ui.yt.mini

import com.github.libretube.ui.yt.YtFeedback
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YtMiniMovoLayoutTest {
    private val spec = YtMiniSpecs.MOVO
    private val px = 2.75f

    /** Screens 360, 393 and 430 reference dp wide, and a landscape one. */
    private val boxes = listOf(360f, 393f, 430f, 860f).map { (it - spec.start - spec.end) * px }

    private fun forAll(block: (Float, Boolean) -> Unit) {
        for (width in boxes) for (hasNext in listOf(true, false)) block(width, hasNext)
    }

    @Test
    fun theMosaicGrowsUpwardAndKeepsItsTilesApart() = forAll { width, hasNext ->
        val rest = YtMiniLayout.rest(spec, width, px)
        val p = YtMovoLayout.peek(spec, width, px, hasNext)
        val f = p.frame
        assertTrue("the box grows", f.boxHeight > rest.boxHeight)
        assertTrue("the picture grows", f.player.width > rest.player.width && f.player.height > rest.player.height)
        assertEquals("aspect", rest.player.width / rest.player.height, f.player.width / f.player.height, 0.01f)
        val tiles = listOfNotNull(f.player, f.pill, p.count, f.next)
        for (i in tiles.indices) {
            for (j in i + 1 until tiles.size) {
                assertFalse("${tiles[i]} overlaps ${tiles[j]}", tiles[i].overlaps(tiles[j]))
            }
        }
        for (tile in tiles) {
            assertTrue("$tile inside", tile.left >= 0f && tile.right <= width + 0.01f)
            assertTrue("$tile above the bar", tile.top >= 0f && tile.bottom <= f.boxHeight - spec.overlap * px + 0.01f)
        }
        if (hasNext) assertNotNull(f.next) else assertNull(f.next)
        assertTrue("pill tall enough: ${f.pill.height / px} dp", f.pill.height >= 40f * px - 0.01f)
        assertTrue("pill wide enough: ${f.pill.width / px} dp", f.pill.width >= 80f * px)
        // the count tile sits under the play tile, in its column
        assertEquals(f.pill.left, p.count.left, 0.01f)
        assertTrue(p.count.top > f.pill.bottom)
        assertEquals(f.player.bottom, p.count.bottom, 0.01f)
    }

    @Test
    fun thePictureGrowsByTheFactorWhereThereIsRoom() {
        val p = YtMovoLayout.peek(spec, 369 * px, px, true)
        assertEquals(YtMovoLayout.GROWTH, p.frame.player.height / (spec.thumbHeight * px), 0.02f)
    }

    @Test
    fun theFingerThatHoldsThePictureStaysOnIt() = forAll { width, hasNext ->
        val rest = YtMiniLayout.rest(spec, width, px)
        val p = YtMovoLayout.peek(spec, width, px, hasNext).frame
        val x = rest.player.centerX
        val y = p.boxHeight - (rest.boxHeight - rest.player.centerY)
        assertTrue("still on the picture", p.player.contains(x, y))
        assertEquals(YtMiniTarget.NONE, YtMiniLayout.target(p, x, y, 8f * px))
        assertEquals(YtMiniTarget.PILL, YtMiniLayout.target(p, p.pill.centerX, p.pill.centerY, 0f))
        p.next?.let { assertEquals(YtMiniTarget.NEXT, YtMiniLayout.target(p, it.centerX, it.centerY, 0f)) }
    }

    @Test
    fun theFootStaysOnTheBar() {
        val box = YtMiniBox(10f, 20f, 30f, 40f)
        // a box 100 tall seen in one 160 tall: 60 lower
        assertEquals(YtMiniBox(10f, 80f, 30f, 100f), YtMovoLayout.fromFoot(box, 100f, 160f))
    }

    @Test
    fun theTileTiltsUnderTheFinger() {
        val tile = YtMiniBox(100f, 100f, 300f, 200f)
        val most = YtMovoLayout.MAX_TILT
        assertEquals(YtMovoTilt.FLAT, YtMovoMath.tilt(200f, 150f, tile, 10f, most))
        // the right edge gives way, the top tips back
        assertEquals(most, YtMovoMath.tilt(300f, 150f, tile, 10f, most).y, 0.001f)
        assertEquals(-most, YtMovoMath.tilt(100f, 150f, tile, 10f, most).y, 0.001f)
        assertEquals(most, YtMovoMath.tilt(200f, 100f, tile, 10f, most).x, 0.001f)
        assertEquals(-most, YtMovoMath.tilt(200f, 200f, tile, 10f, most).x, 0.001f)
        // beside it within the reach: clamped; beyond: flat
        assertEquals(most, YtMovoMath.tilt(308f, 150f, tile, 10f, most).y, 0.001f)
        assertEquals(YtMovoTilt.FLAT, YtMovoMath.tilt(320f, 150f, tile, 10f, most))
    }

    @Test
    fun theTilesComeOneAfterAnother() {
        assertEquals(0f, YtMovoMath.phase(0.1f, 0.15f), 0f)
        assertEquals(1f, YtMovoMath.phase(1f, 0.15f), 0.0001f)
        assertEquals(1f, YtMovoMath.phase(1f, 0f), 0.0001f)
        // a later tile is behind an earlier one on the way
        assertTrue(YtMovoMath.phase(0.5f, 0.3f) < YtMovoMath.phase(0.5f, 0.05f))
        // the spring's swing past 1 goes on, bounded
        assertTrue(YtMovoMath.phase(1.1f, 0.15f) > 1f)
        assertTrue(YtMovoMath.phase(10f, 0.15f) <= 1.25f)
        var last = 0f
        for (i in 0..115) {
            val v = YtMovoMath.phase(i / 100f, 0.3f)
            assertTrue(v >= last)
            last = v
        }
    }

    @Test
    fun theShuffleSwingsOnceAndSettles() {
        assertEquals(0f, YtMovoMath.shuffle(0f), 0f)
        assertEquals(0f, YtMovoMath.shuffle(1f), 0f)
        assertTrue(YtMovoMath.shuffle(0.2f) > 0f)
        assertTrue(YtMovoMath.shuffle(0.7f) < 0f)
    }

    @Test
    fun everyMomentFeelsLikeMovoWithAFallback() {
        for (moment in YtMoment.entries) {
            val texture = YtMiniMovoTextures.of(moment)
            assertNotEquals(moment.name, YtFeedback.NO_HAPTICS, texture.fallback)
            assertTrue(moment.name, texture.steps.isNotEmpty())
            assertTrue(moment.name, texture.name.endsWith("_movo"))
            assertFalse(moment.name, texture.composableOn(29))
            val old = YtFeedback.compat(texture.fallback, 26)
            assertTrue("${moment.name}: $old", old in listOf(YtFeedback.CLOCK_TICK, YtFeedback.CONTEXT_CLICK, YtFeedback.LONG_PRESS))
        }
        // the emulator checks look for these
        assertTrue(YtMiniMovoTextures.of(YtMoment.PEEK_START).name.contains("peek"))
        assertTrue(YtMiniMovoTextures.of(YtMoment.DOCK).name.contains("dock"))
        assertEquals(YtMoment.entries.size, YtMoment.entries.map { YtMiniMovoTextures.of(it).name }.toSet().size)
        // the double click of docking, 60 ms apart; all of it plays on Android 11
        val dock = YtMiniMovoTextures.of(YtMoment.DOCK)
        assertEquals(listOf(0.6f, 0.8f), dock.steps.map { it.scale })
        assertEquals(60, dock.steps[1].delayMs)
        for (moment in YtMoment.entries) assertTrue(moment.name, YtMiniMovoTextures.of(moment).composableOn(30))
    }
}
