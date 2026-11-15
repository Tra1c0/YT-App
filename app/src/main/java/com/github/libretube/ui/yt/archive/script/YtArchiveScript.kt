package com.github.libretube.ui.yt.archive.script

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/*
 * YT: the readable script of an archived video (YtArchiveFiles.script), as the archive core reads
 * it. One JSON object:
 *
 *   {"version":1,"source":"transcript"|"audio",
 *    "speakers":[{"id":"A","name":null|"Lisa"}],
 *    "lines":[{"start":ms,"speaker":"A"|null,"text":"…"}],
 *    "chapters":[{"start":ms,"title":"…"}]}
 *
 * A line is one paragraph of one speaker. Speakers are told apart within this one video only
 * (A, B, …); a name is set only when it is said in the video or written in its description.
 * "speaker" is null where it is not known (the plain fallback without the AI). Pure Kotlin.
 */

/** Where a script came from: the captions (text AI) or the stored sound (audio AI). */
enum class YtScriptSource(val id: String) {
    TRANSCRIPT("transcript"),
    AUDIO("audio"),
    ;

    companion object {
        fun of(id: String?): YtScriptSource? = entries.firstOrNull { it.id == id }
    }
}

data class YtScriptSpeaker(val id: String, val name: String?)

/** One paragraph; [start] in milliseconds from the video's start. */
data class YtScriptLine(val start: Long, val speaker: String?, val text: String)

data class YtScriptChapter(val start: Long, val title: String)

data class YtArchiveScript(
    val source: YtScriptSource,
    val speakers: List<YtScriptSpeaker>,
    val lines: List<YtScriptLine>,
    val chapters: List<YtScriptChapter>,
    val version: Int = VERSION,
) {
    /** Whether speakers were told apart (false for the plain paragraphs of the fallback). */
    val hasSpeakers: Boolean get() = speakers.isNotEmpty() && lines.any { it.speaker != null }

    fun toJson(): String = JSON.encodeToString(JsonElement.serializer(), toJsonObject())

    fun toJsonObject(): JsonObject = buildJsonObject {
        put("version", version)
        put("source", source.id)
        putJsonArray("speakers") {
            speakers.forEach { s ->
                add(
                    buildJsonObject {
                        put("id", s.id)
                        put("name", s.name?.let { JsonPrimitive(it) } ?: JsonNull)
                    },
                )
            }
        }
        putJsonArray("lines") {
            lines.forEach { l ->
                add(
                    buildJsonObject {
                        put("start", l.start)
                        put("speaker", l.speaker?.let { JsonPrimitive(it) } ?: JsonNull)
                        put("text", l.text)
                    },
                )
            }
        }
        putJsonArray("chapters") {
            chapters.forEach { c ->
                add(
                    buildJsonObject {
                        put("start", c.start)
                        put("title", c.title)
                    },
                )
            }
        }
    }

    /**
     * The script as plain text for reading or sharing: chapter headings, then
     * "[m:ss] Name: text" paragraphs; a new speaker label only where the speaker changes.
     * [unnamed] labels a speaker without a name (the caller passes its localised "Speaker A").
     */
    fun readable(unnamed: (String) -> String = { it }): String {
        val names = speakers.associate { it.id to (it.name ?: unnamed(it.id)) }
        val out = StringBuilder()
        val chapterQueue = chapters.sortedBy { it.start }.toMutableList()
        var last: String? = null
        for (line in lines) {
            while (chapterQueue.isNotEmpty() && chapterQueue.first().start <= line.start) {
                out.append("## ").append(chapterQueue.removeAt(0).title).append("\n\n")
                last = null
            }
            out.append('[').append(stamp(line.start)).append("] ")
            val speaker = line.speaker
            if (speaker != null && speaker != last) out.append(names[speaker] ?: speaker).append(": ")
            last = speaker
            out.append(line.text).append("\n\n")
        }
        return out.toString().trimEnd() + "\n"
    }

    companion object {
        const val VERSION = 1

        val JSON = Json { ignoreUnknownKeys = true; explicitNulls = false }

        /** "m:ss" or "h:mm:ss" of [ms]. */
        fun stamp(ms: Long): String {
            val s = (ms / 1000).coerceAtLeast(0)
            val h = s / 3600
            val m = (s % 3600) / 60
            val sec = s % 60
            return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
        }

        /**
         * The script in [text], or null when it is not one. Lenient where it costs nothing: lines
         * without text and chapters without a title are dropped, a speaker that is not listed
         * becomes unknown, lines and chapters are put in time order.
         */
        fun parse(text: String): YtArchiveScript? {
            val root = runCatching { JSON.parseToJsonElement(text) }.getOrNull() as? JsonObject ?: return null
            val version = (root["version"] as? JsonPrimitive)?.intOrNull ?: return null
            if (version < 1) return null
            val source = YtScriptSource.of(string(root["source"])) ?: return null
            val speakers = (root["speakers"] as? JsonArray).orEmpty().mapNotNull { e ->
                val o = e as? JsonObject ?: return@mapNotNull null
                val id = string(o["id"])?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                YtScriptSpeaker(id, string(o["name"])?.trim()?.takeIf { it.isNotEmpty() })
            }.distinctBy { it.id }
            val ids = speakers.map { it.id }.toSet()
            val lines = (root["lines"] as? JsonArray).orEmpty().mapNotNull { e ->
                val o = e as? JsonObject ?: return@mapNotNull null
                val start = millis(o["start"]) ?: return@mapNotNull null
                val words = string(o["text"])?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                YtScriptLine(start, string(o["speaker"])?.takeIf { it in ids }, words)
            }.sortedBy { it.start }
            val chapters = (root["chapters"] as? JsonArray).orEmpty().mapNotNull { e ->
                val o = e as? JsonObject ?: return@mapNotNull null
                val start = millis(o["start"]) ?: return@mapNotNull null
                val title = string(o["title"])?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                YtScriptChapter(start, title)
            }.sortedBy { it.start }
            return YtArchiveScript(source, speakers, lines, chapters, version)
        }

        private fun string(e: JsonElement?): String? = (e as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

        private fun millis(e: JsonElement?): Long? {
            val p = e as? JsonPrimitive ?: return null
            val v = p.longOrNull ?: p.doubleOrNull?.toLong() ?: return null
            return v.takeIf { it >= 0 }
        }
    }
}
