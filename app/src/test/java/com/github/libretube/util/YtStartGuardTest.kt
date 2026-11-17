package com.github.libretube.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class YtStartGuardTest {
    @Test
    fun emptyRecordIsOk() {
        assertEquals("ok", YtStartGuard.Record().describe())
    }

    @Test
    fun describesFailedParts() {
        val record = YtStartGuard.Record()
        record.add("queue", IllegalStateException("boom"))
        record.add("ssi", RuntimeException())
        assertEquals("queue (IllegalStateException: boom); ssi (RuntimeException)", record.describe())
    }

    @Test
    fun keepsOnlyTheNewestTwenty() {
        val record = YtStartGuard.Record()
        repeat(25) { record.add("p$it", RuntimeException("x")) }
        assertEquals(YtStartGuard.MAX, record.failures().size)
        assertEquals("p5", record.failures().first().part)
        assertEquals("p24", record.failures().last().part)
    }

    @Test
    fun ytStartSwallowsAndContinues() {
        var after = false
        ytStart("a") { error("x") }
        ytStart("b") { after = true }
        assertTrue(after)
        assertTrue(YtStartGuard.record.failures().any { it.part == "a" })
    }
}
