package com.github.libretube.ui.yt.feed

import kotlin.math.atan
import kotlin.math.roundToInt
import kotlin.math.tan

/**
 * YT: the user's tolerance for the card swipe (see [YtSwipePhysics]). One slider ("Wisch-
 * Empfindlichkeit", 0 strict … 100 loose, 50 = the built-in values) sets all of it; "Fein
 * einstellen" overrides angle, drift and distance one by one. Pure, for the JVM tests.
 *
 * [angleDeg] is the steepest path (from the horizontal) a claimed swipe may take before it
 * counts as a scroll; the claim itself is a little stricter ([claimRatio]): at 30° it is the
 * built-in "twice as far sideways as down".
 */
data class YtSwipeTuning(
    val angleDeg: Float = DEFAULT_ANGLE,
    val driftDp: Float = DEFAULT_DRIFT,
    val distanceDp: Float = DEFAULT_DISTANCE,
    val flickVelocity: Float = YtSwipePhysics.FLICK_VELOCITY,
) {
    /** tan(angle): what [YtSwipePhysics.drifted] and [YtSwipePhysics.steepRelease] compare with. */
    val steep: Float get() = tan(Math.toRadians(angleDeg.toDouble())).toFloat()

    /** How many times as far sideways as down a touch must go to be claimed; 2 at 30°. */
    val claimRatio: Float get() = YtSwipePhysics.CLAIM_RATIO * TAN_30 / steep

    /** The steepest start (degrees from the horizontal) that is still claimed as a swipe. */
    val claimAngleDeg: Float get() = Math.toDegrees(atan(1.0 / claimRatio)).toFloat()

    /** Values rounded to whole degrees / dp and clamped to the ranges the settings offer. */
    fun clamped(): YtSwipeTuning = copy(
        angleDeg = angleDeg.roundToInt().toFloat().coerceIn(ANGLE_RANGE),
        driftDp = driftDp.roundToInt().toFloat().coerceIn(DRIFT_RANGE),
        distanceDp = distanceDp.roundToInt().toFloat().coerceIn(DISTANCE_RANGE),
    )

    companion object {
        const val DEFAULT_ANGLE = 30f
        const val DEFAULT_DRIFT = 24f
        const val DEFAULT_DISTANCE = 96f

        /** The slider's middle: the built-in values. */
        const val DEFAULT_SENSITIVITY = 50

        val ANGLE_RANGE = 15f..45f
        val DRIFT_RANGE = 10f..48f
        val DISTANCE_RANGE = 60f..140f

        internal val TAN_30 = tan(Math.toRadians(30.0)).toFloat()

        val DEFAULT = YtSwipeTuning()

        /** The strict end: a swipe must be nearly flat, may barely wander and must go far. */
        val STRICT = YtSwipeTuning(angleDeg = 18f, driftDp = 14f, distanceDp = 124f, flickVelocity = 1800f)

        /** The loose end: slanted swipes still count and a short pull is enough. */
        val LOOSE = YtSwipeTuning(angleDeg = 42f, driftDp = 40f, distanceDp = 72f, flickVelocity = 1100f)

        /**
         * The tuning for slider position [sensitivity] (0 strict … 100 loose): linear from
         * [STRICT] to [DEFAULT] to [LOOSE], so 50 is exactly today's behaviour.
         */
        fun forSensitivity(sensitivity: Int): YtSwipeTuning {
            val s = sensitivity.coerceIn(0, 100)
            return if (s <= DEFAULT_SENSITIVITY) {
                lerp(STRICT, DEFAULT, s / DEFAULT_SENSITIVITY.toFloat())
            } else {
                lerp(DEFAULT, LOOSE, (s - DEFAULT_SENSITIVITY) / (100f - DEFAULT_SENSITIVITY))
            }
        }

        /**
         * What the card swipe uses: the slider's tuning, with each fine value that is set (not
         * null) put in its place (clamped). The flick speed always follows the slider.
         */
        fun resolve(sensitivity: Int, angleDeg: Float? = null, driftDp: Float? = null, distanceDp: Float? = null): YtSwipeTuning {
            val base = forSensitivity(sensitivity)
            return base.copy(
                angleDeg = angleDeg?.coerceIn(ANGLE_RANGE) ?: base.angleDeg,
                driftDp = driftDp?.coerceIn(DRIFT_RANGE) ?: base.driftDp,
                distanceDp = distanceDp?.coerceIn(DISTANCE_RANGE) ?: base.distanceDp,
            )
        }

        private fun lerp(a: YtSwipeTuning, b: YtSwipeTuning, t: Float) = YtSwipeTuning(
            angleDeg = a.angleDeg + (b.angleDeg - a.angleDeg) * t,
            driftDp = a.driftDp + (b.driftDp - a.driftDp) * t,
            distanceDp = a.distanceDp + (b.distanceDp - a.distanceDp) * t,
            flickVelocity = a.flickVelocity + (b.flickVelocity - a.flickVelocity) * t,
        )
    }
}
