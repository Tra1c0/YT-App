package com.github.libretube.ui.yt.mini

import com.github.libretube.ui.yt.YtFeedback
import com.github.libretube.ui.yt.YtHapticStep
import com.github.libretube.ui.yt.YtHapticTexture
import com.github.libretube.ui.yt.YtPrimitive

/**
 * YT: the feel of the Editorial player, a typewriter on paper. Docking is one crisp key strike;
 * the preview's press types the kicker, a light tick per stroke (one per letter, at most eight,
 * 28 ms apart, in step with the letters appearing); a dropped card is set with a full strike,
 * a refused one jams the key. Everything is short and hard: no rises, no falls. Fallbacks are
 * the neutral constants, so the system feedback stays the same where the steps cannot play.
 * The names keep the moment's neutral name (the emulator checks look for "mini_dock",
 * "mini_peek").
 */
object YtMiniEditorialTextures {
    /** [strokes] typewriter ticks, the first at once. */
    fun typing(strokes: Int): Array<YtHapticStep> =
        Array(YtEditorialMath.typeSteps(strokes)) { i -> YtHapticStep(YtPrimitive.TICK, 0.3f, if (i == 0) 0 else YtEditorialMath.TYPE_MS) }

    /** The feel of [moment]; [kickerLetters] is how many letters the preview's kicker types. */
    fun of(moment: YtMoment, kickerLetters: Int = YtEditorialMath.TYPE_STEPS): YtHapticTexture = when (moment) {
        YtMoment.DOCK -> YtHapticTexture.of("mini_dock_editorial", YtFeedback.CONFIRM, YtHapticStep(YtPrimitive.CLICK, 1f))
        // the kicker types as the block unfolds ...
        YtMoment.PEEK_START -> YtHapticTexture.of("mini_peek_editorial", YtFeedback.GESTURE_START, *typing(kickerLetters))
        // ... and the line is set: the platen stops
        YtMoment.PEEK_FULL -> YtHapticTexture.of("mini_peek_full_editorial", YtFeedback.CLOCK_TICK, YtHapticStep(YtPrimitive.TICK, 0.45f))
        // the carriage returns: one dry tick
        YtMoment.PEEK_RELEASE -> YtHapticTexture.of("mini_peek_release_editorial", YtFeedback.GESTURE_END, YtHapticStep(YtPrimitive.TICK, 0.25f))
        YtMoment.PEEK_TARGET -> YtHapticTexture.of("mini_peek_target_editorial", YtFeedback.SEGMENT_TICK, YtHapticStep(YtPrimitive.TICK, 0.5f))
        // a key, struck
        YtMoment.PLAY_TOGGLE -> YtHapticTexture.of("mini_play_editorial", YtFeedback.TOGGLE_ON, YtHapticStep(YtPrimitive.CLICK, 0.7f))
        // a new line: the strike, then the line feed
        YtMoment.PLAY_NEXT -> YtHapticTexture.of(
            "mini_next_editorial",
            YtFeedback.CONFIRM,
            YtHapticStep(YtPrimitive.CLICK, 0.8f),
            YtHapticStep(YtPrimitive.TICK, 0.4f, 50),
        )
        YtMoment.BUTTON -> YtHapticTexture.of("mini_button_editorial", YtFeedback.CLOCK_TICK, YtHapticStep(YtPrimitive.TICK, 0.6f))
        // the caret is set where the card will go
        YtMoment.HOVER -> YtHapticTexture.of("mini_hover_editorial", YtFeedback.GESTURE_THRESHOLD_ACTIVATE, YtHapticStep(YtPrimitive.TICK, 0.4f))
        YtMoment.ACCEPT -> YtHapticTexture.of("mini_accept_editorial", YtFeedback.CONFIRM, YtHapticStep(YtPrimitive.CLICK, 1f))
        // a jammed key: two strikes that do not go through
        YtMoment.REJECT -> YtHapticTexture.of(
            "mini_reject_editorial",
            YtFeedback.REJECT,
            YtHapticStep(YtPrimitive.CLICK, 0.6f),
            YtHapticStep(YtPrimitive.CLICK, 0.6f, YtEditorialMath.JAM_MS),
        )
    }
}
