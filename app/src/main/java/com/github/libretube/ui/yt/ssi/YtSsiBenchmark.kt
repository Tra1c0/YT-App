package com.github.libretube.ui.yt.ssi

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.security.MessageDigest
import kotlin.math.ceil
import kotlin.math.ln

/**
 * YT (SSI-2): the benchmark on the user's own verified examples, pure: the example file
 * ([parse]) and the measures ([evaluate]). The phone runs each example against the live index
 * ([YtSsiBenchRunner]); the JVM harness runs the same measures on its fixtures.
 *
 * The file (JSON, imported in the SSI settings):
 * ```
 * {"name": "Meine Beispiele", "toleranceMs": 3000, "examples": [
 *   {"query": "„das Fünfzigfache ihres Körpergewichts“", "videoId": "dQw4w9WgXcQ",
 *    "startMs": 61000, "endMs": 68000, "kind": "quote"},
 *   {"query": "Warum macht Kaffee wach?", "videoId": "https://youtu.be/dQw4w9WgXcQ",
 *    "start": "2:05", "end": "2:20", "kind": "question"},
 *   {"query": "etwas, das nirgends gesagt wird", "kind": "none"}]}
 * ```
 * A bare array of examples works too. `videoId` takes an id or a YouTube link; times as
 * `startMs`/`endMs` (milliseconds) or `start`/`end` (seconds, "m:ss" or "h:mm:ss"); `kind` is
 * "quote" (Zitat), "question" (Frage) or "none" (no match expected); without it a query in
 * quotation marks is a quote. An example that cannot be read is skipped and named.
 *
 * Measures: a hit is right when it is in the expected video and its time lies in the expected
 * moment ± the tolerance, or its checked span overlaps the moment. Recall@K is the share of
 * examples with a right hit in the first K; nDCG@5 with one relevant moment is 1 / log2(1 + place);
 * the deviation is |play start − expected start| of the first right hit; no-match is the share
 * of examples with no hit at all; latency per query. P50/P95 by nearest rank.
 */
object YtSsiBenchmark {
    const val MAX_EXAMPLES = 200
    const val DEFAULT_TOLERANCE_MS = 3_000L
    const val MAX_FILE_BYTES = 1_000_000

    enum class Kind { QUOTE, QUESTION, NONE }

    data class Example(val query: String, val videoId: String?, val startMs: Long, val endMs: Long, val kind: Kind)

    data class Examples(
        val name: String?,
        val toleranceMs: Long,
        val examples: List<Example>,
        /** "#3: no videoId" for every entry that was left out. */
        val skipped: List<String> = emptyList(),
    ) {
        /** Identifies the examples (not the name): runs on the same examples compare. */
        val hash: String
            get() = MessageDigest.getInstance("SHA-256")
                .digest(examples.joinToString("\n") { "${it.query}|${it.videoId}|${it.startMs}|${it.endMs}|${it.kind}" }.plus("|$toleranceMs").toByteArray())
                .joinToString("") { "%02x".format(it) }.take(12)
        val quotes: Int get() = examples.count { it.kind == Kind.QUOTE }
        val questions: Int get() = examples.count { it.kind == Kind.QUESTION }
        val negatives: Int get() = examples.count { it.kind == Kind.NONE }
    }

    class Invalid(message: String) : IllegalArgumentException(message)

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val VIDEO_ID = Regex("""^[A-Za-z0-9_-]{11}$""")
    private val VIDEO_IN_URL = Regex("""(?:[?&]v=|youtu\.be/|/shorts/|/live/|/embed/)([A-Za-z0-9_-]{11})""")
    private val QUOTED = Regex("""["„“”»«‚‘].+["“”«»‘’]""")

    /** The examples in [text]; throws [Invalid] when it is no JSON or holds no usable example. */
    fun parse(text: String): Examples {
        val root = runCatching { json.parseToJsonElement(text.trim().removePrefix("﻿")) }.getOrElse { throw Invalid("not JSON: ${it.message?.take(80)}") }
        val obj = root as? JsonObject
        val list = when (root) {
            is JsonArray -> root
            is JsonObject -> (root["examples"] ?: root["cases"] ?: root["beispiele"]) as? JsonArray ?: throw Invalid("no \"examples\" array")
            else -> throw Invalid("no examples")
        }
        val tolerance = obj?.get("toleranceMs")?.let(::number)?.toLong()?.coerceIn(0, 60_000) ?: DEFAULT_TOLERANCE_MS
        val name = obj?.get("name")?.let(::string)?.take(80)
        val out = ArrayList<Example>()
        val skipped = ArrayList<String>()
        for ((i, element) in list.withIndex()) {
            if (out.size >= MAX_EXAMPLES) {
                skipped += "#${i + 1}: more than $MAX_EXAMPLES"
                continue
            }
            val e = element as? JsonObject
            if (e == null) {
                skipped += "#${i + 1}: not an object"
                continue
            }
            runCatching { example(e) }.onSuccess { out += it }.onFailure { skipped += "#${i + 1}: ${it.message}" }
        }
        if (out.isEmpty()) throw Invalid("no usable example" + (skipped.firstOrNull()?.let { " ($it)" } ?: ""))
        return Examples(name, tolerance, out, skipped)
    }

    private fun example(e: JsonObject): Example {
        val query = e["query"]?.let(::string)?.trim()?.takeIf { it.isNotEmpty() } ?: throw Invalid("no query")
        val kind = when (e["kind"]?.let(::string)?.trim()?.lowercase()) {
            "quote", "zitat", "exact" -> Kind.QUOTE
            "question", "frage", "semantic" -> Kind.QUESTION
            "none", "kein", "keine", "nomatch", "no-match" -> Kind.NONE
            null, "" -> if (QUOTED.containsMatchIn(query)) Kind.QUOTE else Kind.QUESTION
            else -> throw Invalid("unknown kind")
        }
        if (kind == Kind.NONE) return Example(query, null, 0, 0, kind)
        val video = (e["videoId"] ?: e["video"] ?: e["url"])?.let(::string)?.trim()?.let(::videoId) ?: throw Invalid("no videoId")
        val start = e["startMs"]?.let(::number)?.toLong() ?: e["start"]?.let(::seconds) ?: throw Invalid("no start")
        val end = e["endMs"]?.let(::number)?.toLong() ?: e["end"]?.let(::seconds) ?: (start + 10_000)
        if (start < 0 || end < start) throw Invalid("end before start")
        return Example(query, video, start, end, kind)
    }

    /** An 11-character video id, from an id or a YouTube link. */
    fun videoId(text: String): String? = when {
        VIDEO_ID.matches(text) -> text
        else -> VIDEO_IN_URL.find(text)?.groupValues?.get(1)
    }

    /** A time as seconds (a number) or "m:ss"/"h:mm:ss" (decimals allowed), in ms. */
    fun seconds(e: JsonElement): Long? {
        val text = string(e)?.trim() ?: return null
        text.toDoubleOrNull()?.let { return (it * 1000).toLong() }
        val parts = text.split(':')
        if (parts.size !in 2..3) return null
        var total = 0.0
        for (part in parts) total = total * 60 + (part.toDoubleOrNull() ?: return null)
        return (total * 1000).toLong()
    }

    private fun string(e: JsonElement): String? = (e as? JsonPrimitive)?.contentOrNull

    private fun number(e: JsonElement): Double? = string(e)?.trim()?.toDoubleOrNull()

    // ---- measures ----

    /** A hit as the benchmark sees it. [spanStartMs]/[spanEndMs] the checked statement (or the passage). */
    data class Found(val videoId: String, val hitMs: Long, val spanStartMs: Long?, val spanEndMs: Long?, val playStartMs: Long)

    /** The answer to one example: its hits in order and how long the search took. */
    data class Answer(val found: List<Found>, val latencyMs: Long, val aiMs: Long? = null)

    @Serializable
    data class Metrics(
        val examples: Int,
        val positives: Int,
        val recall1: Double,
        val recall3: Double,
        val recall5: Double,
        val ndcg5: Double,
        val recall3Quotes: Double? = null,
        val recall3Questions: Double? = null,
        val deviationP50Ms: Long? = null,
        val deviationP95Ms: Long? = null,
        /** Share of examples with a match expected that got no hit at all. */
        val noMatch: Double,
        /** Share of "none" examples answered with no hit; null without such examples. */
        val emptyRight: Double? = null,
        val latencyP50Ms: Long,
        val latencyP95Ms: Long,
        /** Examples whose answer the AI re-ranked, and its median time. */
        val aiUsed: Int = 0,
        val aiP50Ms: Long? = null,
        val at: Long = 0,
        val hash: String = "",
    )

    fun right(example: Example, found: Found, toleranceMs: Long): Boolean {
        if (example.videoId == null || found.videoId != example.videoId) return false
        if (found.hitMs in (example.startMs - toleranceMs)..(example.endMs + toleranceMs)) return true
        val s = found.spanStartMs ?: return false
        val e = found.spanEndMs ?: return false
        return s <= example.endMs && e >= example.startMs
    }

    /** The 1-based place of the first right hit in the first [k], or null. */
    fun place(example: Example, answer: Answer, toleranceMs: Long, k: Int = 5): Int? =
        answer.found.take(k).indexOfFirst { right(example, it, toleranceMs) }.takeIf { it >= 0 }?.plus(1)

    fun evaluate(set: Examples, answers: List<Answer>, at: Long = 0): Metrics {
        require(answers.size == set.examples.size)
        val tol = set.toleranceMs
        val pairs = set.examples.zip(answers)
        val positive = pairs.filter { it.first.kind != Kind.NONE }
        val negative = pairs.filter { it.first.kind == Kind.NONE }
        val places = positive.map { (ex, a) -> place(ex, a, tol) }
        fun recall(k: Int, of: List<Int?> = places) = if (of.isEmpty()) 0.0 else of.count { it != null && it <= k } / of.size.toDouble()
        fun recallOf(kind: Kind): Double? {
            val sub = positive.filter { it.first.kind == kind }
            return if (sub.isEmpty()) null else recall(3, sub.map { (ex, a) -> place(ex, a, tol) })
        }
        val ndcg = if (places.isEmpty()) 0.0 else places.sumOf { p -> if (p == null) 0.0 else 1.0 / log2(p + 1.0) } / places.size
        val deviations = positive.mapNotNull { (ex, a) ->
            val hit = a.found.take(5).firstOrNull { right(ex, it, tol) } ?: return@mapNotNull null
            kotlin.math.abs(hit.playStartMs - ex.startMs)
        }
        val latencies = answers.map { it.latencyMs }
        val ai = answers.mapNotNull { it.aiMs }
        return Metrics(
            examples = set.examples.size,
            positives = positive.size,
            recall1 = recall(1),
            recall3 = recall(3),
            recall5 = recall(5),
            ndcg5 = ndcg,
            recall3Quotes = recallOf(Kind.QUOTE),
            recall3Questions = recallOf(Kind.QUESTION),
            deviationP50Ms = percentile(deviations, 0.50),
            deviationP95Ms = percentile(deviations, 0.95),
            noMatch = if (positive.isEmpty()) 0.0 else positive.count { it.second.found.isEmpty() } / positive.size.toDouble(),
            emptyRight = if (negative.isEmpty()) null else negative.count { it.second.found.isEmpty() } / negative.size.toDouble(),
            latencyP50Ms = percentile(latencies, 0.50) ?: 0,
            latencyP95Ms = percentile(latencies, 0.95) ?: 0,
            aiUsed = ai.size,
            aiP50Ms = percentile(ai, 0.50),
            at = at,
            hash = set.hash,
        )
    }

    /** Nearest-rank percentile ([p] in 0..1); null for no values. */
    fun percentile(values: List<Long>, p: Double): Long? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val rank = ceil(p * sorted.size).toInt().coerceIn(1, sorted.size)
        return sorted[rank - 1]
    }

    private fun log2(x: Double) = ln(x) / ln(2.0)

    // ---- stored runs ----

    /** One run as stored: SSI-2 as it is, and the same examples the SSI-1 way (see [YtSsiBenchRunner]). */
    @Serializable
    data class Run(val current: Metrics, val baseline: Metrics, val fetched: Int = 0, val missing: Int = 0, val name: String? = null)

    private val store = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun encode(run: Run): String = store.encodeToString(Run.serializer(), run)

    fun decode(text: String?): Run? = text?.let { runCatching { store.decodeFromString(Run.serializer(), it) }.getOrNull() }

    /** Whether [previous] ran on the same examples as [now], so their numbers compare. */
    fun comparable(previous: Metrics?, now: Metrics): Boolean = previous != null && previous.hash == now.hash
}
