package com.github.libretube.ui.yt

/**
 * YT: the vibration primitives a haptic texture is made of, with the values of
 * android.os.VibrationEffect.Composition. Kept here, so textures need no Android to be written
 * down and tested.
 */
object YtPrimitive {
    const val CLICK = 1
    const val THUD = 2
    const val SPIN = 3
    const val QUICK_RISE = 4
    const val SLOW_RISE = 5
    const val QUICK_FALL = 6
    const val TICK = 7
    const val LOW_TICK = 8

    /**
     * The Android version a primitive can be composed from: Android 11 knows five of them and
     * refuses the others, THUD, SPIN and LOW_TICK came with Android 12.
     */
    fun minSdk(primitive: Int): Int = when (primitive) {
        CLICK, QUICK_RISE, SLOW_RISE, QUICK_FALL, TICK -> 30
        THUD, SPIN, LOW_TICK -> 31
        else -> Int.MAX_VALUE
    }
}

/**
 * YT: the touch feedback constants of android.view.HapticFeedbackConstants (same values), and
 * what each one becomes on the Android versions that do not know it yet.
 */
object YtFeedback {
    const val NO_HAPTICS = -1
    const val LONG_PRESS = 0
    const val VIRTUAL_KEY = 1
    const val KEYBOARD_TAP = 3
    const val CLOCK_TICK = 4
    const val CONTEXT_CLICK = 6
    const val TEXT_HANDLE_MOVE = 9
    const val GESTURE_START = 12
    const val GESTURE_END = 13
    const val CONFIRM = 16
    const val REJECT = 17
    const val TOGGLE_ON = 21
    const val TOGGLE_OFF = 22
    const val GESTURE_THRESHOLD_ACTIVATE = 23
    const val GESTURE_THRESHOLD_DEACTIVATE = 24
    const val DRAG_START = 25
    const val SEGMENT_TICK = 26
    const val SEGMENT_FREQUENT_TICK = 27

    /**
     * [constant] as it can play on [sdk]: itself where Android knows it, else an older constant
     * of a similar feel (a click for what starts or confirms, a tick for what ends or steps).
     * Constants not listed here stay as they are.
     */
    fun compat(constant: Int, sdk: Int): Int = when (constant) {
        TEXT_HANDLE_MOVE -> if (sdk >= 27) constant else CLOCK_TICK
        GESTURE_START, CONFIRM -> if (sdk >= 30) constant else CONTEXT_CLICK
        GESTURE_END -> if (sdk >= 30) constant else CLOCK_TICK
        REJECT -> if (sdk >= 30) constant else LONG_PRESS
        DRAG_START -> if (sdk >= 34) constant else compat(GESTURE_START, sdk)
        TOGGLE_ON, GESTURE_THRESHOLD_ACTIVATE -> if (sdk >= 34) constant else CONTEXT_CLICK
        TOGGLE_OFF, GESTURE_THRESHOLD_DEACTIVATE, SEGMENT_TICK, SEGMENT_FREQUENT_TICK ->
            if (sdk >= 34) constant else CLOCK_TICK
        NO_HAPTICS -> NO_HAPTICS
        else -> constant
    }
}

/** One primitive of a texture: which ([YtPrimitive]), how strong (0 to 1), after how many ms. */
data class YtHapticStep(val primitive: Int, val scale: Float = 1f, val delayMs: Int = 0)

/**
 * YT: a design's own feel for a moment (the player lands, a card is taken): a short sequence of
 * vibration primitives, and the touch feedback constant ([YtFeedback]) that plays instead where
 * the primitives cannot (no Android 11, a vibrator without them, the switch "Eigene Haptik" off).
 * [name] goes into the log of the emulator runs.
 */
data class YtHapticTexture(
    val name: String,
    val steps: List<YtHapticStep>,
    val fallback: Int,
) {
    /** Whether Android [sdk] can compose every step (whether the vibrator plays them is asked then). */
    fun composableOn(sdk: Int): Boolean =
        sdk >= 30 && steps.isNotEmpty() && steps.all { YtPrimitive.minSdk(it.primitive) <= sdk }

    companion object {
        fun of(name: String, fallback: Int, vararg steps: YtHapticStep) =
            YtHapticTexture(name, steps.toList(), fallback)

        /** A finger takes hold of something that will follow it (the video, a card). */
        val GRAB = of("grab", YtFeedback.GESTURE_START, YtHapticStep(YtPrimitive.LOW_TICK, 0.7f))

        /** A small step under the finger (a level of the magnifier, a frame). */
        val DETENT = of("detent", YtFeedback.SEGMENT_FREQUENT_TICK, YtHapticStep(YtPrimitive.TICK, 0.35f))

        /** Into another segment (a chapter, a section). */
        val SEGMENT = of("segment", YtFeedback.SEGMENT_TICK, YtHapticStep(YtPrimitive.TICK, 0.7f))

        /** A drag passes the point from which letting go does something. */
        val THRESHOLD_ON = of("threshold_on", YtFeedback.GESTURE_THRESHOLD_ACTIVATE, YtHapticStep(YtPrimitive.CLICK, 0.6f))

        /** The drag goes back behind that point. */
        val THRESHOLD_OFF = of("threshold_off", YtFeedback.GESTURE_THRESHOLD_DEACTIVATE, YtHapticStep(YtPrimitive.TICK, 0.4f))

        /** The finger lets go of what it held. */
        val RELEASE = of("release", YtFeedback.GESTURE_END, YtHapticStep(YtPrimitive.TICK, 0.3f))

        /** Something does not take what came (a drop that does not fit). */
        val REJECT = of(
            "reject",
            YtFeedback.REJECT,
            YtHapticStep(YtPrimitive.CLICK, 0.6f),
            YtHapticStep(YtPrimitive.CLICK, 0.6f, 60),
        )

        /** Something comes to rest, heavier with [weight] (0 to 1). */
        fun land(weight: Float): YtHapticTexture {
            val w = weight.coerceIn(0f, 1f)
            return of(
                "land",
                if (w >= 0.5f) YtFeedback.CONFIRM else YtFeedback.GESTURE_END,
                YtHapticStep(YtPrimitive.THUD, 0.3f + 0.6f * w),
                YtHapticStep(YtPrimitive.TICK, 0.15f + 0.3f * w, 50),
            )
        }
    }
}
