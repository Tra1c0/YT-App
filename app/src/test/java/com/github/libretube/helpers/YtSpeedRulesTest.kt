package com.github.libretube.helpers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YtSpeedRulesTest {
    @Test
    fun speedStaysInTheSinksRange() {
        assertEquals(0.1f, YtSpeedRules.clampSpeed(0f), 0f)
        assertEquals(8f, YtSpeedRules.clampSpeed(20f), 0f)
        assertEquals(1.25f, YtSpeedRules.clampSpeed(1.25f), 0f)
        assertEquals(1f, YtSpeedRules.clampSpeed(Float.NaN), 0f)
        assertEquals(1f, YtSpeedRules.clampSpeed(Float.POSITIVE_INFINITY), 0f)
        // rounded, so a value a hair away is the same value
        assertEquals(1.5f, YtSpeedRules.clampSpeed(1.5000001f), 0f)
    }

    @Test
    fun pitchStaysWithinAnOctave() {
        assertEquals(0.5f, YtSpeedRules.clampPitch(0.01f), 0f)
        assertEquals(2f, YtSpeedRules.clampPitch(5f), 0f)
        assertEquals(1f, YtSpeedRules.clampPitch(Float.NaN), 0f)
        assertEquals(1.059f, YtSpeedRules.clampPitch(1.059463f), 0f)
    }

    @Test
    fun semitonesSnapToTheirGrid() {
        assertEquals(1.25f, YtSpeedRules.quantizeSemitone(1.2634f), 0f)
        assertEquals(-12f, YtSpeedRules.quantizeSemitone(-30f), 0f)
        assertEquals(0f, YtSpeedRules.quantizeSemitone(Float.NaN), 0f)
        assertEquals(0f, YtSpeedRules.quantizeSemitone(0.02f), 0f)
    }

    @Test
    fun sliderGetsAValueOnItsGrid() {
        assertEquals(4f, YtSpeedRules.sliderSpeed(6f), 0f)
        assertEquals(0.25f, YtSpeedRules.sliderSpeed(0.1f), 0f)
        assertEquals(1f, YtSpeedRules.sliderSpeed(1.1f), 0f)
        assertEquals(1.25f, YtSpeedRules.sliderSpeed(1.2f), 0f)
    }

    @Test
    fun aChannelSpeedWinsAndTheNextChannelGoesBack() {
        assertEquals(1.75f, YtSpeedRules.startSpeed(1.75f, 1f, lastWasChannelSpeed = false))
        assertEquals(1.25f, YtSpeedRules.startSpeed(null, 1.25f, lastWasChannelSpeed = true))
        // without a channel speed before, the speed is left as it is (like upstream)
        assertNull(YtSpeedRules.startSpeed(null, 1.25f, lastWasChannelSpeed = false))
    }

    @Test
    fun entriesAreReplacedAndRemovedById() {
        var list = YtSpeedRules.put(emptyList(), "UC1", "A", 1.5f)
        list = YtSpeedRules.put(list, "UC2", "B", 2f)
        list = YtSpeedRules.put(list, "UC1", "A", 1.75f)
        assertEquals(2, list.size)
        assertEquals(1.75f, YtSpeedRules.find(list, "UC1")?.speed)
        list = YtSpeedRules.remove(list, "UC1")
        assertNull(YtSpeedRules.find(list, "UC1"))
        assertNull(YtSpeedRules.find(list, null))
        assertNull(YtSpeedRules.find(list, ""))
    }
}

class YtThrottleTest {
    @Test
    fun firstChangeGoesAtOnceTheNextWaits() {
        val t = YtThrottle(150)
        assertEquals(0L, t.delayAt(1_000))
        t.sent(1_000)
        assertEquals(150L, t.delayAt(1_000))
        assertEquals(50L, t.delayAt(1_100))
        assertEquals(0L, t.delayAt(1_150))
        assertEquals(0L, t.delayAt(5_000))
    }

    @Test
    fun aDragOfManyStepsSendsAboutOncePerInterval() {
        // a drag reporting every 8 ms for one second; a send happens whenever the wait is over
        val t = YtThrottle(150)
        var sends = 0
        var due = -1L
        for (now in 0L..1_000L step 8) {
            if (due in 0..now) {
                t.sent(now); sends++; due = -1
            }
            val d = t.delayAt(now)
            if (d == 0L) {
                t.sent(now); sends++
            } else if (due < 0) {
                due = now + d
            }
        }
        // the trailing change is still due after the loop: it is sent then
        assertTrue("sends=$sends", sends in 6..8)
    }
}
