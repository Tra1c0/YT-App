package com.github.libretube.ui.yt.mini

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/*
 * YT (round 29): the seamless minimize. While the player page is dragged (or animated) down, the
 * moving window takes on the width, the corners and the colour of the navigation bar and flows
 * into it, the page below the video fades out softly instead of moving as a hard-edged slab, and
 * nothing cuts the page at the bar's top edge while it is minimized (the cut stays for the throw
 * away and the rise). A design opts in with YtMiniDesign.merge; the decisions are pure (px, ARGB,
 * no Android), so the host, the design's composables and the JVM tests read the same numbers.
 */

/**
 * What a design's window merges into, in dp of the bar (YtMiniDesign.density) and ARGB colours.
 * The window is the player's body behind the picture, as wide as the player's box less [bodyInset]
 * on each side once docked.
 */
data class YtMiniMerge(
    /** The corner radius of the bar's surface: the window's corners on its way down. */
    val barRadius: Float,
    /** Docked: the body's inset from the box's edges (a bulge narrower than the bar; 0 for a stack). */
    val bodyInset: Float,
    /** Docked: the body's top corner radius. */
    val bodyRadius: Float,
    /** Docked: the concave curve from the bar up into the body (0: the foot is square). */
    val fillet: Float,
    /** The bar's surface colour, which the window takes on. */
    val barColor: Int,
    /** The watch page's background, the window's colour where it starts. */
    val pageColor: Int,
)

/**
 * The window at one moment, in px of the box (main_container): its sides [inset] from the box's
 * edges, its top corners [top], its foot either rounded by [foot] (convex, the bar's own corner)
 * or flaring out into the bar by [fillet] (concave, the bulge); never both. [tint] is how far its
 * colour has gone from the page's to the bar's (0 to 1).
 */
data class YtMiniWindow(
    val inset: Float,
    val top: Float,
    val foot: Float,
    val fillet: Float,
    val tint: Float,
) {
    companion object {
        /** The page's own look: the full box, square, in the page's colour. */
        val PAGE = YtMiniWindow(0f, 0f, 0f, 0f, 0f)
    }
}

object YtMiniMergeMath {
    /** By this progress the window is as round and as coloured as the bar. */
    const val APPROACH_TO = 0.7f

    /** From this progress the window narrows into its docked body (the bulge grows out of the bar). */
    const val NARROW_FROM = 0.8f

    /** The page below the video fades out between these two progresses. */
    const val FADE_FROM = 0.04f
    const val FADE_TO = 0.45f

    /** Without motion the steps happen at this progress (and the docked body at 1). */
    const val SNAP_AT = 0.5f

    fun smoothstep(from: Float, to: Float, x: Float): Float {
        if (to <= from) return if (x >= to) 1f else 0f
        val t = ((x - from) / (to - from)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    /** How far the window has taken on the bar's corners and colour at [progress]. */
    fun approach(progress: Float, motion: Boolean): Float =
        if (motion) smoothstep(0f, APPROACH_TO, progress) else if (progress >= SNAP_AT) 1f else 0f

    /** How far the window has narrowed into the docked body at [progress]. */
    fun narrow(progress: Float, motion: Boolean): Float =
        if (motion) smoothstep(NARROW_FROM, 1f, progress) else if (progress >= 1f) 1f else 0f

    /**
     * The window of [merge] at minimize [progress] (0 the page, 1 docked) in a box [width] ×
     * [height] px, [density] px per dp. On the way down it rounds its corners to the bar's and
     * takes the bar's colour while it is as wide as the box (whose width runs to the bar's);
     * at the end its sides draw in to the docked body, the convex foot turning into the fillet.
     * Without [motion] it changes in steps: the page's look, the bar's from [SNAP_AT], the body docked.
     */
    fun window(merge: YtMiniMerge, progress: Float, width: Float, height: Float, density: Float, motion: Boolean): YtMiniWindow {
        val p = progress.coerceIn(0f, 1f)
        if (width <= 0f || height <= 0f) return YtMiniWindow.PAGE
        val a = approach(p, motion)
        val n = narrow(p, motion)
        val bar = merge.barRadius * density
        val inset = (merge.bodyInset * density * n).coerceIn(0f, max(0f, width / 2f - 1f))
        val bodyWidth = width - 2f * inset
        // the foot: the bar's convex corner while it approaches, through square into the fillet
        // as it narrows (a signed radius: below 0 convex, above 0 concave)
        val signed = lerp(-bar * a, merge.fillet * density, n)
        var foot = if (signed < 0f) -signed else 0f
        var fillet = if (signed > 0f) signed else 0f
        var top = lerp(bar * a, merge.bodyRadius * density, n)
        // radii that fit the body: no corner wider than half of it, top and foot not overlapping
        val half = bodyWidth / 2f
        top = top.coerceIn(0f, max(0f, min(half, height)))
        foot = foot.coerceIn(0f, max(0f, min(half, height - top)))
        fillet = fillet.coerceIn(0f, max(0f, min(inset, height - top)))
        return YtMiniWindow(inset, top, foot, fillet, a)
    }

    /** The window's colour: from the page's to the bar's by [tint]. */
    fun color(merge: YtMiniMerge, tint: Float): Int = lerpArgb(merge.pageColor, merge.barColor, tint.coerceIn(0f, 1f))

    fun lerpArgb(from: Int, to: Int, t: Float): Int {
        fun channel(shift: Int): Int {
            val a = (from ushr shift) and 0xFF
            val b = (to ushr shift) and 0xFF
            return (a + (b - a) * t + 0.5f).toInt().coerceIn(0, 255) shl shift
        }
        return channel(24) or channel(16) or channel(8) or channel(0)
    }

    /**
     * The alpha of the page below the video at [progress]: it fades out early and softly as the
     * window leaves it (no slab with a hard edge moving down), and back in as the window rises.
     * Without [motion] it is there or gone, switching at [SNAP_AT].
     */
    fun pageAlpha(progress: Float, motion: Boolean): Float =
        if (motion) 1f - smoothstep(FADE_FROM, FADE_TO, progress) else if (progress >= SNAP_AT) 0f else 1f

    /** The window (the body behind the picture) is opaque as soon as the page moves: it is the page's card. */
    fun underAlpha(progress: Float): Float = if (progress > 0f) 1f else 0f

    /**
     * Whether the cut at the bar's edge (YtMiniHost.clipToBar, when YtDockMath.clipsToBar says so)
     * may run: a merging design ([merging]) is never cut while a drag that started on the open
     * page ([fromPage]) or its release moves it; it presses into the bar and springs back instead
     * of hitting an invisible wall. The throw away and the rise keep their cut.
     */
    fun clipAllowed(merging: Boolean, fromPage: Boolean): Boolean = !merging || !fromPage

    /**
     * The corner the picture (a SurfaceView, which only an outline can clip) needs so that it
     * stays inside [window]: a picture corner within a rounder window corner's reach is rounded
     * just enough that its arc lies inside the window's; one beside a straight edge needs nothing.
     * The picture lies [left], [top], [right], [bottom] px inside the box's edges, which is
     * [width] × [height]; [base] is the corner it has anyway. Never more than half its shorter side.
     */
    fun pictureCorner(base: Float, window: YtMiniWindow, width: Float, height: Float, left: Float, top: Float, right: Float, bottom: Float): Float {
        val pictureWidth = width - left - right
        val pictureHeight = height - top - bottom
        if (pictureWidth <= 0f || pictureHeight <= 0f) return max(base, 0f)
        val fromLeft = max(left - window.inset, 0f)
        val fromRight = max(right - window.inset, 0f)
        val fromTop = max(top, 0f)
        val fromFoot = max(bottom, 0f)
        val need = max(
            max(corner(window.top, fromLeft, fromTop), corner(window.top, fromRight, fromTop)),
            max(corner(window.foot, fromLeft, fromFoot), corner(window.foot, fromRight, fromFoot)),
        )
        return max(base, need).coerceIn(0f, min(pictureWidth, pictureHeight) / 2f)
    }

    /**
     * The least corner a picture corner [dx], [dy] px inside a window corner of [radius] needs:
     * its quarter circle (centre dx + r, dy + r) must lie inside the window's (centre radius,
     * radius), i.e. with u = radius − r: (u − dx)² + (u − dy)² ≤ u², so u ≤ dx + dy + √(2·dx·dy).
     * Its straight edges then run inside the window too. None beside a straight edge.
     */
    private fun corner(radius: Float, dx: Float, dy: Float): Float =
        max(0f, radius - dx - dy - sqrt(2f * dx * dy))
}
