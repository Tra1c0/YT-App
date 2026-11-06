package com.github.libretube.ui.yt.mini

import com.github.libretube.ui.yt.YtFeedback
import com.github.libretube.ui.yt.YtHapticStep
import com.github.libretube.ui.yt.YtHapticTexture
import com.github.libretube.ui.yt.YtPrimitive

/**
 * YT: the feel of the Formen mini player: lively and mechanical, like a shape sorter. The cookie
 * window turns in on docking with a click and a light knock; the long press unwinds it with a
 * spin; a dropped video turns the cookie a quarter in three detents (a spin, then a tick every
 * 30°). The names keep the moment's neutral name ("mini_dock", "mini_peek"), which the emulator
 * checks look for, with the design behind it. Pure, for the JVM tests; where the primitives
 * cannot play (SPIN and LOW_TICK need Android 12), each falls back to its touch feedback constant.
 */
object YtMiniShapesTextures {
    /** The detents of a taken drop: one tick per 30°, about as far apart as the window's steps. */
    const val DETENT_GAP_MS = 110

    fun of(moment: YtMoment): YtHapticTexture = when (moment) {
        // the cookie turns in and clicks into its window
        YtMoment.DOCK -> YtHapticTexture.of(
            "mini_dock_shapes",
            YtFeedback.CONFIRM,
            YtHapticStep(YtPrimitive.CLICK, 0.6f),
            YtHapticStep(YtPrimitive.TICK, 0.4f, 70),
        )
        // the scallops unwind as the window grows
        YtMoment.PEEK_START -> YtHapticTexture.of("mini_peek_shapes", YtFeedback.GESTURE_START, YtHapticStep(YtPrimitive.SPIN, 0.45f))
        YtMoment.PEEK_FULL -> YtHapticTexture.of("mini_peek_full_shapes", YtFeedback.CLOCK_TICK, YtHapticStep(YtPrimitive.TICK, 0.45f))
        // the window shrinks and the scallops snap back
        YtMoment.PEEK_RELEASE -> YtHapticTexture.of(
            "mini_peek_release_shapes",
            YtFeedback.GESTURE_END,
            YtHapticStep(YtPrimitive.QUICK_FALL, 0.3f),
            YtHapticStep(YtPrimitive.TICK, 0.3f, 80),
        )
        YtMoment.PEEK_TARGET -> YtHapticTexture.of("mini_peek_target_shapes", YtFeedback.SEGMENT_TICK, YtHapticStep(YtPrimitive.TICK, 0.5f))
        // a press on the pink cookie: a click with a light knock after
        YtMoment.PLAY_TOGGLE -> YtHapticTexture.of(
            "mini_play_shapes",
            YtFeedback.TOGGLE_ON,
            YtHapticStep(YtPrimitive.CLICK, 0.6f),
            YtHapticStep(YtPrimitive.TICK, 0.3f, 40),
        )
        // the next video's cookie turns into the window
        YtMoment.PLAY_NEXT -> YtHapticTexture.of(
            "mini_next_shapes",
            YtFeedback.CONFIRM,
            YtHapticStep(YtPrimitive.QUICK_RISE, 0.4f),
            YtHapticStep(YtPrimitive.CLICK, 0.7f, 60),
        )
        YtMoment.BUTTON -> YtHapticTexture.of("mini_button_shapes", YtFeedback.CLOCK_TICK, YtHapticStep(YtPrimitive.TICK, 0.6f))
        // a card comes over the window: the cookie leans towards it with a low knock and a tick
        YtMoment.HOVER -> YtHapticTexture.of(
            "mini_hover_shapes",
            YtFeedback.GESTURE_THRESHOLD_ACTIVATE,
            YtHapticStep(YtPrimitive.LOW_TICK, 0.4f),
            YtHapticStep(YtPrimitive.TICK, 0.3f, 40),
        )
        // a quarter turn in three detents
        YtMoment.ACCEPT -> YtHapticTexture.of(
            "mini_accept_shapes",
            YtFeedback.CONFIRM,
            YtHapticStep(YtPrimitive.SPIN, 0.5f),
            YtHapticStep(YtPrimitive.TICK, 0.5f, 40),
            YtHapticStep(YtPrimitive.TICK, 0.5f, DETENT_GAP_MS),
            YtHapticStep(YtPrimitive.TICK, 0.5f, DETENT_GAP_MS),
        )
        // the cookie shrugs: two knocks, the second lighter
        YtMoment.REJECT -> YtHapticTexture.of(
            "mini_reject_shapes",
            YtFeedback.REJECT,
            YtHapticStep(YtPrimitive.CLICK, 0.6f),
            YtHapticStep(YtPrimitive.TICK, 0.4f, 70),
        )
    }
}
