package com.github.libretube.util

import com.github.libretube.util.YtUpdateWatchRules.Run
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YtUpdateWatchRulesTest {
    private val min = 60_000L
    private val start = 1_800_000_000_000L

    @Test
    fun `slots at 12, 16, 20, 30 and 45 minutes, past ones skipped`() {
        assertEquals(start + 12 * min, YtUpdateWatchRules.nextSlot(start, start))
        assertEquals(start + 16 * min, YtUpdateWatchRules.nextSlot(start, start + 13 * min))
        assertEquals(start + 30 * min, YtUpdateWatchRules.nextSlot(start, start + 25 * min))
        // a slot due a moment ago is still taken
        assertEquals(start + 20 * min, YtUpdateWatchRules.nextSlot(start, start + 20 * min + 10_000))
        assertNull(YtUpdateWatchRules.nextSlot(start, start + 46 * min))
    }

    @Test
    fun `after a slot ran the next one follows`() {
        val first = start + 12 * min
        assertEquals(start + 16 * min, YtUpdateWatchRules.nextSlot(start, first + 5_000, after = first))
        val last = start + 45 * min
        assertNull(YtUpdateWatchRules.nextSlot(start, last, after = last))
        assertEquals(0L, YtUpdateWatchRules.delay(first, first + 1))
        assertEquals(4 * min, YtUpdateWatchRules.delay(start + 16 * min, start + 12 * min))
    }

    @Test
    fun `the runs are looked at at most every ten minutes`() {
        assertTrue(YtUpdateWatchRules.mayProbe(0, start))
        assertFalse(YtUpdateWatchRules.mayProbe(start - 9 * min, start))
        assertTrue(YtUpdateWatchRules.mayProbe(start - 10 * min, start))
        assertTrue(YtUpdateWatchRules.mayProbe(start + min, start))
    }

    @Test
    fun `the newest running build of main newer than the installed one is watched`() {
        val now = start + 5 * min
        val runs = listOf(
            Run(140, "in_progress", "r12/x", start),
            Run(135, "completed", "main", start - 30 * min),
            Run(134, "queued", "main", start),
            Run(133, "in_progress", "main", start - 2 * min),
        )
        assertEquals(134, YtUpdateWatchRules.pickRun(runs, 132, null, now)?.number)
        // installed or told already: nothing
        assertNull(YtUpdateWatchRules.pickRun(runs, 134, null, now))
        assertNull(YtUpdateWatchRules.pickRun(runs, 132, 134, now))
        // a run stuck for longer than the last slot is not watched
        assertNull(YtUpdateWatchRules.pickRun(listOf(Run(133, "queued", "main", start - 50 * min)), 132, null, now))
        assertEquals(133, YtUpdateWatchRules.pickRun(listOf(Run(133, "waiting", null, null)), 132, null, now)?.number)
        assertNull(YtUpdateWatchRules.pickRun(emptyList(), 132, null, now))
    }

    @Test
    fun `a watch is replaced only by a newer build or when idle`() {
        assertTrue(YtUpdateWatchRules.replaces(133, null, null, start))
        assertTrue(YtUpdateWatchRules.replaces(134, 133, start, start + min))
        assertFalse(YtUpdateWatchRules.replaces(133, 133, start, start + min))
        assertFalse(YtUpdateWatchRules.replaces(132, 133, start, start + min))
        assertFalse(YtUpdateWatchRules.replaces(null, 133, start, start + min))
        // the old watch is over: anything starts a new one
        assertTrue(YtUpdateWatchRules.replaces(null, 133, start, start + 50 * min))
        assertTrue(YtUpdateWatchRules.replaces(133, 133, start, start + 50 * min))
    }

    @Test
    fun `github times are read`() {
        assertEquals(1_800_000_000_000L, YtUpdateWatchRules.parseTime("2027-01-15T08:00:00Z"))
        assertNull(YtUpdateWatchRules.parseTime(null))
        assertNull(YtUpdateWatchRules.parseTime(""))
        assertNull(YtUpdateWatchRules.parseTime("yesterday"))
    }
}
