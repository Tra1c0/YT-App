package com.github.libretube.ui.yt.player

/**
 * YT: which finger the player's gesture controller is following, and whether its long press
 * (the hold with the tiles) runs. Pure bookkeeping, so the rules can be tested on the JVM.
 *
 * Why it exists: a long press that never heard its UP or CANCEL stayed "in progress" across
 * gestures, and every later drag on the video moved the hold tiles. That happened when the
 * minimize drag turned the controls off (useController = false) before the parent's CANCEL
 * reached the player view, which then dropped the CANCEL: the GestureDetector's pending long
 * press fired later, with no finger down. The rules here make every path end it.
 */
class YtPlayerTouch {
    /** A finger of the current gesture is down and this view gets its events. */
    var fingerDown = false
        private set

    /** The long press of the current gesture fired and has not ended yet. */
    var longPress = false
        private set

    /** A new gesture starts. True: a long press of an earlier gesture never ended; end it (cancelled) now. */
    fun down(): Boolean {
        val stale = longPress
        longPress = false
        fingerDown = true
        return stale
    }

    /** The detector's long press fired. True only while the finger that started it is still down. */
    fun longPressFired(): Boolean {
        if (!fingerDown) return false
        longPress = true
        return true
    }

    /** A second finger: true if a running long press must end (cancelled); the finger stays down. */
    fun secondFinger(): Boolean {
        val was = longPress
        longPress = false
        return was
    }

    /** UP, CANCEL, or the gesture taken away: true if a running long press must end. */
    fun end(): Boolean {
        val was = longPress
        longPress = false
        fingerDown = false
        return was
    }

    /** Whether anything still has to be ended (see [end]). */
    val active: Boolean get() = fingerDown || longPress
}
