package com.github.libretube.ui.yt.ssi

import com.github.libretube.ui.yt.ssi.YtSsiIndexRules.Candidate
import com.github.libretube.ui.yt.ssi.YtSsiIndexRules.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YtSsiIndexRulesTest {
    private val day = 24L * 60 * 60 * 1000
    private val now = 1000 * day

    private fun c(id: String, ageDays: Int, passages: Int = 100, prot: Boolean = false, watched: Boolean = false) =
        Candidate(id, now - ageDays * day, passages, prot, watched)

    @Test fun planOrder() {
        assertEquals(listOf(Step.CUT_AND_EMBED, Step.EMBED, Step.RECUT), YtSsiIndexRules.plan(3, 50, 10, true))
        assertEquals(listOf(Step.EMBED, Step.RECUT), YtSsiIndexRules.plan(0, 50, 10, true))
        assertEquals(listOf(Step.RECUT), YtSsiIndexRules.plan(0, 0, 10, true))
        assertEquals(emptyList<Step>(), YtSsiIndexRules.plan(3, 50, 10, false))
    }

    @Test fun localBudgetIsWholeRun() {
        assertEquals(9 * 60 * 1000L, YtSsiIndexRules.localDeadline(0))
        assertTrue(YtSsiIndexRules.LOCAL_BUDGET_MS < 10 * 60 * 1000L)
    }

    @Test fun chain() {
        assertTrue(YtSsiIndexRules.followUp(10, true, false, false, 0))
        assertFalse(YtSsiIndexRules.followUp(0, true, false, false, 0))
        assertFalse(YtSsiIndexRules.followUp(10, false, false, false, 0))
        assertFalse(YtSsiIndexRules.followUp(10, true, true, false, 0))
        assertFalse(YtSsiIndexRules.followUp(10, true, false, true, 0))
        assertFalse(YtSsiIndexRules.followUp(10, true, false, false, YtSsiIndexRules.MAX_IDLE_FOLLOW_UPS))
        assertEquals(0, YtSsiIndexRules.idleCount(2, true))
        assertEquals(3, YtSsiIndexRules.idleCount(2, false))
    }

    @Test fun windowParsing() {
        assertEquals(30, YtSsiIndexRules.windowDays(null))
        assertEquals(0, YtSsiIndexRules.windowDays("0"))
        assertEquals(90, YtSsiIndexRules.windowDays("90"))
        assertEquals(30, YtSsiIndexRules.windowDays("7"))
    }

    @Test fun evictsOldestBeyondWindowSkippingProtectedAndWatched() {
        val list = listOf(c("new", 5), c("old1", 50), c("old2", 40), c("prot", 80, prot = true), c("seen", 70, watched = true))
        assertEquals(listOf("old1", "old2"), YtSsiIndexRules.evictions(list, now, 30))
        assertEquals(emptyList<String>(), YtSsiIndexRules.evictions(list, now, 0))
    }

    @Test fun capEvictsUnprotectedThenWatchedNeverProtected() {
        val list = listOf(
            c("a", 10, 100), c("b", 20, 100), c("seen", 30, 100, watched = true), c("prot", 40, 100, prot = true),
        )
        // total 400, cap 250: a/b go first (oldest b), then watched only if needed
        assertEquals(listOf("b", "a"), YtSsiIndexRules.evictions(list, now, 0, cap = 250))
        assertEquals(listOf("b", "a", "seen"), YtSsiIndexRules.evictions(list, now, 0, cap = 100))
        assertEquals(listOf("b", "a", "seen"), YtSsiIndexRules.evictions(list, now, 0, cap = 0))
    }

    @Test fun windowEvictionCountsTowardCap() {
        val list = listOf(c("x", 60, 200), c("y", 5, 100), c("z", 3, 100))
        assertEquals(listOf("x"), YtSsiIndexRules.evictions(list, now, 30, cap = 250))
    }

    @Test fun tombstonesBlockIndexing() {
        assertFalse(YtSsiIndexRules.mayIndex("a", setOf("a")))
        assertTrue(YtSsiIndexRules.mayIndex("b", setOf("a")))
    }

    @Test fun memoryRelease() {
        val t = 10_000_000L
        assertFalse(YtSsiIndexRules.releaseVectors(false, 0, t))
        assertFalse(YtSsiIndexRules.releaseVectors(true, t - 60_000, t))
        assertTrue(YtSsiIndexRules.releaseVectors(true, t - 5 * 60_000, t))
        assertTrue(YtSsiIndexRules.releaseVectors(true, t, t, trimLevel = 10))
        assertTrue(YtSsiIndexRules.releaseVectors(true, t, t, trimLevel = 15))
        assertFalse(YtSsiIndexRules.releaseVectors(true, t, t, trimLevel = 5))
        assertFalse(YtSsiIndexRules.releaseVectors(true, t, t, trimLevel = 20))
    }
}
