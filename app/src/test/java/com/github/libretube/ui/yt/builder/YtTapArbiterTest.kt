package com.github.libretube.ui.yt.builder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YtTapArbiterTest {
    private val slop = 10f

    @Test
    fun `without a double-tap trigger a tap plays at once, also twice in a row`() {
        val a = YtTapArbiter(waitForDouble = false, doubleMs = 300, slop = slop)
        assertNull(a.down(0, 5f, 5f))
        assertEquals(YtTriggerKind.TAP, a.tap(80, 5f, 5f))
        assertNull(a.down(150, 5f, 5f))
        assertEquals(YtTriggerKind.TAP, a.tap(220, 5f, 5f))
        assertFalse(a.pending)
        assertNull(a.waitMs(220))
    }

    @Test
    fun `with one two quick taps are one double tap and no tap`() {
        val a = YtTapArbiter(waitForDouble = true, doubleMs = 300, slop = slop)
        assertNull(a.down(0, 5f, 5f))
        assertNull(a.tap(80, 5f, 5f))
        assertTrue(a.pending)
        assertEquals(301L, a.waitMs(80))
        assertNull(a.down(200, 8f, 6f))
        // the second touch is down: the first tap is not let go by time
        assertNull(a.expire(1000))
        assertEquals(YtTriggerKind.DOUBLE_TAP, a.tap(260, 8f, 6f))
        assertFalse(a.pending)
        assertNull(a.expire(2000))
    }

    @Test
    fun `a single tap is let go when the window ends`() {
        val a = YtTapArbiter(waitForDouble = true, doubleMs = 300, slop = slop)
        a.down(0, 0f, 0f)
        assertNull(a.tap(100, 0f, 0f))
        assertNull(a.expire(399))
        assertEquals(YtTriggerKind.TAP, a.expire(401))
        assertNull(a.expire(500))
    }

    @Test
    fun `a late or far second touch lets the first tap go at once`() {
        val late = YtTapArbiter(waitForDouble = true, doubleMs = 300, slop = slop)
        late.down(0, 0f, 0f)
        late.tap(100, 0f, 0f)
        assertEquals(YtTriggerKind.TAP, late.down(450, 0f, 0f))
        // the new touch starts fresh: it waits on its own
        assertNull(late.tap(500, 0f, 0f))
        assertTrue(late.pending)

        val far = YtTapArbiter(waitForDouble = true, doubleMs = 300, slop = slop)
        far.down(0, 0f, 0f)
        far.tap(100, 0f, 0f)
        assertEquals(YtTriggerKind.TAP, far.down(200, 100f, 0f))
    }

    @Test
    fun `a second touch that drags or holds lets the first tap go`() {
        val a = YtTapArbiter(waitForDouble = true, doubleMs = 300, slop = slop)
        a.down(0, 0f, 0f)
        a.tap(100, 0f, 0f)
        assertNull(a.down(200, 0f, 0f))
        assertEquals(YtTriggerKind.TAP, a.other())
        assertFalse(a.pending)
        // a drag on its own decides nothing
        assertNull(a.other())
    }

    @Test
    fun `switching the double tap off lets a held tap go`() {
        val a = YtTapArbiter(waitForDouble = true, doubleMs = 300, slop = slop)
        a.down(0, 0f, 0f)
        a.tap(100, 0f, 0f)
        a.waitForDouble = false
        assertEquals(YtTriggerKind.TAP, a.expire(110))
    }
}
