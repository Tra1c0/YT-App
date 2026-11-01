package com.github.libretube.ui.yt.player

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * YT: a damped spring for the player's View HUDs (the return chip, the lens), stepped once a
 * frame from onDraw. A retarget keeps the velocity, so an interrupted motion carries on from where
 * it is instead of starting over. Pure, so it is tested on the JVM.
 *
 * @param stiffness as in androidx.dynamicanimation (e.g. 380 medium-low, 1500 medium, 10000 high)
 * @param dampingRatio 1 settles without overshoot, below 1 overshoots a little
 */
class YtSpring(
    initial: Float,
    var stiffness: Float = 600f,
    var dampingRatio: Float = 0.85f,
    /** Closer than this to the target (and slower than this per second) counts as at rest. */
    private val threshold: Float = 0.001f,
) {
    var value: Float = initial
        private set
    var velocity: Float = 0f
        private set
    var target: Float = initial
        private set

    val atRest: Boolean get() = value == target && velocity == 0f

    /** Moves towards [target] from here, with the velocity it has. */
    fun animateTo(target: Float) {
        this.target = target
    }

    /** Jumps to [value] and stays there (the motion switch is off, or a first value). */
    fun snapTo(value: Float) {
        this.value = value
        target = value
        velocity = 0f
    }

    /** Adds [impulse] to the velocity (a release carries its speed into the spring). */
    fun kick(impulse: Float) {
        velocity += impulse
    }

    /** Advances by [dtSec]; true while it still moves. */
    fun step(dtSec: Float): Boolean {
        if (atRest) return false
        // a dropped frame or a paused view must not throw it
        var left = dtSec.coerceIn(0f, MAX_STEP_SEC)
        val omega = sqrt(stiffness)
        val damping = 2f * dampingRatio * omega
        while (left > 0f) {
            val dt = minOf(left, SUB_STEP_SEC)
            // semi-implicit Euler: stable for these stiffnesses at 4 ms steps
            val accel = -stiffness * (value - target) - damping * velocity
            velocity += accel * dt
            value += velocity * dt
            left -= dt
        }
        if (abs(value - target) < threshold && abs(velocity) < threshold * 10f) {
            value = target
            velocity = 0f
            return false
        }
        return true
    }

    private companion object {
        const val SUB_STEP_SEC = 0.004f
        const val MAX_STEP_SEC = 0.064f
    }
}
