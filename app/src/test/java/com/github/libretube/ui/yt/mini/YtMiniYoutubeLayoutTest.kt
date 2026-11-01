package com.github.libretube.ui.yt.mini

import com.github.libretube.ui.yt.YtFeedback
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YtMiniYoutubeLayoutTest {
    private val spec = YtMiniSpecs.YOUTUBE

    @Test
    fun thePictureGrowsTwoPointTwoTimesIn16By9() {
        val d = 2.75f
        val width = (393 - 24) * d
        val p = YtMiniYoutubeLayout.peek(spec, width, d, hasNext = true)
        assertEquals(spec.thumbHeight * 2.2f * d, p.player.height, 0.5f)
        assertEquals(16f / 9f, p.player.width / p.player.height, 0.01f)
        assertEquals(YtMiniYoutubeLayout.PAD * d, p.player.left, 0.01f)
    }

    @Test
    fun thePartsStayInTheCardAndDoNotOverlap() {
        for (screen in listOf(320, 360, 393, 430, 600)) {
            for (hasNext in listOf(true, false)) {
                val d = 2.5f
                val width = (screen - 24) * d
                val p = YtMiniYoutubeLayout.peek(spec, width, d, hasNext)
                val all = listOfNotNull(p.player, p.title, p.pill, p.next)
                for (box in all) {
                    assertTrue("$screen $box", box.left >= 0f && box.right <= width + 0.01f)
                    assertTrue("$screen $box", box.top >= 0f && box.bottom <= p.boxHeight - spec.overlap * d)
                }
                assertFalse(p.player.overlaps(p.pill))
                assertFalse(p.player.overlaps(p.title))
                assertFalse(p.title.overlaps(p.pill))
                p.next?.let {
                    assertFalse(it.overlaps(p.player))
                    assertFalse(it.overlaps(p.pill))
                    // under the picture, across the card
                    assertTrue(it.top >= p.player.bottom)
                    assertTrue(it.width > p.player.width)
                }
                assertEquals(hasNext, p.next != null)
                assertTrue(p.boxHeight > (spec.height + spec.overlap) * d)
            }
        }
    }

    @Test
    fun theNextRowWinsWhereTheFingerLetsGoOnIt() {
        val d = 3f
        val p = YtMiniYoutubeLayout.peek(spec, 369 * d, d, hasNext = true)
        val row = assertNotNullBox(p.next)
        assertEquals(YtMiniTarget.NEXT, YtMiniLayout.target(p, row.centerX, row.centerY, 8f * d))
        assertEquals(YtMiniTarget.PILL, YtMiniLayout.target(p, p.pill.centerX, p.pill.centerY, 8f * d))
        assertEquals(YtMiniTarget.NONE, YtMiniLayout.target(p, p.player.centerX, p.player.top + 4f * d, 8f * d))
        assertNull(YtMiniYoutubeLayout.peek(spec, 369 * d, d, hasNext = false).next)
    }

    private fun assertNotNullBox(box: YtMiniBox?): YtMiniBox {
        assertNotNull(box)
        return box!!
    }

    @Test
    fun theCurvesStartAndEndAtRest() {
        assertEquals(0f, YtMiniYoutubeMath.corner(0f, 24f), 0f)
        assertEquals(24f, YtMiniYoutubeMath.corner(1f, 24f), 0f)
        assertEquals(0f, YtMiniYoutubeMath.needle(0f), 0f)
        assertEquals(1f, YtMiniYoutubeMath.needle(1f), 0f)
        assertEquals(0f, YtMiniYoutubeMath.needleLight(1f), 0f)
        assertEquals(1f, YtMiniYoutubeMath.restText(1f, 0f), 0f)
        assertEquals(0f, YtMiniYoutubeMath.restText(1f, 0.5f), 0f)
        assertEquals(0f, YtMiniYoutubeMath.rowShown(0.4f), 0f)
        assertEquals(1f, YtMiniYoutubeMath.rowShown(1f), 0f)
        // the row comes after the column
        assertTrue(YtMiniYoutubeMath.columnShown(0.6f) > YtMiniYoutubeMath.rowShown(0.6f))
        assertEquals(0f, YtMiniYoutubeMath.seamShown(0.85f), 0f)
    }

    @Test
    fun theDropPillRisesWidensAndSinksBack() {
        val start = YtMiniYoutubeMath.dropPill(0f)
        assertEquals(0f, start.rise, 0.001f)
        assertTrue(start.shown)
        // it pops a little past its place and settles
        var most = 0f
        var t = 0f
        while (t <= YtMiniYoutubeMath.RISE_END) {
            most = maxOf(most, YtMiniYoutubeMath.dropPill(t).rise)
            t += 5f
        }
        assertTrue(most > 1f && most < 1.2f)
        val held = YtMiniYoutubeMath.dropPill(1000f)
        assertEquals(1f, held.rise, 0f)
        assertEquals(1f, held.widen, 0f)
        assertEquals(0f, YtMiniYoutubeMath.dropPill(400f).widen, 0f)
        assertEquals(0f, YtMiniYoutubeMath.dropPill(YtMiniYoutubeMath.DROP_END).rise, 0.001f)
        assertFalse(YtMiniYoutubeMath.dropPill(YtMiniYoutubeMath.DROP_END + 1f).shown)
        assertFalse(YtMiniYoutubeMath.dropPill(-1f).shown)
    }

    @Test
    fun everyMomentFeelsItsOwnAndFallsBack() {
        val names = YtMoment.entries.map { YtMiniYoutubeTextures.of(it).name }
        assertEquals(YtMoment.entries.size, names.toSet().size)
        for (moment in YtMoment.entries) {
            val texture = YtMiniYoutubeTextures.of(moment)
            assertNotEquals(YtFeedback.NO_HAPTICS, texture.fallback)
            assertTrue(texture.steps.isNotEmpty())
            assertFalse(texture.composableOn(29))
            // only primitives Android 11 has: it plays them all
            assertTrue(moment.name, texture.composableOn(30))
        }
        assertTrue(YtMiniYoutubeTextures.of(YtMoment.DOCK).name.contains("mini_dock"))
        assertTrue(YtMiniYoutubeTextures.of(YtMoment.PEEK_START).name.contains("mini_peek"))
        // the brief: dock CLICK 0.7, peek TICK 0.4, accept CLICK 0.5 + TICK 0.3 at +50 ms
        val dock = YtMiniYoutubeTextures.of(YtMoment.DOCK).steps.single()
        assertEquals(0.7f, dock.scale, 0f)
        val accept = YtMiniYoutubeTextures.of(YtMoment.ACCEPT).steps
        assertEquals(50, accept[1].delayMs)
    }
}
