package com.github.libretube.ui.yt.builder

import com.github.libretube.ui.yt.builder.YtDocOps.componentOf
import com.github.libretube.ui.yt.builder.YtDocOps.withComponent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class YtDocOpsTest {
    private val card = testDoc().components.card

    @Test
    fun `an update shares the untouched branches`() {
        val next = YtDocOps.update(card, "title") { it.copy(name = "T") }
        assertEquals("T", YtDocOps.find(next, "title")!!.name)
        assertSame(YtDocOps.find(card, "thumbFrame"), YtDocOps.find(next, "thumbFrame"))
        assertSame(card, YtDocOps.update(card, "missing") { it.copy(name = "x") })
    }

    @Test
    fun `parents and paths`() {
        assertEquals("texts", YtDocOps.parentOf(card, "title")!!.id)
        assertEquals(listOf("card", "infoRow", "texts", "title"), YtDocOps.pathTo(card, "title"))
        assertTrue(YtDocOps.pathTo(card, "nope").isEmpty())
    }

    @Test
    fun `duplicate gets fresh ids and sits after the original`() {
        val (next, id) = YtDocOps.duplicate(card, "badge")
        val ids = YtDocOps.all(next).map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        val frame = YtDocOps.find(next, "thumbFrame")!!
        val at = frame.children.indexOfFirst { it.id == "badge" }
        assertEquals(id, frame.children[at + 1].id)
        // in a free container the copy is a little offset
        assertEquals(16f, frame.children[at + 1].x)
        assertNotEquals("duration", frame.children[at + 1].children.single().id)
    }

    @Test
    fun `remove and reorder`() {
        val gone = YtDocOps.remove(card, "avatar")
        assertEquals(null, YtDocOps.find(gone, "avatar"))
        val front = YtDocOps.reorder(card, "thumb", 1)
        assertEquals(listOf("progress", "thumb", "badge"), YtDocOps.find(front, "thumbFrame")!!.children.map { it.id })
        assertSame(card, YtDocOps.reorder(card, "badge", 1))
    }

    @Test
    fun `move between containers, never into itself`() {
        val moved = YtDocOps.moveTo(card, "badge", "infoRow", 0)
        assertEquals("badge", YtDocOps.find(moved, "infoRow")!!.children.first().id)
        assertEquals(null, YtDocOps.find(moved, "thumbFrame")!!.children.firstOrNull { it.id == "badge" })
        assertSame(card, YtDocOps.moveTo(card, "infoRow", "texts", 0))
        assertSame(card, YtDocOps.moveTo(card, "badge", "title", 0))
    }

    @Test
    fun `components of a document`() {
        val doc = testDoc()
        assertEquals(YtComponentKind.NAV_TAB, doc.componentOf("navLabel"))
        val next = doc.withComponent(YtComponentKind.CHIP) { YtDocOps.update(it, "chipLabel") { n -> n.copy(name = "L") } }
        assertEquals("L", YtDocOps.find(next.components.chip, "chipLabel")!!.name)
        assertSame(doc.components.card, next.components.card)
    }

    @Test
    fun `tokens resolve with fallbacks and loops end`() {
        val doc = testDoc().let { d ->
            d.copy(tokens = d.tokens.copy(colors = d.tokens.colors + ("loopA" to YtColorToken(YtPaint.token("loopB"))) + ("loopB" to YtColorToken(YtPaint.token("loopA")))))
        }
        val t = YtTokensResolver(doc, night = false)
        assertEquals(0xFFFF334B.toInt(), t.argb(YtRole.ACCENT))
        assertEquals(YtThemeSeeds.fallbackRole("nothing"), t.argb("nothing"))
        t.argb("loopA")
        val g = t.resolve(YtPaint(type = YtPaintType.LINEAR, stops = listOf(YtStop(0f, 0, YtRole.ACCENT), YtStop(1f, 5))))
        assertEquals(0xFFFF334B.toInt(), g.stops.first().color)
    }

    @Test
    fun `a both theme uses its night colours at night only`() {
        val doc = testDoc().let { d ->
            d.copy(mode = YtThemeMode.BOTH, tokens = d.tokens.copy(colors = d.tokens.colors + (YtRole.BACKGROUND to YtColorToken(YtPaint.solid(1), YtPaint.solid(2)))))
        }
        assertEquals(1, YtTokensResolver(doc, false).argb(YtRole.BACKGROUND))
        assertEquals(2, YtTokensResolver(doc, true).argb(YtRole.BACKGROUND))
        assertEquals(1, YtTokensResolver(doc.copy(mode = YtThemeMode.DARK), true).argb(YtRole.BACKGROUND))
    }
}
