package com.github.libretube.ui.yt.builder

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class YtThemeCodecTest {
    private fun ok(text: String): YtThemeCodec.Read.Ok {
        val r = YtThemeCodec.decode(text)
        assertTrue(r.toString(), r is YtThemeCodec.Read.Ok)
        return r as YtThemeCodec.Read.Ok
    }

    @Test
    fun `a document survives a round trip`() {
        val doc = testDoc()
        val back = ok(YtThemeCodec.encode(doc))
        assertEquals(doc, back.doc)
        assertTrue(back.issues.isEmpty())
        assertEquals(YT_THEME_SCHEMA, back.fromSchema)
    }

    @Test
    fun `colours are written as hex`() {
        val text = YtThemeCodec.encode(testDoc())
        assertTrue(text.contains("\"#FFFF334B\""))
    }

    @Test
    fun `unknown fields are ignored and the animations kept`() {
        val doc = testDoc().copy(animations = YtAnimations(items = listOf(YtAnim("a1", component = YtComponentKind.CARD, node = testDoc().components.card.id, tracks = listOf(YtTrack(YtAnimProp.OPACITY, listOf(YtKeyframe(0f, 0f), YtKeyframe(1f, 1f))))))))
        val text = YtThemeCodec.encode(doc).replaceFirst("{", "{\"futureThing\":{\"a\":1},")
        val back = ok(text)
        assertEquals(doc.animations, back.doc.animations)
    }

    @Test
    fun `schema 1 reserved animations move to the legacy field`() {
        val raw = YtThemeCodec.json.parseToJsonElement(YtThemeCodec.encode(testDoc())).let { it as kotlinx.serialization.json.JsonObject }
        val v1 = kotlinx.serialization.json.JsonObject(
            raw.toMutableMap().apply {
                put("schema", JsonPrimitive(1))
                remove("animationsLegacy")
                put("animations", buildJsonObject { put("timeline", JsonPrimitive(3)) })
            },
        )
        val back = ok(v1.toString())
        assertEquals(1, back.fromSchema)
        assertEquals(YtAnimations(), back.doc.animations)
        assertEquals(buildJsonObject { put("timeline", JsonPrimitive(3)) }, back.doc.animationsLegacy)
        assertEquals(YT_THEME_SCHEMA, back.doc.schema)
    }

    @Test
    fun `schema 0 is migrated`() {
        val doc = testDoc()
        val text = YtThemeCodec.encode(doc).replace("\"schema\":$YT_THEME_SCHEMA,", "").replace("\"components\":", "\"parts\":")
        val back = ok(text)
        assertEquals(0, back.fromSchema)
        assertEquals(doc.components, back.doc.components)
        assertEquals(YT_THEME_SCHEMA, back.doc.schema)
    }

    @Test
    fun `a newer schema is read and reported`() {
        val text = YtThemeCodec.encode(testDoc()).replace("\"schema\":$YT_THEME_SCHEMA", "\"schema\":99")
        val back = ok(text)
        assertTrue(back.issues.any { it.path == "schema" })
        assertEquals(YT_THEME_SCHEMA, back.doc.schema)
    }

    @Test
    fun `broken files fail without throwing`() {
        for (text in listOf("", "{", "[1,2]", "null", "{\"id\":5}", "{\"schema\":-1}", "x".repeat(10))) {
            assertTrue(text, YtThemeCodec.decode(text) is YtThemeCodec.Read.Failed)
        }
    }

    @Test
    fun `wrong values are clamped on reading`() {
        val text = YtThemeCodec.encode(testDoc()).replace("\"columns\":2", "\"columns\":999").replace("\"opacity\":1.0", "\"opacity\":7.0")
        val back = ok(text)
        assertTrue(YtDocOps.all(back.doc.components.card).all { it.layout.columns <= YtThemeValidate.MAX_COLUMNS && it.style.opacity <= 1f })
        assertTrue(back.issues.isNotEmpty())
    }

    @Test
    fun `an unknown enum value takes the default`() {
        val text = YtThemeCodec.encode(testDoc()).replace("\"mode\":\"DARK\"", "\"mode\":\"NEON\"")
        assertEquals(YtThemeMode.DARK, ok(text).doc.mode)
    }
}
