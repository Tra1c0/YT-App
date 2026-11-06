package com.github.libretube.ui.yt.shorts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YtShortsMathTest {
    @Test
    fun `the setting reads off, the choices and falls back to the default`() {
        assertEquals(0, YtShortsAutoscroll.parse("off"))
        assertEquals(3, YtShortsAutoscroll.parse("3"))
        assertEquals(5, YtShortsAutoscroll.parse("5"))
        assertEquals(YtShortsAutoscroll.DEFAULT, YtShortsAutoscroll.parse(null))
        assertEquals(YtShortsAutoscroll.DEFAULT, YtShortsAutoscroll.parse("4"))
        assertEquals(YtShortsAutoscroll.DEFAULT, YtShortsAutoscroll.parse("x"))
        for (n in YtShortsAutoscroll.CHOICES) assertEquals(n, YtShortsAutoscroll.parse(YtShortsAutoscroll.format(n)))
    }

    private fun decide(plays: Int, completed: Int, pos: Long, dur: Long = 30_000L, ended: Boolean = false, blocked: Boolean = false, next: Boolean = true) =
        YtShortsAutoscroll.decide(plays, completed, pos, dur, ended, blocked, next)

    @Test
    fun `off, the last short or a finger down always loop`() {
        assertEquals(YtShortsAutoscroll.Decision.LOOP, decide(0, 9, 29_900L, ended = true))
        assertEquals(YtShortsAutoscroll.Decision.LOOP, decide(1, 0, 29_900L, next = false))
        assertEquals(YtShortsAutoscroll.Decision.LOOP, decide(1, 0, 29_900L, blocked = true))
        assertEquals(YtShortsAutoscroll.Decision.LOOP, decide(1, 0, 29_900L, ended = true, blocked = true))
    }

    @Test
    fun `with one play the first play counts down and scrolls at its end`() {
        val early = decide(1, 0, 10_000L)
        assertFalse(early.loop)
        assertNull(early.countdownMs)
        val late = decide(1, 0, 28_000L)
        assertEquals(2_000L, late.countdownMs)
        assertTrue(decide(1, 0, 30_000L, ended = true).scroll)
    }

    @Test
    fun `with three plays the first two loop`() {
        assertEquals(YtShortsAutoscroll.Decision.LOOP, decide(3, 0, 29_000L))
        assertEquals(YtShortsAutoscroll.Decision.LOOP, decide(3, 1, 29_000L))
        assertEquals(1_000L, decide(3, 2, 29_000L).countdownMs)
        // a finger held over the end let it loop once more: it still scrolls at the next end
        assertTrue(decide(3, 3, 30_000L, ended = true).scroll)
    }

    @Test
    fun `a very short short counts down for half its length`() {
        assertNull(decide(1, 0, 1_000L, dur = 4_000L).countdownMs)
        assertEquals(2_000L, decide(1, 0, 2_000L, dur = 4_000L).countdownMs)
        assertEquals(listOf(3, 2, 1, 1), listOf(3_000L, 1_500L, 999L, 0L).map(YtShortsAutoscroll::seconds))
    }

    @Test
    fun `the zone lies above the navigation bar and includes it`() {
        val h = 2000f
        assertTrue(YtShortsScrub.inZone(1990f, h, 40f, 120f))
        assertTrue(YtShortsScrub.inZone(1840f, h, 40f, 120f))
        assertFalse(YtShortsScrub.inZone(1839f, h, 40f, 120f))
        assertFalse(YtShortsScrub.inZone(10f, 0f, 0f, 120f))
    }

    @Test
    fun `the bar maps the finger to exact positions and back`() {
        assertEquals(0f, YtShortsScrub.fraction(-5f, 1000f, 20f), 0f)
        assertEquals(1f, YtShortsScrub.fraction(2000f, 1000f, 20f), 0f)
        assertEquals(0.5f, YtShortsScrub.fraction(500f, 1000f, 20f), 1e-6f)
        assertEquals(30_000L, YtShortsScrub.position(0.5f, 60_000L))
        assertEquals(0L, YtShortsScrub.position(0.5f, -1L))
        assertEquals(500f, YtShortsScrub.x(30_000L, 60_000L, 1000f, 20f), 1e-3f)
        assertEquals(0f, YtShortsScrub.fraction(10f, 30f, 20f), 0f)
    }

    @Test
    fun `a tick for each new second`() {
        assertFalse(YtShortsScrub.crossedSecond(1_000L, 1_999L))
        assertTrue(YtShortsScrub.crossedSecond(1_999L, 2_000L))
        assertTrue(YtShortsScrub.crossedSecond(5_100L, 4_900L))
    }

    @Test
    fun `time labels`() {
        assertEquals("0:07", YtShortsScrub.label(7_400L))
        assertEquals("1:05", YtShortsScrub.label(65_000L))
        assertEquals("1:02:03", YtShortsScrub.label(3_723_000L))
        assertEquals("0:00", YtShortsScrub.label(-5L))
    }

    @Test
    fun `the list grows near its end without doubles`() {
        assertFalse(YtShortsList.shouldLoadMore(0, 10, false, false))
        assertTrue(YtShortsList.shouldLoadMore(7, 10, false, false))
        assertFalse(YtShortsList.shouldLoadMore(9, 10, true, false))
        assertFalse(YtShortsList.shouldLoadMore(9, 10, false, true))
        val merged = YtShortsList.append(listOf("a", "b"), listOf("b", "c", "c", "d"), { it })
        assertEquals(listOf("a", "b", "c", "d"), merged)
        assertEquals(2, YtShortsList.startPage(listOf("a", "b", "c"), "c", { it }))
        assertEquals(0, YtShortsList.startPage(listOf("a", "b", "c"), "z", { it }))
    }
}
