package com.github.libretube.ui.yt

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.util.fastRoundToInt
import androidx.compose.ui.util.lerp
import com.github.libretube.ui.yt.liquid.DampedDragAnimation
import com.github.libretube.ui.yt.liquid.InteractiveHighlight
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.highlight.HighlightStyle
import com.kyant.backdrop.isRenderEffectSupported
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import kotlin.math.abs
import kotlin.math.floor

/*
 * YT: liquid glass for the screens of the glass designs (Glas, Frost): glass surfaces that
 * blur and refract what lies below them, a selection drop for segmented tabs, glass that
 * swells under the finger, and slowly drifting light for the glass to catch.
 */

/** The tint of a glass surface: lighter over blur, nearly solid where blur is unavailable. */
fun glassTint(tint: Color): Color =
    if (isRenderEffectSupported()) tint else tint.copy(alpha = (tint.alpha + 0.45f).coerceAtMost(0.92f))

/**
 * Glass over [backdrop]: blurred, more saturated, refracted towards the rim, with a light
 * catching its edge. [swell] (0 to 1, e.g. while pressed) makes it swell and refract more.
 */
@Composable
fun Modifier.liquidGlass(
    backdrop: Backdrop,
    shape: Shape,
    tint: Color,
    shadowColor: Color?,
    blur: Dp = 10.dp,
    refraction: Dp = 14.dp,
    swell: () -> Float = { 0f },
    /** 0 to 1: the frost clears, the glass turns almost transparent (Frost: a warm finger). */
    clear: () -> Float = { 0f },
): Modifier {
    val tilt = LocalYtTilt.current
    return liquidGlassWithLight(backdrop, shape, tint, shadowColor, blur, refraction, swell, clear) { ytLightAngle(tilt.value) }
}

private fun Modifier.liquidGlassWithLight(
    backdrop: Backdrop,
    shape: Shape,
    tint: Color,
    shadowColor: Color?,
    blur: Dp,
    refraction: Dp,
    swell: () -> Float,
    clear: () -> Float,
    light: () -> Float,
): Modifier = this.drawBackdrop(
    backdrop = backdrop,
    shape = { shape },
    effects = {
        vibrancy()
        val c = clear()
        blur(blur.toPx() * (1f - 0.92f * c))
        val s = swell()
        lens(refraction.toPx() * (1f + 0.4f * s) * (1f - 0.6f * c), (refraction * 1.5f).toPx() * (1f + 0.6f * s) * (1f - 0.6f * c))
    },
    highlight = { Highlight(style = HighlightStyle.Default(angle = light())) },
    shadow = shadowColor?.let { color -> { Shadow(radius = 16.dp, color = color) } },
    layerBlock = {
        val scale = 1f + 0.04f * swell()
        scaleX = scale
        scaleY = scale
    },
    onDrawSurface = { drawRect(glassTint(tint), alpha = 1f - 0.85f * clear()) },
)

/**
 * Frosted sage glass of the Frost design over [backdrop]; [clear] (0 to 1) thaws it. Without a
 * backdrop (classic screens) a milky fill with a light edge.
 */
@Composable
fun Modifier.frostGlass(backdrop: Backdrop?, shape: Shape, clear: () -> Float = { 0f }): Modifier =
    if (backdrop != null) {
        liquidGlass(backdrop, shape, Color(0x2EFFFFFF), Color(0x2E000000), blur = 16.dp, refraction = 10.dp, clear = clear)
    } else {
        this
            .clip(shape)
            .background(Brush.verticalGradient(listOf(Color(0x40FFFFFF), Color(0x1FFFFFFF))))
            .border(1.dp, Color(0x59FFFFFF), shape)
    }

/**
 * 0 to 1 while [interaction] is pressed: the frost thaws under a warm finger, slowly, and
 * freezes over again slower still. 0 when motion is off.
 */
@Composable
fun rememberThaw(interaction: MutableInteractionSource): State<Float> {
    val pressed by interaction.collectIsPressedAsState()
    val warm = pressed && LocalYtMotion.current
    return animateFloatAsState(
        if (warm) 1f else 0f,
        if (warm) tween<Float>(650, easing = YtEaseOut) else tween<Float>(1100, easing = YtEaseInOut),
        label = "thaw",
    )
}

/** 0 to 1 while [interaction] is pressed, with a soft spring; 0 when motion is off. */
@Composable
fun rememberSwell(interaction: MutableInteractionSource): State<Float> {
    val pressed by interaction.collectIsPressedAsState()
    return animateFloatAsState(
        if (pressed && LocalYtMotion.current) 1f else 0f,
        spring(dampingRatio = 0.55f, stiffness = 380f),
        label = "swell",
    )
}

/**
 * Soft light drifting slowly behind glass, so it has something to catch and bend. Still when
 * motion is off.
 */
@Composable
fun LiquidLight(colors: List<Color>, modifier: Modifier = Modifier) {
    val motion = LocalYtMotion.current
    val phase = if (motion) {
        val transition = rememberInfiniteTransition(label = "liquidLight")
        transition.animateFloat(0f, 1f, infiniteRepeatable(tween(26000, easing = LinearEasing), RepeatMode.Reverse), label = "lightPhase")
    } else {
        remember { androidx.compose.runtime.mutableFloatStateOf(0.35f) }
    }
    Canvas(modifier) {
        val p = phase.value
        colors.forEachIndexed { i, color ->
            val t = (p + i * 0.37f) * 2f * Math.PI.toFloat()
            val cx = size.width * (0.2f + 0.6f * ((i * 0.53f + 0.5f + 0.35f * kotlin.math.sin(t)) % 1f))
            val cy = size.height * (0.12f + 0.76f * ((i * 0.29f + 0.4f + 0.3f * kotlin.math.cos(t * 0.8f)) % 1f))
            val radius = size.minDimension * (0.42f + 0.1f * i)
            drawCircle(
                Brush.radialGradient(listOf(color, color.copy(alpha = 0f)), center = Offset(cx, cy), radius = radius),
                radius = radius,
                center = Offset(cx, cy),
            )
        }
    }
}

/** How a [LiquidSegments] control looks. */
class LiquidSegmentsLook(
    val height: Dp,
    val gap: Dp,
    val tile: Color,
    val pill: Color,
    val shadow: Color,
    val text: Color,
    val lensText: Color,
    val font: FontFamily,
    val size: TextUnit,
    val activeSize: TextUnit,
)

/**
 * Segmented tabs with a drop of glass as the selection: it rests as a bright pill, can be
 * dragged across the segments, turns clear and magnifies them while held, stretches with its
 * speed and settles with a soft wobble.
 */
@Composable
fun LiquidSegments(
    backdrop: Backdrop,
    look: LiquidSegmentsLook,
    keys: List<String>,
    labels: List<String>,
    weights: List<Float>,
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val count = keys.size
    val index = keys.indexOf(selected).coerceAtLeast(0)
    val scope = rememberCoroutineScope()
    val motion = LocalYtMotion.current
    val density = LocalDensity.current
    val shape = RoundedCornerShape(50)
    val haptics = rememberYtHaptics()

    BoxWithConstraints(modifier) {
        val gapPx = with(density) { look.gap.toPx() }
        val heightPx = with(density) { look.height.roundToPx() }
        // The width can be 0 for a frame (e.g. while a row animates in), so nothing here
        // may go below 0: a negative segment width crashes Constraints.fixed.
        val maxWidth = if (constraints.hasBoundedWidth) constraints.maxWidth else constraints.minWidth
        val free = (maxWidth - gapPx * (count - 1)).coerceAtLeast(0f)
        val total = weights.sum().takeIf { it > 0f } ?: 1f
        val widths = weights.map { (free * it / total).coerceAtLeast(0f) }
        val starts = widths.runningFold(0f) { x, w -> x + w + gapPx }
        val step = free / count + gapPx

        val gesture = remember { FloatArray(2) }
        val drop = remember(scope, count, motion, step) {
            DampedDragAnimation(
                animationScope = scope,
                initialValue = index.toFloat(),
                valueRange = 0f..(count - 1).toFloat(),
                visibilityThreshold = 0.001f,
                initialScale = 1f,
                pressedScale = 1.16f,
                motion = motion,
                onDragStarted = {
                    gesture[0] = targetValue.fastRoundToInt().toFloat()
                    gesture[1] = 0f
                },
                onDragStopped = {
                    val target = targetValue.fastRoundToInt().fastCoerceIn(0, count - 1)
                    animateToValue(target.toFloat())
                    if (target != gesture[0].toInt()) {
                        haptics.tick()
                        onSelect(keys[target])
                    }
                },
                onDrag = { _, dragAmount ->
                    gesture[1] += abs(dragAmount.x)
                    updateValue(targetValue + dragAmount.x / step)
                },
            )
        }
        LaunchedEffect(drop, index) {
            if (abs(drop.targetValue - index) > 0.001f) drop.animateToValue(index.toFloat())
        }
        fun activeness(i: Int) = (1f - abs(drop.value - i)).fastCoerceIn(0f, 1f)
        fun lens(): Pair<Float, Float> {
            val v = drop.value.fastCoerceIn(0f, (count - 1).toFloat())
            val i = floor(v).toInt().coerceAtMost(count - 1)
            val next = (i + 1).coerceAtMost(count - 1)
            val f = v - i
            return lerp(starts[i], starts[next], f) to lerp(widths[i], widths[next], f)
        }
        val highlight = remember(scope) {
            InteractiveHighlight(scope) { size, _ ->
                val (x, w) = lens()
                Offset(x + w / 2f, size.height / 2f)
            }
        }

        val select: (Int) -> Unit = { i ->
            if (keys[i] != selected) haptics.tick()
            drop.animateToValue(i.toFloat())
            onSelect(keys[i])
        }

        // 1: glass segments over the screen, labels at rest
        SegmentStrip(count, widths, starts, heightPx) {
            Box(
                Modifier
                    .fillMaxSize()
                    .liquidGlass(backdrop, shape, look.tile, null, blur = 8.dp, refraction = 8.dp),
            )
        }
        SegmentLabels(widths, starts, heightPx, labels, look, { look.text }, { 1f - activeness(it) }, bold = false, onClick = select)

        // 2: the segments in the lens color, seen through the drop while held
        val segmentsBackdrop = rememberLayerBackdrop()
        Box(
            Modifier
                .matchParentSizeCompat(heightPx)
                .clearAndSetSemantics {}
                .alpha(0f)
                .layerBackdrop(segmentsBackdrop),
        ) {
            SegmentStrip(count, widths, starts, heightPx) {
                Box(Modifier.fillMaxSize().liquidGlass(backdrop, shape, look.tile, null, blur = 8.dp, refraction = 8.dp))
            }
            SegmentLabels(widths, starts, heightPx, labels, look, { look.lensText }, { 1f }, bold = true, onClick = null)
        }

        // 3: the drop
        Box(
            Modifier
                .layout { measurable, constraints ->
                    val (x, w) = lens()
                    val placeable = measurable.measure(Constraints.fixed(w.fastRoundToInt().coerceAtLeast(0), heightPx))
                    layout(maxWidth, heightPx) { placeable.place(x.fastRoundToInt(), 0) }
                }
                .then(highlight.gestureModifier)
                .then(drop.modifier)
                .drawBackdrop(
                    backdrop = rememberCombinedBackdrop(backdrop, segmentsBackdrop),
                    shape = { shape },
                    effects = {
                        val progress = drop.pressProgress
                        lens(8.dp.toPx() * progress, 12.dp.toPx() * progress, chromaticAberration = true)
                    },
                    highlight = { Highlight.Default.copy(alpha = 0.6f + 0.4f * drop.pressProgress) },
                    shadow = { Shadow(radius = 10.dp, color = look.shadow) },
                    innerShadow = { InnerShadow(radius = 6.dp * drop.pressProgress, alpha = drop.pressProgress) },
                    layerBlock = {
                        var x = drop.scaleX
                        var y = drop.scaleY
                        val velocity = drop.velocity / 10f
                        x /= 1f - (velocity * 0.75f).fastCoerceIn(-0.2f, 0.2f)
                        y *= 1f - (velocity * 0.25f).fastCoerceIn(-0.2f, 0.2f)
                        scaleX = x
                        scaleY = y
                    },
                    onDrawSurface = { drawRect(look.pill, alpha = 1f - 0.85f * drop.pressProgress) },
                )
                .then(highlight.modifier),
        )

        // 4: the selected label on the drop at rest
        Box(Modifier.clearAndSetSemantics {}) {
            SegmentLabels(widths, starts, heightPx, labels, look, { look.text }, { activeness(it) * (1f - drop.pressProgress) }, bold = true, onClick = null)
        }
    }
}

/** Fills the width of the control at its height (the box has no size of its own). */
private fun Modifier.matchParentSizeCompat(heightPx: Int): Modifier = this.layout { measurable, constraints ->
    val width = if (constraints.hasBoundedWidth) constraints.maxWidth else constraints.minWidth
    val placeable = measurable.measure(Constraints.fixed(width, heightPx))
    layout(placeable.width, placeable.height) { placeable.place(0, 0) }
}

@Composable
private fun SegmentStrip(count: Int, widths: List<Float>, starts: List<Float>, heightPx: Int, content: @Composable (Int) -> Unit) {
    Layout(
        content = { repeat(count) { i -> Box(propagateMinConstraints = true) { content(i) } } },
    ) { measurables, constraints ->
        val placeables = measurables.mapIndexed { i, m -> m.measure(Constraints.fixed(widths[i].fastRoundToInt().coerceAtLeast(0), heightPx)) }
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else constraints.minWidth
        layout(width, heightPx) {
            placeables.forEachIndexed { i, p -> p.place(starts[i].fastRoundToInt(), 0) }
        }
    }
}

@Composable
private fun SegmentLabels(
    widths: List<Float>,
    starts: List<Float>,
    heightPx: Int,
    labels: List<String>,
    look: LiquidSegmentsLook,
    color: (Int) -> Color,
    alpha: (Int) -> Float,
    bold: Boolean,
    onClick: ((Int) -> Unit)?,
) {
    SegmentStrip(labels.size, widths, starts, heightPx) { i ->
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (onClick != null) Modifier.clickable(interactionSource = null, indication = null) { onClick(i) }
                    else Modifier,
                ),
        ) {
            Text(
                text = labels[i],
                color = color(i),
                maxLines = 1,
                softWrap = false,
                style = TextStyle(
                    fontFamily = look.font,
                    fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
                    fontSize = if (bold) look.activeSize else look.size,
                ),
                modifier = Modifier
                    .wrapContentWidth(unbounded = true)
                    .graphicsLayer { this.alpha = alpha(i) },
            )
        }
    }
}
