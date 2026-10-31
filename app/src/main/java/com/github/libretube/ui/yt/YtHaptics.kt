package com.github.libretube.ui.yt

import android.os.Build
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView
import com.github.libretube.helpers.PreferenceHelper
import com.github.libretube.helpers.YtGuard

/**
 * YT: touch feedback of the designs, on the frame of the visual change: a light tick when
 * something snaps into place (a tab, a segment, a carousel page, a grabbed pebble) and a
 * firmer confirm for a like. Follows the system setting for touch feedback and, like the
 * animations, the switch "Animationen der Designs".
 *
 * The gestures of round 12 speak a vocabulary of their own ([grab], [detent], [threshold],
 * [land], [release], [reject], [segment], or a design's own [YtHapticTexture] through [play]).
 * Those follow the switch "Eigene Haptik" instead of the animation switch: on, they play the
 * design's vibration patterns where the vibrator has them; off (or without them), the system's
 * touch feedback of a similar feel. Both respect the system setting for touch feedback.
 * In the emulator, which has no vibrator, every haptic is also logged (tag YtHaptic).
 */
class YtHaptics(private val view: View, private val enabled: Boolean) {
    fun tick() {
        if (!enabled) return
        log("tick")
        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }

    fun confirm() {
        if (!enabled) return
        log("confirm")
        view.performHapticFeedback(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.CONFIRM
            else HapticFeedbackConstants.CONTEXT_CLICK,
        )
    }

    /** A finger takes hold of something that will follow it (the video, a card). */
    fun grab() = play(YtHapticTexture.GRAB)

    /** A small step under the finger (a level of the magnifier, a frame). */
    fun detent() = play(YtHapticTexture.DETENT)

    /** A drag passes the point from which letting go does something ([on]), or goes back behind it. */
    fun threshold(on: Boolean) = play(if (on) YtHapticTexture.THRESHOLD_ON else YtHapticTexture.THRESHOLD_OFF)

    /** Something comes to rest, heavier with [weight] (0 to 1). */
    fun land(weight: Float = 0.5f) = play(YtHapticTexture.land(weight))

    /** The finger lets go of what it held. */
    fun release() = play(YtHapticTexture.RELEASE)

    /** Something does not take what came. */
    fun reject() = play(YtHapticTexture.REJECT)

    /** Into another segment (a chapter, a section). */
    fun segment() = play(YtHapticTexture.SEGMENT)

    /** Plays [texture]: its vibration pattern if possible and wanted, else its fallback constant. */
    fun play(texture: YtHapticTexture) {
        log(texture.name)
        if (view.isHapticFeedbackEnabled && YtHapticsPrefs.enabled && YtVibes.play(view.context, texture)) return
        val constant = YtFeedback.compat(texture.fallback, Build.VERSION.SDK_INT)
        if (constant != YtFeedback.NO_HAPTICS) view.performHapticFeedback(constant)
    }

    companion object {
        /** For View code: tick and confirm follow the animation switch, as in Compose. */
        fun of(view: View): YtHaptics = YtHaptics(view, YtMotionPrefs.enabled)

        private fun log(name: String) {
            if (YtGuard.isEmulator) Log.i("YtHaptic", name)
        }
    }
}

/** The switch "Eigene Haptik": the designs' own vibration patterns for the gestures of round 12. */
object YtHapticsPrefs {
    const val KEY = "yt_haptics"
    val enabled: Boolean get() = PreferenceHelper.getBoolean(KEY, true)
}

@Composable
fun rememberYtHaptics(): YtHaptics {
    val view = LocalView.current
    val motion = LocalYtMotion.current
    return remember(view, motion) { YtHaptics(view, motion) }
}
