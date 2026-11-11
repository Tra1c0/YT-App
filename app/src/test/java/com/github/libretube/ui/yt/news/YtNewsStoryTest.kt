package com.github.libretube.ui.yt.news

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class YtNewsStoryTest {
    private val hour = 60L * 60 * 1000
    private val now = 1_800_000_000_000L

    private fun item(id: String, title: String, channel: String, hoursAgo: Long = 1) = YtNewsItem(
        id = id,
        title = title,
        channelId = "id-" + channel.lowercase().replace(' ', '-'),
        channelName = channel,
        uploadedMs = now - hoursAgo * hour,
    )

    /** A realistic morning of German and English news videos, newest first. */
    private val morning = listOf(
        item("t1", "Bundestag beschließt Heizungsgesetz nach langem Streit", "tagesschau", 1),
        item("z1", "Heizungsgesetz: Bundestag stimmt für umstrittene Reform | ZDFheute", "ZDFheute Nachrichten", 2),
        item("w1", "HEIZUNGSGESETZ: Bundestag beschließt Reform – Opposition tobt | WELT", "WELT Nachrichtensender", 3),
        item("t2", "Scholz trifft Macron in Paris", "tagesschau", 2),
        item("z2", "Scholz zur Haushaltskrise: \"Wir werden sparen müssen\"", "ZDFheute Nachrichten", 4),
        item("b1", "Trump announces new tariffs on China", "BBC News", 3),
        item("c1", "Trump slaps fresh tariffs on Chinese imports, Beijing vows response", "CNN", 5),
        item("d1", "Trump droht China mit neuen Zöllen", "DW Deutsch", 6),
        item("b2", "Trump says he will meet Putin next week", "BBC News", 4),
        item("t3", "Erdbeben in der Türkei: Zahl der Toten steigt auf über 100", "tagesschau", 7),
        item("z3", "Schweres Erdbeben erschüttert die Türkei – mehr als 100 Tote", "ZDFheute Nachrichten", 8),
        item("t4", "tagesschau 20:00 Uhr, 10.11.2026", "tagesschau", 20),
        item("t5", "tagesschau 20:00 Uhr, 11.11.2026", "tagesschau", 1),
        item("l1", "LIVE: Pressekonferenz im Kanzleramt", "phoenix", 2),
        item("l2", "LIVE: Breaking News – heute im Bundestag", "WELT Nachrichtensender", 3),
        item("u1", "Update: Neue Entwicklungen heute", "n-tv", 2),
        item("f1", "Fußball: Bayern gewinnt Topspiel gegen Dortmund", "sportschau", 5),
        item("f2", "Bundesliga: FC Bayern schlägt Borussia Dortmund 3:1", "ZDFheute Nachrichten", 6),
        item("k1", "Klimagipfel in Belém: Einigung auf neues Abkommen", "tagesschau", 9),
        item("k2", "COP30 climate summit reaches deal in Belém", "DW News", 10),
    )

    private fun groupsOf(items: List<YtNewsItem>, edits: YtNewsStoryEdits = YtNewsStoryEdits()): Set<Set<String>> =
        YtNewsStory.groups(items, edits).filter { it.size > 1 }.map { g -> g.map { items[it].id }.toSet() }.toSet()

    @Test
    fun groupsTheSameStoryFromSeveralSources() {
        val groups = groupsOf(morning)
        assertTrue(groups.toString(), setOf("t1", "z1", "w1") in groups)
        // the tariffs: at least two of the three reports (one says "Chinese imports", not "China")
        assertTrue(groups.toString(), groups.any { it.containsAll(listOf("b1", "d1")) || it.containsAll(listOf("b1", "c1")) })
        assertTrue(groups.toString(), setOf("t3", "z3") in groups)
        assertTrue(groups.toString(), setOf("f1", "f2") in groups)
    }

    @Test
    fun samePoliticianDifferentStoryStaysApart() {
        val groups = groupsOf(morning)
        val all = groups.flatten()
        // Scholz in Paris and Scholz on the budget; Trump on tariffs and Trump on Putin
        assertFalse(groups.toString(), groups.any { "t2" in it && "z2" in it })
        assertFalse(groups.toString(), groups.any { "b2" in it })
        assertFalse(all.contains("t2") || all.contains("z2"))
    }

    @Test
    fun samePeopleInOtherNewsStayApart() {
        val items = listOf(
            item("a", "Merz besucht Selenskyj in Kiew", "tagesschau", 1),
            item("b", "Merz verteidigt Rentenpaket im Bundestag", "ZDFheute", 3),
            item("c", "Starmer meets Macron in London", "BBC News", 2),
            item("d", "Starmer faces revolt over budget", "Sky News", 4),
            item("e", "Breaking News: Live Update heute", "n-tv", 2),
            item("f", "LIVE: Breaking News – Update", "WELT", 3),
        )
        assertTrue(groupsOf(items).toString(), groupsOf(items).isEmpty())
        assertTrue(YtNewsStory.cards(items).all { it is YtNewsCard.Single })
    }

    @Test
    fun genericWordsAndRecurringShowsNeverMakeAStory() {
        val all = groupsOf(morning).flatten()
        // "LIVE", "Breaking News", "heute", "Update" and the daily show by date
        for (id in listOf("l1", "l2", "u1", "t4", "t5")) assertFalse("$id grouped: ${groupsOf(morning)}", id in all)
    }

    @Test
    fun crossLanguageOnlyWithSharedNames() {
        // "Klimagipfel in Belém" and "COP30 climate summit … Belém" share only the place: no story
        val all = groupsOf(morning).flatten()
        assertFalse(all.contains("k1") && all.contains("k2") && groupsOf(morning).any { "k1" in it && "k2" in it })
    }

    @Test
    fun timeWindowSplitsOldReports() {
        val items = listOf(
            item("a", "Bundestag beschließt Heizungsgesetz nach langem Streit", "tagesschau", 1),
            item("b", "Heizungsgesetz: Bundestag stimmt für umstrittene Reform", "ZDFheute", 60),
        )
        assertTrue(groupsOf(items).isEmpty())
        val near = listOf(items[0], items[1].copy(uploadedMs = now - 30 * hour))
        assertEquals(setOf(setOf("a", "b")), groupsOf(near))
    }

    @Test
    fun unknownAgeIsNeverGrouped() {
        val items = listOf(
            item("a", "Bundestag beschließt Heizungsgesetz nach langem Streit", "tagesschau", 1),
            item("b", "Heizungsgesetz: Bundestag stimmt für umstrittene Reform", "ZDFheute", 1).copy(uploadedMs = 0L),
        )
        assertTrue(groupsOf(items).isEmpty())
    }

    @Test
    fun sameChannelNeedsMoreInCommon() {
        // two updates of one channel on the same story with three shared words: grouped
        val updates = listOf(
            item("a", "Erdbeben in der Türkei: Zahl der Toten steigt auf über 100", "tagesschau", 1),
            item("b", "Erdbeben in der Türkei: Rettungskräfte suchen nach Überlebenden, über 100 Tote", "tagesschau", 5),
        )
        assertEquals(setOf(setOf("a", "b")), groupsOf(updates))
        // two words of a recurring format of one channel: not grouped
        val format = listOf(
            item("a", "Markus Lanz vom 10. November: Wirtschaftskrise", "ZDF", 1),
            item("b", "Markus Lanz vom 11. November: Migration", "ZDF", 25),
        )
        assertTrue(groupsOf(format).isEmpty())
    }

    @Test
    fun deterministicAndKeysStable() {
        val first = YtNewsStory.cards(morning)
        val second = YtNewsStory.cards(morning)
        assertEquals(first, second)
        val story = first.filterIsInstance<YtNewsCard.Story>().first { s -> s.items.any { it.id == "t1" } }
        // a newer report joins: the key (from the oldest member) stays
        val later = listOf(item("n1", "Heizungsgesetz beschlossen: Was der Bundestag jetzt geändert hat", "BR24", 0)) + morning
        val again = YtNewsStory.cards(later).filterIsInstance<YtNewsCard.Story>().first { s -> s.items.any { it.id == "t1" } }
        assertEquals(story.key, again.key)
        assertTrue(again.items.any { it.id == "n1" })
        assertEquals("n1", again.lead.id)
    }

    @Test
    fun storyLeadIsNewestAndTimelineNewestFirst() {
        val cards = YtNewsStory.cards(morning)
        val story = cards.filterIsInstance<YtNewsCard.Story>().first { s -> s.items.any { it.id == "t1" } }
        assertEquals("t1", story.lead.id)
        assertEquals(listOf("t1", "z1", "w1"), story.timeline.map { it.id })
        assertEquals("story:w1", story.key)
        assertEquals(3, story.sources)
        // the cards keep the order of their first item; every item is on exactly one card
        assertEquals(morning.map { it.id }.toSet(), cards.flatMap { c -> c.items.map { it.id } }.toSet())
        assertEquals(morning.size, cards.sumOf { it.items.size })
        assertEquals("t1", cards.first().lead.id)
    }

    @Test
    fun detachedVideoStaysAlone() {
        val edits = YtNewsStoryEditRules.detach(YtNewsStoryEdits(), "w1", now)
        val groups = groupsOf(morning, edits)
        assertTrue(groups.toString(), setOf("t1", "z1") in groups)
        assertFalse(groups.flatten().contains("w1"))
        // taking out one of two leaves two singles
        val both = YtNewsStoryEditRules.detach(edits, "z1", now)
        assertFalse(groupsOf(morning, both).flatten().contains("t1"))
    }

    @Test
    fun mergedCardsStayTogether() {
        // Scholz in Paris merged with Scholz on the budget (the user knows better)
        val edits = YtNewsStoryEditRules.merge(YtNewsStoryEdits(), listOf("t2", "z2"), now)
        assertTrue(setOf("t2", "z2") in groupsOf(morning, edits))
        // a single merged into a story takes the whole story along
        val more = YtNewsStoryEditRules.merge(YtNewsStoryEdits(), listOf("t1", "z1", "w1", "l2"), now)
        assertTrue(setOf("t1", "z1", "w1", "l2") in groupsOf(morning, more))
        // then one taken out again: the rest stays together
        val less = YtNewsStoryEditRules.detach(more, "z1", now)
        val groups = groupsOf(morning, less)
        assertTrue(groups.toString(), setOf("t1", "w1", "l2") in groups)
        assertFalse(groups.flatten().contains("z1"))
    }

    @Test
    fun mergedDetachedVideoDoesNotFallBackIntoItsOldStory() {
        val out = YtNewsStoryEditRules.detach(YtNewsStoryEdits(), "w1", now)
        val merged = YtNewsStoryEditRules.merge(out, listOf("w1", "l2"), now)
        val groups = groupsOf(morning, merged)
        assertTrue(groups.toString(), setOf("w1", "l2") in groups)
        assertTrue(groups.toString(), setOf("t1", "z1") in groups)
    }

    @Test
    fun editsArePrunedWithAge() {
        val old = YtNewsStoryEditRules.merge(YtNewsStoryEdits(), listOf("a", "b", "c"), now - 9 * 24 * hour)
        val kept = YtNewsStoryEditRules.detach(old, "x", now)
        assertTrue(kept.links.isEmpty())
        assertEquals(listOf("x"), kept.apart.map { it.id })
        assertEquals(3, YtNewsStoryEditRules.merge(YtNewsStoryEdits(), listOf("a", "b", "c"), now).links.size)
        assertEquals(YtNewsStoryEdits(), YtNewsStoryEditRules.merge(YtNewsStoryEdits(), listOf("a"), now))
    }

    @Test
    fun wordsFoldStemAndSkipNoise() {
        val a = YtNewsStory.words("Trump droht China mit neuen Zöllen", "DW Deutsch")
        val b = YtNewsStory.words("Zölle: Trumps neue Drohung", null)
        assertTrue(a.keys.toString(), "zoll" in a && "zoll" in b)
        assertTrue("trump" in a && "trump" in b)
        val noise = YtNewsStory.words("LIVE: Breaking News – heute Update 20:00 Uhr, 10.11.2026", null)
        assertTrue(noise.toString(), noise.isEmpty())
        // the channel's own name is no word of the story
        assertFalse("welt" in YtNewsStory.words("Reform beschlossen | WELT", "WELT Nachrichtensender"))
        assertEquals(YtNewsStory.stem("prasidenten"), YtNewsStory.stem("prasident"))
        assertEquals(YtNewsStory.stem("bundestages"), YtNewsStory.stem("bundestag"))
        // acronyms and numbers count, with more weight
        val names = YtNewsStory.words("EU und NATO beraten über G7-Gipfel", null)
        assertTrue(names.toString(), "eu" in names && "nato" in names && "g7" in names)
        assertTrue(names.getValue("eu") > names.getValue(YtNewsStory.stem("beraten")))
    }

    @Test
    fun differentStoriesHaveDifferentKeys() {
        val stories = YtNewsStory.cards(morning).filterIsInstance<YtNewsCard.Story>()
        assertEquals(stories.size, stories.map { it.key }.distinct().size)
        stories.forEach { assertNotEquals(it.lead.id, it.key) }
    }
}
