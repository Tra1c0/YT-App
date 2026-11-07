package com.github.libretube.helpers

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessingPipeline
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import com.google.common.collect.ImmutableList
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The drain media3 runs before every speed change must end: a stage that answered each repeated
 * queueEndOfStream with a new limiter tail never ended, and the sound stopped until a seek.
 */
@UnstableApi
class YtLoudnessDrainTest {

    private val format = AudioProcessor.AudioFormat(48_000, 2, C.ENCODING_PCM_FLOAT)

    private fun pipeline(): AudioProcessingPipeline {
        // a plain stage first, as in the app (the wave tap, silence skipping, Sonic): once it has
        // ended, the pipeline queues the end into the next stage again on every pass
        val p = AudioProcessingPipeline(ImmutableList.of<AudioProcessor>(PassThrough(), YtLoudnessProcessor()))
        p.configure(format)
        p.flush(AudioProcessor.StreamMetadata.DEFAULT)
        return p
    }

    private fun audio(frames: Int): ByteBuffer {
        val b = ByteBuffer.allocateDirect(frames * 2 * 4).order(ByteOrder.nativeOrder())
        for (i in 0 until frames * 2) b.putFloat(0.1f * kotlin.math.sin(i / 20.0).toFloat())
        b.flip()
        return b
    }

    // the sink: reads what comes out, like DefaultAudioSink writing to the AudioTrack
    private fun consume(p: AudioProcessingPipeline) {
        repeat(4) {
            val out = p.output
            out.position(out.limit())
        }
    }

    @Test
    fun drainEndsAfterRepeatedEndOfStream() {
        val p = pipeline()
        assertTrue(p.isOperational)
        p.queueInput(audio(4_800))
        consume(p)
        // DefaultAudioSink.drainToEndOfStream: queue the end and process, again on every pass
        var ended = false
        for (pass in 0 until 20) {
            p.queueEndOfStream()
            consume(p)
            if (p.isEnded) {
                ended = true
                break
            }
        }
        assertTrue("the chain must end its drain", ended)
    }

    @Test
    fun drainsAgainAfterAFlush() {
        val p = pipeline()
        p.queueInput(audio(4_800))
        consume(p)
        repeat(3) { p.queueEndOfStream(); consume(p) }
        assertTrue(p.isEnded)
        // the next speed change, after the chain was set up again
        p.flush(AudioProcessor.StreamMetadata.DEFAULT)
        p.queueInput(audio(4_800))
        consume(p)
        repeat(3) { p.queueEndOfStream(); consume(p) }
        assertTrue(p.isEnded)
    }

    private class PassThrough : BaseAudioProcessor() {
        override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat) = inputAudioFormat

        override fun queueInput(inputBuffer: ByteBuffer) {
            val n = inputBuffer.remaining()
            if (n > 0) replaceOutputBuffer(n).put(inputBuffer).flip()
        }
    }
}
