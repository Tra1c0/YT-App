package com.github.libretube.ui.yt.mini

import com.github.libretube.ui.yt.YtFeedback
import com.github.libretube.ui.yt.YtHapticStep
import com.github.libretube.ui.yt.YtHapticTexture
import com.github.libretube.ui.yt.YtPrimitive

/**
 * YT: the feel of the Movo mini player: bouncy and solid, like tiles knocking into place.
 * Docking clicks twice (the picture snaps into its tile, then the lime disc pops), the long press
 * rises quickly as the tile flips up, a dropped video lands with a firm click and a light tick
 * as the tiles shuffle. The names keep the moment's neutral name ("mini_dock", "mini_peek"),
 * which the emulator checks look for, with the design behind it. Pure, for the JVM tests; where
 * the primitives cannot play, each falls back to its touch feedback constant.
 */
object YtMiniMovoTextures {
    fun of(moment: YtMoment): YtHapticTexture = when (moment) {
        YtMoment.DOCK -> YtHapticTexture.of(
            "mini_dock_movo",
            YtFeedback.CONFIRM,
            YtHapticStep(YtPrimitive.CLICK, 0.6f),
            YtHapticStep(YtPrimitive.CLICK, 0.8f, 60),
        )
        YtMoment.PEEK_START -> YtHapticTexture.of("mini_peek_movo", YtFeedback.GESTURE_START, YtHapticStep(YtPrimitive.QUICK_RISE, 0.5f))
        // the mosaic is complete: the last tile knocks into place
        YtMoment.PEEK_FULL -> YtHapticTexture.of("mini_peek_full_movo", YtFeedback.CLOCK_TICK, YtHapticStep(YtPrimitive.CLICK, 0.4f))
        // the tiles fall back onto the bar
        YtMoment.PEEK_RELEASE -> YtHapticTexture.of(
            "mini_peek_release_movo",
            YtFeedback.GESTURE_END,
            YtHapticStep(YtPrimitive.QUICK_FALL, 0.35f),
            YtHapticStep(YtPrimitive.TICK, 0.3f, 50),
        )
        YtMoment.PEEK_TARGET -> YtHapticTexture.of("mini_peek_target_movo", YtFeedback.SEGMENT_TICK, YtHapticStep(YtPrimitive.TICK, 0.55f))
        YtMoment.PLAY_TOGGLE -> YtHapticTexture.of("mini_play_movo", YtFeedback.TOGGLE_ON, YtHapticStep(YtPrimitive.CLICK, 0.7f))
        YtMoment.PLAY_NEXT -> YtHapticTexture.of(
            "mini_next_movo",
            YtFeedback.CONFIRM,
            YtHapticStep(YtPrimitive.QUICK_RISE, 0.4f),
            YtHapticStep(YtPrimitive.CLICK, 0.8f, 70),
        )
        YtMoment.BUTTON -> YtHapticTexture.of("mini_button_movo", YtFeedback.CLOCK_TICK, YtHapticStep(YtPrimitive.TICK, 0.6f))
        // a card comes over the mosaic: two light knocks, as a tile that is about to be set
        YtMoment.HOVER -> YtHapticTexture.of(
            "mini_hover_movo",
            YtFeedback.GESTURE_THRESHOLD_ACTIVATE,
            YtHapticStep(YtPrimitive.TICK, 0.4f),
            YtHapticStep(YtPrimitive.TICK, 0.25f, 45),
        )
        YtMoment.ACCEPT -> YtHapticTexture.of(
            "mini_accept_movo",
            YtFeedback.CONFIRM,
            YtHapticStep(YtPrimitive.CLICK, 0.8f),
            YtHapticStep(YtPrimitive.TICK, 0.5f, 70),
        )
        // knocked back: three quick knocks that die down with the shake
        YtMoment.REJECT -> YtHapticTexture.of(
            "mini_reject_movo",
            YtFeedback.REJECT,
            YtHapticStep(YtPrimitive.CLICK, 0.6f),
            YtHapticStep(YtPrimitive.CLICK, 0.45f, 55),
            YtHapticStep(YtPrimitive.TICK, 0.3f, 55),
        )
    }
}
