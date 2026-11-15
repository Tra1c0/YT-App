package com.github.libretube.ui.yt.archive.script

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** A text AI as the script sees it: one request, the answer text (null when refused or empty). */
interface YtScriptTextAi {
    /** Short provider name for the diagnosis line. */
    val label: String

    /** One request; [schema] is the JSON the answer must follow where the service takes one. Throws on errors. */
    suspend fun complete(system: String, user: String, schema: JsonObject): String?
}

/**
 * YT: the dialogue script from the transcript. The transcript goes out in chunks with numbered,
 * timed lines; the AI answers which line each paragraph starts at, who speaks and the readable
 * text. Every paragraph is checked against the lines it covers ([YtScriptCheck]): an invented or
 * shortened paragraph is replaced by the plain words of its lines, a chunk with mostly bad
 * paragraphs (or no answer) becomes plain paragraphs without speakers. Times always come from the
 * transcript, never from the AI. Pure Kotlin; the AI is passed in.
 */
object YtScriptDialogue {
    /** Characters of transcript per request (about 2–3 thousand tokens; the answer is as long). */
    const val CHUNK_CHARS = 9_000

    /** Lines repeated before and after a chunk so a sentence or turn over the border is understood. */
    const val OVERLAP_LINES = 6

    /** Requests per script at most (about 3 hours of speech); the rest stays plain paragraphs. */
    const val MAX_CHUNKS = 24

    /** A paragraph start given as a time must be this close to a line's start. */
    const val NEAR_MS = 2_000L

    /** More repaired paragraphs than this share: the chunk is not trusted at all. */
    private const val MAX_REPAIRED_SHARE = 0.5

    private const val DESCRIPTION_CHARS = 1_500
    private const val MAX_SPEAKERS = 26
    private const val CHAPTER_GAP_MS = 30_000L
    private const val MAX_TITLE = 80

    /**
     * Lines [from] until [until] go out; the chunk is responsible for [ownFrom] until [ownUntil]
     * only (the rest is context).
     */
    data class Chunk(val index: Int, val from: Int, val ownFrom: Int, val ownUntil: Int, val until: Int)

    /** A paragraph placed at the transcript line [index] (0-based). */
    data class Placed(val index: Int, val speaker: String?, val text: String)

    data class AiLine(val line: Int?, val start: Long?, val speaker: String?, val text: String)
    data class AiChapter(val line: Int?, val start: Long?, val title: String)
    data class AiAnswer(val speakers: List<YtScriptSpeaker>, val lines: List<AiLine>, val chapters: List<AiChapter>)

    /** The checked result of one chunk. [failed]: the answer was unusable, all plain paragraphs. */
    data class ChunkResult(
        val placed: List<Placed>,
        val speakers: List<YtScriptSpeaker>,
        val chapters: List<YtScriptChapter>,
        val repaired: Int,
        val failed: Boolean,
    )

    /** How a script was made, for the diagnosis line. */
    data class Outcome(val script: YtArchiveScript, val calls: Int, val repaired: Int, val failedChunks: Int, val error: Throwable?)

    /**
     * The chunks of [cues]: about [maxChars] each, ending after a sentence where one ends in the
     * last fifth, each with [overlap] lines of context on both sides.
     */
    fun chunks(cues: List<YtScriptCue>, maxChars: Int = CHUNK_CHARS, overlap: Int = OVERLAP_LINES): List<Chunk> {
        val out = ArrayList<Chunk>()
        var i = 0
        while (i < cues.size) {
            var chars = 0
            var j = i
            while (j < cues.size && (j == i || chars + cost(cues[j]) <= maxChars)) {
                chars += cost(cues[j])
                j++
            }
            if (j < cues.size) {
                // end at a sentence end late in the chunk, so a turn is less often cut
                val earliest = i + ((j - i) * 4) / 5
                for (k in j - 1 downTo maxOf(earliest, i + 1)) {
                    if (cues[k].text.trimEnd().lastOrNull() in SENTENCE_END) {
                        j = k + 1
                        break
                    }
                }
            }
            out += Chunk(out.size, maxOf(0, i - overlap), i, j, minOf(cues.size, j + overlap))
            i = j
        }
        return out
    }

    private val SENTENCE_END = setOf('.', '?', '!', '…')

    /** Characters a cue takes in the prompt (text plus "L123 [1:02:03] "). */
    private fun cost(cue: YtScriptCue) = cue.text.length + 16

    const val SYSTEM = "Du machst aus dem automatischen Transkript eines Videos ein gut lesbares Skript. " +
        "Du erfindest nichts: jeder Absatz besteht aus den Wörtern des Transkripts, in derselben Sprache " +
        "und Reihenfolge. Du setzt nur Groß- und Kleinschreibung und Satzzeichen, berichtigst offensichtliche " +
        "Erkennungsfehler einzelner Wörter und lässt Füllwörter wie „äh“ weg. Nichts zusammenfassen, " +
        "nichts übersetzen, nichts auslassen, nichts hinzufügen."

    /** The user message for [chunk]; [assigned] holds the speakers of earlier lines (context). */
    fun prompt(
        chunk: Chunk,
        cues: List<YtScriptCue>,
        meta: YtScriptMeta,
        known: List<YtScriptSpeaker>,
        assigned: Map<Int, String?>,
        askChapters: Boolean,
    ): String {
        val out = StringBuilder()
        out.append("Titel: ").append(meta.title).append('\n')
        meta.description?.takeIf { it.isNotBlank() }?.let {
            out.append("Beschreibung: ").append(it.take(DESCRIPTION_CHARS).replace('\n', ' ')).append('\n')
        }
        if (known.isNotEmpty()) {
            out.append("Bisherige Sprecher: ")
            out.append(known.joinToString(", ") { s -> s.id + (s.name?.let { " ($it)" } ?: " (ohne Namen)") })
            out.append('\n')
        }
        out.append("\nTranskript (Zeilennummer, Startzeit, Text):\n")
        for (i in chunk.from until chunk.until) {
            out.append('L').append(i + 1).append(" [").append(YtArchiveScript.stamp(cues[i].start)).append("] ")
            if (i < chunk.ownFrom) assigned[i]?.let { out.append('(').append(it).append(") ") }
            out.append(cues[i].text).append('\n')
        }
        out.append('\n')
        out.append("Bearbeite nur die Zeilen L").append(chunk.ownFrom + 1).append(" bis L").append(chunk.ownUntil).append('.')
        if (chunk.from < chunk.ownFrom || chunk.until > chunk.ownUntil) {
            out.append(" Die Zeilen davor und danach sind nur Zusammenhang; gib sie nicht aus.")
        }
        out.append(
            "\n- Teile den Text in Absätze. Ein neuer Absatz beginnt bei jedem Sprecherwechsel und bei einem neuen Gedanken. " +
                "\"line\" ist die Nummer der Zeile, an der der Absatz beginnt (nur die Zahl, L12 → 12). Beginnt ein " +
                "Sprecherwechsel mitten in einer Zeile, nimm diese Zeile für beide Absätze.\n" +
                "- Jeder Absatz enthält lückenlos alle Wörter von seiner Zeile bis vor den nächsten Absatz.\n" +
                "- \"speaker\": A, B, C … in der Reihenfolge des ersten Auftretens; behalte die bisherigen Kennungen bei. " +
                "Unterscheide Sprecher am Inhalt (Frage und Antwort, Anrede, Vorstellung). Spricht nur eine Person, ist alles A.\n" +
                "- \"name\" in \"speakers\": nur wenn der Name im Transkript gesagt wird oder in der Beschreibung steht und " +
                "sicher zu diesem Sprecher gehört, sonst \"\". Keine Rollen wie Moderator oder Gast.\n",
        )
        if (askChapters) {
            out.append(
                "- \"chapters\": kurze Kapitelüberschriften (höchstens 8 Wörter, in der Sprache des Transkripts) an den " +
                    "Zeilen, an denen ein neues Thema beginnt; selten, nur bei echten Themenwechseln.\n",
            )
        } else {
            out.append("- \"chapters\": [] (das Video hat eigene Kapitel).\n")
        }
        out.append(
            "Antworte nur mit JSON der Form {\"speakers\":[{\"id\":\"A\",\"name\":\"\"}]," +
                "\"lines\":[{\"line\":1,\"speaker\":\"A\",\"text\":\"…\"}],\"chapters\":[{\"line\":1,\"title\":\"…\"}]}.",
        )
        return out.toString()
    }

    /** The JSON schema of the answer (strict: every field required, nothing else allowed). */
    fun schema(closed: Boolean = true): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("speakers") { array(closed, "id" to "string", "name" to "string") }
            putJsonObject("lines") { array(closed, "line" to "integer", "speaker" to "string", "text" to "string") }
            putJsonObject("chapters") { array(closed, "line" to "integer", "title" to "string") }
        }
        putJsonArray("required") {
            add(JsonPrimitive("speakers"))
            add(JsonPrimitive("lines"))
            add(JsonPrimitive("chapters"))
        }
        if (closed) put("additionalProperties", false)
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.array(closed: Boolean, vararg fields: Pair<String, String>) {
        put("type", "array")
        putJsonObject("items") {
            put("type", "object")
            putJsonObject("properties") { fields.forEach { (name, type) -> putJsonObject(name) { put("type", type) } } }
            putJsonArray("required") { fields.forEach { add(JsonPrimitive(it.first)) } }
            if (closed) put("additionalProperties", false)
        }
    }

    private val FENCE = Regex("""^\s*```[a-zA-Z]*\s*|\s*```\s*$""")

    /** The answer object of [text]: lenient about code fences, text around it, "L12" as a line. */
    fun answer(text: String?): AiAnswer? {
        if (text.isNullOrBlank()) return null
        val body = FENCE.replace(text.trim(), "")
        val start = body.indexOf('{')
        val end = body.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val root = runCatching { YtArchiveScript.JSON.parseToJsonElement(body.substring(start, end + 1)) }.getOrNull()
            as? JsonObject ?: return null
        val lines = (root["lines"] as? JsonArray ?: return null).mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val words = str(o["text"])?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            AiLine(lineNumber(o["line"]), millis(o["start"]), str(o["speaker"])?.trim()?.uppercase(), words)
        }
        val speakers = (root["speakers"] as? JsonArray).orEmpty().mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val id = str(o["id"])?.trim()?.uppercase()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            YtScriptSpeaker(id, str(o["name"])?.trim()?.takeIf { it.isNotEmpty() })
        }
        val chapters = (root["chapters"] as? JsonArray).orEmpty().mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val title = str(o["title"])?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            AiChapter(lineNumber(o["line"]), millis(o["start"]), title)
        }
        return AiAnswer(speakers, lines, chapters)
    }

    private fun str(e: JsonElement?): String? = (e as? JsonPrimitive)?.contentOrNull

    private fun lineNumber(e: JsonElement?): Int? {
        val p = e as? JsonPrimitive ?: return null
        p.longOrNull?.let { return it.toInt() }
        return p.contentOrNull?.let { Regex("""\d+""").find(it)?.value?.toIntOrNull() }
    }

    private fun millis(e: JsonElement?): Long? {
        val p = e as? JsonPrimitive ?: return null
        return p.longOrNull ?: p.doubleOrNull?.toLong()
    }

    private val SPEAKER_ID = Regex("""^[A-Z]$""")

    /** The line whose start is nearest to [ms], when within [NEAR_MS]. */
    fun nearest(cues: List<YtScriptCue>, ms: Long): Int? {
        if (cues.isEmpty()) return null
        var lo = 0
        var hi = cues.size - 1
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (cues[mid].start < ms) lo = mid + 1 else hi = mid
        }
        val best = listOf(lo - 1, lo).filter { it in cues.indices }.minBy { kotlin.math.abs(cues[it].start - ms) }
        return best.takeIf { kotlin.math.abs(cues[it].start - ms) <= NEAR_MS }
    }

    /**
     * The checked paragraphs of [chunk]. [known] are the speakers so far; [names] the normalised
     * words a name must come from.
     */
    fun check(
        answer: AiAnswer?,
        cues: List<YtScriptCue>,
        chunk: Chunk,
        known: List<YtScriptSpeaker>,
        names: Set<String>,
    ): ChunkResult {
        val plain = ChunkResult(paragraphs(cues, chunk.ownFrom, chunk.ownUntil), known, emptyList(), 0, failed = true)
        if (answer == null) return plain
        val own = chunk.ownFrom until chunk.ownUntil
        val placed = answer.lines.mapNotNull { line ->
            val index = line.line?.let { it - 1 } ?: line.start?.let { nearest(cues, it) } ?: return@mapNotNull null
            if (index !in own) return@mapNotNull null
            Placed(index, line.speaker?.takeIf { SPEAKER_ID.matches(it) }, line.text)
        }.sortedBy { it.index }
        if (placed.isEmpty()) return plain

        // the speakers: the known ones keep their id and name; a new one needs a free letter
        val speakers = known.toMutableList()
        val byId = speakers.associateBy { it.id }.toMutableMap()
        for (s in answer.speakers + placed.mapNotNull { p -> p.speaker?.let { YtScriptSpeaker(it, null) } }) {
            if (!SPEAKER_ID.matches(s.id)) continue
            val name = YtScriptCheck.name(s.name, names)?.takeIf { n -> speakers.none { it.name == n && it.id != s.id } }
            val old = byId[s.id]
            if (old == null) {
                if (speakers.size >= MAX_SPEAKERS) continue
                val added = YtScriptSpeaker(s.id, name)
                speakers += added
                byId[s.id] = added
            } else if (old.name == null && name != null) {
                val named = old.copy(name = name)
                speakers[speakers.indexOf(old)] = named
                byId[s.id] = named
            }
        }

        // group paragraphs that start at the same line (a turn inside one caption line)
        val groups = placed.groupBy { it.index }.toSortedMap()
        val starts = groups.keys.toList()
        val out = ArrayList<Placed>()
        var repaired = 0
        var total = 0
        if (starts.first() > chunk.ownFrom) {
            // the AI began late: the skipped lines stay as they were
            out += paragraphs(cues, chunk.ownFrom, starts.first())
            repaired++
            total++
        }
        for ((n, index) in starts.withIndex()) {
            val next = starts.getOrNull(n + 1) ?: chunk.ownUntil
            val group = groups.getValue(index)
            total++
            val range = text(cues, index, next)
            val window = text(cues, maxOf(0, index - 1), minOf(cues.size, next + 1))
            val verdict = YtScriptCheck.faithful(group.joinToString(" ") { it.text }, range, window)
            if (verdict.ok) {
                group.forEach { p -> out += p.copy(speaker = p.speaker?.takeIf { it in byId }) }
            } else {
                repaired++
                // words made up: the speaker is not trusted either; words left out: it is
                val speaker = if (verdict.noInvention) group.first().speaker?.takeIf { it in byId } else null
                out += paragraphs(cues, index, next).map { it.copy(speaker = speaker) }
            }
        }
        if (repaired > total * MAX_REPAIRED_SHARE) return plain.copy(repaired = repaired)

        val chapters = answer.chapters.mapNotNull { c ->
            val index = c.line?.let { it - 1 } ?: c.start?.let { nearest(cues, it) } ?: return@mapNotNull null
            if (index !in own) return@mapNotNull null
            val title = c.title.replace(Regex("""\s+"""), " ").trim().trim('#', '*', ' ').take(MAX_TITLE)
            if (title.isEmpty()) null else YtScriptChapter(cues[index].start, title)
        }
        return ChunkResult(out, speakers, chapters, repaired, failed = false)
    }

    private fun text(cues: List<YtScriptCue>, from: Int, until: Int) =
        (from until until).joinToString(" ") { cues[it].text }

    /** Pause that starts a new plain paragraph. */
    private const val PAUSE_MS = 2_500L
    private const val PARAGRAPH_MAX = 600
    private const val PARAGRAPH_SENTENCE = 300

    /**
     * Lines [from] until [until] as plain paragraphs without a speaker: a new one after a pause,
     * after a sentence once it is long enough, or when it gets too long.
     */
    fun paragraphs(cues: List<YtScriptCue>, from: Int, until: Int): List<Placed> {
        val out = ArrayList<Placed>()
        var start = -1
        val text = StringBuilder()
        for (i in from until minOf(until, cues.size)) {
            val cue = cues[i]
            if (start >= 0) {
                val gap = cue.start - cues[i - 1].end
                val sentence = text.lastOrNull() in SENTENCE_END
                if (gap >= PAUSE_MS || text.length >= PARAGRAPH_MAX || (sentence && text.length >= PARAGRAPH_SENTENCE)) {
                    out += Placed(start, null, text.toString())
                    start = -1
                    text.clear()
                }
            }
            if (start < 0) start = i else text.append(' ')
            text.append(cue.text)
        }
        if (start >= 0) out += Placed(start, null, text.toString())
        return out
    }

    /**
     * The script of [placed] paragraphs: starts from the transcript, speaker ids renamed A, B, …
     * in order of first appearance, only speakers that speak listed, chapters sorted and at
     * least half a minute apart.
     */
    fun assemble(
        source: YtScriptSource,
        placed: List<Placed>,
        cues: List<YtScriptCue>,
        speakers: List<YtScriptSpeaker>,
        chapters: List<YtScriptChapter>,
    ): YtArchiveScript {
        val names = speakers.associate { it.id to it.name }
        val rename = LinkedHashMap<String, String>()
        for (p in placed) p.speaker?.let { id -> if (id in names && id !in rename) rename[id] = ('A' + rename.size).toString() }
        val lines = placed.map { YtScriptLine(cues[it.index].start, it.speaker?.let(rename::get), it.text) }
        val listed = rename.map { (old, new) -> YtScriptSpeaker(new, names[old]) }
        return YtArchiveScript(source, listed, lines, spaced(chapters))
    }

    fun spaced(chapters: List<YtScriptChapter>): List<YtScriptChapter> {
        val out = ArrayList<YtScriptChapter>()
        for (c in chapters.sortedBy { it.start }) {
            val last = out.lastOrNull()
            if (last != null && c.start - last.start < CHAPTER_GAP_MS) continue
            out += c
        }
        return out
    }

    /** The normalised words a speaker's name may come from. */
    fun nameWords(cues: List<YtScriptCue>, meta: YtScriptMeta): Set<String> {
        val out = HashSet<String>()
        cues.forEach { out += YtScriptCheck.words(it.text) }
        out += YtScriptCheck.words(meta.title)
        meta.description?.let { out += YtScriptCheck.words(it) }
        return out
    }

    /**
     * The whole dialogue script: chunk by chunk through [ai] (null: plain paragraphs only, no
     * request). After the first failed request no further one is made; those chunks stay plain.
     * Cancellation is passed on, any other error is not.
     */
    suspend fun make(cues: List<YtScriptCue>, meta: YtScriptMeta, ai: YtScriptTextAi?): Outcome {
        val chunks = chunks(cues)
        val names = nameWords(cues, meta)
        val askChapters = meta.chapters.isEmpty()
        var known = emptyList<YtScriptSpeaker>()
        val placed = ArrayList<Placed>()
        val assigned = HashMap<Int, String?>()
        val aiChapters = ArrayList<YtScriptChapter>()
        var calls = 0
        var repaired = 0
        var failed = 0
        var error: Throwable? = null
        val schema = schema()
        for (chunk in chunks) {
            val usable = ai != null && error == null && chunk.index < MAX_CHUNKS
            val result = if (!usable) {
                ChunkResult(paragraphs(cues, chunk.ownFrom, chunk.ownUntil), known, emptyList(), 0, failed = ai != null)
            } else {
                val text = try {
                    calls++
                    ai!!.complete(SYSTEM, prompt(chunk, cues, meta, known, assigned, askChapters), schema)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    error = e
                    null
                }
                check(answer(text), cues, chunk, known, names)
            }
            if (result.failed) failed++
            repaired += result.repaired
            known = result.speakers
            placed += result.placed
            aiChapters += result.chapters
            for (p in result.placed) assigned[p.index] = p.speaker
        }
        val chapters = if (askChapters) aiChapters else meta.chapters
        val script = assemble(YtScriptSource.TRANSCRIPT, placed, cues, known, chapters)
        return Outcome(script, calls, repaired, failed, error)
    }
}
