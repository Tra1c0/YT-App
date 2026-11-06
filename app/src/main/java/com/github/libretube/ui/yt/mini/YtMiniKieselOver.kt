package com.github.libretube.ui.yt.mini

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import com.github.libretube.ui.yt.LocalYtMotion
import com.github.libretube.ui.yt.YtDock
import kotlinx.coroutines.delay
import kotlin.math.min

/*
 * YT: what the Kiesel player draws on its picture (the overlay over the video, clipped to it).
 * The video is a SurfaceView, which cannot be masked to a path and whose outline keeps the
 * design's fixed corner: the stadium is cut by drawing the pebble's black over the corners
 * outside it, so the picture is a stadium at any size (docked, and the wide one of the tall
 * pebble). The glyph flash of play and pause is a lavender pebble that pops. Every moving value
 * is read while drawing.
 */

@Composable
internal fun KieselOver(scene: YtMiniScene) {
    val motion = LocalYtMotion.current
    val lavender = scene.palette?.accent ?: Color(0xFFB4A7F5)
    val ink = Color(0xFF0E0E0E)

    val flash = remember { Animatable(0f) }
    val flashes = scene.flashes
    var seen by remember { mutableIntStateOf(flashes) }
    LaunchedEffect(flashes) {
        if (flashes == seen) return@LaunchedEffect
        seen = flashes
        if (motion) {
            flash.snapTo(0f)
            flash.animateTo(1f, tween(560, easing = LinearEasing))
        } else {
            flash.snapTo(0.3f)
            delay(450)
        }
        flash.snapTo(0f)
    }
    val playing = scene.flashPlaying
    val mask = remember { Path().apply { fillType = PathFillType.EvenOdd } }

    Canvas(Modifier.fillMaxSize().clearAndSetSemantics {}) {
        val t = flash.value
        if (t > 0f) kieselFlash(t, playing, lavender, ink)

        // the stadium: the pebble's black over the corners, as the player arrives on the bar
        // (on the page the picture stays square)
        val shown = YtMiniKieselLayout.smooth(YtDock.progress, 0.55f, 1f)
        if (shown > 0f && scene.palette != null) {
            val r = size.minDimension / 2f
            mask.reset()
            mask.fillType = PathFillType.EvenOdd
            mask.addRect(Rect(0f, 0f, size.width, size.height))
            mask.addRoundRect(RoundRect(0f, 0f, size.width, size.height, CornerRadius(r)))
            drawPath(mask, ink, alpha = shown)
        }
    }
}

/**
 * The flash: a lavender pebble that pops in with a little overshoot and fades as it swells, the
 * glyph of play or pause in black on it.
 */
private fun DrawScope.kieselFlash(t: Float, playing: Boolean, lavender: Color, ink: Color) {
    val pop = YtMiniKieselLayout.backOut((t / 0.3f).coerceAtMost(1f), 2f)
    val shown = if (t < 0.6f) 1f else 1f - (t - 0.6f) / 0.4f
    if (shown <= 0f) return
    val radius = min(size.minDimension * 0.36f, 22.dp.toPx()) * (0.6f + 0.4f * pop)
    val c = center
    // a pebble, a touch wider than tall
    drawOval(
        lavender,
        topLeft = Offset(c.x - radius * 1.12f, c.y - radius * 0.94f),
        size = Size(radius * 2.24f, radius * 1.88f),
        alpha = 0.95f * shown,
    )
    val r = radius * 0.42f
    if (playing) {
        val path = Path().apply {
            moveTo(c.x - r * 0.6f, c.y - r)
            lineTo(c.x + r, c.y)
            lineTo(c.x - r * 0.6f, c.y + r)
            close()
        }
        drawPath(path, ink, alpha = shown)
    } else {
        val bar = r * 0.62f
        val corner = CornerRadius(bar / 2f)
        drawRoundRect(ink, Offset(c.x - r * 0.85f, c.y - r), Size(bar, r * 2f), corner, alpha = shown)
        drawRoundRect(ink, Offset(c.x + r * 0.85f - bar, c.y - r), Size(bar, r * 2f), corner, alpha = shown)
    }
}
