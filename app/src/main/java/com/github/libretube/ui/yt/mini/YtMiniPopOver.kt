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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import com.github.libretube.ui.yt.LocalYtMotion
import com.github.libretube.ui.yt.YtDock
import kotlinx.coroutines.delay
import kotlin.math.min

/*
 * YT: what the Pop player draws on its picture (the overlay over the video, clipped to its
 * rounded outline): a pink rim and a pink wash as a dragged card comes near, a white pop when
 * the blob has gulped one, and the play and pause glyph in plum on a small butter-yellow blob
 * that pops up past its size like a sticker. Every moving value is read while drawing.
 */

/** ▶ while it plays, ❚❚ while paused, with round corners as Pop's type. */
private fun DrawScope.popGlyph(playing: Boolean, center: Offset, radius: Float, color: Color, path: Path) {
    val round = Stroke(radius * 0.35f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    path.reset()
    if (playing) {
        path.moveTo(center.x - radius * 0.55f, center.y - radius * 0.8f)
        path.lineTo(center.x + radius * 0.8f, center.y)
        path.lineTo(center.x - radius * 0.55f, center.y + radius * 0.8f)
        path.close()
        drawPath(path, color)
        drawPath(path, color, style = round)
    } else {
        val bar = radius * 0.5f
        val corner = CornerRadius(bar / 2f)
        drawRoundRect(color, Offset(center.x - radius * 0.75f, center.y - radius * 0.85f), Size(bar, radius * 1.7f), corner)
        drawRoundRect(color, Offset(center.x + radius * 0.75f - bar, center.y - radius * 0.85f), Size(bar, radius * 1.7f), corner)
    }
}

@Composable
internal fun PopOver(scene: YtMiniScene) {
    val palette = scene.palette
    val motion = LocalYtMotion.current
    val pink = palette?.pink ?: Color(0xFFE0457F)
    // plum on the butter-yellow blob in both modes (at night the accent is a light lilac)
    val plum = PopColors.plum

    // the glyph of a toggle on a yellow blob: pops past its size, settles, fades
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
            flash.snapTo(0.4f)
            delay(450)
        }
        flash.snapTo(0f)
    }

    // the gulp is done: the picture pops white once, as the card goes down
    val accept = remember { Animatable(0f) }
    val accepted = YtDock.accepted
    var seenDrop by remember { mutableIntStateOf(accepted) }
    LaunchedEffect(accepted) {
        if (accepted == seenDrop) return@LaunchedEffect
        seenDrop = accepted
        if (motion) {
            // after the chomp (YtPopMath's gulp)
            delay(180)
            accept.snapTo(1f)
            accept.animateTo(0f, tween(360, easing = LinearEasing))
        }
    }

    val glyphPath = remember { Path() }
    val blobPath = remember { Path() }
    val points = remember { FloatArray(2 * (FLASH_POINTS + 1)) }

    Canvas(Modifier.fillMaxSize().clearAndSetSemantics {}) {
        val progress = YtDock.progress
        // only on the docked sticker: the overlay moves with the video into fullscreen as well
        val shown = YtPopMath.smoothstep(0.7f, 1f, progress)
        if (shown <= 0f) return@Canvas
        val radius = YtMiniPop.spec.thumbRadius.dp.toPx() * progress
        val corner = CornerRadius(min(radius, size.minDimension / 2f))

        val hover = if (YtDock.peek > 0f) 0f else scene.hover()
        if (hover > 0f) {
            drawRoundRect(pink.copy(alpha = 0.16f * hover * shown), cornerRadius = corner)
            val rim = 3.dp.toPx()
            drawRoundRect(
                pink.copy(alpha = hover * shown),
                topLeft = Offset(rim / 2f, rim / 2f),
                size = Size(size.width - rim, size.height - rim),
                cornerRadius = CornerRadius((corner.x - rim / 2f).coerceAtLeast(0f)),
                style = Stroke(rim),
            )
        }
        val a = accept.value
        if (a > 0f) drawRoundRect(Color.White.copy(alpha = 0.45f * a * shown), cornerRadius = corner)

        val t = flash.value
        if (t > 0f) {
            val alpha = YtPopMath.flashAlpha(t) * shown
            val pop = YtPopMath.flashScale(t)
            val r = min(size.minDimension * 0.32f, 22.dp.toPx())
            val center = Offset(size.width / 2f, size.height / 2f)
            rotate(YtPopMath.lerp(-12f, 0f, (t / 0.3f).coerceAtMost(1f)), center) {
                scale(pop, pop, center) {
                    val count = YtPopMath.blobPoints(
                        cx = center.x,
                        cy = center.y,
                        rx = r,
                        ry = r * 0.94f,
                        n = 2.3f,
                        pill = 0f,
                        amp = 0.06f,
                        phase = 1.1f + 2f * t,
                        mouth = 0f,
                        facing = -90f,
                        count = FLASH_POINTS,
                        out = points,
                    )
                    blobPath.reset()
                    blobPath.moveTo(points[0], points[1])
                    var i = 2
                    while (i + 1 < count) {
                        blobPath.lineTo(points[i], points[i + 1])
                        i += 2
                    }
                    blobPath.close()
                    drawPath(blobPath, PopColors.yellow.copy(alpha = alpha))
                    popGlyph(scene.flashPlaying, center, r * 0.45f, plum.copy(alpha = alpha), glyphPath)
                }
            }
        }
    }
}

private const val FLASH_POINTS = 40
