package com.github.libretube.ui.yt.builder

/**
 * YT: how a state's look (a node's state patch: pressed, selected, active, loading, custom …)
 * and the animator's state transitions work together (pure).
 *
 * - The patch is the look of the state; it applies at once when the state goes on.
 * - Where the animator animates a property of that node into or out of that state (a STATE
 *   transition naming the state, or the event that sets it: touch down/up for pressed, (de)select,
 *   playing/paused, loading start/end, the events of the state rules), the animation carries the
 *   change and the patch leaves that property alone. Otherwise both would act: an opacity patch
 *   and an opacity animation multiply, offsets and extra radii add up (the double effect).
 * - Several states at once: the patches are laid on from the lowest priority up, so the shown state
 *   (YtStateNames.priority, then the custom states in their order) wins where they disagree.
 * - The plain press feel (a spring scale) gives by the pressed patch's scale or the motion token,
 *   and not at all where the animator owns the press.
 */
object YtStatePatchRule {
    /** The animator properties a patch value stands for. */
    fun props(p: YtPatch): Set<YtAnimProp> = buildSet {
        if (p.width != null) add(YtAnimProp.WIDTH)
        if (p.height != null) add(YtAnimProp.HEIGHT)
        if (p.x != null) add(YtAnimProp.X)
        if (p.y != null) add(YtAnimProp.Y)
        if (p.rotation != null) add(YtAnimProp.ROTATION)
        if (p.opacity != null) add(YtAnimProp.OPACITY)
        if (p.corners != null) add(YtAnimProp.CORNERS)
        if (p.fills != null) addAll(FILL)
        if (p.strokes != null) addAll(STROKE)
        if (p.textColor != null) add(YtAnimProp.TEXT_COLOR)
        if (p.scale != null) addAll(SCALE)
    }

    private val FILL = setOf(YtAnimProp.FILL_COLOR, YtAnimProp.GRADIENT_ANGLE, YtAnimProp.GRADIENT_SHIFT)
    private val STROKE = setOf(YtAnimProp.STROKE_COLOR, YtAnimProp.STROKE_WIDTH)
    private val SCALE = setOf(YtAnimProp.SCALE_X, YtAnimProp.SCALE_Y)

    /** [p] without the values the animator animates ([animated]) in its state. */
    fun effective(p: YtPatch, animated: Set<YtAnimProp>): YtPatch {
        if (animated.isEmpty()) return p
        fun has(vararg props: YtAnimProp) = props.any { it in animated }
        return p.copy(
            width = p.width.takeUnless { has(YtAnimProp.WIDTH) },
            height = p.height.takeUnless { has(YtAnimProp.HEIGHT) },
            x = p.x.takeUnless { has(YtAnimProp.X) },
            y = p.y.takeUnless { has(YtAnimProp.Y) },
            rotation = p.rotation.takeUnless { has(YtAnimProp.ROTATION) },
            opacity = p.opacity.takeUnless { has(YtAnimProp.OPACITY) },
            corners = p.corners.takeUnless { has(YtAnimProp.CORNERS) },
            fills = p.fills.takeUnless { FILL.any { it in animated } },
            strokes = p.strokes.takeUnless { STROKE.any { it in animated } },
            textColor = p.textColor.takeUnless { has(YtAnimProp.TEXT_COLOR) },
            scale = p.scale.takeUnless { SCALE.any { it in animated } },
        )
    }

    /** The events that set [state] on their own (besides STATE transitions naming it). */
    fun eventsOf(state: String): Set<YtTriggerKind> = when (state) {
        YtStateNames.PRESSED -> setOf(YtTriggerKind.TOUCH_DOWN, YtTriggerKind.TOUCH_UP)
        YtStateNames.SELECTED -> setOf(YtTriggerKind.SELECTED, YtTriggerKind.DESELECTED)
        YtStateNames.ACTIVE -> setOf(YtTriggerKind.PLAYING, YtTriggerKind.PAUSED)
        YtStateNames.LOADING -> setOf(YtTriggerKind.LOADING_START, YtTriggerKind.LOADING_END)
        else -> emptySet()
    }

    /**
     * The properties of [node] that [anims] (one component's, enabled) animate when [state] goes
     * on or off: STATE transitions that name it as from or to, the events that set it, and the
     * events of [rules] that change it.
     */
    fun animatedIn(anims: List<YtAnim>, rules: List<YtStateRule>, node: String, state: String): Set<YtAnimProp> {
        val events = eventsOf(state) + rules.filter { it.state == state }.map { it.on }
        val out = HashSet<YtAnimProp>()
        for (a in anims) {
            if (a.node != node || !a.enabled) continue
            val t = a.trigger
            val hit = if (t.kind == YtTriggerKind.STATE) t.from == state || t.to == state else t.kind in events
            if (hit) a.playing().mapTo(out) { it.prop }
        }
        return out
    }

    /** [states] in the order their patches are laid on: lowest priority first, the shown one last. */
    fun order(states: Set<String>, custom: List<String> = emptyList()): List<String> {
        if (states.size < 2) return states.toList()
        val rank = YtStateNames.priority + custom
        // unknown names first (lowest), then from the end of the priority list up to its top
        return states.sortedByDescending { s -> rank.indexOf(s).let { if (it < 0) Int.MAX_VALUE else it } }
    }

    /** The plain press feel's scale: null when the animator owns the press (it moves the element itself). */
    fun pressScale(patchScale: Float?, token: Float, animatorOwnsPress: Boolean): Float? =
        if (animatorOwnsPress) null else (patchScale ?: token)
}
