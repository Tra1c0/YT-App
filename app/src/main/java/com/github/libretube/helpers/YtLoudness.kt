package com.github.libretube.helpers

import android.content.SharedPreferences
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessorChain
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.DefaultAudioSink
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * YT: the settings of the loudness levelling ([YtLeveller]), kept current from the
 * preferences so the player's audio thread only reads a field.
 */
object YtLoudness {
    const val KEY_ENABLED = "yt_loud"
    const val KEY_DYNAMIC = "yt_loud_dynamic"
    const val KEY_TARGET = "yt_loud_target"
    const val KEY_TRIM = "yt_loud_trim"

    @Volatile
    var settings = YtLevelSettings()
        private set

    // kept as a field: SharedPreferences holds its listeners weakly
    private var listener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    /** Reads the settings and follows later changes; safe to call more than once. */
    @Synchronized
    fun follow() {
        if (listener != null) return
        val prefs = runCatching { PreferenceHelper.settings }.getOrNull() ?: return
        settings = read(prefs)
        listener = SharedPreferences.OnSharedPreferenceChangeListener { p, key ->
            if (key == null || key.startsWith(KEY_ENABLED)) settings = read(p)
        }.also { prefs.registerOnSharedPreferenceChangeListener(it) }
    }

    private fun read(p: SharedPreferences) = YtLevelSettings(
        enabled = p.getBoolean(KEY_ENABLED, true),
        dynamic = p.getBoolean(KEY_DYNAMIC, true),
        targetLufs = number(p, KEY_TARGET, -14.0).coerceIn(-16.0, -12.0),
        trimDb = number(p, KEY_TRIM, 0.0).coerceIn(-10.0, 6.0)
    )

    // the slider preferences store their value as text
    private fun number(p: SharedPreferences, key: String, def: Double): Double =
        runCatching { p.getString(key, null)?.toDouble() }.getOrNull()?.takeIf { it.isFinite() } ?: def
}

/**
 * YT: runs [YtLeveller] inside the player's audio chain, on 16-bit or float PCM.
 *
 * Decided when the player configures its audio (each new video or format): with levelling and
 * trim both off the processor is inactive and media3 skips it, so the sound passes bit for bit.
 * While active, setting changes apply within a step (100 ms), gliding. A flush (seek) keeps
 * what was learnt about the video; [newItem] or a new format starts afresh.
 */
@OptIn(UnstableApi::class)
class YtLoudnessProcessor : BaseAudioProcessor() {
    private var leveller: YtLeveller? = null
    private var buffer = FloatArray(0)
    private var float = false

    // the limiter's tail goes out once per end of stream: media3's pipeline calls
    // queueEndOfStream again on every pass while the stage before is ended, and a fresh tail each
    // time kept this stage from ever ending. A speed change drains the chain and waits for that
    // end, so the sound stopped (the picture ran on its own clock) until a seek flushed it.
    private var tailSent = false

    @Volatile
    private var itemGeneration = 0
    private var seenGeneration = 0

    /** A new video starts: its loudness is not known yet. */
    fun newItem() {
        itemGeneration++
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        YtLoudness.follow()
        val encoding = inputAudioFormat.encoding
        val readable = encoding == C.ENCODING_PCM_16BIT || encoding == C.ENCODING_PCM_FLOAT
        if (!readable || !YtLoudness.settings.active || inputAudioFormat.channelCount < 1) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        return inputAudioFormat
    }

    // the new format only takes over at the flush that follows configure (media3 may still
    // drain the old one in between), so the leveller is set up here
    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        tailSent = false
        val format = inputAudioFormat
        if (outputAudioFormat == AudioProcessor.AudioFormat.NOT_SET) {
            leveller = null
            return
        }
        float = format.encoding == C.ENCODING_PCM_FLOAT
        val old = leveller
        if (old != null && old.sampleRate == format.sampleRate && old.channels == format.channelCount) {
            old.flush()
        } else {
            leveller = YtLeveller(format.sampleRate, format.channelCount).apply {
                settings = YtLoudness.settings
                reset()
            }
        }
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val lev = leveller
        val bytes = inputBuffer.remaining()
        if (lev == null || bytes == 0) {
            if (bytes > 0) replaceOutputBuffer(bytes).put(inputBuffer).flip()
            return
        }
        lev.settings = YtLoudness.settings
        if (seenGeneration != itemGeneration) {
            seenGeneration = itemGeneration
            lev.reset()
        }
        val channels = lev.channels
        val frameBytes = channels * if (float) 4 else 2
        val frames = bytes / frameBytes
        val samples = frames * channels
        if (buffer.size < samples) buffer = FloatArray(samples)
        val data = buffer
        val input = inputBuffer.order(ByteOrder.LITTLE_ENDIAN)
        if (float) {
            for (i in 0 until samples) data[i] = input.float
        } else {
            for (i in 0 until samples) data[i] = input.short / 32768f
        }
        // a stray partial frame cannot happen with PCM, but never leave input unread
        inputBuffer.position(inputBuffer.limit())
        lev.process(data, frames)
        write(data, samples)
    }

    override fun onQueueEndOfStream() {
        if (tailSent) return
        tailSent = true
        val lev = leveller ?: return
        val tail = lev.drain()
        write(tail, tail.size)
    }

    private fun write(data: FloatArray, samples: Int) {
        if (samples == 0) return
        val out = replaceOutputBuffer(samples * if (float) 4 else 2).order(ByteOrder.LITTLE_ENDIAN)
        if (float) {
            for (i in 0 until samples) out.putFloat(data[i])
        } else {
            for (i in 0 until samples) {
                val v = Math.round(data[i] * 32768f).coerceIn(-32768, 32767)
                out.putShort(v.toShort())
            }
        }
        out.flip()
    }

    override fun onReset() {
        tailSent = false
        leveller = null
        buffer = FloatArray(0)
    }
}

/**
 * YT: media3's default chain (silence skipping, speed and pitch) with the loudness levelling
 * after it, so the limiter is the very last stage and also catches what a pitch change adds.
 */
@OptIn(UnstableApi::class)
class YtAudioChain(
    before: Array<AudioProcessor>,
    private val tail: YtLoudnessProcessor
) : AudioProcessorChain {
    private val inner = DefaultAudioSink.DefaultAudioProcessorChain(*before)
    private val all: Array<AudioProcessor> = inner.audioProcessors + tail

    override fun getAudioProcessors(): Array<AudioProcessor> = all
    override fun applyPlaybackParameters(playbackParameters: PlaybackParameters): PlaybackParameters =
        inner.applyPlaybackParameters(playbackParameters)
    override fun applySkipSilenceEnabled(skipSilenceEnabled: Boolean): Boolean =
        inner.applySkipSilenceEnabled(skipSilenceEnabled)
    override fun getMediaDuration(playoutDuration: Long): Long = inner.getMediaDuration(playoutDuration)
    override fun getSkippedOutputFrameCount(): Long = inner.skippedOutputFrameCount
}
