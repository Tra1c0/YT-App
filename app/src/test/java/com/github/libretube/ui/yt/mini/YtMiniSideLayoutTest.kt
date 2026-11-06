package com.github.libretube.ui.yt.mini

import com.github.libretube.ui.yt.YtPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YtMiniSideLayoutTest {
    private val spec = YtMiniSpecs.SIDE
    private val px = 2.75f

    /** Screens 360, 393 and 430 reference dp wide (the bar's dp). */
    private fun forWidths(block: (Float) -> Unit) {
        for (screen in listOf(360f, 393f, 430f)) block((screen - spec.start - spec.end) * px)
    }

    @Test
    fun theCardGrowsUpwardAndKeepsItsPartsApart() = forWidths { width ->
        val rest = YtMiniLayout.rest(spec, width, px)
        for (hasNext in listOf(true, false)) {
            val card = YtMiniSideLayout.card(spec, width, px, hasNext)
            val p = card.geometry
            assertTrue("the box grows", p.boxHeight > rest.boxHeight)
            assertEquals("the picture's height grows by the factor", spec.thumbHeight * YtMiniSideLayout.GROWTH * px, p.player.height, 0.5f)
            assertTrue("across the card", p.player.width > rest.player.width * 3f)
            val parts = listOfNotNull(p.player, card.avatar, card.text, p.pill, p.next)
            for (i in parts.indices) {
                for (j in i + 1 until parts.size) {
                    assertFalse("${parts[i]} overlaps ${parts[j]}", parts[i].overlaps(parts[j]))
                }
            }
            for (part in parts) {
                assertTrue("$part inside the body", part.left >= spec.body * px - 0.01f && part.right <= width - spec.body * px + 0.01f)
                assertTrue("$part above the joint", part.top >= 0f && part.bottom <= card.joint + 0.01f)
            }
            assertEquals("the joint lies on the bar", p.boxHeight - spec.overlap * px, card.joint, 0.01f)
            if (hasNext) assertNotNull(p.next) else assertNull(p.next)
            assertTrue("the disc fits its hit area", p.pill.width >= (YtMiniSideLayout.DISC + 8) * px - 0.01f)
            assertTrue("two lines of title have room", card.text.width >= 120f * px)
        }
    }

    @Test
    fun theFingerThatHeldThePictureActsOnNothing() = forWidths { width ->
        // the box grows from its foot: a point keeps its distance to the bottom; anywhere on the
        // docked picture the finger lies on no part of the card that acts
        val rest = YtMiniLayout.rest(spec, width, px)
        for (hasNext in listOf(true, false)) {
            val p = YtMiniSideLayout.card(spec, width, px, hasNext).geometry
            for (fx in listOf(0f, 0.5f, 1f)) {
                for (fy in listOf(0f, 0.5f, 1f)) {
                    val x = rest.player.left + rest.player.width * fx
                    val y = p.boxHeight - (rest.boxHeight - (rest.player.top + rest.player.height * fy))
                    assertEquals("($fx, $fy)", YtMiniTarget.NONE, YtMiniLayout.target(p, x, y, 8f * px))
                }
            }
        }
    }

    @Test
    fun theFingerFindsTheDiscAndTheReply() = forWidths { width ->
        val p = YtMiniSideLayout.card(spec, width, px, true).geometry
        val next = p.next!!
        assertEquals(YtMiniTarget.NEXT, YtMiniLayout.target(p, next.centerX, next.centerY, 8f * px))
        assertEquals(YtMiniTarget.PILL, YtMiniLayout.target(p, p.pill.centerX, p.pill.centerY, 8f * px))
        // the reply keeps to the card's end, like an answer in a conversation
        assertEquals(width - (spec.body + YtMiniSideLayout.PAD) * px, next.right, 0.01f)
        assertTrue(next.width < p.player.width)
    }

    @Test
    fun avatarAndBadgeSitOnTheBubblesCorners() {
        val width = 369f * px
        val (ax, ay) = YtMiniSideLayout.avatarCenter(spec, px)
        val (bx, by) = YtMiniSideLayout.badgeCenter(spec, width, px)
        assertEquals(width - ax, bx, 0.01f)
        assertEquals(ay, by, 0.01f)
        // the avatar stays clear of the picture's rounded corner (the video would cover it)
        val g = YtMiniLayout.rest(spec, width, px)
        val r = spec.thumbRadius * px
        val cornerX = g.player.left + r
        val cornerY = g.player.top + r
        val distance = kotlin.math.hypot(ax - cornerX, ay - cornerY)
        val avatar = (YtMiniSideLayout.AVATAR / 2f + YtMiniSideLayout.RING) * px
        assertTrue("avatar clear of the picture: $distance", distance >= avatar + r)
    }

    @Test
    fun motionCurvesStartAndEndAtRest() {
        assertEquals(0f, YtMiniSideLayout.rise(0f), 0f)
        assertEquals(1f, YtMiniSideLayout.rise(1f), 0f)
        assertEquals(0f, YtMiniSideLayout.avatarGate(0.8f), 0f)
        assertEquals(1f, YtMiniSideLayout.avatarGate(1f), 0f)
        assertEquals(0f, YtMiniSideLayout.avatarPop(0f), 0f)
        assertEquals(0f, YtMiniSideLayout.avatarPop(YtMiniSideLayout.POP_DELAY), 0f)
        assertEquals(1f, YtMiniSideLayout.avatarPop(YtMiniSideLayout.POP_DELAY + YtMiniSideLayout.POP), 0.001f)
        // a small overshoot on the way
        val peak = (0..100).maxOf { YtMiniSideLayout.avatarPop(YtMiniSideLayout.POP_DELAY + YtMiniSideLayout.POP * it / 100f) }
        assertTrue("overshoot $peak", peak > 1.03f && peak < 1.2f)
        assertEquals(0f, YtMiniSideLayout.heartbeat(0f), 0f)
        assertEquals(0f, YtMiniSideLayout.heartbeat(YtMiniSideLayout.HEARTBEAT.toFloat()), 0f)
        // the second beat is the stronger one, as the haptic
        assertTrue(YtMiniSideLayout.heartbeat(190f) > YtMiniSideLayout.heartbeat(55f))
        assertEquals(0f, YtMiniSideLayout.badgeScale(0f), 0f)
        assertEquals(0f, YtMiniSideLayout.badgeScale(1f), 0f)
        assertEquals(1f, YtMiniSideLayout.badgeScale(YtMiniSideLayout.DROP_STILL), 0f)
        assertEquals(1f, YtMiniSideLayout.label(YtMiniSideLayout.DROP_STILL), 0f)
        // without motion the still moment shows no burst
        assertEquals(1f, YtMiniSideLayout.burst(YtMiniSideLayout.DROP_STILL), 0f)
    }

    @Test
    fun theHeartbeatIsATickThenAClick() {
        for (moment in listOf(YtMoment.DOCK, YtMoment.ACCEPT)) {
            val texture = YtMiniSideTextures.of(moment)
            assertEquals(listOf(YtPrimitive.TICK, YtPrimitive.CLICK), texture.steps.map { it.primitive })
            assertEquals(120, texture.steps[1].delayMs)
            assertEquals(0.5f, texture.steps[0].scale, 0f)
            assertEquals(0.8f, texture.steps[1].scale, 0f)
        }
        val peek = YtMiniSideTextures.of(YtMoment.PEEK_START)
        assertEquals(YtPrimitive.SLOW_RISE, peek.steps.single().primitive)
        assertEquals(0.3f, peek.steps.single().scale, 0f)
        for (moment in YtMoment.entries) {
            val texture = YtMiniSideTextures.of(moment)
            assertTrue(texture.name, texture.name.startsWith("mini_") && texture.name.endsWith("_side"))
            // Android 11 has no LOW_TICK, THUD or SPIN: the Side textures play there too
            assertTrue(texture.name, texture.composableOn(30))
        }
    }
}
