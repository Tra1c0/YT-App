package com.github.libretube.ui.yt.archive.risk

import com.anthropic.models.beta.AnthropicBeta
import com.anthropic.models.beta.messages.BetaContentBlockParam
import com.anthropic.models.beta.messages.BetaOutputConfig
import com.anthropic.models.beta.messages.BetaStopReason
import com.anthropic.models.beta.messages.BetaTextBlockParam
import com.anthropic.models.beta.messages.MessageCreateParams
import com.github.libretube.ui.yt.news.YtNewsChatCompletions
import com.github.libretube.ui.yt.news.YtNewsGemini
import com.github.libretube.ui.yt.news.YtNewsHttp
import com.github.libretube.ui.yt.news.YtNewsOpenAi
import com.github.libretube.ui.yt.news.YtNewsProviderKind
import com.github.libretube.ui.yt.news.YtNewsProviderSetup
import com.github.libretube.ui.yt.news.YtNewsSummaryApi
import kotlinx.coroutines.future.await
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

/**
 * YT: a plain "instructions + text → answer text" request to the provider and model the user set
 * up for the news summaries ([YtNewsProviderSetup]; no model choice of its own). It uses the same
 * pieces as the summaries: one HTTP client without logging ([YtNewsHttp]) and the answer readers
 * of each provider, and for the provider with an SDK its official client ([YtNewsSummaryApi]).
 * The JSON schema of [YtRiskAiRules] is asked for where the service takes one. Errors are thrown
 * (the caller keeps the local score). Free of Android; the addresses can be changed for tests.
 */
class YtRiskAiClient(
    private val setup: YtNewsProviderSetup,
    private val addresses: Addresses = Addresses(),
    private val http: OkHttpClient = YtNewsHttp.client,
) : YtRiskAsk {

    /** The services' addresses: the same the summary providers use. [anthropic] null = the SDK's own. */
    class Addresses(
        val openAi: HttpUrl = "https://api.openai.com/v1/".toHttpUrl(),
        val gemini: HttpUrl = "https://generativelanguage.googleapis.com/v1beta/".toHttpUrl(),
        val mistral: HttpUrl = "https://api.mistral.ai/v1/".toHttpUrl(),
        val anthropic: String? = null,
        /** SDK retries; null keeps the SDK's default. */
        val anthropicRetries: Int? = null,
    )

    override suspend fun complete(system: String, user: String): String? = when (setup.kind) {
        YtNewsProviderKind.ANTHROPIC -> anthropic(system, user)
        YtNewsProviderKind.OPENAI -> openAi(system, user)
        YtNewsProviderKind.GEMINI -> gemini(system, user)
        YtNewsProviderKind.MISTRAL -> chat(addresses.mistral, system, user, ownServer = false)
        YtNewsProviderKind.COMPATIBLE -> {
            val base = setup.address?.let { YtNewsHttp.baseUrl(it) } ?: throw IllegalStateException("no address")
            chat(base, system, user, ownServer = true)
        }
    }

    private fun bearer(): Map<String, String> =
        if (setup.key.isBlank()) emptyMap() else mapOf("Authorization" to "Bearer ${setup.key}")

    fun openAiBody(system: String, user: String): JsonObject = buildJsonObject {
        put("model", setup.model)
        put("instructions", system)
        put("input", user)
        put("store", false)
        put("max_output_tokens", MAX_OUTPUT_TOKENS)
        // as for the summaries: the default model reasons, a little is enough
        if (setup.model == YtNewsOpenAi.MODEL) putJsonObject("reasoning") { put("effort", "low") }
        putJsonObject("text") {
            putJsonObject("format") {
                put("type", "json_schema")
                put("name", "video_risk")
                put("schema", YtRiskAiRules.schema())
                put("strict", true)
            }
        }
    }

    private suspend fun openAi(system: String, user: String): String? {
        val request = YtNewsHttp.post(YtNewsHttp.resolve(addresses.openAi, "responses"), bearer(), openAiBody(system, user))
        return YtNewsOpenAi.text(YtNewsHttp.await(request, http))
    }

    fun geminiBody(system: String, user: String): JsonObject = buildJsonObject {
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
            put("responseJsonSchema", YtRiskAiRules.schema(closed = false))
        }
    }

    private suspend fun gemini(system: String, user: String): String? {
        val url = YtNewsHttp.resolve(addresses.gemini, "models").newBuilder()
            .addPathSegment("${setup.model}:generateContent").build()
        val request = YtNewsHttp.post(url, mapOf("x-goog-api-key" to setup.key), geminiBody(system, user))
        return YtNewsGemini.text(YtNewsHttp.await(request, http))
    }

    fun chatBody(system: String, user: String, format: YtNewsChatCompletions.Format): JsonObject = buildJsonObject {
        put("model", setup.model)
        putJsonArray("messages") {
            add(buildJsonObject { put("role", "system"); put("content", system) })
            add(buildJsonObject { put("role", "user"); put("content", user) })
        }
        when (format) {
            YtNewsChatCompletions.Format.SCHEMA -> putJsonObject("response_format") {
                put("type", "json_schema")
                putJsonObject("json_schema") {
                    put("name", "video_risk")
                    put("schema", YtRiskAiRules.schema())
                    put("strict", true)
                }
            }
            YtNewsChatCompletions.Format.JSON -> putJsonObject("response_format") { put("type", "json_object") }
            YtNewsChatCompletions.Format.NONE -> Unit
        }
    }

    /** Chat completions; a server of the user's own may not take the schema: then JSON mode, then plain. */
    private suspend fun chat(base: HttpUrl, system: String, user: String, ownServer: Boolean): String? {
        val url = YtNewsHttp.resolve(base, "chat/completions")
        val formats = if (ownServer) YtNewsChatCompletions.Format.entries else listOf(YtNewsChatCompletions.Format.SCHEMA)
        for ((i, format) in formats.withIndex()) {
            val answer = try {
                YtNewsHttp.await(YtNewsHttp.post(url, bearer(), chatBody(system, user, format)), http)
            } catch (e: YtNewsHttp.Status) {
                if (e.code in FORMAT_REJECTED && i < formats.lastIndex) continue
                throw e
            }
            return YtNewsChatCompletions.text(answer)
        }
        return null
    }

    fun anthropicParams(system: String, user: String): MessageCreateParams =
        MessageCreateParams.builder()
            .model(setup.model)
            .maxTokens(MAX_OUTPUT_TOKENS.toLong())
            .system(system)
            .apply {
                // as for the summaries: low effort and server-side fallbacks for the default model only
                if (setup.model == YtNewsSummaryApi.MODEL) {
                    outputConfig(BetaOutputConfig.builder().effort(BetaOutputConfig.Effort.LOW).build())
                    addBeta(AnthropicBeta.SERVER_SIDE_FALLBACK_2026_07_01)
                    fallbacksDefault()
                }
            }
            .addUserMessageOfContentBlockParams(listOf(BetaContentBlockParam.ofText(BetaTextBlockParam.builder().text(user).build())))
            .build()

    private suspend fun anthropic(system: String, user: String): String? {
        val client = YtNewsSummaryApi.client(setup.key, addresses.anthropic, addresses.anthropicRetries)
        val message = try {
            client.async().beta().messages().create(anthropicParams(system, user)).await()
        } finally {
            client.close()
        }
        // a refusal's content may be partial: not used
        if (message.stopReason().orElse(null) == BetaStopReason.REFUSAL) return null
        return message.content().mapNotNull { it.text().orElse(null)?.text() }.joinToString("").takeIf { it.isNotBlank() }
    }

    private companion object {
        /** Room for some reasoning and 25 short items; billed only as far as used. */
        const val MAX_OUTPUT_TOKENS = 8000

        val FORMAT_REJECTED = setOf(400, 422)
    }
}
