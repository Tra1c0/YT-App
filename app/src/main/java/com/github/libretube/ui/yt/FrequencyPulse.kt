package com.github.libretube.ui.yt

import android.animation.ValueAnimator
import android.view.Choreographer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.center
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.github.libretube.helpers.YtDesign
import com.github.libretube.ui.yt.FrequencyPulseMath as M
import com.github.libretube.ui.yt.sound.YtSound
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * YT: the pulse of the design "Frequency": the sound (YtSound) smoothed once per display frame
 * into a few channels that the design's shapes read while drawing.
 *
 * - Every screen of the design holds the pulse while it is started ([FrequencyPulseHost]); with
 *   no holder (another design, the app in the background) the analysis is switched off
 *   (YtSound.setReactive(false)) and the frame loop stops.
 * - The analysis also stays off while the player page is open but paused, and while the motion
 *   switch (or Android's "remove animations") is off; then every channel rests at 0, the look of
 *   silence.
 * - The channels are plain float states written from a Choreographer callback: readers in the
 *   draw or layout phase redraw, nothing recomposes, and a frame allocates nothing.
 * Main thread only.
 */
object FrequencyPulse {
    /** Breath of the shapes: bass and level (0..1). */
    val energy = mutableFloatStateOf(0f)

    /** Light of the accent glow: the level (0..1). */
    val glow = mutableFloatStateOf(0f)

    /** Short hits, rate-safe (≤ 3 swings a second), for the nav's indicator (0..1). */
    val punch = mutableFloatStateOf(0f)

    /** Highs, for the shimmer on buttons and the time bar (0..1). */
    val shimmer = mutableFloatStateOf(0f)

    /** The three bands for the background aura (0..1). */
    val bass = mutableFloatStateOf(0f)
    val mid = mutableFloatStateOf(0f)
    val high = mutableFloatStateOf(0f)

    /** Degrees the shapes have turned; advances only while there is sound. */
    val turn = mutableFloatStateOf(0f)

    private var holders = 0
    private var running = false
    private var lastNs = 0L
    private var pageSaysPaused = false
    private var clockJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Called by the screens of the design while they are started. */
    fun hold() {
        holders++
        if (holders == 1) {
            // the player page, when one is open, says whether something plays
            clockJob = scope.launch {
                YtPlaybackClock.clock.collect { clock ->
                    pageSaysPaused = clock.videoId != null && !clock.playing
                    update()
                }
            }
        }
        update()
    }

    /** The screen stopped or left. */
    fun release() {
        if (holders == 0) return
        holders--
        if (holders == 0) {
            clockJob?.cancel()
            clockJob = null
            pageSaysPaused = false
        }
        update()
    }

    private val motionAllowed: Boolean
        get() = YtMotionPrefs.enabled && ValueAnimator.areAnimatorsEnabled()

    /** Whether the analysis is wanted now. */
    private val wanted: Boolean
        get() = holders > 0 && motionAllowed && !pageSaysPaused && YtDesign.current() == YtDesign.FREQUENCY

    private fun update() {
        val on = wanted
        YtSound.setReactive(on)
        if (holders > 0 && motionAllowed) {
            // keeps running while paused, so the shapes settle back to rest smoothly
            start()
        } else {
            stop()
            rest()
        }
    }

    private fun start() {
        if (running) return
        running = true
        lastNs = 0L
        Choreographer.getInstance().postFrameCallback(frame)
    }

    private fun stop() {
        if (!running) return
        running = false
        Choreographer.getInstance().removeFrameCallback(frame)
    }

    private fun rest() {
        energy.floatValue = 0f
        glow.floatValue = 0f
        punch.floatValue = 0f
        shimmer.floatValue = 0f
        bass.floatValue = 0f
        mid.floatValue = 0f
        high.floatValue = 0f
    }

    private val frame = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            val dt = if (lastNs == 0L) 0f else (frameTimeNanos - lastNs) / 1_000_000_000f
            lastNs = frameTimeNanos
            step(dt)
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    /** One frame: every channel follows the sound, rate-limited; silence while not wanted. */
    private fun step(dt: Float) {
        val sound = YtSound.frame.value
        val live = wanted && sound.playing
        val level = if (live) sound.level else 0f
        val b = if (live) sound.bass else 0f
        val m = if (live) sound.mid else 0f
        val h = if (live) sound.high else 0f
        val p = if (live) sound.punch else 0f
        // a state written with the value it has does not redraw anything
        energy.floatValue = M.follow(energy.floatValue, M.energyTarget(level, b), dt)
        glow.floatValue = M.follow(glow.floatValue, M.glowTarget(level), dt)
        punch.floatValue = M.follow(punch.floatValue, p, dt)
        shimmer.floatValue = M.follow(shimmer.floatValue, h, dt)
        bass.floatValue = M.follow(bass.floatValue, b, dt)
        mid.floatValue = M.follow(mid.floatValue, m, dt)
        high.floatValue = M.follow(high.floatValue, h, dt)
        val e = energy.floatValue
        if (e > M.REST_EPSILON) turn.floatValue = (turn.floatValue + M.turnStep(e, dt)) % 360f
    }
}

/**
 * Holds the pulse while this screen is started (call once in every screen of the design). The
 * analysis runs only while at least one is held, so it stops in the background.
 */
@Composable
fun FrequencyPulseHost() {
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        var held = false
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> if (!held) {
                    held = true
                    FrequencyPulse.hold()
                }
                Lifecycle.Event.ON_STOP -> if (held) {
                    held = false
                    FrequencyPulse.release()
                }
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            if (held) FrequencyPulse.release()
        }
    }
}

/** True for the palette of Frequency. */
val YtPalette.isFrequency: Boolean get() = signature == YtSignature.FREQUENCY

/** The shape colours of Frequency in turn: aqua, violet, coral, amber. */
fun frequencyTag(palette: YtPalette, index: Int): Color = when (Math.floorMod(index, 4)) {
    0 -> palette.lime
    1 -> palette.lavender
    2 -> palette.accent
    else -> palette.pink
}

/*
 * The modifiers below read the pulse only while drawing (or in the layer block), never while
 * composing. In silence they draw the rest state.
 */

/** Grows by up to [amount] (≤ 6 %) with the bass and level, around its centre. */
fun Modifier.frequencyBreath(amount: Float = M.BREATH_MAX): Modifier = graphicsLayer {
    val s = M.breathScale(FrequencyPulse.energy.floatValue, amount)
    scaleX = s
    scaleY = s
}

/** Turns with the music (and stands still in silence); [factor] scales and reverses it. */
fun Modifier.frequencyTurn(factor: Float = 1f): Modifier = graphicsLayer {
    rotationZ = FrequencyPulse.turn.floatValue * factor
}

/**
 * A soft round light of [color] behind the element, [reach] times its half size; its alpha goes
 * from [rest] (silence) to [peak] with the level.
 */
fun Modifier.frequencyGlow(
    color: Color,
    reach: Float = 1.25f,
    rest: Float = M.GLOW_REST,
    peak: Float = M.GLOW_PEAK,
): Modifier = drawWithCache {
    val radius = max(size.width, size.height) / 2f * reach
    val brush = Brush.radialGradient(listOf(color, color.copy(alpha = 0f)), size.center, radius.coerceAtLeast(1f))
    onDrawBehind {
        drawCircle(brush, radius, center, alpha = M.glowAlpha(FrequencyPulse.glow.floatValue, rest, peak))
    }
}

/**
 * The aura behind a Frequency screen: two large soft lights (coral and violet) and a faint aqua
 * one, drifting with bass, mids and highs and brightening with the level. At rest they sit still
 * as a calm gradient over the night.
 */
fun Modifier.frequencyAura(palette: YtPalette): Modifier = drawWithCache {
    val w = size.width
    val h = size.height
    val r1 = max(w, h) * 0.55f
    val r2 = max(w, h) * 0.5f
    val r3 = max(w, h) * 0.35f
    val coral = Brush.radialGradient(listOf(palette.accent, palette.accent.copy(alpha = 0f)), Offset.Zero, r1.coerceAtLeast(1f))
    val violet = Brush.radialGradient(listOf(palette.lavender, palette.lavender.copy(alpha = 0f)), Offset.Zero, r2.coerceAtLeast(1f))
    val aqua = Brush.radialGradient(listOf(palette.lime, palette.lime.copy(alpha = 0f)), Offset.Zero, r3.coerceAtLeast(1f))
    onDrawBehind {
        drawRect(palette.background)
        val bass = FrequencyPulse.bass.floatValue
        val dx = M.auraDx(FrequencyPulse.mid.floatValue, FrequencyPulse.high.floatValue) * w
        val dy = M.auraDy(bass) * h
        val grow = M.auraGrow(bass)
        val a = M.auraAlpha(FrequencyPulse.glow.floatValue)
        // the brushes are centred at 0,0 and moved by translate: nothing is allocated per frame
        translate(w * 0.92f + dx, h * 0.04f + dy) {
            drawCircle(coral, r1 * grow, Offset.Zero, alpha = a * 0.7f)
        }
        translate(w * 0.05f - dx, h * 0.42f + dy * 0.6f) {
            drawCircle(violet, r2 * grow, Offset.Zero, alpha = a * 0.55f)
        }
        translate(w * 0.75f - dx * 0.5f, h * 0.9f - dy * 0.4f) {
            drawCircle(aqua, r3, Offset.Zero, alpha = a * 0.35f)
        }
    }
}

/**
 * A scalloped "cookie" of [color] behind the element (Formen's shape), [lobes] scallops, [rest]
 * deep in silence; it deepens with the bass and turns with the music. [ring] > 0 draws only its
 * outline, that many px wide.
 */
fun Modifier.frequencyCookie(color: Color, lobes: Int = 9, rest: Float = 0.07f, ring: Float = 0f): Modifier = drawWithCache {
    val path = Path()
    val stroke = if (ring > 0f) Stroke(ring) else null
    onDrawBehind {
        val e = FrequencyPulse.energy.floatValue
        val depth = M.cookieDepth(rest, e)
        val turn = Math.toRadians(FrequencyPulse.turn.floatValue.toDouble()).toFloat()
        val outer = min(size.width, size.height) / 2f - (stroke?.width ?: 0f) / 2f
        cookiePath(path, center, outer, lobes, depth, turn)
        if (stroke != null) drawPath(path, color, style = stroke) else drawPath(path, color)
    }
}

/** Builds a cookie of [lobes] around [center] with outer [radius] into [path] (reset first). */
fun cookiePath(path: Path, center: Offset, radius: Float, lobes: Int, depth: Float, turn: Float) {
    path.reset()
    val steps = (lobes * 10).coerceAtLeast(24)
    for (i in 0..steps) {
        val angle = M.TAU * i / steps
        val r = radius * M.cookieRadius(angle, lobes, depth, turn)
        val x = center.x + r * sin(angle)
        val y = center.y - r * cos(angle)
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
}

/**
 * A soft band of light that sweeps across the element with the highs (nothing in silence),
 * clipped to [shape].
 */
fun Modifier.frequencyShimmer(color: Color = Color.White, shape: Shape = RectangleShape): Modifier = drawWithCache {
    val band = size.width * 0.35f
    val brush = Brush.horizontalGradient(
        listOf(color.copy(alpha = 0f), color, color.copy(alpha = 0f)),
        startX = 0f,
        endX = band.coerceAtLeast(1f),
    )
    val outline = shape.createOutline(size, layoutDirection, this)
    val clip = Path().apply { addOutline(outline) }
    onDrawWithContent {
        drawContent()
        val a = M.shimmerAlpha(FrequencyPulse.shimmer.floatValue)
        if (a <= 0.003f) return@onDrawWithContent
        val x = -band + (size.width + band) * M.shimmerAt(FrequencyPulse.turn.floatValue)
        clipPath(clip) {
            translate(x, 0f) { drawRect(brush, Offset.Zero, androidx.compose.ui.geometry.Size(band, size.height), alpha = a) }
        }
    }
}

/**
 * Brightens the element softly on hits (the rate-safe punch), clipped to [shape]: a white veil
 * of at most 18 %, never changing more than 3 times a second.
 */
fun Modifier.frequencyPulseLift(shape: Shape): Modifier = drawWithCache {
    val outline: Outline = shape.createOutline(size, layoutDirection, this)
    onDrawWithContent {
        drawContent()
        val lift = M.pulseLift(FrequencyPulse.punch.floatValue)
        if (lift > 0.003f) drawOutline(outline, Color.White, alpha = lift)
    }
}

/** A rim of [color] along [shape], [width] px, whose alpha follows the level ([rest] in silence). */
fun Modifier.frequencyRim(color: Color, shape: Shape, width: Float, rest: Float = 0.35f): Modifier = drawWithCache {
    val outline = shape.createOutline(
        androidx.compose.ui.geometry.Size((size.width - width).coerceAtLeast(0f), (size.height - width).coerceAtLeast(0f)),
        layoutDirection,
        this,
    )
    val stroke = Stroke(width)
    onDrawWithContent {
        drawContent()
        val g = FrequencyPulse.glow.floatValue
        translate(width / 2f, width / 2f) {
            drawOutline(outline, color, alpha = rest + (1f - rest) * M.clamp01(g), style = stroke)
        }
    }
}

/** Draws the rate-safe pulse as a white veil over the current drawing (for Canvas users). */
fun DrawScope.frequencyVeil(outline: Outline) {
    val lift = M.pulseLift(FrequencyPulse.punch.floatValue)
    if (lift > 0.003f) drawOutline(outline, Color.White, alpha = lift)
}
