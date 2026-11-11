package com.github.libretube.ui.yt.news

import kotlin.math.abs
import kotlin.math.min

/**
 * YT: the decisions behind the News gestures, on plain numbers so they are tested on the JVM.
 * The pinch that opens the overlay (two fingers spread apart on the home) and closes it (pinched
 * together), and the vertical swipes on a card (up: to the queue, down: close).
 */
object YtNewsGestureRules {
    /** What the pinch detector does with the current pointer event. */
    enum class Step {
        /** Not decided: the event goes on to the content (scroll, swipes, holds) untouched. */
        WAIT,

        /** A pinch: from now on its events are consumed, the content sees no more of them. */
        CLAIM,

        /** Far enough: open (or close) now. */
        COMMIT,

        /** Not a pinch (a two-finger pan, the other direction): leave the gesture alone. */
        REJECT,
    }

    /**
     * The distances, in px: [claimPx] of spread claims the gesture, [commitPx] commits it. A
     * claim also needs the spread to dominate the travel of the fingers' centre: at most
     * [driftRatio] of the spread plus [claimPx] (a two-finger scroll moves the centre, a spread
     * does not). [panPx] of centre travel without a spread is a pan, not a pinch.
     */
    data class Spec(
        val claimPx: Float,
        val commitPx: Float,
        val panPx: Float = claimPx * 2.5f,
        val driftRatio: Float = 1.2f,
    )

    /**
     * Whether a second finger may start a pinch: not when the first one already travelled more
     * than [slopPx] alone (it is scrolling or swiping a card, the second finger is a stray).
     */
    fun acceptsSecond(firstTravelPx: Float, slopPx: Float): Boolean = firstTravelPx <= slopPx

    /**
     * How far the fingers went the asked way: their gap now ([gap]) against the gap when the second
     * finger came ([startGap]); positive the way of the gesture (apart, or together when [inward]).
     */
    fun spread(startGap: Float, gap: Float, inward: Boolean): Float = if (inward) startGap - gap else gap - startGap

    /**
     * The spread that commits: [Spec.commitPx]; pinching together it can be at most half of the
     * gap the fingers started from (they cannot come closer than touching).
     */
    fun commitAt(startGap: Float, spec: Spec, inward: Boolean): Float =
        if (inward) min(spec.commitPx, (startGap * 0.5f).coerceAtLeast(spec.claimPx * 1.5f)) else spec.commitPx

    /**
     * The next step of a pinch with the fingers [gap] apart (started at [startGap]) and their centre
     * [drift] px away from where it started; [claimed] says whether it was claimed before.
     */
    fun step(startGap: Float, gap: Float, drift: Float, claimed: Boolean, spec: Spec, inward: Boolean = false): Step {
        val way = spread(startGap, gap, inward)
        val commit = commitAt(startGap, spec, inward)
        if (claimed) return if (way >= commit) Step.COMMIT else Step.WAIT
        if (way <= -spec.claimPx) return Step.REJECT
        if (way >= spec.claimPx) {
            if (drift > way * spec.driftRatio + spec.claimPx) return Step.REJECT
            return if (way >= commit) Step.COMMIT else Step.CLAIM
        }
        if (drift > spec.panPx) return Step.REJECT
        return Step.WAIT
    }

    /** How far a claimed pinch is towards its commit, 0..1 (for the live preview). */
    fun progress(startGap: Float, gap: Float, spec: Spec, inward: Boolean = false): Float {
        val commit = commitAt(startGap, spec, inward)
        val from = spec.claimPx * 0.5f
        if (commit <= from) return 1f
        return ((spread(startGap, gap, inward) - from) / (commit - from)).coerceIn(0f, 1f)
    }

    // ---------------------------------------------------------------------------------------------
    // Vertical swipes on a card

    enum class Claim { WAIT, VERTICAL, HORIZONTAL }

    /**
     * Who takes a finger that moved ([dx], [dy]) on a card: the card's vertical swipe only for a
     * clearly vertical move (|dy| ≥ [ratio]·|dx|), the pager for a sideways one.
     */
    fun claim(dx: Float, dy: Float, slopPx: Float, ratio: Float = 1.5f): Claim {
        val ax = abs(dx)
        val ay = abs(dy)
        if (ay > slopPx && ay >= ratio * ax) return Claim.VERTICAL
        if (ax > slopPx) return Claim.HORIZONTAL
        return Claim.WAIT
    }

    /** What letting go of a card after a vertical swipe does. */
    enum class Release { STAY, QUEUE, CLOSE }

    /**
     * Released [dy] px from where it started (negative: up) with [velocityY] px/s: up past
     * [queuePx] (or flicked up) queues, down past [closePx] (or flicked down) closes. A flick
     * counts from a third of the way and faster than [flickVelocity].
     */
    fun release(dy: Float, velocityY: Float, queuePx: Float, closePx: Float, flickVelocity: Float = 1400f): Release {
        if (dy < 0f) {
            val far = -dy >= queuePx
            val flick = -velocityY > flickVelocity && -dy >= queuePx / 3f
            return if (far || flick) Release.QUEUE else Release.STAY
        }
        if (dy > 0f) {
            val far = dy >= closePx
            val flick = velocityY > flickVelocity && dy >= closePx / 3f
            return if (far || flick) Release.CLOSE else Release.STAY
        }
        return Release.STAY
    }
}
