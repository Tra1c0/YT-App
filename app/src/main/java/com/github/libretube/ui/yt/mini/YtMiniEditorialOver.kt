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
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import com.github.libretube.ui.yt.LocalYtMotion
import kotlinx.coroutines.delay
import kotlin.math.min

/*
 * YT: what the Editorial player draws on its picture (the overlay over the video, clipped to
 * it). The video is a SurfaceView, which cannot be masked: the stamp is drawn over it. Every
 * moving value is read while drawing.
 */

/** How long the stamp stands before it is wiped off, and how long the wipe takes. */
private const val STAMP_HOLD = 0.7f
private const val STAMP_MS = 520

@Composable
internal fun EditorialOver(scene: YtMiniScene) {
    val motion = LocalYtMotion.current
    val ink = scene.palette?.text ?: Color(0xFF0A0A0A)
    val paper = scene.palette?.background ?: Color.White
    val accent = scene.palette?.accent ?: Color(0xFF0F54F6)

    // the glyph flash of a toggle (the play button, the slip): a black square stamped on the
    // picture at once, no fade in, standing, then wiped off from the start edge
    val flash = remember { Animatable(0f) }
    val flashes = scene.flashes
    var seen by remember { mutableIntStateOf(flashes) }
    LaunchedEffect(flashes) {
        if (flashes == seen) return@LaunchedEffect
        seen = flashes
        if (motion) {
            flash.snapTo(0.001f)
            flash.animateTo(1f, tween(STAMP_MS, easing = LinearEasing))
        } else {
            flash.snapTo(0.3f)
            delay(450)
        }
        flash.snapTo(0f)
    }

    Canvas(Modifier.fillMaxSize().clearAndSetSemantics {}) {
        val t = flash.value
        if (t <= 0f) return@Canvas
        val side = min(size.minDimension * 0.5f, 40.dp.toPx())
        val left = (size.width - side) / 2f
        val top = (size.height - side) / 2f
        // the wipe runs from the start edge to the end over the last part of the flash
        val gone = YtEditorialMath.ramp(STAMP_HOLD, 1f, t)
        val rtl = layoutDirection == androidx.compose.ui.unit.LayoutDirection.Rtl
        val cut = side * gone
        val clipLeft = if (rtl) left else left + cut
        val clipRight = if (rtl) left + side - cut else left + side
        clipRect(left = clipLeft, top = top, right = clipRight, bottom = top + side) {
            drawRect(ink, Offset(left, top), Size(side, side))
            // the blue mark on the stamp's top edge, as on the bar
            drawRect(accent, Offset(left, top), Size(side, 2.dp.toPx()))
            editorialGlyph(scene.flashPlaying, Offset(left + side / 2f, top + side / 2f), side * 0.2f, paper)
        }
    }
}
