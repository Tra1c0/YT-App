package com.github.libretube.ui.yt.mini

import com.github.libretube.ui.yt.YtFeedback
import com.github.libretube.ui.yt.YtHapticStep
import com.github.libretube.ui.yt.YtHapticTexture
import com.github.libretube.ui.yt.YtPrimitive

/**
 * YT: the feel of the Synthwave player ("Neonkante"): a synth. Docking is a kick with a soft swell
 * after it (the car taking the bump), the preview opens like a filter sweep, a taken drop is an
 * arpeggio of three rising ticks. Pure, so the fallbacks can be tested; the names keep the
 * moment's neutral name (the emulator checks look for "mini_dock", "mini_peek") with the design
 * behind it.
 */
object YtMiniSynthwaveTextures {
    fun of(moment: YtMoment): YtHapticTexture = when (moment) {
        YtMoment.DOCK -> YtHapticTexture.of(
            "mini_dock_synthwave",
            YtFeedback.CONFIRM,
            YtHapticStep(YtPrimitive.THUD, 0.5f),
            YtHapticStep(YtPrimitive.QUICK_RISE, 0.3f, 80),
        )
        YtMoment.PEEK_START -> YtHapticTexture.of("mini_peek_synthwave", YtFeedback.GESTURE_START, YtHapticStep(YtPrimitive.SLOW_RISE, 0.35f))
        YtMoment.PEEK_FULL -> YtHapticTexture.of("mini_peek_full_synthwave", YtFeedback.CLOCK_TICK, YtHapticStep(YtPrimitive.TICK, 0.3f))
        YtMoment.PEEK_RELEASE -> YtHapticTexture.of("mini_peek_release_synthwave", YtFeedback.GESTURE_END, YtHapticStep(YtPrimitive.QUICK_FALL, 0.3f))
        YtMoment.PEEK_TARGET -> YtHapticTexture.of("mini_peek_target_synthwave", YtFeedback.SEGMENT_TICK, YtHapticStep(YtPrimitive.TICK, 0.45f))
        YtMoment.PLAY_TOGGLE -> YtHapticTexture.of("mini_play_synthwave", YtFeedback.TOGGLE_ON, YtHapticStep(YtPrimitive.CLICK, 0.5f))
        YtMoment.PLAY_NEXT -> YtHapticTexture.of(
            "mini_next_synthwave",
            YtFeedback.CONFIRM,
            YtHapticStep(YtPrimitive.TICK, 0.4f),
            YtHapticStep(YtPrimitive.CLICK, 0.55f, 50),
        )
        YtMoment.BUTTON -> YtHapticTexture.of("mini_button_synthwave", YtFeedback.CLOCK_TICK, YtHapticStep(YtPrimitive.TICK, 0.55f))
        // a dragged video comes near: a soft pulse, like a held synth note
        YtMoment.HOVER -> YtHapticTexture.of(
            "mini_hover_synthwave",
            YtFeedback.GESTURE_THRESHOLD_ACTIVATE,
            YtHapticStep(YtPrimitive.LOW_TICK, 0.4f),
            YtHapticStep(YtPrimitive.LOW_TICK, 0.3f, 90),
        )
        // taken: an arpeggio, three ticks rising
        YtMoment.ACCEPT -> YtHapticTexture.of(
            "mini_accept_synthwave",
            YtFeedback.CONFIRM,
            YtHapticStep(YtPrimitive.TICK, 0.35f),
            YtHapticStep(YtPrimitive.TICK, 0.5f, 55),
            YtHapticStep(YtPrimitive.CLICK, 0.65f, 55),
        )
        YtMoment.REJECT -> YtHapticTexture.REJECT.copy(name = "mini_reject_synthwave")
    }
}
