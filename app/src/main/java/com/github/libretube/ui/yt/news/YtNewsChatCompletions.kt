package com.github.libretube.ui.yt.news

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

/**
 * YT: the summaries through the common chat-completions format, called with plain HTTP: Mistral
 * at its own address, and any compatible server at the address the user enters (Groq, DeepSeek,
 * OpenRouter, a local Ollama or LM Studio…). The answer is asked for as JSON with a schema and
 * checked by [YtNewsQuotes]. A compatible server that does not take the schema is asked again in
 * plain JSON mode, and then without any format (the prompt describes the JSON as well).
 */
class YtNewsChatCompletions(
    override val kind: YtNewsProviderKind,
    /** The fixed address, or null for the user's own ([YtNewsProviderSetup.address]). */
    private val fixedBase: HttpUrl?,
    override val defaultModel: String?,
    private val http: OkHttpClient = YtNewsHttp.client,
) : YtNewsSummaryProvider {

    /** The address of a request; null when the user's own is missing or not allowed. */
    private fun base(setup: YtNewsProviderSetup): HttpUrl? = fixedBase ?: setup.address?.let { YtNewsHttp.baseUrl(it) }

    private fun auth(setup: YtNewsProviderSetup): Map<String, String> =
        if (setup.key.isBlank()) emptyMap() else mapOf("Authorization" to "Bearer ${setup.key}")

    /** How strictly the answer's format is asked for. */
    enum class Format { SCHEMA, JSON, NONE }

    fun body(setup: YtNewsProviderSetup, sources: List<YtNewsSource>, format: Format = Format.SCHEMA): JsonObject =
        buildJsonObject {
            put("model", setup.model)
            putJsonArray("messages") {
                add(message("system", YtNewsQuotes.SYSTEM))
                add(message("user", YtNewsQuotes.prompt(sources)))
            }
            when (format) {
                Format.SCHEMA -> putJsonObject("response_format") {
                    put("type", "json_schema")
                    putJsonObject("json_schema") {
                        put("name", "news_summary")
                        put("schema", YtNewsQuotes.schema(story = sources.size > 1))
                        put("strict", true)
                    }
                }
                Format.JSON -> putJsonObject("response_format") { put("type", "json_object") }
                Format.NONE -> Unit
            }
        }

    private fun message(role: String, text: String) = buildJsonObject {
        put("role", role)
        put("content", text)
    }

    override fun check(setup: YtNewsProviderSetup): YtNewsSummaryFailure? {
        val base = base(setup) ?: return YtNewsSummaryFailure.SERVICE
        return try {
            YtNewsHttp.execute(YtNewsHttp.get(YtNewsHttp.resolve(base, "models"), auth(setup)), http)
            null
        } catch (e: Exception) {
            // no model list there: the address is wrong, not the model
            failure(e).takeIf { it != YtNewsSummaryFailure.MODEL_UNKNOWN } ?: YtNewsSummaryFailure.SERVICE
        }
    }

    override suspend fun summarize(setup: YtNewsProviderSetup, sources: List<YtNewsSource>): YtNewsSummaryResult {
        val base = base(setup) ?: return YtNewsSummaryResult.Failed(YtNewsSummaryFailure.SERVICE)
        val url = YtNewsHttp.resolve(base, "chat/completions")
        // only a server of the user's choice may lack the schema format
        val formats = if (fixedBase == null) Format.entries else listOf(Format.SCHEMA)
        for ((i, format) in formats.withIndex()) {
            val answer = try {
                YtNewsHttp.await(YtNewsHttp.post(url, auth(setup), body(setup, sources, format)), http)
            } catch (e: YtNewsHttp.Status) {
                // 400/422: the format is not understood; try the next one
                if (e.code in FORMAT_REJECTED && i < formats.lastIndex) continue
                throw e
            }
            return YtNewsProviders.quoted(text(answer), sources)
        }
        return YtNewsSummaryResult.Failed(YtNewsSummaryFailure.SERVICE)
    }

    override fun failure(error: Throwable): YtNewsSummaryFailure = YtNewsHttp.failure(error)

    companion object {
        /** Mistral's default: the "latest" alias of its small model, which moves to new versions. */
        const val MISTRAL_MODEL = "mistral-small-latest"

        private val FORMAT_REJECTED = setOf(400, 422)

        val MISTRAL = YtNewsChatCompletions(
            YtNewsProviderKind.MISTRAL,
            "https://api.mistral.ai/v1/".toHttpUrl(),
            MISTRAL_MODEL,
        )

        val COMPATIBLE = YtNewsChatCompletions(YtNewsProviderKind.COMPATIBLE, null, null)

        /**
         * The text of the first choice; null when refused, filtered or empty. The content is a
         * string, or a list of parts of which only the text parts count (reasoning models).
         */
        fun text(answer: String): String? {
            val root = YtNewsHttp.json.parseToJsonElement(answer) as? JsonObject ?: return null
            val choice = (root["choices"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return null
            if ((choice["finish_reason"] as? JsonPrimitive)?.contentOrNull == "content_filter") return null
            val message = choice["message"] as? JsonObject ?: return null
            val refusal = message["refusal"]
            if (refusal != null && refusal !is JsonNull && (refusal as? JsonPrimitive)?.contentOrNull?.isNotBlank() == true) return null
            return content(message["content"])?.takeIf { it.isNotBlank() }
        }

        private fun content(element: JsonElement?): String? = when (element) {
            is JsonPrimitive -> element.contentOrNull
            is JsonArray -> element.mapNotNull { part ->
                val obj = part as? JsonObject ?: return@mapNotNull null
                if ((obj["type"] as? JsonPrimitive)?.contentOrNull != "text") return@mapNotNull null
                (obj["text"] as? JsonPrimitive)?.contentOrNull
            }.joinToString("")
            else -> null
        }
    }
}
