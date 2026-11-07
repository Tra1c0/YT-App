package com.github.libretube.ui.yt.feed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YtSeenMarkTest {
    @Test
    fun unwatchedAndAccidentalTapsHaveNoMark() {
        assertNull(YtSeenMark.of(null, 600))
        assertNull(YtSeenMark.of(4_000, 600))
    }

    @Test
    fun aStartedVideoGetsABarOfItsShare() {
        val mark = YtSeenMark.of(150_000, 600)!!
        assertTrue(!mark.watched)
        assertEquals(0.25f, mark.progress!!, 1e-4f)
        // a very small share is still visible
        assertEquals(0.04f, YtSeenMark.of(11_000, 7_200)!!.progress!!, 1e-4f)
        // length unknown: marked, but no bar
        assertNull(YtSeenMark.of(60_000, null)!!.progress)
    }

    @Test
    fun aFinishedVideoIsDimmed() {
        val mark = YtSeenMark.of(580_000, 600)!!
        assertTrue(mark.watched)
        assertTrue(YtSeenMark.WATCHED_BRIGHTNESS in 0.6f..0.7f)
    }
}
