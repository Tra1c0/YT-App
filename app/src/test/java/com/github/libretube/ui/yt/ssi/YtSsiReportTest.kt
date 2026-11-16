package com.github.libretube.ui.yt.ssi

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class YtSsiReportTest {
    private val utc = ZoneId.of("UTC")

    private fun hit(id: String, seg: Long?, text: String, up: Long? = 1_600_000_000_000L, ms: Long = 61_000, timing: YtSsiTiming = YtSsiTiming.WORD) = YtSsiHit(
        videoId = id, title = "Video $id", channelName = "Kanal $id", uploaded = up, language = null, segmentId = seg, startMs = ms, endMs = ms + 5000,
        hitMs = ms, contextStartMs = 0, contextEndMs = 0, text = text, highlights = emptyList(), matches = emptySet(), methods = emptySet(),
        timing = timing, availability = YtSsiAvailability.PUBLIC, rank = 1, fused = 0.0, ranks = emptyMap(),
    )

    private val hits = listOf(
        hit("aaaaaaaaaaa", 1, "Ja, Menschen müssen Fleisch essen, sagt er."),
        hit("bbbbbbbbbbb", 2, "Das stimmt nicht. Die Studie zeigt das Gegenteil.", ms = 125_400),
        hit("ccccccccccc", 3, "Er sagt etwas über Fleisch, aber unklar.", up = null, timing = YtSsiTiming.LINE),
        hit("ddddddddddd", 4, "Nicht eingeordnete Stelle."),
        hit("eeeeeeeeeee", null, "nur der Titel"),
    )
    private val stances = mapOf(
        1L to YtSsiStance(1, YtSsiRelation.SUPPORTS, YtSsiEvidence("Menschen müssen Fleisch essen", 1, 61_400, YtSsiTiming.WORD)),
        2L to YtSsiStance(
            2, YtSsiRelation.REFUTES, YtSsiEvidence("Das stimmt nicht", 2, 125_400, YtSsiTiming.WORD), YtSsiOpposition.REASONED,
            reason = YtSsiEvidence("Die Studie zeigt das Gegenteil", 2, 127_000, YtSsiTiming.WORD),
            flags = mapOf(YtSsiContextFlag.CRITICAL to YtSsiEvidence("Das stimmt nicht", 2, 125_400, YtSsiTiming.WORD)),
        ),
        3L to YtSsiStance(3, YtSsiRelation.UNCERTAIN, null, claimed = YtSsiRelation.SUPPORTS, unsure = YtSsiUnsure.QUOTE_NOT_FOUND),
    )
    private val origins = listOf(YtSsiOrigin(listOf("aaaaaaaaaaa", "ddddddddddd"), setOf(YtSsiSameOrigin.SAME_LINK)), YtSsiOrigin(listOf("bbbbbbbbbbb"), emptySet()), YtSsiOrigin(listOf("ccccccccccc"), emptySet()), YtSsiOrigin(listOf("eeeeeeeeeee"), emptySet()))

    private val labels = YtSsiReport.Labels(
        title = "Belegbericht", question = "Frage", claim = "Behauptung", created = "Erstellt", neutral = "SSI prüft nicht, wer recht hat.",
        groups = mapOf(YtSsiGroup.SUPPORTS to "Stützt", YtSsiGroup.REFUTES_REASONED to "Widerlegt mit Begründung", YtSsiGroup.CONTRADICTS to "Widerspricht",
            YtSsiGroup.UNCERTAIN to "Unklar", YtSsiGroup.UNCHECKED to "Nicht geprüft"),
        flags = mapOf(YtSsiContextFlag.CRITICAL to "kritisch"),
        reason = "Begründung", notVerified = "nicht belegt", passageOnly = "Stelle", sameOrigin = "Gleicher Ursprung", originsCount = "%1\$d Videos, %2\$d Ursprünge",
        uncertainties = "Unsicherheiten", uncertainNotVerified = "%1\$d nicht belegt", uncertainAi = "%1\$d unklar", uncertainUnchecked = "%1\$d nicht geprüft",
        uncertainNoAi = "ohne KI", uncertainApprox = "%1\$d ungefähr", uncertainUndated = "%1\$d ohne Datum", shift = "%1\$s: %2\$s / %3\$s", shiftNote = "nur Hinweis",
        notes = "Notizen", none = "keine", approx = "≈ ",
    )

    private fun report(ai: Boolean = true) = YtSsiReport.build("Müssen Menschen Fleisch essen?", "Menschen müssen Fleisch essen", hits, stances, origins, ai, 1_700_000_000_000L, notes = "meine Notiz")

    @Test
    fun groupsByRelationAndLinksTheSecond() {
        val r = report()
        assertEquals(listOf(YtSsiGroup.SUPPORTS, YtSsiGroup.REFUTES_REASONED, YtSsiGroup.UNCERTAIN, YtSsiGroup.UNCHECKED), r.groups.map { it.first })
        val support = r.groups[0].second.single()
        assertEquals("https://www.youtube.com/watch?v=aaaaaaaaaaa&t=61s", support.url)
        assertTrue(support.verified)
        val unclear = r.groups.first { it.first == YtSsiGroup.UNCERTAIN }.second.single()
        assertFalse(unclear.verified)
        assertEquals(hits[2].text, unclear.quote) // the passage, not an invented quote
        // title-only hits are no statements
        assertFalse(r.groups.flatMap { it.second }.any { it.hit.segmentId == null })
    }

    @Test
    fun listsTheUncertainties() {
        val u = report().uncertainty
        assertEquals(1, u.notVerified)
        assertEquals(1, u.unchecked)
        assertEquals(1, u.approximate)
        assertEquals(1, u.undated)
        assertEquals(5, u.videos)
        assertEquals(4, u.origins)
        assertFalse(u.aiMissing)
        assertTrue(report(ai = false).uncertainty.aiMissing)
    }

    @Test
    fun markdownCarriesQuotesLinksAndTheNotEvidencedMark() {
        val md = YtSsiReport.markdown(report(), labels, utc)
        assertTrue(md.startsWith("# Belegbericht"))
        assertTrue(md.contains("**Frage:** Müssen Menschen Fleisch essen?"))
        assertTrue(md.contains("SSI prüft nicht, wer recht hat."))
        assertTrue(md.contains("5 Videos, 4 Ursprünge"))
        assertTrue(md.contains("## Stützt (1)"))
        assertTrue(md.contains("(https://www.youtube.com/watch?v=aaaaaaaaaaa&t=61s)"))
        assertTrue(md.contains("„Menschen müssen Fleisch essen“"))
        assertTrue(md.contains("Begründung: „Die Studie zeigt das Gegenteil“"))
        assertTrue(md.contains("kritisch: „Das stimmt nicht“"))
        assertTrue(md.contains("nicht belegt: „Er sagt etwas über Fleisch, aber unklar.“"))
        assertTrue(md.contains("@ ≈ 1:01")) // a line-timed statement is marked approximate
        assertTrue(md.contains("Gleicher Ursprung: aaaaaaaaaaa"))
        assertTrue(md.contains("## Unsicherheiten"))
        assertTrue(md.contains("- 1 nicht belegt"))
        assertTrue(md.contains("## Notizen\n\nmeine Notiz"))
    }

    @Test
    fun jsonIsMachineReadable() {
        val root = Json.parseToJsonElement(YtSsiReport.json(report())).jsonObject
        assertEquals("yt-ssi-report", root["format"]!!.jsonPrimitive.content)
        val findings = root["findings"]!!.jsonArray
        assertEquals(4, findings.size)
        val first = findings[0].jsonObject
        assertEquals("supports", first["relation"]!!.jsonPrimitive.content)
        assertTrue(first["verified"]!!.jsonPrimitive.boolean)
        assertEquals("Menschen müssen Fleisch essen", first["quote"]!!.jsonPrimitive.content)
        assertTrue(first["url"]!!.jsonPrimitive.content.endsWith("&t=61s"))
        val refutes = findings[1].jsonObject
        assertEquals("reasoned", refutes["opposition"]!!.jsonPrimitive.content)
        assertEquals("Die Studie zeigt das Gegenteil", (refutes["reason"] as JsonObject)["quote"]!!.jsonPrimitive.content)
        val unclear = findings[2].jsonObject
        assertFalse(unclear["verified"]!!.jsonPrimitive.boolean)
        assertEquals("quote_not_found", unclear["unsure"]!!.jsonPrimitive.content)
        assertTrue("quote" !in unclear && "passage" in unclear)
        assertEquals(1, (root["same_origin"] as JsonArray).size)
        assertEquals(4, root["uncertainty"]!!.jsonObject["independent_origins"]!!.jsonPrimitive.int)
    }

    @Test
    fun urlsNeverGoNegative() {
        assertEquals("https://www.youtube.com/watch?v=x&t=0s", YtSsiReport.url("x", -500))
    }
}
