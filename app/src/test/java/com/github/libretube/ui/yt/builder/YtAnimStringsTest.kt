package com.github.libretube.ui.yt.builder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** The animator's labels are string arrays in enum order: both languages must have one item per enum value. */
class YtAnimStringsTest {
    private fun arrays(lang: String): Map<String, Int> {
        val f = listOf(File("src/main/res/$lang/yt_r35_builder_strings.xml"), File("app/src/main/res/$lang/yt_r35_builder_strings.xml")).first { it.exists() }
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(f)
        val list = doc.getElementsByTagName("string-array")
        return (0 until list.length).associate { i ->
            val e = list.item(i) as org.w3c.dom.Element
            e.getAttribute("name") to e.getElementsByTagName("item").length
        }
    }

    @Test
    fun `label arrays match the enums in both languages`() {
        val expected = mapOf(
            "yt_anim_props" to YtAnimProp.entries.size,
            "yt_anim_presets" to YtPreset.entries.size,
            "yt_anim_triggers" to YtTriggerKind.entries.size,
            "yt_anim_unsupported" to YtAnimUnsupported.entries.size,
            "yt_anim_curves" to YtCurveKind.entries.size,
            "yt_anim_states" to YtStateNames.builtIn.size,
            "yt_anim_actions" to YtStateAction.entries.size,
        )
        for (lang in listOf("values", "values-de")) assertEquals(lang, expected, arrays(lang))
    }

    @Test
    fun `unsupported properties are marked`() {
        val plain = YtNode("n", type = YtNodeType.CONTAINER)
        assertEquals(YtAnimUnsupported.NO_FILL, YtAnimSupport.check(YtAnimProp.FILL_COLOR, plain))
        assertEquals(YtAnimUnsupported.NO_STROKE, YtAnimSupport.check(YtAnimProp.STROKE_WIDTH, plain))
        assertEquals(YtAnimUnsupported.TEXT_ONLY, YtAnimSupport.check(YtAnimProp.TEXT_COLOR, plain))
        assertEquals(YtAnimUnsupported.NEEDS_ANDROID_12, YtAnimSupport.check(YtAnimProp.BLUR, plain, sdk = 30))
        assertNull(YtAnimSupport.check(YtAnimProp.BLUR, plain, sdk = 31))
        assertNull(YtAnimSupport.check(YtAnimProp.X, plain))
        val filled = plain.copy(style = YtStyle(fills = listOf(YtPaint.solid(0xFF000000.toInt()))))
        assertNull(YtAnimSupport.check(YtAnimProp.FILL_COLOR, filled))
        assertEquals(YtAnimUnsupported.GRADIENT_ONLY, YtAnimSupport.check(YtAnimProp.GRADIENT_SHIFT, filled))
        val progress = YtNode("p", type = YtNodeType.SLOT, slot = YtSlot.PROGRESS, style = filled.style)
        assertEquals(YtAnimUnsupported.OWN_DRAWING, YtAnimSupport.check(YtAnimProp.FILL_COLOR, progress))
        assertNotNull(YtAnimSupport.check(YtAnimProp.CORNERS, progress))
        assertNull(YtAnimSupport.check(YtAnimProp.TEXT_COLOR, YtNode("t", type = YtNodeType.SLOT, slot = YtSlot.TITLE)))
    }
}
