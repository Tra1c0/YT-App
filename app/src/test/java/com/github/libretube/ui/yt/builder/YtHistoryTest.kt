package com.github.libretube.ui.yt.builder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class YtHistoryTest {
    private fun rename(to: String, key: String? = null) = YtCommand("rename", key) { it.copy(name = to) }

    @Test
    fun `undo and redo walk the steps`() {
        val h = YtHistory(testDoc())
        h.run(rename("a"), 0)
        h.run(rename("b"), 5000)
        assertEquals("b", h.current.name)
        assertEquals("a", h.undo()!!.name)
        assertEquals("Test", h.undo()!!.name)
        assertNull(h.undo())
        assertEquals("a", h.redo()!!.name)
        assertEquals("b", h.redo()!!.name)
        assertNull(h.redo())
    }

    @Test
    fun `steps with the same key close in time merge`() {
        val h = YtHistory(testDoc())
        for (i in 0 until 30) h.run(rename("n$i", "slider"), i * 50L)
        assertEquals(1, h.size)
        assertEquals("Test", h.undo()!!.name)
        assertEquals("n29", h.redo()!!.name)
    }

    @Test
    fun `merging stops after a pause, a seal or another key`() {
        val h = YtHistory(testDoc(), coalesceMs = 1000)
        h.run(rename("a", "k"), 0)
        h.run(rename("b", "k"), 5000)
        assertEquals(2, h.size)
        h.seal()
        h.run(rename("c", "k"), 5100)
        assertEquals(3, h.size)
        h.run(rename("d", "other"), 5200)
        assertEquals(4, h.size)
    }

    @Test
    fun `a step that changes nothing is not recorded`() {
        val h = YtHistory(testDoc())
        val same = h.run(rename("Test"), 0)
        assertSame(h.current, same)
        assertFalse(h.canUndo)
    }

    @Test
    fun `merged back to the start removes the step`() {
        val h = YtHistory(testDoc())
        h.run(rename("x", "k"), 0)
        h.run(rename("Test", "k"), 10)
        assertFalse(h.canUndo)
    }

    @Test
    fun `a new step drops the redo list`() {
        val h = YtHistory(testDoc())
        h.run(rename("a"), 0)
        h.undo()
        assertTrue(h.canRedo)
        h.run(rename("z"), 10)
        assertFalse(h.canRedo)
    }

    @Test
    fun `the history is bounded`() {
        val h = YtHistory(testDoc(), limit = 5)
        for (i in 0 until 20) h.run(rename("n$i"), i * 10_000L)
        assertEquals(5, h.size)
    }

    @Test
    fun `a drag is one step`() {
        val h = YtHistory(testDoc())
        val move = YtCommand("move", null) { d -> d.copy(components = d.components.copy(card = d.components.card.copy(x = 40f))) }
        h.run(move, 0)
        assertEquals(1, h.size)
        assertEquals(0f, h.undo()!!.components.card.x)
    }
}
