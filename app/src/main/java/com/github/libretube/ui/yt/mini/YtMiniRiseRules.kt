package com.github.libretube.ui.yt.mini

/**
 * YT: the pure rules of the restored mini player's way back onto the bar (a long press on
 * "Abos"). It is the throw-away reversed: thrown, the docked player falls down behind the bar's
 * top edge (the page is cut at the line its foot rests on); brought back, it rises from behind
 * that edge to its place, with the duration and curve the design minimizes with, and lands there
 * with the design's own landing. No Android types, so it is unit tested.
 */
object YtMiniRiseRules {
    /** dp below the hidden line the player starts from (its shadow and joint, as the throw's 8 dp). */
    const val MARGIN_DP = 8f

    /** Whether the restored page rises (motion on) or simply appears docked (motion off). */
    fun rises(restored: Boolean, motionEnabled: Boolean): Boolean = restored && motionEnabled

    /**
     * How far below its place the player starts, in px: the box's own height (below that it is
     * all behind the bar's edge) and a margin; [boxHeightPx] ≤ 0 (not laid out) gives 0, no rise.
     */
    fun from(boxHeightPx: Int, density: Float): Float =
        if (boxHeightPx <= 0) 0f else boxHeightPx + MARGIN_DP * density

    /** How far up the player is, 0 (hidden, at [from]) to 1 (in place), for an [offset] in px. */
    fun share(offset: Float, from: Float): Float =
        if (from <= 0f) 1f else (1f - offset / from).coerceIn(0f, 1f)

    /** The bar's slab behind the player (Glas) grows with the rise, as it does with a minimize. */
    fun slab(offset: Float, from: Float, extentPx: Float): Float = extentPx * share(offset, from)

    /** The curves MotionScene names by its INTERPOLATE_* numbers. */
    enum class Curve { EASE_IN_OUT, EASE_IN, EASE_OUT, LINEAR, BOUNCE, OVERSHOOT, ANTICIPATE }

    /**
     * The curve of a design's minimize ([YtMiniMotion.interpolator], MotionScene's numbers 0 to 6);
     * anything else (a spline or a reference) eases out, as the default motion does.
     */
    fun curve(interpolator: Int): Curve = when (interpolator) {
        0 -> Curve.EASE_IN_OUT
        1 -> Curve.EASE_IN
        2 -> Curve.EASE_OUT
        3 -> Curve.LINEAR
        4 -> Curve.BOUNCE
        5 -> Curve.OVERSHOOT
        6 -> Curve.ANTICIPATE
        else -> Curve.EASE_OUT
    }
}
