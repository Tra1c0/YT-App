package com.github.libretube.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YtFetchGateRulesTest {
    @Test fun pausedUntilIsLatestFutureOne() {
        assertNull(YtFetchGateRules.pausedUntil(listOf(0, 50, 100), 100))
        assertEquals(300L, YtFetchGateRules.pausedUntil(listOf(100, 300, 200), 150))
    }

    @Test fun waitOver() {
        assertFalse(YtFetchGateRules.waitOver(0, 1000, 999))
        assertTrue(YtFetchGateRules.waitOver(0, 1000, 1000))
    }

    @Test fun pauseDoubles() {
        val h = 3_600_000L
        assertEquals(6 * h, YtFetchGateRules.pauseMs(1, 6 * h, 48 * h))
        assertEquals(12 * h, YtFetchGateRules.pauseMs(2, 6 * h, 48 * h))
        assertEquals(48 * h, YtFetchGateRules.pauseMs(4, 6 * h, 48 * h))
        assertEquals(48 * h, YtFetchGateRules.pauseMs(9, 6 * h, 48 * h))
    }
}
