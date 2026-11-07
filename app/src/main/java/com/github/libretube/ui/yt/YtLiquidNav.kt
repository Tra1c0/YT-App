package com.github.libretube.ui.yt

import androidx.compose.runtime.derivedStateOf
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.IntSize
import com.github.libretube.ui.yt.mini.YtBleedImage
import com.github.libretube.ui.yt.mini.YtGlassLayout
import com.github.libretube.ui.yt.mini.YtGlassMath
import com.github.libretube.ui.yt.mini.YtGlassSlab
import com.github.libretube.ui.yt.mini.ytGlassSlabShape
import com.github.libretube.ui.yt.mini.ytMiniHover
import kotlinx.coroutines.delay
import androidx.compose.ui.unit.coerceIn
import androidx.compose.runtime.getValue
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.util.fastRoundToInt
import androidx.compose.ui.util.lerp
import com.github.libretube.helpers.YtDesign
import com.github.libretube.ui.yt.liquid.DampedDragAnimation
import com.github.libretube.ui.yt.liquid.InteractiveHighlight
import com.github.libretube.ui.yt.mini.ytDockTop
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
import kotlin.math.roundToInt

/*
 * YT: the liquid glass navigation of Glas and Frost. The bar is real glass over the screen
 * that scrolls below it (blur, refraction at the rim, more saturated colors). The selection
 * is a drop of glass: it rests as the white pill of the reference, can be dragged across the
 * tabs, stretches with its speed, swells and turns clear while held, magnifying the tabs
 * below it, and settles with a soft wobble. The tabs make room for it continuously, so the
 * active tab grows out of its circle as the drop arrives.
 */

/** How a design draws its liquid bar, measured in the references (docs/DESIGN-MASSE.md). */
private class LiquidLook(
    val margin: PaddingValues,
    /** Null: the bar spans the width and the tabs spread out; else the gap between tabs. */
    val gap: Dp?,
    val barHeight: Dp,
    val itemHeight: Dp,
    val activeWidth: Dp,
    val inactiveWidth: Dp,
    val blur: Dp,
    /** Over the blurred screen, and without blur (before Android 12). */
    val tint: Color,
    val tintSolid: Color,
    val shadow: Color,
    val pill: Color,
    val tile: Color,
    val tileBorder: Color,
    val active: Color,
    val inactive: Color,
    /** The tabs as seen through the drop while it is held. */
    val lensContent: Color,
    val font: FontFamily,
    val activeLabel: TextUnit,
    val activeWeight: FontWeight,
    val activeIcon: Dp,
    val inactiveIcon: Dp,
    /** Glas shows small labels under the inactive icons, Frost only icons. */
    val inactiveLabel: TextUnit?,
    val labelGap: Dp,
)

private fun liquidLook(design: YtDesign, palette: YtPalette): LiquidLook = when (design) {
    YtDesign.FROST -> LiquidLook(
        margin = PaddingValues(top = 6.dp, bottom = 12.dp),
        gap = 6.dp,
        barHeight = 60.dp,
        itemHeight = 48.dp,
        activeWidth = 118.dp,
        inactiveWidth = 48.dp,
        blur = 12.dp,
        tint = Color(0x9E1F2A26),
        tintSolid = Color(0xF21F2A26),
        shadow = Color(0x59000000),
        pill = Color.White,
        tile = Color(0x1AFFFFFF),
        tileBorder = Color(0x33FFFFFF),
        active = palette.ink,
        inactive = Color.White,
        lensContent = palette.accent,
        font = YtFonts.jakarta,
        activeLabel = 14.sp,
        activeWeight = FontWeight.SemiBold,
        activeIcon = 22.dp,
        inactiveIcon = 22.dp,
        inactiveLabel = null,
        labelGap = 8.dp,
    )
    else -> LiquidLook(
        margin = PaddingValues(start = 13.dp, end = 11.dp, top = 6.dp, bottom = 10.dp),
        gap = null,
        barHeight = 59.dp,
        itemHeight = 49.dp,
        activeWidth = 107.dp,
        inactiveWidth = 49.dp,
        blur = 8.dp,
        tint = ytDayNight(Color(0x61FFFFFF), Color(0x99183230)),
        tintSolid = ytDayNight(Color(0xD9FFFFFF), Color(0xE61F3A38)),
        shadow = ytDayNight(Color(0x4D5C8C85), Color(0x66000000)),
        pill = ytDayNight(Color(0xF2FFFFFF), Color(0xF22E5452)),
        tile = ytDayNight(Color(0x40FFFFFF), Color(0x1FCFEDE7)),
        tileBorder = ytDayNight(Color(0xB3FFFFFF), Color(0x33CFEDE7)),
        active = palette.text,
        inactive = palette.text,
        lensContent = ytDayNight(Color(0xFF0A8F7F), Color(0xFF7FE0D2)),
        font = YtFonts.googleSans,
        activeLabel = 15.sp,
        activeWeight = FontWeight.Bold,
        activeIcon = 24.dp,
        inactiveIcon = 22.dp,
        inactiveLabel = 11.5.sp,
        labelGap = 9.dp,
    )
}

/** Where the tabs sit for a (fractional) selected index, in px. */
private class LiquidSlots(
    private val count: Int,
    private val active: Float,
    private val inactive: Float,
    private val pad: Float,
    private val gap: Float,
    private val value: () -> Float,
) {
    /** 1 for the selected tab, falling to 0 one tab away. */
    fun activeness(i: Int): Float = (1f - abs(value() - i)).fastCoerceIn(0f, 1f)

    fun width(i: Int): Float = inactive + (active - inactive) * activeness(i)

    fun x(i: Int): Float {
        var x = pad
        for (j in 0 until i) x += width(j) + gap
        return x
    }

    fun lensX(): Float {
        val v = value().fastCoerceIn(0f, (count - 1).toFloat())
        val i = floor(v).toInt().coerceAtMost(count - 1)
        val next = (i + 1).coerceAtMost(count - 1)
        return lerp(x(i), x(next), v - i)
    }

    fun lensWidth(): Float {
        val v = value().fastCoerceIn(0f, (count - 1).toFloat())
        val i = floor(v).toInt().coerceAtMost(count - 1)
        val next = (i + 1).coerceAtMost(count - 1)
        return lerp(width(i), width(next), v - i)
    }
}

@Composable
fun LiquidNav(
    design: YtDesign,
    palette: YtPalette,
    items: List<YtNavItem>,
    state: YtNavState,
    backdrop: Backdrop,
    onSelect: (Int) -> Unit,
) {
    val look = remember(design, palette) { liquidLook(design, palette) }
    val motion = LocalYtMotion.current
    val density = LocalDensity.current
    val count = items.size
    val index = items.indexOfFirst { it.id == state.selected }.coerceAtLeast(0)
    val scope = rememberCoroutineScope()
    val tilt = LocalYtTilt.current

    val itemHeightPx = with(density) { look.itemHeight.roundToPx() }
    val pad = (look.barHeight - look.itemHeight) / 2
    // the tabs as one strip: gap fixed (Frost) or spread over the bar (Glas)
    val fixedGap = look.gap
    val barWidth: Dp? = fixedGap?.let {
        pad * 2 + look.activeWidth + look.inactiveWidth * (count - 1) + it * (count - 1)
    }

    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .padding(look.margin),
        contentAlignment = Alignment.Center,
    ) {
        val width = barWidth ?: maxWidth
        val gap = fixedGap ?: ((width - pad * 2 - look.activeWidth - look.inactiveWidth * (count - 1)) / (count - 1))
        val step = with(density) { ((look.activeWidth + look.inactiveWidth) / 2 + gap).toPx() }

        // where a drag started and how far it went: a tap on the drop selects its tab again
        val gesture = remember { FloatArray(2) }
        val drop = remember(scope, count, motion, step) {
            DampedDragAnimation(
                animationScope = scope,
                initialValue = index.toFloat(),
                valueRange = 0f..(count - 1).toFloat(),
                visibilityThreshold = 0.001f,
                initialScale = 1f,
                pressedScale = 1.22f,
                motion = motion,
                onDragStarted = {
                    gesture[0] = targetValue.fastRoundToInt().toFloat()
                    gesture[1] = 0f
                },
                onDragStopped = {
                    val target = targetValue.fastRoundToInt().fastCoerceIn(0, count - 1)
                    animateToValue(target.toFloat())
                    val tapped = gesture[1] < step / 8f
                    if (target != gesture[0].toInt() || tapped) onSelect(items[target].id)
                },
                onDrag = { _, dragAmount ->
                    gesture[1] += abs(dragAmount.x)
                    updateValue(targetValue + dragAmount.x / step)
                },
            )
        }
        // selection from elsewhere (back navigation, links): the drop flows there
        LaunchedEffect(drop, index) {
            if (abs(drop.targetValue - index) > 0.001f) drop.animateToValue(index.toFloat())
        }

        val slots = with(density) {
            remember(drop, width, gap) {
                LiquidSlots(count, look.activeWidth.toPx(), look.inactiveWidth.toPx(), pad.toPx(), gap.toPx()) { drop.value }
            }
        }
        val barPx = with(density) { look.barHeight.toPx() }
        val highlight = remember(scope, slots, barPx) {
            InteractiveHighlight(scope) { size, _ ->
                // on the tabs, also while the Glas slab makes the bar taller
                Offset(slots.lensX() + slots.lensWidth() / 2f, size.height - barPx / 2f)
            }
        }
        val glassOk = isRenderEffectSupported()
        val barShape = RoundedCornerShape(50)
        // a mini player set on the bar: the glass squares its top corners as it docks, both are
        // one pane. The backdrop takes its outline from composition, so the corner composes
        // again, but only when it changes by a whole pixel
        val roundPx = with(density) { (look.barHeight / 2).toPx() }
        // Glas does not square its corners: the bar grows into one slab with the player instead
        val slab = design == YtDesign.GLASS
        val stacks = remember(design) { YtMiniPlayer.stacks(design) && !slab }
        val dockTopPx by remember(roundPx, stacks) { derivedStateOf { ytDockTop(roundPx, stacks).roundToInt() } }
        val top = with(density) { dockTopPx.toDp() }.coerceIn(0.dp, look.barHeight / 2)
        val surfaceShape = RoundedCornerShape(
            topStart = top,
            topEnd = top,
            bottomEnd = look.barHeight / 2,
            bottomStart = look.barHeight / 2,
        )

        fun select(i: Int) {
            drop.animateToValue(i.toFloat())
            onSelect(items[i].id)
        }

        // Glas: the slab behind the docked player, its handle, the video's colour in it, the
        // bulge towards a video dragged near and the ripple of one dropped in
        val slabShape = remember(barPx, density) { ytGlassSlabShape(barPx, with(density) { YtGlassLayout.SLAB_CORNER.dp.toPx() }) }
        val topMarginPx = with(density) { look.margin.calculateTopPadding().toPx() }
        // px per dp, under its own name: the glass's lambdas have a density of their own
        val dpScale = density.density
        val bleedImage = remember { YtBleedImage() }
        val ripple = remember { Animatable(0f) }
        if (slab) {
            DisposableEffect(Unit) {
                YtGlassSlab.navDraws = true
                onDispose { YtGlassSlab.navDraws = false }
            }
            val accepted = YtDock.accepted
            var seenDrop by remember { mutableIntStateOf(accepted) }
            LaunchedEffect(accepted) {
                if (accepted == seenDrop) return@LaunchedEffect
                seenDrop = accepted
                if (!motion) return@LaunchedEffect
                // the droplet reaches the glass a little after the drop (YtGlassOver)
                delay(380)
                ripple.snapTo(0f)
                ripple.animateTo(1f, tween(140))
                ripple.animateTo(0f, spring(dampingRatio = 0.35f, stiffness = 260f))
            }
        }

        // 1: the bar, glass over the screen, with the tabs at rest. Glas: grown upward into the
        // slab behind the docked player (drawn above the bar's own view, its height unchanged)
        Box(
            Modifier
                .width(width)
                .height(look.barHeight)
                .then(if (slab) Modifier.glassSlabGrowth(topMarginPx) else Modifier)
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { if (slab) slabShape else surfaceShape },
                    effects = {
                        vibrancy()
                        blur(look.blur.toPx())
                        // a video dragged near bends the glass more, one dropped in ripples it
                        val bend = if (slab) 1f + 0.35f * ytMiniHover(dpScale) + 0.5f * ripple.value else 1f
                        lens(20.dp.toPx() * bend, 22.dp.toPx() * bend)
                    },
                    highlight = { Highlight(style = HighlightStyle.Default(angle = ytLightAngle(tilt.value))) },
                    shadow = { Shadow(radius = 22.dp, color = look.shadow) },
                    layerBlock = {
                        val scale = lerp(1f, 1f + 10.dp.toPx() / size.width, drop.pressProgress)
                        // the slab bulges up towards a video dragged near, from the bar
                        val bulge = if (slab) 1f + 0.03f * ytMiniHover(dpScale) else 1f
                        scaleX = scale * bulge
                        scaleY = scale * bulge
                        if (slab) transformOrigin = TransformOrigin(0.5f, 1f)
                    },
                    onDrawSurface = {
                        val grown = size.height - barPx
                        if (slab && grown > 0.5f) {
                            // the video's colour bleeds into the glass around it
                            val image = bleedImage.of(YtGlassSlab.bleed?.frame)
                            if (image != null) {
                                drawImage(
                                    image = image,
                                    dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                                    alpha = 0.32f * YtGlassMath.smoothstep(0f, 40.dp.toPx(), grown),
                                    filterQuality = FilterQuality.Low,
                                )
                            }
                        }
                        drawRect(if (glassOk) look.tint else look.tintSolid)
                        if (slab && grown > 0.5f) {
                            // the tab row lies in the slab as a plate with a light rim
                            val rim = YtGlassMath.plateRim(grown, barPx / 2f)
                            val stroke = 1.dp.toPx()
                            drawRoundRect(
                                Color.White.copy(alpha = 0.75f * rim),
                                topLeft = Offset(stroke / 2f, grown + stroke / 2f),
                                size = Size(size.width - stroke, barPx - stroke),
                                cornerRadius = CornerRadius(barPx / 2f - stroke / 2f),
                                style = Stroke(stroke),
                            )
                            // the grab handle on the rim above the picture
                            val handle = YtGlassMath.smoothstep(14.dp.toPx(), 30.dp.toPx(), grown)
                            val hw = YtGlassLayout.HANDLE_WIDTH.dp.toPx()
                            val hh = YtGlassLayout.HANDLE_HEIGHT.dp.toPx()
                            drawRoundRect(
                                YtGlassSlab.handle,
                                topLeft = Offset((size.width - hw) / 2f, YtGlassLayout.HANDLE_TOP.dp.toPx()),
                                size = Size(hw, hh),
                                cornerRadius = CornerRadius(hh / 2f),
                                alpha = handle,
                            )
                        }
                    },
                )
                .then(highlight.modifier),
        ) {
            // the tabs keep to the bar at the slab's foot
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .height(look.barHeight),
            ) {
                TabStrip(count, slots, itemHeightPx) { i ->
                    val item = items[i]
                    Box(
                        Modifier
                            .fillMaxSize()
                            .clickable(interactionSource = null, indication = null) { select(i) },
                    ) {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .graphicsLayer { alpha = 1f - slots.activeness(i) }
                                .clip(barShape)
                                .background(look.tile)
                                .border(1.dp, look.tileBorder, barShape),
                        )
                        InactiveTab(look, palette, item, look.inactive, state.badges[item.id], Modifier.graphicsLayer { alpha = 1f - slots.activeness(i) })
                    }
                }
            }
        }

        // 2: the same bar with the tabs in the lens color, seen through the drop while held
        val tabsBackdrop = rememberLayerBackdrop()
        Box(
            Modifier
                .width(width)
                .height(look.barHeight)
                .clearAndSetSemantics {}
                .alpha(0f)
                .layerBackdrop(tabsBackdrop)
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { surfaceShape },
                    effects = {
                        vibrancy()
                        blur(look.blur.toPx())
                    },
                    highlight = null,
                    shadow = null,
                    onDrawSurface = { drawRect(if (glassOk) look.tint else look.tintSolid) },
                ),
        ) {
            TabStrip(count, slots, itemHeightPx) { i ->
                val item = items[i]
                Box(Modifier.fillMaxSize()) {
                    InactiveTab(look, palette, item, look.lensContent, null, Modifier.graphicsLayer { alpha = 1f - slots.activeness(i) })
                    ActiveTab(look, item, look.lensContent, null, Modifier.graphicsLayer { alpha = slots.activeness(i) })
                }
            }
        }

        // 3: the drop
        Box(
            Modifier
                .width(width)
                .height(look.barHeight),
        ) {
            Box(
                Modifier
                    .lensPlacement(slots, itemHeightPx)
                    .then(highlight.gestureModifier)
                    .then(drop.modifier)
                    .drawBackdrop(
                        backdrop = rememberCombinedBackdrop(backdrop, tabsBackdrop),
                        shape = { barShape },
                        effects = {
                            val progress = drop.pressProgress
                            lens(10.dp.toPx() * progress, 14.dp.toPx() * progress, chromaticAberration = true)
                        },
                        highlight = { Highlight(alpha = drop.pressProgress, style = HighlightStyle.Default(angle = ytLightAngle(tilt.value))) },
                        shadow = { Shadow(radius = 10.dp, color = look.shadow, alpha = 1f - 0.4f * drop.pressProgress) },
                        innerShadow = {
                            val progress = drop.pressProgress
                            InnerShadow(radius = 8.dp * progress, alpha = progress)
                        },
                        layerBlock = {
                            var x = drop.scaleX
                            var y = drop.scaleY
                            // a moving drop stretches along its way and gets thinner
                            val velocity = drop.velocity / 10f
                            x /= 1f - (velocity * 0.75f).fastCoerceIn(-0.2f, 0.2f)
                            y *= 1f - (velocity * 0.25f).fastCoerceIn(-0.2f, 0.2f)
                            scaleX = x
                            scaleY = y
                        },
                        onDrawSurface = {
                            val progress = drop.pressProgress
                            drawRect(look.pill, alpha = 1f - 0.88f * progress)
                        },
                    ),
            )
        }

        // 4: the selected tab on the drop at rest (icon and label), fading while the drop is held
        Box(
            Modifier
                .width(width)
                .height(look.barHeight)
                .clearAndSetSemantics {},
        ) {
            TabStrip(count, slots, itemHeightPx) { i ->
                val item = items[i]
                ActiveTab(
                    look, item, look.active, state.badges[item.id],
                    Modifier.graphicsLayer { alpha = slots.activeness(i) * (1f - drop.pressProgress) },
                )
            }
        }
    }
}

/** Lays out the tabs at the positions of [slots]; reads them in layout, not in composition. */
@Composable
private fun TabStrip(count: Int, slots: LiquidSlots, itemHeightPx: Int, content: @Composable (Int) -> Unit) {
    Layout(
        content = { repeat(count) { i -> Box(propagateMinConstraints = true) { content(i) } } },
        modifier = Modifier.fillMaxSize(),
    ) { measurables, constraints ->
        val height = constraints.maxHeight
        val top = (height - itemHeightPx) / 2
        val placeables = measurables.mapIndexed { i, measurable ->
            measurable.measure(Constraints.fixed(slots.width(i).fastRoundToInt(), itemHeightPx))
        }
        layout(constraints.maxWidth, height) {
            placeables.forEachIndexed { i, placeable -> placeable.place(slots.x(i).fastRoundToInt(), top) }
        }
    }
}

/** Sizes and places the drop over the tab it is on. */
private fun Modifier.lensPlacement(slots: LiquidSlots, itemHeightPx: Int): Modifier =
    this.then(
        Modifier.layout { measurable, constraints ->
            val width = slots.lensWidth().fastRoundToInt()
            val placeable = measurable.measure(Constraints.fixed(width, itemHeightPx))
            layout(constraints.maxWidth, constraints.maxHeight) {
                placeable.place(slots.lensX().fastRoundToInt(), (constraints.maxHeight - itemHeightPx) / 2)
            }
        },
    )

@Composable
private fun InactiveTab(look: LiquidLook, palette: YtPalette, item: YtNavItem, color: Color, badge: Int?, modifier: Modifier) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = modifier.fillMaxSize(),
    ) {
        Box {
            LiquidIcon(item, active = false, color = color, size = look.inactiveIcon)
            Badge(palette, (badge ?: 0) > 0, Modifier.align(Alignment.TopEnd).offset(4.dp, (-2).dp))
        }
        look.inactiveLabel?.let { size ->
            Text(
                text = stringResource(item.label),
                color = color,
                maxLines = 1,
                softWrap = false,
                style = TextStyle(fontFamily = look.font, fontWeight = FontWeight.Medium, fontSize = size),
            )
        }
    }
}

@Composable
private fun ActiveTab(look: LiquidLook, item: YtNavItem, color: Color, badge: Int?, modifier: Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        // keeps its natural width while the tab grows around it
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.wrapContentWidth(unbounded = true)) {
            LiquidIcon(item, active = true, color = color, size = look.activeIcon)
            Spacer(Modifier.width(look.labelGap))
            Text(
                text = stringResource(item.label),
                color = color,
                maxLines = 1,
                softWrap = false,
                style = TextStyle(fontFamily = look.font, fontWeight = look.activeWeight, fontSize = look.activeLabel),
            )
        }
        if ((badge ?: 0) > 0) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 8.dp, end = 12.dp)
                    .size(8.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color(0xFFF03A3A)),
            )
        }
    }
}

@Composable
private fun LiquidIcon(item: YtNavItem, active: Boolean, color: Color, size: Dp) {
    if (item.icon == null) {
        YtShortsGlyph(color, filled = active, modifier = Modifier.size(size))
    } else {
        Icon(
            painterResource(if (active) item.activeIcon ?: item.icon else item.icon),
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(size),
        )
    }
}

/**
 * Glas: the bar's glass grows upward by the slab behind the docked player (YtDock.slabPx, read
 * while laying out), drawn above the bar's own view, which keeps its height.
 */
private fun Modifier.glassSlabGrowth(topMargin: Float): Modifier =
    this.then(
        Modifier.layout { measurable, constraints ->
            val grow = YtGlassMath.slabGrowth(YtDock.slabPx, topMargin).roundToInt()
            // emulator checks: whether the bar really grows into the slab behind the mini player
            if (com.github.libretube.helpers.YtGuard.isEmulator) {
                android.util.Log.i("YtMini", "slab grow=$grow slabPx=${YtDock.slabPx} bar=${constraints.maxHeight}")
            }
            val placeable = measurable.measure(Constraints.fixed(constraints.maxWidth, constraints.maxHeight + grow))
            layout(constraints.maxWidth, constraints.maxHeight) { placeable.place(0, -grow) }
        },
    )
