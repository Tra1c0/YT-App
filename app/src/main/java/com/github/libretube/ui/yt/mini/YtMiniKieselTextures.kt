package com.github.libretube.ui.yt.mini

import com.github.libretube.ui.yt.YtFeedback
import com.github.libretube.ui.yt.YtHapticStep
import com.github.libretube.ui.yt.YtHapticTexture
import com.github.libretube.ui.yt.YtPrimitive

/**
 * YT: the feel of the Kiesel player ("Schwarzer Kiesel"): a heavy, smooth stone. It lands with a
 * thud and three bounce ticks fading out at 140, 240 and 320 ms, the same moments the pebble
 * squashes on the bar (YtMiniKieselLayout.DOCK); the long press squeezes it with a low tick; a
 * drop lands on it with a thud and the tick of its one hop. Android's composition delays count
 * from the end of the step before, so the ticks come a few ms later than the squashes; too
 * little to feel. Pure, so the fallbacks can be tested; the names keep the moment's neutral
 * name (the emulator checks look for "mini_dock", "mini_peek") with the design behind it.
 */
object YtMiniKieselTextures {
    fun of(moment: YtMoment): YtHapticTexture = when (moment) {
        YtMoment.DOCK -> YtHapticTexture.of(
            "mini_dock_kiesel",
            YtFeedback.CONFIRM,
            YtHapticStep(YtPrimitive.THUD, 0.9f),
            YtHapticStep(YtPrimitive.TICK, 0.6f, 140),
            YtHapticStep(YtPrimitive.TICK, 0.35f, 100),
            YtHapticStep(YtPrimitive.TICK, 0.15f, 80),
        )
        // the press takes: the stone is squeezed
        YtMoment.PEEK_START -> YtHapticTexture.of("mini_peek_kiesel", YtFeedback.GESTURE_START, YtHapticStep(YtPrimitive.LOW_TICK, 0.6f))
        // it has sprung up into the tall pebble
        YtMoment.PEEK_FULL -> YtHapticTexture.of("mini_peek_full_kiesel", YtFeedback.CLOCK_TICK, YtHapticStep(YtPrimitive.TICK, 0.3f))
        // let go: it sinks back onto the bar, a soft thud
        YtMoment.PEEK_RELEASE -> YtHapticTexture.of("mini_peek_release_kiesel", YtFeedback.GESTURE_END, YtHapticStep(YtPrimitive.THUD, 0.35f))
        YtMoment.PEEK_TARGET -> YtHapticTexture.of("mini_peek_target_kiesel", YtFeedback.SEGMENT_TICK, YtHapticStep(YtPrimitive.TICK, 0.45f))
        // a knock on stone: a click and its low echo
        YtMoment.PLAY_TOGGLE -> YtHapticTexture.of(
            "mini_play_kiesel",
            YtFeedback.TOGGLE_ON,
            YtHapticStep(YtPrimitive.CLICK, 0.55f),
            YtHapticStep(YtPrimitive.LOW_TICK, 0.3f, 30),
        )
        // the capsule of the next video rolls over and clicks into place
        YtMoment.PLAY_NEXT -> YtHapticTexture.of(
            "mini_next_kiesel",
            YtFeedback.CONFIRM,
            YtHapticStep(YtPrimitive.TICK, 0.3f),
            YtHapticStep(YtPrimitive.TICK, 0.3f, 50),
            YtHapticStep(YtPrimitive.CLICK, 0.6f, 50),
        )
        YtMoment.BUTTON -> YtHapticTexture.of("mini_button_kiesel", YtFeedback.CLOCK_TICK, YtHapticStep(YtPrimitive.TICK, 0.55f))
        // a dragged video comes over the stone: a low tick, as a pebble rubbing on another
        YtMoment.HOVER -> YtHapticTexture.of("mini_hover_kiesel", YtFeedback.GESTURE_THRESHOLD_ACTIVATE, YtHapticStep(YtPrimitive.LOW_TICK, 0.45f))
        // the counter lands on the stone (thud), the stone hops once and lands (tick)
        YtMoment.ACCEPT -> YtHapticTexture.of(
            "mini_accept_kiesel",
            YtFeedback.CONFIRM,
            YtHapticStep(YtPrimitive.THUD, 0.6f),
            YtHapticStep(YtPrimitive.TICK, 0.3f, 200),
        )
        YtMoment.REJECT -> YtHapticTexture.REJECT.copy(name = "mini_reject_kiesel")
    }
}
