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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import com.github.libretube.ui.yt.LocalYtMotion
import com.github.libretube.ui.yt.YtDock
import kotlinx.coroutines.delay
import kotlin.math.min

/*
 * YT: what the After player draws on its picture (the overlay over the video, clipped to it).
 * The video is a SurfaceView, which cannot be masked: the gate's black bars are drawn over it.
 * Every moving value (minimize progress, preview) is read while drawing.
 */

/** The colours of the projection. */
internal object YtAfterColors {
    val gate = Color(0xFF0A0305)
    val accent = Color(0xFFFF8B4B)
    val bone = Color(0xFFFFF4DF)
    val leader = Color(0xCC120609)
}

/** How long the lamp flickers on landing: as long as the rattle. */
private const val LAMP_MS = YtAfterMath.RATTLE_STEP * YtAfterMath.RATTLE_STEPS

@Composable
internal fun AfterOver(scene: YtMiniScene) {
    val motion = LocalYtMotion.current
    val accent = scene.palette?.accent ?: YtAfterColors.accent
    val bone = scene.palette?.text ?: YtAfterColors.bone
    val scope = YtAfterLayout.scope(YtMiniAfter.spec)

    // the glyph flash of a toggle (the play button, the ticket): a projector's countdown leader
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
            flash.snapTo(0.3f)
            delay(450)
        }
        flash.snapTo(0f)
    }

    // the lamp strikes as the player lands, in step with the rattle (1 = done)
    val lamp = remember { Animatable(1f) }
    val landings = scene.landings
    LaunchedEffect(landings) {
        if (landings > 0 && motion) {
            lamp.snapTo(0f)
            lamp.animateTo(1f, tween(LAMP_MS, easing = LinearEasing))
        }
    }

    // a dropped card: once its frame is pulled into the gate (drawn under the picture), the
    // picture is briefly exposed brighter
    val exposure = remember { Animatable(0f) }
    val accepted = YtDock.accepted
    var seenDrop by remember { mutableIntStateOf(accepted) }
    LaunchedEffect(accepted) {
        if (accepted == seenDrop) return@LaunchedEffect
        seenDrop = accepted
        if (motion) {
            delay(YtAfterMath.PULL_MS.toLong())
            exposure.snapTo(1f)
            exposure.animateTo(0f, tween(260, easing = LinearEasing))
        }
    }

    Canvas(Modifier.fillMaxSize().clearAndSetSemantics {}) {
        val w = size.width
        val h = size.height

        // the gate: closes while minimizing, opens in the preview
        val bar = YtAfterMath.gateBar(w, h, scope, YtAfterMath.gateClosed(YtDock.progress, YtDock.peek))
        if (bar >= 0.5f) {
            drawRect(YtAfterColors.gate, Offset.Zero, Size(w, bar))
            drawRect(YtAfterColors.gate, Offset(0f, h - bar), Size(w, bar))
            // the edges of the gate catch a little of the lamp
            val edge = 1.dp.toPx()
            drawRect(bone, Offset(0f, bar - edge), Size(w, edge), alpha = 0.12f)
            drawRect(bone, Offset(0f, h - bar), Size(w, edge), alpha = 0.12f)
        }

        if (lamp.value < 1f) {
            val f = YtAfterMath.flicker(lamp.value * LAMP_MS)
            if (f > 0f) drawRect(bone, alpha = f)
        }
        val e = exposure.value
        if (e > 0f) drawRect(bone, alpha = 0.22f * e)

        val t = flash.value
        if (t > 0f) leader(t, scene.flashPlaying, accent, bone)
    }
}

/**
 * The flash as a countdown leader: a dark disc with crosshairs and rings, a tangerine sector
 * sweeping round once, the glyph of play or pause in bone.
 */
private fun DrawScope.leader(t: Float, playing: Boolean, accent: Color, bone: Color) {
    val shown = if (t < 0.2f) t / 0.2f else 1f - (t - 0.2f) / 0.8f
    if (shown <= 0f) return
    val radius = min(size.minDimension * 0.36f, 22.dp.toPx())
    val c = center
    val box = Size(2f * radius, 2f * radius)
    val topLeft = Offset(c.x - radius, c.y - radius)
    drawCircle(YtAfterColors.leader, radius, c, alpha = shown)
    drawArc(accent, -90f, 360f * t, useCenter = true, topLeft = topLeft, size = box, alpha = 0.4f * shown)
    val hair = 0.8f.dp.toPx()
    drawLine(bone, Offset(c.x - radius, c.y), Offset(c.x + radius, c.y), hair, alpha = 0.3f * shown)
    drawLine(bone, Offset(c.x, c.y - radius), Offset(c.x, c.y + radius), hair, alpha = 0.3f * shown)
    drawCircle(bone, radius * 0.72f, c, alpha = 0.35f * shown, style = Stroke(hair))
    drawCircle(bone, radius, c, alpha = 0.9f * shown, style = Stroke(1.2f.dp.toPx()))
    afterGlyph(playing, c, radius * 0.4f, bone, shown)
}

/** ▶ while it plays, ❚❚ while paused, at [center] ([r] half its height). */
internal fun DrawScope.afterGlyph(playing: Boolean, center: Offset, r: Float, color: Color, alpha: Float) {
    if (playing) {
        val path = Path().apply {
            moveTo(center.x - r * 0.6f, center.y - r)
            lineTo(center.x + r, center.y)
            lineTo(center.x - r * 0.6f, center.y + r)
            close()
        }
        drawPath(path, color, alpha = alpha)
    } else {
        val bar = r * 0.6f
        drawRect(color, Offset(center.x - r * 0.85f, center.y - r), Size(bar, r * 2f), alpha = alpha)
        drawRect(color, Offset(center.x + r * 0.85f - bar, center.y - r), Size(bar, r * 2f), alpha = alpha)
    }
}
