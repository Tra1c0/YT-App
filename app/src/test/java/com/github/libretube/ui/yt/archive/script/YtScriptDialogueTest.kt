package com.github.libretube.ui.yt.archive.script

import java.io.IOException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Chunking, the check of AI answers against the transcript, fallbacks and the whole run. */
class YtScriptDialogueTest {
    private val cues = listOf(
        YtScriptCue(1_000, 3_000, "willkommen zu unserem gespräch heute ist lisa bei mir"),
        YtScriptCue(3_000, 6_000, "hallo danke für die einladung ich freue mich"),
        YtScriptCue(6_000, 9_000, "lisa du forschst seit jahren an bienen was hat dich dazu gebracht"),
        YtScriptCue(9_000, 12_000, "mein großvater hatte zwölf völker im garten"),
        YtScriptCue(12_000, 15_000, "da war ich als kind jeden sommer dabei"),
    )
    private val meta = YtScriptMeta("Bienen", "Ein Gespräch mit Lisa über Bienen", emptyList())
    private val whole = YtScriptDialogue.Chunk(0, 0, 0, cues.size, cues.size)
    private val names = YtScriptDialogue.nameWords(cues, meta)

    private val good = """{"speakers":[{"id":"A","name":""},{"id":"B","name":"Lisa"}],
        "lines":[
          {"line":1,"speaker":"A","text":"Willkommen zu unserem Gespräch! Heute ist Lisa bei mir."},
          {"line":2,"speaker":"B","text":"Hallo, danke für die Einladung, ich freue mich."},
          {"line":3,"speaker":"A","text":"Lisa, du forschst seit Jahren an Bienen. Was hat dich dazu gebracht?"},
          {"line":4,"speaker":"B","text":"Mein Großvater hatte zwölf Völker im Garten. Da war ich als Kind jeden Sommer dabei."}],
        "chapters":[{"line":3,"title":"Wie alles begann"}]}"""

    @Test
    fun goodAnswerIsKept() {
        val result = YtScriptDialogue.check(YtScriptDialogue.answer(good), cues, whole, emptyList(), names)
        assertFalse(result.failed)
        assertEquals(0, result.repaired)
        assertEquals(listOf(0, 1, 2, 3), result.placed.map { it.index })
        assertEquals(listOf("A", "B", "A", "B"), result.placed.map { it.speaker })
        assertEquals("Lisa", result.speakers.first { it.id == "B" }.name)
        assertEquals(listOf(YtScriptChapter(6_000, "Wie alles begann")), result.chapters)
        val script = YtScriptDialogue.assemble(YtScriptSource.TRANSCRIPT, result.placed, cues, result.speakers, result.chapters)
        // times come from the transcript
        assertEquals(listOf(1_000L, 3_000L, 6_000L, 9_000L), script.lines.map { it.start })
    }

    @Test
    fun fencedJsonAndStartTimesAreRead() {
        val fenced = "Hier ist das Skript:\n```json\n" +
            """{"speakers":[{"id":"A","name":""}],"lines":[{"start":1200,"speaker":"a","text":"Willkommen zu unserem Gespräch, heute ist Lisa bei mir. Hallo, danke für die Einladung, ich freue mich."},{"line":"L3","speaker":"A","text":"Lisa, du forschst seit Jahren an Bienen. Was hat dich dazu gebracht? Mein Großvater hatte zwölf Völker im Garten. Da war ich als Kind jeden Sommer dabei."}],"chapters":[]}""" +
            "\n```"
        val answer = YtScriptDialogue.answer(fenced)
        assertNotNull(answer)
        val result = YtScriptDialogue.check(answer, cues, whole, emptyList(), names)
        assertFalse(result.failed)
        assertEquals(listOf(0, 2), result.placed.map { it.index })
        assertEquals(listOf("A", "A"), result.placed.map { it.speaker })
    }

    @Test
    fun inventedWordsAreReplacedByTheTranscript() {
        val invented = """{"speakers":[{"id":"A","name":"Peter"},{"id":"B","name":"Lisa"}],
            "lines":[
              {"line":1,"speaker":"A","text":"Willkommen zu unserem Gespräch! Heute ist Lisa bei mir."},
              {"line":2,"speaker":"B","text":"Hallo, danke für die Einladung, ich freue mich."},
              {"line":3,"speaker":"A","text":"Lisa, du forschst seit Jahren an Bienen. Was hat dich dazu gebracht?"},
              {"line":4,"speaker":"B","text":"Ich habe den Nobelpreis gewonnen und lebe heute in Paris mit meinen Katzen."}],
            "chapters":[]}"""
        val result = YtScriptDialogue.check(YtScriptDialogue.answer(invented), cues, whole, emptyList(), names)
        assertFalse(result.failed)
        assertEquals(1, result.repaired)
        val last = result.placed.last()
        assertEquals("mein großvater hatte zwölf völker im garten da war ich als kind jeden sommer dabei", last.text)
        // made up words: the speaker of that paragraph is not trusted
        assertNull(last.speaker)
        // "Peter" is never said
        assertNull(result.speakers.first { it.id == "A" }.name)
    }

    @Test
    fun mostlyBadAnswerBecomesPlainParagraphs() {
        val bad = """{"speakers":[],"lines":[
            {"line":1,"speaker":"A","text":"Das Video handelt von Autos."},
            {"line":3,"speaker":"B","text":"Und von Flugzeugen und Schiffen."}],"chapters":[]}"""
        val result = YtScriptDialogue.check(YtScriptDialogue.answer(bad), cues, whole, emptyList(), names)
        assertTrue(result.failed)
        assertTrue(result.placed.all { it.speaker == null })
        val words = result.placed.joinToString(" ") { it.text }
        assertEquals(cues.joinToString(" ") { it.text }, words)
    }

    @Test
    fun noJsonIsNoAnswer() {
        assertNull(YtScriptDialogue.answer("Ich kann das nicht."))
        assertNull(YtScriptDialogue.answer("""{"speakers":[]}"""))
        val result = YtScriptDialogue.check(null, cues, whole, emptyList(), names)
        assertTrue(result.failed)
    }

    @Test
    fun linesOutsideTheChunkAreIgnored() {
        val chunk = YtScriptDialogue.Chunk(1, 0, 2, 4, 5)
        val answer = YtScriptDialogue.answer(
            """{"speakers":[],"lines":[{"line":1,"speaker":"A","text":"willkommen zu unserem gespräch heute ist lisa bei mir"},
               {"line":3,"speaker":"A","text":"Lisa, du forschst seit Jahren an Bienen. Was hat dich dazu gebracht?"},
               {"line":4,"speaker":"B","text":"Mein Großvater hatte zwölf Völker im Garten."},
               {"line":5,"speaker":"B","text":"Da war ich als Kind jeden Sommer dabei."}],"chapters":[]}""",
        )
        val result = YtScriptDialogue.check(answer, cues, chunk, emptyList(), names)
        assertEquals(listOf(2, 3), result.placed.map { it.index })
    }

    @Test
    fun chunksCoverEverythingOnceWithOverlap() {
        val many = (0 until 500).map { YtScriptCue(it * 2_000L, it * 2_000L + 1_900, "satz nummer $it mit ein paar worten mehr." ) }
        val chunks = YtScriptDialogue.chunks(many, maxChars = 2_000, overlap = 3)
        assertTrue(chunks.size > 5)
        assertEquals(0, chunks.first().ownFrom)
        assertEquals(many.size, chunks.last().ownUntil)
        chunks.zipWithNext().forEach { (a, b) -> assertEquals(a.ownUntil, b.ownFrom) }
        chunks.forEach { c ->
            assertTrue(c.from <= c.ownFrom && c.ownUntil <= c.until)
            if (c.index > 0) assertEquals(3, c.ownFrom - c.from)
            val chars = (c.ownFrom until c.ownUntil).sumOf { many[it].text.length + 16 }
            assertTrue(chars <= 2_000)
        }
    }

    @Test
    fun oneHugeCueIsStillAChunk() {
        val chunks = YtScriptDialogue.chunks(listOf(YtScriptCue(0, 1, "x".repeat(20_000))), maxChars = 1_000)
        assertEquals(1, chunks.size)
    }

    @Test
    fun plainParagraphsBreakAtPauses() {
        val list = listOf(
            YtScriptCue(0, 1_000, "eins."),
            YtScriptCue(1_000, 2_000, "zwei"),
            YtScriptCue(9_000, 10_000, "drei"),
        )
        val paragraphs = YtScriptDialogue.paragraphs(list, 0, list.size)
        assertEquals(listOf(0, 2), paragraphs.map { it.index })
        assertEquals("eins. zwei", paragraphs.first().text)
    }

    @Test
    fun speakersAreRenamedInOrderAndChaptersSpaced() {
        val placed = listOf(
            YtScriptDialogue.Placed(0, "C", "a"),
            YtScriptDialogue.Placed(1, "A", "b"),
            YtScriptDialogue.Placed(2, null, "c"),
        )
        val script = YtScriptDialogue.assemble(
            YtScriptSource.TRANSCRIPT,
            placed,
            cues,
            listOf(YtScriptSpeaker("A", "Lisa"), YtScriptSpeaker("B", null), YtScriptSpeaker("C", null)),
            listOf(YtScriptChapter(0, "x"), YtScriptChapter(10_000, "zu nah"), YtScriptChapter(40_000, "y")),
        )
        assertEquals(listOf(YtScriptSpeaker("A", null), YtScriptSpeaker("B", "Lisa")), script.speakers)
        assertEquals(listOf("A", "B", null), script.lines.map { it.speaker })
        assertEquals(listOf("x", "y"), script.chapters.map { it.title })
    }

    private class FakeAi(private val answers: List<String?>, private val failAt: Int = -1) : YtScriptTextAi {
        override val label = "fake"
        val prompts = ArrayList<String>()

        override suspend fun complete(system: String, user: String, schema: JsonObject): String? {
            prompts += user
            if (prompts.size - 1 == failAt) throw IOException("down")
            return answers.getOrNull(prompts.size - 1)
        }
    }

    @Test
    fun wholeRunWithGoodAnswer() = runBlocking {
        val ai = FakeAi(listOf(good))
        val outcome = YtScriptDialogue.make(cues, meta, ai)
        assertEquals(1, outcome.calls)
        assertTrue(outcome.script.hasSpeakers)
        assertEquals(listOf(YtScriptSpeaker("A", null), YtScriptSpeaker("B", "Lisa")), outcome.script.speakers)
        assertEquals(YtScriptSource.TRANSCRIPT, outcome.script.source)
        assertTrue(ai.prompts.single().contains("L1 [0:01] willkommen"))
        assertTrue(ai.prompts.single().contains("Ein Gespräch mit Lisa"))
    }

    @Test
    fun metaChaptersWinOverTheAi() = runBlocking {
        val own = meta.copy(chapters = listOf(YtScriptChapter(0, "Intro")))
        val ai = FakeAi(listOf(good))
        val outcome = YtScriptDialogue.make(cues, own, ai)
        assertEquals(listOf(YtScriptChapter(0, "Intro")), outcome.script.chapters)
        assertTrue(ai.prompts.single().contains("eigene Kapitel"))
    }

    @Test
    fun laterChunksKnowEarlierSpeakersAndAnErrorStopsRequests() = runBlocking {
        val many = (0 until 300).map { YtScriptCue(it * 2_000L, it * 2_000L + 1_900, "wort$it ".repeat(8).trim() + ".") }
        val chunks = YtScriptDialogue.chunks(many)
        assertTrue(chunks.size >= 3)
        // first chunk: all speaker A, faithful
        val first = chunks[0]
        val lines = (first.ownFrom until first.ownUntil).joinToString(",") { i ->
            """{"line":${i + 1},"speaker":"A","text":"${many[i].text}"}"""
        }
        val ai = FakeAi(listOf("""{"speakers":[{"id":"A","name":""}],"lines":[$lines],"chapters":[]}"""), failAt = 1)
        val outcome = YtScriptDialogue.make(many, meta, ai)
        assertEquals(2, outcome.calls)
        assertNotNull(outcome.error)
        assertTrue(ai.prompts[1].contains("Bisherige Sprecher: A (ohne Namen)"))
        assertTrue(ai.prompts[1].contains("(A) wort"))
        assertEquals(chunks.size - 1, outcome.failedChunks)
        // every word of the transcript is in the script exactly once
        assertEquals(many.joinToString(" ") { it.text }, outcome.script.lines.joinToString(" ") { it.text })
        assertEquals(many.first().start, outcome.script.lines.first().start)
    }

    @Test
    fun withoutAiOnlyPlainParagraphs() = runBlocking {
        val outcome = YtScriptDialogue.make(cues, meta, null)
        assertEquals(0, outcome.calls)
        assertFalse(outcome.script.hasSpeakers)
        assertTrue(outcome.script.speakers.isEmpty())
    }
}
