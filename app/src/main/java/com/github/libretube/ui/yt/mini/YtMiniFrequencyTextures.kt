package com.github.libretube.ui.yt.mini

import com.github.libretube.ui.yt.YtFeedback
import com.github.libretube.ui.yt.YtHapticStep
import com.github.libretube.ui.yt.YtHapticTexture
import com.github.libretube.ui.yt.YtPrimitive

/**
 * YT: the feel of the Frequency player ("Pulsnaht"): a beat. Docking is a kick (a low thud, a
 * light tick after it like a hi-hat), the preview rises like a swell, a taken drop is a short
 * fill (two kicks). Pure, so the fallbacks can be tested; the names keep the moment's neutral
 * name (the emulator checks look for "mini_dock", "mini_peek") with the design behind it.
 */
object YtMiniFrequencyTextures {
    fun of(moment: YtMoment): YtHapticTexture = when (moment) {
        YtMoment.DOCK -> YtHapticTexture.of(
            "mini_dock_frequency",
            YtFeedback.CONFIRM,
            YtHapticStep(YtPrimitive.THUD, 0.6f),
            YtHapticStep(YtPrimitive.TICK, 0.35f, 70),
        )
        YtMoment.PEEK_START -> YtHapticTexture.of("mini_peek_frequency", YtFeedback.GESTURE_START, YtHapticStep(YtPrimitive.QUICK_RISE, 0.35f))
        YtMoment.PEEK_FULL -> YtHapticTexture.of("mini_peek_full_frequency", YtFeedback.CLOCK_TICK, YtHapticStep(YtPrimitive.TICK, 0.3f))
        YtMoment.PEEK_RELEASE -> YtHapticTexture.of("mini_peek_release_frequency", YtFeedback.GESTURE_END, YtHapticStep(YtPrimitive.QUICK_FALL, 0.3f))
        YtMoment.PEEK_TARGET -> YtHapticTexture.of("mini_peek_target_frequency", YtFeedback.SEGMENT_TICK, YtHapticStep(YtPrimitive.TICK, 0.5f))
        YtMoment.PLAY_TOGGLE -> YtHapticTexture.of("mini_play_frequency", YtFeedback.TOGGLE_ON, YtHapticStep(YtPrimitive.CLICK, 0.55f))
        YtMoment.PLAY_NEXT -> YtHapticTexture.of(
            "mini_next_frequency",
            YtFeedback.CONFIRM,
            YtHapticStep(YtPrimitive.CLICK, 0.55f),
            YtHapticStep(YtPrimitive.TICK, 0.35f, 60),
        )
        YtMoment.BUTTON -> YtHapticTexture.of("mini_button_frequency", YtFeedback.CLOCK_TICK, YtHapticStep(YtPrimitive.TICK, 0.6f))
        // a dragged video comes near: a hi-hat, twice
        YtMoment.HOVER -> YtHapticTexture.of(
            "mini_hover_frequency",
            YtFeedback.GESTURE_THRESHOLD_ACTIVATE,
            YtHapticStep(YtPrimitive.TICK, 0.3f),
            YtHapticStep(YtPrimitive.TICK, 0.3f, 60),
        )
        // taken: a short fill, two kicks
        YtMoment.ACCEPT -> YtHapticTexture.of(
            "mini_accept_frequency",
            YtFeedback.CONFIRM,
            YtHapticStep(YtPrimitive.THUD, 0.5f),
            YtHapticStep(YtPrimitive.THUD, 0.4f, 90),
        )
        YtMoment.REJECT -> YtHapticTexture.REJECT.copy(name = "mini_reject_frequency")
    }
}
