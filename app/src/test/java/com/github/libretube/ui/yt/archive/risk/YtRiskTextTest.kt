package com.github.libretube.ui.yt.archive.risk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YtRiskTextTest {
    @Test
    fun wordsAreFoldedAndLinksDropped() {
        val words = YtRiskText.words("𝐋𝐄𝐀𝐊𝐄𝐃: Über die Größe! Re-Upload", "Mehr auf https://example.com/x?y=1 und www.test.de")
        assertEquals(listOf("leaked", "über", "die", "größe", "re", "upload", "mehr", "auf", "und"), words)
    }

    @Test
    fun onlyTheStartOfTheDescriptionCounts() {
        val description = "a".repeat(YtRiskText.DESCRIPTION_CHARS) + " geleakt"
        assertFalse("geleakt" in YtRiskText.words("Titel", description))
        assertEquals(YtRiskText.DESCRIPTION_CHARS, YtRiskText.descriptionStart(description).length)
    }

    @Test
    fun tokensSkipStopWordsNumbersAndShortWords() {
        val tokens = YtRiskText.tokens(YtRiskText.words("Der neue Rechtsstreit 2024 um das Video mit Ex", null))
        assertEquals(setOf("rechtsstreit"), tokens)
        val many = YtRiskText.tokens((1..100).map { "wort$it" })
        assertEquals(YtRiskText.MAX_TOKENS, many.size)
    }

    @Test
    fun keywordsMatchPhrasesAndPrefixesButNotOtherWords() {
        fun labels(title: String) = YtRiskText.keywords(YtRiskText.words(title, null)).map { it.label }
        assertEquals(listOf("Leak"), labels("Album leaked early"))
        assertEquals(listOf("Leak"), labels("Song geleakt – Leak des Jahres"))
        assertEquals(listOf("Reupload"), labels("Re-Upload: Interview"))
        assertEquals(listOf("wird gelöscht"), labels("Schnell schauen, bevor es gelöscht wird"))
        assertEquals(listOf("before it gets deleted"), labels("Watch before it's deleted"))
        assertTrue(labels("Ausflug nach Klagenfurt").isEmpty())
        assertTrue(labels("Ein dramatischer Abend").isEmpty())
        assertTrue(labels("Movie night: full of fun").isEmpty())
    }

    @Test
    fun displayCapitalises() {
        assertEquals("Rechtsstreit", YtRiskText.display("rechtsstreit"))
        assertEquals("Überwachung", YtRiskText.display("überwachung"))
    }
}
