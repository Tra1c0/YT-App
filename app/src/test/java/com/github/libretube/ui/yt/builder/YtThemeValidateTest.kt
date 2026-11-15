package com.github.libretube.ui.yt.builder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class YtThemeValidateTest {
    @Test
    fun `a seed document needs no repair`() {
        val r = YtThemeValidate.clamp(testDoc())
        assertEquals(emptyList<YtThemeValidate.Issue>(), r.issues)
        assertEquals(testDoc(), r.doc)
    }

    @Test
    fun `numbers out of range are clamped and NaN replaced`() {
        val card = testDoc().components.card.copy(
            width = YtSize.fixed(Float.NaN),
            height = YtSize.fixed(1e9f),
            rotation = Float.POSITIVE_INFINITY,
            style = YtStyle(opacity = -3f, corners = YtCorners.all(5000f, smoothing = 4f), shadows = listOf(YtShadow(blur = 1e6f))),
        )
        val doc = testDoc().let { it.copy(components = it.components.copy(card = card)) }
        val r = YtThemeValidate.clamp(doc)
        val c = r.doc.components.card
        assertEquals(0f, c.width.value)
        assertEquals(YtThemeValidate.MAX_SIZE, c.height.value)
        assertEquals(0f, c.style.opacity)
        assertEquals(YtThemeValidate.MAX_RADIUS, c.style.corners.topStart)
        assertEquals(1f, c.style.corners.smoothing)
        assertEquals(YtThemeValidate.MAX_SHADOW, c.style.shadows.single().blur)
        assertTrue(c.rotation.isFinite())
        assertTrue(r.issues.size >= 5)
    }

    @Test
    fun `duplicate ids are renamed`() {
        val card = testDoc().components.card.copy(children = listOf(YtNode("x"), YtNode("x")))
        val doc = testDoc().let { it.copy(components = it.components.copy(card = card)) }
        val ids = YtDocOps.all(YtThemeValidate.clamp(doc).doc.components.card).map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `missing colour roles are added`() {
        val doc = testDoc().let { it.copy(tokens = it.tokens.copy(colors = emptyMap())) }
        val r = YtThemeValidate.clamp(doc)
        assertTrue(YtRole.all.all { it in r.doc.tokens.colors })
    }

    @Test
    fun `min above max keeps the minimum`() {
        val card = testDoc().components.card.copy(minW = 300f, maxW = 100f)
        val doc = testDoc().let { it.copy(components = it.components.copy(card = card)) }
        val c = YtThemeValidate.clamp(doc).doc.components.card
        assertEquals(300f, c.minW!!)
        assertEquals(300f, c.maxW!!)
    }

    @Test
    fun `deep trees are cut`() {
        var n = YtNode("leaf")
        repeat(60) { n = YtNode("d$it", children = listOf(n)) }
        val doc = testDoc().let { it.copy(components = it.components.copy(card = n)) }
        val r = YtThemeValidate.clamp(doc)
        assertTrue(YtDocOps.all(r.doc.components.card).size <= YtThemeValidate.MAX_DEPTH + 1)
        assertTrue(r.issues.any { it.what.contains("deep") })
    }

    @Test
    fun `bad ids and unsafe asset names are repaired`() {
        val doc = testDoc().copy(id = "../../etc", assets = listOf(YtAsset("../x.ttf", YtAssetKind.FONT), YtAsset("ok.ttf", YtAssetKind.FONT)))
        val r = YtThemeValidate.clamp(doc).doc
        assertTrue(YtThemeIds.valid(r.id))
        assertEquals(listOf("ok.ttf"), r.assets.map { it.file })
    }

    @Test
    fun `gradients with broken stops become drawable`() {
        val card = testDoc().components.card.copy(style = YtStyle(fills = listOf(YtPaint(type = YtPaintType.LINEAR, stops = emptyList(), angle = Float.NaN))))
        val doc = testDoc().let { it.copy(components = it.components.copy(card = card)) }
        val fill = YtThemeValidate.clamp(doc).doc.components.card.style.fills.single()
        assertEquals(2, fill.stops.size)
        assertEquals(90f, fill.angle)
    }
}
