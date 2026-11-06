package androidx.constraintlayout.motion.widget

/**
 * YT: one field of MotionLayout that has no public setter. Every transitionToStart/End keeps the
 * transition's interpolator for the progress set by hand afterwards (mProgressInterpolator, only
 * ever replaced by the next animation), so a progress of 0.6 would draw an eased 0.6 (past the
 * end with an overshoot). The drag under the finger (YtMinimizeDrag) needs progress to mean
 * position, so it clears that interpolator while it drives the transition; the next animation
 * sets the transition's own again. Kept in MotionLayout's package for its package-private field.
 */
internal object YtMotionAccess {
    /** From now on [layout] draws a progress set by hand as it is, until its next animation. */
    fun drawProgressLinear(layout: MotionLayout) {
        layout.mProgressInterpolator = null
    }

    /**
     * Builds the motion of [layout]'s views again from its transition's keyframes and puts them
     * where the current progress has them. MotionLayout reads keyframe values only when it sets
     * up its views (at a measure of a changed hierarchy), and at rest it moves nothing until the
     * progress changes: a keyframe written after that setup (MainActivity writes the container's
     * docked shift once the bar has its height) would otherwise wait for the next transition.
     * The same two steps as MotionLayout.onLayout takes when its size changes.
     */
    fun reapply(layout: MotionLayout) {
        layout.rebuildScene()
        layout.evaluate(true)
    }
}
