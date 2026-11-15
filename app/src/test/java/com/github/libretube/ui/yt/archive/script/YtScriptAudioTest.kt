package com.github.libretube.ui.yt.archive.script

import com.github.libretube.ui.yt.news.YtNewsProviderKind
import com.github.libretube.ui.yt.news.YtNewsProviderSetup
import java.io.File
import java.nio.file.Files
import java.util.Base64
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Sound formats, the mapping of each audio service's answer, and the request shapes. */
class YtScriptAudioTest {
    private val meta = YtScriptMeta("Interview", "Zu Gast: Jonas Weber", emptyList())

    @Test
    fun formatsByFirstBytes() {
        fun head(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }
        assertEquals(YtAudioFormat.MP4, YtAudioFormat.detect(byteArrayOf(0, 0, 0, 0x20) + "ftypM4A ".toByteArray()))
        assertEquals(YtAudioFormat.WEBM, YtAudioFormat.detect(head(0x1A, 0x45, 0xDF, 0xA3, 0x01)))
        assertEquals(YtAudioFormat.OGG, YtAudioFormat.detect("OggS\u0000".toByteArray()))
        assertEquals(YtAudioFormat.MP3, YtAudioFormat.detect("ID3\u0003".toByteArray()))
        assertEquals(YtAudioFormat.WAV, YtAudioFormat.detect("RIFF\u0000\u0000\u0000\u0000WAVE".toByteArray()))
        assertNull(YtAudioFormat.detect("hello world".toByteArray()))
    }

    @Test
    fun openAiDiarizedJson() {
        val answer = """{"task":"transcribe","duration":42.7,"text":"…","segments":[
            {"type":"transcript.text.segment","id":"seg_0","start":0.3,"end":4.1,"text":" Hallo und willkommen.","speaker":"A"},
            {"type":"transcript.text.segment","id":"seg_1","start":4.4,"end":9.0,"text":"Danke, ich bin Jonas.","speaker":"B"},
            {"type":"transcript.text.segment","id":"seg_2","start":9.2,"end":11.0,"text":"Schön, Jonas.","speaker":"A"}],
            "usage":{"type":"duration","seconds":43}}"""
        val result = YtScriptOpenAiAudio.parse(answer)!!
        assertEquals(3, result.segments.size)
        assertEquals(YtAudioSegment(300, 4_100, "A", "Hallo und willkommen."), result.segments[0])
        val script = YtScriptAudioMap.script(result, meta, 43)!!
        assertEquals(YtScriptSource.AUDIO, script.source)
        assertEquals(listOf("A", "B", "A"), script.lines.map { it.speaker })
        assertEquals(listOf(300L, 4_400L, 9_200L), script.lines.map { it.start })
        assertTrue(script.speakers.all { it.name == null })
        assertEquals(
            listOf("model" to "gpt-4o-transcribe-diarize", "response_format" to "diarized_json", "chunking_strategy" to "auto"),
            YtScriptOpenAiAudio.fields(),
        )
    }

    @Test
    fun mistralSegmentsWithSpeakerIds() {
        val answer = """{"model":"voxtral-mini-latest","text":"…","language":"de","segments":[
            {"text":"Guten Tag.","start":1.0,"end":2.0,"speaker_id":"speaker_2","type":"transcription_segment"},
            {"text":"Hallo.","start":2.5,"end":3.0,"speaker_id":"speaker_1","type":"transcription_segment"},
            {"text":"Wie geht es?","start":3.2,"end":4.0,"speaker_id":"speaker_1","type":"transcription_segment"}]}"""
        val script = YtScriptAudioMap.script(YtScriptMistralAudio.parse(answer)!!, meta, null)!!
        // labels in order of first appearance, joined while the same voice goes on
        assertEquals(listOf("A", "B"), script.speakers.map { it.id })
        assertEquals(listOf(YtScriptLine(1_000, "A", "Guten Tag."), YtScriptLine(2_500, "B", "Hallo. Wie geht es?")), script.lines)
        assertTrue(YtScriptMistralAudio.fields().contains("diarize" to "true"))
        assertNull(YtScriptMistralAudio.parse("""{"text":"no segments"}"""))
    }

    private fun gemini(text: String) =
        """{"candidates":[{"content":{"parts":[{"text":${kotlinx.serialization.json.JsonPrimitive(text)}}]},"finishReason":"STOP"}]}"""

    @Test
    fun geminiAnswerWithNamesFromTheDescription() {
        val inner = """{"speakers":[{"id":"A","name":"Moderator"},{"id":"B","name":"Jonas Weber"},{"id":"C","name":"Angela"}],
            "segments":[{"start":"0:00:01","speaker":"A","text":"Willkommen."},
                        {"start":"00:07","speaker":"B","text":"Danke für die Einladung."},
                        {"start":"0:01:10","speaker":"C","text":"Ich bin auch da."}]}"""
        val result = YtScriptGeminiAudio.parse(gemini(inner), null)!!
        val script = YtScriptAudioMap.script(result, meta, 120)!!
        assertEquals(listOf(1_000L, 7_000L, 70_000L), script.lines.map { it.start })
        // a role is no name, a name never said or written is dropped
        assertEquals(listOf(null, "Jonas Weber", null), script.speakers.map { it.name })
    }

    @Test
    fun geminiWindowsKeepOnlyTheirPart() {
        val inner = """{"speakers":[],"segments":[{"start":"0:29:50","speaker":"A","text":"vorher"},
            {"start":"0:30:05","speaker":"A","text":"drin"},{"start":"1:00:01","speaker":"A","text":"danach"}]}"""
        val result = YtScriptGeminiAudio.parse(gemini(inner), 1_800L until 3_600L, last = false)!!
        assertEquals(listOf("drin"), result.segments.map { it.text })
        val end = YtScriptGeminiAudio.parse(gemini(inner), 1_800L until 3_600L, last = true)!!
        assertEquals(listOf("drin", "danach"), end.segments.map { it.text })
        assertEquals(listOf(null), YtScriptGeminiAudio.windows(null))
        assertEquals(listOf(null), YtScriptGeminiAudio.windows(40 * 60))
        val windows = YtScriptGeminiAudio.windows(2 * 3600 + 5 * 60)
        assertEquals(4, windows.size)
        assertEquals(0L, windows.first()!!.first)
        assertEquals(2L * 3600 + 5 * 60, windows.last()!!.last + 1)
    }

    @Test
    fun geminiBlockedOrCutOff() {
        assertNull(YtScriptGeminiAudio.parse("""{"promptFeedback":{"blockReason":"SAFETY"}}""", null))
        assertNull(YtScriptGeminiAudio.parse(gemini("""{"speakers":[],"segments":[{"start":"0:01","speaker":"A","text":"abgeschn"""), null))
    }

    @Test
    fun geminiFilesApiObjects() {
        val up = YtScriptGeminiAudio.uploaded("""{"file":{"name":"files/abc","uri":"https://x/files/abc","state":"PROCESSING"}}""")!!
        assertEquals("files/abc", up.name)
        assertEquals("PROCESSING", up.state)
        assertEquals("ACTIVE", YtScriptGeminiAudio.state("""{"name":"files/abc","uri":"u","state":"ACTIVE"}""")!!.state)
    }

    @Test
    fun geminiInlineBodyStreamsTheFile() {
        val dir = Files.createTempDirectory("yt-audio").toFile()
        try {
            val audio = File(dir, "audio")
            val bytes = ByteArray(10_001) { (it * 7).toByte() }
            audio.writeBytes(bytes)
            val buffer = Buffer()
            YtScriptGeminiAudio.inlineBody("Prompt", audio, YtAudioFormat.OGG).writeTo(buffer)
            val root = YtArchiveScript.JSON.parseToJsonElement(buffer.readUtf8()).jsonObject
            val parts = root["contents"]!!.jsonArray[0].jsonObject["parts"]!!.jsonArray
            val inline = parts[0].jsonObject["inlineData"]!!.jsonObject
            assertEquals("audio/ogg", inline["mimeType"]!!.jsonPrimitive.content)
            assertTrue(Base64.getDecoder().decode(inline["data"]!!.jsonPrimitive.content).contentEquals(bytes))
            assertEquals("Prompt", parts[1].jsonObject["text"]!!.jsonPrimitive.content)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun loopsAndLateSegmentsAreDropped() {
        val segments = listOf(
            YtAudioSegment(0, 1_000, "x", "Hallo."),
            YtAudioSegment(5_000, 6_000, "x", "Danke danke."),
            YtAudioSegment(7_000, 8_000, "x", "Danke, danke!"),
            YtAudioSegment(9_000, 10_000, "x", "danke danke"),
            YtAudioSegment(11_000, 12_000, "x", "Danke danke."),
            YtAudioSegment(500_000, 501_000, "y", "nach dem Ende"),
        )
        val script = YtScriptAudioMap.script(YtAudioResult(segments), meta, 60)!!
        assertEquals(1, script.speakers.size)
        // the third and fourth repeat go, the segment far after the end too; near ones join
        assertEquals(listOf("Hallo.", "Danke danke. Danke, danke!"), script.lines.map { it.text })
        assertNull(YtScriptAudioMap.script(YtAudioResult(emptyList()), meta, 60))
    }

    @Test
    fun clockFormats() {
        assertEquals(65_000L, YtScriptAudioMap.clock("1:05"))
        assertEquals(3_723_500L, YtScriptAudioMap.clock("1:02:03.5"))
        assertEquals(12_500L, YtScriptAudioMap.clock("12.5"))
        assertNull(YtScriptAudioMap.clock("bald"))
    }

    @Test
    fun providersWithoutSoundHaveNoAudioAi() {
        assertNull(YtScriptAudioAis.of(YtNewsProviderSetup(YtNewsProviderKind.ANTHROPIC, "k", "m")))
        assertNull(YtScriptAudioAis.of(YtNewsProviderSetup(YtNewsProviderKind.COMPATIBLE, "", "m", "https://x")))
        assertEquals("gemini", YtScriptAudioAis.of(YtNewsProviderSetup(YtNewsProviderKind.GEMINI, "k", "m"))!!.label)
    }

    @Test
    fun textRequestShapes() {
        val schema = YtScriptDialogue.schema()
        val openAi = YtScriptOpenAiText.body("gpt-x", "sys", "user", schema)
        val format = openAi["text"]!!.jsonObject["format"]!!.jsonObject
        assertEquals("json_schema", format["type"]!!.jsonPrimitive.content)
        assertEquals("false", openAi["store"]!!.jsonPrimitive.content)
        // the schema dialect of one service goes without additionalProperties
        val open = YtScriptGemini.open(schema).toString()
        assertFalse(open.contains("additionalProperties"))
        assertTrue(schema.toString().contains("additionalProperties"))
        val chat = YtScriptChatText.body("m", "s", "u", schema, YtScriptChatText.Format.JSON)
        assertEquals("json_object", chat["response_format"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals(2, chat["messages"]!!.jsonArray.size)
        val gem = YtScriptGeminiText.body("s", "u", schema)
        assertTrue(gem["generationConfig"] is JsonObject)
    }
}
