package com.github.libretube.ui.yt.ssi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class YtSsiIntentTest {
    private val zone = ZoneId.of("Europe/Berlin")
    private val now = ZonedDateTime.of(2026, 11, 15, 12, 0, 0, 0, zone).toInstant().toEpochMilli()
    private val known = listOf("Kurzgesagt", "MaiLab", "Terra X Lesch & Co")

    private fun parse(text: String) = YtSsiIntentParser.parse(text, now, zone, known)
    private fun day(y: Int, m: Int, d: Int) = LocalDate.of(y, m, d).atStartOfDay(zone).toInstant().toEpochMilli()

    @Test
    fun quotesAreExactAndRequired() {
        val intent = parse("„das ist nicht wahr“ von Kurzgesagt")
        assertEquals(YtSsiIntentKind.EXACT_QUOTE, intent.kind)
        assertEquals(listOf("das ist nicht wahr"), intent.phrases)
        assertEquals(listOf("Kurzgesagt"), intent.channels)
        assertTrue(intent.exact)
        // the quote's words are not filters: "nicht" inside it stays
        assertTrue(intent.excludeTerms.isEmpty())
        assertTrue(intent.weights.lexical > intent.weights.semantic)
    }

    @Test
    fun yearsAgoIsAWindowAroundThen() {
        val time = parse("Wo hat er vor drei Jahren etwas anderes behauptet").time!!
        assertTrue(time.contains(day(2023, 11, 15)))
        assertTrue(time.contains(day(2023, 6, 1)))
        assertFalse(time.contains(day(2024, 6, 1)))
        assertFalse(time.contains(day(2022, 12, 1)))
        assertEquals(setOf(YtSsiLater.CONTRADICTION), parse("Wo hat er vor drei Jahren etwas anderes behauptet").later)
    }

    @Test
    fun yearNumbersAndRelativeRanges() {
        val year = parse("Klimadebatte 2023").time!!
        assertEquals(day(2023, 1, 1), year.fromMs)
        assertEquals(day(2024, 1, 1) - 1, year.toMs)
        assertEquals(listOf("klimadebatte"), parse("Klimadebatte 2023").terms)

        val week = parse("Nachrichten letzte Woche").time!!
        assertEquals(now - 14L * 24 * 3600 * 1000, week.fromMs)
        assertEquals(now, week.toMs)

        val since = parse("seit 2020 Mondlandung").time!!
        assertEquals(day(2020, 1, 1), since.fromMs)
        assertNull(since.toMs)

        val between = parse("zwischen 2019 und 2021 Pandemie").time!!
        assertEquals(day(2019, 1, 1), between.fromMs)
        assertEquals(day(2022, 1, 1) - 1, between.toMs)

        val month = parse("im März 2024 Wahl").time!!
        assertEquals(day(2024, 3, 1), month.fromMs)
        assertEquals(day(2024, 4, 1) - 1, month.toMs)

        val english = parse("two years ago interview").time!!
        assertTrue(english.contains(day(2024, 11, 15)))
    }

    @Test
    fun channelsByHandleOrKnownName() {
        val handle = parse("@maiLab erklärt Impfstoffe")
        assertEquals(listOf("maiLab"), handle.channels)
        val known = parse("Video von Terra X Lesch & Co über Schwarze Löcher")
        assertEquals(listOf("Terra X Lesch & Co"), known.channels)
        assertFalse(known.semanticText.contains("Lesch"))
        // an unknown name after "von" is meaning, not a filter
        assertTrue(parse("Ballade von Hans Müller").channels.isEmpty())
        // a known name written plainly only lifts
        val guess = parse("Kurzgesagt Video über Ameisen")
        assertTrue(guess.channels.isEmpty())
        assertEquals(listOf("Kurzgesagt"), guess.channelGuesses)
    }

    @Test
    fun exclusionsButNotTheMeaningOfNicht() {
        val intent = parse("Finde die Stelle, an der diese Person erklärt, warum das nicht stimmt")
        assertTrue(intent.excludeTerms.isEmpty())
        assertTrue("stimmt" in intent.terms)
        assertTrue(intent.semanticText.contains("nicht stimmt"))
        assertEquals(YtSsiIntentKind.EXPLANATION, intent.kind)
        assertTrue(intent.weights.semantic > intent.weights.lexical)

        val without = parse("Rezept Lasagne ohne Fleisch -Werbung aber nicht Spinat")
        assertEquals(listOf("spinat", "fleisch", "werbung").sorted(), without.excludeTerms.sorted())
        assertEquals(listOf("rezept", "lasagne"), without.terms)

        val notFrom = parse("Schwarze Löcher nicht von Kurzgesagt")
        assertEquals(listOf("Kurzgesagt"), notFrom.excludeChannels)
        assertTrue(notFrom.channels.isEmpty())
    }

    @Test
    fun languageHintsAndQuestions() {
        val intent = parse("Wie funktioniert Photosynthese auf Englisch?")
        assertEquals("en", intent.language)
        assertEquals(YtSsiIntentKind.QUESTION, intent.kind)
        assertFalse(intent.terms.contains("englisch"))
        assertEquals(setOf(YtSsiLater.REFUTE), parse("Videos, die widerlegen, dass Menschen Fleisch essen müssen").later)
        assertEquals(setOf(YtSsiLater.COMPARE), parse("vergleiche die Aussagen zu Atomkraft").later)
    }

    @Test
    fun findTheVideoAndFillers() {
        val intent = parse("Finde das Video, in dem der Gitarrist zuerst Sweep Picking spielt")
        assertEquals(YtSsiIntentKind.FIND_VIDEO, intent.kind)
        assertTrue(intent.semanticText.startsWith("der Gitarrist"))
        assertEquals(listOf("gitarrist", "zuerst", "sweep", "picking", "spielt"), intent.terms)
    }

    @Test
    fun cacheKeyIgnoresCaseAndSpaces() {
        assertEquals(parse("Hallo   Welt!").cacheKey, parse("hallo welt").cacheKey)
        assertTrue(parse("   ").empty)
    }
}
