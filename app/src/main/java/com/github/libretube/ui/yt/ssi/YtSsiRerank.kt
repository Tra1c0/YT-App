package com.github.libretube.ui.yt.ssi

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.security.MessageDigest
import kotlin.math.roundToInt

/**
 * YT (SSI-2): the optional AI re-rank of Deep, pure. The best [TOP] passages go to the provider
 * of the news summaries in one request (query, each passage with its words numbered and a piece
 * of the passages before and after). It answers per passage a grade 0–3 and optionally the
 * statement's words inside it. The answer is read leniently (code fences, other key names,
 * numbers as text) but only for the ids that were sent: it can reorder and mark, never add a
 * passage. Its order is fused with the local one ([fuse]), not put in its place, and only
 * inside the window it saw. Answers are cached per query, passages and model ([cacheKey]).
 */
object YtSsiRerank {
    const val TOP = 20

    /** A passage's text in the request; longer ones are cut (their words keep their numbers). */
    const val MAX_PASSAGE_CHARS = 900

    /** How much of each neighbour passage goes along as context. */
    const val CONTEXT_CHARS = 220

    /** Every [MARK_EVERY] words the word number is written into the passage. */
    const val MARK_EVERY = 5

    /** Requests per search and per day (the day cap is counted on the phone). */
    const val CALLS_PER_SEARCH = 1
    const val CALLS_PER_DAY = 40

    const val CACHE_MS = 30L * 24 * 60 * 60 * 1000

    /** The local place enters as (K + 1) / (K + place): 1 for the first, 0.37 for the 20th. */
    const val LOCAL_K = 10.0

    /** An ungraded passage of the window counts as this grade. */
    const val NEUTRAL = 1.5

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** One passage of the request: its short id ("p1"), its index segment and words. */
    data class Passage(val id: String, val segmentId: Long, val title: String, val text: String, val before: String?, val after: String?) {
        val words: Int get() = YtSsiText.wordRanges(text).size
    }

    /** The passages of [hits] to grade: the first [TOP] that are passages, with their neighbours' text. */
    fun passages(hits: List<YtSsiHit>, segment: (Long) -> YtSsiSegment?, neighbour: (String, Int) -> YtSsiSegment?): List<Passage> {
        val out = ArrayList<Passage>()
        for (hit in hits.take(TOP)) {
            val id = hit.segmentId ?: continue
            val seg = segment(id) ?: continue
            if (seg.kind != YtSsiSegmentKind.SPEECH) continue
            val before = neighbour(seg.videoId, seg.ord - 1)?.takeIf { it.kind == YtSsiSegmentKind.SPEECH }?.text?.takeLast(CONTEXT_CHARS)
            val after = neighbour(seg.videoId, seg.ord + 1)?.takeIf { it.kind == YtSsiSegmentKind.SPEECH }?.text?.take(CONTEXT_CHARS)
            out += Passage("p${out.size + 1}", id, hit.title, seg.text, before, after)
        }
        return out
    }

    val SYSTEM = """
        Du prüfst Fundstellen einer Suche in den Untertiteln von YouTube-Videos.
        Bewerte jede Passage danach, wie genau sie die gesuchte Aussage enthält oder die Frage beantwortet:
        0 = passt nicht, 1 = nur das Thema, 2 = passt teilweise, 3 = genau die gesuchte Stelle.
        Der Text davor und danach dient nur dem Verständnis; bewertet wird die Passage selbst.
        Steht die passende Aussage nur in einem Teil der Passage, gib in "start" und "end" die Nummern ihres ersten und letzten Wortes an
        (Wörter ab 0 gezählt; jede fünfte Nummer steht als ⟨n⟩ vor ihrem Wort), sonst -1 und -1.
        Bewerte nur die gelieferten Passagen mit genau ihren IDs; erfinde keine Passagen, IDs oder Inhalte.
        Antworte nur mit JSON: {"items":[{"id":"p1","relevant":0,"start":-1,"end":-1}]}.
    """.trimIndent()

    /** The passage with every [MARK_EVERY]th word number in front of its word, cut at [MAX_PASSAGE_CHARS]. */
    fun marked(text: String): String {
        val ranges = YtSsiText.wordRanges(text)
        val out = StringBuilder()
        var at = 0
        for ((i, range) in ranges.withIndex()) {
            if (range.first > MAX_PASSAGE_CHARS) break
            out.append(text, at, range.first)
            if (i % MARK_EVERY == 0) out.append('⟨').append(i).append('⟩')
            out.append(text, range.first, range.last + 1)
            at = range.last + 1
        }
        if (at < text.length && at <= MAX_PASSAGE_CHARS) out.append(text, at, minOf(text.length, MAX_PASSAGE_CHARS))
        if (text.length > MAX_PASSAGE_CHARS) out.append(" …")
        return out.toString()
    }

    fun user(intent: YtSsiIntent, passages: List<Passage>): String = buildString {
        append("Suchanfrage: ").append(intent.raw)
        if (intent.phrases.isNotEmpty()) append("\nWörtliches Zitat: ").append(intent.phrases.joinToString(" | "))
        append("\n")
        for (p in passages) {
            append("\n### ").append(p.id).append(" (Video: ").append(p.title.take(120)).append(")\n")
            p.before?.let { append("Davor: …").append(it.replace('\n', ' ')).append('\n') }
            append("Passage: ").append(marked(p.text).replace('\n', ' ')).append('\n')
            p.after?.let { append("Danach: ").append(it.replace('\n', ' ')).append("…\n") }
        }
    }

    /** Strict JSON schema (every key required; -1 means "no span"). */
    fun schema(): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("items") {
                put("type", "array")
                putJsonObject("items") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("id") { put("type", "string") }
                        putJsonObject("relevant") { put("type", "integer") }
                        putJsonObject("start") { put("type", "integer") }
                        putJsonObject("end") { put("type", "integer") }
                    }
                    putJsonArray("required") {
                        add("id")
                        add("relevant")
                        add("start")
                        add("end")
                    }
                    put("additionalProperties", false)
                }
            }
        }
        putJsonArray("required") { add("items") }
        put("additionalProperties", false)
    }

    /**
     * The grades in [answer] by segment id, or null when nothing in it could be read. Unknown
     * ids, grades outside 0–3 that are not numbers, and spans outside the passage are dropped
     * (a grade is clamped into 0–3); the first answer for an id counts.
     */
    fun parse(answer: String?, passages: List<Passage>): Map<Long, YtSsiAiGrade>? {
        val text = answer?.trim() ?: return null
        val root = element(text) ?: return null
        val items: JsonArray = when (root) {
            is JsonArray -> root
            is JsonObject -> (root["items"] ?: root["results"] ?: root["passages"] ?: root["grades"]) as? JsonArray ?: return null
            else -> return null
        }
        val byId = passages.associateBy { it.id.lowercase() }
        val out = LinkedHashMap<Long, YtSsiAiGrade>()
        for (item in items) {
            val obj = item as? JsonObject ?: continue
            val id = obj["id"]?.let(::content)?.trim()?.lowercase()?.let { if (it.firstOrNull()?.isDigit() == true) "p$it" else it } ?: continue
            val passage = byId[id] ?: continue
            if (passage.segmentId in out) continue
            val grade = (obj["relevant"] ?: obj["relevance"] ?: obj["score"] ?: obj["grade"])?.let(::number) ?: continue
            val span = obj["span"]
            val start = (obj["start"] ?: (span as? JsonObject)?.get("start") ?: (span as? JsonArray)?.getOrNull(0))?.let(::number)
            val end = (obj["end"] ?: (span as? JsonObject)?.get("end") ?: (span as? JsonArray)?.getOrNull(1))?.let(::number)
            out[passage.segmentId] = YtSsiAiGrade(grade.roundToInt().coerceIn(0, 3), words(start, end, passage.words))
        }
        return out.takeIf { it.isNotEmpty() }
    }

    /** A span of word offsets inside a passage of [count] words; null when absent or not inside it. */
    internal fun words(start: Double?, end: Double?, count: Int): IntRange? {
        val s = start?.roundToInt() ?: return null
        val e = end?.roundToInt() ?: return null
        if (s < 0 || e < 0 || count <= 0 || s >= count || e < s) return null
        return s..minOf(e, count - 1)
    }

    private fun element(text: String): JsonElement? {
        val obj = text.indexOf('{').let { s -> if (s < 0) null else text.lastIndexOf('}').takeIf { it > s }?.let { text.substring(s, it + 1) } }
        val arr = text.indexOf('[').let { s -> if (s < 0) null else text.lastIndexOf(']').takeIf { it > s }?.let { text.substring(s, it + 1) } }
        // an object first (it may hold the array); a bare array when the answer is one
        val first = if (obj != null && arr != null && text.indexOf('[') < text.indexOf('{')) listOf(arr, obj) else listOfNotNull(obj, arr)
        for (candidate in first) {
            runCatching { json.parseToJsonElement(candidate) }.getOrNull()?.let { return it }
        }
        return null
    }

    private fun content(e: JsonElement): String? = (e as? JsonPrimitive)?.contentOrNull

    private fun number(e: JsonElement): Double? = content(e)?.trim()?.toDoubleOrNull()?.takeIf { !it.isNaN() && !it.isInfinite() }

    /** How much the AI counts against the local place: less for a quote, whose wording decides. */
    fun weight(intent: YtSsiIntent): Double = if (intent.exact) 0.5 else 1.0

    /**
     * [hits] in their new order: inside the window of the first [TOP] each scores
     * (K + 1) / (K + local place) + weight × grade / 3 (an ungraded one [NEUTRAL]); ties keep the
     * local order; everything after the window stays behind it as it was. Graded hits carry their
     * grade and AI_RERANK; every hit keeps its local place in [YtSsiHit.localRank].
     */
    fun fuse(hits: List<YtSsiHit>, grades: Map<Long, YtSsiAiGrade>, intent: YtSsiIntent): List<YtSsiHit> {
        val w = weight(intent)
        val window = hits.take(TOP)
        val scored = window.mapIndexed { i, hit ->
            val grade = hit.segmentId?.let { grades[it] }
            val local = (LOCAL_K + 1) / (LOCAL_K + i + 1)
            Triple(hit, grade, local + w * (grade?.relevant?.toDouble() ?: NEUTRAL) / 3.0)
        }.withIndex().sortedWith(compareByDescending<IndexedValue<Triple<YtSsiHit, YtSsiAiGrade?, Double>>> { it.value.third }.thenBy { it.index })
        val ordered = scored.map { (_, t) ->
            val (hit, grade, _) = t
            if (grade == null) hit.copy(localRank = hit.rank) else hit.copy(ai = grade, methods = hit.methods + YtSsiMethod.AI_RERANK, localRank = hit.rank)
        } + hits.drop(TOP).map { it.copy(localRank = it.rank) }
        return ordered.mapIndexed { i, hit -> hit.copy(rank = i + 1) }
    }

    /** The cache key: the folded query, the sorted passage ids and the model. */
    fun cacheKey(query: String, segmentIds: Collection<Long>, model: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest((query + "\n" + segmentIds.sorted().joinToString(",") + "\n" + model).toByteArray())
        return "rr|" + digest.joinToString("") { "%02x".format(it) }.take(40)
    }

    fun encode(grades: Map<Long, YtSsiAiGrade>): String = buildJsonArray {
        for ((id, g) in grades) {
            add(buildJsonArray {
                add(id)
                add(g.relevant)
                add(g.words?.first ?: -1)
                add(g.words?.last ?: -1)
            })
        }
    }.toString()

    fun decode(text: String?): Map<Long, YtSsiAiGrade>? {
        val array = text?.let { runCatching { json.parseToJsonElement(it) }.getOrNull() } as? JsonArray ?: return null
        val out = LinkedHashMap<Long, YtSsiAiGrade>()
        for (row in array) {
            val r = row as? JsonArray ?: continue
            val id = r.getOrNull(0)?.let(::number)?.toLong() ?: continue
            val grade = r.getOrNull(1)?.let(::number)?.roundToInt()?.coerceIn(0, 3) ?: continue
            val s = r.getOrNull(2)?.let(::number)?.roundToInt() ?: -1
            val e = r.getOrNull(3)?.let(::number)?.roundToInt() ?: -1
            out[id] = YtSsiAiGrade(grade, if (s >= 0 && e >= s) s..e else null, cached = true)
        }
        return out
    }

    fun fresh(storedAt: Long, now: Long): Boolean = now - storedAt in 0 until CACHE_MS

    /** Requests made on one day (local day number). */
    data class Day(val day: Long = 0, val count: Int = 0)

    /** The day after one more request, or null when the day's cap is reached. */
    fun spend(state: Day, today: Long, cap: Int = CALLS_PER_DAY): Day? {
        val current = if (state.day == today) state else Day(today, 0)
        return if (current.count >= cap) null else current.copy(count = current.count + 1)
    }

    /** Requests made [today] so far. */
    fun used(state: Day, today: Long): Int = if (state.day == today) state.count else 0
}
