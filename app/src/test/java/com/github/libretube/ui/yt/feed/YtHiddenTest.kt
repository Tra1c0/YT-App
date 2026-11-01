package com.github.libretube.ui.yt.feed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class YtHiddenTest {
    private val today = 20_000

    @Test
    fun aHiddenVideoComesBackAfterNinetyDays() {
        val hidden = YtHiddenRules.hide(emptyMap(), "a", today)
        assertTrue("a" in YtHiddenRules.prune(hidden, today + 89))
        assertFalse("a" in YtHiddenRules.prune(hidden, today + 90))
    }

    @Test
    fun pruneKeepsTheSameMapWhenNothingGoes() {
        val hidden = mapOf("a" to today, "b" to today - 3)
        assertSame(hidden, YtHiddenRules.prune(hidden, today))
    }

    @Test
    fun hidingAgainMovesItToTheNewestEnd() {
        var hidden = YtHiddenRules.hide(emptyMap(), "a", today - 5)
        hidden = YtHiddenRules.hide(hidden, "b", today - 4)
        hidden = YtHiddenRules.hide(hidden, "a", today)
        assertEquals(listOf("b", "a"), hidden.keys.toList())
        assertEquals(today, hidden["a"])
    }

    @Test
    fun theCapDropsTheOldestFirst() {
        var hidden = emptyMap<String, Int>()
        for (i in 0 until 6) hidden = YtHiddenRules.hide(hidden, "v$i", today - 10 + i / 2, cap = 4)
        assertEquals(listOf("v2", "v3", "v4", "v5"), hidden.keys.toList())
    }

    @Test
    fun theCapKeepsTheLaterOfOneDay() {
        var hidden = emptyMap<String, Int>()
        for (i in 0 until 5) hidden = YtHiddenRules.hide(hidden, "v$i", today, cap = 3)
        assertEquals(listOf("v2", "v3", "v4"), hidden.keys.toList())
    }

    @Test
    fun unhide() {
        val hidden = YtHiddenRules.hide(emptyMap(), "a", today)
        assertTrue(YtHiddenRules.unhide(hidden, "a").isEmpty())
        assertSame(hidden, YtHiddenRules.unhide(hidden, "x"))
    }

    @Test
    fun theStoredTextFitsABackup() {
        // the longest entry: an 11 character id and a five digit day, as JSON
        var hidden = emptyMap<String, Int>()
        for (i in 0 until YtHiddenRules.CAP + 20) hidden = YtHiddenRules.hide(hidden, "%011d".format(i), 99_999)
        val json = hidden.entries.joinToString(",", "{", "}") { "\"${it.key}\":${it.value}" }
        assertEquals(YtHiddenRules.CAP, hidden.size)
        assertTrue(json.length < 10_000)
    }

    @Test
    fun visibilityDropsHiddenVideosAndPausedChannels() {
        data class V(val id: String?, val channel: String?, val name: String?)
        val now = 1_000_000L
        val list = listOf(V("a", "UC1", "Eins"), V("b", "UC2", "Zwei"), V("c", null, "Drei"), V("d", "UC4", "Vier"))
        val hidden = mapOf("a" to today)
        val paused = listOf(
            YtPausedChannel(id = "UC2", name = "Zwei", untilMillis = now + 1),
            YtPausedChannel(id = null, name = "drei ", untilMillis = now + 1),
            YtPausedChannel(id = "UC4", name = "Vier", untilMillis = now),
        )
        val shown = YtVisibility.visible(list, hidden, paused, now, { it.id }, { it.channel }, { it.name })
        assertEquals(listOf("d"), shown?.map { it.id })
    }

    @Test
    fun visibilityKeepsTheListWhenNothingGoes() {
        val list = listOf("a", "b")
        assertSame(list, YtVisibility.visible(list, mapOf("x" to today), emptyList(), 0L, { it }, { null }, { null }))
        assertSame(list, YtVisibility.visible(list, emptyMap(), emptyList(), 0L, { it }, { null }, { null }))
        assertNull(YtVisibility.visible<String>(null, mapOf("a" to today), emptyList(), 0L, { it }, { null }, { null }))
    }
}
