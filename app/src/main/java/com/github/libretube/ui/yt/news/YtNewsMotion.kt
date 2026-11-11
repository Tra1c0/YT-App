package com.github.libretube.ui.yt.news

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** A rectangle in px (window coordinates unless said otherwise); plain floats, for the JVM tests. */
data class YtNewsRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f
    val area: Float get() = max(0f, width) * max(0f, height)
    val isEmpty: Boolean get() = width <= 0f || height <= 0f

    fun contains(x: Float, y: Float): Boolean = x >= left && x < right && y >= top && y < bottom

    fun offset(dx: Float, dy: Float) = YtNewsRect(left + dx, top + dy, right + dx, bottom + dy)

    /** This rectangle shrunk by the given room on each side (a card's gap inside its bounds). */
    fun inset(l: Float, t: Float, r: Float, b: Float): YtNewsRect =
        YtNewsRect(left + l, top + t, max(left + l, right - r), max(top + t, bottom - b))

    /** The part of this rectangle inside [other] (empty when they do not meet). */
    fun intersect(other: YtNewsRect): YtNewsRect =
        YtNewsRect(max(left, other.left), max(top, other.top), min(right, other.right), min(bottom, other.bottom))
}

/**
 * YT: the motion of the News overlay as plain numbers (stage 2), tested on the JVM. The window
 * grows out of the card under the fingers ([window]), the pages recede as they leave the middle
 * ([page]), the thumbnail lags behind its card ([parallax]), a card dragged up or down tilts
 * ([tilt]), and the home behind sinks back as the window opens ([home]).
 */
object YtNewsMotion {
    /** Without a card under the fingers the window starts at this share of the screen (as stage 1). */
    const val CENTRE_START = 0.42f

    /** How far a pinch that is not yet committed lets the window grow (0..1 of the way). */
    const val PREVIEW_REACH = 0.4f

    /** A card counts as still on screen for the way back when at least this share of it shows. */
    const val VISIBLE_SHARE = 0.5f

    fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    fun lerp(a: YtNewsRect, b: YtNewsRect, t: Float): YtNewsRect = YtNewsRect(
        lerp(a.left, b.left, t),
        lerp(a.top, b.top, t),
        lerp(a.right, b.right, t),
        lerp(a.bottom, b.bottom, t),
    )

    /** 0 below [from], 1 above [to], smooth between. */
    fun smooth(from: Float, to: Float, x: Float): Float {
        if (to <= from) return if (x >= to) 1f else 0f
        val t = ((x - from) / (to - from)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /**
     * The card that a pinch at ([x], [y]) starts from, by index: of the cards whose visible part
     * holds the point, the smallest (a card inside a carousel card wins over the carousel); -1
     * for none (a gap, the header).
     */
    fun pick(visible: List<YtNewsRect>, x: Float, y: Float): Int {
        var best = -1
        var bestArea = Float.MAX_VALUE
        visible.forEachIndexed { i, r ->
            if (!r.isEmpty && r.contains(x, y) && r.area < bestArea) {
                best = i
                bestArea = r.area
            }
        }
        return best
    }

    /** Whether a card whose [visible] part (of its [full] rectangle) shows is still there to go back into. */
    fun stillThere(full: YtNewsRect, visible: YtNewsRect?): Boolean {
        if (visible == null || full.isEmpty || visible.isEmpty) return false
        return visible.area / full.area >= VISIBLE_SHARE
    }

    /** Where the window starts without a card: [full] shrunk to [CENTRE_START] about ([ox], [oy]). */
    fun centreStart(full: YtNewsRect, ox: Float, oy: Float): YtNewsRect {
        val x = ox.coerceIn(full.left, full.right)
        val y = oy.coerceIn(full.top, full.bottom)
        val s = CENTRE_START
        return YtNewsRect(x + (full.left - x) * s, y + (full.top - y) * s, x + (full.right - x) * s, y + (full.bottom - y) * s)
    }

    /**
     * The window's rectangle at [reveal] (0: the [start], 1: [full]), moved by the fingers'
     * travel ([followX], [followY]) less and less as it opens, so it is exactly [full] when open.
     * A reveal past 1 (a spring overshooting) is held at [full].
     */
    fun window(start: YtNewsRect, full: YtNewsRect, reveal: Float, followX: Float = 0f, followY: Float = 0f): YtNewsRect {
        val t = reveal.coerceIn(0f, 1f)
        val k = 1f - t
        return lerp(start, full, t).offset(followX * k, followY * k)
    }

    /** The corner radius of the window: the card's at the start, square when open. */
    fun corner(startRadius: Float, reveal: Float): Float = lerp(startRadius, 0f, smooth(0f, 1f, reveal))

    /**
     * How opaque the window (its ground and pages) is at [reveal]. With the card's own picture on
     * top ([picture]) it fades in under the picture as that fades out; without, as in stage 1.
     */
    fun windowAlpha(reveal: Float, picture: Boolean): Float =
        if (picture) smooth(0.02f, 0.28f, reveal) else (reveal * 1.8f).coerceIn(0f, 1f)

    /** How opaque the card's picture over the growing window is: the card itself at the start. */
    fun pictureAlpha(reveal: Float): Float = 1f - smooth(0.12f, 0.42f, reveal)

    /**
     * The window dragged down [drag] (0..1 of its height): it shrinks a little about its centre
     * and follows the finger by [dragPx].
     */
    fun dragged(rect: YtNewsRect, drag: Float, dragPx: Float): YtNewsRect {
        val s = 1f - 0.1f * drag.coerceIn(0f, 1f)
        val w = rect.width * s
        val h = rect.height * s
        val l = rect.centerX - w / 2f
        val t = rect.centerY - h / 2f + dragPx
        return YtNewsRect(l, t, l + w, t + h)
    }

    // ---------------------------------------------------------------------------------------------
    // Depth

    /** How a page [offset] pages from the middle (signed) recedes. */
    data class Page(val scale: Float, val alpha: Float, val elevation: Float)

    /**
     * A page [offset] pages from the middle: smaller, dimmer and lower ([elevation] 0..1 of the
     * card's shadow) as it leaves; with [depth] off (the motion switch, a weak phone) only the dim
     * of stage 1 stays.
     */
    fun page(offset: Float, depth: Boolean): Page {
        val off = abs(offset).coerceIn(0f, 1f)
        return if (depth) {
            Page(scale = lerp(1f, 0.88f, off), alpha = lerp(1f, 0.6f, off), elevation = 1f - off)
        } else {
            Page(scale = 1f, alpha = lerp(1f, 0.55f, off), elevation = 0f)
        }
    }

    /** How much slower the thumbnail moves than its card (share of the card's width per page). */
    const val PARALLAX = 0.06f

    /** The thumbnail's overscan, so its lag never shows an edge: 1 + 2·[PARALLAX]. */
    const val PARALLAX_SCALE = 1f + 2f * PARALLAX

    /** The thumbnail's shift inside its frame, in px, for a page [offset] pages from the middle and [width] px wide. */
    fun parallax(offset: Float, width: Float): Float = offset.coerceIn(-1f, 1f) * PARALLAX * width

    /** A card dragged [dy] px (negative: up) of a [range] px swipe: rotation about X, degrees, and scale. */
    data class Tilt(val rotationX: Float, val scale: Float)

    fun tilt(dy: Float, range: Float): Tilt {
        if (range <= 0f) return Tilt(0f, 1f)
        val k = (dy / range).coerceIn(-1.5f, 1.5f)
        // up: the top leans away and the card lifts a little; down: the bottom leans away
        val rotation = (-k * 6f).coerceIn(-7f, 7f)
        val scale = if (k < 0f) 1f + 0.03f * min(1f, -k) else 1f
        return Tilt(rotation, scale)
    }

    /** How the home behind the window sinks back at [reveal]. */
    data class Home(val scale: Float, val blur: Float, val scrim: Float)

    /**
     * The home behind at [reveal]: a little smaller, blurred up to [blurMax] px where it is cheap
     * ([blurMax] 0: dim only), and the scrim over everything. With [depth] off only the scrim.
     */
    fun home(reveal: Float, depth: Boolean, blurMax: Float): Home {
        val t = reveal.coerceIn(0f, 1f)
        if (!depth) return Home(1f, 0f, 0.6f * t)
        // whole px only, so the blur is not rebuilt for every tiny step
        val blur = (blurMax * t).toInt().toFloat()
        return Home(scale = 1f - 0.06f * t, blur = blur, scrim = 0.6f * t)
    }
}
