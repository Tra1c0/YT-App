package com.github.libretube.ui.yt.mini

import com.github.libretube.ui.yt.YtFeedback
import com.github.libretube.ui.yt.YtHapticStep
import com.github.libretube.ui.yt.YtHapticTexture
import com.github.libretube.ui.yt.YtPrimitive

/**
 * YT: the feel of the YouTube neu player ("Rote Naht"): calm and crisp, like a seam being sewn.
 * Clicks where something is decided (docking, a drop taken), light ticks for what only looks
 * (the preview), a second smaller tick where a thread is pulled tight (a drop, the next video).
 * Pure, so the fallbacks can be tested; the names keep the moment's neutral name (the emulator
 * checks look for "mini_dock", "mini_peek") with the design behind it.
 */
object YtMiniYoutubeTextures {
    fun of(moment: YtMoment): YtHapticTexture = when (moment) {
        // the player sits down on the bar: one firm click
        YtMoment.DOCK -> YtHapticTexture.of("mini_dock_youtube", YtFeedback.CONFIRM, YtHapticStep(YtPrimitive.CLICK, 0.7f))
        YtMoment.PEEK_START -> YtHapticTexture.of("mini_peek_youtube", YtFeedback.GESTURE_START, YtHapticStep(YtPrimitive.TICK, 0.4f))
        // the preview arrives without a bounce: only a whisper of a tick
        YtMoment.PEEK_FULL -> YtHapticTexture.of("mini_peek_full_youtube", YtFeedback.CLOCK_TICK, YtHapticStep(YtPrimitive.TICK, 0.2f))
        YtMoment.PEEK_RELEASE -> YtHapticTexture.of("mini_peek_release_youtube", YtFeedback.GESTURE_END, YtHapticStep(YtPrimitive.TICK, 0.25f))
        YtMoment.PEEK_TARGET -> YtHapticTexture.of("mini_peek_target_youtube", YtFeedback.SEGMENT_TICK, YtHapticStep(YtPrimitive.TICK, 0.5f))
        YtMoment.PLAY_TOGGLE -> YtHapticTexture.of("mini_play_youtube", YtFeedback.TOGGLE_ON, YtHapticStep(YtPrimitive.CLICK, 0.55f))
        YtMoment.PLAY_NEXT -> YtHapticTexture.of(
            "mini_next_youtube",
            YtFeedback.CONFIRM,
            YtHapticStep(YtPrimitive.CLICK, 0.6f),
            YtHapticStep(YtPrimitive.TICK, 0.35f, 50),
        )
        YtMoment.BUTTON -> YtHapticTexture.of("mini_button_youtube", YtFeedback.CLOCK_TICK, YtHapticStep(YtPrimitive.TICK, 0.6f))
        // a dragged video comes over the seam: two quick stitches
        YtMoment.HOVER -> YtHapticTexture.of(
            "mini_hover_youtube",
            YtFeedback.GESTURE_THRESHOLD_ACTIVATE,
            YtHapticStep(YtPrimitive.TICK, 0.3f),
            YtHapticStep(YtPrimitive.TICK, 0.3f, 45),
        )
        // taken: a click, then the thread pulled tight
        YtMoment.ACCEPT -> YtHapticTexture.of(
            "mini_accept_youtube",
            YtFeedback.CONFIRM,
            YtHapticStep(YtPrimitive.CLICK, 0.5f),
            YtHapticStep(YtPrimitive.TICK, 0.3f, 50),
        )
        YtMoment.REJECT -> YtHapticTexture.REJECT.copy(name = "mini_reject_youtube")
    }
}
