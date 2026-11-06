package com.github.libretube.ui.yt.mini

import com.github.libretube.ui.yt.YtFeedback
import com.github.libretube.ui.yt.YtHapticStep
import com.github.libretube.ui.yt.YtHapticTexture
import com.github.libretube.ui.yt.YtPrimitive

/**
 * YT: the feel of the Clay player ("Knetkugel"): soft and heavy, like a ball of dough. It lands
 * with a deep thud; a long press kneads it (low ticks every 80 ms for the first 400 ms) before
 * it stretches; a dropped video sinks into a dent with a thud and a light tick as the dough
 * closes over it. Pure, so the fallbacks can be tested; the names keep the moment's neutral name
 * (the emulator checks look for "mini_dock", "mini_peek") with the design behind it.
 */
object YtMiniClayTextures {
    /** The kneading of a long press: six low ticks, 80 ms apart (0 to 400 ms). */
    const val KNEAD_TICKS = 6
    const val KNEAD_GAP = 80

    private fun knead(): Array<YtHapticStep> =
        Array(KNEAD_TICKS) { i -> YtHapticStep(YtPrimitive.LOW_TICK, 0.3f, if (i == 0) 0 else KNEAD_GAP) }

    fun of(moment: YtMoment): YtHapticTexture = when (moment) {
        YtMoment.DOCK -> YtHapticTexture.of("mini_dock_clay", YtFeedback.CONFIRM, YtHapticStep(YtPrimitive.THUD, 0.8f))
        YtMoment.PEEK_START -> YtHapticTexture.of("mini_peek_clay", YtFeedback.GESTURE_START, *knead())
        // the head has risen: a soft plop as the neck fills out
        YtMoment.PEEK_FULL -> YtHapticTexture.of(
            "mini_peek_full_clay",
            YtFeedback.CLOCK_TICK,
            YtHapticStep(YtPrimitive.THUD, 0.3f),
            YtHapticStep(YtPrimitive.LOW_TICK, 0.25f, 50),
        )
        // the dough sinks back into the ball
        YtMoment.PEEK_RELEASE -> YtHapticTexture.of(
            "mini_peek_release_clay",
            YtFeedback.GESTURE_END,
            YtHapticStep(YtPrimitive.QUICK_FALL, 0.35f),
            YtHapticStep(YtPrimitive.LOW_TICK, 0.4f, 70),
        )
        YtMoment.PEEK_TARGET -> YtHapticTexture.of("mini_peek_target_clay", YtFeedback.SEGMENT_TICK, YtHapticStep(YtPrimitive.LOW_TICK, 0.5f))
        // a poke into the dough
        YtMoment.PLAY_TOGGLE -> YtHapticTexture.of("mini_play_clay", YtFeedback.TOGGLE_ON, YtHapticStep(YtPrimitive.THUD, 0.45f))
        // the small ball pinches off and is pressed into the big one
        YtMoment.PLAY_NEXT -> YtHapticTexture.of(
            "mini_next_clay",
            YtFeedback.CONFIRM,
            YtHapticStep(YtPrimitive.QUICK_RISE, 0.4f),
            YtHapticStep(YtPrimitive.THUD, 0.6f, 80),
        )
        YtMoment.BUTTON -> YtHapticTexture.of("mini_button_clay", YtFeedback.CLOCK_TICK, YtHapticStep(YtPrimitive.LOW_TICK, 0.6f))
        // the dough gives under the card: two soft low ticks
        YtMoment.HOVER -> YtHapticTexture.of(
            "mini_hover_clay",
            YtFeedback.GESTURE_THRESHOLD_ACTIVATE,
            YtHapticStep(YtPrimitive.LOW_TICK, 0.4f),
            YtHapticStep(YtPrimitive.LOW_TICK, 0.25f, 60),
        )
        YtMoment.ACCEPT -> YtHapticTexture.of(
            "mini_accept_clay",
            YtFeedback.CONFIRM,
            YtHapticStep(YtPrimitive.THUD, 0.6f),
            YtHapticStep(YtPrimitive.TICK, 0.4f, 60),
        )
        YtMoment.REJECT -> YtHapticTexture.REJECT.copy(name = "mini_reject_clay")
    }
}
