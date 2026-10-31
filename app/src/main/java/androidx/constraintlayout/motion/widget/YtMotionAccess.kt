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
}
