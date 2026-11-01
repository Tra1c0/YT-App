package com.github.libretube.ui.yt.feed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YtDragRulesTest {
    private val mini = YtBox(100f, 1000f, 500f, 1200f)

    @Test
    fun distanceIsZeroInsideAndEuclideanOutside() {
        assertEquals(0f, YtDragRules.distance(300f, 1100f, mini), 0.001f)
        assertEquals(50f, YtDragRules.distance(300f, 950f, mini), 0.001f)
        assertEquals(5f, YtDragRules.distance(97f, 996f, mini), 0.001f)
    }

    @Test
    fun noMiniMeansNoPullAndNoHover() {
        val at = YtDragRules.ghost(10f, 20f, null, 24f, 80f)
        assertEquals(YtGhostAt(10f, 20f, 0f, false), at)
    }

    @Test
    fun hoverStartsInsideTheInflatedBounds() {
        // 20 px above the mini player: inside the 24 px margin
        val near = YtDragRules.ghost(300f, 980f, mini, 24f, 80f)
        assertTrue(near.hover)
        assertEquals(YtDragRules.MAX_PULL, near.pull, 0.001f)
        // pulled toward the centre (300, 1100)
        assertTrue(near.y > 980f)
        val off = YtDragRules.ghost(300f, 970f, mini, 24f, 80f)
        assertFalse(off.hover)
    }

    @Test
    fun theMagnetGrowsSmoothlyTowardTheTarget() {
        val far = YtDragRules.ghost(300f, 1000f - 24f - 90f, mini, 24f, 80f)
        assertEquals(0f, far.pull, 0.001f)
        val edge = YtDragRules.ghost(300f, 1000f - 24f - 79f, mini, 24f, 80f)
        val close = YtDragRules.ghost(300f, 1000f - 24f - 10f, mini, 24f, 80f)
        assertTrue(edge.pull in 0f..0.01f)
        assertTrue(close.pull > edge.pull)
        assertTrue(close.pull < YtDragRules.MAX_PULL)
        assertFalse(close.hover)
    }

    @Test
    fun cardAtPicksTheContainingCardNearestItsCentre() {
        val cards = mapOf(
            "a" to YtBox(0f, 0f, 100f, 100f),
            "b" to YtBox(0f, 80f, 100f, 300f),
        )
        assertEquals("a", YtDragRules.cardAt(50f, 50f, cards))
        assertEquals("b", YtDragRules.cardAt(50f, 200f, cards))
        // in the overlap, b's centre (190) is farther than a's (50)
        assertEquals("a", YtDragRules.cardAt(50f, 90f, cards))
        assertNull(YtDragRules.cardAt(150f, 50f, cards))
    }

    @Test
    fun searchNameDropsTopicAndBlank() {
        assertEquals("Daft Punk", YtDragRules.searchName("Daft Punk - Topic"))
        assertEquals("Kurzgesagt", YtDragRules.searchName("  Kurzgesagt "))
        assertEquals("Topic", YtDragRules.searchName("Topic"))
        assertNull(YtDragRules.searchName(" - Topic"))
        assertNull(YtDragRules.searchName("   "))
        assertNull(YtDragRules.searchName(null))
    }

    @Test
    fun theLoupeStaysInsideThePictureUntilTheFingerLeavesIt() {
        assertEquals(0f to 50f, YtDragRules.loupe(-30f, 50f, 300f, 150f))
        assertEquals(300f to 150f, YtDragRules.loupe(320f, 200f, 300f, 150f))
        assertFalse(YtDragRules.leftPicture(-20f, 50f, 300f, 150f, 24f))
        assertTrue(YtDragRules.leftPicture(-25f, 50f, 300f, 150f, 24f))
        assertTrue(YtDragRules.leftPicture(100f, 175f, 300f, 150f, 24f))
    }
}
