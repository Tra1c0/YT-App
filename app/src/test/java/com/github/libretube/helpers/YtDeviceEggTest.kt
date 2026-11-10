package com.github.libretube.helpers

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YtDeviceEggTest {

    @Test
    fun modelGateMatchesOnlyTheTargetFamily() {
        assertTrue(YtDeviceEgg.modelMatches("SM-G998B"))
        assertTrue(YtDeviceEgg.modelMatches("SM-G998U"))
        // case-insensitive and the plain prefix
        assertTrue(YtDeviceEgg.modelMatches("sm-g998b"))
        assertTrue(YtDeviceEgg.modelMatches("SM-G998"))
        // other Galaxy models and null do not match
        assertFalse(YtDeviceEgg.modelMatches("SM-G991B"))
        assertFalse(YtDeviceEgg.modelMatches("SM-G973F"))
        assertFalse(YtDeviceEgg.modelMatches(null))
        assertFalse(YtDeviceEgg.modelMatches(""))
    }

    @Test
    fun waveformHasTheRightLengthAndStaysInRange() {
        val sampleRate = 44100
        val durationSec = 1.7f
        val wave = YtDeviceEgg.fartWaveform(sampleRate, durationSec)
        assertEquals((sampleRate * durationSec).toInt(), wave.size)

        var max = 0f
        for (s in wave) {
            // never leaves [-1, 1], and with headroom never reaches the rail (no clipping)
            assertTrue("sample out of range: $s", s >= -1f && s <= 1f)
            assertTrue("sample clipped: $s", abs(s) < 1f)
            if (abs(s) > max) max = abs(s)
        }
        // non-silent: it actually makes a sound
        assertTrue("too quiet, max=$max", max > 0.3f)
    }

    @Test
    fun waveformEnvelopeRisesFromAndDecaysToSilence() {
        val sampleRate = 44100
        val wave = YtDeviceEgg.fartWaveform(sampleRate, 1.7f)

        // the very first sample starts at (near) zero: a quick attack, not a click
        assertTrue("start not silent: ${wave.first()}", abs(wave.first()) < 0.1f)

        fun peak(range: IntRange): Float {
            var m = 0f
            for (i in range) if (abs(wave[i]) > m) m = abs(wave[i])
            return m
        }

        // the body (middle) is clearly louder than the first and last few milliseconds
        val head = peak(0 until sampleRate / 100)
        val tail = peak(wave.size - sampleRate / 100 until wave.size)
        val body = peak(wave.size / 3 until wave.size / 3 + sampleRate / 100)
        assertTrue("body not louder than the attack", body > head)
        assertTrue("body not louder than the release", body > tail)
        // the tail has decayed well down from the body
        assertTrue("did not decay: tail=$tail body=$body", tail < body * 0.6f)
    }
}
