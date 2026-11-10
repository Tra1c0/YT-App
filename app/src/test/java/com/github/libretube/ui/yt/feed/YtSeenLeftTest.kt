package com.github.libretube.ui.yt.feed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class YtSeenLeftTest {
    @Test
    fun leftRoundsUpAndPicksTheUnit() {
        assertEquals(YtSeenMark.Left.Seconds(30), YtSeenMark.left(0.5f, 60))
        assertEquals(YtSeenMark.Left.Minutes(12), YtSeenMark.left(0f, 11 * 60 + 5))
        assertEquals(YtSeenMark.Left.Hours(1, 5), YtSeenMark.left(0f, 65 * 60))
        assertNull(YtSeenMark.left(1f, 600))
        assertNull(YtSeenMark.left(0.3f, null))
    }
}
