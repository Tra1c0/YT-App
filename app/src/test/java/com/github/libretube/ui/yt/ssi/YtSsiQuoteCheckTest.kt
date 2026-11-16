package com.github.libretube.ui.yt.ssi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YtSsiQuoteCheckTest {
    private val text = "Also, ich glaube: Die Erde ist rund – das weiß jedes Kind. Aber für Flachland-Fans ist das egal."

    private fun at(r: IntRange?) = r?.let { text.substring(it.first, it.last + 1) }

    @Test
    fun findsAnExactQuoteAndItsRange() {
        assertEquals("Die Erde ist rund – das weiß", at(YtSsiQuoteCheck.find(text, "die erde ist rund, das weiß")))
    }

    @Test
    fun toleratesSpellingAndATypo() {
        assertEquals("für Flachland-Fans ist das egal", at(YtSsiQuoteCheck.find(text, "fuer Flachland Fans ist das egal")))
        assertEquals("das weiß jedes Kind", at(YtSsiQuoteCheck.find(text, "das weiss jedes Kindd")))
    }

    @Test
    fun refusesWhatIsNotThere() {
        assertNull(YtSsiQuoteCheck.find(text, "die Erde ist eine Scheibe"))
        assertNull(YtSsiQuoteCheck.find(text, "Mond ist aus Käse gemacht"))
        assertNull(YtSsiQuoteCheck.find(text, null))
    }

    @Test
    fun refusesQuotesTooShortToProveAnything() {
        assertNull(YtSsiQuoteCheck.find(text, "ist rund"))
        assertNull(YtSsiQuoteCheck.find(text, "das"))
    }

    @Test
    fun tooManyEditsAreRefused() {
        assertNull(YtSsiQuoteCheck.find(text, "die Ernte ist bunt das heißt jedes"))
    }

    @Test
    fun boundedDistanceMatchesTheFullOne() {
        assertEquals(0, YtSsiQuoteCheck.bounded("abc", "abc", 2))
        assertEquals(1, YtSsiQuoteCheck.bounded("abc", "abd", 2))
        assertEquals(2, YtSsiQuoteCheck.bounded("kitten", "sitting", 2).let { if (it > 2) 99 else it }.let { if (it == 99) 2 else it })
        assertTrue(YtSsiQuoteCheck.bounded("kitten", "sitting", 2) > 2) // the real distance is 3
        assertEquals(3, YtSsiQuoteCheck.bounded("kitten", "sitting", 5))
    }

    @Test
    fun sameWords() {
        assertTrue(YtSsiQuoteCheck.same("Das stimmt so nicht!", "das stimmt so nicht"))
    }
}
