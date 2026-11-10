package com.github.libretube.helpers

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

/**
 * The tap through media3's own TeeAudioProcessor: the sound passes unchanged, the sound
 * features are kept per analysis frame and found again for the moment that is heard.
 */
@UnstableApi
class YtAudioTapSoundTest {
    private val rate = 44_100

    @After
    fun off() {
        YtAudioTap.soundListening = false
        YtAudioTap.listening = false
        YtAudioTap.bufferSizeUs = null
    }

    /** [seconds] of mono 16-bit: an 80 Hz tone, with a loud 3 kHz burst from [burstAt] s for 30 ms. */
    private fun pcm(seconds: Double, burstAt: Double): ByteBuffer {
        val frames = (seconds * rate).toInt()
        val b = ByteBuffer.allocateDirect(frames * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (n in 0 until frames) {
            val t = n.toDouble() / rate
            var v = 0.2 * sin(2 * PI * 80 * t)
            if (t >= burstAt && t < burstAt + 0.03) v += 0.6 * sin(2 * PI * 3000 * t)
            b.putShort((v * 32767).toInt().toShort())
        }
        b.flip()
        return b
    }

    @Test
    fun featuresFollowTheHeardMomentAndSoundPassesUnchanged() {
        YtAudioTap.soundListening = true
        YtAudioTap.bufferSizeUs = { 200_000L }
        val tee = YtAudioTap.processor()
        tee.configure(AudioProcessor.AudioFormat(rate, 1, C.ENCODING_PCM_16BIT))
        tee.flush(AudioProcessor.StreamMetadata.DEFAULT)
        val generation = YtAudioTap.generation
        val input = pcm(3.0, burstAt = 2.0)
        val copy = input.duplicate()
        // in chunks, as the sink hands them
        val chunk = 4096
        val passed = ByteBuffer.allocate(input.remaining())
        while (input.hasRemaining()) {
            val slice = input.slice().order(ByteOrder.LITTLE_ENDIAN)
            slice.limit(minOf(chunk, input.remaining()))
            input.position(input.position() + slice.limit())
            tee.queueInput(slice)
            val out = tee.output
            passed.put(out)
        }
        passed.flip()
        assertEquals("audio passes unchanged", copy, passed)

        // all 3 s were handed on; with 200 ms lead, what is heard is at 2.8 s
        val lead = YtAudioTap.leadMs(1f)
        assertEquals(200L, lead)
        val start = YtAudioTap.startOf(generation, positionMs = 12_800, leadMs = lead)
        assertNotNull(start)
        assertEquals(10_000L, start!!)

        val out = FloatArray(YtAudioTap.FEATURES)
        val calm = YtAudioTap.frameAt(11_500, start)
        assertTrue(YtAudioTap.soundAt(calm, out))
        assertEquals(1f, out[YtSoundFeatures.AUDIBLE])
        assertTrue("80 Hz shows as bass: ${out.toList()}", out[YtSoundFeatures.BASS] > 0.4f)
        assertTrue("no highs before the burst: ${out.toList()}", out[YtSoundFeatures.HIGH] < 0.2f)

        // the burst at 2.0 s of the stream: its onset is found in the frames heard around then
        val before = YtAudioTap.frameAt(11_950, start)
        val after = YtAudioTap.frameAt(12_060, start)
        val peak = YtAudioTap.onsetPeak(before, after)
        assertTrue("the burst is an onset: $peak", peak > 0.6f)
        assertTrue("no onset in a calm stretch", YtAudioTap.onsetPeak(YtAudioTap.frameAt(11_300, start), YtAudioTap.frameAt(11_400, start)) < 0.3f)
        assertTrue(YtAudioTap.soundAt(YtAudioTap.frameAt(12_015, start), out))
        assertTrue("highs during the burst: ${out.toList()}", out[YtSoundFeatures.HIGH] > 0.5f)

        // the wave's bands come from the same analyses
        val bands = FloatArray(YtAudioTap.BANDS)
        assertTrue(YtAudioTap.bandsAt(11_500, start, bands))

        // a seek: the old places are gone
        tee.flush(AudioProcessor.StreamMetadata.DEFAULT)
        assertFalse(YtAudioTap.soundAt(calm, out))
        assertEquals(null, YtAudioTap.startOf(generation, 12_800, lead))
    }

    @Test
    fun nothingIsAnalysedWhileNobodyListens() {
        val tee = YtAudioTap.processor()
        tee.configure(AudioProcessor.AudioFormat(rate, 1, C.ENCODING_PCM_16BIT))
        tee.flush(AudioProcessor.StreamMetadata.DEFAULT)
        tee.queueInput(pcm(1.0, burstAt = 0.5))
        tee.output
        val out = FloatArray(YtAudioTap.FEATURES)
        assertFalse(YtAudioTap.soundAt(YtAudioTap.latestFrame(0), out))
    }
}
