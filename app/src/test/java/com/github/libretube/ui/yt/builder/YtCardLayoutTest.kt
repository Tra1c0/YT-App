package com.github.libretube.ui.yt.builder

import com.github.libretube.ui.yt.builder.YtLayoutEngine.Box
import com.github.libretube.ui.yt.builder.YtLayoutEngine.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The seeded card must take the height of its whole content (thumbnail + info row). */
class YtCardLayoutTest {
    private fun leaf(lines: Int, d: Float, scale: Float) = { node: YtNode, b: Box ->
        val h = if (node.slot == YtSlot.TITLE || node.slot == YtSlot.META) {
            val l = if (node.slot == YtSlot.TITLE) lines else 1
            l * 18f * d * scale
        } else {
            0f
        }
        val w = if (b.maxW == YtLayoutEngine.INF) 50f else b.maxW
        Size(w.coerceIn(b.minW, b.maxW), h.coerceIn(b.minH, b.maxH))
    }

    private fun check(widthDp: Float, d: Float, lines: Int, scale: Float) {
        val seeded = testDoc().components.card
        val rt = (YtThemeCodec.decode(YtThemeCodec.encode(testDoc())) as YtThemeCodec.Read.Ok).doc.components.card
        assertEquals(seeded, rt)
        val card = rt
        val w = widthDp * d
        val box = YtLayoutEngine.boxFor(card, w, YtLayoutEngine.INF, d)
        val (p, _) = YtLayoutEngine.tree(card, box, d, leaf(lines, d, scale))
        val root = p["card"]!!
        val thumb = p["thumbFrame"]!!
        val info = p["infoRow"]!!
        assertEquals(w, root.w, 0.5f)
        assertEquals(w * 9f / 16f, thumb.h, 1f)
        assertTrue("info below thumb", info.y >= thumb.bottom - 0.5f)
        assertTrue("root ${root.h} covers info ${info.bottom}", root.h >= info.bottom - 0.5f)
        val texts = p["texts"]!!
        assertTrue("info covers texts", info.h >= texts.h - 0.5f)
        assertTrue("info row has height", info.h >= 36f * d - 0.5f)
    }

    @Test fun `card covers thumbnail and info at phone width`() = check(376f, 2.625f, 1, 1f)
    @Test fun `card covers info at half width`() = check(184f, 2.625f, 1, 1f)
    @Test fun `two line titles keep the items apart`() = check(184f, 2.625f, 2, 1f)
    @Test fun `font scale 1_3 with two lines`() = check(376f, 2.625f, 2, 1.3f)
}
