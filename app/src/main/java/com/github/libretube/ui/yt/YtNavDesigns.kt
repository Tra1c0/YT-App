package com.github.libretube.ui.yt

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.coerceIn
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.libretube.R
import com.github.libretube.ui.yt.mini.ytDockedBar
import kotlinx.coroutines.launch
import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.util.fastRoundToInt
import com.github.libretube.ui.yt.liquid.DampedDragAnimation
import kotlin.math.abs

/*
 * YT: navigation of the second round of designs (Clay, Editorial, Glas, Pop) and of the designs
 * from the inspiration images (Formen, Frost). Sizes follow docs/DESIGN-MASSE.md. Selection
 * changes animate (colors, sliding or growing indicators), so further motion can build on them.
 */

private val springDp = spring<Dp>(dampingRatio = 0.72f, stiffness = 420f)

/** The Shorts mark: two capsules leaning against each other with a play mark. */
@Composable
fun YtShortsGlyph(color: Color, filled: Boolean, modifier: Modifier = Modifier) {
    Canvas(modifier.graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)) {
        val s = size.minDimension
        val w = s * 0.46f
        val h = s * 0.5f
        val style = if (filled) Fill else Stroke(width = s * 0.09f)
        rotate(-30f) {
            drawRoundRect(color, Offset(center.x - w * 0.82f, center.y - h * 0.86f), Size(w * 1.2f, h), CornerRadius(w / 2f), style = style)
            drawRoundRect(color, Offset(center.x - w * 0.38f, center.y - h * 0.14f), Size(w * 1.2f, h), CornerRadius(w / 2f), style = style)
        }
        val play = Path().apply {
            moveTo(center.x - s * 0.09f, center.y - s * 0.125f)
            lineTo(center.x + s * 0.135f, center.y)
            lineTo(center.x - s * 0.09f, center.y + s * 0.125f)
            close()
        }
        if (filled) drawPath(play, Color.Black, blendMode = BlendMode.Clear) else drawPath(play, color)
    }
}

/** The icon of a tab: the item's drawable or, for Shorts, the Shorts mark. */
@Composable
private fun NavIcon(item: YtNavItem, active: Boolean, tint: Color, size: Dp) {
    // a newly selected tab pops its icon, in the motion character of the design
    val modifier = Modifier
        .size(size)
        .ytSelectBounce(active)
    if (item.icon == null) {
        YtShortsGlyph(tint, filled = active, modifier = modifier)
    } else {
        Icon(
            painterResource(if (active) item.activeIcon ?: item.icon else item.icon),
            contentDescription = null,
            tint = tint,
            modifier = modifier,
        )
    }
}

private fun Modifier.noRippleClick(onClick: () -> Unit) =
    this.clickable(interactionSource = null, indication = null, onClick = onClick)

@Composable
internal fun Badge(palette: YtPalette, visible: Boolean, modifier: Modifier = Modifier) {
    if (!visible) return
    Box(
        modifier
            .size(9.dp)
            .clip(CircleShape)
            .background(palette.live),
    )
}

// ---------------------------------------------------------------------------------------------
// Clay: four puffy blobs in a 2 x 2 grid, the active one in mauve.

private class ClayTile(val x: Dp, val y: Dp, val w: Dp, val h: Dp, val shape: Shape, val base: Color, val contentBottom: Dp)

@Composable
fun ClayNav(palette: YtPalette, items: List<YtNavItem>, state: YtNavState, onSelect: (Int) -> Unit) {
    val cream = Color(0xFFF3E1D2)
    val blue = Color(0xFFB7CBE4)
    val mauve = Color(0xFFA8696F)
    // measured in the reference: grid 357 x 215 dp from x 18, the lower blobs lie on the upper ones
    val tiles = remember {
        listOf(
            ClayTile(3.dp, 0.dp, 176.dp, 129.dp, BlobShape(0.55f, 0.42f, 0.24f, 0.3f), cream, 26.dp),
            ClayTile(179.dp, 0.dp, 175.dp, 125.dp, BlobShape(0.42f, 0.55f, 0.3f, 0.24f), blue, 24.dp),
            ClayTile(0.dp, 102.dp, 178.dp, 113.dp, BlobShape(0.34f, 0.26f, 0.32f, 0.6f), cream, 0.dp),
            ClayTile(180.dp, 102.dp, 177.dp, 107.dp, BlobShape(0.26f, 0.34f, 0.6f, 0.32f), blue, 0.dp),
        )
    }
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = 18.dp, end = 18.dp, top = 6.dp, bottom = 10.dp)
            .height(215.dp),
    ) {
        items.take(4).forEachIndexed { index, item ->
            val tile = tiles[index]
            val active = state.selected == item.id
            val color by animateColorAsState(if (active) mauve else tile.base, ytStateSpec(), label = "clayTile")
            val content by animateColorAsState(if (active) Color.White else Color(0xFF5C5260), ytStateSpec(), label = "clayContent")
            val interaction = remember { MutableInteractionSource() }
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .offset(tile.x, tile.y)
                    .size(tile.w, tile.h)
                    // the docked mini player is dough pressed onto the upper blobs: they are
                    // pulled up a little with it while its long press stretches it (read while
                    // drawing, so the bar does not compose again)
                    .then(
                        if (index < 2) {
                            Modifier.graphicsLayer {
                                val pull = YtDock.peek.coerceIn(0f, 1.15f)
                                scaleY = 1f + 0.025f * pull
                                scaleX = 1f - 0.008f * pull
                                transformOrigin = TransformOrigin(0.5f, 1f)
                            }
                        } else {
                            Modifier
                        },
                    )
                    // a poked blob of clay: squashes under the finger, wobbles when it becomes active
                    .ytJelly(active, amount = 0.08f)
                    .ytSquish(interaction)
                    .shadow(10.dp, tile.shape, clip = false, ambientColor = Color(0x66B07A66), spotColor = Color(0x80B07A66))
                    .clip(tile.shape)
                    .background(Brush.verticalGradient(listOf(lighten(color, 0.16f), color, darken(color, 0.06f))))
                    .border(1.5.dp, Color.White.copy(alpha = 0.35f), tile.shape)
                    .clickable(interactionSource = interaction, indication = null) { onSelect(item.id) },
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(bottom = tile.contentBottom),
                ) {
                    Box {
                        NavIcon(item, true, content, 38.dp)
                        Badge(palette, (state.badges[item.id] ?: 0) > 0, Modifier.align(Alignment.TopEnd))
                    }
                    Text(
                        text = stringResource(item.label),
                        color = content,
                        style = TextStyle(fontFamily = YtFonts.nunito, fontWeight = FontWeight.Black, fontSize = 15.5.sp),
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}

private fun lighten(c: Color, f: Float) = Color(
    red = c.red + (1 - c.red) * f, green = c.green + (1 - c.green) * f, blue = c.blue + (1 - c.blue) * f, alpha = c.alpha,
)

private fun darken(c: Color, f: Float) = Color(red = c.red * (1 - f), green = c.green * (1 - f), blue = c.blue * (1 - f), alpha = c.alpha)

// ---------------------------------------------------------------------------------------------
// Editorial: a floating white slip with a hairline black frame; a blue bar on its top edge
// marks the active tab and slides straight across, without overshoot.

@Composable
fun EditorialNav(palette: YtPalette, items: List<YtNavItem>, state: YtNavState, onSelect: (Int) -> Unit) {
    val index = items.indexOfFirst { it.id == state.selected }.coerceAtLeast(0)
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .padding(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 10.dp)
            .shadow(12.dp, RectangleShape, clip = false, ambientColor = Color(0x33000000), spotColor = Color(0x40000000))
            .background(palette.background)
            .border(1.dp, palette.text),
    ) {
        val itemWidth = maxWidth / items.size
        val barX by animateDpAsState(itemWidth * index, ytSpec(tween(200, easing = LinearOutSlowInEasing)), label = "editorialBar")
        Box(
            Modifier
                .offset(x = barX)
                .width(itemWidth)
                .height(3.dp)
                .background(palette.accent),
        )
        Row(
            Modifier
                .fillMaxWidth()
                .height(50.dp),
        ) {
            items.forEachIndexed { i, item ->
                val active = item.id == state.selected
                if (i > 0) {
                    Box(
                        Modifier
                            .padding(vertical = 14.dp)
                            .width(1.dp)
                            .fillMaxHeight()
                            .background(palette.text.copy(alpha = 0.18f)),
                    )
                }
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .noRippleClick { onSelect(item.id) },
                ) {
                    Text(
                        text = stringResource(item.label),
                        color = palette.text,
                        style = TextStyle(
                            fontFamily = YtFonts.inter,
                            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                            fontSize = 14.sp,
                        ),
                    )
                    Badge(palette, (state.badges[item.id] ?: 0) > 0, Modifier.align(Alignment.TopEnd).padding(top = 11.dp, end = 14.dp))
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Glas: a frosted floating bar; the active tab grows from a circle into a white pill.

@Composable
fun GlassNav(palette: YtPalette, items: List<YtNavItem>, state: YtNavState, onSelect: (Int) -> Unit) {
    // a mini player set on the bar: the bar squares its top corners as it docks
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = 13.dp, end = 11.dp, top = 6.dp, bottom = 10.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier
                .fillMaxWidth()
                .height(59.dp)
                .ytDockedBar(
                    radius = 30.dp,
                    stacks = true,
                    fill = Brush.verticalGradient(listOf(Color(0x8CFFFFFF), Color(0x52FFFFFF))),
                    border = Color(0xCCFFFFFF),
                    shadow = 8.dp,
                    shadowColor = Color(0x405C8C85),
                )
                .padding(horizontal = 6.dp),
        ) {
            items.forEach { item ->
                val active = item.id == state.selected
                val width by animateDpAsState(if (active) 107.dp else 49.dp, ytSpec(springDp), label = "glassPill")
                val pill = RoundedCornerShape(25.dp)
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .width(width)
                        .height(49.dp)
                        .then(if (active) Modifier.shadow(6.dp, pill, clip = false, ambientColor = Color(0x335C8C85), spotColor = Color(0x335C8C85)) else Modifier)
                        .clip(pill)
                        .background(if (active) Color(0xF2FFFFFF) else Color(0x40FFFFFF))
                        .border(1.dp, Color(0xB3FFFFFF), pill)
                        .noRippleClick { onSelect(item.id) },
                ) {
                    if (active) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            NavIcon(item, true, palette.text, 24.dp)
                            Spacer(Modifier.width(9.dp))
                            Text(
                                text = stringResource(item.label),
                                color = palette.text,
                                maxLines = 1,
                                style = TextStyle(fontFamily = YtFonts.googleSans, fontWeight = FontWeight.Bold, fontSize = 15.sp),
                            )
                        }
                    } else {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Box {
                                NavIcon(item, false, palette.text, 22.dp)
                                Badge(palette, (state.badges[item.id] ?: 0) > 0, Modifier.align(Alignment.TopEnd).offset(4.dp, (-2).dp))
                            }
                            Text(
                                text = stringResource(item.label),
                                color = palette.text,
                                maxLines = 1,
                                style = TextStyle(fontFamily = YtFonts.googleSans, fontWeight = FontWeight.Medium, fontSize = 11.5.sp),
                            )
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Pop: a wavy lavender band; a plum blob slides to the active tab and rises above the band.

@Composable
fun PopNav(palette: YtPalette, items: List<YtNavItem>, state: YtNavState, onSelect: (Int) -> Unit) {
    val count = items.size
    val index = items.indexOfFirst { it.id == state.selected }.coerceAtLeast(0)
    val scope = rememberCoroutineScope()
    val motion = LocalYtMotion.current
    val density = LocalDensity.current
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .padding(start = 10.dp, end = 10.dp, bottom = 8.dp)
            .height(70.dp),
    ) {
        val itemWidth = maxWidth / count
        val itemWidthPx = with(density) { itemWidth.toPx() }
        val blobWidthPx = with(density) { 100.dp.toPx() }
        // the plum blob is a thick drop of syrup: it can be dragged along the band, stretches
        // with its speed, swells while held and wobbles when it lands
        val gesture = remember { FloatArray(2) }
        val blob = remember(scope, count, motion, itemWidthPx) {
            DampedDragAnimation(
                animationScope = scope,
                initialValue = index.toFloat(),
                valueRange = 0f..(count - 1).toFloat(),
                visibilityThreshold = 0.001f,
                initialScale = 1f,
                pressedScale = 1.1f,
                motion = motion,
                onDragStarted = {
                    gesture[0] = targetValue.fastRoundToInt().toFloat()
                    gesture[1] = 0f
                },
                onDragStopped = {
                    val target = targetValue.fastRoundToInt().fastCoerceIn(0, count - 1)
                    animateToValue(target.toFloat())
                    if (target != gesture[0].toInt() || gesture[1] < itemWidthPx / 8f) onSelect(items[target].id)
                },
                onDrag = { _, dragAmount ->
                    gesture[1] += abs(dragAmount.x)
                    updateValue(targetValue + dragAmount.x / itemWidthPx)
                },
            )
        }
        LaunchedEffect(blob, index) {
            if (abs(blob.targetValue - index) > 0.001f) blob.animateToValue(index.toFloat())
        }
        fun activeness(i: Int) = (1f - abs(blob.value - i)).fastCoerceIn(0f, 1f)
        val blobX: Density.() -> IntOffset = {
            IntOffset((blob.value * itemWidthPx + (itemWidthPx - blobWidthPx) / 2f).fastRoundToInt(), 0)
        }
        val stretch: GraphicsLayerScope.() -> Unit = {
            val velocity = blob.velocity / 10f
            scaleX = blob.scaleX / (1f - (velocity * 0.6f).fastCoerceIn(-0.25f, 0.25f))
            scaleY = blob.scaleY * (1f - (velocity * 0.3f).fastCoerceIn(-0.2f, 0.2f))
            transformOrigin = TransformOrigin(0.5f, 1f)
        }

        // the band: one bubble per tab on a rounded bar, so it bulges around each tab and has
        // round ends, as in the reference; the bubble under the blob swells as it passes
        val band = Color(0xFFD9D1F6)
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(58.dp),
        ) {
            Box(
                Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp)
                    .height(44.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(band),
            )
            Row(Modifier.fillMaxSize()) {
                items.forEachIndexed { i, _ ->
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .padding(horizontal = 1.dp)
                            .graphicsLayer {
                                val a = activeness(i)
                                scaleX = 1f + 0.05f * a
                                scaleY = 1f + 0.1f * a
                            }
                            .clip(RoundedCornerShape(50))
                            .background(band),
                    )
                }
            }
        }
        Box(
            Modifier
                .align(Alignment.BottomStart)
                .offset(blobX)
                .size(100.dp, 68.dp)
                .graphicsLayer(stretch)
                .clip(BlobShape(0.55f, 0.45f, 0.5f, 0.6f))
                .background(palette.accent),
        )
        Row(Modifier.fillMaxSize()) {
            items.forEachIndexed { i, item ->
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .padding(top = 12.dp)
                        .noRippleClick {
                            blob.animateToValue(i.toFloat())
                            onSelect(item.id)
                        },
                ) {
                    PopTab(palette, item, state, active = false, palette.text, Modifier.graphicsLayer { alpha = 1f - activeness(i) })
                    PopTab(palette, item, state, active = true, Color.White, Modifier.graphicsLayer { alpha = activeness(i) })
                }
            }
        }
        // the handle to drag the blob, on top of the tabs
        Box(
            Modifier
                .align(Alignment.BottomStart)
                .offset(blobX)
                .size(100.dp, 68.dp)
                .then(blob.modifier),
        )
    }
}

@Composable
private fun PopTab(palette: YtPalette, item: YtNavItem, state: YtNavState, active: Boolean, tint: Color, modifier: Modifier) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = modifier.fillMaxSize(),
    ) {
        Box {
            NavIcon(item, active, tint, 26.dp)
            Badge(palette, (state.badges[item.id] ?: 0) > 0, Modifier.align(Alignment.TopEnd).offset(4.dp, (-3).dp))
        }
        Text(
            text = stringResource(item.label),
            color = tint,
            maxLines = 1,
            style = TextStyle(fontFamily = YtFonts.nunito, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp),
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Formen: a night bar; the active tab sits in its own colored Material shape.

@Composable
fun ShapesNav(palette: YtPalette, items: List<YtNavItem>, state: YtNavState, onSelect: (Int) -> Unit) {
    val colors = listOf(palette.lime, Color(0xFFF6D365), palette.pink, palette.lavender)
    val bar = RoundedCornerShape(32.dp)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 10.dp)
            .height(66.dp)
            .clip(bar)
            .background(Color(0xFF1E1D44))
            .border(1.dp, Color(0x26FFFFFF), bar),
    ) {
        items.forEachIndexed { i, item ->
            val active = item.id == state.selected
            val width by animateDpAsState(if (active) (if (i == 1) 72.dp else 50.dp) else 40.dp, ytSpec(springDp), label = "shapeSize")
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .noRippleClick { onSelect(item.id) },
            ) {
                // the shape of the active tab blooms out of a circle (lobes grow, corners
                // square up) while it turns into place; the scalloped ones keep turning slowly
                val morph = animateFloatAsState(
                    if (active) 1f else 0f,
                    ytSpec(spring(dampingRatio = 0.45f, stiffness = 320f)),
                    label = "shapeMorph",
                )
                val spin = if (active && (i == 0 || i == 3)) rememberSpin(16000) else null
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .width(width)
                        .height(if (active) 44.dp else 36.dp),
                ) {
                    Box(
                        Modifier
                            .matchParentSize()
                            // read in the draw phase: the morph redraws, it does not recompose
                            .graphicsLayer {
                                val m = morph.value
                                rotationZ = (spin?.value ?: 0f) - 70f * (1f - m)
                                alpha = m.coerceIn(0f, 1f)
                                clip = true
                                shape = when (i % 4) {
                                    0 -> CookieShape(9, 0.07f * m)
                                    2 -> MorphRoundRectShape(m, 14.dp)
                                    3 -> CookieShape(6, 0.1f * m)
                                    else -> RoundedCornerShape(50)
                                }
                            }
                            .background(colors[i % colors.size]),
                    )
                    NavIcon(item, active, if (active) palette.ink else palette.text.copy(alpha = 0.75f), 22.dp)
                    Badge(palette, (state.badges[item.id] ?: 0) > 0, Modifier.align(Alignment.TopEnd))
                }
                if (active) {
                    Text(
                        text = stringResource(item.label),
                        color = palette.text,
                        maxLines = 1,
                        style = TextStyle(fontFamily = YtFonts.googleSans, fontWeight = FontWeight.Medium, fontSize = 11.sp),
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Frost: a dark pill floating over the sage glass; the active tab is a white pill with its label.

@Composable
fun FrostNav(palette: YtPalette, items: List<YtNavItem>, state: YtNavState, onSelect: (Int) -> Unit) {
    // a mini player set on the bar: the bar squares its top corners as it docks
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier
                .padding(top = 6.dp, bottom = 12.dp)
                .height(60.dp)
                .ytDockedBar(radius = 32.dp, stacks = true, fill = SolidColor(Color(0xF21F2A26)), shadow = 12.dp)
                .padding(horizontal = 6.dp),
        ) {
            items.forEach { item ->
                val active = item.id == state.selected
                val width by animateDpAsState(if (active) 118.dp else 48.dp, ytSpec(springDp), label = "frostPill")
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .width(width)
                        .height(48.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .background(if (active) Color.White else Color(0x1AFFFFFF))
                        .border(1.dp, if (active) Color.Transparent else Color(0x33FFFFFF), RoundedCornerShape(24.dp))
                        .noRippleClick { onSelect(item.id) },
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box {
                            NavIcon(item, active, if (active) palette.ink else Color.White, 22.dp)
                            Badge(palette, (state.badges[item.id] ?: 0) > 0, Modifier.align(Alignment.TopEnd).offset(3.dp, (-2).dp))
                        }
                        if (active) {
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = stringResource(item.label),
                                color = palette.ink,
                                maxLines = 1,
                                style = TextStyle(fontFamily = YtFonts.jakarta, fontWeight = FontWeight.SemiBold, fontSize = 14.sp),
                            )
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Kiesel: a black floating bar with cream icons only; a dot slides under the active one and a
// grey circle ripples out behind a tapped icon, as in the inspiration image.

@Composable
fun KieselNav(palette: YtPalette, items: List<YtNavItem>, state: YtNavState, onSelect: (Int) -> Unit) {
    val index = items.indexOfFirst { it.id == state.selected }.coerceAtLeast(0)
    val dot = animateFloatAsState(index.toFloat(), ytSpec(spring(dampingRatio = 0.6f, stiffness = 380f)), label = "kieselDot")
    val cream = Color(0xFFEDE8E3)
    val scope = rememberCoroutineScope()
    val rippleSpec = ytFinite(tween<Float>(420, easing = YtEaseOut))
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 10.dp)
            .height(64.dp)
            .shadow(14.dp, RoundedCornerShape(26.dp), clip = false, ambientColor = Color(0x59000000), spotColor = Color(0x59000000))
            .clip(RoundedCornerShape(26.dp))
            .background(Color(0xFF0E0E0E)),
    ) {
        val itemWidth = maxWidth / items.size
        Box(
            Modifier
                .align(Alignment.BottomStart)
                .padding(bottom = 9.dp)
                .graphicsLayer { translationX = (dot.value + 0.5f) * itemWidth.toPx() - 2.5.dp.toPx() }
                .size(5.dp)
                .clip(CircleShape)
                .background(cream),
        )
        Row(Modifier.fillMaxSize()) {
            items.forEachIndexed { i, item ->
                val active = i == index
                val ripple = remember { Animatable(1f) }
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .noRippleClick {
                            scope.launch {
                                ripple.snapTo(0f)
                                ripple.animateTo(1f, rippleSpec)
                            }
                            onSelect(item.id)
                        },
                ) {
                    Box(
                        Modifier
                            .size(46.dp)
                            .graphicsLayer {
                                val r = ripple.value
                                alpha = if (r >= 1f) 0f else 1f - r
                                scaleX = 0.5f + 0.5f * r
                                scaleY = 0.5f + 0.5f * r
                            }
                            .clip(CircleShape)
                            .background(Color(0xFF3A3A3A)),
                    )
                    Box(Modifier.padding(bottom = 6.dp)) {
                        NavIcon(item, active, if (active) cream else cream.copy(alpha = 0.55f), 25.dp)
                        Badge(palette, (state.badges[item.id] ?: 0) > 0, Modifier.align(Alignment.TopEnd).offset(3.dp, (-2).dp))
                    }
                }
            }
        }
    }
}
