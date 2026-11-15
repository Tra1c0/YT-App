package com.github.libretube.ui.yt.ssi

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * What the optional AI step (Deep only, with the news-summary provider) adds to a query: search
 * phrases for YouTube (also in other languages) and extra words for the full-text search. It
 * never decides what a hit is: the hits still come only from transcripts SSI has read.
 */
@Serializable
data class YtSsiAiPlan(
    val queries: List<String> = emptyList(),
    val keywords: List<String> = emptyList(),
)

/** YT: the request and answer rules of [YtSsiAiPlan], pure. */
object YtSsiAiRules {
    const val MAX_QUERIES = 4
    const val MAX_KEYWORDS = 12

    /** How long an answer is reused for the same query (folded). */
    const val CACHE_MS = 30L * 24 * 60 * 60 * 1000

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    val SYSTEM = """
        Du hilfst einer Suchfunktion, die Stellen in YouTube-Videos über deren Untertitel findet.
        Zerlege die Suchanfrage in bis zu $MAX_QUERIES kurze YouTube-Suchanfragen, die Videos mit der gesuchten Aussage finden
        (auch auf Englisch oder in der Sprache, in der das Thema meist besprochen wird), und bis zu $MAX_KEYWORDS Wörter,
        die in der gesuchten Stelle wahrscheinlich gesagt werden (Synonyme, Fachbegriffe, Übersetzungen).
        Behalte die Bedeutung der Anfrage genau bei; erfinde keine Namen, Kanäle oder Fakten.
        Zeiträume, Kanäle und Ausschlüsse der Anfrage prüft die App selbst; nimm sie nicht in die Wörter auf.
        Antworte nur mit JSON: {"queries":[...],"keywords":[...]}.
    """.trimIndent()

    fun user(intent: YtSsiIntent): String = buildString {
        append("Suchanfrage: ").append(intent.raw)
        if (intent.phrases.isNotEmpty()) append("\nWörtliches Zitat (muss genau so vorkommen): ").append(intent.phrases.joinToString(" | "))
    }

    fun schema(): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("queries") {
                put("type", "array")
                putJsonObject("items") { put("type", "string") }
            }
            putJsonObject("keywords") {
                put("type", "array")
                putJsonObject("items") { put("type", "string") }
            }
        }
        putJsonArray("required") {
            add("queries")
            add("keywords")
        }
        put("additionalProperties", false)
    }

    /** The plan in an answer (code fences and text around the JSON are tolerated), trimmed to the caps; null when unreadable. */
    fun parse(answer: String?): YtSsiAiPlan? {
        val text = answer?.trim() ?: return null
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val plan = runCatching { json.decodeFromString(YtSsiAiPlan.serializer(), text.substring(start, end + 1)) }.getOrNull() ?: return null
        fun clean(list: List<String>, max: Int, len: Int) =
            list.map { it.replace(Regex("\\s+"), " ").trim() }.filter { it.isNotEmpty() && it.length <= len }.distinctBy { it.lowercase() }.take(max)
        return YtSsiAiPlan(clean(plan.queries, MAX_QUERIES, 120), clean(plan.keywords, MAX_KEYWORDS, 60))
    }

    fun encode(plan: YtSsiAiPlan): String = json.encodeToString(YtSsiAiPlan.serializer(), plan)

    fun decode(text: String?): YtSsiAiPlan? = text?.let { runCatching { json.decodeFromString(YtSsiAiPlan.serializer(), it) }.getOrNull() }

    fun fresh(storedAt: Long, now: Long): Boolean = now - storedAt in 0 until CACHE_MS

    /**
     * The YouTube searches of a Deep search: the query itself (without its filters) first, then
     * the AI's, without repeats; at most [max].
     */
    fun searches(intent: YtSsiIntent, plan: YtSsiAiPlan?, max: Int = 1 + MAX_QUERIES): List<String> {
        val own = (intent.phrases.joinToString(" ") { "\"$it\"" } + " " + intent.semanticText.takeIf { intent.phrases.isEmpty() }.orEmpty()).trim()
            .ifEmpty { intent.raw }
        return (listOf(own) + plan?.queries.orEmpty()).distinctBy { YtSsiText.fold(it) }.filter { it.isNotBlank() }.take(max)
    }
}
