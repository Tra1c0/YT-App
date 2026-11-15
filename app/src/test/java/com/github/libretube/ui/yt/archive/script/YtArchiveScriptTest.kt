package com.github.libretube.ui.yt.archive.script

import java.io.File
import java.nio.file.Files
import java.util.zip.GZIPOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The script format, its reading and the reading of the stored transcript and meta. */
class YtArchiveScriptTest {
    private val script = YtArchiveScript(
        YtScriptSource.AUDIO,
        listOf(YtScriptSpeaker("A", "Lisa"), YtScriptSpeaker("B", null)),
        listOf(
            YtScriptLine(0, "A", "Willkommen zur Sendung."),
            YtScriptLine(4_200, "B", "Danke, dass ich hier sein darf – \"wirklich\"."),
            YtScriptLine(65_000, null, "Musik"),
        ),
        listOf(YtScriptChapter(0, "Begrüßung"), YtScriptChapter(60_000, "Thema")),
    )

    @Test
    fun roundTrip() {
        val json = script.toJson()
        assertEquals(script, YtArchiveScript.parse(json))
        assertTrue(json.contains("\"version\":1"))
        assertTrue(json.contains("\"source\":\"audio\""))
        assertTrue(json.contains("\"name\":null"))
        assertTrue(json.contains("\"speaker\":null"))
        assertTrue(script.hasSpeakers)
    }

    @Test
    fun parseIsLenientWhereItCostsNothing() {
        val parsed = YtArchiveScript.parse(
            """{"version":1,"source":"transcript","extra":1,
              "speakers":[{"id":"A","name":""},{"id":"A","name":"dup"}],
              "lines":[{"start":5000,"speaker":"Z","text":"zwei"},{"start":1000,"speaker":"A","text":"eins"},{"start":7,"text":"  "}],
              "chapters":[{"start":3000,"title":""}]}""",
        )!!
        assertEquals(listOf(YtScriptSpeaker("A", null)), parsed.speakers)
        assertEquals(listOf(YtScriptLine(1000, "A", "eins"), YtScriptLine(5000, null, "zwei")), parsed.lines)
        assertTrue(parsed.chapters.isEmpty())
    }

    @Test
    fun notAScript() {
        assertNull(YtArchiveScript.parse("nope"))
        assertNull(YtArchiveScript.parse("""{"source":"audio"}"""))
        assertNull(YtArchiveScript.parse("""{"version":1,"source":"video"}"""))
    }

    @Test
    fun readableText() {
        val text = script.readable { "Sprecher $it" }
        assertEquals(
            "## Begrüßung\n\n[0:00] Lisa: Willkommen zur Sendung.\n\n[0:04] Sprecher B: Danke, dass ich hier sein darf – \"wirklich\".\n\n" +
                "## Thema\n\n[1:05] Musik\n",
            text,
        )
        assertEquals("1:02:03", YtArchiveScript.stamp(3_723_000))
    }

    @Test
    fun cuesAreCleaned() {
        val cues = YtScriptInput.parseCues(
            sequenceOf(
                """{"start":2000,"end":3000,"text":"zweite <c>Zeile</c>"}""",
                """{"start":0,"end":1500,"text":"  erste   Zeile "}""",
                """{"start":3000,"end":4000,"text":"zweite Zeile"}""",
                """not json""",
                """{"start":5000,"end":6000,"text":""}""",
            ),
        )
        assertEquals(listOf(YtScriptCue(0, 1500, "erste Zeile"), YtScriptCue(2000, 3000, "zweite Zeile")), cues)
    }

    @Test
    fun cuesFromGzipFile() {
        val dir = Files.createTempDirectory("yt-script").toFile()
        try {
            val file = File(dir, "transcript.jsonl.gz")
            GZIPOutputStream(file.outputStream()).bufferedWriter().use {
                it.write("""{"start":0,"end":900,"text":"Hallo"}""" + "\n" + """{"start":1000,"end":2000,"text":"Welt"}""" + "\n")
            }
            assertEquals(2, YtScriptInput.cues(file).size)
            assertTrue(YtScriptInput.cues(File(dir, "missing")).isEmpty())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun metaChaptersInMillisOrSeconds() {
        val meta = YtScriptInput.parseMeta(
            """{"title":"T","description":"D","chapters":[{"start":60000,"title":"Zwei"},{"seconds":0,"title":"Eins"},{"title":"ohne Zeit"}]}""",
        )!!
        assertEquals("T", meta.title)
        assertEquals(listOf(YtScriptChapter(0, "Eins"), YtScriptChapter(60_000, "Zwei")), meta.chapters)
        assertNull(YtScriptInput.parseMeta("[]"))
    }

    @Test
    fun checksWordsAndNames() {
        assertTrue(YtScriptCheck.editsAtMostOne("bundestag", "bundestak"))
        assertTrue(YtScriptCheck.editsAtMostOne("haus", "hause"))
        assertFalse(YtScriptCheck.editsAtMostOne("haus", "maul"))
        val known = YtScriptCheck.words("Heute bei uns: Lisa Müller und Jonas").toHashSet()
        assertEquals("Lisa Müller", YtScriptCheck.name("Lisa Müller", known))
        assertNull(YtScriptCheck.name("Peter", known))
        assertNull(YtScriptCheck.name("Moderator", known + "moderator"))
        assertNull(YtScriptCheck.name("", known))
    }
}
