package com.github.libretube.ui.yt.mini

import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/*
 * YT: the Kiesel mini player ("Schwarzer Kiesel") in numbers: the tall pebble its long press
 * squeezes and springs into, the peach capsule of the next video rolling in under the picture,
 * the bounces of the landing and of a drop, the cream counter capsule. Pure (px and ms, no
 * Android), so the View constraints, the composables and the JVM tests read the same numbers.
 */

/**
 * The grown pebble of the preview, in px of the grown box, left to right, from its top: the
 * picture across the pebble as a wide stadium, the byline under it (title and channel, the
 * lavender disc at its end), the peach capsule of the next video at the pebble's end under it.
 */
data class YtMiniKieselPeek(
    /** What the host reads (picture, title, pill, next). */
    val geometry: YtMiniPeekGeometry,
    /** Title and channel beside the disc. */
    val byline: YtMiniBox,
    /** The line where the pebble's foot curves into the bar. */
    val joint: Float,
    /** The stadium of the picture: half its height. */
    val pictureRadius: Float,
    /** The pebble's top corners, concentric with the picture's. */
    val bodyRadius: Float,
)

/**
 * A run of bounces on the bar: at each impact (ms) the pebble squashes, between impacts it
 * stretches up as it hops, smaller each time; afterwards it rests. [value] is the stretch (> 0)
 * or squash (< 0) as a share of its height, [dip] how far the bar gives at the impacts (0 to 1).
 */
class YtKieselBounce(
    /** The impacts, in ms from the start, rising; the first is 0. */
    private val impacts: FloatArray,
    /** How much each impact squashes. */
    private val squash: FloatArray,
    /** How much the hop after each impact (but the last) stretches. */
    private val hop: FloatArray,
    /** How far the bar gives at each impact (0 to 1). */
    private val give: FloatArray,
) {
    /** The last impact's squash and the bar's give, then everything rests. */
    val duration: Float get() = impacts.last() + max(LAST_SQUASH, DIP)

    fun value(ms: Float): Float {
        if (ms <= 0f || ms >= duration) return 0f
        var i = impacts.size - 1
        while (i > 0 && ms < impacts[i]) i--
        val start = impacts[i]
        val end = if (i + 1 < impacts.size) impacts[i + 1] else duration
        val length = end - start
        // the squash takes the first part of each period, the hop the rest (none after the last)
        val squashLength = if (i + 1 < impacts.size) min(length * SQUASH_SHARE, LAST_SQUASH) else LAST_SQUASH
        val u = ms - start
        return if (u < squashLength) {
            -squash[i] * sin(PI.toFloat() * u / squashLength)
        } else {
            val h = if (i < hop.size) hop[i] else 0f
            h * sin(PI.toFloat() * (u - squashLength) / (length - squashLength))
        }
    }

    fun dip(ms: Float): Float {
        var sum = 0f
        for (i in impacts.indices) {
            val u = ms - impacts[i]
            if (u > 0f && u < DIP) sum += give[i] * sin(PI.toFloat() * u / DIP)
        }
        return sum.coerceIn(0f, 1f)
    }

    companion object {
        const val SQUASH_SHARE = 0.35f
        const val LAST_SQUASH = 70f
        const val DIP = 90f
    }
}

object YtMiniKieselLayout {
    /** How much the preview enlarges the picture's height. */
    const val GROWTH = 1.9f

    /** From the grown pebble's top to the picture, and the picture's inset from its sides. */
    const val TOP = 10
    const val PAD = 10

    /** The byline under the picture: its gap, its height, the disc's hit area in it. */
    const val BYLINE_GAP = 8
    const val BYLINE = 44
    const val PILL = 44

    /** The disc: docked and in the byline (its cream ring 4 dp outside it). */
    const val DISC = 34
    const val PEEK_DISC = 38

    /** The capsule of the next video: gap to the byline, height, share of the pebble's width. */
    const val NEXT_GAP = 6
    const val NEXT = 46
    const val NEXT_SHARE = 0.6f

    /** The least gap between the docked picture's end and the capsule's start, in dp. */
    const val NEXT_CLEAR = 14

    /** The next video's round thumbnail in its capsule, and its inset. */
    const val NEXT_THUMB = 36
    const val NEXT_INSET = 5

    /** From the lowest part to the joint with the bar. */
    const val FOOT = 10

    /** How far the docked pebble's top domes up in its middle. */
    const val DOME = 2.5f

    /**
     * The squeeze before the pebble springs up: how far its top sinks (less than the picture's
     * own margin to it while it grows, so the picture never pokes out), how far it widens.
     */
    const val SQUEEZE_DROP = 5f
    const val SQUEEZE_WIDEN = 6f

    /** How far the spring's swing past full size narrows the tall pebble. */
    const val STRETCH_NARROW = 7f

    /**
     * The grown pebble of [spec] in a box [boxWidth] px wide ([density] px per dp), the capsule
     * of the next video only with [hasNext]. The finger that held the docked picture (at the
     * pebble's start, near its foot) lies on neither the capsule, which keeps to the end, nor on
     * the disc at the end of the byline.
     */
    fun peek(spec: YtMiniSpec, boxWidth: Float, density: Float, hasNext: Boolean): YtMiniKieselPeek {
        val d = density
        val left = (spec.body + PAD) * d
        val right = max(boxWidth - (spec.body + PAD) * d, left + 1f)
        val inner = right - left
        // a wide stadium across the pebble, never taller than 16:9 on narrow screens
        val height = min(spec.thumbHeight * GROWTH * d, inner * 9f / 16f)
        val player = YtMiniBox(left, TOP * d, right, TOP * d + height)

        val bylineTop = player.bottom + BYLINE_GAP * d
        val bylineBottom = bylineTop + BYLINE * d
        val pillTop = bylineTop + (BYLINE - PILL) / 2f * d
        val pill = YtMiniBox(right - PILL * d, pillTop, right, pillTop + PILL * d)
        val textLeft = left + 4f * d
        val byline = YtMiniBox(textLeft, bylineTop, max(pill.left - 8f * d, textLeft), bylineBottom)

        val next = if (hasNext) {
            val top = bylineBottom + NEXT_GAP * d
            // never under the docked picture, where the finger rests (narrow screens)
            val clear = (spec.body + spec.thumbStart + spec.thumbWidth + NEXT_CLEAR) * d
            YtMiniBox(min(max(right - inner * NEXT_SHARE, clear), right - 1f), top, right, top + NEXT * d)
        } else {
            null
        }
        val joint = (next?.bottom ?: bylineBottom) + FOOT * d
        val rest = (spec.height + spec.overlap) * d
        val box = max(rest, joint + spec.overlap * d)
        val pictureRadius = height / 2f
        return YtMiniKieselPeek(
            geometry = YtMiniPeekGeometry(box, player, byline, pill, next),
            byline = byline,
            joint = joint,
            pictureRadius = pictureRadius,
            bodyRadius = pictureRadius + PAD * d,
        )
    }

    /** 0 below [from], 1 above [to], smooth between. */
    fun smooth(value: Float, from: Float, to: Float): Float {
        if (to <= from) return if (value >= to) 1f else 0f
        val t = ((value - from) / (to - from)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /** An ease out that swings [overshoot] past 1 and settles (0 at 0, 1 at 1). */
    fun backOut(t: Float, overshoot: Float = 1.7f): Float {
        val u = t.coerceIn(0f, 1f) - 1f
        return 1f + (overshoot + 1f) * u * u * u + overshoot * u * u
    }

    /**
     * The squeeze at preview [t] (0 to 1): the pebble flattens before it springs up, and again
     * as it sinks back onto the bar. 0 at rest and from a third of the way on.
     */
    fun squeeze(t: Float): Float = if (t <= 0f || t >= 0.3f) 0f else sin(PI.toFloat() * t / 0.3f)

    /** How far the spring swings the preview past its full size, 0 to 1 (the spring stops at 1.15). */
    fun stretch(peek: Float): Float = ((peek - 1f) / 0.15f).coerceIn(0f, 1f)

    /**
     * How far the falling pebble has merged with the bar at minimize [progress]: a free stone
     * until just before it lands, then its foot flows into the bar's curves.
     */
    fun merge(progress: Float): Float = smooth(progress, 0.78f, 1f)

    /** The landing: an impact with the bar, then three hops, each lower (the haptic's ticks). */
    val DOCK = YtKieselBounce(
        impacts = floatArrayOf(0f, 140f, 240f, 320f),
        squash = floatArrayOf(0.07f, 0.045f, 0.025f, 0.012f),
        hop = floatArrayOf(0.055f, 0.028f, 0.012f),
        give = floatArrayOf(1f, 0.55f, 0.3f, 0.15f),
    )

    /** A drop: the counter capsule lands on the pebble, which hops once (THUD, then TICK). */
    val ACCEPT = YtKieselBounce(
        impacts = floatArrayOf(0f, 230f),
        squash = floatArrayOf(0.06f, 0.02f),
        hop = floatArrayOf(0.05f),
        give = floatArrayOf(0.6f, 0.2f),
    )

    /**
     * The capsule of the next video rolls in from the pebble's end: [t] the preview (0 to 1),
     * returns how far it still has to roll, 1 (out of sight past the end) to 0 (in place), with
     * a little swing past its place.
     */
    fun roll(t: Float): Float {
        val s = ((t - 0.4f) / 0.6f).coerceIn(0f, 1f)
        return 1f - backOut(s, 1.2f)
    }

    /** The thumbnail's turn in degrees when the capsule has [distance] px still to roll on a wheel of [radius] px. */
    fun turn(distance: Float, radius: Float): Float =
        if (radius <= 0f) 0f else (distance / radius) * (180f / PI.toFloat())

    /**
     * The timeline of an accepted drop ([t] 0 to 1 over [DROP_MS]): the counter capsule pops in
     * with "+1" ([counter]), stretches into the label ([open]) and holds it, closes again and
     * sinks into the pebble ([sink]).
     */
    const val DROP_MS = 2000

    fun counter(t: Float): Float = when {
        t <= 0f || t >= 1f -> 0f
        t < 0.12f -> backOut(t / 0.12f, 2.4f)
        else -> 1f
    }

    fun open(t: Float): Float = when {
        t <= 0.16f || t >= 1f -> 0f
        t < 0.84f -> backOut(((t - 0.16f) / 0.14f).coerceAtMost(1f), 1.1f)
        else -> 1f - smooth(t, 0.84f, 0.9f)
    }

    fun sink(t: Float): Float = if (t <= 0.9f) 0f else smooth(t, 0.9f, 1f)

    /** A moment of the drop's timeline where everything stands still (no motion: shown as is). */
    const val DROP_STILL = 0.5f

    /** The counter capsule ("+1") on the pebble's top end corner: its height and width, in dp. */
    const val COUNTER_HEIGHT = 22
    const val COUNTER_WIDTH = 36

    /** How far its centre sits in from the pebble's end. */
    const val COUNTER_IN = 30
}
