package com.github.libretube.ui.yt.sound

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf

/**
 * YT: what the sound is doing right now, for a UI that reacts to it (the Frequency design, later
 * the music mode). All values are smoothed and normalised to 0..1, safe to map straight onto scale,
 * glow, colour or blur. [punch] is a short transient (a hit, a consonant), decaying quickly;
 * [level] is the overall loudness envelope. [playing] is false while paused, stopped or silent.
 */
@Immutable
data class YtSoundFrame(
    val level: Float,
    val bass: Float,
    val mid: Float,
    val high: Float,
    val punch: Float,
    val playing: Boolean,
) {
    companion object {
        val SILENT = YtSoundFrame(0f, 0f, 0f, 0f, 0f, false)
    }
}

/**
 * The contract between the sound engine and the UI. Read [frame] only in the draw or layout phase
 * (graphicsLayer / drawBehind / Modifier.Node), never in composition, so the screen does not
 * recompose every frame.
 *
 * Filled by [YtSoundEngine] (sound analysis on top of YtAudioTap/YtSpectrum), which runs only
 * while [reactive] is on, the app is in front, the design animations are on and the player
 * plays; otherwise [frame] fades to [YtSoundFrame.SILENT] (at once with animations off).
 *
 * Safety: every value is smoothed and passes a flash guard (each change of 0.1 or more lasts at
 * least ~170 ms, so no value can flash more than 3 times a second), and [YtSoundFrame.punch]
 * reaches full strength at most once per 330 ms. A UI that combines several values into one
 * brightness should take their max or a weighted mean, not a sum of opposite movements.
 */
object YtSound {
    private val state = mutableStateOf(YtSoundFrame.SILENT)

    /** The latest frame, updated once per display frame while [reactive] is on and sound plays. */
    val frame: State<YtSoundFrame> get() = state

    /**
     * Motion values of the same moment: level, bass, mid, high, punch ([MOTION_LEVEL]..) before
     * the flash guard (smoothed, faded, 0..1). They may follow the sound faster than [frame]:
     * map them onto size, position, rotation or shape only, never onto the brightness of a
     * large area. Written in place by the engine (main thread) right before [frame] changes;
     * read them in the same frame callback or draw pass that reads [frame]. Do not write.
     */
    val motion = FloatArray(5)

    /**
     * The spectrum of the same moment, [BANDS] bands bass to treble (0..1), quick envelopes:
     * for a visualizer's bar heights or wave shape (a motion channel, as [motion]). Written in
     * place by the engine (main thread). Do not write.
     */
    val bands = FloatArray(BANDS)

    const val MOTION_LEVEL = 0
    const val MOTION_BASS = 1
    const val MOTION_MID = 2
    const val MOTION_HIGH = 3
    const val MOTION_PUNCH = 4
    const val BANDS = 16

    /** Whether some visible UI wants sound frames; the engine analyses only while true. */
    @Volatile
    var reactive: Boolean = false
        private set

    /** Turns the analysis on or off (the design calls this while it is shown). */
    fun setReactive(on: Boolean) {
        if (reactive == on) return
        reactive = on
        YtSoundEngine.reactiveChanged()
    }

    /** For the engine: publish a new frame (main thread). */
    internal fun publish(next: YtSoundFrame) {
        state.value = next
    }
}

/** The sound frame for draw-phase reads in composables. */
@Composable
fun rememberYtSound(): State<YtSoundFrame> = YtSound.frame
