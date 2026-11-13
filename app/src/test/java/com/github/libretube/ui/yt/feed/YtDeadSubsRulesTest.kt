package com.github.libretube.ui.yt.feed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YtDeadSubsRulesTest {
    private val now = 1_800_000_000_000L
    private val day = YT_DAY_MILLIS

    private fun check(id: String, state: YtSubState, last: Long = 0, at: Long = now - day) =
        YtSubCheck(id, state, last, at)

    @Test
    fun `a year and a day without upload is dead, a year minus a day is not`() {
        assertTrue(YtDeadSubsRules.isDead(now - 366 * day, now))
        assertFalse(YtDeadSubsRules.isDead(now - 364 * day, now))
        assertTrue(YtDeadSubsRules.isDead(0, now))
    }

    @Test
    fun `latest keeps the newest upload per channel and skips unknown ones`() {
        val latest = YtDeadSubsRules.latest(
            listOf("a" to 10L, "a" to 30L, "b" to 5L, null to 99L, "" to 99L, "c" to 0L),
        )
        assertEquals(mapOf("a" to 30L, "b" to 5L), latest)
    }

    @Test
    fun `channels in the feed and freshly checked ones need no request`() {
        val feed = mapOf("a" to now - day)
        val checks = mapOf(
            "b" to check("b", YtSubState.UPLOADS, now - 400 * day),
            "c" to check("c", YtSubState.UPLOADS, now - 400 * day, at = now - 8 * day),
        )
        assertEquals(listOf("c", "d"), YtDeadSubsRules.toCheck(listOf("a", "b", "c", "d", "d"), feed, checks, now))
    }

    @Test
    fun `an old video in the feed does not save a channel from a check`() {
        val feed = mapOf("a" to now - 500 * day)
        assertEquals(listOf("a"), YtDeadSubsRules.toCheck(listOf("a"), feed, emptyMap(), now))
    }

    @Test
    fun `verdict lists dead channels and counts the unchecked ones`() {
        val feed = mapOf("alive" to now - 2 * day)
        val checks = mapOf(
            "old" to check("old", YtSubState.UPLOADS, now - 800 * day),
            "older" to check("older", YtSubState.UPLOADS, now - 900 * day),
            "recent" to check("recent", YtSubState.UPLOADS, now - 100 * day),
            "empty" to check("empty", YtSubState.NONE),
            "gone" to check("gone", YtSubState.GONE),
        )
        val result = YtDeadSubsRules.verdict(
            listOf("alive", "old", "older", "recent", "empty", "gone", "unknown"), feed, checks, now,
        )
        assertEquals(listOf("gone", "empty", "older", "old"), result.dead.map { it.id })
        assertEquals(1, result.unknown)
        assertEquals(now - 900 * day, result.dead[2].last)
    }

    @Test
    fun `a stale check counts as unknown`() {
        val checks = mapOf("a" to check("a", YtSubState.NONE, at = now - 8 * day))
        val result = YtDeadSubsRules.verdict(listOf("a"), emptyMap(), checks, now)
        assertTrue(result.dead.isEmpty())
        assertEquals(1, result.unknown)
    }

    @Test
    fun `a check from the future is not trusted`() {
        assertFalse(YtDeadSubsRules.fresh(check("a", YtSubState.NONE, at = now + day), now))
    }

    @Test
    fun `the newer of feed and check wins`() {
        // the check says no videos, the feed has an old one: dead, with the feed's date
        val feed = mapOf("a" to now - 500 * day)
        val checks = mapOf("a" to check("a", YtSubState.NONE))
        val dead = YtDeadSubsRules.verdict(listOf("a"), feed, checks, now).dead.single()
        assertEquals(YtSubState.UPLOADS, dead.state)
        assertEquals(now - 500 * day, dead.last)
    }

    @Test
    fun `prune drops unsubscribed channels and stale checks`() {
        val checks = listOf(
            check("a", YtSubState.NONE),
            check("b", YtSubState.NONE),
            check("c", YtSubState.NONE, at = now - 30 * day),
        )
        assertEquals(listOf("a"), YtDeadSubsRules.prune(checks, listOf("a", "c"), now).map { it.id })
    }

    @Test
    fun `merge keeps the newer check of a channel`() {
        val old = check("a", YtSubState.NONE, at = now - 2 * day)
        val new = check("a", YtSubState.UPLOADS, now - 3 * day, at = now)
        val merged = YtDeadSubsRules.merge(listOf(old, check("b", YtSubState.GONE)), listOf(new))
        assertEquals(2, merged.size)
        assertEquals(new, merged.first { it.id == "a" })
    }

    @Test
    fun `fromDates tells empty, dated and undated lists apart`() {
        assertEquals(YtSubState.NONE, YtDeadSubsRules.fromDates("a", emptyList(), now)?.state)
        val dated = YtDeadSubsRules.fromDates("a", listOf(0L, 5L, 9L), now)!!
        assertEquals(YtSubState.UPLOADS, dated.state)
        assertEquals(9L, dated.last)
        assertEquals(now, dated.at)
        assertNull(YtDeadSubsRules.fromDates("a", listOf(0L, 0L), now))
    }
}
