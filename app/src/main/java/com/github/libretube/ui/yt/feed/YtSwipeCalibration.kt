package com.github.libretube.ui.yt.feed

import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.tan

/**
 * YT: the maths of "Wischgesten kalibrieren". The user scrolls a test list a few times as usual
 * and swipes a test card a few times on purpose; from the recorded paths this proposes the
 * angle, drift and distance of [YtSwipeTuning] that tell the two apart for this user, and
 * replays both sets of strokes through the same rules the card uses ([YtSwipePhysics]) to show
 * how many of them the current and the proposed values get right. Everything in dp.
 */
object YtSwipeCalibration {
    /** One sample of a finger path, in dp (any origin: a stroke is read from its first point). */
    data class Point(val x: Float, val y: Float)

    /** What the card would make of a stroke. */
    enum class Outcome {
        /** Never left the touch slop. */
        TAP,

        /** Given to the list: the scroll goes on. */
        SCROLL,

        /** Claimed as a swipe, then dropped as too steep or too far up/down. */
        DROPPED,

        /** A swipe that stayed short of the distance (without a throw nothing happens). */
        SHORT,

        /** A swipe that reached the distance: it commits. */
        SWIPE,
    }

    /** How many strokes of each kind the values handle as meant. */
    data class Score(val swipesOk: Int, val swipes: Int, val scrollsOk: Int, val scrolls: Int) {
        val perfect: Boolean get() = swipesOk == swipes && scrollsOk == scrolls
    }

    /**
     * The proposal: [tuning] to apply, whether the user's swipes and scrolls overlap so that
     * scrolling had to win ([conflict]), and the scores of the current values and of [tuning].
     */
    data class Proposal(val tuning: YtSwipeTuning, val conflict: Boolean, val before: Score, val after: Score)

    /** Strokes needed of each kind before a proposal is made. */
    const val MIN_STROKES = 3

    /** Android's usual touch slop. */
    const val SLOP_DP = 8f

    /** Room left between the user's steepest swipe / flattest scroll and the limits. */
    private const val ANGLE_MARGIN = 4f
    private const val DRIFT_FACTOR = 1.3f
    private const val DRIFT_EXTRA = 4f

    /** Share of the user's typical swipe length at which it commits. */
    private const val DISTANCE_SHARE = 0.6f

    /** Replays [stroke] through the card's rules with [tuning] (see YtCardSwipe). */
    fun classify(stroke: List<Point>, tuning: YtSwipeTuning, slop: Float = SLOP_DP): Outcome {
        if (stroke.size < 2) return Outcome.TAP
        val origin = stroke.first()
        var claimed = -1
        for (i in 1 until stroke.size) {
            val dx = stroke[i].x - origin.x
            val dy = stroke[i].y - origin.y
            when (YtSwipePhysics.claim(dx, dy, slop, tuning.claimRatio)) {
                YtSwipePhysics.Claim.SCROLL -> return Outcome.SCROLL
                YtSwipePhysics.Claim.SWIPE -> {
                    claimed = i
                    break
                }
                YtSwipePhysics.Claim.WAIT -> Unit
            }
        }
        if (claimed < 0) return Outcome.TAP
        var reach = 0f
        for (i in claimed until stroke.size) {
            val dx = stroke[i].x - origin.x
            val dy = stroke[i].y - origin.y
            if (YtSwipePhysics.drifted(dx, dy, tuning.driftDp, slop, tuning.steep)) return Outcome.DROPPED
            // the card starts following once the slop is used up
            reach = max(reach, abs(dx) - slop)
        }
        return if (reach >= tuning.distanceDp) Outcome.SWIPE else Outcome.SHORT
    }

    fun score(scrolls: List<List<Point>>, swipes: List<List<Point>>, tuning: YtSwipeTuning, slop: Float = SLOP_DP): Score {
        val scrollsOk = scrolls.count {
            val outcome = classify(it, tuning, slop)
            outcome == Outcome.SCROLL || outcome == Outcome.TAP
        }
        val swipesOk = swipes.count { classify(it, tuning, slop) == Outcome.SWIPE }
        return Score(swipesOk, swipes.size, scrollsOk, scrolls.size)
    }

    /** Whether [stroke] went past the slop at all (a tap tells nothing). */
    fun moved(stroke: List<Point>, slop: Float = SLOP_DP): Boolean {
        val origin = stroke.firstOrNull() ?: return false
        return stroke.any { max(abs(it.x - origin.x), abs(it.y - origin.y)) > slop }
    }

    /** The angle (degrees from the horizontal) at which [stroke] first leaves the slop: where the card decides. */
    fun startAngle(stroke: List<Point>, slop: Float = SLOP_DP): Float? {
        val origin = stroke.firstOrNull() ?: return null
        val out = stroke.firstOrNull { max(abs(it.x - origin.x), abs(it.y - origin.y)) > slop } ?: return null
        return angle(out.x - origin.x, out.y - origin.y)
    }

    /** The steepest angle the path of [stroke] reaches (from its start) once past the slop. */
    fun steepestAngle(stroke: List<Point>, slop: Float = SLOP_DP): Float? {
        val origin = stroke.firstOrNull() ?: return null
        return stroke
            .filter { max(abs(it.x - origin.x), abs(it.y - origin.y)) > slop }
            .maxOfOrNull { angle(it.x - origin.x, it.y - origin.y) }
    }

    /**
     * The values for [scrolls] and [swipes], or null with fewer than [MIN_STROKES] usable
     * strokes of either kind. The angle sits between the user's steepest swipe and (through the
     * claim) the flattest start of a scroll; if those overlap, scrolling wins ([Proposal.conflict]).
     * The drift leaves room above the swipes' largest wander, the distance is a share of a
     * typical swipe. The flick speed stays as in [current].
     */
    fun propose(
        scrolls: List<List<Point>>,
        swipes: List<List<Point>>,
        current: YtSwipeTuning,
        slop: Float = SLOP_DP,
    ): Proposal? {
        val scrollStrokes = scrolls.filter { moved(it, slop) }
        val swipeStrokes = swipes.filter { stroke ->
            val origin = stroke.firstOrNull()
            origin != null && stroke.any { abs(it.x - origin.x) > slop }
        }
        if (scrollStrokes.size < MIN_STROKES || swipeStrokes.size < MIN_STROKES) return null

        val steepestSwipe = swipeStrokes.mapNotNull { steepestAngle(it, slop) }.maxOrNull() ?: 0f
        val flattestScroll = scrollStrokes.mapNotNull { startAngle(it, slop) }.minOrNull() ?: 90f
        val low = steepestSwipe + ANGLE_MARGIN
        val high = angleForClaim(flattestScroll - ANGLE_MARGIN)
        val range = YtSwipeTuning.ANGLE_RANGE
        val conflict = low > high
        val angle = if (conflict) high else (low + minOf(high, range.endInclusive)) / 2f

        val wander = swipeStrokes.maxOf { stroke ->
            val origin = stroke.first()
            stroke.maxOf { abs(it.y - origin.y) }
        }
        val drift = wander * DRIFT_FACTOR + DRIFT_EXTRA

        val reaches = swipeStrokes.map { stroke ->
            val origin = stroke.first()
            stroke.maxOf { abs(it.x - origin.x) } - slop
        }.sorted()
        val distance = median(reaches) * DISTANCE_SHARE

        val tuning = current.copy(angleDeg = angle, driftDp = drift, distanceDp = distance).clamped()
        return Proposal(
            tuning = tuning,
            conflict = conflict,
            before = score(scrollStrokes, swipeStrokes, current, slop),
            after = score(scrollStrokes, swipeStrokes, tuning, slop),
        )
    }

    /**
     * The swipe angle whose claim is exactly [claimDeg] (inverse of [YtSwipeTuning.claimAngleDeg]):
     * tan(claim) = tan(angle) / (2 tan 30°).
     */
    internal fun angleForClaim(claimDeg: Float): Float {
        val c = claimDeg.coerceIn(0.5f, 89.5f)
        val t = YtSwipePhysics.CLAIM_RATIO * YtSwipeTuning.TAN_30 * tan(Math.toRadians(c.toDouble()))
        return Math.toDegrees(atan(t)).toFloat()
    }

    private fun angle(dx: Float, dy: Float): Float = Math.toDegrees(atan2(abs(dy).toDouble(), abs(dx).toDouble())).toFloat()

    private fun median(sorted: List<Float>): Float = when {
        sorted.isEmpty() -> 0f
        sorted.size % 2 == 1 -> sorted[sorted.size / 2]
        else -> (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2f
    }
}
