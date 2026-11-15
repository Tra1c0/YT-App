package com.github.libretube.ui.yt.builder

import com.github.libretube.ui.yt.builder.YtLayoutEngine.Box
import com.github.libretube.ui.yt.builder.YtLayoutEngine.ConflictKind
import com.github.libretube.ui.yt.builder.YtLayoutEngine.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class YtLayoutEngineTest {
    /** Leaves are 20 x 10 unless their box says otherwise. */
    private fun leaf(node: YtNode, box: Box) = Size(20f.coerceIn(box.minW, box.maxW), 10f.coerceIn(box.minH, box.maxH))

    private fun tree(root: YtNode, box: Box, density: Float = 1f) = YtLayoutEngine.tree(root, box, density, ::leaf)

    @Test
    fun `a row shares the room left among its fill children by weight`() {
        val row = YtNode(
            "row", layout = YtLayout(YtLayoutMode.FLOW_H, gap = 10f),
            children = listOf(
                YtNode("a", width = YtSize.fixed(40f)),
                YtNode("b", width = YtSize(YtSizeKind.FILL, 1f)),
                YtNode("c", width = YtSize(YtSizeKind.FILL, 3f)),
            ),
        )
        val (p, conflicts) = tree(row, Box.exact(250f, 50f))
        assertEquals(40f, p["a"]!!.w)
        // 250 - 40 - 2 * 10 = 190, shared 1:3
        assertEquals(47.5f, p["b"]!!.w, 0.01f)
        assertEquals(142.5f, p["c"]!!.w, 0.01f)
        assertEquals(50f, p["b"]!!.x)
        assertEquals(107.5f, p["c"]!!.x, 0.01f)
        assertTrue(conflicts.isEmpty())
    }

    @Test
    fun `a hugging column is as big as its content plus padding`() {
        val col = YtNode(
            "col", layout = YtLayout(YtLayoutMode.FLOW_V, gap = 4f), padding = YtInsets.all(5f),
            children = listOf(YtNode("a"), YtNode("b", width = YtSize.fixed(60f), height = YtSize.fixed(30f))),
        )
        val (p, _) = tree(col, Box.upTo(500f, 500f))
        assertEquals(70f, p["col"]!!.w)
        assertEquals(10f + 4f + 30f + 10f, p["col"]!!.h)
        assertEquals(5f, p["a"]!!.x)
        assertEquals(19f, p["b"]!!.y)
    }

    @Test
    fun `density turns dp into px`() {
        val col = YtNode("col", children = listOf(YtNode("a", width = YtSize.fixed(10f), height = YtSize.fixed(10f))), padding = YtInsets.all(2f))
        val (p, _) = tree(col, Box.upTo(500f, 500f), density = 3f)
        assertEquals(30f, p["a"]!!.w)
        assertEquals(6f, p["a"]!!.x)
    }

    @Test
    fun `justify and align place the children`() {
        val row = YtNode(
            "row", layout = YtLayout(YtLayoutMode.FLOW_H, align = YtAlign.CENTER, justify = YtJustify.SPACE_BETWEEN),
            children = listOf(YtNode("a"), YtNode("b"), YtNode("c")),
        )
        val (p, _) = tree(row, Box.exact(100f, 30f))
        assertEquals(0f, p["a"]!!.x)
        assertEquals(40f, p["b"]!!.x)
        assertEquals(80f, p["c"]!!.x)
        assertEquals(10f, p["a"]!!.y)
    }

    @Test
    fun `a grid puts children in columns and rows`() {
        val grid = YtNode(
            "grid", layout = YtLayout(YtLayoutMode.GRID, gap = 10f, crossGap = 6f, columns = 3),
            children = (0 until 5).map { YtNode("i$it", height = YtSize.fixed(20f)) },
        )
        val (p, _) = tree(grid, Box.exact(320f, 400f))
        assertEquals(100f, p["i0"]!!.w)
        assertEquals(110f, p["i1"]!!.x)
        assertEquals(220f, p["i2"]!!.x)
        assertEquals(26f, p["i3"]!!.y)
        assertEquals(0f, p["i3"]!!.x)
    }

    @Test
    fun `free children sit at their anchors`() {
        val box = YtNode(
            "box", layout = YtLayout(YtLayoutMode.FREE),
            children = listOf(
                YtNode("tl", x = 4f, y = 6f),
                YtNode("br", x = 8f, y = 8f, anchorH = YtAnchor.END, anchorV = YtAnchor.END),
                YtNode("c", anchorH = YtAnchor.CENTER, anchorV = YtAnchor.CENTER),
                YtNode("fill", width = YtSize.FILL, height = YtSize.percent(50f)),
            ),
        )
        val (p, _) = tree(box, Box.exact(200f, 100f))
        assertEquals(4f, p["tl"]!!.x)
        assertEquals(6f, p["tl"]!!.y)
        assertEquals(200f - 20f - 8f, p["br"]!!.x)
        assertEquals(100f - 10f - 8f, p["br"]!!.y)
        assertEquals(90f, p["c"]!!.x)
        assertEquals(45f, p["c"]!!.y)
        assertEquals(200f, p["fill"]!!.w)
        assertEquals(50f, p["fill"]!!.h)
    }

    @Test
    fun `aspect lock gives the height from the width`() {
        val col = YtNode("col", children = listOf(YtNode("pic", width = YtSize.FILL, aspect = 16f / 9f)))
        val (p, _) = tree(col, Box.exact(320f, 1000f))
        assertEquals(180f, p["pic"]!!.h, 0.01f)
    }

    @Test
    fun `min and max bound a child`() {
        val col = YtNode(
            "col",
            children = listOf(
                YtNode("a", width = YtSize.FILL, maxW = 100f),
                YtNode("b", minW = 50f, minH = 30f),
            ),
        )
        val (p, _) = tree(col, Box.exact(300f, 300f))
        assertEquals(100f, p["a"]!!.w)
        assertEquals(50f, p["b"]!!.w)
        assertEquals(30f, p["b"]!!.h)
    }

    @Test
    fun `conflicts are reported and the layout still works`() {
        val hugRow = YtNode(
            "row", layout = YtLayout(YtLayoutMode.FLOW_H),
            children = listOf(
                YtNode("fill", width = YtSize.FILL),
                YtNode("pct", height = YtSize.percent(50f)),
                YtNode("minmax", minW = 80f, maxW = 40f),
                YtNode("aspect", width = YtSize.fixed(40f), height = YtSize.fixed(40f), aspect = 2f),
            ),
        )
        val (p, conflicts) = tree(hugRow, Box.upTo(YtLayoutEngine.INF, YtLayoutEngine.INF))
        val kinds = conflicts.map { it.nodeId to it.kind }.toSet()
        assertTrue(kinds.toString(), ("fill" to ConflictKind.FILL_IN_HUG) in kinds)
        assertTrue(kinds.toString(), ("pct" to ConflictKind.PERCENT_IN_HUG) in kinds)
        assertTrue(kinds.toString(), ("minmax" to ConflictKind.MIN_ABOVE_MAX) in kinds)
        assertTrue(kinds.toString(), ("aspect" to ConflictKind.ASPECT_OVERCONSTRAINED) in kinds)
        // the width wins over the aspect, the minimum over the maximum
        assertEquals(20f, p["aspect"]!!.h)
        assertEquals(80f, p["minmax"]!!.w)
        assertEquals(20f, p["fill"]!!.w)
    }

    @Test
    fun `a grid without width becomes a column`() {
        val grid = YtNode("grid", layout = YtLayout(YtLayoutMode.GRID, columns = 2), children = listOf(YtNode("a"), YtNode("b")))
        val (p, conflicts) = tree(grid, Box.upTo(YtLayoutEngine.INF, YtLayoutEngine.INF))
        assertTrue(conflicts.any { it.kind == ConflictKind.GRID_WITHOUT_WIDTH })
        assertEquals(10f, p["b"]!!.y)
    }

    @Test
    fun `hidden children take no room`() {
        val col = YtNode("col", layout = YtLayout(YtLayoutMode.FLOW_V, gap = 5f), children = listOf(YtNode("a"), YtNode("h", visible = false), YtNode("b")))
        val (p, _) = tree(col, Box.upTo(500f, 500f))
        assertEquals(15f, p["b"]!!.y)
        assertTrue("h" !in p)
    }

    @Test
    fun `every child is measured exactly once`() {
        val row = YtNode("row", layout = YtLayout(YtLayoutMode.FLOW_H), children = listOf(YtNode("a"), YtNode("b", width = YtSize.FILL), YtNode("c")))
        val counts = IntArray(3)
        YtLayoutEngine.layout(row, row.children, Box.exact(200f, 40f), 1f) { i, box ->
            counts[i]++
            leaf(row.children[i], box)
        }
        assertEquals(listOf(1, 1, 1), counts.toList())
    }

    @Test
    fun `overflow is reported`() {
        val row = YtNode("row", layout = YtLayout(YtLayoutMode.FLOW_H), children = listOf(YtNode("a", width = YtSize.fixed(80f)), YtNode("b", width = YtSize.fixed(80f))))
        val (_, conflicts) = tree(row, Box.exact(100f, 20f))
        assertTrue(conflicts.any { it.kind == ConflictKind.OVERFLOW })
    }

    @Test
    fun `the seed card lays out with real sizes`() {
        val card = testDoc().components.card
        val (p, conflicts) = tree(card, Box(360f, 360f, 0f, YtLayoutEngine.INF), density = 1f)
        assertEquals(360f, p["thumbFrame"]!!.w)
        assertEquals(202.5f, p["thumbFrame"]!!.h, 0.01f)
        assertEquals(3f, p["progress"]!!.h)
        // the progress bar lies on the bottom edge of the picture
        assertEquals(202.5f - 3f, p["progress"]!!.y, 0.01f)
        assertTrue(p["title"]!!.y > p["thumbFrame"]!!.bottom)
        assertTrue(conflicts.toString(), conflicts.isEmpty())
    }
}
