package com.github.libretube.ui.yt.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YtPlayerTouchTest {
    @Test
    fun `a normal hold starts and ends once`() {
        val t = YtPlayerTouch()
        assertFalse(t.down())
        assertTrue(t.longPressFired())
        assertTrue(t.longPress)
        assertTrue(t.end())
        assertFalse(t.longPress)
        assertFalse(t.active)
        // a second end has nothing left to end
        assertFalse(t.end())
    }

    @Test
    fun `a long press after the gesture was taken away starts nothing`() {
        // the minimize drag: the CANCEL was dropped, abort() ended the gesture, then the
        // detector's pending long press fired anyway
        val t = YtPlayerTouch()
        t.down()
        assertFalse(t.end())
        assertFalse(t.longPressFired())
        assertFalse(t.longPress)
        assertFalse(t.active)
    }

    @Test
    fun `a hold that never heard its end is ended by the next touch`() {
        val t = YtPlayerTouch()
        t.down()
        t.longPressFired()
        // no UP, no CANCEL: the next DOWN reports the stale hold once
        assertTrue(t.down())
        assertFalse(t.longPress)
        // and the next move of the new gesture is no hold
        assertTrue(t.fingerDown)
        assertFalse(t.end())
    }

    @Test
    fun `a second finger ends the hold but not the gesture`() {
        val t = YtPlayerTouch()
        t.down()
        t.longPressFired()
        assertTrue(t.secondFinger())
        assertFalse(t.longPress)
        assertTrue(t.fingerDown)
        assertFalse(t.secondFinger())
        assertFalse(t.end())
    }

    @Test
    fun `a touch the view did not take leaves nothing behind`() {
        // landscape top strip and locked controls: DOWN, then nothing for this view
        val t = YtPlayerTouch()
        t.down()
        t.end()
        assertFalse(t.active)
        assertFalse(t.longPressFired())
    }
}
