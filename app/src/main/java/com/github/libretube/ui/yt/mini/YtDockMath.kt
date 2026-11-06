package com.github.libretube.ui.yt.mini

import kotlin.math.abs

/**
 * YT: where the docked mini player belongs on the bar, apart from the views so it can be tested
 * on the JVM. The activity shifts the player container up by the bar's height and down again by
 * the design's dock offset (MainActivity's keyframes), and the end set puts the box at the
 * bottom of the page: the box's foot lands on the top of the bar's view plus that offset.
 */
internal object YtDockMath {
    /** px the emulator check allows between the box's foot and its place (rounding of dp). */
    const val SLACK = 3

    /** How far the box's foot is below its place, in px (negative: above it). */
    fun footError(boxBottom: Int, barTop: Int, dockPx: Int): Int = boxBottom - (barTop + dockPx)

    /** Whether the foot is at its place, within [SLACK]. */
    fun onBar(boxBottom: Int, barTop: Int, dockPx: Int): Boolean =
        abs(footError(boxBottom, barTop, dockPx)) <= SLACK

    /**
     * The line the emulator check (scripts/gestures/b.sh) reads: the box's foot, the bar's top,
     * the dock offset and the error, in window px. Its format is part of that check.
     */
    fun checkLine(boxBottom: Int, barTop: Int, dockPx: Int): String =
        "dock check box=$boxBottom bar=$barTop dock=$dockPx err=${footError(boxBottom, barTop, dockPx)}"

    /**
     * Whether the offsets a drag, a landing or a drop reaction left on the mini player's views
     * may be cleared when a minimize or open completes: not while a drag or its release still
     * moves them (it puts them back itself) and not while the player is thrown away.
     */
    fun clearsOffsets(dragging: Boolean, closing: Boolean): Boolean = !dragging && !closing
}
