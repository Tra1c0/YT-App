package com.github.libretube.ui.yt.ssi

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.security.MessageDigest

/** SSI-3: how a passage relates to the claim of a query. */
enum class YtSsiRelation { SUPPORTS, REFUTES, QUALIFIES, QUOTES, DISCUSSES, UNCERTAIN }

/**
 * SSI-3: what kind of "against" a REFUTES is. A passage that only says the claim is wrong
 * [CONTRADICTS] it ("widerspricht"); only when it gives a reason or evidence of its own, and that
 * sentence was found in it, is it [REASONED] ("widerlegt mit Begründung"). Neither says who is right.
 */
enum class YtSsiOpposition { CONTRADICTS, REASONED }

/** SSI-3: how a statement is meant, where the passage shows it. */
enum class YtSsiContextFlag { IRONIC, HYPOTHETICAL, QUOTING_OTHER, CRITICAL }

/** SSI-3: why a relation is "uncertain". */
enum class YtSsiUnsure {
    /** The AI itself could not tell. */
    AI_SAID,

    /** The AI named a relation but gave no quote. */
    NO_QUOTE,

    /** The AI's quote is not in the passage: "nicht belegt". */
    QUOTE_NOT_FOUND,
}

/**
 * A verified quote: [quote] is the passage's own text (not the AI's spelling of it), from the
 * passage [segmentId], said at [ms] (as exact as [timing]).
 */
@Serializable
data class YtSsiEvidence(val quote: String, val segmentId: Long, val ms: Long, val timing: YtSsiTiming)

/**
 * SSI-3: a passage's relation to the claim. The rule this type keeps (see [YtSsiStanceRules.verify]):
 * every [relation] other than UNCERTAIN has an [evidence] quote that was found in the passage;
 * [opposition] is set for REFUTES only, REASONED only with a found [reason]; a [flags] entry
 * exists only with its own found quote. [claimed] is what the AI answered, kept so the screen can
 * say "KI sagte „stützt“, Zitat nicht gefunden".
 */
@Serializable
data class YtSsiStance(
    val segmentId: Long,
    val relation: YtSsiRelation,
    val evidence: YtSsiEvidence? = null,
    val opposition: YtSsiOpposition? = null,
    val reason: YtSsiEvidence? = null,
    val flags: Map<YtSsiContextFlag, YtSsiEvidence> = emptyMap(),
    val claimed: YtSsiRelation = relation,
    val unsure: YtSsiUnsure? = null,
    val cached: Boolean = false,
) {
    /** Whether it rests on a quote found in the passage. */
    val verified: Boolean get() = evidence != null
}

/**
 * YT (SSI-3): the stance request and the answer rules, pure. The passages go to the news
 * summaries' provider in batches of [BATCH], each with its words, a piece of the passage before
 * and the passage after ("Fortsetzung", where reasons are often given). Per passage the answer
 * names a relation, a verbatim quote, for "refutes" the sentence with the reason, and context
 * flags each with its own quote. Nothing in the answer is trusted: every quote is looked up in
 * the text that was sent ([YtSsiQuoteCheck]); a relation whose quote is not there becomes
 * UNCERTAIN, a "refutes" whose reason is not there (or is the same words as the quote) is only a
 * contradiction, a flag without a found quote is dropped. The raw answer is cached per claim,
 * passage text and model, and verified again whenever it is read.
 */
object YtSsiStanceRules {
    /** Passages per request. */
    const val BATCH = 6

    const val MAX_PASSAGE_CHARS = 900
    const val MAX_FOLLOW_CHARS = 450
    const val CONTEXT_CHARS = 200

    const val CACHE_MS = 30L * 24 * 60 * 60 * 1000

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }

    /** A piece of a unit: the text of passage [segmentId] as sent, with its times. */
    data class Part(val segmentId: Long, val text: String, val startMs: Long, val timing: YtSsiTiming, val marks: List<YtSsiMark>)

    /**
     * What is sent for one hit: [parts] (the passage, then its continuation) are what quotes may
     * come from; [before] is context only.
     */
    data class Unit(val id: String, val segmentId: Long, val title: String, val channel: String?, val parts: List<Part>, val before: String?) {
        /** The parts as one text, joined by a space (what quotes are looked up in). */
        val text: String get() = parts.joinToString(" ") { it.text }
    }

    /** The unit of [segment] with its neighbours; null for a chapter. */
    fun unit(id: String, segment: YtSsiSegment, title: String, channel: String?, before: YtSsiSegment?, after: YtSsiSegment?): Unit? {
        if (segment.kind != YtSsiSegmentKind.SPEECH) return null
        val parts = ArrayList<Part>()
        parts += Part(segment.id, segment.text.take(MAX_PASSAGE_CHARS), segment.startMs, segment.timing, segment.marks)
        if (after != null && after.kind == YtSsiSegmentKind.SPEECH) {
            parts += Part(after.id, after.text.take(MAX_FOLLOW_CHARS), after.startMs, after.timing, after.marks)
        }
        val context = before?.takeIf { it.kind == YtSsiSegmentKind.SPEECH }?.text?.takeLast(CONTEXT_CHARS)
        return Unit(id, segment.id, title, channel, parts, context)
    }

    /** One item of an answer, before any check. */
    @Serializable
    data class Raw(
        val relation: String = "",
        val quote: String = "",
        val reason: String = "",
        val flags: List<RawFlag> = emptyList(),
    )

    @Serializable
    data class RawFlag(val flag: String = "", val quote: String = "")

    val SYSTEM = """
        Du ordnest Passagen aus den Untertiteln von YouTube-Videos einer Behauptung zu. Du prüfst nicht, ob die Behauptung stimmt,
        und du entscheidest nicht, wer recht hat: du beschreibst nur, was die Passage selbst über die Behauptung sagt.
        Beziehungen ("relation"):
        - "supports": die Passage vertritt oder bestätigt die Behauptung.
        - "refutes": die Passage sagt, die Behauptung sei falsch.
        - "qualifies": die Passage schränkt die Behauptung ein, nennt Ausnahmen oder Bedingungen.
        - "quotes": die Passage gibt die Behauptung oder die Aussage eines anderen wieder, ohne eigene Haltung.
        - "discusses": die Passage behandelt das Thema ohne erkennbare Haltung.
        - "uncertain": die Haltung ist aus der Passage nicht sicher zu erkennen.
        "quote": 4 bis 25 aufeinanderfolgende Wörter, buchstabengetreu aus "Passage" oder "Fortsetzung" abgeschrieben (Originalsprache),
        die die Beziehung zeigen. Gibt es keine solche Stelle, ist "quote" leer und "relation" ist "uncertain".
        "reason": nur bei "refutes" und nur, wenn die Passage selbst einen Grund, Daten, eine Quelle oder ein Beispiel dafür nennt:
        der Satz mit dieser Begründung, buchstabengetreu und nicht dieselben Wörter wie "quote"; sonst leer. Ein bloßes "das stimmt nicht" ist keine Begründung.
        "flags": nur wenn eindeutig erkennbar: "ironic" (ironisch gemeint), "hypothetical" (als Annahme oder Gedankenspiel),
        "quoting" (gibt die Worte eines anderen wieder), "critical" (kritisch gegenüber der Behauptung oder ihrer Quelle);
        jedes mit einem eigenen wörtlichen Zitat aus der Passage. Im Zweifel keine Flags.
        Der Text "Davor" dient nur dem Verständnis; zitiere nicht daraus.
        Bewerte nur die gelieferten Passagen mit genau ihren IDs; erfinde keine Passagen, IDs, Zitate oder Inhalte.
        Antworte nur mit JSON: {"items":[{"id":"p1","relation":"supports","quote":"…","reason":"","flags":[]}]}.
    """.trimIndent()

    fun user(claim: String, query: String, units: List<Unit>): String = buildString {
        append("Behauptung: ").append(claim.trim())
        if (YtSsiText.fold(query) != YtSsiText.fold(claim)) append("\n(Suchanfrage: ").append(query.trim()).append(")")
        append("\n")
        for (u in units) {
            append("\n### ").append(u.id).append(" (Video: ").append(u.title.take(120))
            u.channel?.let { append(", Kanal: ").append(it.take(60)) }
            append(")\n")
            u.before?.let { append("Davor: …").append(it.replace('\n', ' ')).append('\n') }
            append("Passage: ").append(u.parts.first().text.replace('\n', ' ')).append('\n')
            u.parts.getOrNull(1)?.let { append("Fortsetzung: ").append(it.text.replace('\n', ' ')).append("…\n") }
        }
    }

    private val RELATION_VALUES = listOf("supports", "refutes", "qualifies", "quotes", "discusses", "uncertain")
    private val FLAG_VALUES = listOf("ironic", "hypothetical", "quoting", "critical")

    /** Strict JSON schema: every key required, empty strings for "none". */
    fun schema(): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("items") {
                put("type", "array")
                putJsonObject("items") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("id") { put("type", "string") }
                        putJsonObject("relation") {
                            put("type", "string")
                            putJsonArray("enum") { RELATION_VALUES.forEach { add(it) } }
                        }
                        putJsonObject("quote") { put("type", "string") }
                        putJsonObject("reason") { put("type", "string") }
                        putJsonObject("flags") {
                            put("type", "array")
                            putJsonObject("items") {
                                put("type", "object")
                                putJsonObject("properties") {
                                    putJsonObject("flag") {
                                        put("type", "string")
                                        putJsonArray("enum") { FLAG_VALUES.forEach { add(it) } }
                                    }
                                    putJsonObject("quote") { put("type", "string") }
                                }
                                putJsonArray("required") {
                                    add("flag")
                                    add("quote")
                                }
                                put("additionalProperties", false)
                            }
                        }
                    }
                    putJsonArray("required") {
                        add("id")
                        add("relation")
                        add("quote")
                        add("reason")
                        add("flags")
                    }
                    put("additionalProperties", false)
                }
            }
        }
        putJsonArray("required") { add("items") }
        put("additionalProperties", false)
    }

    /**
     * The raw items of [answer] by segment id, only for the ids that were sent (the first answer
     * per id counts); null when nothing could be read. Tolerates code fences, a bare array and
     * other key names.
     */
    fun parse(answer: String?, units: List<Unit>): Map<Long, Raw>? {
        val text = answer?.trim() ?: return null
        val root = element(text) ?: return null
        val items: JsonArray = when (root) {
            is JsonArray -> root
            is JsonObject -> (root["items"] ?: root["results"] ?: root["passages"] ?: root["relations"]) as? JsonArray ?: return null
            else -> return null
        }
        val byId = units.associateBy { it.id.lowercase() }
        val out = LinkedHashMap<Long, Raw>()
        for (item in items) {
            val obj = item as? JsonObject ?: continue
            val id = obj["id"]?.let(::content)?.trim()?.lowercase()?.let { if (it.firstOrNull()?.isDigit() == true) "p$it" else it } ?: continue
            val unit = byId[id] ?: continue
            if (unit.segmentId in out) continue
            val relation = (obj["relation"] ?: obj["stance"] ?: obj["label"])?.let(::content)?.trim().orEmpty()
            val quote = (obj["quote"] ?: obj["evidence"])?.let(::content).orEmpty()
            val reason = (obj["reason"] ?: obj["reason_quote"] ?: obj["reasoning"])?.let(::content).orEmpty()
            val flags = (obj["flags"] as? JsonArray).orEmpty().mapNotNull { f ->
                when (f) {
                    is JsonObject -> RawFlag(f["flag"]?.let(::content).orEmpty(), f["quote"]?.let(::content).orEmpty())
                    else -> null
                }
            }
            out[unit.segmentId] = Raw(relation, quote, reason, flags)
        }
        return out.takeIf { it.isNotEmpty() }
    }

    /** The relation an answer's word means (English or German, any case); UNCERTAIN for anything else. */
    fun relationOf(word: String): YtSsiRelation {
        val w = YtSsiText.fold(word)
        return when {
            w.startsWith("support") || w.startsWith("stutz") || w.startsWith("bestatig") -> YtSsiRelation.SUPPORTS
            w.startsWith("refut") || w.startsWith("contradict") || w.startsWith("widerleg") || w.startsWith("widersp") -> YtSsiRelation.REFUTES
            w.startsWith("qualif") || w.startsWith("schrankt") || w.startsWith("einschr") -> YtSsiRelation.QUALIFIES
            w.startsWith("quot") || w.startsWith("zitier") -> YtSsiRelation.QUOTES
            w.startsWith("discuss") || w.startsWith("bespr") || w.startsWith("behandel") -> YtSsiRelation.DISCUSSES
            else -> YtSsiRelation.UNCERTAIN
        }
    }

    fun flagOf(word: String): YtSsiContextFlag? {
        val w = YtSsiText.fold(word)
        return when {
            w.startsWith("iron") -> YtSsiContextFlag.IRONIC
            w.startsWith("hypothe") -> YtSsiContextFlag.HYPOTHETICAL
            w.startsWith("quot") || w.startsWith("zitat") || w.startsWith("zitier") -> YtSsiContextFlag.QUOTING_OTHER
            w.startsWith("critic") || w.startsWith("krit") -> YtSsiContextFlag.CRITICAL
            else -> null
        }
    }

    /** [quote] found in [unit], as the passage's own words with their time; null when not there. */
    fun evidence(unit: Unit, quote: String?): YtSsiEvidence? {
        if (quote.isNullOrBlank()) return null
        val text = unit.text
        val range = YtSsiQuoteCheck.find(text, quote) ?: return null
        var offset = 0
        for (part in unit.parts) {
            val end = offset + part.text.length
            if (range.first < end) {
                val local = range.first - offset
                val ms = if (part.timing == YtSsiTiming.APPROX) part.startMs else YtSsiSegmenter.timeAt(part.marks, local, part.startMs)
                return YtSsiEvidence(text.substring(range.first, range.last + 1), part.segmentId, ms, part.timing)
            }
            offset = end + 1
        }
        return null
    }

    /**
     * The checked stance of [raw] for [unit] (the hard rule): no found quote, no relation.
     */
    fun verify(unit: Unit, raw: Raw, cached: Boolean = false): YtSsiStance {
        val claimed = relationOf(raw.relation)
        val evidence = evidence(unit, raw.quote)
        val flags = LinkedHashMap<YtSsiContextFlag, YtSsiEvidence>()
        for (f in raw.flags) {
            val flag = flagOf(f.flag) ?: continue
            if (flag in flags) continue
            evidence(unit, f.quote)?.let { flags[flag] = it }
        }
        if (claimed == YtSsiRelation.UNCERTAIN) {
            return YtSsiStance(unit.segmentId, YtSsiRelation.UNCERTAIN, evidence, flags = flags, claimed = claimed, unsure = YtSsiUnsure.AI_SAID, cached = cached)
        }
        if (evidence == null) {
            val why = if (raw.quote.isBlank()) YtSsiUnsure.NO_QUOTE else YtSsiUnsure.QUOTE_NOT_FOUND
            return YtSsiStance(unit.segmentId, YtSsiRelation.UNCERTAIN, null, flags = flags, claimed = claimed, unsure = why, cached = cached)
        }
        if (claimed != YtSsiRelation.REFUTES) {
            return YtSsiStance(unit.segmentId, claimed, evidence, flags = flags, claimed = claimed, cached = cached)
        }
        // a contradiction is not a refutation: only a found reason of its own makes it one
        val reason = raw.reason.takeIf { it.isNotBlank() && !YtSsiQuoteCheck.same(it, raw.quote) && !YtSsiQuoteCheck.same(it, evidence.quote) }
            ?.let { evidence(unit, it) }
            ?.takeIf { !YtSsiQuoteCheck.same(it.quote, evidence.quote) }
        return YtSsiStance(
            unit.segmentId,
            YtSsiRelation.REFUTES,
            evidence,
            opposition = if (reason != null) YtSsiOpposition.REASONED else YtSsiOpposition.CONTRADICTS,
            reason = reason,
            flags = flags,
            claimed = claimed,
            cached = cached,
        )
    }

    /**
     * The claim a query asks about: the words of its meaning without the asks for a refutation
     * or comparison ("widerlegen", "vergleiche", "Gegenargumente zu") and a leading "dass"/"ob".
     */
    fun claim(intent: YtSsiIntent): String {
        var text = intent.semanticText
        for (regex in ASK_WORDS) text = regex.replace(text, " ")
        text = text.replace(Regex("""\s+"""), " ").trim().trim(',', '.', ';', ':', '?', '!').trim()
        text = LEADING.replace(text, "").trim()
        return text.ifEmpty { intent.semanticText.ifEmpty { intent.raw } }
    }

    private val ASK_WORDS = listOf(
        Regex("""\b(die|welche|was)\s+(widerleg\w*|widersprech\w*|widerspricht|entkr(ä|a)ft\w*)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(widerleg\w*|gegenargument\w*|entkr(ä|a)ft\w*|debunk\w*|refut\w*|counter\s*argument\w*|rebut\w*)(\s+(zu|zur|zum|gegen|to|against|of))?\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(widersp(r|ru)ch\w*|widerspricht|widersprochen|contradict\w*)(\s+(zu|zur|zum|to))?\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(vergleich\w*|compar\w*)(\s+(die|the))?(\s+(aussagen|statements|positionen|positions))?(\s+(zu|zur|zum|about|on|of))?\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(im laufe der (zeit|jahre)|over time|timeline)\b""", RegexOption.IGNORE_CASE),
    )
    private val LEADING = Regex("""^(dass|daß|ob|that|whether)\s+""", RegexOption.IGNORE_CASE)

    // ---- cache ----

    /** The cache key of one passage's raw answer: the folded claim, the passage, its text and the model. */
    fun cacheKey(claim: String, unit: Unit, model: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(
            (YtSsiText.fold(claim) + "\n" + unit.segmentId + "\n" + unit.text + "\n" + model).toByteArray(),
        )
        return "st|" + digest.joinToString("") { "%02x".format(it) }.take(40)
    }

    fun encode(raw: Raw): String = json.encodeToString(Raw.serializer(), raw)

    fun decode(text: String?): Raw? = text?.let { runCatching { json.decodeFromString(Raw.serializer(), it) }.getOrNull() }

    fun fresh(storedAt: Long, now: Long): Boolean = now - storedAt in 0 until CACHE_MS

    // ---- budget ----

    /**
     * How many requests one analysis may make, and which units go into them: the first
     * [perSearch] × [BATCH] of [units] that are not cached, in batches of [BATCH].
     */
    fun batches(units: List<Unit>, cached: Set<Long>, perSearch: Int): List<List<Unit>> =
        units.filter { it.segmentId !in cached }.take(perSearch.coerceAtLeast(0) * BATCH).chunked(BATCH)

    private fun element(text: String): JsonElement? {
        val obj = text.indexOf('{').let { s -> if (s < 0) null else text.lastIndexOf('}').takeIf { it > s }?.let { text.substring(s, it + 1) } }
        val arr = text.indexOf('[').let { s -> if (s < 0) null else text.lastIndexOf(']').takeIf { it > s }?.let { text.substring(s, it + 1) } }
        val first = if (obj != null && arr != null && text.indexOf('[') < text.indexOf('{')) listOf(arr, obj) else listOfNotNull(obj, arr)
        for (candidate in first) {
            runCatching { json.parseToJsonElement(candidate) }.getOrNull()?.let { return it }
        }
        return null
    }

    private fun content(e: JsonElement): String? = (e as? JsonPrimitive)?.contentOrNull
}
