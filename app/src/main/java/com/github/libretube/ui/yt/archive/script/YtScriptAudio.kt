package com.github.libretube.ui.yt.archive.script

import java.io.File
import java.util.Locale

/** The container of a stored sound file, told by its first bytes (the file has no extension). */
enum class YtAudioFormat(val mime: String, val extension: String) {
    MP4("audio/mp4", "m4a"),
    WEBM("audio/webm", "webm"),
    OGG("audio/ogg", "ogg"),
    MP3("audio/mpeg", "mp3"),
    WAV("audio/wav", "wav"),
    FLAC("audio/flac", "flac"),
    ;

    companion object {
        fun detect(file: File): YtAudioFormat? = runCatching {
            file.inputStream().use { input ->
                val head = ByteArray(16)
                val n = input.read(head)
                if (n <= 0) null else detect(head.copyOf(n))
            }
        }.getOrNull()

        fun detect(head: ByteArray): YtAudioFormat? {
            fun at(offset: Int, text: String) = head.size >= offset + text.length &&
                text.indices.all { head[offset + it] == text[it].code.toByte() }
            return when {
                at(4, "ftyp") -> MP4
                head.size >= 4 && head[0] == 0x1A.toByte() && head[1] == 0x45.toByte() &&
                    head[2] == 0xDF.toByte() && head[3] == 0xA3.toByte() -> WEBM
                at(0, "OggS") -> OGG
                at(0, "fLaC") -> FLAC
                at(0, "RIFF") && at(8, "WAVE") -> WAV
                at(0, "ID3") -> MP3
                head.size >= 2 && head[0] == 0xFF.toByte() && (head[1].toInt() and 0xE0) == 0xE0 -> MP3
                else -> null
            }
        }
    }
}

/** One stretch of speech as an audio service returned it; [speaker] is the service's own label. */
data class YtAudioSegment(val start: Long, val end: Long?, val speaker: String?, val text: String)

/** A service's transcript: its segments and the names it gave its labels (unchecked). */
data class YtAudioResult(val segments: List<YtAudioSegment>, val names: Map<String, String?> = emptyMap())

/** An audio AI as the script sees it. */
interface YtScriptAudioAi {
    val label: String

    /** Largest file one request takes. */
    val maxBytes: Long

    /** Longest sound one request takes. */
    val maxSeconds: Long

    /** Whether the service names speakers itself (else a text request may find the names). */
    val namesSpeakers: Boolean

    fun accepts(format: YtAudioFormat): Boolean

    /** The diarised transcript of [audio]; null when the answer is unusable. Throws on errors. */
    suspend fun transcribe(audio: File, format: YtAudioFormat, durationSec: Long?, meta: YtScriptMeta): YtAudioResult?
}

/**
 * YT: turns a service's diarised transcript into the script. The service's labels become A, B, …
 * in order of first appearance, valid for this video only; nothing that identifies a voice is
 * kept beyond the label. A name stays only when it is said in the sound or written in the title
 * or description ([YtScriptCheck.name]). Pure Kotlin.
 */
object YtScriptAudioMap {
    /** Segments of one speaker closer than this become one paragraph. */
    private const val JOIN_GAP_MS = 1_500L
    private const val PARAGRAPH_MAX = 600

    /** A segment may start this long after the stated length (rounding, intro). */
    private const val LATE_MS = 60_000L

    /** The same words more often in a row than this: a loop of the model, dropped. */
    private const val MAX_REPEATS = 2

    private const val MAX_SPEAKERS = 26

    fun script(result: YtAudioResult, meta: YtScriptMeta, durationSec: Long?): YtArchiveScript? {
        val limit = durationSec?.let { it * 1000 + LATE_MS }
        val segments = result.segments
            .map { it.copy(text = YtScriptInput.clean(it.text)) }
            .filter { it.text.isNotEmpty() && it.start >= 0 && (limit == null || it.start <= limit) }
            .sortedBy { it.start }
        // drop loops: the same words again and again
        val kept = ArrayList<YtAudioSegment>()
        var repeats = 0
        var previous: String? = null
        for (s in segments) {
            val norm = YtScriptCheck.words(s.text).joinToString(" ")
            repeats = if (norm == previous) repeats + 1 else 0
            previous = norm
            if (repeats >= MAX_REPEATS) continue
            kept += s
        }
        if (kept.isEmpty()) return null

        val labels = LinkedHashMap<String, String>()
        for (s in kept) {
            val raw = s.speaker?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            if (raw !in labels && labels.size < MAX_SPEAKERS) labels[raw] = ('A' + labels.size).toString()
        }
        val lines = ArrayList<YtScriptLine>()
        var lastEnd: Long? = null
        for (s in kept) {
            val speaker = s.speaker?.trim()?.let(labels::get)
            val last = lines.lastOrNull()
            val gap = s.start - (lastEnd ?: Long.MIN_VALUE / 2)
            if (last != null && last.speaker == speaker && speaker != null && gap in 0..JOIN_GAP_MS &&
                last.text.length + s.text.length < PARAGRAPH_MAX
            ) {
                lines[lines.lastIndex] = last.copy(text = last.text + " " + s.text)
            } else {
                lines += YtScriptLine(s.start, speaker, s.text)
            }
            lastEnd = s.end ?: s.start
        }

        val known = HashSet<String>()
        kept.forEach { known += YtScriptCheck.words(it.text) }
        known += YtScriptCheck.words(meta.title)
        meta.description?.let { known += YtScriptCheck.words(it) }
        val used = HashSet<String>()
        val speakers = labels.map { (raw, id) ->
            val name = YtScriptCheck.name(result.names[raw], known)?.takeIf { used.add(it.lowercase(Locale.ROOT)) }
            YtScriptSpeaker(id, name)
        }
        return YtArchiveScript(YtScriptSource.AUDIO, speakers, lines, YtScriptDialogue.spaced(meta.chapters))
    }

    /** Seconds or "h:mm:ss" / "m:ss" (with optional fraction) as milliseconds; null when neither. */
    fun clock(text: String?): Long? {
        val t = text?.trim()?.trim('[', ']') ?: return null
        if (t.isEmpty()) return null
        t.toDoubleOrNull()?.let { return (it * 1000).toLong().takeIf { ms -> ms >= 0 } }
        val parts = t.split(':')
        if (parts.size !in 2..3) return null
        val numbers = parts.map { it.toDoubleOrNull() ?: return null }
        val seconds = numbers.fold(0.0) { acc, v -> acc * 60 + v }
        return (seconds * 1000).toLong().takeIf { it >= 0 }
    }

    /** The names a text request may find for a script without names (speaker → name, unchecked). */
    fun withNames(script: YtArchiveScript, names: Map<String, String?>, meta: YtScriptMeta): YtArchiveScript {
        val known = HashSet<String>()
        script.lines.forEach { known += YtScriptCheck.words(it.text) }
        known += YtScriptCheck.words(meta.title)
        meta.description?.let { known += YtScriptCheck.words(it) }
        val used = script.speakers.mapNotNull { it.name?.lowercase(Locale.ROOT) }.toHashSet()
        return script.copy(
            speakers = script.speakers.map { s ->
                if (s.name != null) return@map s
                val name = YtScriptCheck.name(names[s.id], known)?.takeIf { used.add(it.lowercase(Locale.ROOT)) }
                s.copy(name = name)
            },
        )
    }
}
