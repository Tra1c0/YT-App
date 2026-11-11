package com.github.libretube.ui.yt.news

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

/**
 * YT: the summaries through OpenAI's Responses API (the API its docs recommend for new work),
 * called with plain HTTP. The answer is held to a strict JSON schema (Structured Outputs) and
 * checked by [YtNewsQuotes]. The request is not stored on the service ("store": false). The key
 * test lists the models, which costs nothing.
 */
class YtNewsOpenAi(
    private val base: HttpUrl = "https://api.openai.com/v1/".toHttpUrl(),
    private val http: OkHttpClient = YtNewsHttp.client,
) : YtNewsSummaryProvider {
    override val kind = YtNewsProviderKind.OPENAI
    override val defaultModel: String = MODEL

    private fun auth(setup: YtNewsProviderSetup) = mapOf("Authorization" to "Bearer ${setup.key}")

    /** The request body: instructions, the numbered transcripts, and the schema the answer must follow. */
    fun body(setup: YtNewsProviderSetup, sources: List<YtNewsSource>): JsonObject = buildJsonObject {
        put("model", setup.model)
        put("instructions", YtNewsQuotes.SYSTEM)
        put("input", YtNewsQuotes.prompt(sources))
        put("store", false)
        put("max_output_tokens", MAX_OUTPUT_TOKENS)
        // the default model reasons; a little is plenty for a summary. Other models may not
        // take the setting, so it is left out for them.
        if (setup.model == MODEL) putJsonObject("reasoning") { put("effort", "low") }
        putJsonObject("text") {
            putJsonObject("format") {
                put("type", "json_schema")
                put("name", "news_summary")
                put("schema", YtNewsQuotes.schema(story = sources.size > 1))
                put("strict", true)
            }
        }
    }

    override fun check(setup: YtNewsProviderSetup): YtNewsSummaryFailure? = try {
        YtNewsHttp.execute(YtNewsHttp.get(YtNewsHttp.resolve(base, "models"), auth(setup)), http)
        null
    } catch (e: Exception) {
        failure(e)
    }

    override suspend fun summarize(setup: YtNewsProviderSetup, sources: List<YtNewsSource>): YtNewsSummaryResult {
        val request = YtNewsHttp.post(YtNewsHttp.resolve(base, "responses"), auth(setup), body(setup, sources))
        val answer = YtNewsHttp.await(request, http)
        return when (val text = text(answer)) {
            null -> YtNewsSummaryResult.Failed(YtNewsSummaryFailure.REFUSED)
            else -> YtNewsProviders.quoted(text, sources)
        }
    }

    override fun failure(error: Throwable): YtNewsSummaryFailure = YtNewsHttp.failure(error)

    companion object {
        /**
         * The default model: the small, low-cost model of the current family with Structured
         * Outputs (the user can name another in the settings).
         */
        const val MODEL = "gpt-6-luna"

        /** Room for the reasoning and a few short statements; billed only as far as used. */
        private const val MAX_OUTPUT_TOKENS = 16_000

        val DEFAULT = YtNewsOpenAi()

        /**
         * The answer's output text, or null when the model refused (a "refusal" part comes
         * instead of the text; a partial text next to it is not used) or there is none.
         */
        fun text(answer: String): String? {
            val root = YtNewsHttp.json.parseToJsonElement(answer) as? JsonObject ?: return null
            val out = StringBuilder()
            for (item in root["output"] as? JsonArray ?: return null) {
                val obj = item as? JsonObject ?: continue
                if ((obj["type"] as? JsonPrimitive)?.contentOrNull != "message") continue
                for (part in obj["content"] as? JsonArray ?: continue) {
                    val content = part as? JsonObject ?: continue
                    when ((content["type"] as? JsonPrimitive)?.contentOrNull) {
                        "refusal" -> return null
                        "output_text" -> out.append((content["text"] as? JsonPrimitive)?.contentOrNull.orEmpty())
                    }
                }
            }
            return out.toString().takeIf { it.isNotBlank() }
        }
    }
}
