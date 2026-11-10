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
     * px the box reaches into what the system covers at the window's sides (the three-button
     * navigation and a cutout, turned): 0 when it keeps out of both.
     */
    fun sideError(boxLeft: Int, boxRight: Int, windowWidth: Int, insetLeft: Int, insetRight: Int): Int =
        maxOf(0, insetLeft - boxLeft) + maxOf(0, boxRight - (windowWidth - insetRight))

    /** The side line of the emulator check (scripts/gestures/b.sh); its format is part of it. */
    fun sideLine(boxLeft: Int, boxRight: Int, windowWidth: Int, insetLeft: Int, insetRight: Int): String =
        "dock side box=$boxLeft,$boxRight window=$windowWidth insets=$insetLeft,$insetRight " +
            "err=${sideError(boxLeft, boxRight, windowWidth, insetLeft, insetRight)}"

    /**
     * Whether the offsets a drag, a landing or a drop reaction left on the mini player's views
     * may be cleared when a minimize or open completes: not while a drag or its release still
     * moves them (it puts them back itself) and not while the player is thrown away.
     */
    fun clearsOffsets(dragging: Boolean, closing: Boolean): Boolean = !dragging && !closing

    /**
     * Whether the page moved down by [shift] px is cut at the bar's top edge (YtMiniHost.clipToBar):
     * while it is docked and pushed below its place (dragged down, thrown away, rising back).
     * Docked means the page's own transition rests at its end ([pageProgress], not [peeking]) or
     * the bar still counts it as docked ([barProgress], YtDock.progress). The page alone must be
     * enough: a throw releases the bar at once (YtMiniPlayer.undock runs YtDock.progress down to 0
     * in 300 ms) while the page still falls; on the bar's value alone the cut went with the
     * animator's first step, and the falling player jumped in front of the bar. When that step
     * came depended on how late the frames after the release were (the first throw after a start).
     */
    fun clipsToBar(shift: Float, barProgress: Float, pageProgress: Float, peeking: Boolean, boxHeight: Int, pageWidth: Int): Boolean =
        shift > 0.5f && boxHeight > 0 && pageWidth > 0 &&
            (barProgress >= DOCKED || (!peeking && pageProgress >= DOCKED))

    /**
     * Whether the page is parked off its place on the bar with nobody to bring it back: it rests
     * docked ([docked], the transition at its end and not [peeking]) but is still moved by
     * [shift] px, while no gesture, throw or rise owns that offset ([owned]). Cut at the bar's
     * edge (clipsToBar), such a page shows the mini player half behind the bar and stays so
     * until the next drag. The host then moves it back onto its place.
     */
    fun parked(shift: Float, docked: Boolean, atEnd: Boolean, pageProgress: Float, peeking: Boolean, owned: Boolean): Boolean =
        abs(shift) > 0.5f && docked && atEnd && pageProgress >= DOCKED && !peeking && !owned

    /** How long a parked page waits before it is moved back (an owner the host missed ends first). */
    const val PARKED_MS = 300L

    /**
     * Whether a throw that started [elapsedMs] ago has run too long: its fall takes well under a
     * second at the throw's lowest speed, so a longer one lost its frames (or was stopped) and
     * the player is closed from where it is instead of staying half behind the bar.
     */
    fun throwOverdue(elapsedMs: Long): Boolean = elapsedMs >= THROW_LIMIT_MS

    const val THROW_LIMIT_MS = 1500L

    private const val DOCKED = 0.999f
}
