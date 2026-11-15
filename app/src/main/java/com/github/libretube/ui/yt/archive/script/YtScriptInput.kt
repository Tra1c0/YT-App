package com.github.libretube.ui.yt.archive.script

import com.github.libretube.ui.yt.archive.YtArchiveCandidate
import java.io.File
import java.util.zip.GZIPInputStream
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/** One caption cue of the stored transcript; times in milliseconds. */
data class YtScriptCue(val start: Long, val end: Long, val text: String)

/** What the script may use besides the words: title, description and the video's own chapters. */
data class YtScriptMeta(val title: String, val description: String?, val chapters: List<YtScriptChapter>)

/**
 * YT: reads what the archive core stored for a video: the transcript (gzip JSON lines
 * {"start":ms,"end":ms,"text":"…"}) and meta.json (title, description, chapters). meta.json
 * belongs to the core; it is read leniently ("start" in ms or "seconds"/"time" in seconds).
 * Pure Kotlin (java.io only).
 */
object YtScriptInput {
    private val TAG = Regex("""<[^>]{1,40}>""")
    private val SPACES = Regex("""\s+""")

    /** The cues of [file]; empty when it is missing or unreadable. */
    fun cues(file: File): List<YtScriptCue> {
        if (!file.isFile) return emptyList()
        return runCatching {
            GZIPInputStream(file.inputStream().buffered()).bufferedReader(Charsets.UTF_8).useLines { parseCues(it) }
        }.getOrDefault(emptyList())
    }

    /**
     * The cues of JSON [lines], cleaned: markup tags removed, spaces collapsed, empty cues and
     * the repeats that rolling auto-captions produce dropped, in time order.
     */
    fun parseCues(lines: Sequence<String>): List<YtScriptCue> {
        val out = ArrayList<YtScriptCue>()
        for (raw in lines) {
            if (raw.isBlank()) continue
            val o = runCatching { YtArchiveScript.JSON.parseToJsonElement(raw) }.getOrNull() as? JsonObject ?: continue
            val start = number(o["start"]) ?: continue
            val end = number(o["end"]) ?: start
            val text = clean((o["text"] as? JsonPrimitive)?.contentOrNull ?: continue)
            if (text.isEmpty()) continue
            out += YtScriptCue(start, maxOf(start, end), text)
        }
        out.sortBy { it.start }
        val unique = ArrayList<YtScriptCue>(out.size)
        for (cue in out) {
            if (unique.lastOrNull()?.text == cue.text) continue
            unique += cue
        }
        return unique
    }

    fun clean(text: String): String = SPACES.replace(TAG.replace(text, " "), " ").trim()

    /** Title and description from the candidate, chapters (and missing text) from [metaFile]. */
    fun meta(candidate: YtArchiveCandidate, metaFile: File): YtScriptMeta {
        val text = runCatching { if (metaFile.isFile) metaFile.readText() else null }.getOrNull()
        val parsed = text?.let { parseMeta(it) }
        return YtScriptMeta(
            title = candidate.title.ifBlank { parsed?.title.orEmpty() },
            description = candidate.description?.takeIf { it.isNotBlank() } ?: parsed?.description,
            chapters = parsed?.chapters.orEmpty(),
        )
    }

    /** title, description and chapters of a meta.json text; null when it is not an object. */
    fun parseMeta(text: String): YtScriptMeta? {
        val root = runCatching { YtArchiveScript.JSON.parseToJsonElement(text) }.getOrNull() as? JsonObject ?: return null
        val chapters = (root["chapters"] as? JsonArray).orEmpty().mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val title = (o["title"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
                ?: return@mapNotNull null
            val start = number(o["start"]) ?: number(o["startMs"])
                ?: (number(o["seconds"]) ?: number(o["time"]) ?: number(o["startSec"]))?.times(1000)
                ?: return@mapNotNull null
            YtScriptChapter(start.coerceAtLeast(0), title)
        }.sortedBy { it.start }.distinctBy { it.start }
        return YtScriptMeta(
            title = (root["title"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
            description = (root["description"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() },
            chapters = chapters,
        )
    }

    private fun number(e: JsonElement?): Long? {
        val p = e as? JsonPrimitive ?: return null
        return p.longOrNull ?: p.doubleOrNull?.toLong()
    }
}
