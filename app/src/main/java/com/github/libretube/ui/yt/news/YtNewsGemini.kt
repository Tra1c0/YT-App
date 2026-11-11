package com.github.libretube.ui.yt.news

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

/**
 * YT: the summaries through the Gemini API (generateContent), called with plain HTTP. The key
 * goes in the "x-goog-api-key" header, never in the address. The answer is held to a JSON
 * schema ("responseJsonSchema") and checked by [YtNewsQuotes]. The key test lists the models,
 * which costs nothing.
 */
class YtNewsGemini(
    private val base: HttpUrl = "https://generativelanguage.googleapis.com/v1beta/".toHttpUrl(),
    private val http: OkHttpClient = YtNewsHttp.client,
) : YtNewsSummaryProvider {
    override val kind = YtNewsProviderKind.GEMINI
    override val defaultModel: String = MODEL

    private fun auth(setup: YtNewsProviderSetup) = mapOf("x-goog-api-key" to setup.key)

    fun body(sources: List<YtNewsSource>): JsonObject = buildJsonObject {
        putJsonObject("systemInstruction") {
            putJsonArray("parts") { add(buildJsonObject { put("text", YtNewsQuotes.SYSTEM) }) }
        }
        putJsonArray("contents") {
            add(
                buildJsonObject {
                    put("role", "user")
                    putJsonArray("parts") { add(buildJsonObject { put("text", YtNewsQuotes.prompt(sources)) }) }
                },
            )
        }
        putJsonObject("generationConfig") {
            put("responseMimeType", "application/json")
            put("responseJsonSchema", YtNewsQuotes.schema(story = sources.size > 1, closed = false))
        }
    }

    override fun check(setup: YtNewsProviderSetup): YtNewsSummaryFailure? = try {
        val url = YtNewsHttp.resolve(base, "models").newBuilder().addQueryParameter("pageSize", "1").build()
        YtNewsHttp.execute(YtNewsHttp.get(url, auth(setup)), http)
        null
    } catch (e: Exception) {
        failure(e)
    }

    override suspend fun summarize(setup: YtNewsProviderSetup, sources: List<YtNewsSource>): YtNewsSummaryResult {
        val url = YtNewsHttp.resolve(base, "models").newBuilder().addPathSegment("${setup.model}:generateContent").build()
        val answer = YtNewsHttp.await(YtNewsHttp.post(url, auth(setup), body(sources)), http)
        return YtNewsProviders.quoted(text(answer), sources)
    }

    override fun failure(error: Throwable): YtNewsSummaryFailure = YtNewsHttp.failure(error)

    companion object {
        /** The default model: the low-cost Flash-Lite model the docs point new projects to. */
        const val MODEL = "gemini-3.5-flash-lite"

        val DEFAULT = YtNewsGemini()

        /** Finish reasons that mean the answer was withheld. */
        private val BLOCKED = setOf("SAFETY", "RECITATION", "BLOCKLIST", "PROHIBITED_CONTENT", "SPII", "LANGUAGE")

        /** The text of the first candidate without thought parts; null when blocked or empty. */
        fun text(answer: String): String? {
            val root = YtNewsHttp.json.parseToJsonElement(answer) as? JsonObject ?: return null
            val feedback = root["promptFeedback"] as? JsonObject
            if ((feedback?.get("blockReason") as? JsonPrimitive)?.contentOrNull != null) return null
            val candidate = (root["candidates"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return null
            if ((candidate["finishReason"] as? JsonPrimitive)?.contentOrNull in BLOCKED) return null
            val parts = (candidate["content"] as? JsonObject)?.get("parts") as? JsonArray ?: return null
            return parts.mapNotNull { part ->
                val obj = part as? JsonObject ?: return@mapNotNull null
                if ((obj["thought"] as? JsonPrimitive)?.booleanOrNull == true) return@mapNotNull null
                (obj["text"] as? JsonPrimitive)?.contentOrNull
            }.joinToString("").takeIf { it.isNotBlank() }
        }
    }
}
