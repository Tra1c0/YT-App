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

    /**
     * Lets [layout]'s states take the views as they are now, the way they took them when it
     * came into the window. A MotionLayout's states take every view's constraints, visibility
     * and elevation that its scene does not set from the views once, when it is attached, and
     * lay the views out from that copy from then on. On a cold start that is after the
     * activity's onCreate. A recreate() while the activity is in front (a new design or theme
     * applied without a restart) keeps the window, so setContentView attaches the layout at
     * once and the copy is taken before onCreate changes the views (the floating bar's screens
     * running down behind the bar); the old layout would come back. Reloading the scene while
     * attached takes the copy again and puts the progress back, like the attach does.
     * Does nothing when [layout] is not attached: its attach still comes and reads the views.
     */
    fun takeViewsAgain(layout: MotionLayout, scene: Int) {
        if (!layout.isAttachedToWindow) return
        layout.loadLayoutDescription(scene)
    }
}
