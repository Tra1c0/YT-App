package com.github.libretube.helpers

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.floor

/**
 * YT: listens to the sound the player is about to play and turns it into [BANDS] levels, bass
 * to treble, for the wave at the floating player ([YtSpectrum] does the analysis).
 *
 * The audio is taken inside the player, before it reaches the speaker: no microphone, no
 * permission, and nothing leaves the app. Work only happens while someone listens
 * ([listening]). The player hands the sound over ahead of time (its speaker buffer, [leadUs]),
 * so each set of bands is kept with its place in the stream; [startOf] ties those places to the
 * player's position and [bandsAt] then returns the bands for what is heard right now, blended
 * between the two nearest analyses, so every frame of the screen gets its own exact moment.
 */
@OptIn(UnstableApi::class)
object YtAudioTap {
    const val BANDS = YtSpectrum.BANDS
    private const val N = YtSpectrum.N
    private const val HOP = YtSpectrum.HOP
    private const val KEEP = 256

    /** How much more than the lead must have passed through before [startOf] trusts it. */
    private const val SETTLE_MS = 120L

    /** The speaker buffer assumed until the player reports its own: media3's smallest, 250 ms. */
    private const val DEFAULT_LEAD_US = 250_000L

    /** True while a wave is shown; otherwise the sound only passes through. */
    @Volatile
    var listening = false

    /** Counts the restarts of the stream (seek, new video); positions are counted from each. */
    @Volatile
    var generation = 0
        private set

    /**
     * The player's speaker buffer in µs, asked on the player's own thread while sound passes
     * (set by [PlayerHelper] with the audio sink it builds).
     */
    @Volatile
    var bufferSizeUs: (() -> Long)? = null

    /** How far the sound handed on runs ahead of what is heard, in µs of playing. */
    @Volatile
    var leadUs = DEFAULT_LEAD_US
        private set

    @Volatile
    private var sampleRate = 44100

    private val lock = Any()
    private val frames = Array(KEEP) { FloatArray(BANDS) }
    private val frameIndex = LongArray(KEEP) { -1 }

    /** Samples (per channel) handed on since the stream began; read under [lock]. */
    private var handed = 0L

    // the player's thread only
    private var counted = 0L
    private var fresh = 0L
    private val ring = FloatArray(N)
    private var ringPos = 0
    private var spectrum = YtSpectrum(44100)
    private val levels = FloatArray(BANDS)
    private var wasListening = false
    private var channels = 2
    private var encoding = C.ENCODING_PCM_16BIT

    /** The processor to put into the player's audio chain. */
    fun processor(): TeeAudioProcessor = TeeAudioProcessor(sink)

    /** The lead for [speed], in ms of the stream (the buffer plays faster when sped up). */
    fun leadMs(speed: Float): Long = (leadUs / 1000.0 * speed.coerceIn(0.25f, 4f)).toLong()

    private val sink = object : TeeAudioProcessor.AudioBufferSink {
        override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
            val rate = sampleRateHz.coerceAtLeast(1)
            synchronized(lock) {
                sampleRate = rate
                handed = 0
                frameIndex.fill(-1)
                generation++
            }
            channels = channelCount.coerceAtLeast(1)
            this@YtAudioTap.encoding = encoding
            counted = 0
            fresh = 0
            ringPos = 0
            if (spectrum.sampleRate != rate.coerceAtLeast(8000)) spectrum = YtSpectrum(rate) else spectrum.reset()
        }

        override fun handleBuffer(buffer: ByteBuffer) {
            readLead()
            val step = frameBytes()
            val count = buffer.remaining() / step
            val readable = encoding == C.ENCODING_PCM_16BIT || encoding == C.ENCODING_PCM_FLOAT
            if (!listening || !readable) {
                // nobody watches: only count, so the bands stay in step later
                wasListening = false
                counted += count
            } else {
                if (!wasListening) {
                    // the window holds old sound from before: start it afresh
                    wasListening = true
                    fresh = 0
                    spectrum.reset()
                }
                read(buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN), count)
            }
            synchronized(lock) { handed = counted }
        }
    }

    private fun readLead() {
        val size = bufferSizeUs ?: return
        val us = runCatching { size() }.getOrNull() ?: return
        if (us > 0 && us != C.TIME_UNSET) leadUs = us
    }

    private fun frameBytes(): Int = channels * if (encoding == C.ENCODING_PCM_FLOAT) 4 else 2

    private fun read(data: ByteBuffer, count: Int) {
        val float = encoding == C.ENCODING_PCM_FLOAT
        repeat(count) {
            var sum = 0f
            for (c in 0 until channels) {
                sum += if (float) data.float else data.short / 32768f
            }
            ring[ringPos] = sum / channels
            ringPos = (ringPos + 1) and (N - 1)
            counted++
            fresh++
            // frame i covers samples i·HOP until i·HOP + N
            if (counted >= N && fresh >= N && counted % HOP == 0L) analyse((counted - N) / HOP)
        }
    }

    private fun analyse(index: Long) {
        spectrum.analyse(ring, ringPos, levels)
        synchronized(lock) {
            val slot = (index % KEEP).toInt()
            System.arraycopy(levels, 0, frames[slot], 0, BANDS)
            frameIndex[slot] = index
        }
    }

    /**
     * Where the stream of [forGeneration] began, in the player's ms, judged from what is heard
     * now ([positionMs]) and how far the tap runs ahead of it ([leadMs] of the stream): the tap
     * has handed on [leadMs] more than was heard. Null while the stream has not yet run that
     * far ahead (just after a seek or a change of speed): then the estimate would not hold, and
     * the tap can only be trusted once the speaker's buffer has filled again.
     */
    fun startOf(forGeneration: Int, positionMs: Long, leadMs: Long): Long? {
        synchronized(lock) {
            if (forGeneration != generation) return null
            val handedMs = handed * 1000L / sampleRate
            if (handedMs < leadMs + SETTLE_MS) return null
            return positionMs + leadMs - handedMs
        }
    }

    /**
     * The bands for the moment [positionMs] of the stream that started at [startMs] (from
     * [startOf]), blended between the two analyses around it; false when that moment is not
     * known.
     */
    fun bandsAt(positionMs: Long, startMs: Long, out: FloatArray): Boolean {
        val rate = sampleRate
        // analysis i is centred on sample i·HOP + N/2
        val at = ((positionMs - startMs) * rate / 1000.0 - N / 2) / HOP
        return blend(at, out)
    }

    /** The bands for what is heard now when the place in the stream is not known (a new video). */
    fun latest(out: FloatArray, leadMs: Long): Boolean {
        val rate = sampleRate
        val heard = synchronized(lock) { handed } - leadMs * rate / 1000L
        return blend((heard - N / 2).toDouble() / HOP, out)
    }

    private fun blend(at: Double, out: FloatArray): Boolean {
        if (at < 0) return false
        val first = floor(at).toLong()
        val share = (at - first).toFloat()
        synchronized(lock) {
            val a = (first % KEEP).toInt()
            if (frameIndex[a] != first) return false
            val b = ((first + 1) % KEEP).toInt()
            val count = minOf(BANDS, out.size)
            if (frameIndex[b] == first + 1) {
                for (i in 0 until count) out[i] = frames[a][i] + (frames[b][i] - frames[a][i]) * share
            } else {
                System.arraycopy(frames[a], 0, out, 0, count)
            }
        }
        return true
    }
}
