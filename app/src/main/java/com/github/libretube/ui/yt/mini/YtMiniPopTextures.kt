package com.github.libretube.ui.yt.mini

import com.github.libretube.ui.yt.YtFeedback
import com.github.libretube.ui.yt.YtHapticStep
import com.github.libretube.ui.yt.YtHapticTexture
import com.github.libretube.ui.yt.YtPrimitive

/**
 * YT: the feel of the Pop mini player: rubbery and playful. Docking is the jelly landing (a
 * fall and a tick) and one more tick as the blob pops back; the long press rises quickly as the
 * balloon inflates, the sticker slaps on with a click; a dropped card is gulped: a click as the
 * mouth snaps shut and a short spin as it goes down. The names keep the moment's neutral name
 * ("mini_dock", "mini_peek"), which the emulator checks look for, with the design behind it.
 * Pure, for the JVM tests; where the primitives cannot play, each falls back to its touch
 * feedback constant.
 */
object YtMiniPopTextures {
    fun of(moment: YtMoment): YtHapticTexture = when (moment) {
        // the jelly landing (fall, squash), then the blob pops back up
        YtMoment.DOCK -> YtHapticTexture.of(
            "mini_dock_pop",
            YtFeedback.CONFIRM,
            YtHapticStep(YtPrimitive.QUICK_FALL, 0.3f),
            YtHapticStep(YtPrimitive.TICK, 0.5f, 60),
            YtHapticStep(YtPrimitive.TICK, 0.5f, 90),
        )
        // the balloon inflates
        YtMoment.PEEK_START -> YtHapticTexture.of("mini_peek_pop", YtFeedback.GESTURE_START, YtHapticStep(YtPrimitive.QUICK_RISE, 0.6f))
        // the sticker slaps on
        YtMoment.PEEK_FULL -> YtHapticTexture.of("mini_peek_full_pop", YtFeedback.CLOCK_TICK, YtHapticStep(YtPrimitive.CLICK, 0.45f))
        // the air goes out: a fall and a soft tick as the pill lands back on the band
        YtMoment.PEEK_RELEASE -> YtHapticTexture.of(
            "mini_peek_release_pop",
            YtFeedback.GESTURE_END,
            YtHapticStep(YtPrimitive.QUICK_FALL, 0.45f),
            YtHapticStep(YtPrimitive.TICK, 0.3f, 50),
        )
        YtMoment.PEEK_TARGET -> YtHapticTexture.of("mini_peek_target_pop", YtFeedback.SEGMENT_TICK, YtHapticStep(YtPrimitive.TICK, 0.5f))
        // a squeeze of the blob: a click with a little bounce after it
        YtMoment.PLAY_TOGGLE -> YtHapticTexture.of(
            "mini_play_pop",
            YtFeedback.TOGGLE_ON,
            YtHapticStep(YtPrimitive.CLICK, 0.6f),
            YtHapticStep(YtPrimitive.TICK, 0.3f, 50),
        )
        // the sticker is peeled off and slapped onto the player
        YtMoment.PLAY_NEXT -> YtHapticTexture.of(
            "mini_next_pop",
            YtFeedback.CONFIRM,
            YtHapticStep(YtPrimitive.QUICK_RISE, 0.4f),
            YtHapticStep(YtPrimitive.CLICK, 0.7f, 60),
        )
        YtMoment.BUTTON -> YtHapticTexture.of("mini_button_pop", YtFeedback.CLOCK_TICK, YtHapticStep(YtPrimitive.TICK, 0.6f))
        // a card comes near: the mouth opens
        YtMoment.HOVER -> YtHapticTexture.of(
            "mini_hover_pop",
            YtFeedback.GESTURE_THRESHOLD_ACTIVATE,
            YtHapticStep(YtPrimitive.QUICK_RISE, 0.3f),
            YtHapticStep(YtPrimitive.TICK, 0.3f, 30),
        )
        // the gulp: the mouth snaps shut, then it goes down
        YtMoment.ACCEPT -> YtHapticTexture.of(
            "mini_accept_pop",
            YtFeedback.CONFIRM,
            YtHapticStep(YtPrimitive.CLICK, 0.7f),
            YtHapticStep(YtPrimitive.SPIN, 0.4f, 40),
        )
        // spat out: two clicks that fall away with the shake
        YtMoment.REJECT -> YtHapticTexture.of(
            "mini_reject_pop",
            YtFeedback.REJECT,
            YtHapticStep(YtPrimitive.CLICK, 0.6f),
            YtHapticStep(YtPrimitive.QUICK_FALL, 0.4f, 40),
            YtHapticStep(YtPrimitive.CLICK, 0.35f, 50),
        )
    }
}
