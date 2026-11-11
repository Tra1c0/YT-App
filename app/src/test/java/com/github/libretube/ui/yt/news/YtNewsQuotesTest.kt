package com.github.libretube.ui.yt.news

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The shared quote check of the providers without character citations. */
class YtNewsQuotesTest {
    // cues 30 s apart: every cue is a line of its own (L1 at 0:05, L2 at 0:35, …)
    private val lines = listOf(
        "Der Bundestag hat am Donnerstag das neue Heizungsgesetz beschlossen.",
        "Die Opposition kündigt eine Klage vor dem Bundesverfassungsgericht an.",
        "Für Mieter ändert sich zunächst wenig, sagt der Verband.",
        "In Kraft tritt das Gesetz am ersten Januar des kommenden Jahres.",
        "Die Kosten für den Staat liegen bei rund zwölf Milliarden Euro.",
        "Kritik kommt auch von den Ländern, die mehr Geld fordern.",
        "Der Kanzler verteidigt das Vorhaben als notwendig für den Klimaschutz.",
        "Umweltverbände halten die Regeln dagegen für zu schwach.",
    )
    private val doc = YtNewsTranscriptDoc.build(lines.mapIndexed { i, text -> YtNewsCue(5.0 + i * 30, text) })
    private val sources = listOf(YtNewsSource("vid1", "Kanal: Titel", doc))

    private fun second(line: Int) = 5L + (line - 1) * 30

    @Test
    fun numberedDocument() {
        assertEquals(lines.size, doc.lines)
        val numbered = doc.numbered().lines()
        assertEquals("L1 [0:05] ${lines[0]}", numbered[0])
        assertEquals("L4 [1:35] ${lines[3]}", numbered[3])
        assertEquals(lines[1], doc.lineText(1))
        assertEquals(35L, doc.lineSecond(1))
    }

    @Test
    fun exactQuoteOnTheCitedLine() {
        assertEquals(1, YtNewsQuotes.locate(doc, 2, "kündigt eine Klage vor dem Bundesverfassungsgericht an"))
    }

    @Test
    fun hallucinatedQuoteIsDropped() {
        assertNull(YtNewsQuotes.locate(doc, 2, "Die Regierung tritt geschlossen zurück"))
        val claims = listOf(YtNewsQuotes.Claim("Die Regierung tritt zurück.", 2, "Die Regierung tritt geschlossen zurück"))
        assertTrue(YtNewsQuotes.verify(claims, sources, 5).isEmpty())
    }

    @Test
    fun quoteFoundOnANeighbourLineTakesThatLinesSecond() {
        // cited L3, quote is on L5 (two away): found, and the second is L5's
        val line = YtNewsQuotes.locate(doc, 3, "Kosten für den Staat liegen bei rund zwölf Milliarden")
        assertEquals(4, line)
        val statement = YtNewsQuotes.verify(
            listOf(YtNewsQuotes.Claim("Kosten von zwölf Milliarden.", 3, "Kosten für den Staat liegen bei rund zwölf Milliarden")),
            sources,
            5,
        ).single()
        assertEquals(second(5), statement.seconds)
        // one line away as well
        assertEquals(5, YtNewsQuotes.locate(doc, 5, "Kritik kommt auch von den Ländern"))
    }

    @Test
    fun wrongLineFarAwayIsDropped() {
        // the quote is on L8, the answer cites L2: more than two lines off
        assertNull(YtNewsQuotes.locate(doc, 2, "Umweltverbände halten die Regeln dagegen für zu schwach"))
        // a line number outside the transcript
        assertNull(YtNewsQuotes.locate(doc, 40, "Umweltverbände halten die Regeln dagegen für zu schwach"))
    }

    @Test
    fun umlautsCaseAndPunctuationDoNotMatter() {
        assertEquals(2, YtNewsQuotes.locate(doc, 3, "fuer mieter aendert sich zunaechst wenig"))
        assertEquals(2, YtNewsQuotes.locate(doc, 3, "FÜR MIETER ÄNDERT SICH ZUNÄCHST WENIG!"))
        assertEquals(4, YtNewsQuotes.locate(doc, 5, "rund zwoelf Milliarden Euro"))
        assertEquals(2, YtNewsQuotes.locate(doc, 3, "„Für Mieter ändert sich zunächst wenig“ – sagt der Verband"))
        assertEquals("fuer strasse cafe", YtNewsQuotes.normalize("Für Straße, Café!"))
    }

    @Test
    fun smallDifferencesAreTolerated() {
        // two typos in a quote of about 50 letters
        assertEquals(6, YtNewsQuotes.locate(doc, 7, "Der Kanzler verteidigt das Vorhabn als notwendig fur den"))
        // but not a different sentence of similar length
        assertNull(YtNewsQuotes.locate(doc, 7, "Der Kanzler lehnt das Vorhaben als unnötig für das Land ab"))
    }

    @Test
    fun tooShortQuotesProveNothing() {
        assertNull(YtNewsQuotes.locate(doc, 1, "Bundestag"))
        assertNull(YtNewsQuotes.locate(doc, 1, "das neue"))
    }

    @Test
    fun quoteAcrossALineBreak() {
        assertEquals(3, YtNewsQuotes.locate(doc, 4, "des kommenden Jahres. Die Kosten für den Staat"))
    }

    @Test
    fun copiedLineMarksAreIgnored() {
        assertEquals(0, YtNewsQuotes.locate(doc, 1, "L1 [0:05] Der Bundestag hat am Donnerstag"))
    }

    @Test
    fun answerParsing() {
        val answer = """
            ```json
            {"statements":[
              {"text":"- Gesetz beschlossen.","line":1,"quote":"Der Bundestag hat am Donnerstag"},
              {"text":"Klage angekündigt.","line":"L2","quote":"kündigt eine Klage vor dem"},
              {"text":"","line":3,"quote":"x"},
              {"text":"Ohne Zeile","quote":"y"}
            ]}
            ```
        """.trimIndent()
        val claims = YtNewsQuotes.claims(answer)!!
        assertEquals(2, claims.size)
        assertEquals(2, claims[1].line)
        assertNull(YtNewsQuotes.claims("Ich kann das nicht."))
        assertNull(YtNewsQuotes.claims("{\"other\":[]}"))
        val statements = YtNewsQuotes.verify(claims, sources, 5)
        assertEquals(listOf("Gesetz beschlossen." to second(1), "Klage angekündigt." to second(2)), statements.map { it.text to it.seconds })
    }

    @Test
    fun resultRules() {
        val good = """{"statements":[
            {"text":"A.","line":1,"quote":"Der Bundestag hat am Donnerstag"},
            {"text":"B.","line":2,"quote":"kündigt eine Klage vor dem"},
            {"text":"C.","line":3,"quote":"erfunden und nirgends gesagt worden"}]}"""
        val done = YtNewsProviders.quoted(good, sources) as YtNewsSummaryResult.Done
        assertEquals(2, done.statements.size)
        // only one statement survives: the description stays, with a note
        val weak = """{"statements":[
            {"text":"A.","line":1,"quote":"Der Bundestag hat am Donnerstag"},
            {"text":"C.","line":3,"quote":"erfunden und nirgends gesagt worden"}]}"""
        assertEquals(YtNewsSummaryResult.Failed(YtNewsSummaryFailure.UNVERIFIED), YtNewsProviders.quoted(weak, sources))
        assertEquals(YtNewsSummaryResult.Failed(YtNewsSummaryFailure.REFUSED), YtNewsProviders.quoted("", sources))
        assertEquals(YtNewsSummaryResult.Failed(YtNewsSummaryFailure.REFUSED), YtNewsProviders.quoted("""{"statements":[]}""", sources))
        assertEquals(YtNewsSummaryResult.Failed(YtNewsSummaryFailure.REFUSED), YtNewsProviders.quoted("Nein.", sources))
    }

    @Test
    fun atMostFiveForOneVideo() {
        val many = (1..8).joinToString(",", "{\"statements\":[", "]}") { i ->
            """{"text":"Aussage $i.","line":$i,"quote":"${lines[i - 1].split(' ').take(5).joinToString(" ")}"}"""
        }
        val done = YtNewsProviders.quoted(many, sources) as YtNewsSummaryResult.Done
        assertEquals(YtNewsCitations.MAX_SINGLE, done.statements.size)
    }

    @Test
    fun storyStatementsNameTheirVideo() {
        val other = YtNewsTranscriptDoc.build(listOf(YtNewsCue(12.0, "Wir sehen das Gesetz grundsätzlich anders als die Regierung.")))
        val story = listOf(YtNewsSource("a", "Sender A", doc), YtNewsSource("b", "Sender B", other))
        val claims = listOf(
            YtNewsQuotes.Claim("Sender A: Beschlossen.", 1, "Der Bundestag hat am Donnerstag", video = 1),
            YtNewsQuotes.Claim("Sender B: Widerspruch.", 1, "sehen das Gesetz grundsätzlich anders", video = 2),
            // the quote is in video 1, but the claim names video 2
            YtNewsQuotes.Claim("Sender B: Klage.", 2, "kündigt eine Klage vor dem", video = 2),
            YtNewsQuotes.Claim("Sender C: ?", 1, "Der Bundestag hat am Donnerstag", video = 3),
        )
        val statements = YtNewsQuotes.verify(claims, story, YtNewsCitations.MAX_STORY)
        assertEquals(listOf("a" to 5L, "b" to 12L), statements.map { it.videoId to it.seconds })
        assertTrue(YtNewsQuotes.prompt(story).contains("=== Video 2: Sender B ==="))
    }

    @Test
    fun schemaIsStrict() {
        val schema = YtNewsQuotes.schema(story = true)
        assertEquals("false", schema["additionalProperties"]!!.jsonPrimitive.content)
        val items = schema["properties"]!!.jsonObject["statements"]!!.jsonObject["items"] as JsonObject
        assertEquals(listOf("video", "text", "line", "quote"), items["required"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("false", items["additionalProperties"]!!.jsonPrimitive.content)
        val single = YtNewsQuotes.schema(story = false, closed = false)
        assertNull(single["additionalProperties"])
        val singleItems = single["properties"]!!.jsonObject["statements"]!!.jsonObject["items"] as JsonObject
        assertNull(singleItems["properties"]!!.jsonObject["video"])
    }

    @Test
    fun distance() {
        assertEquals(0, YtNewsQuotes.distance("klage", "eine klage vor"))
        assertEquals(1, YtNewsQuotes.distance("klagge", "eine klage vor"))
        assertEquals(2, YtNewsQuotes.distance("ab", "xyz"))
    }
}
