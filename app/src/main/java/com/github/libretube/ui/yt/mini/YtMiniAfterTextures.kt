package com.github.libretube.ui.yt.mini

import com.github.libretube.ui.yt.YtFeedback
import com.github.libretube.ui.yt.YtHapticStep
import com.github.libretube.ui.yt.YtHapticTexture
import com.github.libretube.ui.yt.YtPrimitive

/**
 * YT: the feel of the After player, a projector. Docking rattles four times (the claw pulling
 * film through the gate, a light tick every 35 ms) and lands with a thud; the preview's press
 * starts the rattle and the open gate locks with the thud; a dropped card is pulled in with a
 * shorter rattle and clicks into the reel; a refused one jams. Fallbacks are the neutral ones,
 * so the system feedback stays the same where the steps cannot play. The names keep the
 * moment's neutral name (the emulator checks look for "mini_dock", "mini_peek").
 */
object YtMiniAfterTextures {
    private fun rattle(times: Int, scale: Float): Array<YtHapticStep> =
        Array(times) { i -> YtHapticStep(YtPrimitive.TICK, scale, if (i == 0) 0 else YtAfterMath.RATTLE_STEP) }

    fun of(moment: YtMoment): YtHapticTexture = when (moment) {
        YtMoment.DOCK -> YtHapticTexture.of(
            "mini_dock_after",
            YtFeedback.CONFIRM,
            *rattle(YtAfterMath.RATTLE_STEPS, 0.3f),
            YtHapticStep(YtPrimitive.THUD, 0.4f, YtAfterMath.RATTLE_STEP),
        )
        // the motor starts: the rattle runs while the frame grows ...
        YtMoment.PEEK_START -> YtHapticTexture.of(
            "mini_peek_after",
            YtFeedback.GESTURE_START,
            *rattle(YtAfterMath.RATTLE_STEPS, 0.3f),
        )
        // ... and the open gate locks with the thud
        YtMoment.PEEK_FULL -> YtHapticTexture.of("mini_peek_full_after", YtFeedback.CLOCK_TICK, YtHapticStep(YtPrimitive.THUD, 0.4f))
        // the gate closes again: two ticks running down
        YtMoment.PEEK_RELEASE -> YtHapticTexture.of(
            "mini_peek_release_after",
            YtFeedback.GESTURE_END,
            YtHapticStep(YtPrimitive.TICK, 0.3f),
            YtHapticStep(YtPrimitive.TICK, 0.18f, YtAfterMath.RATTLE_STEP),
        )
        YtMoment.PEEK_TARGET -> YtHapticTexture.of("mini_peek_target_after", YtFeedback.SEGMENT_TICK, YtHapticStep(YtPrimitive.TICK, 0.4f))
        // the projector's switch: a click and its spring
        YtMoment.PLAY_TOGGLE -> YtHapticTexture.of(
            "mini_play_after",
            YtFeedback.TOGGLE_ON,
            YtHapticStep(YtPrimitive.CLICK, 0.55f),
            YtHapticStep(YtPrimitive.TICK, 0.25f, YtAfterMath.RATTLE_STEP),
        )
        // the reel changes: two pulls, then the new film clicks in
        YtMoment.PLAY_NEXT -> YtHapticTexture.of(
            "mini_next_after",
            YtFeedback.CONFIRM,
            *rattle(2, 0.3f),
            YtHapticStep(YtPrimitive.CLICK, 0.7f, 60),
        )
        YtMoment.BUTTON -> YtHapticTexture.of("mini_button_after", YtFeedback.CLOCK_TICK, YtHapticStep(YtPrimitive.TICK, 0.55f))
        // the reel catches the film: one light pull
        YtMoment.HOVER -> YtHapticTexture.of("mini_hover_after", YtFeedback.GESTURE_THRESHOLD_ACTIVATE, YtHapticStep(YtPrimitive.TICK, 0.35f))
        YtMoment.ACCEPT -> YtHapticTexture.of(
            "mini_accept_after",
            YtFeedback.CONFIRM,
            *rattle(3, 0.3f),
            YtHapticStep(YtPrimitive.CLICK, 0.6f, YtAfterMath.RATTLE_STEP),
        )
        // the film jams: a dull knock, then two stuck ticks
        YtMoment.REJECT -> YtHapticTexture.of(
            "mini_reject_after",
            YtFeedback.REJECT,
            YtHapticStep(YtPrimitive.THUD, 0.5f),
            YtHapticStep(YtPrimitive.TICK, 0.35f, 40),
            YtHapticStep(YtPrimitive.TICK, 0.35f, 40),
        )
    }
}
