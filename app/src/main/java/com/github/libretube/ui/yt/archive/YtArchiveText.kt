package com.github.libretube.ui.yt.archive

import com.github.libretube.ui.yt.news.YtNewsCue
import com.github.libretube.ui.yt.news.YtNewsTranscriptDoc
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * YT: the text side of an archived video, free of Android: the transcript file
 * ([YtArchiveFiles.transcript], JSON lines {"start":ms,"end":ms,"text":"…"} gzipped), the script
 * file the script part writes ([YtArchiveFiles.script], read leniently) and the plain text the
 * reading screen and the export show.
 */
object YtArchiveText {
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }

    /** One caption line with its times in milliseconds. */
    @Serializable
    data class Line(val start: Long, val end: Long, val text: String)

    /** The longest a line is held when the next one is far away. */
    private const val MAX_LINE_MS = 8_000L

    /** Caption cues (start only) → lines that end where the next starts (at most [MAX_LINE_MS] later). */
    fun lines(cues: List<YtNewsCue>): List<Line> {
        val sorted = cues.filter { it.text.isNotBlank() }.sortedBy { it.startSec }
        return sorted.mapIndexed { i, cue ->
            val start = (cue.startSec * 1000).toLong()
            val next = sorted.getOrNull(i + 1)?.let { (it.startSec * 1000).toLong() }
            val end = (next ?: (start + MAX_LINE_MS)).coerceIn(start, start + MAX_LINE_MS)
            Line(start, end, cue.text.trim())
        }
    }

    fun write(lines: List<Line>, file: File) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        GZIPOutputStream(tmp.outputStream()).bufferedWriter().use { out ->
            for (line in lines) {
                out.write(json.encodeToString(Line.serializer(), line))
                out.write("\n")
            }
        }
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }

    fun read(file: File): List<Line> {
        if (!file.isFile) return emptyList()
        return runCatching {
            GZIPInputStream(file.inputStream()).bufferedReader().useLines { seq ->
                seq.filter { it.isNotBlank() }
                    .mapNotNull { runCatching { json.decodeFromString(Line.serializer(), it) }.getOrNull() }
                    .toList()
            }
        }.getOrDefault(emptyList())
    }

    // ---- the script of the script part ----

    @Serializable
    data class Speaker(val id: String, val name: String? = null)

    @Serializable
    data class ScriptLine(val start: Long = 0, val speaker: String? = null, val text: String = "")

    @Serializable
    data class ScriptChapter(val start: Long = 0, val title: String = "")

    @Serializable
    data class Script(
        val speakers: List<Speaker> = emptyList(),
        val lines: List<ScriptLine> = emptyList(),
        val chapters: List<ScriptChapter> = emptyList(),
    )

    /** The script in [file], or null when there is none or it cannot be read. */
    fun readScript(file: File): Script? {
        if (!file.isFile) return null
        return runCatching { json.decodeFromString(Script.serializer(), file.readText()) }.getOrNull()
            ?.takeIf { it.lines.any { line -> line.text.isNotBlank() } }
    }

    fun parseScript(text: String): Script? =
        runCatching { json.decodeFromString(Script.serializer(), text) }.getOrNull()

    // ---- reading text ----

    /** The words the reading text needs from the app (German or English). */
    data class Labels(
        val chapters: String,
        val transcript: String,
        val script: String,
        val none: String,
        /** "Sprecher A" for a speaker without a name. */
        val speaker: (String) -> String,
    )

    /**
     * The text to read: title, channel, description, chapters, then the script with speakers if
     * there is one, else the transcript; every line with its "[m:ss]" stamp.
     */
    fun render(meta: YtArchiveMeta, transcript: List<Line>, script: Script?, labels: Labels): String = buildString {
        appendLine(meta.title)
        meta.channelName?.takeIf { it.isNotBlank() }?.let { appendLine(it) }
        meta.description?.trim()?.takeIf { it.isNotEmpty() }?.let {
            appendLine()
            appendLine(it)
        }
        val chapters = script?.chapters?.takeIf { it.isNotEmpty() }?.map { it.start / 1000 to it.title }
            ?: meta.chapters.map { it.start / 1000 to it.title }
        if (chapters.isNotEmpty()) {
            appendLine()
            appendLine(labels.chapters)
            for ((start, title) in chapters) appendLine("[${YtNewsTranscriptDoc.stamp(start)}] $title")
        }
        appendLine()
        if (script != null) {
            appendLine(labels.script)
            val names = script.speakers.associate { it.id to (it.name?.takeIf { n -> n.isNotBlank() } ?: labels.speaker(it.id)) }
            var last: String? = null
            for (line in script.lines) {
                if (line.text.isBlank()) continue
                val who = line.speaker?.let { names[it] ?: labels.speaker(it) }
                val stamp = "[${YtNewsTranscriptDoc.stamp(line.start / 1000)}]"
                // the speaker is named when it changes, as in a play
                if (who != null && who != last) appendLine("$stamp $who: ${line.text.trim()}") else appendLine("$stamp ${line.text.trim()}")
                last = who
            }
        } else if (transcript.isNotEmpty()) {
            appendLine(labels.transcript)
            for ((start, words) in YtNewsTranscriptDoc.passages(transcript.map { YtNewsCue(it.start / 1000.0, it.text) })) {
                appendLine("[${YtNewsTranscriptDoc.stamp(start)}] $words")
            }
        } else {
            appendLine(labels.none)
        }
    }.trimEnd()
}
