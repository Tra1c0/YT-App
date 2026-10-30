package com.github.libretube.helpers

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * YT: listens to the sound the player is about to play and turns it into a few loudness
 * bands (bass to treble) for the wave below the picture-in-picture window.
 *
 * The audio is taken inside the player, before it reaches the speaker: no microphone, no
 * permission, and nothing leaves the app. Work only happens while someone listens
 * ([listening]). The player hands the sound over ahead of time (its speaker buffer holds about
 * a quarter of a second), so each set of bands is kept with its place in the stream; [startOf]
 * ties those places to the player's position and [bandsAt] then returns the set for what is
 * heard right now.
 */
@OptIn(UnstableApi::class)
object YtAudioTap {
    const val BANDS = 8
    private const val SIZE = 1024
    private const val KEEP = 256

    /** How much more than the lead must have passed through before [startOf] trusts it. */
    private const val SETTLE_MS = 120L

    /** True while a visualizer is shown; otherwise the sound only passes through. */
    @Volatile
    var listening = false

    /** Counts the restarts of the stream (seek, new video); positions are counted from each. */
    @Volatile
    var generation = 0
        private set

    @Volatile
    private var sampleRate = 44100

    private val lock = Any()
    private val frames = Array(KEEP) { FloatArray(BANDS) }
    private val frameIndex = LongArray(KEEP) { -1 }
    private var produced = 0L

    // the analysis window, filled sample by sample (mono)
    private val window = FloatArray(SIZE)
    private var filled = 0
    private var channels = 2
    private var encoding = C.ENCODING_PCM_16BIT
    private val re = FloatArray(SIZE)
    private val im = FloatArray(SIZE)
    private val hann = FloatArray(SIZE) { (0.5 - 0.5 * cos(2 * PI * it / (SIZE - 1))).toFloat() }
    private val level = FloatArray(BANDS)
    private var peak = 1e-3f

    /** The processor to put into the player's audio chain. */
    fun processor(): TeeAudioProcessor = TeeAudioProcessor(sink)

    private val sink = object : TeeAudioProcessor.AudioBufferSink {
        override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
            synchronized(lock) {
                sampleRate = sampleRateHz.coerceAtLeast(1)
                channels = channelCount.coerceAtLeast(1)
                this@YtAudioTap.encoding = encoding
                filled = 0
                produced = 0
                frameIndex.fill(-1)
                generation++
            }
        }

        override fun handleBuffer(buffer: ByteBuffer) {
            if (listening) {
                read(buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN))
            } else {
                // nobody watches: only count the windows, so the bands stay in step later
                skip(buffer.remaining() / frameBytes())
            }
        }
    }

    private fun frameBytes(): Int = channels * if (encoding == C.ENCODING_PCM_FLOAT) 4 else 2

    private fun skip(samples: Int) {
        filled += samples
        if (filled < SIZE) return
        synchronized(lock) {
            while (filled >= SIZE) {
                filled -= SIZE
                produced++
            }
        }
    }

    private fun read(data: ByteBuffer) {
        val float = encoding == C.ENCODING_PCM_FLOAT
        val step = frameBytes()
        if (encoding != C.ENCODING_PCM_16BIT && !float) return
        while (data.remaining() >= step) {
            var sum = 0f
            for (c in 0 until channels) {
                sum += if (float) data.float else data.short / 32768f
            }
            window[filled++] = sum / channels
            if (filled == SIZE) {
                analyse()
                filled = 0
            }
        }
    }

    /** One window of sound into [BANDS] levels from 0 to 1, kept with its place in the stream. */
    private fun analyse() {
        for (i in 0 until SIZE) {
            re[i] = window[i] * hann[i]
            im[i] = 0f
        }
        fft(re, im)
        val rate = sampleRate
        for (b in 0 until BANDS) {
            // logarithmic bands from 40 Hz to 12 kHz
            val from = 40.0 * (12000.0 / 40.0).pow(b.toDouble() / BANDS)
            val to = 40.0 * (12000.0 / 40.0).pow((b + 1).toDouble() / BANDS)
            val lo = (from * SIZE / rate).toInt().coerceIn(1, SIZE / 2 - 1)
            val hi = (to * SIZE / rate).toInt().coerceIn(lo + 1, SIZE / 2)
            var energy = 0f
            for (k in lo until hi) energy = max(energy, hypot(re[k], im[k]))
            level[b] = energy
        }
        // a slowly falling peak keeps quiet and loud videos in the same range
        val loudest = level.maxOrNull() ?: 0f
        peak = max(loudest, peak * 0.995f).coerceAtLeast(1e-3f)
        synchronized(lock) {
            val slot = (produced % KEEP).toInt()
            for (b in 0 until BANDS) {
                val v = level[b] / peak
                frames[slot][b] = (ln(1f + 9f * v) / ln(10f)).coerceIn(0f, 1f)
            }
            frameIndex[slot] = produced
            produced++
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
            val handedMs = (produced * SIZE + filled) * 1000L / sampleRate
            if (handedMs < leadMs + SETTLE_MS) return null
            return positionMs + leadMs - handedMs
        }
    }

    /**
     * The bands for the moment [positionMs] of the stream that started at [startMs] (from
     * [startOf]); false when that moment is not known.
     */
    fun bandsAt(positionMs: Long, startMs: Long, out: FloatArray): Boolean {
        val rate = sampleRate
        val index = ((positionMs - startMs) * rate / 1000L) / SIZE
        if (index < 0) return false
        synchronized(lock) {
            val slot = (index % KEEP).toInt()
            if (frameIndex[slot] != index) return false
            System.arraycopy(frames[slot], 0, out, 0, min(BANDS, out.size))
        }
        return true
    }

    /** The newest bands, for when the place in the stream is not known (e.g. a new video). */
    fun latest(out: FloatArray): Boolean {
        synchronized(lock) {
            // about a quarter of a second behind what was handed over: what is heard now; just
            // after the stream restarted, what is heard is not in it yet
            val behind = (rate() / 4 / SIZE).coerceAtLeast(1)
            val index = produced - 1 - behind
            if (index < 0) return false
            val slot = (index % KEEP).toInt()
            if (frameIndex[slot] != index) return false
            System.arraycopy(frames[slot], 0, out, 0, min(BANDS, out.size))
        }
        return true
    }

    private fun rate(): Int = sampleRate

    /** In-place radix-2 FFT of [SIZE] points. */
    private fun fft(re: FloatArray, im: FloatArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var len = 2
        while (len <= n) {
            val angle = -2 * PI / len
            val wr = cos(angle).toFloat()
            val wi = sin(angle).toFloat()
            var i = 0
            while (i < n) {
                var cr = 1f
                var ci = 0f
                for (k in 0 until len / 2) {
                    val ar = re[i + k + len / 2] * cr - im[i + k + len / 2] * ci
                    val ai = re[i + k + len / 2] * ci + im[i + k + len / 2] * cr
                    re[i + k + len / 2] = re[i + k] - ar
                    im[i + k + len / 2] = im[i + k] - ai
                    re[i + k] += ar
                    im[i + k] += ai
                    val next = cr * wr - ci * wi
                    ci = cr * wi + ci * wr
                    cr = next
                }
                i += len
            }
            len = len shl 1
        }
    }
}
