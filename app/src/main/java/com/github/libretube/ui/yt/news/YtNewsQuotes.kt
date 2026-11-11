package com.github.libretube.ui.yt.news

import java.text.Normalizer
import java.util.Locale
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * YT: cited summaries for services that have no character citations (all but one provider). The
 * transcript goes out with numbered lines ("L12 [3:41] words"); the answer is strict JSON in which
 * every statement names its line and quotes a few words of it. Nothing the service says is
 * trusted: each quote is looked up in the cited line and its two neighbours on either side
 * (case, punctuation, umlaut spelling and a few typos do not matter), the second shown is that
 * line's start, and statements whose quote is not there are dropped. Pure Kotlin, JVM-tested.
 */
object YtNewsQuotes {
    /** Fewer verified statements than this: no summary, the description stays. */
    const val MIN_STATEMENTS = 2

    /** How far from the cited line a quote may be found. */
    const val NEIGHBOURS = 2

    /** Quotes shorter than this (letters after normalising) prove nothing. */
    private const val MIN_QUOTE_CHARS = 12
    private const val MIN_QUOTE_WORDS = 3

    /** Longer quotes are compared on their first part only; a whole line is fine. */
    private const val MAX_QUOTE_CHARS = 300

    /** Edits allowed per character of the quote (about one typo per eight letters). */
    private const val TOLERANCE = 0.125

    const val SYSTEM = "Du fasst Nachrichtenvideos anhand ihres Transkripts zusammen. " +
        "Schreibe auf Deutsch, kurz und neutral. Gib nur wieder, was im Transkript gesagt wird: " +
        "keine eigenen Meinungen, Wertungen, Vermutungen oder Hintergründe. " +
        "Die Zeilen des Transkripts sind nummeriert (L1, L2, …) und tragen ihre Startzeit [m:ss]. " +
        "Jede Aussage stützt sich auf genau eine Zeile und zitiert sie wörtlich."

    private const val FIELDS = "\"text\": die Aussage auf Deutsch, höchstens 25 Wörter; " +
        "\"line\": die Nummer der Zeile, auf die sie sich stützt, nur die Zahl (L12 → 12); " +
        "\"quote\": 4 bis 15 aufeinanderfolgende Wörter genau aus dieser Zeile, buchstabengetreu " +
        "abgeschrieben, in der Sprache des Transkripts, ohne Zeilennummer und Zeitangabe."

    private const val ASK_SINGLE = "Nenne die 3 bis 5 wichtigsten Kernaussagen dieses Videos. " +
        "Antworte nur mit JSON der Form {\"statements\":[{\"text\":…,\"line\":…,\"quote\":…}]}. " +
        FIELDS + " Ist das Transkript nicht deutsch, schreibe \"text\" trotzdem auf Deutsch."

    private const val ASK_STORY = "Die Transkripte gehören zu mehreren Videos zur selben Nachricht, " +
        "eines pro Quelle; die Überschrift nennt die Nummer des Videos und den Kanal, die " +
        "Zeilennummern beginnen in jedem Video bei L1. Was sagt welche Quelle? Nenne für jede " +
        "Quelle 1 bis 2 Kernaussagen, insgesamt höchstens 8. Antworte nur mit JSON der Form " +
        "{\"statements\":[{\"video\":…,\"text\":…,\"line\":…,\"quote\":…}]}. \"video\": die Nummer " +
        "des Videos; \"text\" in der Form \"Kanal: Aussage\". " + FIELDS +
        " Schreibe \"text\" auf Deutsch, auch wenn ein Transkript es nicht ist."

    /** The user message: the numbered transcript(s), then the task. */
    fun prompt(sources: List<YtNewsSource>): String {
        val out = StringBuilder()
        if (sources.size == 1) {
            out.append("Transkript: ").append(sources[0].title).append('\n').append(sources[0].doc.numbered())
            out.append('\n').append(ASK_SINGLE)
        } else {
            sources.forEachIndexed { i, source ->
                out.append("=== Video ").append(i + 1).append(": ").append(source.title).append(" ===\n")
                out.append(source.doc.numbered()).append('\n')
            }
            out.append(ASK_STORY)
        }
        return out.toString()
    }

    /**
     * The JSON schema of the answer. [closed] adds "additionalProperties": false, which strict
     * modes require and one service's schema dialect does not need.
     */
    fun schema(story: Boolean, closed: Boolean = true): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("statements") {
                put("type", "array")
                putJsonObject("items") {
                    put("type", "object")
                    putJsonObject("properties") {
                        if (story) putJsonObject("video") { put("type", "integer") }
                        putJsonObject("text") { put("type", "string") }
                        putJsonObject("line") { put("type", "integer") }
                        putJsonObject("quote") { put("type", "string") }
                    }
                    putJsonArray("required") {
                        if (story) add(JsonPrimitive("video"))
                        add(JsonPrimitive("text"))
                        add(JsonPrimitive("line"))
                        add(JsonPrimitive("quote"))
                    }
                    if (closed) put("additionalProperties", false)
                }
            }
        }
        putJsonArray("required") { add(JsonPrimitive("statements")) }
        if (closed) put("additionalProperties", false)
    }

    /** One statement as the service returned it, not yet checked. [video] is 1-based. */
    data class Claim(val text: String, val line: Int, val quote: String, val video: Int = 1)

    private val FENCE = Regex("""^\s*```[a-zA-Z]*\s*|\s*```\s*$""")

    /**
     * The claims of an answer text; null when it is not the JSON asked for. Lenient about what
     * a service without a strict mode may add: code fences, text around the object, "L12" or
     * "12" as a string for the line.
     */
    fun claims(answer: String): List<Claim>? {
        val text = FENCE.replace(answer.trim(), "")
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val root = runCatching { YtNewsHttp.json.parseToJsonElement(text.substring(start, end + 1)) }.getOrNull()
            as? JsonObject ?: return null
        val list = root["statements"] as? JsonArray ?: return null
        return list.mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val words = string(item["text"])?.trim().orEmpty()
            val quote = string(item["quote"])?.trim().orEmpty()
            val line = number(item["line"]) ?: return@mapNotNull null
            val video = item["video"]?.let { number(it) } ?: 1
            if (words.isEmpty() || quote.isEmpty()) null else Claim(words, line, quote, video)
        }
    }

    private fun string(element: JsonElement?): String? = (element as? JsonPrimitive)?.contentOrNull

    private fun number(element: JsonElement?): Int? {
        val primitive = element as? JsonPrimitive ?: return null
        primitive.intOrNull?.let { return it }
        return primitive.contentOrNull?.let { Regex("""\d+""").find(it)?.value?.toIntOrNull() }
    }

    /**
     * The statements of [claims] whose quote is found, each with the second of the line it was
     * found in; at most [max]. A claim of a video that does not exist is dropped.
     */
    fun verify(claims: List<Claim>, sources: List<YtNewsSource>, max: Int): List<YtNewsStatement> {
        val out = ArrayList<YtNewsStatement>()
        for (claim in claims) {
            if (out.size >= max) break
            val source = sources.getOrNull(claim.video - 1) ?: continue
            val line = locate(source.doc, claim.line, claim.quote) ?: continue
            val text = tidy(claim.text)
            if (text.isEmpty()) continue
            out += YtNewsStatement(text, source.videoId, source.doc.lineSecond(line))
        }
        return out
    }

    /**
     * The line (0-based) that holds [quote], searched at the cited [line] (1-based) and up to
     * [NEIGHBOURS] lines either side, also across the break between two lines; null when the
     * quote is not there or too short to prove anything. The closest good match wins.
     */
    fun locate(doc: YtNewsTranscriptDoc, line: Int, quote: String): Int? {
        val needle = normalize(stripMarks(quote)).take(MAX_QUOTE_CHARS).trim()
        if (needle.length < MIN_QUOTE_CHARS || needle.split(' ').size < MIN_QUOTE_WORDS) return null
        val cited = line - 1
        if (doc.lines == 0 || cited !in -NEIGHBOURS until doc.lines + NEIGHBOURS) return null
        val allowed = (needle.length * TOLERANCE).toInt()
        // single lines, nearest first, then pairs of neighbouring lines (a quote over a break)
        val order = (0..NEIGHBOURS).flatMap { d -> if (d == 0) listOf(cited) else listOf(cited - d, cited + d) }
            .filter { it in 0 until doc.lines }
        var best: Int? = null
        var bestDistance = Int.MAX_VALUE
        for (i in order) {
            val distance = distance(needle, normalize(doc.lineText(i)))
            if (distance == 0) return i
            if (distance < bestDistance) {
                best = i
                bestDistance = distance
            }
        }
        if (bestDistance <= allowed) return best
        for (i in (cited - NEIGHBOURS) until (cited + NEIGHBOURS)) {
            if (i < 0 || i + 1 >= doc.lines) continue
            val pair = normalize(doc.lineText(i) + " " + doc.lineText(i + 1))
            if (distance(needle, pair) <= allowed) return i
        }
        return null
    }

    private val LINE_MARK = Regex("""^\s*L\d+\s*[:.)-]?\s*""")
    private val STAMP = Regex("""\[\d{1,2}(?::\d{2}){1,2}]""")

    /** A quote without a line number or time stamp the service may have copied along. */
    private fun stripMarks(quote: String): String = STAMP.replace(LINE_MARK.replace(quote, ""), " ")

    private val MARKS = Regex("""\p{M}+""")
    private val NOT_WORD = Regex("""[^\p{L}\p{N}]+""")

    /**
     * Text for comparing: lower case, umlauts and ß spelled out (so "für" and "fuer" agree),
     * accents dropped, every run of punctuation and spaces one space.
     */
    fun normalize(text: String): String {
        var s = text.lowercase(Locale.ROOT)
            .replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss")
        s = MARKS.replace(Normalizer.normalize(s, Normalizer.Form.NFKD), "")
        return NOT_WORD.replace(s, " ").trim()
    }

    /** The fewest edits that turn [needle] into some part of [hay] (approximate substring match). */
    fun distance(needle: String, hay: String): Int {
        if (needle.isEmpty()) return 0
        if (hay.contains(needle)) return 0
        val m = needle.length
        var previous = IntArray(m + 1) { it }
        var current = IntArray(m + 1)
        var best = m
        for (j in 1..hay.length) {
            current[0] = 0
            val c = hay[j - 1]
            for (i in 1..m) {
                val substitute = previous[i - 1] + if (needle[i - 1] == c) 0 else 1
                val v = minOf(substitute, previous[i] + 1, current[i - 1] + 1)
                current[i] = v
            }
            if (current[m] < best) best = current[m]
            val swap = previous
            previous = current
            current = swap
        }
        return best
    }

    private val BULLET = Regex("""^\s*(?:[-–•*]|\d{1,2}[.)])\s+""")
    private val BOLD = Regex("""\*\*|__""")
    private val SPACES = Regex("""\s+""")

    private fun tidy(text: String): String {
        val words = SPACES.replace(BOLD.replace(BULLET.replace(text, ""), ""), " ").trim()
        return if (words.any { it.isLetterOrDigit() }) words else ""
    }
}
