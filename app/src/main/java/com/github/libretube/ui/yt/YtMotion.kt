package com.github.libretube.ui.yt

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.github.libretube.helpers.PreferenceHelper
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.delay
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.launch

/*
 * YT: motion of the designs. Every animation goes through [ytSpec] or checks [LocalYtMotion],
 * so the switch "Animationen der Designs" (Darstellung) turns all of it into direct state
 * changes. Compose also scales everything with the system animator duration, so "Remove
 * animations" in the Android accessibility settings works as well.
 */

/** The preference behind the motion switch. */
object YtMotionPrefs {
    const val KEY = "yt_design_motion"
    val enabled: Boolean get() = PreferenceHelper.getBoolean(KEY, true)
}

/** The character of a design's motion. */
enum class YtMotionStyle {
    /** YouTube neu: quiet fades, short distances. */
    CALM,

    /** Movo, Pop, Clay, Formen: springs that overshoot a little. */
    SPRINGY,

    /** After: slow crossfades, long eases, a slow zoom on posters. */
    CINEMATIC,

    /** Editorial: short, straight, no overshoot. */
    CRISP,

    /** Side, Glas, Frost: soft and floating. */
    SOFT,
}

/** Strong ease-out for things that enter or answer (Emil Kowalski's UI curve). */
val YtEaseOut = CubicBezierEasing(0.23f, 1f, 0.32f, 1f)

/** Strong ease-in-out for things that move or morph on screen. */
val YtEaseInOut = CubicBezierEasing(0.77f, 0f, 0.175f, 1f)

/** False when the motion switch is off. */
val LocalYtMotion = staticCompositionLocalOf { true }

/** The motion character of the current design. */
val LocalYtMotionStyle = staticCompositionLocalOf { YtMotionStyle.CALM }

/** [spec], or a direct change when motion is off. */
@Composable
fun <T> ytSpec(spec: AnimationSpec<T>): AnimationSpec<T> = if (LocalYtMotion.current) spec else snap()

/** [spec] for APIs that need a finite animation (Crossfade, animateContentSize). */
@Composable
fun <T> ytFinite(spec: FiniteAnimationSpec<T>): FiniteAnimationSpec<T> = if (LocalYtMotion.current) spec else snap()

/** The usual spec of the current design for a state change. */
@Composable
fun <T> ytStateSpec(): AnimationSpec<T> = ytSpec(
    when (LocalYtMotionStyle.current) {
        YtMotionStyle.SPRINGY -> spring(dampingRatio = 0.7f, stiffness = 420f)
        YtMotionStyle.CINEMATIC -> tween(420, easing = YtEaseInOut)
        YtMotionStyle.CRISP -> tween(160, easing = YtEaseOut)
        YtMotionStyle.SOFT -> spring(dampingRatio = 0.9f, stiffness = 300f)
        YtMotionStyle.CALM -> tween(220, easing = YtEaseOut)
    }
)

/** An enter transition, or none when motion is off. */
@Composable
fun ytEnterTransition(transition: EnterTransition): EnterTransition =
    if (LocalYtMotion.current) transition else EnterTransition.None

/** An exit transition, or none when motion is off. */
@Composable
fun ytExitTransition(transition: ExitTransition): ExitTransition =
    if (LocalYtMotion.current) transition else ExitTransition.None

// ---------------------------------------------------------------------------------------------
// Opening a video

/** How long a just opened video stays marked, i.e. while the player slides in. */
private const val OPENING_MILLIS = 700L

/** URL of the video that is opening right now, or null. */
val LocalYtOpening = compositionLocalOf<String?> { null }

/** Holds the opening video for a moment and clears it again. */
@Composable
fun rememberYtOpening(): MutableState<String?> {
    val opening = remember { mutableStateOf<String?>(null) }
    LaunchedEffect(opening.value) {
        if (opening.value != null) {
            delay(OPENING_MILLIS)
            opening.value = null
        }
    }
    return opening
}

/**
 * Movo: the chosen cover grows a little towards the player while the other covers step back.
 */
@Composable
fun Modifier.ytRecede(url: String?): Modifier {
    val opening = LocalYtOpening.current
    val scale by animateFloatAsState(
        targetValue = when {
            opening == null -> 1f
            opening == url -> 1.04f
            else -> 0.94f
        },
        animationSpec = ytSpec(tween(220)),
        label = "recedeScale",
    )
    val alpha by animateFloatAsState(
        targetValue = if (opening != null && opening != url) 0.5f else 1f,
        animationSpec = ytSpec(tween(220)),
        label = "recedeAlpha",
    )
    return graphicsLayer {
        scaleX = scale
        scaleY = scale
        this.alpha = alpha
    }
}

// ---------------------------------------------------------------------------------------------
// Press

/**
 * A clickable surface in [shape] that gives way while pressed, in the character of the design:
 * Clay squishes (wider and lower), springy designs dip deeper, Editorial barely moves.
 */
@Composable
fun Modifier.ytPressable(shape: Shape, squish: Boolean = false, onClick: () -> Unit): Modifier {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val style = LocalYtMotionStyle.current
    val depth = when (style) {
        YtMotionStyle.SPRINGY -> 0.95f
        YtMotionStyle.CRISP -> 0.985f
        YtMotionStyle.CINEMATIC -> 0.98f
        else -> 0.97f
    }
    val spec: AnimationSpec<Float> = when (style) {
        YtMotionStyle.SPRINGY -> spring(dampingRatio = 0.45f, stiffness = 500f)
        YtMotionStyle.CRISP -> tween(90)
        else -> spring(dampingRatio = 0.8f, stiffness = 600f)
    }
    val press by animateFloatAsState(if (pressed) 1f else 0f, ytSpec(spec), label = "press")
    return graphicsLayer {
        if (squish) {
            scaleX = 1f + 0.04f * press
            scaleY = 1f - 0.07f * press
        } else {
            val scale = 1f - (1f - depth) * press
            scaleX = scale
            scaleY = scale
        }
    }
        .clip(shape)
        .clickable(interactionSource = interaction, indication = LocalIndication.current, onClick = onClick)
}

/** Clay: the squish of [ytPressable] for things with their own click handling. */
@Composable
fun Modifier.ytSquish(interaction: MutableInteractionSource): Modifier {
    val pressed by interaction.collectIsPressedAsState()
    val press by animateFloatAsState(
        if (pressed) 1f else 0f,
        ytSpec(spring(dampingRatio = 0.4f, stiffness = 520f)),
        label = "squish",
    )
    return graphicsLayer {
        scaleX = 1f + 0.05f * press
        scaleY = 1f - 0.08f * press
    }
}

/** The press dip of the design for things with their own click handling and shadow. */
@Composable
fun Modifier.ytPressScale(interaction: MutableInteractionSource): Modifier {
    if (LocalYtMotionStyle.current == YtMotionStyle.SPRINGY) return ytSquish(interaction)
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed) 0.94f else 1f,
        ytSpec(spring(dampingRatio = 0.7f, stiffness = 600f)),
        label = "pressScale",
    )
    return graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

/** A number that counts up to [value] when it first appears or changes. */
@Composable
fun ytCountUp(value: Int): Int {
    val motion = LocalYtMotion.current
    var target by remember { mutableIntStateOf(if (motion) 0 else value) }
    LaunchedEffect(value) { target = value }
    val shown by animateIntAsState(target, ytSpec(tween(900, easing = FastOutSlowInEasing)), label = "countUp")
    return shown
}

// ---------------------------------------------------------------------------------------------
// Appearing

/** Remembers which items of a screen have appeared already, so they only enter once. */
class YtEnterState {
    val seen = mutableSetOf<Any>()
}

@Composable
fun rememberYtEnterState(): YtEnterState = remember { YtEnterState() }

/**
 * Lets an item enter in the character of the design: springy designs grow in, After fades in
 * slowly, Editorial fades in quickly, the others rise a little. Items further down the list
 * follow a bit later.
 */
@Composable
fun Modifier.ytEnter(state: YtEnterState, key: Any, index: Int): Modifier {
    if (!LocalYtMotion.current || key in state.seen) return this
    val style = LocalYtMotionStyle.current
    val progress = remember(key) { Animatable(0f) }
    LaunchedEffect(key) {
        val stagger = when (style) {
            YtMotionStyle.SPRINGY -> 45L
            YtMotionStyle.CINEMATIC -> 80L
            YtMotionStyle.CRISP -> 30L
            else -> 40L
        }
        delay(stagger * index.coerceIn(0, 6))
        progress.animateTo(
            1f,
            when (style) {
                YtMotionStyle.SPRINGY -> spring(dampingRatio = 0.72f, stiffness = 380f)
                YtMotionStyle.CINEMATIC -> tween(480, easing = YtEaseOut)
                YtMotionStyle.CRISP -> tween(200, easing = YtEaseOut)
                else -> tween(280, easing = YtEaseOut)
            },
        )
        state.seen += key
    }
    return graphicsLayer {
        val p = progress.value
        when (style) {
            YtMotionStyle.SPRINGY -> {
                // never from nothing: a block grows in from 94 %
                alpha = p.coerceIn(0f, 1f)
                val scale = 0.94f + 0.06f * p
                scaleX = scale
                scaleY = scale
            }
            YtMotionStyle.CINEMATIC -> {
                alpha = p.coerceIn(0f, 1f)
                val scale = 1.03f - 0.03f * p
                scaleX = scale
                scaleY = scale
            }
            YtMotionStyle.CRISP -> {
                // print: the item is revealed from the left edge, like ink laid down
                clip = true
                shape = YtRevealShape(p.coerceIn(0f, 1f))
            }
            else -> {
                alpha = p.coerceIn(0f, 1f)
                translationY = (1f - p) * 10.dp.toPx()
            }
        }
    }
}

/** A short pop when [active] becomes true, e.g. the icon of a newly selected tab. */
@Composable
fun Modifier.ytSelectBounce(active: Boolean): Modifier {
    if (!LocalYtMotion.current) return this
    val style = LocalYtMotionStyle.current
    val scale = remember { Animatable(1f) }
    LaunchedEffect(active) {
        if (!active) return@LaunchedEffect
        when (style) {
            YtMotionStyle.CRISP, YtMotionStyle.CINEMATIC -> Unit
            YtMotionStyle.SPRINGY -> {
                scale.snapTo(0.72f)
                scale.animateTo(1f, spring(dampingRatio = 0.38f, stiffness = 420f))
            }
            else -> {
                scale.snapTo(0.86f)
                scale.animateTo(1f, spring(dampingRatio = 0.6f, stiffness = 380f))
            }
        }
    }
    return graphicsLayer {
        scaleX = scale.value
        scaleY = scale.value
    }
}

/**
 * Clay and Pop: a soft body poked into shape. When [active] turns true it squashes (wider and
 * flatter) and wobbles back through a few smaller squashes and stretches until it settles.
 */
@Composable
fun Modifier.ytJelly(active: Boolean, amount: Float = 0.1f): Modifier {
    if (!LocalYtMotion.current) return this
    val jelly = remember { Animatable(0f) }
    val initial = remember { booleanArrayOf(true) }
    LaunchedEffect(active) {
        // not on first appearance, only when it becomes active
        if (initial[0]) {
            initial[0] = false
            return@LaunchedEffect
        }
        if (!active) return@LaunchedEffect
        jelly.snapTo(1f)
        jelly.animateTo(0f, spring(dampingRatio = 0.3f, stiffness = 260f))
    }
    return graphicsLayer {
        val j = jelly.value
        scaleX = 1f + amount * j
        scaleY = 1f - amount * j
    }
}

/**
 * Movo: a card pressed off-center tilts under the finger like a physical tile, follows the
 * finger while it moves and springs back flat when let go. Does not take the touch.
 */
@Composable
fun Modifier.ytTilt(maxDegrees: Float = 5f): Modifier {
    if (!LocalYtMotion.current) return this
    val tiltX = remember { Animatable(0f) }
    val tiltY = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    return this
        .pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                fun aim(position: Offset) {
                    val x = (position.x / size.width - 0.5f).coerceIn(-0.5f, 0.5f) * 2f
                    val y = (position.y / size.height - 0.5f).coerceIn(-0.5f, 0.5f) * 2f
                    // the pressed side gives way: the right edge turns away, the top tips back
                    scope.launch { tiltY.animateTo(x * maxDegrees, spring(dampingRatio = 0.8f, stiffness = 700f)) }
                    scope.launch { tiltX.animateTo(-y * maxDegrees, spring(dampingRatio = 0.8f, stiffness = 700f)) }
                }
                aim(down.position)
                while (true) {
                    val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) break
                    aim(change.position)
                }
                scope.launch { tiltX.animateTo(0f, spring(dampingRatio = 0.5f, stiffness = 320f)) }
                scope.launch { tiltY.animateTo(0f, spring(dampingRatio = 0.5f, stiffness = 320f)) }
            }
        }
        .graphicsLayer {
            rotationX = tiltX.value
            rotationY = tiltY.value
            cameraDistance = 14f * density
        }
}

/**
 * Side: a small burst when something is liked: a ring opens around it and six sparks fly out,
 * fading as they go. Only when [active] turns true, not on first appearance.
 */
@Composable
fun Modifier.ytBurst(active: Boolean, color: Color): Modifier {
    if (!LocalYtMotion.current) return this
    val burst = remember { Animatable(1f) }
    val initial = remember { booleanArrayOf(true) }
    LaunchedEffect(active) {
        if (initial[0]) {
            initial[0] = false
            return@LaunchedEffect
        }
        if (!active) return@LaunchedEffect
        burst.snapTo(0f)
        burst.animateTo(1f, tween(460, easing = YtEaseOut))
    }
    return drawBehind {
        val p = burst.value
        if (p >= 1f) return@drawBehind
        val radius = size.minDimension / 2f
        val fade = 1f - p
        drawCircle(
            color.copy(alpha = 0.7f * fade),
            radius = radius * (0.6f + 1.0f * p),
            style = Stroke(width = 2.dp.toPx() * fade),
        )
        repeat(6) { k ->
            val angle = (k * 60f - 90f) * (PI.toFloat() / 180f)
            val distance = radius * (0.8f + 1.2f * p)
            drawCircle(
                color.copy(alpha = fade),
                radius = 2.dp.toPx() * fade,
                center = center + Offset(cos(angle) * distance, sin(angle) * distance),
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Ongoing motion (not started at all when motion is off)

/** YouTube neu: a placeholder in [base] with a soft light sweeping across it; still when motion is off. */
@Composable
fun Modifier.ytShimmer(base: Color): Modifier {
    if (!LocalYtMotion.current) return background(base)
    val transition = rememberInfiniteTransition(label = "shimmer")
    val sweep = transition.animateFloat(
        initialValue = -1f,
        targetValue = 2f,
        animationSpec = infiniteRepeatable(tween(1300, easing = LinearEasing)),
        label = "shimmerSweep",
    )
    return drawBehind {
        drawRect(base)
        val x = size.width * sweep.value
        drawRect(
            Brush.linearGradient(
                listOf(Color.Transparent, Color.White.copy(alpha = 0.12f), Color.Transparent),
                start = Offset(x - size.width * 0.5f, 0f),
                end = Offset(x + size.width * 0.5f, size.height),
            ),
        )
    }
}

/** After: a slow zoom over a poster, back and forth. */
@Composable
fun rememberKenBurns(durationMillis: Int = 18000, zoom: Float = 0.08f): Float {
    if (!LocalYtMotion.current) return 1f
    val transition = rememberInfiniteTransition(label = "kenBurns")
    val value by transition.animateFloat(
        initialValue = 1f,
        targetValue = 1f + zoom,
        animationSpec = infiniteRepeatable(tween(durationMillis, easing = LinearEasing), RepeatMode.Reverse),
        label = "kenBurnsScale",
    )
    return value
}

/** A soft pulse between [min] and 1, e.g. for live dots. */
@Composable
fun rememberPulse(min: Float = 0.35f, durationMillis: Int = 900): Float {
    if (!LocalYtMotion.current) return 1f
    val transition = rememberInfiniteTransition(label = "pulse")
    val value by transition.animateFloat(
        initialValue = 1f,
        targetValue = min,
        animationSpec = infiniteRepeatable(tween(durationMillis, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "pulseValue",
    )
    return value
}

/** Clay: a slow breath of a puffy button. */
@Composable
fun rememberBreath(amount: Float = 0.04f, durationMillis: Int = 2400): Float {
    if (!LocalYtMotion.current) return 1f
    val transition = rememberInfiniteTransition(label = "breath")
    val value by transition.animateFloat(
        initialValue = 1f,
        targetValue = 1f + amount,
        animationSpec = infiniteRepeatable(tween(durationMillis, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "breathValue",
    )
    return value
}

/** Formen: a slow full turn, in degrees. */
@Composable
fun rememberSpin(durationMillis: Int = 20000): Float {
    if (!LocalYtMotion.current) return 0f
    val transition = rememberInfiniteTransition(label = "spin")
    val value by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(durationMillis, easing = LinearEasing)),
        label = "spinValue",
    )
    return value
}

/** Formen: the phase of a travelling wave, 0 to 1. */
@Composable
fun rememberWavePhase(durationMillis: Int = 1600): Float {
    if (!LocalYtMotion.current) return 0f
    val transition = rememberInfiniteTransition(label = "wave")
    val value by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(durationMillis, easing = LinearEasing)),
        label = "wavePhase",
    )
    return value
}

/** A rectangle revealed from the left up to [fraction] of the width. */
private class YtRevealShape(private val fraction: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        Outline.Rectangle(Rect(0f, 0f, size.width * fraction, size.height))
}
