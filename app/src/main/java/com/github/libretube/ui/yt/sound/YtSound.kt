package com.github.libretube.ui.yt.sound

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf

/**
 * YT: what the sound is doing right now, for a UI that reacts to it (the Soundspace design, later
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
 * Contract stub: the engine (sound analysis on top of YtAudioTap/YtSpectrum) fills this in.
 */
object YtSound {
    private val state = mutableStateOf(YtSoundFrame.SILENT)

    /** The latest frame, updated once per display frame while [reactive] is on and sound plays. */
    val frame: State<YtSoundFrame> get() = state

    /** Whether some visible UI wants sound frames; the engine analyses only while true. */
    @Volatile
    var reactive: Boolean = false
        private set

    /** Turns the analysis on or off (the design calls this while it is shown). */
    fun setReactive(on: Boolean) {
        reactive = on
    }

    /** For the engine: publish a new frame (main thread). */
    internal fun publish(next: YtSoundFrame) {
        state.value = next
    }
}

/** The sound frame for draw-phase reads in composables. */
@Composable
fun rememberYtSound(): State<YtSoundFrame> = YtSound.frame
