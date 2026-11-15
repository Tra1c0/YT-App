package com.github.libretube.ui.yt.archive.script

import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.StopReason
import com.github.libretube.ui.yt.news.YtNewsChatCompletions
import com.github.libretube.ui.yt.news.YtNewsGemini
import com.github.libretube.ui.yt.news.YtNewsHttp
import com.github.libretube.ui.yt.news.YtNewsOpenAi
import com.github.libretube.ui.yt.news.YtNewsProviderKind
import com.github.libretube.ui.yt.news.YtNewsProviderSetup
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.future.await
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

/*
 * YT: plain text requests for the script, through the provider, key and model the user set for
 * the news summaries (no choice of its own). The answer readers of the news providers are
 * reused; the requests are the script's own (longer answers, longer time limits). Free of
 * Android; the request bodies are built by pure functions for the tests.
 */

object YtScriptHttp {
    /** A rewritten chunk is as long as its input: more time than a short summary needs. */
    val text: OkHttpClient by lazy {
        YtNewsHttp.client.newBuilder()
            .readTimeout(5, TimeUnit.MINUTES)
            .callTimeout(6, TimeUnit.MINUTES)
            .build()
    }

    /** Uploading and transcribing an hour of sound takes a while. */
    val audio: OkHttpClient by lazy {
        YtNewsHttp.client.newBuilder()
            .writeTimeout(5, TimeUnit.MINUTES)
            .readTimeout(10, TimeUnit.MINUTES)
            .callTimeout(15, TimeUnit.MINUTES)
            .build()
    }
}

object YtScriptTextAis {
    /** Room for a rewritten chunk plus some reasoning; billed only as far as used. */
    const val MAX_OUTPUT_TOKENS = 16_000

    /** The text AI of [setup]. */
    fun of(setup: YtNewsProviderSetup): YtScriptTextAi = when (setup.kind) {
        YtNewsProviderKind.ANTHROPIC -> YtScriptAnthropicText(setup)
        YtNewsProviderKind.OPENAI -> YtScriptOpenAiText(setup)
        YtNewsProviderKind.GEMINI -> YtScriptGeminiText(setup)
        YtNewsProviderKind.MISTRAL -> YtScriptChatText(setup, "https://api.mistral.ai/v1/".toHttpUrl())
        YtNewsProviderKind.COMPATIBLE -> YtScriptChatText(setup, setup.address?.let { YtNewsHttp.baseUrl(it) })
    }
}

/** OpenAI's Responses API with a strict JSON schema; not stored on the service. */
class YtScriptOpenAiText(
    private val setup: YtNewsProviderSetup,
    private val base: HttpUrl = "https://api.openai.com/v1/".toHttpUrl(),
    private val http: OkHttpClient = YtScriptHttp.text,
) : YtScriptTextAi {
    override val label = "openai"

    override suspend fun complete(system: String, user: String, schema: JsonObject): String? {
        val request = YtNewsHttp.post(YtNewsHttp.resolve(base, "responses"), mapOf("Authorization" to "Bearer ${setup.key}"), body(setup.model, system, user, schema))
        return YtNewsOpenAi.text(YtNewsHttp.await(request, http))
    }

    companion object {
        fun body(model: String, system: String, user: String, schema: JsonObject): JsonObject = buildJsonObject {
            put("model", model)
            put("instructions", system)
            put("input", user)
            put("store", false)
            put("max_output_tokens", YtScriptTextAis.MAX_OUTPUT_TOKENS)
            // the default model reasons; rewriting needs little of it (others may not take it)
            if (model == YtNewsOpenAi.MODEL) putJsonObject("reasoning") { put("effort", "low") }
            putJsonObject("text") {
                putJsonObject("format") {
                    put("type", "json_schema")
                    put("name", "archive_script")
                    put("schema", schema)
                    put("strict", true)
                }
            }
        }
    }
}

/** The Gemini API (generateContent) with a JSON schema; key in the header. */
class YtScriptGeminiText(
    private val setup: YtNewsProviderSetup,
    private val base: HttpUrl = YtScriptGemini.BASE,
    private val http: OkHttpClient = YtScriptHttp.text,
) : YtScriptTextAi {
    override val label = "gemini"

    override suspend fun complete(system: String, user: String, schema: JsonObject): String? {
        val url = YtScriptGemini.generateUrl(base, setup.model)
        val answer = YtNewsHttp.await(YtNewsHttp.post(url, YtScriptGemini.auth(setup), body(system, user, schema)), http)
        return YtNewsGemini.text(answer)
    }

    companion object {
        fun body(system: String, user: String, schema: JsonObject): JsonObject = buildJsonObject {
            putJsonObject("systemInstruction") {
                putJsonArray("parts") { add(buildJsonObject { put("text", system) }) }
            }
            putJsonArray("contents") {
                add(
                    buildJsonObject {
                        put("role", "user")
                        putJsonArray("parts") { add(buildJsonObject { put("text", user) }) }
                    },
                )
            }
            putJsonObject("generationConfig") {
                put("responseMimeType", "application/json")
                put("responseJsonSchema", YtScriptGemini.open(schema))
            }
        }
    }
}

/**
 * The chat-completions format: Mistral at its address, a compatible server at the user's. Only a
 * server of the user's choice may lack the schema format; it is then asked in JSON mode and
 * then without a format (the prompt describes the JSON as well).
 */
class YtScriptChatText(
    private val setup: YtNewsProviderSetup,
    private val base: HttpUrl?,
    private val http: OkHttpClient = YtScriptHttp.text,
) : YtScriptTextAi {
    override val label = setup.kind.id

    enum class Format { SCHEMA, JSON, NONE }

    override suspend fun complete(system: String, user: String, schema: JsonObject): String? {
        val base = base ?: throw YtNewsHttp.Status(400, "")
        val url = YtNewsHttp.resolve(base, "chat/completions")
        val auth = if (setup.key.isBlank()) emptyMap() else mapOf("Authorization" to "Bearer ${setup.key}")
        val compatible = setup.kind == YtNewsProviderKind.COMPATIBLE
        val formats = if (compatible) Format.entries else listOf(Format.SCHEMA)
        val maxTokens = if (compatible) null else YtScriptTextAis.MAX_OUTPUT_TOKENS
        for ((i, format) in formats.withIndex()) {
            val answer = try {
                YtNewsHttp.await(YtNewsHttp.post(url, auth, body(setup.model, system, user, schema, format, maxTokens)), http)
            } catch (e: YtNewsHttp.Status) {
                if (e.code in setOf(400, 422) && i < formats.lastIndex) continue
                throw e
            }
            return YtNewsChatCompletions.text(answer)
        }
        return null
    }

    companion object {
        /** [maxTokens] null leaves the server's own limit (a small local model may have less room). */
        fun body(model: String, system: String, user: String, schema: JsonObject, format: Format, maxTokens: Int? = YtScriptTextAis.MAX_OUTPUT_TOKENS): JsonObject = buildJsonObject {
            put("model", model)
            maxTokens?.let { put("max_tokens", it) }
            putJsonArray("messages") {
                add(buildJsonObject { put("role", "system"); put("content", system) })
                add(buildJsonObject { put("role", "user"); put("content", user) })
            }
            when (format) {
                Format.SCHEMA -> putJsonObject("response_format") {
                    put("type", "json_schema")
                    putJsonObject("json_schema") {
                        put("name", "archive_script")
                        put("schema", schema)
                        put("strict", true)
                    }
                }
                Format.JSON -> putJsonObject("response_format") { put("type", "json_object") }
                Format.NONE -> Unit
            }
        }
    }
}

/**
 * The provider with the official SDK: a plain message (its answer is JSON as the prompt asks;
 * there is no audio). Its own client with a longer time limit than the summaries'.
 */
class YtScriptAnthropicText(private val setup: YtNewsProviderSetup) : YtScriptTextAi {
    override val label = "anthropic"

    override suspend fun complete(system: String, user: String, schema: JsonObject): String? {
        val client = AnthropicOkHttpClient.builder().apiKey(setup.key).timeout(Duration.ofMinutes(6)).build()
        return try {
            text(client.async().messages().create(params(setup.model, system, user)).await())
        } finally {
            client.close()
        }
    }

    companion object {
        fun params(model: String, system: String, user: String): MessageCreateParams =
            MessageCreateParams.builder()
                .model(model)
                .maxTokens(YtScriptTextAis.MAX_OUTPUT_TOKENS.toLong())
                .system(system)
                .addUserMessage(user)
                .build()

        /** The answer text; null when declined (a partial text next to a refusal is not used). */
        fun text(message: Message): String? {
            if (message.stopReason().orElse(null) == StopReason.REFUSAL) return null
            return message.content().mapNotNull { it.text().orElse(null)?.text() }.joinToString("").takeIf { it.isNotBlank() }
        }
    }
}

/** Address, key header and schema dialect shared by Gemini's text and audio requests. */
object YtScriptGemini {
    val BASE: HttpUrl = "https://generativelanguage.googleapis.com/v1beta/".toHttpUrl()

    fun auth(setup: YtNewsProviderSetup) = mapOf("x-goog-api-key" to setup.key)

    fun generateUrl(base: HttpUrl, model: String): HttpUrl =
        YtNewsHttp.resolve(base, "models").newBuilder().addPathSegment("$model:generateContent").build()

    /** [schema] without "additionalProperties", which this service's schema dialect does not need. */
    fun open(schema: JsonObject): JsonObject = JsonObject(
        schema.filterKeys { it != "additionalProperties" }.mapValues { (_, v) ->
            when (v) {
                is JsonObject -> open(v)
                else -> v
            }
        },
    )
}
