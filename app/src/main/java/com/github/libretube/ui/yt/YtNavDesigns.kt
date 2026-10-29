package com.github.libretube.ui.yt

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
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
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.libretube.R

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
private fun Badge(palette: YtPalette, visible: Boolean, modifier: Modifier = Modifier) {
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
// Editorial: docked text tabs under a black rule, a blue bar marks the active tab.

@Composable
fun EditorialNav(palette: YtPalette, items: List<YtNavItem>, state: YtNavState, onSelect: (Int) -> Unit) {
    val index = items.indexOfFirst { it.id == state.selected }.coerceAtLeast(0)
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .background(palette.background),
    ) {
        val itemWidth = maxWidth / items.size
        val barX by animateDpAsState(itemWidth * index, ytSpec(tween(200, easing = LinearOutSlowInEasing)), label = "editorialBar")
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(palette.text),
        )
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
                .padding(top = 3.dp)
                .height(44.dp),
        ) {
            items.forEach { item ->
                val active = item.id == state.selected
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
                    Badge(palette, (state.badges[item.id] ?: 0) > 0, Modifier.align(Alignment.TopEnd).padding(top = 9.dp, end = 18.dp))
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Glas: a frosted floating bar; the active tab grows from a circle into a white pill.

@Composable
fun GlassNav(palette: YtPalette, items: List<YtNavItem>, state: YtNavState, onSelect: (Int) -> Unit) {
    val shape = RoundedCornerShape(30.dp)
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
                .shadow(8.dp, shape, clip = false, ambientColor = Color(0x405C8C85), spotColor = Color(0x405C8C85))
                .clip(shape)
                .background(Brush.verticalGradient(listOf(Color(0x8CFFFFFF), Color(0x52FFFFFF))))
                .border(1.dp, Color(0xCCFFFFFF), shape)
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
    val index = items.indexOfFirst { it.id == state.selected }.coerceAtLeast(0)
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .padding(start = 10.dp, end = 10.dp, bottom = 8.dp)
            .height(70.dp),
    ) {
        val itemWidth = maxWidth / items.size
        val blobX by animateDpAsState(itemWidth * index, ytSpec(spring(dampingRatio = 0.55f, stiffness = 320f)), label = "popBlob")
        // the band: one bubble per tab on a rounded bar, so it bulges around each tab and has
        // round ends, as in the reference
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
                items.forEach { _ ->
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .padding(horizontal = 1.dp)
                            .clip(RoundedCornerShape(50))
                            .background(band),
                    )
                }
            }
        }
        Box(
            Modifier
                .offset(x = blobX + (itemWidth - 100.dp) / 2)
                .align(Alignment.BottomStart)
                .size(100.dp, 68.dp)
                .clip(BlobShape(0.55f, 0.45f, 0.5f, 0.6f))
                .background(palette.accent),
        )
        Row(Modifier.fillMaxSize()) {
            items.forEachIndexed { i, item ->
                val active = i == index
                val tint by animateColorAsState(if (active) Color.White else palette.text, ytStateSpec(), label = "popTint")
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .padding(top = 12.dp)
                        .noRippleClick { onSelect(item.id) },
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
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Formen: a night bar; the active tab sits in its own colored Material shape.

@Composable
fun ShapesNav(palette: YtPalette, items: List<YtNavItem>, state: YtNavState, onSelect: (Int) -> Unit) {
    val shapes = remember { listOf(CookieShape(9), RoundedCornerShape(50), RoundedCornerShape(14.dp), CookieShape(6, 0.1f)) }
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
                // the scalloped shapes of the active tab turn slowly
                val spin = if (active && (i == 0 || i == 3)) rememberSpin(16000) else 0f
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .width(width)
                        .height(if (active) 44.dp else 36.dp),
                ) {
                    Box(
                        Modifier
                            .matchParentSize()
                            .graphicsLayer { rotationZ = spin }
                            .clip(shapes[i % shapes.size])
                            .background(if (active) colors[i % colors.size] else Color.Transparent),
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
    val bar = RoundedCornerShape(32.dp)
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier
                .padding(top = 6.dp, bottom = 12.dp)
                .height(60.dp)
                .shadow(12.dp, bar, clip = false)
                .clip(bar)
                .background(Color(0xF21F2A26))
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
