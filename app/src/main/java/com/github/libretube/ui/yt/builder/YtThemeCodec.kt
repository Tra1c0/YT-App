package com.github.libretube.ui.yt.builder

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

/**
 * YT: theme documents as JSON (pure). Reading goes: parse → migrate the raw object from its
 * schema up to [YT_THEME_SCHEMA] step by step → decode (unknown fields ignored) → clamp
 * (YtThemeValidate). Nothing here throws: a broken file is a [Read.Failed] with the reason.
 */
object YtThemeCodec {
    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
        coerceInputValues = true
        isLenient = false
        prettyPrint = false
    }

    /** At most this much text is parsed; a bigger theme file is refused. */
    const val MAX_CHARS = 4_000_000

    sealed interface Read {
        data class Ok(val doc: YtThemeDoc, val issues: List<YtThemeValidate.Issue>, val fromSchema: Int) : Read
        data class Failed(val reason: String) : Read
    }

    /**
     * Steps from schema N to N + 1 on the raw JSON. Version 1 is the first; a later build adds
     * e.g. `1 to { o -> ...rename a field... }`. Keys are the schema a step starts from.
     */
    internal val migrations: Map<Int, (JsonObject) -> JsonObject> = mapOf(
        // schema 0: the prototype documents had no schema field and called the components "parts"
        0 to { o ->
            val parts = o["parts"]
            val m = o.toMutableMap()
            if (parts != null && "components" !in m) {
                m["components"] = parts
                m.remove("parts")
            }
            JsonObject(m)
        },
    )

    fun encode(doc: YtThemeDoc): String = json.encodeToString(YtThemeDoc.serializer(), doc)

    fun decode(text: String): Read {
        if (text.length > MAX_CHARS) return Read.Failed("file too large")
        val element = try {
            json.parseToJsonElement(text)
        } catch (e: Exception) {
            return Read.Failed("not JSON: ${e.message?.take(120)}")
        }
        val obj = element as? JsonObject ?: return Read.Failed("not a theme object")
        val schema = (obj["schema"] as? JsonPrimitive)?.intOrNull ?: 0
        if (schema < 0) return Read.Failed("bad schema $schema")
        val migrated = try {
            migrate(obj, schema)
        } catch (e: Exception) {
            return Read.Failed("migration failed: ${e.message?.take(120)}")
        }
        val doc = try {
            json.decodeFromJsonElement(YtThemeDoc.serializer(), migrated)
        } catch (e: Exception) {
            return Read.Failed("not a theme: ${e.message?.take(160)}")
        }
        val result = YtThemeValidate.clamp(doc)
        val issues = if (schema > YT_THEME_SCHEMA) {
            listOf(YtThemeValidate.Issue("schema", "made by a newer version ($schema); unknown parts are ignored")) + result.issues
        } else {
            result.issues
        }
        return Read.Ok(result.doc, issues, schema)
    }

    /** Runs the steps from [from] up to the current schema; a newer schema is left as it is. */
    fun migrate(obj: JsonObject, from: Int): JsonObject {
        var o = obj
        var v = from
        while (v < YT_THEME_SCHEMA) {
            o = migrations[v]?.invoke(o) ?: o
            v++
        }
        return if (from < YT_THEME_SCHEMA) JsonObject(o.toMutableMap().apply { put("schema", JsonPrimitive(YT_THEME_SCHEMA)) }) else o
    }

    /** The animations of a raw file, kept as they are (phase 2 reads them). */
    fun animationsOf(text: String): JsonElement? = runCatching { json.parseToJsonElement(text).jsonObject["animations"] }.getOrNull()
}
