package com.github.libretube.ui.yt.player

import kotlin.math.floor

/**
 * YT: the rules of sharing onto the timeline: the share action is held and dragged onto the time
 * bar, where a pin shows the second the link will start at. Pure, so it is tested on the JVM.
 * Distances are px in the time bar's coordinates.
 */
object YtSharePlan {
    /**
     * Whether the finger at ([x], [y]) is over the share zone: across the bar (and [sidePx] beyond
     * its ends), from [topY] (the top of the picture) down to [belowPx] under the bar's centre
     * [barCenterY]. Above the bar moving up refines; letting go outside cancels.
     */
    fun inZone(
        x: Float,
        y: Float,
        barWidth: Float,
        barCenterY: Float,
        topY: Float,
        sidePx: Float,
        belowPx: Float,
    ): Boolean = x >= -sidePx && x <= barWidth + sidePx && y >= topY && y <= barCenterY + belowPx

    /**
     * The whole second the link starts at for a pin at [positionMs] of a video of [durationMs]:
     * truncated (12:03.9 is still 12:03), never past the last second the video has.
     */
    fun seconds(positionMs: Double, durationMs: Long): Long {
        val last = if (durationMs > 0L) (durationMs - 1L) / 1000L else 0L
        val s = if (positionMs.isFinite()) floor(positionMs.coerceAtLeast(0.0) / 1000.0).toLong() else 0L
        return s.coerceIn(0L, last)
    }

    /**
     * The magnifier's level lines for sharing: the same ½, ¼ and ⅛, but no frames (the link
     * holds whole seconds), so the frames' line is out of reach.
     */
    fun levelLines(lines: FloatArray): FloatArray =
        FloatArray(lines.size) { if (it == lines.size - 1) Float.POSITIVE_INFINITY else lines[it] }
}

/**
 * YT: the finger's speed over its last moves, for the spring that carries a release on (px per
 * second). Pure; the samples come with their own clock.
 */
class YtFlingTracker(private val windowMs: Long = 100L) {
    private val times = LongArray(SIZE)
    private val xs = FloatArray(SIZE)
    private val ys = FloatArray(SIZE)
    private var count = 0
    private var head = 0

    fun reset() {
        count = 0
        head = 0
    }

    fun add(timeMs: Long, x: Float, y: Float) {
        times[head] = timeMs
        xs[head] = x
        ys[head] = y
        head = (head + 1) % SIZE
        if (count < SIZE) count++
    }

    /** The speed as (x, y) in px per second over the last [windowMs]; zero with too few samples. */
    fun velocity(): FloatArray {
        if (count < 2) return floatArrayOf(0f, 0f)
        val last = (head - 1 + SIZE) % SIZE
        var first = last
        // back to the oldest sample inside the window
        for (i in 1 until count) {
            val index = (last - i + SIZE) % SIZE
            if (times[last] - times[index] > windowMs) break
            first = index
        }
        val dt = (times[last] - times[first]) / 1000f
        if (first == last || dt <= 0f) return floatArrayOf(0f, 0f)
        return floatArrayOf((xs[last] - xs[first]) / dt, (ys[last] - ys[first]) / dt)
    }

    private companion object {
        const val SIZE = 16
    }
}
