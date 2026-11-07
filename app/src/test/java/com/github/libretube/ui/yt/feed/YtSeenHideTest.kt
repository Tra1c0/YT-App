package com.github.libretube.ui.yt.feed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class YtSeenHideTest {
    private val tenMinutes = 600L

    @Test
    fun `ninety percent counts as seen`() {
        assertTrue(YtSeenHide.finished(540_000, tenMinutes))
        assertTrue(YtSeenHide.finished(600_000, tenMinutes))
        assertFalse(YtSeenHide.finished(530_000, tenMinutes))
    }

    @Test
    fun `the last thirty seconds count as seen`() {
        // a short video stopped in the end card: 86 %, but 28 s left
        assertTrue(YtSeenHide.finished(172_000, 200))
        // 82.5 % and 35 s left: started, not seen
        assertFalse(YtSeenHide.finished(165_000, 200))
    }

    @Test
    fun `a short clip needs at least half of it near the end`() {
        assertFalse(YtSeenHide.finished(2_000, 40))
        assertTrue(YtSeenHide.finished(25_000, 40))
    }

    @Test
    fun `unwatched, started and unknown lengths stay`() {
        assertFalse(YtSeenHide.finished(null, tenMinutes))
        assertFalse(YtSeenHide.finished(0, tenMinutes))
        assertFalse(YtSeenHide.finished(120_000, tenMinutes))
        assertFalse(YtSeenHide.finished(900_000, null))
        assertFalse(YtSeenHide.finished(900_000, 0))
        assertFalse(YtSeenHide.finished(900_000, -1))
    }

    @Test
    fun `visible keeps the list when nothing goes`() {
        val list = listOf("a" to 10_000L, "b" to 20_000L)
        assertSame(list, YtSeenHide.visible(list, position = { it.second }, duration = { tenMinutes }))
        val mixed = listOf("a" to 590_000L, "b" to 20_000L, "c" to null)
        assertEquals(listOf("b", "c"), YtSeenHide.visible(mixed, position = { it.second }, duration = { tenMinutes }).map { it.first })
    }
}
