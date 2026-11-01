package com.github.libretube.ui.yt.mini

import com.github.libretube.ui.yt.YtFeedback
import com.github.libretube.ui.yt.YtHapticStep
import com.github.libretube.ui.yt.YtHapticTexture
import com.github.libretube.ui.yt.YtPrimitive

/** YT: the moments of the docked mini player that each design gives a feel of its own. */
enum class YtMoment {
    /** The player lands on the bar after minimizing. */
    DOCK,

    /** The long press takes: the preview starts to grow. */
    PEEK_START,

    /** The preview has reached its full size. */
    PEEK_FULL,

    /** The finger lets go and the preview shrinks back. */
    PEEK_RELEASE,

    /** The finger moves onto a part of the preview (the pill, the next video). */
    PEEK_TARGET,

    /** Play or pause toggles (double tap, the pill). */
    PLAY_TOGGLE,

    /** The next video starts from the preview. */
    PLAY_NEXT,

    /** A button beside the picture is pressed (prev, next). */
    BUTTON,

    /** A dragged video comes over the player. */
    HOVER,

    /** The player takes a dropped video. */
    ACCEPT,

    /** The player does not take it (the same video, no player). */
    REJECT,
}

/**
 * YT: the neutral haptics of the mini player, until a design brings its own (YtMiniDesign.haptic).
 * Pure, so the fallbacks can be tested: where the vibrator cannot compose the steps (before
 * Android 11, no primitives, "Eigene Haptik" off) each plays its system feedback constant.
 */
object YtMiniTextures {
    fun neutral(moment: YtMoment): YtHapticTexture = when (moment) {
        YtMoment.DOCK -> YtHapticTexture.of(
            "mini_dock",
            YtFeedback.CONFIRM,
            YtHapticStep(YtPrimitive.QUICK_FALL, 0.3f),
            YtHapticStep(YtPrimitive.TICK, 0.5f, 60),
        )
        YtMoment.PEEK_START -> YtHapticTexture.of("mini_peek", YtFeedback.GESTURE_START, YtHapticStep(YtPrimitive.LOW_TICK, 0.5f))
        YtMoment.PEEK_FULL -> YtHapticTexture.of("mini_peek_full", YtFeedback.CLOCK_TICK, YtHapticStep(YtPrimitive.TICK, 0.4f))
        YtMoment.PEEK_RELEASE -> YtHapticTexture.of("mini_peek_release", YtFeedback.GESTURE_END, YtHapticStep(YtPrimitive.QUICK_FALL, 0.3f))
        YtMoment.PEEK_TARGET -> YtHapticTexture.of("mini_peek_target", YtFeedback.SEGMENT_TICK, YtHapticStep(YtPrimitive.TICK, 0.5f))
        YtMoment.PLAY_TOGGLE -> YtHapticTexture.of("mini_play", YtFeedback.TOGGLE_ON, YtHapticStep(YtPrimitive.CLICK, 0.6f))
        YtMoment.PLAY_NEXT -> YtHapticTexture.of(
            "mini_next",
            YtFeedback.CONFIRM,
            YtHapticStep(YtPrimitive.CLICK, 0.7f),
            YtHapticStep(YtPrimitive.TICK, 0.4f, 60),
        )
        YtMoment.BUTTON -> YtHapticTexture.of("mini_button", YtFeedback.CLOCK_TICK, YtHapticStep(YtPrimitive.TICK, 0.6f))
        YtMoment.HOVER -> YtHapticTexture.of("mini_hover", YtFeedback.GESTURE_THRESHOLD_ACTIVATE, YtHapticStep(YtPrimitive.LOW_TICK, 0.4f))
        YtMoment.ACCEPT -> YtHapticTexture.of(
            "mini_accept",
            YtFeedback.CONFIRM,
            YtHapticStep(YtPrimitive.SLOW_RISE, 0.4f),
            YtHapticStep(YtPrimitive.CLICK, 0.6f, 90),
        )
        YtMoment.REJECT -> YtHapticTexture.REJECT.copy(name = "mini_reject")
    }

    /** The classic design: the same moments with the system's touch feedback only. */
    fun system(moment: YtMoment): YtHapticTexture = neutral(moment).copy(steps = emptyList())

    /**
     * Glas: soft and liquid. The press takes with a low tick, the full preview a light tick, the
     * release falls away; docking falls in and clicks shut; a dropped video rises like a drop into
     * the glass and merges with a click. The names keep the moment's neutral name (the emulator
     * checks look for "mini_dock", "mini_peek") with the design behind it.
     */
    fun glass(moment: YtMoment): YtHapticTexture = when (moment) {
        YtMoment.DOCK -> YtHapticTexture.of(
            "mini_dock_glass",
            YtFeedback.CONFIRM,
            YtHapticStep(YtPrimitive.QUICK_FALL, 0.3f),
            YtHapticStep(YtPrimitive.TICK, 0.5f, 60),
        )
        YtMoment.PEEK_START -> YtHapticTexture.of("mini_peek_glass", YtFeedback.GESTURE_START, YtHapticStep(YtPrimitive.LOW_TICK, 0.5f))
        YtMoment.PEEK_FULL -> YtHapticTexture.of("mini_peek_full_glass", YtFeedback.CLOCK_TICK, YtHapticStep(YtPrimitive.TICK, 0.4f))
        YtMoment.PEEK_RELEASE -> YtHapticTexture.of("mini_peek_release_glass", YtFeedback.GESTURE_END, YtHapticStep(YtPrimitive.QUICK_FALL, 0.3f))
        YtMoment.PEEK_TARGET -> YtHapticTexture.of("mini_peek_target_glass", YtFeedback.SEGMENT_TICK, YtHapticStep(YtPrimitive.TICK, 0.35f))
        // a tap on glass: a light tick with a low after-ring
        YtMoment.PLAY_TOGGLE -> YtHapticTexture.of(
            "mini_play_glass",
            YtFeedback.CLOCK_TICK,
            YtHapticStep(YtPrimitive.TICK, 0.5f),
            YtHapticStep(YtPrimitive.LOW_TICK, 0.3f, 40),
        )
        YtMoment.PLAY_NEXT -> YtHapticTexture.of(
            "mini_next_glass",
            YtFeedback.CONFIRM,
            YtHapticStep(YtPrimitive.QUICK_RISE, 0.3f),
            YtHapticStep(YtPrimitive.CLICK, 0.5f, 70),
        )
        YtMoment.BUTTON -> YtHapticTexture.of("mini_button_glass", YtFeedback.CLOCK_TICK, YtHapticStep(YtPrimitive.TICK, 0.6f))
        YtMoment.HOVER -> YtHapticTexture.of("mini_hover_glass", YtFeedback.GESTURE_THRESHOLD_ACTIVATE, YtHapticStep(YtPrimitive.LOW_TICK, 0.4f))
        YtMoment.ACCEPT -> YtHapticTexture.of(
            "mini_accept_glass",
            YtFeedback.CONFIRM,
            YtHapticStep(YtPrimitive.SLOW_RISE, 0.4f),
            YtHapticStep(YtPrimitive.CLICK, 0.6f, 90),
        )
        YtMoment.REJECT -> YtHapticTexture.REJECT.copy(name = "mini_reject_glass")
    }
}
