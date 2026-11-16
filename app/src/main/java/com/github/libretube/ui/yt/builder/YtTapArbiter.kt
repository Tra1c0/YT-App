package com.github.libretube.ui.yt.builder

/**
 * YT: tap or double tap for the animator's touch triggers (pure, times in ms). A component that
 * has a double-tap trigger ([waitForDouble]) holds a finished tap back for the double-tap window:
 * a second tap near it in that window is a DOUBLE_TAP alone (no TAP for either tap), otherwise the
 * held TAP is let go when the window ends, or at once when the next touch cannot be a second tap.
 * Without a double-tap trigger every tap is a TAP at once, as before.
 *
 * The window runs from the first tap's lift to the second touch going down (as Android's own
 * gesture detector counts it). Each call returns the trigger it decides, or null.
 */
class YtTapArbiter(
    var waitForDouble: Boolean,
    private val doubleMs: Long,
    /** The touch slop in px; a second tap counts within four of it from the first. */
    private val slop: Float,
) {
    private var pendingAt = -1L
    private var pendingX = 0f
    private var pendingY = 0f

    /** The touch going on now began as the second tap of a pending one. */
    private var second = false

    val pending: Boolean get() = pendingAt >= 0L

    /** A touch went down at [t]: a held tap it cannot complete is let go. */
    fun down(t: Long, x: Float, y: Float): YtTriggerKind? {
        if (!pending) return null
        val dx = x - pendingX
        val dy = y - pendingY
        val near = dx * dx + dy * dy < (4f * slop) * (4f * slop)
        if (waitForDouble && t - pendingAt <= doubleMs && near) {
            second = true
            return null
        }
        return release()
    }

    /** The touch ended as a tap (no move past the slop, no long press) lifted at [t]. */
    fun tap(t: Long, x: Float, y: Float): YtTriggerKind? {
        if (second) {
            clear()
            return YtTriggerKind.DOUBLE_TAP
        }
        if (!waitForDouble) {
            clear()
            return YtTriggerKind.TAP
        }
        pendingAt = t
        pendingX = x
        pendingY = y
        return null
    }

    /** The touch ended as something else (moved, long press, cancelled): a held first tap is let go. */
    fun other(): YtTriggerKind? = if (pending) release() else null

    /** Time is [t] and no touch is down: a held tap whose window is over is let go. */
    fun expire(t: Long): YtTriggerKind? {
        if (!pending || second) return null
        return if (!waitForDouble || t - pendingAt > doubleMs) release() else null
    }

    /** ms from [t] until a held tap is decided, or null when none waits. */
    fun waitMs(t: Long): Long? = if (!pending || second) null else (pendingAt + doubleMs - t + 1L).coerceAtLeast(0L)

    fun reset() = clear()

    private fun release(): YtTriggerKind {
        clear()
        return YtTriggerKind.TAP
    }

    private fun clear() {
        pendingAt = -1L
        second = false
    }
}
