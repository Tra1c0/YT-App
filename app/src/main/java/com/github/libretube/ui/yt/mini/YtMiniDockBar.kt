package com.github.libretube.ui.yt.mini

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.github.libretube.ui.yt.YtDock

/*
 * YT: how the navigation bars meet the docked mini player. A bar that carries a stacked player
 * (one surface with it) squares its top corners continuously as the player arrives, following
 * YtDock.progress frame by frame, instead of switching when it docks.
 */

/** The top corner radius of a bar of corner [radius] (px), with [stacks] a stacked player on it. */
fun ytDockTop(radius: Float, stacks: Boolean): Float =
    if (stacks) radius * (1f - YtMiniDockMath.square(YtDock.progress)) else radius

/**
 * The surface of a navigation bar a mini player may dock on: [fill] behind the content, [border]
 * over it and a shadow of [shadow] elevation, in a rounded rect of [radius] whose top corners
 * square as a stacked player ([stacks]) docks. The progress is read while drawing, so the bar
 * draws again but never composes again while the player moves.
 */
fun Modifier.ytDockedBar(
    radius: Dp,
    stacks: Boolean,
    fill: Brush,
    border: Color? = null,
    borderWidth: Dp = 1.dp,
    shadow: Dp = 0.dp,
    shadowColor: Color = Color.Black,
): Modifier = this
    .graphicsLayer {
        val r = radius.toPx()
        val top = ytDockTop(r, stacks)
        shape = RoundedCornerShape(topStart = top, topEnd = top, bottomEnd = r, bottomStart = r)
        clip = true
        if (shadow > 0.dp) {
            shadowElevation = shadow.toPx()
            ambientShadowColor = shadowColor
            spotShadowColor = shadowColor
        }
    }
    .drawWithContent {
        drawRect(fill)
        drawContent()
        if (border != null) {
            // inside the edge, as the border modifier draws it
            val r = radius.toPx()
            val top = ytDockTop(r, stacks)
            val width = borderWidth.toPx()
            val half = width / 2f
            val outline = RoundRect(
                left = half,
                top = half,
                right = size.width - half,
                bottom = size.height - half,
                topLeftCornerRadius = CornerRadius((top - half).coerceAtLeast(0f)),
                topRightCornerRadius = CornerRadius((top - half).coerceAtLeast(0f)),
                bottomRightCornerRadius = CornerRadius((r - half).coerceAtLeast(0f)),
                bottomLeftCornerRadius = CornerRadius((r - half).coerceAtLeast(0f)),
            )
            drawPath(Path().apply { addRoundRect(outline) }, border, style = Stroke(width))
        }
    }
