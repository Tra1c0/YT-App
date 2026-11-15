package com.github.libretube.ui.yt.archive.script

import com.github.libretube.ui.yt.archive.YtArchiveCandidate
import com.github.libretube.ui.yt.archive.YtArchiveFiles
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The script part as the archive core calls it: one script per video, caps, fallbacks. */
class YtScriptMakerTest {
    private val root = Files.createTempDirectory("yt-maker").toFile()

    @After
    fun clean() {
        root.deleteRecursively()
    }

    private class MemoryStore : YtScriptStore {
        var ledger = YtScriptLedger()
        override fun load() = ledger
        override fun save(ledger: YtScriptLedger) {
            this.ledger = ledger
        }
    }

    private class TextAi(var answer: String?) : YtScriptTextAi {
        override val label = "fake"
        var calls = 0
        override suspend fun complete(system: String, user: String, schema: JsonObject): String? {
            calls++
            return answer
        }
    }

    private class AudioAi(var result: YtAudioResult?, var error: Exception? = null) : YtScriptAudioAi {
        override val label = "fakeaudio"
        override val maxBytes = 1_000_000L
        override val maxSeconds = 3_600L
        override val namesSpeakers = false
        var calls = 0
        override fun accepts(format: YtAudioFormat) = format == YtAudioFormat.OGG
        override suspend fun transcribe(audio: File, format: YtAudioFormat, durationSec: Long?, meta: YtScriptMeta): YtAudioResult? {
            calls++
            error?.let { throw it }
            return result
        }
    }

    private val transcript = listOf(
        """{"start":0,"end":2000,"text":"hallo ich bin jonas"}""",
        """{"start":2000,"end":4000,"text":"und ich bin mara"}""",
    )
    private val goodAnswer = """{"speakers":[{"id":"A","name":"Jonas"},{"id":"B","name":"Mara"}],"lines":[
        {"line":1,"speaker":"A","text":"Hallo, ich bin Jonas."},{"line":2,"speaker":"B","text":"Und ich bin Mara."}],"chapters":[]}"""

    private fun video(id: String, seconds: Long? = 60, audio: Boolean = false, withTranscript: Boolean = true): Pair<YtArchiveCandidate, YtArchiveFiles> {
        val files = YtArchiveFiles(File(root, id).apply { mkdirs() })
        if (withTranscript) {
            GZIPOutputStream(files.transcript.outputStream()).bufferedWriter().use { w -> transcript.forEach { w.write(it + "\n") } }
        }
        if (audio) files.audio.writeBytes("OggS".toByteArray() + ByteArray(100))
        return YtArchiveCandidate(id, "c", "Kanal", "Titel $id", "Beschreibung", 0, seconds) to files
    }

    private fun maker(text: YtScriptTextAi?, audio: YtScriptAudioAi?, store: YtScriptStore = MemoryStore(), day: String = "2026-11-15") =
        YtScriptMaker({ text }, { audio }, store, { day })

    @Test
    fun dialogueWritesAScriptOnce() = runBlocking {
        val ai = TextAi(goodAnswer)
        val store = MemoryStore()
        val maker = maker(ai, null, store)
        val (c, f) = video("v1")
        assertTrue(maker.dialogue(c, f))
        val script = YtArchiveScript.parse(f.script.readText())!!
        assertEquals(listOf("Jonas", "Mara"), script.speakers.map { it.name })
        assertTrue(maker.dialogue(c, f))
        assertEquals(1, ai.calls)
        assertEquals(1, store.ledger.dialogues)
        assertTrue(maker.diagnosis().startsWith("text 1/20, audio 0/5 today; last: text ok (fake"))
    }

    @Test
    fun noTranscriptNoScript() = runBlocking {
        val (c, f) = video("v2", withTranscript = false)
        assertFalse(maker(TextAi(goodAnswer), null).dialogue(c, f))
        assertFalse(f.script.exists())
    }

    @Test
    fun withoutProviderPlainParagraphsWithoutRequest() = runBlocking {
        val (c, f) = video("v3")
        assertTrue(maker(null, null).dialogue(c, f))
        val script = YtArchiveScript.parse(f.script.readText())!!
        assertFalse(script.hasSpeakers)
        // a plain script may be made better once a provider is set
        val ai = TextAi(goodAnswer)
        assertTrue(maker(ai, null).dialogue(c, f))
        assertEquals(1, ai.calls)
        assertTrue(YtArchiveScript.parse(f.script.readText())!!.hasSpeakers)
    }

    @Test
    fun dailyCapAndTriesPerVideo() = runBlocking {
        val store = MemoryStore()
        store.ledger = YtScriptLedger(day = "2026-11-15", dialogues = YtScriptLedger.MAX_DIALOGUES_PER_DAY)
        val ai = TextAi(goodAnswer)
        val (c, f) = video("v4")
        assertFalse(maker(ai, null, store).dialogue(c, f))
        assertEquals(0, ai.calls)
        // a new day: the count starts again
        assertTrue(maker(ai, null, store, day = "2026-11-16").dialogue(c, f))
        assertEquals(1, ai.calls)

        // an AI that only answers nonsense: two tries, then plain without requests
        val bad = TextAi("kein json")
        val (c2, f2) = video("v5")
        val m = maker(bad, null, store, day = "2026-11-16")
        assertTrue(m.dialogue(c2, f2))
        assertTrue(m.dialogue(c2, f2))
        assertTrue(m.dialogue(c2, f2))
        assertEquals(YtScriptLedger.MAX_TRIES, bad.calls)
    }

    @Test
    fun audioScriptWithNamesFromTheTextAi() = runBlocking {
        val audio = AudioAi(
            YtAudioResult(
                listOf(
                    YtAudioSegment(0, 1_900, "spk1", "Hallo, ich bin Jonas."),
                    YtAudioSegment(2_000, 3_900, "spk0", "Und ich bin Mara."),
                ),
            ),
        )
        val text = TextAi("""{"speakers":[{"id":"A","name":"Jonas"},{"id":"B","name":"Peter"}]}""")
        val (c, f) = video("a1", audio = true)
        val m = maker(text, audio)
        assertTrue(m.analyseAudio(c, f))
        val script = YtArchiveScript.parse(f.script.readText())!!
        assertEquals(YtScriptSource.AUDIO, script.source)
        assertEquals(listOf(YtScriptSpeaker("A", "Jonas"), YtScriptSpeaker("B", null)), script.speakers)
        // an audio script is never redone, and a dialogue does not replace it
        assertTrue(m.analyseAudio(c, f))
        assertTrue(m.dialogue(c, f))
        assertEquals(1, audio.calls)
        assertEquals(1, text.calls)
    }

    @Test
    fun audioFallsBackToTheTranscript() = runBlocking {
        val text = TextAi(goodAnswer)
        // a provider without sound (text only)
        val (c, f) = video("a2", audio = true)
        assertTrue(maker(text, null).analyseAudio(c, f))
        assertEquals(YtScriptSource.TRANSCRIPT, YtArchiveScript.parse(f.script.readText())!!.source)

        // too long for audio
        val audio = AudioAi(YtAudioResult(listOf(YtAudioSegment(0, 1, "x", "hallo"))))
        val (c3, f3) = video("a3", seconds = 4 * 3600, audio = true)
        assertTrue(maker(text, audio).analyseAudio(c3, f3))
        assertEquals(0, audio.calls)

        // the service fails
        val failing = AudioAi(null, IOException("down"))
        val (c4, f4) = video("a4", audio = true)
        val store = MemoryStore()
        assertTrue(maker(text, failing, store).analyseAudio(c4, f4))
        assertEquals(1, failing.calls)
        assertEquals(1, store.ledger.audios)
        assertEquals(YtScriptSource.TRANSCRIPT, YtArchiveScript.parse(f4.script.readText())!!.source)

        // no sound stored and no transcript: nothing
        val (c5, f5) = video("a5", withTranscript = false)
        assertFalse(maker(text, audio).analyseAudio(c5, f5))
    }

    @Test
    fun audioDailyCap() = runBlocking {
        val store = MemoryStore()
        store.ledger = YtScriptLedger(day = "2026-11-15", audios = YtScriptLedger.MAX_AUDIO_PER_DAY)
        val audio = AudioAi(YtAudioResult(listOf(YtAudioSegment(0, 1, "x", "hallo"))))
        val (c, f) = video("a6", audio = true)
        assertTrue(maker(TextAi(goodAnswer), audio, store).analyseAudio(c, f))
        assertEquals(0, audio.calls)
    }

    @Test
    fun ledgerRoundTripAndForgetting() {
        var ledger = YtScriptLedger(day = "d")
        repeat(YtScriptLedger.MAX_TRACKED + 5) { ledger = ledger.spend(YtScriptLedger.Kind.DIALOGUE, "v$it") }
        assertEquals(YtScriptLedger.MAX_TRACKED, ledger.tries.size)
        assertEquals(0, ledger.tries(YtScriptLedger.Kind.DIALOGUE, "v0"))
        val parsed = YtScriptLedger.parse(ledger.copy(last = "x").toJson())
        assertEquals(ledger.copy(last = "x"), parsed)
        assertEquals(YtScriptLedger(), YtScriptLedger.parse("broken"))
        assertNull(YtScriptLedger.parse(null).last)
    }

    @Test
    fun namesAnswer() {
        assertEquals(mapOf("A" to "Lisa", "B" to null), YtScriptMaker.names("```json\n{\"speakers\":[{\"id\":\"a\",\"name\":\"Lisa\"},{\"id\":\"B\",\"name\":\"\"}]}\n```"))
        assertTrue(YtScriptMaker.names("nein").isEmpty())
    }
}
