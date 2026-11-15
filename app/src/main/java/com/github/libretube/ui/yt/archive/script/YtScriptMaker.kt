package com.github.libretube.ui.yt.archive.script

import com.github.libretube.ui.yt.archive.YtArchiveCandidate
import com.github.libretube.ui.yt.archive.YtArchiveFiles
import com.github.libretube.ui.yt.archive.YtArchiveScriptSource
import java.io.File
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * YT: the script part of the archive. One script per video: a script from the sound is never
 * replaced, one from the transcript with speakers neither; the plain paragraphs (no AI, or the AI
 * failed) may be tried again ([YtScriptLedger.MAX_TRIES]). One run at a time. Never throws
 * (cancellation is passed on). Pure Kotlin: provider, store and day are passed in.
 */
class YtScriptMaker(
    private val textAi: () -> YtScriptTextAi?,
    private val audioAi: () -> YtScriptAudioAi?,
    private val store: YtScriptStore,
    private val today: () -> String,
) : YtArchiveScriptSource {
    private val lock = Mutex()

    override suspend fun dialogue(candidate: YtArchiveCandidate, files: YtArchiveFiles): Boolean =
        guarded { lock.withLock { makeDialogue(candidate, files) } }

    override suspend fun analyseAudio(candidate: YtArchiveCandidate, files: YtArchiveFiles): Boolean =
        guarded { lock.withLock { makeAudio(candidate, files) } }

    private suspend fun guarded(block: suspend () -> Boolean): Boolean = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        note("error ${e.javaClass.simpleName}")
        false
    }

    private suspend fun makeDialogue(candidate: YtArchiveCandidate, files: YtArchiveFiles): Boolean {
        val existing = read(files.script)
        if (existing != null && (existing.source == YtScriptSource.AUDIO || existing.hasSpeakers)) return true
        val cues = YtScriptInput.cues(files.transcript)
        if (cues.isEmpty()) {
            if (existing == null) note("text: no transcript")
            return existing != null
        }
        val meta = YtScriptInput.meta(candidate, files.meta)
        val ai = textAi()
        val ledger = store.load().on(today())
        val id = candidate.videoId
        if (ai == null || !ledger.mayTry(YtScriptLedger.Kind.DIALOGUE, id)) {
            // no AI (or tried enough): the plain paragraphs, without any request
            if (existing != null) return true
            write(files.script, YtScriptDialogue.make(cues, meta, null).script)
            note(if (ai == null) "text: plain (no provider)" else "text: plain (tries used)")
            return true
        }
        if (!ledger.underCap(YtScriptLedger.Kind.DIALOGUE)) {
            note("text: daily cap reached")
            return existing != null
        }
        store.save(ledger.spend(YtScriptLedger.Kind.DIALOGUE, id))
        val outcome = YtScriptDialogue.make(cues, meta, ai)
        write(files.script, outcome.script)
        note(
            "text ${if (outcome.script.hasSpeakers) "ok" else "plain"} (${ai.label}, ${outcome.calls} req, " +
                "${outcome.repaired} repaired, ${outcome.failedChunks} plain" +
                (outcome.error?.let { ", ${it.javaClass.simpleName}" } ?: "") + ")",
        )
        return true
    }

    private suspend fun makeAudio(candidate: YtArchiveCandidate, files: YtArchiveFiles): Boolean {
        val existing = read(files.script)
        if (existing?.source == YtScriptSource.AUDIO) return true
        val ai = audioAi()
        val skip = skipReason(candidate, files, ai)
        if (skip != null) {
            note("audio: $skip")
            return makeDialogue(candidate, files)
        }
        ai!!
        val format = YtAudioFormat.detect(files.audio)!!
        val id = candidate.videoId
        store.save(store.load().on(today()).spend(YtScriptLedger.Kind.AUDIO, id))
        val meta = YtScriptInput.meta(candidate, files.meta)
        val result = try {
            ai.transcribe(files.audio, format, candidate.durationSec, meta)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            note("audio failed (${ai.label}, ${e.javaClass.simpleName}${(e as? com.github.libretube.ui.yt.news.YtNewsHttp.Status)?.let { " ${it.code}" }.orEmpty()})")
            return makeDialogue(candidate, files)
        }
        var script = result?.let { YtScriptAudioMap.script(it, meta, candidate.durationSec) }
        if (script == null) {
            note("audio unusable (${ai.label})")
            return makeDialogue(candidate, files)
        }
        if (!ai.namesSpeakers && script.speakers.isNotEmpty()) script = named(script, meta)
        write(files.script, script)
        note("audio ok (${ai.label}, ${script.lines.size} lines, ${script.speakers.size} speakers)")
        return true
    }

    /** Why the sound is not sent, or null when it may be. */
    private fun skipReason(candidate: YtArchiveCandidate, files: YtArchiveFiles, ai: YtScriptAudioAi?): String? {
        if (ai == null) return "provider takes no sound"
        if (!files.audio.isFile) return "no sound stored"
        val format = YtAudioFormat.detect(files.audio) ?: return "unknown sound format"
        if (!ai.accepts(format)) return "${format.extension} not taken by ${ai.label}"
        if (files.audio.length() > ai.maxBytes) return "file too large for ${ai.label}"
        val seconds = candidate.durationSec
        if (seconds != null && seconds > YtScriptLedger.MAX_AUDIO_SECONDS) return "longer than 3 h"
        if (seconds != null && seconds > ai.maxSeconds) return "too long for ${ai.label}"
        val ledger = store.load().on(today())
        if (!ledger.mayTry(YtScriptLedger.Kind.AUDIO, candidate.videoId)) return "tries used"
        if (!ledger.underCap(YtScriptLedger.Kind.AUDIO)) return "daily cap reached"
        return null
    }

    /**
     * The names of the speakers, asked of the text AI with a few paragraphs of each (the audio
     * service gives labels only); unchanged when there is no text AI or it fails.
     */
    private suspend fun named(script: YtArchiveScript, meta: YtScriptMeta): YtArchiveScript {
        val ai = textAi() ?: return script
        val answer = try {
            ai.complete(NAMES_SYSTEM, namesPrompt(script, meta), namesSchema())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return script
        }
        return YtScriptAudioMap.withNames(script, names(answer), meta)
    }

    private fun note(text: String) {
        runCatching { store.save(store.load().on(today()).copy(last = text)) }
    }

    /** For the diagnosis page: today's use against the caps and the latest run. No video id. */
    fun diagnosis(): String {
        val ledger = runCatching { store.load().on(today()) }.getOrDefault(YtScriptLedger())
        return "text ${ledger.dialogues}/${YtScriptLedger.MAX_DIALOGUES_PER_DAY}, " +
            "audio ${ledger.audios}/${YtScriptLedger.MAX_AUDIO_PER_DAY} today" +
            (ledger.last?.let { "; last: $it" } ?: "")
    }

    companion object {
        fun read(file: File): YtArchiveScript? =
            runCatching { if (file.isFile) YtArchiveScript.parse(file.readText()) else null }.getOrNull()

        /** Writes [script] to [file] through a temporary file, so a reader never sees half of it. */
        fun write(file: File, script: YtArchiveScript) {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(script.toJson())
            if (!tmp.renameTo(file)) {
                file.delete()
                if (!tmp.renameTo(file)) throw java.io.IOException("rename failed")
            }
        }

        const val NAMES_SYSTEM = "Du ordnest den Sprechern eines Transkripts ihre Namen zu. Ein Name gilt nur, " +
            "wenn er im Transkript gesagt wird oder in der Beschreibung steht und sicher zu diesem Sprecher gehört. " +
            "Sonst bleibt der Name leer. Keine Rollen wie Moderator oder Gast, keine Vermutungen."

        private const val PER_SPEAKER = 4
        private const val PER_LINE = 400

        fun namesPrompt(script: YtArchiveScript, meta: YtScriptMeta): String {
            val out = StringBuilder()
            out.append("Titel: ").append(meta.title).append('\n')
            meta.description?.takeIf { it.isNotBlank() }?.let { out.append("Beschreibung: ").append(it.take(1_500).replace('\n', ' ')).append('\n') }
            out.append("\nAuszüge je Sprecher (erste Absätze und die Absätze direkt davor):\n")
            val wanted = LinkedHashSet<Int>()
            for (s in script.speakers) {
                script.lines.withIndex().filter { it.value.speaker == s.id }.take(PER_SPEAKER).forEach {
                    if (it.index > 0) wanted += it.index - 1
                    wanted += it.index
                }
            }
            for (i in wanted.sorted()) {
                val line = script.lines[i]
                out.append('[').append(YtArchiveScript.stamp(line.start)).append("] ").append(line.speaker ?: "?").append(": ")
                    .append(line.text.take(PER_LINE)).append('\n')
            }
            out.append("\nSprecher: ").append(script.speakers.joinToString(", ") { it.id }).append('\n')
            out.append("Antworte nur mit JSON der Form {\"speakers\":[{\"id\":\"A\",\"name\":\"\"}].")
            return out.toString()
        }

        fun namesSchema(): JsonObject = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("speakers") {
                    put("type", "array")
                    putJsonObject("items") {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("id") { put("type", "string") }
                            putJsonObject("name") { put("type", "string") }
                        }
                        putJsonArray("required") { add(JsonPrimitive("id")); add(JsonPrimitive("name")) }
                        put("additionalProperties", false)
                    }
                }
            }
            putJsonArray("required") { add(JsonPrimitive("speakers")) }
            put("additionalProperties", false)
        }

        /** id → name of a names answer (unchecked); empty when it is not the JSON asked for. */
        fun names(answer: String?): Map<String, String?> {
            val text = answer?.trim()?.removePrefix("```json")?.removePrefix("```")?.removeSuffix("```") ?: return emptyMap()
            val root = runCatching { YtArchiveScript.JSON.parseToJsonElement(text.substring(text.indexOf('{'), text.lastIndexOf('}') + 1)) }
                .getOrNull() as? JsonObject ?: return emptyMap()
            return (root["speakers"] as? JsonArray).orEmpty().mapNotNull { e ->
                val o = e as? JsonObject ?: return@mapNotNull null
                val id = (o["id"] as? JsonPrimitive)?.contentOrNull?.trim()?.uppercase() ?: return@mapNotNull null
                id to (o["name"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
            }.toMap()
        }
    }
}
