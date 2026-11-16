package com.github.libretube.ui.yt.ssi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YtSsiJson3GateTest {
    private val hour = 60L * 60 * 1000

    /** Asks the gate for [ids] one after the other; true where json3 was tried. */
    private fun ask(state: YtSsiJson3State, ids: List<String>, now: Long): Pair<List<Boolean>, YtSsiJson3State> {
        var s = state
        val tried = ids.map { id -> YtSsiJson3Gate.decide(s, id, now).let { (t, next) -> s = next; t } }
        return tried to s
    }

    @Test
    fun anEmptyAnswerPausesJson3ForTheNextVideos() {
        var s = YtSsiJson3Gate.empty(YtSsiJson3State(), "v0", 0)
        assertEquals(1, s.empty)
        assertEquals(YtSsiJson3Gate.SKIP_VIDEOS, s.skipLeft)
        val (tried, after) = ask(s, (1..7).map { "v$it" }, hour)
        // five left out, then the next one tries again
        assertEquals(listOf(false, false, false, false, false, true, true), tried)
        assertEquals(5, after.skipped)
        s = YtSsiJson3Gate.ok(after, "v6")
        assertEquals(1, s.ok)
        assertEquals(0, s.streak)
    }

    @Test
    fun emptyAnswersInARowPauseLongerUpToTheLimit() {
        var s = YtSsiJson3State()
        val skips = (1..6).map { s = YtSsiJson3Gate.empty(s, "v$it", 0); s.skipLeft }
        assertEquals(listOf(5, 10, 20, 40, 40, 40), skips)
    }

    @Test
    fun thePauseEndsAfterAWhile() {
        val s = YtSsiJson3Gate.empty(YtSsiJson3State(), "v0", 0)
        val (tried, after) = ask(s, listOf("a", "b"), YtSsiJson3Gate.SKIP_MS + 1)
        assertEquals(listOf(true, true), tried)
        assertEquals(0, after.skipLeft)
    }

    @Test
    fun aVideoWithAnEmptyJson3GoesStraightToTheFallback() {
        var s = YtSsiJson3Gate.empty(YtSsiJson3State(), "v0", 0)
        s = YtSsiJson3Gate.ok(s.copy(skipLeft = 0), "other")
        assertFalse(YtSsiJson3Gate.decide(s, "v0", hour).first)
        assertTrue(YtSsiJson3Gate.decide(s, "v1", hour).first)
        // forgotten after two weeks
        assertTrue(YtSsiJson3Gate.decide(s, "v0", YtSsiJson3Gate.FAILED_KEEP_MS + 1).first)
    }

    @Test
    fun theFailedListIsBounded() {
        var s = YtSsiJson3State()
        for (i in 0 until YtSsiJson3Gate.MAX_FAILED + 50) s = YtSsiJson3Gate.empty(s, "v$i", i.toLong())
        assertEquals(YtSsiJson3Gate.MAX_FAILED, s.failed.size)
        assertTrue("v0" !in s.failed)
        assertTrue("v${YtSsiJson3Gate.MAX_FAILED + 49}" in s.failed)
    }

    @Test
    fun theDiagnoseLine() {
        var s = YtSsiJson3State()
        s = YtSsiJson3Gate.ok(s, "a")
        assertEquals("json3 ok 1 / empty 0 / fallback 0", YtSsiJson3Gate.describe(s, 0))
        s = YtSsiJson3Gate.fallback(YtSsiJson3Gate.empty(s, "b", 0))
        s = YtSsiJson3Gate.decide(s, "c", 1).second
        assertEquals("json3 ok 1 / empty 1 / fallback 1 (skipped 1, paused for 4 more videos)", YtSsiJson3Gate.describe(s, 1))
    }
}
