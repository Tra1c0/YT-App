package com.github.libretube.ui.yt.archive.script

import com.github.libretube.ui.yt.news.YtNewsGemini
import com.github.libretube.ui.yt.news.YtNewsHttp
import com.github.libretube.ui.yt.news.YtNewsProviderKind
import com.github.libretube.ui.yt.news.YtNewsProviderSetup
import java.io.File
import java.io.FilterOutputStream
import java.io.IOException
import java.util.Base64
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink

/*
 * YT: the audio services with speaker separation, through the provider and key the user set for
 * the news summaries. Only the providers that take sound have one (OpenAI, Gemini, Mistral); the
 * others make the script from the transcript. The sound is sent as stored (it cannot be cut or
 * re-encoded on the phone without a codec pipeline, and labels of separate requests would not
 * match); a file over a service's limit falls back to the transcript. Free of Android.
 *
 * Sources (web search summaries; the service pages could not be opened from here): OpenAI's
 * speech-to-text guide and API reference (diarize model, "diarized_json", "chunking_strategy",
 * 25 MB), Mistral's diarization guide and known limitations ("diarize", segment timestamps,
 * "speaker_id"), Gemini's audio and Files API pages (inline data under 20 MB, resumable upload).
 */

object YtScriptAudioAis {
    /** The audio AI of [setup], or null when its provider takes no sound. */
    fun of(setup: YtNewsProviderSetup): YtScriptAudioAi? = when (setup.kind) {
        YtNewsProviderKind.OPENAI -> YtScriptOpenAiAudio(setup)
        YtNewsProviderKind.GEMINI -> YtScriptGeminiAudio(setup)
        YtNewsProviderKind.MISTRAL -> YtScriptMistralAudio(setup)
        YtNewsProviderKind.ANTHROPIC, YtNewsProviderKind.COMPATIBLE -> null
    }

    internal fun number(e: JsonElement?): Double? = (e as? JsonPrimitive)?.doubleOrNull
    internal fun string(e: JsonElement?): String? = (e as? JsonPrimitive)?.contentOrNull

    /** Segments {start, end, text, <speakerField>} with times in seconds. */
    internal fun segments(answer: String, speakerField: String): YtAudioResult? {
        val root = runCatching { YtNewsHttp.json.parseToJsonElement(answer) }.getOrNull() as? JsonObject ?: return null
        val list = root["segments"] as? JsonArray ?: return null
        val segments = list.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val start = number(o["start"]) ?: return@mapNotNull null
            val text = string(o["text"])?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            YtAudioSegment((start * 1000).toLong(), number(o["end"])?.let { (it * 1000).toLong() }, string(o[speakerField]), text)
        }
        return YtAudioResult(segments).takeIf { segments.isNotEmpty() }
    }

    /** A multipart form with [fields] and the sound as "file". */
    internal fun form(fields: List<Pair<String, String>>, audio: File, format: YtAudioFormat): MultipartBody =
        MultipartBody.Builder().setType(MultipartBody.FORM).apply {
            fields.forEach { (k, v) -> addFormDataPart(k, v) }
            addFormDataPart("file", "audio.${format.extension}", audio.asRequestBody(format.mime.toMediaType()))
        }.build()
}

/**
 * OpenAI: the transcription endpoint with its diarising model, answer "diarized_json" (segments
 * with start, end, speaker "A"/"B"…, text), server-side chunking (required for sound over 30 s).
 * An unknown model (it was announced to be retired) answers 404 or "model_not_found"; the caller
 * then falls back to the transcript.
 */
class YtScriptOpenAiAudio(
    private val setup: YtNewsProviderSetup,
    private val base: HttpUrl = "https://api.openai.com/v1/".toHttpUrl(),
    private val http: OkHttpClient = YtScriptHttp.audio,
) : YtScriptAudioAi {
    override val label = "openai"
    override val maxBytes = 25L * 1000 * 1000
    override val maxSeconds = MAX_SECONDS
    override val namesSpeakers = false

    override fun accepts(format: YtAudioFormat) = format in setOf(YtAudioFormat.MP4, YtAudioFormat.WEBM, YtAudioFormat.MP3, YtAudioFormat.WAV)

    override suspend fun transcribe(audio: File, format: YtAudioFormat, durationSec: Long?, meta: YtScriptMeta): YtAudioResult? {
        val request = Request.Builder()
            .url(YtNewsHttp.resolve(base, "audio/transcriptions"))
            .header("Authorization", "Bearer ${setup.key}")
            .post(YtScriptAudioAis.form(fields(), audio, format))
            .build()
        return parse(YtNewsHttp.await(request, http))
    }

    companion object {
        /** The diarising transcription model (the newer transcription models have no speakers). */
        const val MODEL = "gpt-4o-transcribe-diarize"

        /** Reported per-request length limit of that model (about 23 minutes; not confirmed by the docs). */
        const val MAX_SECONDS = 1_400L

        fun fields() = listOf(
            "model" to MODEL,
            "response_format" to "diarized_json",
            "chunking_strategy" to "auto",
        )

        fun parse(answer: String): YtAudioResult? = YtScriptAudioAis.segments(answer, "speaker")
    }
}

/**
 * Mistral: the transcription endpoint with "diarize" and segment timestamps (diarisation needs
 * them); every segment carries a "speaker_id".
 */
class YtScriptMistralAudio(
    private val setup: YtNewsProviderSetup,
    private val base: HttpUrl = "https://api.mistral.ai/v1/".toHttpUrl(),
    private val http: OkHttpClient = YtScriptHttp.audio,
) : YtScriptAudioAi {
    override val label = "mistral"

    /** The documented limit is 500 MB; the archive's sound is far smaller, so this is a sanity cap. */
    override val maxBytes = 300L * 1000 * 1000

    /** Documented as 60 minutes (newer reports say 3 hours; unconfirmed). */
    override val maxSeconds = 3_600L
    override val namesSpeakers = false

    override fun accepts(format: YtAudioFormat) = true

    override suspend fun transcribe(audio: File, format: YtAudioFormat, durationSec: Long?, meta: YtScriptMeta): YtAudioResult? {
        val request = Request.Builder()
            .url(YtNewsHttp.resolve(base, "audio/transcriptions"))
            .header("Authorization", "Bearer ${setup.key}")
            .post(YtScriptAudioAis.form(fields(), audio, format))
            .build()
        return parse(YtNewsHttp.await(request, http))
    }

    companion object {
        /** The "latest" alias of the small transcription model, which moves to new versions. */
        const val MODEL = "voxtral-mini-latest"

        fun fields() = listOf(
            "model" to MODEL,
            "diarize" to "true",
            "timestamp_granularities" to "segment",
        )

        fun parse(answer: String): YtAudioResult? = YtScriptAudioAis.segments(answer, "speaker_id")
    }
}

/**
 * Gemini: the user's model hears the sound and writes the diarised transcript as JSON. Small
 * files go inline (base64, streamed into the request); larger ones, or long sound that is asked
 * for in windows of [WINDOW_SECONDS], are uploaded once through the Files API and deleted right
 * after. Each window gets the speakers found so far, so the labels stay the same.
 */
class YtScriptGeminiAudio(
    private val setup: YtNewsProviderSetup,
    private val base: HttpUrl = YtScriptGemini.BASE,
    private val http: OkHttpClient = YtScriptHttp.audio,
    private val pollDelayMs: Long = 2_000,
) : YtScriptAudioAi {
    override val label = "gemini"
    override val maxBytes = 300L * 1000 * 1000
    override val maxSeconds = 3L * 3600
    override val namesSpeakers = true

    override fun accepts(format: YtAudioFormat) = true

    override suspend fun transcribe(audio: File, format: YtAudioFormat, durationSec: Long?, meta: YtScriptMeta): YtAudioResult? {
        val windows = windows(durationSec)
        val auth = YtScriptGemini.auth(setup)
        val url = YtScriptGemini.generateUrl(base, setup.model)
        if (windows.size == 1 && audio.length() <= INLINE_MAX) {
            val body = inlineBody(prompt(meta, null, emptyList()), audio, format)
            val answer = YtNewsHttp.await(Request.Builder().url(url).apply { auth.forEach { (k, v) -> header(k, v) } }.post(body).build(), http)
            return parse(answer, null)
        }
        val file = upload(audio, format, auth)
        try {
            val segments = ArrayList<YtAudioSegment>()
            val names = LinkedHashMap<String, String?>()
            for ((i, window) in windows.withIndex()) {
                val speakers = names.map { (id, name) -> YtScriptSpeaker(id, name) }
                val body = fileBody(prompt(meta, window, speakers), file.uri, format)
                val answer = YtNewsHttp.await(YtNewsHttp.post(url, auth, body), http)
                val part = parse(answer, window, last = i == windows.lastIndex) ?: return null
                segments += part.segments
                part.names.forEach { (id, name) -> if (names[id] == null) names[id] = name }
            }
            return YtAudioResult(segments, names).takeIf { segments.isNotEmpty() }
        } finally {
            withContext(NonCancellable) { runCatching { delete(file.name, auth) } }
        }
    }

    /** An uploaded file: its resource [name] ("files/…") and [uri] for the request. */
    data class Uploaded(val name: String, val uri: String)

    private suspend fun upload(audio: File, format: YtAudioFormat, auth: Map<String, String>): Uploaded {
        val root = base.newBuilder().encodedPath("/").build()
        val version = base.pathSegments.firstOrNull { it.isNotEmpty() } ?: "v1beta"
        val start = Request.Builder()
            .url(YtNewsHttp.resolve(root, "upload/$version/files"))
            .apply { auth.forEach { (k, v) -> header(k, v) } }
            .header("X-Goog-Upload-Protocol", "resumable")
            .header("X-Goog-Upload-Command", "start")
            .header("X-Goog-Upload-Header-Content-Length", audio.length().toString())
            .header("X-Goog-Upload-Header-Content-Type", format.mime)
            .post("""{"file":{"display_name":"audio"}}""".toRequestBody(JSON))
            .build()
        val target = exchange(start).second["x-goog-upload-url"] ?: throw IOException("no upload address")
        val send = Request.Builder()
            .url(target)
            .header("X-Goog-Upload-Offset", "0")
            .header("X-Goog-Upload-Command", "upload, finalize")
            .post(audio.asRequestBody(format.mime.toMediaType()))
            .build()
        var file = uploaded(exchange(send).first) ?: throw IOException("no file in answer")
        // sound is processed after the upload; it can be used once it is ACTIVE
        repeat(POLLS) {
            when (file.state) {
                "ACTIVE", null -> return Uploaded(file.name, file.uri)
                "FAILED" -> throw IOException("processing failed")
            }
            delay(pollDelayMs)
            val get = Request.Builder().url(YtNewsHttp.resolve(base, file.name)).apply { auth.forEach { (k, v) -> header(k, v) } }.get().build()
            file = state(exchange(get).first) ?: file
        }
        throw IOException("processing took too long")
    }

    private suspend fun delete(name: String, auth: Map<String, String>) {
        val request = Request.Builder().url(YtNewsHttp.resolve(base, name)).apply { auth.forEach { (k, v) -> header(k, v) } }.delete().build()
        exchange(request)
    }

    /** Runs [request]; body and headers of a 2xx answer, else throws [YtNewsHttp.Status]. */
    private suspend fun exchange(request: Request): Pair<String, Headers> = suspendCancellableCoroutine { cont ->
        val call = http.newCall(request)
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                val result = runCatching {
                    response.use {
                        val text = it.body.string()
                        if (!it.isSuccessful) throw YtNewsHttp.Status(it.code, text)
                        text to it.headers
                    }
                }
                if (cont.isActive) cont.resumeWith(result)
            }
        })
    }

    companion object {
        /** Raw bytes sent inline at most: base64 adds a third, the request must stay under 20 MB. */
        const val INLINE_MAX = 14L * 1000 * 1000

        /** Long sound is transcribed in windows of this length (one answer would get too long). */
        const val WINDOW_SECONDS = 30L * 60

        private const val POLLS = 30

        private val JSON = "application/json; charset=utf-8".toMediaType()

        private const val PLACEHOLDER = "@@YT_AUDIO_DATA@@"

        const val SYSTEM = "Du schreibst ein wörtliches Transkript einer Tonaufnahme mit Sprecherwechseln. " +
            "Nichts erfinden, nichts zusammenfassen, nichts übersetzen: schreibe genau, was gesagt wird, in der " +
            "gesprochenen Sprache, mit Satzzeichen. Stimmen unterscheidest du nur innerhalb dieser Aufnahme."

        /** The windows [from, until) in seconds; one open window when the length is unknown or short. */
        fun windows(durationSec: Long?): List<LongRange?> {
            if (durationSec == null || durationSec <= WINDOW_SECONDS + WINDOW_SECONDS / 2) return listOf(null)
            val out = ArrayList<LongRange?>()
            var from = 0L
            while (from < durationSec) {
                val until = if (durationSec - from <= WINDOW_SECONDS + WINDOW_SECONDS / 3) durationSec else from + WINDOW_SECONDS
                out += from until until
                from = until
            }
            return out
        }

        fun prompt(meta: YtScriptMeta, window: LongRange?, speakers: List<YtScriptSpeaker>): String {
            val out = StringBuilder()
            out.append("Titel: ").append(meta.title).append('\n')
            meta.description?.takeIf { it.isNotBlank() }?.let { out.append("Beschreibung: ").append(it.take(1_500).replace('\n', ' ')).append('\n') }
            if (window == null) {
                out.append("Transkribiere die ganze Aufnahme.\n")
            } else {
                out.append("Transkribiere nur den Abschnitt von ").append(YtArchiveScript.stamp(window.first * 1000))
                    .append(" bis ").append(YtArchiveScript.stamp((window.last + 1) * 1000)).append(".\n")
            }
            if (speakers.isNotEmpty()) {
                out.append("Bisher erkannte Sprecher: ")
                    .append(speakers.joinToString(", ") { s -> s.id + (s.name?.let { " ($it)" } ?: "") })
                    .append(". Behalte ihre Kennungen bei.\n")
            }
            out.append(
                "- Ein Absatz pro Sprecherbeitrag; lange Beiträge in Absätze von höchstens etwa fünf Sätzen.\n" +
                    "- \"start\": Startzeit des Absatzes ab Beginn der Aufnahme als H:MM:SS.\n" +
                    "- \"speaker\": A, B, C … für die verschiedenen Stimmen, in der Reihenfolge ihres ersten Auftretens.\n" +
                    "- \"name\" in \"speakers\": nur wenn der Name in der Aufnahme gesagt wird oder in der Beschreibung steht " +
                    "und sicher zu dieser Stimme gehört, sonst \"\". Keine Rollen wie Moderator oder Gast.\n" +
                    "- Musik, Geräusche und Stille ohne Sprache: kein Absatz.\n" +
                    "Antworte nur mit JSON der Form {\"speakers\":[{\"id\":\"A\",\"name\":\"\"}]," +
                    "\"segments\":[{\"start\":\"0:00:05\",\"speaker\":\"A\",\"text\":\"…\"}]}.",
            )
            return out.toString()
        }

        fun schema(): JsonObject = buildJsonObject {
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
                    }
                }
                putJsonObject("segments") {
                    put("type", "array")
                    putJsonObject("items") {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("start") { put("type", "string") }
                            putJsonObject("speaker") { put("type", "string") }
                            putJsonObject("text") { put("type", "string") }
                        }
                        putJsonArray("required") { add(JsonPrimitive("start")); add(JsonPrimitive("speaker")); add(JsonPrimitive("text")) }
                    }
                }
            }
            putJsonArray("required") { add(JsonPrimitive("speakers")); add(JsonPrimitive("segments")) }
        }

        /** The request with [media] as the sound part (inline data or an uploaded file). */
        fun body(prompt: String, media: JsonObject): JsonObject = buildJsonObject {
            putJsonObject("systemInstruction") {
                putJsonArray("parts") { add(buildJsonObject { put("text", SYSTEM) }) }
            }
            putJsonArray("contents") {
                add(
                    buildJsonObject {
                        put("role", "user")
                        putJsonArray("parts") {
                            add(media)
                            add(buildJsonObject { put("text", prompt) })
                        }
                    },
                )
            }
            putJsonObject("generationConfig") {
                put("responseMimeType", "application/json")
                put("responseJsonSchema", schema())
            }
        }

        fun fileBody(prompt: String, uri: String, format: YtAudioFormat): JsonObject =
            body(prompt, buildJsonObject { putJsonObject("fileData") { put("mimeType", format.mime); put("fileUri", uri) } })

        /** The inline request, with the file streamed into it as base64 (never whole in memory). */
        fun inlineBody(prompt: String, audio: File, format: YtAudioFormat): RequestBody {
            val media = buildJsonObject { putJsonObject("inlineData") { put("mimeType", format.mime); put("data", PLACEHOLDER) } }
            val text = YtNewsHttp.json.encodeToString(JsonElement.serializer(), body(prompt, media))
            val head = text.substringBefore(PLACEHOLDER)
            val tail = text.substringAfter(PLACEHOLDER)
            return object : RequestBody() {
                override fun contentType(): MediaType = JSON

                override fun writeTo(sink: BufferedSink) {
                    sink.writeUtf8(head)
                    // the encoder's close writes the padding; the sink itself stays open
                    val keepOpen = object : FilterOutputStream(sink.outputStream()) {
                        override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)
                        override fun close() = flush()
                    }
                    Base64.getEncoder().wrap(keepOpen).use { encoder -> audio.inputStream().use { it.copyTo(encoder) } }
                    sink.writeUtf8(tail)
                }
            }
        }

        /**
         * The segments of an answer; [window] drops segments outside the asked part (after the
         * [last] window anything later is kept). Null when blocked, cut off or not the JSON asked for.
         */
        fun parse(answer: String, window: LongRange?, last: Boolean = window == null): YtAudioResult? {
            val text = YtNewsGemini.text(answer) ?: return null
            val body = text.trim().removePrefix("```json").removePrefix("```").removeSuffix("```")
            val root = runCatching { YtNewsHttp.json.parseToJsonElement(body.substring(body.indexOf('{'), body.lastIndexOf('}') + 1)) }
                .getOrNull() as? JsonObject ?: return null
            val names = (root["speakers"] as? JsonArray).orEmpty().mapNotNull { e ->
                val o = e as? JsonObject ?: return@mapNotNull null
                val id = YtScriptAudioAis.string(o["id"])?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                id to YtScriptAudioAis.string(o["name"])?.trim()?.takeIf { it.isNotEmpty() }
            }.toMap()
            val segments = (root["segments"] as? JsonArray ?: return null).mapNotNull { e ->
                val o = e as? JsonObject ?: return@mapNotNull null
                val start = YtScriptAudioMap.clock(YtScriptAudioAis.string(o["start"])) ?: return@mapNotNull null
                // each window keeps only its own part, so a turn at the border is not there twice
                if (window != null && (start < window.first * 1000 || (!last && start >= (window.last + 1) * 1000))) {
                    return@mapNotNull null
                }
                val words = YtScriptAudioAis.string(o["text"])?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                YtAudioSegment(start, null, YtScriptAudioAis.string(o["speaker"])?.trim(), words)
            }
            return YtAudioResult(segments, names).takeIf { segments.isNotEmpty() }
        }

        /** A Files API object: under "file" (upload answer) or at the top (lookup). */
        data class FileState(val name: String, val uri: String, val state: String?)

        fun uploaded(answer: String): FileState? {
            val root = runCatching { YtNewsHttp.json.parseToJsonElement(answer) }.getOrNull() as? JsonObject ?: return null
            return state(((root["file"] as? JsonObject) ?: root).toString())
        }

        fun state(answer: String): FileState? {
            val o = runCatching { YtNewsHttp.json.parseToJsonElement(answer) }.getOrNull() as? JsonObject ?: return null
            val name = YtScriptAudioAis.string(o["name"]) ?: return null
            val uri = YtScriptAudioAis.string(o["uri"]) ?: return null
            return FileState(name, uri, YtScriptAudioAis.string(o["state"]))
        }
    }
}
