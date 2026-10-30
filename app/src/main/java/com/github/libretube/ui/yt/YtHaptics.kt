package com.github.libretube.ui.yt

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView

/**
 * YT: touch feedback of the designs, on the frame of the visual change: a light tick when
 * something snaps into place (a tab, a segment, a carousel page, a grabbed pebble) and a
 * firmer confirm for a like. Follows the system setting for touch feedback and, like the
 * animations, the switch "Animationen der Designs".
 */
class YtHaptics(private val view: View, private val enabled: Boolean) {
    fun tick() {
        if (enabled) view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }

    fun confirm() {
        if (!enabled) return
        view.performHapticFeedback(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.CONFIRM
            else HapticFeedbackConstants.CONTEXT_CLICK,
        )
    }
}

@Composable
fun rememberYtHaptics(): YtHaptics {
    val view = LocalView.current
    val motion = LocalYtMotion.current
    return remember(view, motion) { YtHaptics(view, motion) }
}
