package com.github.libretube.ui.yt.mini

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.spring
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import com.github.libretube.ui.yt.LocalYtMotion
import com.github.libretube.ui.yt.YtDock
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.min

/*
 * YT: what the Movo player draws on its picture (the overlay over the video, clipped to its
 * rounded outline): the 3 dp cobalt frame that makes the picture a tile, which snaps in on
 * landing; the light of the tilt under the finger (the SurfaceView cannot turn, so the tile's
 * turn shows as light and shade on it); a lime glow as a dragged video comes near and a lime
 * flash when it is taken; a pink frame for a drop it refuses; the play and pause glyph on a
 * small lime tile. Every moving value is read while drawing.
 */

/** ▶ while it plays, ❚❚ while paused, blocky as Movo's type. */
private fun DrawScope.movoGlyph(playing: Boolean, center: Offset, radius: Float, color: Color) {
    if (playing) {
        val path = Path().apply {
            moveTo(center.x - radius * 0.6f, center.y - radius)
            lineTo(center.x + radius, center.y)
            lineTo(center.x - radius * 0.6f, center.y + radius)
            close()
        }
        drawPath(path, color)
    } else {
        val bar = radius * 0.7f
        val corner = CornerRadius(bar / 4f)
        drawRoundRect(color, Offset(center.x - radius * 0.9f, center.y - radius), Size(bar, radius * 2f), corner)
        drawRoundRect(color, Offset(center.x + radius * 0.9f - bar, center.y - radius), Size(bar, radius * 2f), corner)
    }
}

/** The light of a tile turned by [tiltX], [tiltY] degrees: the side that gives way darkens. */
private fun DrawScope.tiltShade(tiltX: Float, tiltY: Float) {
    val most = YtMovoLayout.MAX_TILT
    val y = (tiltY / most).coerceIn(-1f, 1f)
    val x = (tiltX / most).coerceIn(-1f, 1f)
    if (abs(y) > 0.01f) {
        // rotationY > 0: the right edge turns away and darkens, the left one catches the light
        val dark = Color.Black.copy(alpha = 0.2f * abs(y))
        val light = Color.White.copy(alpha = 0.12f * abs(y))
        val colors = if (y > 0f) listOf(light, Color.Transparent, dark) else listOf(dark, Color.Transparent, light)
        drawRect(Brush.horizontalGradient(colors, startX = 0f, endX = size.width))
    }
    if (abs(x) > 0.01f) {
        // rotationX < 0: the top tips back and darkens
        val dark = Color.Black.copy(alpha = 0.2f * abs(x))
        val light = Color.White.copy(alpha = 0.12f * abs(x))
        val colors = if (x < 0f) listOf(dark, Color.Transparent, light) else listOf(light, Color.Transparent, dark)
        drawRect(Brush.verticalGradient(colors, startY = 0f, endY = size.height))
    }
}

@Composable
internal fun MovoOver(scene: YtMiniScene) {
    val palette = scene.palette
    val motion = LocalYtMotion.current
    val cobalt = palette?.accent ?: Color(0xFF5260ED)
    val lime = palette?.lime ?: Color(0xFFC9F26F)
    val pink = palette?.pink ?: Color(0xFFE557D6)
    val ink = palette?.ink ?: Color(0xFF101112)

    // the frame snaps in with the landing, a little past its width and back
    val frame = remember { Animatable(1f) }
    val landings = scene.landings
    var seenLanding by remember { mutableIntStateOf(landings) }
    LaunchedEffect(landings) {
        if (landings == seenLanding) return@LaunchedEffect
        if (motion) {
            frame.snapTo(0f)
            seenLanding = landings
            frame.animateTo(1f, spring(dampingRatio = 0.4f, stiffness = 560f))
        } else {
            seenLanding = landings
        }
    }

    // the glyph of a toggle on a small lime tile: pops up past its size, settles, fades
    val flash = remember { Animatable(0f) }
    val flashes = scene.flashes
    var seenFlash by remember { mutableIntStateOf(flashes) }
    LaunchedEffect(flashes) {
        if (flashes == seenFlash) return@LaunchedEffect
        seenFlash = flashes
        if (motion) {
            flash.snapTo(0f)
            flash.animateTo(1f, tween(560, easing = LinearEasing))
        } else {
            flash.snapTo(0.4f)
            delay(450)
        }
        flash.snapTo(0f)
    }

    // a taken drop: a lime flash over the tile
    val accept = remember { Animatable(0f) }
    val accepted = YtDock.accepted
    var seenDrop by remember { mutableIntStateOf(accepted) }
    LaunchedEffect(accepted) {
        if (accepted == seenDrop) return@LaunchedEffect
        seenDrop = accepted
        if (motion) {
            accept.snapTo(1f)
            accept.animateTo(0f, tween(420, easing = LinearEasing))
        }
    }

    // a refused drop: the frame turns pink and back
    val reject = remember { Animatable(0f) }
    val rejects = scene.rejects
    var seenReject by remember { mutableIntStateOf(rejects) }
    LaunchedEffect(rejects) {
        if (rejects == seenReject) return@LaunchedEffect
        seenReject = rejects
        reject.snapTo(1f)
        if (motion) {
            reject.animateTo(0f, tween(520, easing = LinearEasing))
        } else {
            delay(400)
            reject.snapTo(0f)
        }
    }

    Canvas(Modifier.fillMaxSize().clearAndSetSemantics {}) {
        val progress = YtDock.progress
        // only on the docked tile: the overlay moves with the video into fullscreen as well
        val shown = YtMovoMath.smoothstep(0.7f, 1f, progress)
        if (shown <= 0f) return@Canvas
        val radius = YtMiniMovo.spec.thumbRadius.dp.toPx() * progress

        tiltShade(YtMovoShared.tiltX.value, YtMovoShared.tiltY.value)

        val hover = scene.hover()
        if (hover > 0f && YtDock.peek <= 0f) drawRect(lime.copy(alpha = 0.22f * hover * shown))
        val a = accept.value
        if (a > 0f) drawRect(lime.copy(alpha = 0.55f * a))

        // the cobalt frame; before the landing has started it waits at nothing
        val pending = scene.landings != seenLanding
        val snap = if (pending) 0f else frame.value
        val width = YtMovoLayout.BORDER.dp.toPx() * snap.coerceAtLeast(0f)
        if (width > 0.1f) {
            val color = lerp(lerp(cobalt, lime, hover.coerceIn(0f, 1f)), pink, reject.value.coerceIn(0f, 1f))
            drawRoundRect(
                color.copy(alpha = shown),
                topLeft = Offset(width / 2f, width / 2f),
                size = Size(size.width - width, size.height - width),
                cornerRadius = CornerRadius((radius - width / 2f).coerceAtLeast(0f)),
                style = Stroke(width),
            )
        }
        // the snap itself: a light knock inside the frame that is gone as it settles
        if (!pending && snap < 1f) {
            val knock = (1f - snap).coerceIn(0f, 1f)
            drawRoundRect(
                Color.White.copy(alpha = 0.35f * knock * shown),
                cornerRadius = CornerRadius(radius),
                style = Stroke(YtMovoLayout.BORDER.dp.toPx() * 2f),
            )
        }

        val t = flash.value
        if (t > 0f) {
            // up past its size in a quarter, settling, fading in the last half
            val pop = if (t < 0.25f) 0.6f + 0.5f * (t / 0.25f) else 1.1f - 0.1f * ((t - 0.25f) / 0.2f).coerceAtMost(1f)
            val alpha = if (t < 0.5f) 1f else 1f - (t - 0.5f) / 0.5f
            val side = min(size.minDimension * 0.62f, 44.dp.toPx())
            val center = Offset(size.width / 2f, size.height / 2f)
            scale(pop, pop, center) {
                drawRoundRect(
                    lime.copy(alpha = alpha),
                    topLeft = Offset(center.x - side / 2f, center.y - side / 2f),
                    size = Size(side, side),
                    cornerRadius = CornerRadius(12.dp.toPx()),
                )
                movoGlyph(scene.flashPlaying, center, side * 0.2f, ink.copy(alpha = alpha))
            }
        }
    }
}
