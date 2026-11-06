package com.github.libretube.ui.yt.mini

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/*
 * YT: the Clay mini player ("Knetkugel") in numbers: the dough ball the long press stretches
 * upward (a head around the grown picture on a neck out of the docked body, the next video as a
 * smaller ball squeezed out beside it), and the curves of its motion (the waist of the neck, the
 * jelly of the squeeze, the dent a dragged video presses into the dough and the ripple of a
 * drop). Pure (px, no Android), so the View constraints, the composables and the JVM tests read
 * the same numbers.
 */

/**
 * The stretched dough of the preview, in px of the grown box, left to right, from its top. The
 * box's foot stays on the bar: [base] is the docked body at the foot (as tall as the docked box),
 * [head] the ball around the grown picture above it, joined by a neck; [next] the small ball of
 * the next video beside the head, on its own neck.
 */
data class YtMiniClayPeek(
    /** What the host and the shared parts read (picture, title, pill, next). */
    val geometry: YtMiniPeekGeometry,
    val head: YtMiniBox,
    val base: YtMiniBox,
    /** Inside the next ball: the cropped thumbnail and the two lines under it. */
    val nextThumb: YtMiniBox?,
    val nextText: YtMiniBox?,
)

object YtMiniClayLayout {
    /** How much the preview enlarges the picture ("leicht vergrößert"). */
    const val GROWTH = 1.7f

    /** The dough around the grown picture, the neck's length and the room above the head. */
    const val HEAD_PAD = 8
    const val NECK = 14
    const val TOP = 6

    /** The least inset of the head and the next ball from the box's sides. */
    const val PAD = 8

    /** The next ball: its gap to the head, its least width, how much lower its top sits. */
    const val NEXT_GAP = 10
    const val NEXT_MIN = 96
    const val NEXT_DROP = 10
    const val NEXT_INSET = 8

    /** Under the next thumbnail: the label and one line of title. */
    const val NEXT_LINES = 32

    /** The play pill in the body, at its end, and the title before it. */
    const val PILL = 40
    const val PILL_WIDTH = 136
    const val PILL_END = 12
    const val TEXT_START = 16
    const val TEXT_GAP = 10

    /** The mauve blob behind play, in dp. */
    const val BLOB = 42

    /** Corners: the body's top and foot, the head's, the next ball's. */
    const val BODY_TOP = 30
    const val BODY_FOOT = 24
    const val HEAD_CORNER = 28
    const val NEXT_CORNER = 22

    /** The dent a dragged video presses into the body's top edge: its depth and width, in dp. */
    const val DENT = 10
    const val DENT_WIDTH = 86

    /**
     * The preview of [spec] in a box [boxWidth] px wide ([density] px per dp), the next ball only
     * with [hasNext]. The finger holds the docked picture at the body's start: there, in the
     * grown body, lies nothing that acts (the title); the pill is at the body's end and the next
     * ball above the body, beside the head.
     */
    fun peek(spec: YtMiniSpec, boxWidth: Float, density: Float, hasNext: Boolean): YtMiniClayPeek {
        val d = density
        val pad = PAD * d
        val headPad = HEAD_PAD * d
        val inner = max(boxWidth - 2f * pad, 1f)
        // the head keeps the next ball its least width beside it
        val headMost = inner - if (hasNext) (NEXT_GAP + NEXT_MIN) * d else 0f
        val pictureWidth = min(spec.thumbWidth * GROWTH * d, headMost - 2f * headPad).coerceAtLeast(spec.thumbWidth * d)
        val pictureHeight = pictureWidth * spec.thumbHeight / spec.thumbWidth.coerceAtLeast(1)

        val head = YtMiniBox(pad, TOP * d, pad + pictureWidth + 2f * headPad, TOP * d + pictureHeight + 2f * headPad)
        val player = YtMiniBox(head.left + headPad, head.top + headPad, head.right - headPad, head.bottom - headPad)
        val restBox = (spec.height + spec.overlap) * d
        val baseTop = head.bottom + NECK * d
        val base = YtMiniBox(0f, baseTop, boxWidth, baseTop + restBox)
        val content = spec.height * d

        val pillHeight = PILL * d
        val pillRight = boxWidth - PILL_END * d
        val pillWidth = min(PILL_WIDTH * d, max(pillRight - (TEXT_START + 80) * d, pillHeight))
        val pillTop = baseTop + (content - pillHeight) / 2f
        val pill = YtMiniBox(pillRight - pillWidth, pillTop, pillRight, pillTop + pillHeight)
        val textLeft = TEXT_START * d
        val title = YtMiniBox(textLeft, baseTop, max(pill.left - TEXT_GAP * d, textLeft), baseTop + content)

        var next: YtMiniBox? = null
        var thumb: YtMiniBox? = null
        var text: YtMiniBox? = null
        if (hasNext) {
            val left = head.right + NEXT_GAP * d
            val right = max(boxWidth - pad, left + 1f)
            val ball = YtMiniBox(left, head.top + NEXT_DROP * d, right, head.bottom)
            val i = NEXT_INSET * d
            val thumbBottom = max(ball.bottom - i - NEXT_LINES * d, ball.top + i + 1f)
            thumb = YtMiniBox(ball.left + i, ball.top + i, ball.right - i, thumbBottom)
            text = YtMiniBox(ball.left + i + 2f * d, thumbBottom + 2f * d, ball.right - i, ball.bottom - i)
            next = ball
        }
        val geometry = YtMiniPeekGeometry(base.bottom, player, title, pill, next)
        return YtMiniClayPeek(geometry, head, base, thumb, text)
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
     * The neck's waist as a share of the head's width at preview [t] (0 to 1): stretched thin
     * while the dough is pulled up, then it fills out again once the head has risen.
     */
    fun waist(t: Float): Float {
        val s = t.coerceIn(0f, 1f)
        return 0.66f - 0.36f * sin(PI.toFloat() * s)
    }

    /**
     * How far the next ball has squeezed out of the body at preview [t]: nothing while the head
     * rises, then it pops out with a jelly overshoot (a little over 1 for a moment).
     */
    fun squeeze(t: Float): Float = if (t <= 0.42f) 0f else backOut((t - 0.42f) / 0.58f, 2.0f)

    /** How much a spring swinging past full size ([peek] over 1) puffs the head up, 0 to 1. */
    fun puff(peek: Float): Float = ((peek - 1f) / 0.15f).coerceIn(0f, 1f)

    /** A smooth dip of depth 1 at [dx] = 0, falling to 0 at half of [width] on either side. */
    fun dent(dx: Float, width: Float): Float {
        if (width <= 0f) return 0f
        val u = abs(dx) / (width / 2f)
        if (u >= 1f) return 0f
        return 0.5f * (1f + cos(PI.toFloat() * u))
    }

    /**
     * The ripple of a taken drop along the body's top edge, [dx] from where it was taken, at
     * [t] (0 to 1) of the ripple, as a share of its amplitude (positive: into the dough). It runs
     * outward over [reach] px in waves of [wavelength] px and dies away.
     */
    fun ripple(dx: Float, t: Float, reach: Float, wavelength: Float): Float {
        if (t <= 0f || t >= 1f || reach <= 0f || wavelength <= 0f) return 0f
        val front = reach * t
        val distance = abs(dx)
        if (distance > front) return 0f
        val fade = (1f - t) * (1f - t)
        // the waves just behind the front are the strongest, those left behind calm down
        val behind = exp(-2.2f * (front - distance) / reach)
        return fade * behind * sin(2f * PI.toFloat() * (front - distance) / wavelength)
    }

    /**
     * How deep the dent is during a taken drop ([t] 0 to 1 of the gesture), as a share of
     * [DENT]: it presses in a little further as it takes the card, then closes.
     */
    fun gulp(t: Float): Float = when {
        t <= 0f || t >= 1f -> 0f
        t < 0.12f -> 1f + 0.3f * smooth(t, 0f, 0.12f)
        else -> 1.3f * (1f - smooth(t, 0.12f, 0.32f))
    }

    /** The ripple's part of the drop gesture (0 to 1), after the dent closes. */
    fun rippleTime(t: Float): Float = ((t - 0.16f) / 0.5f).coerceIn(0f, 1f)

    /** The label of a drop over the gesture ([t] 0 to 1): pops in, holds, sinks back. */
    fun label(t: Float): Float = when {
        t <= 0f || t >= 1f -> 0f
        t < 0.5f -> backOut(((t - 0.14f) / 0.16f).coerceIn(0f, 1f), 1.8f).coerceAtLeast(0f)
        else -> 1f - smooth(t, 0.84f, 0.97f)
    }

    /** The drop gesture's length, and a moment of it where all stands still (no motion). */
    const val DROP_MS = 1800
    const val DROP_STILL = 0.6f

    /** The squash of the landing at [t] (0 to 1): a damped swing, positive while squashed. */
    fun squash(t: Float): Float = exp(-4.6f * t) * sin(2f * PI.toFloat() * 1.3f * t)
}
