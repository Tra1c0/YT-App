package com.github.libretube.helpers

import kotlinx.serialization.Serializable
import kotlin.math.roundToInt

/**
 * YT: pure rules for playback speed and pitch (no Android, tested).
 *
 * Why the clamps: dragging the pitch slider sent a new value for every pixel, each one a
 * MediaController call that drains and rebuilds the audio processors (Sonic) and changes the
 * decoders' operating rate. Rapid or odd values (a NaN from a broken text field, a pitch outside
 * the slider) can end in a RuntimeException on the playback thread, which media3 reports as
 * "Unexpected runtime error". Values are kept in the range the sheet offers and rounded, so
 * near-identical values are not sent twice.
 */
object YtSpeedRules {
    /** media3's DefaultAudioSink accepts 0.1–8; the fast-forward hold goes up to 8. */
    const val MIN_SPEED = 0.1f
    const val MAX_SPEED = 8f

    /** ±12 semitones, the range of the sheet's pitch slider. */
    const val MIN_PITCH = 0.5f
    const val MAX_PITCH = 2f

    /** The smallest pitch step the sheet sends, in semitones. */
    const val SEMITONE_STEP = 0.05f

    /** The shortest time between two parameter changes from the sheet. */
    const val THROTTLE_MS = 150L

    fun clampSpeed(speed: Float): Float =
        if (speed.isNaN() || speed.isInfinite()) 1f else round2(speed.coerceIn(MIN_SPEED, MAX_SPEED))

    fun clampPitch(pitch: Float): Float =
        if (pitch.isNaN() || pitch.isInfinite()) 1f else round3(pitch.coerceIn(MIN_PITCH, MAX_PITCH))

    /** A semitone value on the [SEMITONE_STEP] grid within ±12. */
    fun quantizeSemitone(semitone: Float): Float {
        if (semitone.isNaN() || semitone.isInfinite()) return 0f
        val steps = (semitone.coerceIn(-12f, 12f) / SEMITONE_STEP).roundToInt()
        return round2(steps * SEMITONE_STEP)
    }

    /**
     * A speed the sheet's slider can show (0.25 to 4 in steps of 0.25): Material's Slider throws
     * for a value off its grid or range, e.g. 6× while the fast-forward hold runs.
     */
    fun sliderSpeed(speed: Float): Float {
        if (speed.isNaN() || speed.isInfinite()) return 1f
        return (speed.coerceIn(0.25f, 4f) * 4f).roundToInt() / 4f
    }

    private fun round2(v: Float) = (v * 100f).roundToInt() / 100f
    private fun round3(v: Float) = (v * 1000f).roundToInt() / 1000f

    /**
     * The speed a new video starts with: the channel's own speed if one is stored; otherwise the
     * general speed, but only if the video before had a channel speed (else the speed stays as it
     * is, like upstream, which never resets it between videos). Null: leave the speed alone.
     */
    fun startSpeed(channelSpeed: Float?, generalSpeed: Float, lastWasChannelSpeed: Boolean): Float? = when {
        channelSpeed != null -> clampSpeed(channelSpeed)
        lastWasChannelSpeed -> clampSpeed(generalSpeed)
        else -> null
    }

    /** [entries] with [id] set to [speed] (replacing an earlier entry). */
    fun put(entries: List<YtChannelSpeedEntry>, id: String, name: String, speed: Float): List<YtChannelSpeedEntry> =
        entries.filterNot { it.id == id } + YtChannelSpeedEntry(id, name, clampSpeed(speed))

    fun remove(entries: List<YtChannelSpeedEntry>, id: String): List<YtChannelSpeedEntry> =
        entries.filterNot { it.id == id }

    fun find(entries: List<YtChannelSpeedEntry>, id: String?): YtChannelSpeedEntry? =
        if (id.isNullOrBlank()) null else entries.lastOrNull { it.id == id }
}

/** YT: a channel's own playback speed ("Für diesen Kanal merken"). */
@Serializable
data class YtChannelSpeedEntry(
    val id: String,
    val name: String = "",
    val speed: Float = 1f,
)

/**
 * YT: lets a stream of changes through at most every [intervalMs], always ending with the last
 * value (the caller sends whatever is current when the wait is over). Plain state, the caller
 * gives the clock.
 */
class YtThrottle(private val intervalMs: Long = YtSpeedRules.THROTTLE_MS) {
    private var lastSentAt: Long? = null

    /** How long to wait at [now] before sending; 0 = send now. */
    fun delayAt(now: Long): Long {
        val last = lastSentAt ?: return 0L
        return (last + intervalMs - now).coerceAtLeast(0L)
    }

    fun sent(now: Long) {
        lastSentAt = now
    }
}
