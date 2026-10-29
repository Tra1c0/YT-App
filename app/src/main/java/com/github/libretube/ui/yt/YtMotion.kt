package com.github.libretube.ui.yt

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
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
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.github.libretube.helpers.PreferenceHelper
import kotlinx.coroutines.delay

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
        YtMotionStyle.SPRINGY -> spring(dampingRatio = 0.62f, stiffness = 380f)
        YtMotionStyle.CINEMATIC -> tween(520, easing = FastOutSlowInEasing)
        YtMotionStyle.CRISP -> tween(160, easing = LinearOutSlowInEasing)
        YtMotionStyle.SOFT -> spring(dampingRatio = 0.85f, stiffness = 240f)
        YtMotionStyle.CALM -> tween(240, easing = FastOutSlowInEasing)
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
            YtMotionStyle.SPRINGY -> 55L
            YtMotionStyle.CINEMATIC -> 110L
            YtMotionStyle.CRISP -> 30L
            else -> 45L
        }
        delay(stagger * index.coerceIn(0, 8))
        progress.animateTo(
            1f,
            when (style) {
                YtMotionStyle.SPRINGY -> spring(dampingRatio = 0.6f, stiffness = 260f)
                YtMotionStyle.CINEMATIC -> tween(700, easing = FastOutSlowInEasing)
                YtMotionStyle.CRISP -> tween(180, easing = LinearOutSlowInEasing)
                else -> tween(360, easing = FastOutSlowInEasing)
            },
        )
        state.seen += key
    }
    return graphicsLayer {
        val p = progress.value
        alpha = p.coerceIn(0f, 1f)
        when (style) {
            YtMotionStyle.SPRINGY -> {
                val scale = 0.86f + 0.14f * p
                scaleX = scale
                scaleY = scale
            }
            YtMotionStyle.CINEMATIC -> {
                val scale = 1.03f - 0.03f * p
                scaleX = scale
                scaleY = scale
            }
            YtMotionStyle.CRISP -> translationY = (1f - p) * 6.dp.toPx()
            else -> translationY = (1f - p) * 14.dp.toPx()
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

// ---------------------------------------------------------------------------------------------
// Ongoing motion (not started at all when motion is off)

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
