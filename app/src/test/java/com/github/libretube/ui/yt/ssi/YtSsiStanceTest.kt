package com.github.libretube.ui.yt.ssi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import kotlin.random.Random

class YtSsiStanceTest {
    private val passageText = "Viele sagen, Menschen müssen Fleisch essen. Das stimmt so nicht. " +
        "Eine große Studie aus Oxford zeigt, dass vegetarische Ernährung alle Nährstoffe liefern kann."
    private val followText = "Natürlich, sagt er ironisch, Pflanzen haben ja überhaupt kein Eiweiß. Vitamin B12 muss man allerdings ergänzen."

    /** Word marks: every word starts 400 ms after the one before. */
    private fun marks(text: String, start: Long) = YtSsiText.wordRanges(text).mapIndexed { i, r -> YtSsiMark(r.first, start + i * 400L) }

    private val seg = YtSsiSegment("v1", 3, 10_000, 30_000, passageText, YtSsiSegmentKind.SPEECH, YtSsiTiming.WORD, marks(passageText, 10_000), id = 41)
    private val next = YtSsiSegment("v1", 4, 30_000, 45_000, followText, YtSsiSegmentKind.SPEECH, YtSsiTiming.WORD, marks(followText, 30_000), id = 42)
    private val unit = YtSsiStanceRules.unit("p1", seg, "Fleisch?", "Kanal", null, next)!!

    private fun raw(relation: String, quote: String = "", reason: String = "", flags: List<YtSsiStanceRules.RawFlag> = emptyList()) =
        YtSsiStanceRules.Raw(relation, quote, reason, flags)

    @Test
    fun aRelationWithAFoundQuoteStands() {
        val s = YtSsiStanceRules.verify(unit, raw("supports", "Menschen müssen Fleisch essen"))
        assertEquals(YtSsiRelation.SUPPORTS, s.relation)
        assertTrue(s.verified)
        assertEquals("Menschen müssen Fleisch essen", s.evidence!!.quote)
        assertEquals(41L, s.evidence!!.segmentId)
        // "Menschen" is the 3rd word: 10 000 + 2 × 400
        assertEquals(10_800L, s.evidence!!.ms)
    }

    @Test
    fun noQuoteMeansUncertainNotEvidenced() {
        for (relation in listOf("supports", "refutes", "qualifies", "quotes", "discusses")) {
            val s = YtSsiStanceRules.verify(unit, raw(relation, ""))
            assertEquals(YtSsiRelation.UNCERTAIN, s.relation)
            assertNull(s.evidence)
            assertEquals(YtSsiUnsure.NO_QUOTE, s.unsure)
            assertEquals(YtSsiStanceRules.relationOf(relation), s.claimed)
        }
    }

    @Test
    fun aQuoteThatIsNotInThePassageIsForcedToUncertain() {
        val s = YtSsiStanceRules.verify(unit, raw("refutes", "Fleisch ist gefährlich für alle Menschen", reason = "eine große Studie aus Oxford zeigt"))
        assertEquals(YtSsiRelation.UNCERTAIN, s.relation)
        assertEquals(YtSsiUnsure.QUOTE_NOT_FOUND, s.unsure)
        assertEquals(YtSsiRelation.REFUTES, s.claimed)
        assertNull(s.evidence)
        assertNull(s.opposition)
        assertNull(s.reason)
    }

    @Test
    fun aContextQuoteBeforeThePassageDoesNotCount() {
        val before = YtSsiSegment("v1", 2, 0, 10_000, "Heute geht es um die Frage nach dem Fleisch und der Gesundheit.", YtSsiSegmentKind.SPEECH, YtSsiTiming.WORD, id = 40)
        val u = YtSsiStanceRules.unit("p1", seg, "t", null, before, next)!!
        val s = YtSsiStanceRules.verify(u, raw("discusses", "die Frage nach dem Fleisch und der Gesundheit"))
        assertEquals(YtSsiRelation.UNCERTAIN, s.relation)
        assertEquals(YtSsiUnsure.QUOTE_NOT_FOUND, s.unsure)
    }

    @Test
    fun smallSpellingDifferencesAreTolerated() {
        // umlaut spelled out, a typo, other case and punctuation
        val s = YtSsiStanceRules.verify(unit, raw("supports", "menschen muessen Fleich essen!"))
        assertEquals(YtSsiRelation.SUPPORTS, s.relation)
        assertEquals("Menschen müssen Fleisch essen", s.evidence!!.quote)
    }

    @Test
    fun aContradictionIsNotARefutation() {
        val s = YtSsiStanceRules.verify(unit, raw("refutes", "Das stimmt so nicht"))
        assertEquals(YtSsiRelation.REFUTES, s.relation)
        assertEquals(YtSsiOpposition.CONTRADICTS, s.opposition)
        assertNull(s.reason)
        assertEquals(YtSsiGroup.CONTRADICTS, YtSsiReport.group(s))
    }

    @Test
    fun aRefutationNeedsItsReasonFoundInThePassage() {
        val reasoned = YtSsiStanceRules.verify(unit, raw("refutes", "Das stimmt so nicht", reason = "Eine große Studie aus Oxford zeigt, dass vegetarische Ernährung alle Nährstoffe liefern kann"))
        assertEquals(YtSsiOpposition.REASONED, reasoned.opposition)
        assertNotNull(reasoned.reason)
        assertTrue(reasoned.reason!!.quote.startsWith("Eine große Studie"))
        assertEquals(YtSsiGroup.REFUTES_REASONED, YtSsiReport.group(reasoned))

        // a reason that is not there: only a contradiction
        val invented = YtSsiStanceRules.verify(unit, raw("refutes", "Das stimmt so nicht", reason = "die WHO hat das 2020 widerlegt und bewiesen"))
        assertEquals(YtSsiOpposition.CONTRADICTS, invented.opposition)
        assertNull(invented.reason)

        // the quote again as the reason: only a contradiction
        val same = YtSsiStanceRules.verify(unit, raw("refutes", "Das stimmt so nicht", reason = "das stimmt so nicht"))
        assertEquals(YtSsiOpposition.CONTRADICTS, same.opposition)
        assertNull(same.reason)
    }

    @Test
    fun aQuoteFromTheContinuationCarriesItsOwnPassageAndTime() {
        val s = YtSsiStanceRules.verify(unit, raw("qualifies", "Vitamin B12 muss man allerdings ergänzen"))
        assertEquals(YtSsiRelation.QUALIFIES, s.relation)
        assertEquals(42L, s.evidence!!.segmentId)
        val words = YtSsiText.wordRanges(followText).size
        assertTrue(s.evidence!!.ms in 30_000L..30_000L + words * 400L)
    }

    @Test
    fun flagsNeedTheirOwnFoundQuote() {
        val s = YtSsiStanceRules.verify(
            unit,
            raw(
                "refutes",
                "Das stimmt so nicht",
                flags = listOf(
                    YtSsiStanceRules.RawFlag("ironic", "Pflanzen haben ja überhaupt kein Eiweiß"),
                    YtSsiStanceRules.RawFlag("hypothetical", "wenn alle Menschen morgen aufhören würden"),
                    YtSsiStanceRules.RawFlag("critical", "zu"),
                ),
            ),
        )
        assertEquals(setOf(YtSsiContextFlag.IRONIC), s.flags.keys)
        assertEquals(42L, s.flags.getValue(YtSsiContextFlag.IRONIC).segmentId)
    }

    @Test
    fun parsesOnlyTheIdsThatWereSent() {
        val second = YtSsiStanceRules.unit("p2", next.copy(id = 50), "B", null, null, null)!!
        val answer = """
            ```json
            {"items":[
              {"id":"p1","relation":"supports","quote":"Menschen müssen Fleisch essen","reason":"","flags":[]},
              {"id":"2","relation":"qualifies","quote":"Vitamin B12 muss man allerdings ergänzen","reason":"","flags":[{"flag":"critical","quote":"x"}]},
              {"id":"p9","relation":"supports","quote":"erfunden","reason":"","flags":[]},
              {"id":"p1","relation":"refutes","quote":"Das stimmt so nicht","reason":"","flags":[]}
            ]}
            ```
        """.trimIndent()
        val raws = YtSsiStanceRules.parse(answer, listOf(unit, second))!!
        assertEquals(setOf(41L, 50L), raws.keys)
        assertEquals("supports", raws.getValue(41L).relation)
        assertEquals(1, raws.getValue(50L).flags.size)
        assertNull(YtSsiStanceRules.parse("no json at all", listOf(unit)))
        assertNull(YtSsiStanceRules.parse("""{"items":[{"id":"p7","relation":"supports","quote":"a b c d"}]}""", listOf(unit)))
    }

    @Test
    fun relationWordsInBothLanguages() {
        assertEquals(YtSsiRelation.SUPPORTS, YtSsiStanceRules.relationOf("Stützt"))
        assertEquals(YtSsiRelation.REFUTES, YtSsiStanceRules.relationOf("widerspricht"))
        assertEquals(YtSsiRelation.REFUTES, YtSsiStanceRules.relationOf("contradicts"))
        assertEquals(YtSsiRelation.QUALIFIES, YtSsiStanceRules.relationOf("schränkt ein"))
        assertEquals(YtSsiRelation.QUOTES, YtSsiStanceRules.relationOf("quotes"))
        assertEquals(YtSsiRelation.DISCUSSES, YtSsiStanceRules.relationOf("bespricht"))
        assertEquals(YtSsiRelation.UNCERTAIN, YtSsiStanceRules.relationOf("maybe"))
        assertEquals(YtSsiContextFlag.QUOTING_OTHER, YtSsiStanceRules.flagOf("quoting"))
        assertNull(YtSsiStanceRules.flagOf("sarcasm?"))
    }

    @Test
    fun theAiItselfUnsureStaysUncertainEvenWithAQuote() {
        val s = YtSsiStanceRules.verify(unit, raw("uncertain", "Das stimmt so nicht"))
        assertEquals(YtSsiRelation.UNCERTAIN, s.relation)
        assertEquals(YtSsiUnsure.AI_SAID, s.unsure)
        assertNotNull(s.evidence)
    }

    /** The hard rule over many random answers: no relation without a quote that is in the passage. */
    @Test
    fun noRelationWithoutAVerifiedQuoteEver() {
        val random = Random(7)
        val words = YtSsiText.wordRanges(unit.text).map { unit.text.substring(it.first, it.last + 1) }
        val invented = listOf("Klima", "Steuern", "Mars", "Fußball", "Banane", "Quanten", "Wahl", "Regen")
        repeat(3_000) {
            val relation = listOf("supports", "refutes", "qualifies", "quotes", "discusses", "uncertain", "stützt", "x").random(random)
            val quote = when (random.nextInt(4)) {
                0 -> ""
                1 -> words.drop(random.nextInt(words.size - 5)).take(4 + random.nextInt(6)).joinToString(" ")
                2 -> (1..(3 + random.nextInt(6))).joinToString(" ") { invented.random(random) }
                else -> words.shuffled(random).take(5).joinToString(" ")
            }
            val reason = if (random.nextBoolean()) "" else words.drop(random.nextInt(words.size - 5)).take(6).joinToString(" ")
            val s = YtSsiStanceRules.verify(unit, raw(relation, quote, reason))
            if (s.relation != YtSsiRelation.UNCERTAIN) {
                val e = s.evidence
                assertNotNull("relation ${s.relation} without evidence for '$quote'", e)
                assertTrue(unit.text.contains(e!!.quote))
                assertTrue(YtSsiQuoteCheck.verified(unit.text, quote))
            }
            if (s.opposition == YtSsiOpposition.REASONED) {
                assertNotNull(s.reason)
                assertTrue(unit.text.contains(s.reason!!.quote))
                assertFalse(YtSsiQuoteCheck.same(s.reason!!.quote, s.evidence!!.quote))
            }
            if (s.relation != YtSsiRelation.REFUTES) assertNull(s.opposition)
        }
    }

    @Test
    fun cachedAnswersAreCheckedAgain() {
        val stored = YtSsiStanceRules.encode(raw("supports", "Menschen müssen Fleisch essen", flags = listOf(YtSsiStanceRules.RawFlag("ironic", "x y z"))))
        val back = YtSsiStanceRules.decode(stored)!!
        val s = YtSsiStanceRules.verify(unit, back, cached = true)
        assertTrue(s.cached && s.verified)
        // the passage changed since: the same answer no longer holds
        val changed = YtSsiStanceRules.unit("p1", seg.copy(text = "Ganz anderer Text ohne das Zitat, nur Gerede."), "t", null, null, null)!!
        assertEquals(YtSsiRelation.UNCERTAIN, YtSsiStanceRules.verify(changed, back, cached = true).relation)
        // the key depends on claim, passage text and model
        val k = YtSsiStanceRules.cacheKey("Menschen müssen Fleisch essen", unit, "openai:m")
        assertTrue(k.startsWith("st|"))
        assertEquals(k, YtSsiStanceRules.cacheKey("menschen MÜSSEN fleisch essen", unit, "openai:m"))
        assertFalse(k == YtSsiStanceRules.cacheKey("Menschen müssen Fleisch essen", unit, "gemini:m"))
        assertFalse(k == YtSsiStanceRules.cacheKey("Menschen müssen Fleisch essen", changed, "openai:m"))
    }

    @Test
    fun batchesKeepTheBudget() {
        val units = (1..20).map { unit.copy(id = "p$it", segmentId = it.toLong()) }
        val b = YtSsiStanceRules.batches(units, cached = setOf(1L, 2L), perSearch = 2)
        assertEquals(2, b.size)
        assertEquals(YtSsiStanceRules.BATCH, b[0].size)
        assertFalse(b.flatten().any { it.segmentId in setOf(1L, 2L) })
        assertTrue(YtSsiStanceRules.batches(units, emptySet(), 0).isEmpty())
    }

    @Test
    fun theClaimIsTheQueryWithoutTheAsk() {
        fun claim(q: String) = YtSsiStanceRules.claim(YtSsiIntentParser.parse(q, 1_800_000_000_000L, ZoneId.of("UTC")))
        assertEquals("Menschen Fleisch essen müssen", claim("Videos, die widerlegen, dass Menschen Fleisch essen müssen"))
        assertEquals("Atomkraft", claim("vergleiche die Aussagen zu Atomkraft"))
        assertEquals("Tempolimit senkt Unfälle", claim("Tempolimit senkt Unfälle"))
    }

    @Test
    fun schemaAndPromptCarryTheRules() {
        val schema = YtSsiStanceRules.schema().toString()
        assertTrue(schema.contains("\"reason\"") && schema.contains("\"flags\"") && schema.contains("uncertain"))
        val user = YtSsiStanceRules.user("Menschen müssen Fleisch essen", "Videos, die widerlegen, dass Menschen Fleisch essen müssen", listOf(unit))
        assertTrue(user.contains("Behauptung: Menschen müssen Fleisch essen"))
        assertTrue(user.contains("### p1") && user.contains("Passage: ") && user.contains("Fortsetzung: "))
        assertTrue(YtSsiStanceRules.SYSTEM.contains("nicht, wer recht hat"))
    }
}
