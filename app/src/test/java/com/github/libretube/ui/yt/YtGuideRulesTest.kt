package com.github.libretube.ui.yt

import com.github.libretube.ui.yt.YtGuideRules.Entry
import com.github.libretube.ui.yt.YtGuideRules.Group
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YtGuideRulesTest {
    private val groups = listOf(
        Group(
            "basics", "Grundsätzlich", listOf(
                Entry("no_ads", "Keine Werbung", "Videos laufen ohne Werbeclips."),
                Entry("ryd", "Mag-ich-nicht-Zahlen", "Schätzungen von Return YouTube Dislike.", "Einstellungen → Allgemein"),
            )
        ),
        Group(
            "player", "Player", listOf(
                Entry("magnifier", "Zeit unter der Lupe", "Beim Spulen den Finger nach oben ziehen."),
                Entry("speed", "Geschwindigkeit je Kanal", "Für diesen Kanal merken."),
            )
        ),
        Group(
            "playback", "Wiedergabe und Ton", listOf(
                Entry("loud", "Lautstärke angleichen", "Hebt leise Stellen an.", "Einstellungen → Audio und Video → Lautstärke"),
            )
        ),
    )

    @Test
    fun blankQueryKeepsEverything() {
        assertEquals(groups, YtGuideRules.filter(groups, ""))
        assertEquals(groups, YtGuideRules.filter(groups, "   "))
        assertEquals(5, YtGuideRules.count(groups))
    }

    @Test
    fun filterKeepsOrderAndDropsEmptySections() {
        val shown = YtGuideRules.filter(groups, "kanal")
        assertEquals(listOf("player"), shown.map { it.key })
        assertEquals(listOf("speed"), shown.single().entries.map { it.key })
    }

    @Test
    fun caseAccentsAndUmlautsAreFolded() {
        assertEquals(listOf("loud"), YtGuideRules.filter(groups, "LAUTSTARKE").flatMap { it.entries }.map { it.key })
        assertEquals(listOf("loud"), YtGuideRules.filter(groups, "lautstärke").flatMap { it.entries }.map { it.key })
        assertEquals("strasse grosse", YtGuideRules.normalize("  Straße   GRÖSSE "))
    }

    @Test
    fun everyTermMustMatchSomewhere() {
        // "lupe" in the title, "finger" in the text
        assertEquals(listOf("magnifier"), YtGuideRules.filter(groups, "lupe finger").flatMap { it.entries }.map { it.key })
        assertTrue(YtGuideRules.filter(groups, "lupe werbung").isEmpty())
    }

    @Test
    fun settingsPathAndSectionTitleCount() {
        assertEquals(listOf("ryd"), YtGuideRules.filter(groups, "allgemein").flatMap { it.entries }.map { it.key })
        // the section title lists the whole section
        assertEquals(listOf("magnifier", "speed"), YtGuideRules.filter(groups, "player").flatMap { it.entries }.map { it.key })
    }

    @Test
    fun firstSectionOpensUnlessChosen() {
        assertTrue(YtGuideRules.expanded("basics", 0, "", emptyMap()))
        assertFalse(YtGuideRules.expanded("player", 1, "", emptyMap()))
        assertFalse(YtGuideRules.expanded("basics", 0, "", mapOf("basics" to false)))
        assertTrue(YtGuideRules.expanded("player", 1, "", mapOf("player" to true)))
    }

    @Test
    fun searchingOpensEveryHit() {
        assertTrue(YtGuideRules.expanded("player", 1, "lupe", mapOf("player" to false)))
    }

    @Test
    fun contentHasUniqueKeysAndNoEmptySection() {
        val keys = YtGuide.sections.flatMap { s -> s.items.map { it.key } }
        assertEquals(keys.size, keys.toSet().size)
        assertEquals(YtGuide.sections.size, YtGuide.sections.map { it.key }.toSet().size)
        assertTrue(YtGuide.sections.all { it.items.isNotEmpty() })
        // the item with a target names its place in words too, so the text alone is enough
        assertTrue(YtGuide.sections.flatMap { it.items }.filter { it.target != null }.all { it.where != null })
    }
}
