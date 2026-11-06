package com.github.libretube.ui.yt.mini

import com.github.libretube.ui.yt.YtFeedback
import com.github.libretube.ui.yt.YtHapticStep
import com.github.libretube.ui.yt.YtHapticTexture
import com.github.libretube.ui.yt.YtPrimitive

/**
 * YT: the feel of the Side player ("Gesprächsblase"): gentle and warm. Where something arrives
 * (the player docks, a dropped video is taken) a heartbeat, a light beat and 120 ms later a
 * firmer one, the same as the heart of a Side post. The preview swells in softly; a dragged
 * video over the bubble taps like someone typing. Pure, so the fallbacks can be tested; the
 * names keep the moment's neutral name (the emulator checks look for "mini_dock", "mini_peek")
 * with the design behind it.
 */
object YtMiniSideTextures {
    private fun heartbeat(name: String) = YtHapticTexture.of(
        name,
        YtFeedback.CONFIRM,
        YtHapticStep(YtPrimitive.TICK, 0.5f),
        YtHapticStep(YtPrimitive.CLICK, 0.8f, 120),
    )

    fun of(moment: YtMoment): YtHapticTexture = when (moment) {
        YtMoment.DOCK -> heartbeat("mini_dock_side")
        // the bubble swells into a post card
        YtMoment.PEEK_START -> YtHapticTexture.of("mini_peek_side", YtFeedback.GESTURE_START, YtHapticStep(YtPrimitive.SLOW_RISE, 0.3f))
        YtMoment.PEEK_FULL -> YtHapticTexture.of("mini_peek_full_side", YtFeedback.CLOCK_TICK, YtHapticStep(YtPrimitive.TICK, 0.3f))
        YtMoment.PEEK_RELEASE -> YtHapticTexture.of("mini_peek_release_side", YtFeedback.GESTURE_END, YtHapticStep(YtPrimitive.QUICK_FALL, 0.25f))
        YtMoment.PEEK_TARGET -> YtHapticTexture.of("mini_peek_target_side", YtFeedback.SEGMENT_TICK, YtHapticStep(YtPrimitive.TICK, 0.45f))
        YtMoment.PLAY_TOGGLE -> YtHapticTexture.of("mini_play_side", YtFeedback.TOGGLE_ON, YtHapticStep(YtPrimitive.CLICK, 0.5f))
        // the reply is opened: a click and a soft beat after it
        YtMoment.PLAY_NEXT -> YtHapticTexture.of(
            "mini_next_side",
            YtFeedback.CONFIRM,
            YtHapticStep(YtPrimitive.CLICK, 0.6f),
            YtHapticStep(YtPrimitive.TICK, 0.35f, 90),
        )
        YtMoment.BUTTON -> YtHapticTexture.of("mini_button_side", YtFeedback.CLOCK_TICK, YtHapticStep(YtPrimitive.TICK, 0.5f))
        // three light taps, as the dots of someone typing
        YtMoment.HOVER -> YtHapticTexture.of(
            "mini_hover_side",
            YtFeedback.GESTURE_THRESHOLD_ACTIVATE,
            YtHapticStep(YtPrimitive.TICK, 0.25f),
            YtHapticStep(YtPrimitive.TICK, 0.25f, 70),
            YtHapticStep(YtPrimitive.TICK, 0.25f, 70),
        )
        YtMoment.ACCEPT -> heartbeat("mini_accept_side")
        YtMoment.REJECT -> YtHapticTexture.REJECT.copy(name = "mini_reject_side")
    }
}
