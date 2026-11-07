package com.github.libretube.ui.yt.feed

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sign
import kotlin.math.sqrt

/**
 * YT: the physics of the card swipe on the home screens (left hides, right queues) and of the
 * token that then flies to the mini player, kept apart from Compose for the JVM tests. All
 * distances in px, velocities in px/s.
 */
object YtSwipePhysics {
    /** A throw this fast commits a swipe that has not reached its threshold yet. */
    const val FLICK_VELOCITY = 1400f

    /** ... once it has come at least this share of the threshold. */
    const val FLICK_SHARE = 0.35f

    private const val RUBBER = 0.55f

    /** A swipe is claimed only when the finger went at least this many times as far sideways as down. */
    const val CLAIM_RATIO = 2f

    /** tan(30°): a claimed swipe whose path (or release) gets steeper than this is a scroll after all. */
    const val STEEP = 0.577f

    /** A release slower than this vertically never counts as a scroll, however slow it is sideways. */
    const val VERTICAL_RELEASE_FLOOR = 600f

    /** What the first moves of a touch on a card are: not decided yet, a swipe, or the list's scroll. */
    enum class Claim { WAIT, SWIPE, SCROLL }

    /**
     * Whether a touch that moved ([dx], [dy]) from where it went down is a swipe of the card. It
     * is one only when it went past [slop] sideways and clearly sideways ([CLAIM_RATIO]); once
     * it went past [slop] down without that, it is the list's: a slanted scroll down never
     * becomes a swipe.
     */
    fun claim(dx: Float, dy: Float, slop: Float, ratio: Float = CLAIM_RATIO): Claim {
        val across = abs(dx)
        val down = abs(dy)
        return when {
            across > slop && across >= ratio * down -> Claim.SWIPE
            down > slop -> Claim.SCROLL
            // neither clear yet: the next move decides (or the list takes it)
            else -> Claim.WAIT
        }
    }

    /**
     * Whether a claimed swipe, now ([dx], [dy]) from where it went down, has turned into a
     * scroll: the finger went more than [drift] up or down, or (past [slop]) its path got
     * steeper than 30° ([steep] = tan of the angle, see YtSwipeTuning). The swipe is then dropped
     * without committing.
     */
    fun drifted(dx: Float, dy: Float, drift: Float, slop: Float, steep: Float = STEEP): Boolean {
        val down = abs(dy)
        return down > drift || (down > slop && down > abs(dx) * steep)
    }

    /** Whether a release with velocity ([vx], [vy]) goes up or down more than sideways (steeper than 30°). */
    fun steepRelease(vx: Float, vy: Float, steep: Float = STEEP): Boolean =
        abs(vy) > VERTICAL_RELEASE_FLOOR && abs(vy) > abs(vx) * steep

    /** How far past the threshold the card moves for [overshoot] px of pull: less and less. */
    fun rubberBand(overshoot: Float, dimension: Float): Float =
        (overshoot * dimension * RUBBER) / (dimension + RUBBER * abs(overshoot))

    /** Where the card is drawn for [raw] px of finger travel: 1:1 up to the threshold, then a rubber band. */
    fun band(raw: Float, threshold: Float, dimension: Float): Float {
        val over = abs(raw) - threshold
        return if (over <= 0f) raw else sign(raw) * (threshold + rubberBand(over, dimension))
    }

    /** The finger travel that draws the card at [shown], so a new touch picks it up where it is. */
    fun unband(shown: Float, threshold: Float, dimension: Float): Float {
        val over = abs(shown) - threshold
        if (over <= 0f) return shown
        // inverse of rubberBand: o = f d / (c (d - f)), the band never reaches d
        val f = over.coerceAtMost(dimension * 0.999f)
        return sign(shown) * (threshold + f * dimension / (RUBBER * (dimension - f)))
    }

    /** The side the swipe is armed for at [shown]: -1 (hide), 1 (queue) or 0. */
    fun side(shown: Float, threshold: Float): Int = when {
        shown >= threshold -> 1
        shown <= -threshold -> -1
        else -> 0
    }

    /**
     * What letting go does: past the threshold it commits that side unless the card is thrown
     * back hard; before it a clear throw outwards commits. A release that moves up or down more
     * than sideways ([vy], see [steepRelease]) never commits. -1 hide, 1 queue, 0 back to rest.
     */
    fun decide(
        shown: Float,
        velocity: Float,
        threshold: Float,
        vy: Float = 0f,
        steep: Float = STEEP,
        flick: Float = FLICK_VELOCITY,
    ): Int {
        if (steepRelease(velocity, vy, steep)) return 0
        val armed = side(shown, threshold)
        val thrown = abs(velocity) > flick
        if (armed != 0) return if (thrown && sign(velocity) != sign(shown)) 0 else armed
        if (thrown && sign(velocity) == sign(shown) && abs(shown) > threshold * FLICK_SHARE) return sign(shown).toInt()
        return 0
    }

    /** How far left a card at [right] (its right edge in the window) flies to be gone: past the edge by [margin]. */
    fun flyOut(right: Float, margin: Float): Float = -(right.coerceAtLeast(0f) + margin)

    /**
     * The detents of a turning underlay (the cookie of Formen): it turns [degrees] per threshold
     * of pull and clicks every [step] degrees. @return how many detents [shown] has passed
     */
    fun detents(shown: Float, threshold: Float, degrees: Float = 90f, step: Float = 30f): Int =
        if (threshold <= 0f) 0 else floor(abs(shown) / threshold * degrees / step).toInt()
}

/**
 * YT: the spring the queued card's token flies on: it leaves with the finger's velocity and
 * bends towards its target, which may move meanwhile (the draft pill that appears). Stepped per
 * frame; small sub-steps keep it stable on slow frames.
 */
class YtFlightSpring(
    var x: Float,
    var y: Float,
    var vx: Float = 0f,
    var vy: Float = 0f,
    private val stiffness: Float = 240f,
    dampingRatio: Float = 0.82f,
) {
    private val damping = 2f * dampingRatio * sqrt(stiffness)

    /** Moves on by [seconds] towards ([tx], [ty]). */
    fun step(tx: Float, ty: Float, seconds: Float) {
        var left = seconds.coerceIn(0f, 0.1f)
        while (left > 0f) {
            val dt = minOf(left, SUB_STEP)
            val ax = -stiffness * (x - tx) - damping * vx
            val ay = -stiffness * (y - ty) - damping * vy
            vx += ax * dt
            vy += ay * dt
            x += vx * dt
            y += vy * dt
            left -= dt
        }
    }

    fun distanceTo(tx: Float, ty: Float): Float = sqrt((x - tx) * (x - tx) + (y - ty) * (y - ty))

    /** Close enough and slow enough to land. */
    fun arrived(tx: Float, ty: Float, distance: Float = 6f, speed: Float = 420f): Boolean =
        distanceTo(tx, ty) <= distance && sqrt(vx * vx + vy * vy) <= speed

    private companion object {
        const val SUB_STEP = 0.004f
    }
}
