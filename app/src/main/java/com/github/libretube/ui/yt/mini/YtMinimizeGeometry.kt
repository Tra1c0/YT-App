package com.github.libretube.ui.yt.mini

/**
 * YT: whether the open and docked places a minimize drag would work with can carry it. Pure, so
 * the JVM tests can check it.
 *
 * The drag puts the transition where the grabbed point lies under the finger
 * ([YtUnderFinger.fit]). When the docked place it works with is not clearly below the open one
 * (a place remembered from another layout of the page, or measured while the page was not
 * really docked), no progress puts the point lower: every move down ends past the end, the
 * transition never leaves 0, and only the rubber band and the sideways follow move the picture,
 * a little down and left and right. Such places are not used: the cached one is dropped, and
 * without a sound one the classic drag takes the gesture.
 */
object YtMinimizeGeometry {
    /** The least the box's centre has to travel down between open and docked, in dp. */
    const val MIN_TRAVEL_DP = 48f

    /**
     * Whether a box moving from [start] (open) to [end] (docked), in page px, with the container
     * shifted by [curve], goes down by at least [minTravel] px, and both boxes have a size.
     */
    fun drivable(start: YtBox, end: YtBox, curve: YtContainerCurve, minTravel: Float): Boolean {
        if (start.width <= 0f || start.height <= 0f || end.width <= 0f || end.height <= 0f) return false
        val from = curve.at(0f) + (start.top + start.bottom) / 2f
        val to = curve.at(1f) + (end.top + end.bottom) / 2f
        if (to - from < minTravel) return false
        // the finger may hold any point of the box: its top and its foot must go down as well
        return curve.at(1f) + end.top - (curve.at(0f) + start.top) > 0f &&
            curve.at(1f) + end.bottom - (curve.at(0f) + start.bottom) > 0f
    }

    /**
     * Whether a place remembered for a page of [seenWidth] x [seenHeight] may be used on the page
     * as it is now ([width] x [height]): only for the same size, and only after the page has not
     * changed its configuration since ([sameConfiguration]).
     */
    fun seenUsable(seenWidth: Int, seenHeight: Int, width: Int, height: Int, sameConfiguration: Boolean): Boolean =
        sameConfiguration && seenWidth == width && seenHeight == height && width > 0 && height > 0
}
