package com.github.libretube.ui.yt.news

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YtNewsTextTest {
    @Test
    fun keepsTheStoryAndDropsTheFooter() {
        val raw = """
            Die Regierung hat heute ein neues Gesetz beschlossen.
            Mehr dazu: https://www.tagesschau.de/inland/gesetz-100.html
            
            #Gesetz #Bundestag #News
            ----------
            Folgt uns auf Instagram: https://instagram.com/tagesschau
            Impressum: https://www.tagesschau.de/impressum
        """.trimIndent()
        assertEquals("Die Regierung hat heute ein neues Gesetz beschlossen.", YtNewsText.clean(raw))
    }

    @Test
    fun linksHashtagsHandlesAndEmojiGoFromALine() {
        val raw = "🔴 Breaking: Sturm über Hamburg #Sturm via @NDR – Bilder auf ndr.de/sturm und www.ndr.de"
        assertEquals("Breaking: Sturm über Hamburg via – Bilder auf und", YtNewsText.clean(raw))
    }

    @Test
    fun chaptersAndCallsToActionGo() {
        val raw = """
            Was heute wichtig ist.
            00:00 Intro
            01:23 Wetter
            (1:02:03) Sport
            Abonniert unseren Kanal!
            Eine lange Zeile, die von Abonnenten erzählt, die man nicht wegwirft, weil sie wirklich eine echte Geschichte erzählt und viele Wörter hat.
        """.trimIndent()
        val text = YtNewsText.clean(raw)
        assertTrue(text.startsWith("Was heute wichtig ist."))
        assertFalse(text.contains("Intro"))
        assertFalse(text.contains("Wetter"))
        assertFalse(text.contains("Abonniert unseren"))
        assertTrue(text.contains("echte Geschichte"))
    }

    @Test
    fun aLineThatOnlyLabelsALinkGoes() {
        assertEquals("", YtNewsText.clean("Quelle: https://example.com/a"))
        // with enough words of its own it stays, without the link
        assertEquals("Die ganze Rede des Kanzlers zum Nachlesen", YtNewsText.clean("Die ganze Rede des Kanzlers zum Nachlesen: https://example.com/a"))
    }

    @Test
    fun linesJoinAndLongTextIsCutAtAWord() {
        assertEquals("Erste Zeile. Zweite Zeile.", YtNewsText.clean("Erste Zeile.\n\nZweite Zeile."))
        val long = (1..80).joinToString(" ") { "Wort$it" }
        val cut = YtNewsText.clean(long, maxChars = 60)
        assertTrue(cut.endsWith("…"))
        assertTrue(cut.length <= 61)
        assertFalse(cut.dropLast(1).endsWith(" "))
        assertTrue(long.startsWith(cut.dropLast(1)))
    }

    @Test
    fun emptyOrSymbolsOnlyIsEmpty() {
        assertEquals("", YtNewsText.clean(null))
        assertEquals("", YtNewsText.clean("   "))
        assertEquals("", YtNewsText.clean("👉👉 #news #politik\n-----\n"))
    }

    @Test
    fun aHashInsideAWordOrAnAnchorStays() {
        assertEquals("C# und F# im Vergleich", YtNewsText.clean("C# und F# im Vergleich"))
    }
}
