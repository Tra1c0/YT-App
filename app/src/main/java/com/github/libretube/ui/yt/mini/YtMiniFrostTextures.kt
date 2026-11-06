package com.github.libretube.ui.yt.mini

import com.github.libretube.ui.yt.YtFeedback
import com.github.libretube.ui.yt.YtHapticStep
import com.github.libretube.ui.yt.YtHapticTexture
import com.github.libretube.ui.yt.YtPrimitive

/**
 * YT: the feel of the Frost player: ice. Freezing crackles (three low ticks fading out, 40 ms
 * apart, as crystals snapping into place), thawing rises slowly, a tap on the ice is a click with
 * a low after-tick. Pure, so the fallbacks can be tested. The names keep the moment's neutral name
 * (the emulator checks look for "mini_dock", "mini_peek") with the design behind it.
 */
object YtFrostTextures {
    /** Crystals snapping into place: low ticks at [scales], 40 ms apart. */
    private fun crackle(vararg scales: Float): Array<YtHapticStep> =
        scales.mapIndexed { i, scale -> YtHapticStep(YtPrimitive.LOW_TICK, scale, if (i == 0) 0 else 40) }.toTypedArray()

    fun of(moment: YtMoment): YtHapticTexture = when (moment) {
        // the window freezes over as the player lands
        YtMoment.DOCK -> YtHapticTexture.of("mini_dock_frost", YtFeedback.CONFIRM, *crackle(0.5f, 0.35f, 0.2f))
        // the press takes: the ice starts to thaw under the finger
        YtMoment.PEEK_START -> YtHapticTexture.of("mini_peek_frost", YtFeedback.GESTURE_START, YtHapticStep(YtPrimitive.SLOW_RISE, 0.3f))
        // thawed through: one light tick
        YtMoment.PEEK_FULL -> YtHapticTexture.of("mini_peek_full_frost", YtFeedback.CLOCK_TICK, YtHapticStep(YtPrimitive.TICK, 0.3f))
        // let go: it freezes back, a shorter crackle
        YtMoment.PEEK_RELEASE -> YtHapticTexture.of("mini_peek_release_frost", YtFeedback.GESTURE_END, *crackle(0.35f, 0.2f))
        // the finger on the card or the pill: a tick, then the ice under it gives
        YtMoment.PEEK_TARGET -> YtHapticTexture.of(
            "mini_peek_target_frost",
            YtFeedback.SEGMENT_TICK,
            YtHapticStep(YtPrimitive.TICK, 0.35f),
            YtHapticStep(YtPrimitive.SLOW_RISE, 0.2f, 10),
        )
        // a tap on ice
        YtMoment.PLAY_TOGGLE -> YtHapticTexture.of(
            "mini_play_frost",
            YtFeedback.CLOCK_TICK,
            YtHapticStep(YtPrimitive.CLICK, 0.45f),
            YtHapticStep(YtPrimitive.LOW_TICK, 0.25f, 40),
        )
        // the card thaws and is taken
        YtMoment.PLAY_NEXT -> YtHapticTexture.of(
            "mini_next_frost",
            YtFeedback.CONFIRM,
            YtHapticStep(YtPrimitive.SLOW_RISE, 0.3f),
            YtHapticStep(YtPrimitive.CLICK, 0.5f, 80),
        )
        YtMoment.BUTTON -> YtHapticTexture.of("mini_button_frost", YtFeedback.CLOCK_TICK, YtHapticStep(YtPrimitive.TICK, 0.5f))
        // a dragged card breathes fog onto the window
        YtMoment.HOVER -> YtHapticTexture.of(
            "mini_hover_frost",
            YtFeedback.GESTURE_THRESHOLD_ACTIVATE,
            YtHapticStep(YtPrimitive.LOW_TICK, 0.4f),
            YtHapticStep(YtPrimitive.LOW_TICK, 0.25f, 40),
        )
        // the card lands and freezes into the pane
        YtMoment.ACCEPT -> YtHapticTexture.of(
            "mini_accept_frost",
            YtFeedback.CONFIRM,
            YtHapticStep(YtPrimitive.CLICK, 0.5f),
            *crackle(0.5f, 0.35f, 0.2f).also { it[0] = it[0].copy(delayMs = 40) },
        )
        YtMoment.REJECT -> YtHapticTexture.REJECT.copy(name = "mini_reject_frost")
    }
}
