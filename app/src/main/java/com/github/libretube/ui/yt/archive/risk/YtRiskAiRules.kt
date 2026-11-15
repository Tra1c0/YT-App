package com.github.libretube.ui.yt.archive.risk

import com.github.libretube.ui.yt.archive.YtArchiveCandidate
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/*
 * YT: the optional AI check of the risk estimator, as rules without any network: what is sent
 * (per video only the title, the channel name and the first 200 characters of the description,
 * under a number local to the request; no video id), the answer format, the tolerant reading of
 * the answer, the per-video cache (a video is never asked twice) and the daily request limit.
 * Free of Android.
 */

/** Sends one request to the AI the user set up for the news summaries; the answer text or null. */
fun interface YtRiskAsk {
    suspend fun complete(system: String, user: String): String?
}

/** The AI side's stored state: today's request count and the answers per video id. */
@Serializable
class YtRiskAiState(
    @SerialName("day") var day: String = "",
    @SerialName("n") var requests: Int = 0,
    @SerialName("cache") val cache: MutableMap<String, YtRiskAiAnswer> = HashMap(),
) {
    /** Takes one request of [day]'s budget; false when it is used up. */
    fun take(day: String): Boolean {
        if (day != this.day) {
            this.day = day
            requests = 0
        }
        if (requests >= YtRiskAiRules.MAX_REQUESTS_PER_DAY) return false
        requests++
        return true
    }

    fun requestsOn(day: String): Int = if (day == this.day) requests else 0

    /** Old answers out, then the oldest beyond the cap. */
    fun prune(now: Long) {
        cache.values.removeAll { now - it.at > YtRiskAiRules.CACHE_DAYS * YtRiskModel.DAY_MS }
        if (cache.size > YtRiskAiRules.MAX_CACHE) {
            cache.entries.sortedBy { it.value.at }.take(cache.size - YtRiskAiRules.MAX_CACHE * 9 / 10)
                .forEach { cache.remove(it.key) }
        }
    }
}

object YtRiskAiRules {
    /** Videos per request. */
    const val BATCH = 25

    /** Requests per day, so at most 100 videos a day are sent. */
    const val MAX_REQUESTS_PER_DAY = 4

    const val CACHE_DAYS = 60L
    const val MAX_CACHE = 2000

    /** The longest reason kept from an answer. */
    const val REASON_WORDS = 12
    private const val REASON_CHARS = 120

    private val JSON = Json { ignoreUnknownKeys = true; isLenient = true }

    /** The instructions; the reason comes in German ([german]) or English. */
    fun system(german: Boolean): String {
        val language = if (german) "German" else "English"
        return "You estimate how likely YouTube videos are to vanish within the next 30 days: deleted by " +
            "the uploader, set to private, or taken down (copyright claims, leaks, legal disputes, " +
            "reuploads of other people's content, full movies or concerts, controversies the uploader " +
            "may regret). You only see the title, the channel and the start of the description. Most " +
            "videos stay online, so give low values unless there is a clear sign. Answer only with JSON " +
            "of the form {\"items\":[{\"id\":\"1\",\"risk\":0.05,\"reason\":\"...\"}]}, one item per " +
            "video with its id, risk between 0 and 1, reason at most $REASON_WORDS words in $language."
    }

    /** The user part: the videos as a JSON list under the numbers 1…n of [batch]. */
    fun prompt(batch: List<YtArchiveCandidate>): String {
        val list = buildJsonArray {
            batch.forEachIndexed { i, c ->
                add(
                    buildJsonObject {
                        put("id", (i + 1).toString())
                        put("channel", c.channelName.orEmpty())
                        put("title", c.title)
                        put("description", YtRiskText.descriptionStart(c.description))
                    },
                )
            }
        }
        return "Videos:\n" + JSON.encodeToString(JsonArray.serializer(), list)
    }

    /** The answer format as a JSON schema; [closed] for services that want no extra fields allowed. */
    fun schema(closed: Boolean = true): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("items") {
                put("type", "array")
                putJsonObject("items") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("id") { put("type", "string") }
                        putJsonObject("risk") { put("type", "number") }
                        putJsonObject("reason") { put("type", "string") }
                    }
                    putJsonArray("required") { add("id"); add("risk"); add("reason") }
                    if (closed) put("additionalProperties", false)
                }
            }
        }
        putJsonArray("required") { add("items") }
        if (closed) put("additionalProperties", false)
    }

    /** One video of an answer. */
    data class Item(val risk: Double, val reason: String?)

    /**
     * The items of an answer by their id, or null when it cannot be read. Tolerates code fences,
     * text around the JSON, a bare list, another name for the list, numbers as strings and
     * percentages; items without a usable risk are left out.
     */
    fun parse(answer: String?): Map<String, Item>? {
        if (answer.isNullOrBlank()) return null
        val root = element(answer) ?: return null
        val list = when (root) {
            is JsonArray -> root
            is JsonObject -> (root["items"] as? JsonArray) ?: root.values.firstOrNull { it is JsonArray } as? JsonArray
            else -> null
        } ?: return null
        val out = LinkedHashMap<String, Item>()
        for (entry in list) {
            val obj = entry as? JsonObject ?: continue
            val id = (obj["id"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            val risk = risk(obj["risk"]) ?: continue
            out[id] = Item(risk, reason((obj["reason"] as? JsonPrimitive)?.contentOrNull))
        }
        return out
    }

    private fun element(answer: String): JsonElement? {
        val text = answer.trim().replace(Regex("""^```[a-zA-Z]*\s*|\s*```$"""), "").trim()
        runCatching { return JSON.parseToJsonElement(text) }
        // text around the JSON: from the first opening bracket to the last closing one
        val start = text.indexOfFirst { it == '{' || it == '[' }
        val end = text.indexOfLast { it == '}' || it == ']' }
        if (start < 0 || end <= start) return null
        return runCatching { JSON.parseToJsonElement(text.substring(start, end + 1)) }.getOrNull()
    }

    private fun risk(element: JsonElement?): Double? {
        val p = element as? JsonPrimitive ?: return null
        var v = p.doubleOrNull ?: p.contentOrNull?.trim()?.removeSuffix("%")?.trim()?.replace(',', '.')?.toDoubleOrNull() ?: return null
        if (v.isNaN()) return null
        // a percentage
        if (v > 1 && v <= 100) v /= 100
        return v.takeIf { it in 0.0..1.0 }
    }

    /** At most [REASON_WORDS] words on one line, without quotes; null when empty. */
    fun reason(text: String?): String? {
        val words = text.orEmpty().replace(Regex("""\s+"""), " ").trim().trim('"', '\'', '„', '“', '”').split(' ')
            .filter { it.isNotEmpty() }
        if (words.isEmpty()) return null
        var out = words.take(REASON_WORDS).joinToString(" ")
        if (out.length > REASON_CHARS) out = out.take(REASON_CHARS).trimEnd() + "…"
        return out
    }
}
