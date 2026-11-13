package com.github.libretube.ui.yt.mini

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.github.libretube.ui.yt.LocalYtMotion
import com.github.libretube.ui.yt.YtDock
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/*
 * YT: what the Formen player draws on its picture (the overlay over the video, clipped to its
 * rounded outline): the body colour around a cookie-shaped hole, so the video is seen through a
 * cookie window, with a fine rim; the lime wave of progress around the cookie; the glyph flash
 * on a pink cookie. The window turns slowly while the video plays, turns in when the player
 * lands, leans towards a dragged video, turns a quarter in detents when it takes one and shrugs
 * when it refuses one; in the preview its scallops smooth out into the rounded picture. Every
 * moving value is read while drawing.
 */

/**
 * Seconds that run only while [live] (the video plays, docked, motion on) and stand still
 * otherwise, so the turning shapes stop where they are instead of jumping back. Wraps at 240 s,
 * a whole number of turns for every speed that reads it.
 */
@Composable
internal fun rememberShapesClock(live: Boolean): MutableFloatState {
    val clock = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(live) {
        if (!live) return@LaunchedEffect
        var last = withFrameNanos { it }
        while (true) {
            withFrameNanos { now ->
                clock.floatValue = (clock.floatValue + (now - last) / 1_000_000_000f) % 240f
                last = now
            }
        }
    }
    return clock
}

/**
 * Keeps the landing's [values] at 0 while the player is not docked, so the landing animates them
 * in from there instead of hiding them first. If no landing follows (it only runs with motion on)
 * they are put at rest once the player has sat on the bar for a moment.
 */
@Composable
internal fun ShapesLandingReset(motion: Boolean, values: List<Animatable<Float, AnimationVector1D>>) {
    LaunchedEffect(motion) {
        if (!motion) return@LaunchedEffect
        snapshotFlow { YtDock.progress >= 1f }.collectLatest { docked ->
            if (!docked) {
                values.forEach { it.snapTo(0f) }
            } else {
                delay(250)
                values.forEach { if (!it.isRunning && it.value == 0f) it.snapTo(1f) }
            }
        }
    }
}

/** The degrees per second of the window and of the pink cookie behind play. */
internal const val SHAPES_WINDOW_SPEED = 12f
internal const val SHAPES_MARK_SPEED = 18f

/** The wave's travel: one wave length in 1.6 s, as on Formen's home. */
internal const val SHAPES_WAVE_SPEED = 1f / 1.6f

/** A scalloped circle of [lobes] around [center] into [path] (the flash's cookie, the ripple). */
internal fun scallops(path: Path, center: Offset, radius: Float, lobes: Int, depth: Float, turn: Float = 0f) {
    path.reset()
    val steps = lobes * 12
    for (i in 0..steps) {
        val angle = 2f * PI.toFloat() * i / steps
        val r = YtShapesWindow.cookie(angle, radius, radius, depth, turn, 1f, lobes)
        val x = center.x + r * sin(angle)
        val y = center.y - r * cos(angle)
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
}

/** ▶ while it plays, ❚❚ while paused, with soft corners as Formen's shapes. */
internal fun DrawScope.shapesGlyph(playing: Boolean, center: Offset, radius: Float, color: Color) {
    if (playing) {
        val path = Path().apply {
            moveTo(center.x - radius * 0.6f, center.y - radius)
            lineTo(center.x + radius, center.y)
            lineTo(center.x - radius * 0.6f, center.y + radius)
            close()
        }
        drawPath(path, color)
    } else {
        val bar = radius * 0.65f
        val corner = androidx.compose.ui.geometry.CornerRadius(bar / 2f)
        drawRoundRect(color, Offset(center.x - radius * 0.85f, center.y - radius), Size(bar, radius * 2f), corner)
        drawRoundRect(color, Offset(center.x + radius * 0.85f - bar, center.y - radius), Size(bar, radius * 2f), corner)
    }
}

@Composable
internal fun ShapesOver(design: YtMiniShapes, scene: YtMiniScene) {
    val palette = scene.palette
    val motion = LocalYtMotion.current
    val density = LocalDensity.current.density
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val lime = palette?.lime ?: Color(0xFFC6F36B)
    val pink = palette?.pink ?: Color(0xFFF4A9B8)
    val ink = palette?.ink ?: Color(0xFF17132B)
    val body = design.body
    val track = Color(0x26FFFFFF)
    val merging = remember(palette) { palette != null && design.merge(palette) != null }

    val playing by remember { derivedStateOf { scene.info.playing } }
    val clock = rememberShapesClock(motion && playing && YtDock.docked)

    // the landing: the cookie turns in and its scallops wobble once (deep and turned back by
    // 60°, which the 12 scallops cannot tell from 0°, while the player comes down)
    val settle = remember { Animatable(1f) }
    ShapesLandingReset(motion, listOf(settle))
    val landings = scene.landings
    var seenLanding by remember { mutableIntStateOf(landings) }
    LaunchedEffect(landings) {
        if (landings == seenLanding) return@LaunchedEffect
        if (motion) {
            settle.snapTo(0f)
            seenLanding = landings
            settle.animateTo(1f, spring(dampingRatio = 0.42f, stiffness = 360f))
        } else {
            seenLanding = landings
        }
    }

    // a taken drop: a quarter turn in three detents (the 12 scallops look the same every 30°,
    // so snapping back to 0 afterwards shows nothing)
    val accept = remember { Animatable(0f) }
    val accepted = YtDock.accepted
    var seenDrop by remember { mutableIntStateOf(accepted) }
    LaunchedEffect(accepted) {
        if (accepted == seenDrop) return@LaunchedEffect
        seenDrop = accepted
        if (motion) {
            accept.snapTo(0f)
            accept.animateTo(1f, tween(ACCEPT_MS, easing = LinearEasing))
            accept.snapTo(0f)
        }
    }

    // a refused drop: the cookie shrugs and its rim flashes pink
    val reject = remember { Animatable(0f) }
    val rejects = scene.rejects
    var seenReject by remember { mutableIntStateOf(rejects) }
    LaunchedEffect(rejects) {
        if (rejects == seenReject) return@LaunchedEffect
        seenReject = rejects
        if (motion) {
            reject.snapTo(0f)
            reject.animateTo(1f, tween(480, easing = LinearEasing))
        } else {
            reject.snapTo(0.5f)
            delay(400)
        }
        reject.snapTo(0f)
    }

    // the glyph of a toggle on a pink cookie that turns in, pops and fades
    val flash = remember { Animatable(0f) }
    val flashes = scene.flashes
    var seenFlash by remember { mutableIntStateOf(flashes) }
    LaunchedEffect(flashes) {
        if (flashes == seenFlash) return@LaunchedEffect
        seenFlash = flashes
        if (motion) {
            flash.snapTo(0f)
            flash.animateTo(1f, tween(600, easing = LinearEasing))
        } else {
            flash.snapTo(0.35f)
            delay(450)
        }
        flash.snapTo(0f)
    }

    // reused every frame: no allocation while the window turns
    val points = remember { FloatArray(2 * YtShapesLayout.POINTS) }
    val hole = remember { Path() }
    val cover = remember { Path() }
    val wave = remember { Path() }
    val ringPath = remember { Path() }
    val cookie = remember { Path() }

    Canvas(Modifier.fillMaxSize().clearAndSetSemantics {}) {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@Canvas
        // the seamless minimize shapes the window by the page's own progress: a thrown player
        // keeps its cookie while it falls (the bar lets go of YtDock.progress at once)
        val progress = if (merging) scene.pageProgress else YtDock.progress
        val peek = YtDock.peek
        val a = w / 2f
        val b = h / 2f
        val center = Offset(a, b)
        val hover = if (peek <= 0f) scene.hover() else 0f
        val landed = if (scene.landings != seenLanding) 0f else settle.value
        val r = reject.value
        val refusing = r > 0f && r < 1f
        val spin = clock.floatValue * SHAPES_WINDOW_SPEED
        val turn = spin + 15f * hover - 60f * (1f - landed) +
            YtShapesWindow.detents(acceptStep(accept.value)) +
            (if (refusing) 14f * YtShapesWindow.wobble(r, 2.5f, 4f) else 0f)
        val depth = (YtShapesLayout.DEPTH * (1f + 0.5f * hover) * (1f + 1.4f * (1f - landed))).coerceAtLeast(0f)
        val outline = design.spec.thumbRadius * density
        val shape = YtShapesWindow.shape(progress, peek, a, b, outline, depth, if (rtl) -turn else turn)

        if (shape.cookie > 0.002f) {
            // 1: the frame in the body's colour around the cookie
            YtShapesWindow.outline(w, h, shape, points)
            hole.reset()
            hole.moveTo(points[0], points[1])
            var i = 2
            while (i < points.size) {
                hole.lineTo(points[i], points[i + 1])
                i += 2
            }
            hole.close()
            cover.reset()
            cover.fillType = PathFillType.EvenOdd
            // past the picture's edges on every side: the mask covers all of the picture's
            // rectangle outside the cookie (the layer is clipped to the picture's outline)
            cover.addRect(Rect(-w, -h, 2f * w, 2f * h))
            cover.addPath(hole)
            drawPath(cover, body)

            // 2: the window's rim: fine and light, lime as a video comes near, pink when refused
            val rim = 1.dp.toPx()
            drawPath(hole, Color.White.copy(alpha = 0.14f * shape.cookie), style = Stroke(rim))
            if (hover > 0f) drawPath(hole, lime.copy(alpha = hover * shape.cookie), style = Stroke(2f * rim))
            if (refusing) drawPath(hole, pink.copy(alpha = (1f - r) * shape.cookie), style = Stroke(2.5f * rim))

            // 3: the lime wave of progress around the cookie, from the top, clockwise
            val shown = shape.cookie * YtShapesWindow.smoothstep(0.85f, 1f, progress)
            if (shown > 0.01f) {
                // the track runs around the cookie's own ellipse (from this frame's size, as the
                // window does), so it stays on the cookie when the picture is not square
                val gap = YtShapesLayout.RING_GAP * density
                ringPath.reset()
                val steps = YtShapesLayout.POINTS
                for (k in 0..steps) {
                    val angle = 2f * PI.toFloat() * k / steps
                    val radius = YtShapesWindow.ringBase(angle, a, b, gap)
                    val x = center.x + radius * sin(angle)
                    val y = center.y - radius * cos(angle)
                    if (k == 0) ringPath.moveTo(x, y) else ringPath.lineTo(x, y)
                }
                ringPath.close()
                drawPath(
                    ringPath,
                    if (hover > 0f) androidx.compose.ui.graphics.lerp(track, lime.copy(alpha = 0.5f), hover) else track,
                    alpha = shown,
                    style = Stroke(1.5f * density),
                )
                val fraction = scene.info.fraction.coerceIn(0f, 1f)
                if (fraction > 0f) {
                    val sweep = 2f * PI.toFloat() * fraction
                    val n = (fraction * 120f).toInt().coerceAtLeast(2)
                    val phase = clock.floatValue * SHAPES_WAVE_SPEED
                    val side = if (rtl) -1f else 1f
                    wave.reset()
                    for (k in 0..n) {
                        val angle = sweep * k / n
                        val base = YtShapesWindow.ringBase(side * angle, a, b, gap)
                        val radius = YtShapesWindow.ring(angle, base, YtShapesLayout.RING_AMPLITUDE * density, phase)
                        val x = center.x + side * radius * sin(angle)
                        val y = center.y - radius * cos(angle)
                        if (k == 0) wave.moveTo(x, y) else wave.lineTo(x, y)
                    }
                    drawPath(wave, lime.copy(alpha = shown), style = Stroke(2.5f * density, cap = StrokeCap.Round))
                }
            }
        }

        // 4: the glyph flash on a pink cookie
        val t = flash.value
        if (t > 0f) {
            val alpha = if (t < 0.2f) t / 0.2f else 1f - ((t - 0.5f) / 0.5f).coerceIn(0f, 1f)
            val pop = if (t < 0.25f) 0.6f + 0.5f * (t / 0.25f) else 1.1f - 0.1f * ((t - 0.25f) / 0.25f).coerceAtMost(1f)
            val radius = min(min(a, b) * 0.62f, 22.dp.toPx()) * pop
            rotate(-50f * (1f - t), center) {
                scallops(cookie, center, radius, 6, 0.1f)
                drawPath(cookie, pink.copy(alpha = alpha))
            }
            shapesGlyph(scene.flashPlaying, center, radius * 0.36f, ink.copy(alpha = alpha))
        }
    }
}
