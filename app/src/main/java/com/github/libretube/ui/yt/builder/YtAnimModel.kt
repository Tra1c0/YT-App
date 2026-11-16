package com.github.libretube.ui.yt.builder

import kotlinx.serialization.Serializable

/*
 * YT: the animations of a theme document (theme builder, phase 2 "Animator"). Pure data in the
 * document's `animations` field (schema 2). The runtime (YtAnimEngine) plays them in the real
 * "Eigenes" design, the editor builds them with presets, parameters, a timeline and a curve editor.
 *
 * Model: an animation [YtAnim] belongs to one node of one component and starts on a [YtTrigger].
 * It is either time-based (keyframes over [YtAnim.durationMs] with an easing curve per segment) or
 * a spring (physics: stiffness, damping ratio, initial velocity; no duration). Each [YtTrack]
 * animates one [YtAnimProp]. Values are relative to the node's own look where that makes sense
 * (offsets, extra radius, multipliers), so a preset works on any node.
 *
 * Several animations on the same property: the one started last wins and starts from the current
 * value (springs keep the current velocity). This rule is shown in the editor.
 */

/** How a property's value is drawn: transform/opacity in the layer, style in the draw cache, size in layout. */
enum class YtPropPhase { LAYER, LAYOUT, STYLE, TEXT, OVERLAY }

/**
 * The properties an animation can change. [rest] is the value without animation, [min]/[max]
 * what the validation allows, [colour] marks colour properties (keyframes hold colours).
 * Units: X, Y, WIDTH, HEIGHT, CORNERS, STROKE_WIDTH, the SHADOW ones, GLOW and BLUR in dp (added to the node's own),
 * the SCALE ones, OPACITY and SHADOW_ALPHA as multipliers, ROTATION and GRADIENT_ANGLE in degrees
 * (added), the PIVOT ones as a share of the box, GRADIENT_SHIFT as a share of the gradient's length,
 * SHIMMER as the position of a light band across the node (0 and 1: outside).
 */
@Serializable
enum class YtAnimProp(val rest: Float, val min: Float, val max: Float, val phase: YtPropPhase, val colour: Boolean = false) {
    X(0f, -2000f, 2000f, YtPropPhase.LAYER),
    Y(0f, -2000f, 2000f, YtPropPhase.LAYER),
    WIDTH(0f, -2000f, 2000f, YtPropPhase.LAYOUT),
    HEIGHT(0f, -2000f, 2000f, YtPropPhase.LAYOUT),
    SCALE_X(1f, -10f, 10f, YtPropPhase.LAYER),
    SCALE_Y(1f, -10f, 10f, YtPropPhase.LAYER),
    ROTATION(0f, -36000f, 36000f, YtPropPhase.LAYER),
    PIVOT_X(0.5f, -2f, 3f, YtPropPhase.LAYER),
    PIVOT_Y(0.5f, -2f, 3f, YtPropPhase.LAYER),
    OPACITY(1f, 0f, 1f, YtPropPhase.LAYER),
    FILL_COLOR(0f, 0f, 0f, YtPropPhase.STYLE, colour = true),
    STROKE_COLOR(0f, 0f, 0f, YtPropPhase.STYLE, colour = true),
    TEXT_COLOR(0f, 0f, 0f, YtPropPhase.TEXT, colour = true),
    GRADIENT_ANGLE(0f, -3600f, 3600f, YtPropPhase.STYLE),
    GRADIENT_SHIFT(0f, -2f, 2f, YtPropPhase.STYLE),
    CORNERS(0f, -1000f, 1000f, YtPropPhase.STYLE),
    STROKE_WIDTH(0f, -100f, 100f, YtPropPhase.STYLE),
    SHADOW_X(0f, -200f, 200f, YtPropPhase.STYLE),
    SHADOW_Y(0f, -200f, 200f, YtPropPhase.STYLE),
    SHADOW_BLUR(0f, -200f, 200f, YtPropPhase.STYLE),
    SHADOW_ALPHA(1f, 0f, 4f, YtPropPhase.STYLE),
    GLOW(0f, 0f, 200f, YtPropPhase.STYLE),
    BLUR(0f, 0f, 100f, YtPropPhase.LAYER),
    SHIMMER(0f, 0f, 1f, YtPropPhase.OVERLAY),

    /** Progress 0..1 along the animation's motion path ([YtAnim.motion], phase 3). */
    PATH(0f, 0f, 1f, YtPropPhase.LAYER),

    /** Progress 0..1 from the node's own shape to the animation's morph target ([YtAnim.morph], phase 3). */
    MORPH(0f, 0f, 1f, YtPropPhase.STYLE),
    ;

    /** Values per sample: colours run as four (OKLab + alpha), everything else as one. */
    val dims: Int get() = if (colour) 4 else 1
}

/** Easing between two keyframes. Named kinds have fixed control points; BEZIER uses its own. */
@Serializable
enum class YtCurveKind { LINEAR, EASE_IN, EASE_OUT, EASE_IN_OUT, BEZIER, HOLD }

/** A cubic Bézier easing (CSS style: from (0,0) to (1,1), control points x in 0..1, y free). */
@Serializable
data class YtCurve(
    val kind: YtCurveKind = YtCurveKind.LINEAR,
    val x1: Float = 0f,
    val y1: Float = 0f,
    val x2: Float = 1f,
    val y2: Float = 1f,
    /** The saved preset it came from, shown in the editor. */
    val name: String? = null,
) {
    companion object {
        val LINEAR = YtCurve()
        val EASE_IN = YtCurve(YtCurveKind.EASE_IN)
        val EASE_OUT = YtCurve(YtCurveKind.EASE_OUT)
        val EASE_IN_OUT = YtCurve(YtCurveKind.EASE_IN_OUT)
        val HOLD = YtCurve(YtCurveKind.HOLD)

        /** Strong ease-out (the app's YtEaseOut) for things that enter or answer. */
        val STRONG_OUT = bezier(0.23f, 1f, 0.32f, 1f)

        fun bezier(x1: Float, y1: Float, x2: Float, y2: Float, name: String? = null) = YtCurve(YtCurveKind.BEZIER, x1, y1, x2, y2, name)
    }
}

/**
 * A keyframe at [at] (share 0..1 of the animation's duration). Float tracks use [value];
 * colour tracks use [color] (ARGB), null meaning the node's own colour. [curve] eases the
 * segment from this keyframe to the next.
 */
@Serializable
data class YtKeyframe(
    val at: Float,
    val value: Float = 0f,
    @Serializable(with = YtArgbSerializer::class) val color: Int? = null,
    val curve: YtCurve = YtCurve.LINEAR,
)

/**
 * One property of an animation. Springs use the first keyframe as start and the last as target
 * and [velocity] (property units per second) as the push at a fresh start. Muted tracks never
 * play; when any track of an animation is solo, only the solo ones play.
 */
@Serializable
data class YtTrack(
    val prop: YtAnimProp,
    val keys: List<YtKeyframe> = emptyList(),
    val velocity: Float = 0f,
    val muted: Boolean = false,
    val solo: Boolean = false,
)

/** Time-based (keyframes over a duration) or physics (a spring that settles in its own time). */
@Serializable
enum class YtAnimKind { TIME, SPRING }

@Serializable
enum class YtAnimDirection { NORMAL, REVERSE, ALTERNATE }

/** A spring: [stiffness] (mass 1) and [damping] ratio (1 critical, below overshoots). */
@Serializable
data class YtSpringSpec(val stiffness: Float = 400f, val damping: Float = 0.6f)

/**
 * What starts an animation. Only hooks YT really has: touches on the component, the component
 * entering/leaving the visible area, the feed's scroll (SCROLL plays along the scroll position
 * over [scrollRange] dp), a chip or tab becoming (de)selected, the player playing or pausing, the
 * feed loading, a change of the component's state ([from] → [to], "*" for any). MANUAL plays
 * only in the editor.
 */
@Serializable
enum class YtTriggerKind {
    APPEAR, DISAPPEAR, TAP, DOUBLE_TAP, LONG_PRESS, TOUCH_DOWN, TOUCH_UP,
    SCROLL, SCROLL_UP, SCROLL_DOWN, SELECTED, DESELECTED, PLAYING, PAUSED,
    LOADING_START, LOADING_END, STATE, MANUAL,
}

@Serializable
data class YtTrigger(
    val kind: YtTriggerKind = YtTriggerKind.TAP,
    val from: String = YtStateNames.ANY,
    val to: String = YtStateNames.ANY,
    val scrollRange: Float = 600f,
)

@Serializable
data class YtAnim(
    val id: String,
    val name: String = "",
    val component: YtComponentKind,
    /** The node of [component] it moves. */
    val node: String,
    val trigger: YtTrigger = YtTrigger(),
    val kind: YtAnimKind = YtAnimKind.TIME,
    val durationMs: Int = 400,
    val delayMs: Int = 0,
    val spring: YtSpringSpec = YtSpringSpec(),
    /** Extra plays after the first; -1 forever. */
    val repeat: Int = 0,
    val direction: YtAnimDirection = YtAnimDirection.NORMAL,
    val tracks: List<YtTrack> = emptyList(),
    /** The [YtAnimGroup] it is scheduled in, if any. */
    val group: String? = null,
    /** The preset it was made from, with its strength (the simple mode edits these). */
    val preset: YtPreset? = null,
    val strength: Float = 1f,
    val enabled: Boolean = true,
    /** The path a PATH track moves the node along (phase 3). */
    val motion: YtMotionPath? = null,
    /** The shape a MORPH track changes the node into (phase 3). */
    val morph: YtMorphTarget? = null,
) {
    val infinite: Boolean get() = repeat < 0

    /** The tracks that play: not muted, and only the solo ones when any is solo. */
    fun playing(): List<YtTrack> {
        val open = tracks.filter { !it.muted && it.keys.isNotEmpty() }
        return if (open.any { it.solo }) open.filter { it.solo } else open
    }
}

/** How the members of a group fired by the same event are scheduled. */
@Serializable
enum class YtGroupMode { PARALLEL, SEQUENCE, STAGGER }

@Serializable
data class YtAnimGroup(val id: String, val name: String = "", val mode: YtGroupMode = YtGroupMode.PARALLEL, val staggerMs: Int = 60)

@Serializable
enum class YtStateAction { SET, CLEAR, TOGGLE }

/** An event that changes a state of the component (e.g. double tap toggles "expanded"). */
@Serializable
data class YtStateRule(val component: YtComponentKind, val on: YtTriggerKind, val state: String, val action: YtStateAction = YtStateAction.TOGGLE)

/** A saved easing curve. */
@Serializable
data class YtEasingPreset(val name: String, val x1: Float, val y1: Float, val x2: Float, val y2: Float) {
    fun curve() = YtCurve.bezier(x1, y1, x2, y2, name)
}

/** Everything the animator stores in a theme. */
@Serializable
data class YtAnimations(
    val items: List<YtAnim> = emptyList(),
    val groups: List<YtAnimGroup> = emptyList(),
    val rules: List<YtStateRule> = emptyList(),
    /** States the user added besides the built-in ones. */
    val customStates: List<String> = emptyList(),
    val easings: List<YtEasingPreset> = emptyList(),
) {
    val isEmpty: Boolean get() = items.isEmpty() && rules.isEmpty()

    fun of(kind: YtComponentKind): List<YtAnim> = items.filter { it.component == kind && it.enabled }
}

/** The presets of the simple mode. */
@Serializable
enum class YtPreset {
    FADE_IN, FADE_OUT, SLIDE_IN, SLIDE_OUT, SCALE_IN, SCALE_OUT, BOUNCE, SPRING, POP, PULSE, ROTATE,
    SHAKE, WOBBLE, STRETCH, COMPRESS, EXPAND, COLLAPSE, FLOATING, GLOW_PULSE, COLOR_TRANSITION,
    LOADING, SHIMMER, STAGGER,
}

/** Component states: the built-in ones in priority order (the first that is on is the shown one). */
object YtStateNames {
    const val ANY = "*"
    const val IDLE = "idle"
    const val PRESSED = "pressed"
    const val SELECTED = "selected"
    const val ACTIVE = "active"
    const val LOADING = "loading"
    const val DISABLED = "disabled"
    const val EXPANDED = "expanded"
    const val COLLAPSED = "collapsed"
    const val FOCUSED = "focused"

    /** Highest priority first; IDLE is what is left when none is on. */
    val priority = listOf(DISABLED, LOADING, PRESSED, ACTIVE, SELECTED, FOCUSED, EXPANDED, COLLAPSED)
    val builtIn = listOf(IDLE) + priority

    /** A name a user may give a custom state. */
    fun validCustom(name: String): Boolean =
        name.length in 1..24 && name !in builtIn && name != ANY && name.all { it.isLetterOrDigit() || it == '_' || it == '-' }
}

/** Why a property cannot animate on a node (the editor marks it), or null when it can. */
enum class YtAnimUnsupported { NO_FILL, SOLID_FILL_ONLY, GRADIENT_ONLY, NO_STROKE, NO_SHADOW, TEXT_ONLY, OWN_DRAWING, NEEDS_ANDROID_12, SHAPE_ONLY }

object YtAnimSupport {
    /** Whether [prop] has an effect on [node] (Android [sdk]); null: it does. */
    fun check(prop: YtAnimProp, node: YtNode, sdk: Int = 36): YtAnimUnsupported? {
        val fill = node.style.fills.firstOrNull { it.enabled }
        val styled = node.slot != YtSlot.PROGRESS
        return when (prop) {
            YtAnimProp.FILL_COLOR -> when {
                !styled -> YtAnimUnsupported.OWN_DRAWING
                fill == null -> YtAnimUnsupported.NO_FILL
                node.style.fills.none { it.enabled && it.type == YtPaintType.SOLID } -> YtAnimUnsupported.SOLID_FILL_ONLY
                else -> null
            }
            YtAnimProp.GRADIENT_ANGLE, YtAnimProp.GRADIENT_SHIFT -> when {
                !styled -> YtAnimUnsupported.OWN_DRAWING
                node.style.fills.none { it.enabled && it.type != YtPaintType.SOLID } -> YtAnimUnsupported.GRADIENT_ONLY
                else -> null
            }
            YtAnimProp.STROKE_COLOR, YtAnimProp.STROKE_WIDTH ->
                if (!styled) YtAnimUnsupported.OWN_DRAWING else if (node.style.strokes.none { it.enabled }) YtAnimUnsupported.NO_STROKE else null
            YtAnimProp.SHADOW_X, YtAnimProp.SHADOW_Y, YtAnimProp.SHADOW_BLUR, YtAnimProp.SHADOW_ALPHA ->
                if (!styled) YtAnimUnsupported.OWN_DRAWING else if (node.style.shadows.none { it.enabled && !it.inner }) YtAnimUnsupported.NO_SHADOW else null
            YtAnimProp.CORNERS, YtAnimProp.GLOW, YtAnimProp.SHIMMER -> if (!styled) YtAnimUnsupported.OWN_DRAWING else null
            YtAnimProp.TEXT_COLOR -> if (node.type == YtNodeType.TEXT || (node.type == YtNodeType.SLOT && node.slot?.text == true)) null else YtAnimUnsupported.TEXT_ONLY
            YtAnimProp.BLUR -> if (sdk < 31) YtAnimUnsupported.NEEDS_ANDROID_12 else null
            YtAnimProp.MORPH -> if (node.type != YtNodeType.SHAPE) YtAnimUnsupported.SHAPE_ONLY else null
            else -> null
        }
    }
}
