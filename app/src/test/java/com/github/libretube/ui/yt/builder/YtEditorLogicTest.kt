package com.github.libretube.ui.yt.builder

import com.github.libretube.helpers.YtDesign
import com.github.libretube.ui.yt.YtContrast
import com.github.libretube.ui.yt.mini.YtMiniCustom
import com.github.libretube.ui.yt.mini.YtMiniJoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YtEditorLogicTest {
    @Test
    fun `frame percentiles and jank`() {
        val s = YtFrameStats(capacity = 100)
        assertNull(s.summary())
        for (i in 1..100) s.add(i * 1_000_000L, jank = i > 95)
        val sum = s.summary()!!
        assertEquals(50.0, sum.p50, 0.001)
        assertEquals(90.0, sum.p90, 0.001)
        assertEquals(95.0, sum.p95, 0.001)
        assertEquals(99.0, sum.p99, 0.001)
        assertEquals(5L, sum.janky)
        assertTrue(sum.line().startsWith("P50 50.0 ms"))
        // the ring keeps the newest frames only
        for (i in 1..100) s.add(2_000_000L, false)
        assertEquals(2.0, s.summary()!!.p99, 0.001)
        assertEquals(200L, s.frames)
    }

    @Test
    fun `a drag moves x and y by the anchors`() {
        val n = YtNode("n", x = 10f, y = 20f)
        assertEquals(YtNode("n", x = 15f, y = 30f), YtEditorDrag.moved(n, 10f, 20f, density = 2f))
        val end = n.copy(anchorH = YtAnchor.END, anchorV = YtAnchor.END)
        val moved = YtEditorDrag.moved(end, 10f, 20f, density = 2f)
        assertEquals(5f, moved.x)
        assertEquals(10f, moved.y)
        // half dp steps: no sub-pixel drift from many small moves
        assertEquals(10.5f, YtEditorDrag.moved(n, 1.3f, 0f, 2f).x)
    }

    @Test
    fun `insert index among siblings`() {
        val centers = listOf(10f, 50f, 90f, 130f)
        assertEquals(0, YtEditorDrag.insertIndex(centers, from = 2, centerMain = 5f))
        assertEquals(1, YtEditorDrag.insertIndex(centers, from = 0, centerMain = 70f))
        assertEquals(3, YtEditorDrag.insertIndex(centers, from = 0, centerMain = 200f))
        assertEquals(2, YtEditorDrag.insertIndex(centers, from = 2, centerMain = 95f))
    }

    @Test
    fun `resizing keeps the aspect when locked and the far edge in place`() {
        val n = YtNode("n", x = 10f, y = 10f)
        val r = YtEditorDrag.resized(n, YtEditorDrag.Handle.BOTTOM_RIGHT, 20f, 0f, 100f, 50f, density = 1f, lock = true, freeParent = true)
        assertEquals(120f, r.width.value)
        assertEquals(60f, r.height.value)
        val tl = YtEditorDrag.resized(n, YtEditorDrag.Handle.TOP_LEFT, -10f, -10f, 100f, 50f, density = 1f, lock = false, freeParent = true)
        assertEquals(110f, tl.width.value)
        assertEquals(60f, tl.height.value)
        assertEquals(0f, tl.x)
        assertEquals(0f, tl.y)
        val tiny = YtEditorDrag.resized(n, YtEditorDrag.Handle.BOTTOM_RIGHT, -500f, -500f, 100f, 50f, density = 1f, lock = false, freeParent = false)
        assertEquals(4f, tiny.width.value)
    }

    @Test
    fun `rotation snaps to 15 degree steps`() {
        assertEquals(0f, YtEditorDrag.rotation(0f, 0f, 1f, -100f, snap = true))
        assertEquals(90f, YtEditorDrag.rotation(0f, 0f, 100f, 3f, snap = true))
        assertEquals(45f, YtEditorDrag.rotation(0f, 0f, 100f, -100f, snap = true))
        val free = YtEditorDrag.rotation(0f, 0f, 100f, -50f, snap = true)
        assertTrue(free in 60f..70f)
    }

    @Test
    fun `the seed theme reads well`() {
        assertEquals(emptyList<YtEditorChecks.Warning>(), YtEditorChecks.contrast(testDoc(), night = false))
    }

    @Test
    fun `low contrast is found`() {
        val doc = testDoc().let { d ->
            d.copy(components = d.components.copy(card = YtDocOps.update(d.components.card, "title") { it.copy(text = it.text!!.copy(color = YtPaint.token(YtRole.RAISED))) }))
        }
        val w = YtEditorChecks.contrast(doc, night = false)
        assertTrue(w.any { it.nodeId == "title" && it.kind == YtEditorChecks.Kind.CONTRAST && it.value < 4.5 })
    }

    @Test
    fun `small controls are found`() {
        val doc = testDoc()
        val w = YtEditorChecks.touch(doc, { _, id -> if (id == "search") YtSnap.Rect(0f, 0f, 80f, 80f) else if (id == "profile") YtSnap.Rect(0f, 0f, 200f, 200f) else null }, density = 2f)
        assertEquals(listOf("search"), w.map { it.nodeId })
        assertEquals(40.0, w.single().value, 0.01)
    }

    @Test
    fun `safe mode decisions`() {
        assertEquals(YtBuilderSafeMode.Verdict.NOTHING, YtBuilderSafeMode.decide(custom = false, crashed = true))
        assertEquals(YtBuilderSafeMode.Verdict.FALL_BACK_CRASH, YtBuilderSafeMode.decide(custom = true, crashed = true))
        assertEquals(YtBuilderSafeMode.Verdict.LOAD, YtBuilderSafeMode.decide(custom = true, crashed = false))
        assertTrue(YtBuilderSafeMode.blames(true, "anything"))
        assertTrue(YtBuilderSafeMode.blames(false, "at com.github.libretube.ui.yt.builder.YtThemeRender.x"))
        assertFalse(YtBuilderSafeMode.blames(false, "at com.github.libretube.ui.yt.YtHome.x"))
    }

    @Test
    fun `every design can be the start of a theme that needs no repair`() {
        for (design in YtDesign.entries) {
            val doc = YtBuilderActive.create(design, "x", now = 1L)
            val r = YtThemeValidate.clamp(doc)
            assertEquals(design.name, emptyList<YtThemeValidate.Issue>(), r.issues)
            assertTrue(design.name, YtDesign.entries.any { it.key == doc.base && it != YtDesign.CUSTOM && it != YtDesign.STANDARD })
            // light designs bring their dark look along
            if (design.light && design != YtDesign.CUSTOM) assertEquals(design.name, YtThemeMode.BOTH, doc.mode)
            assertEquals(design.name, emptyList<YtEditorChecks.Warning>(), YtEditorChecks.contrast(doc, night = false).filter { it.component != YtComponentKind.MINI })
        }
    }

    @Test
    fun `the search of a light theme reads`() {
        val (seed, night) = YtBuilderActive.seedOf(YtDesign.CLAY)
        val doc = YtThemeSeeds.create(seed, "c", "Clay", 0L, night)
        for (n in listOf(false, true)) {
            val inks = YtBuilderInks.search(YtBuilderActive.paletteOf(doc, n, null))
            for (screen in listOf(0xFF000000.toInt(), 0xFFFFFFFF.toInt())) {
                val base = YtContrast.over(inks.scrim, screen)
                assertTrue(YtContrast.ratio(inks.rowText, base) >= 4.5)
                assertTrue(YtContrast.ratio(inks.fieldText, inks.field) >= 4.5)
            }
        }
    }

    @Test
    fun `the mini player measures follow the theme within limits`() {
        val spec = YtMiniCustom.specOf(YtMiniConfig(height = 200f, radius = 99f, thumbWidth = 10f, thumbRadius = 5f), YtNavConfig(floating = false, side = 20f))
        assertEquals(YtMiniJoint.STACK, spec.joint)
        assertEquals(96, spec.height)
        assertEquals(48, spec.radius)
        assertEquals(48, spec.thumbWidth)
        assertEquals(0, spec.start)
        assertEquals(20, YtMiniCustom.specOf(YtMiniConfig(), YtNavConfig(floating = true, side = 20f)).start)
    }
}
