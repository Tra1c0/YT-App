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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.semantics.clearAndSetSemantics
import com.github.libretube.ui.yt.LocalYtMotion
import kotlinx.coroutines.delay
import kotlin.math.min

/*
 * YT: what the Clay player ("Knetkugel") draws over its picture, clipped to it: the shading of
 * the dent the picture is pressed into (shadow under the dough's upper lip, light on the lower
 * one), and the glyph flash of play and pause as a small mauve ball of dough that plops in,
 * squashes and sinks away.
 */

@Composable
internal fun ClayOver(scene: YtMiniScene) {
    val flash = remember { Animatable(0f) }
    val flashes = scene.flashes
    var seen by remember { mutableIntStateOf(flashes) }
    val motion = LocalYtMotion.current
    LaunchedEffect(flashes) {
        if (flashes == seen) return@LaunchedEffect
        seen = flashes
        if (motion) {
            flash.snapTo(0f)
            flash.animateTo(1f, tween(620, easing = LinearEasing))
        } else {
            flash.snapTo(0.35f)
            delay(450)
        }
        flash.snapTo(0f)
    }
    val playing = scene.flashPlaying
    val blob = remember { Path() }

    Canvas(Modifier.fillMaxSize().clearAndSetSemantics {}) {
        val d = density
        val w = size.width
        val h = size.height
        // only on the bar: the full player and the fullscreen keep a clean picture
        val shade = YtMiniClayLayout.smooth(scene.progress(), 0.85f, 1f)
        if (shade > 0f) {
            val lip = 7f * d
            drawRect(
                Brush.verticalGradient(listOf(Color(0x4D5C3A3A), Color.Transparent), startY = 0f, endY = lip),
                size = Size(w, lip),
                alpha = shade,
            )
            val side = 5f * d
            drawRect(
                Brush.horizontalGradient(listOf(Color(0x265C3A3A), Color.Transparent), startX = 0f, endX = side),
                size = Size(side, h),
                alpha = shade,
            )
            drawRect(
                Brush.horizontalGradient(listOf(Color.Transparent, Color(0x265C3A3A)), startX = w - side, endX = w),
                topLeft = Offset(w - side, 0f),
                size = Size(side, h),
                alpha = shade,
            )
            val low = 4f * d
            drawRect(
                Brush.verticalGradient(listOf(Color.Transparent, Color(0x33FFFFFF)), startY = h - low, endY = h),
                topLeft = Offset(0f, h - low),
                size = Size(w, low),
                alpha = shade,
            )
        }

        val t = flash.value
        if (t <= 0f) return@Canvas
        // plops in (a quarter), squashes once, then sinks away while it fades
        val shown = if (t < 0.22f) t / 0.22f else 1f - YtMiniClayLayout.smooth(t, 0.55f, 1f)
        val r = min(min(w, h) * 0.34f, 24f * d) * (0.8f + 0.25f * YtMiniClayLayout.smooth(t, 0f, 0.3f))
        val squash = 0.12f * YtMiniDesign.wobble(t, 1.6f, 4f)
        val c = Offset(w / 2f, h / 2f + 4f * d * YtMiniClayLayout.smooth(t, 0.55f, 1f))
        scale(1f + squash, 1f - squash, Offset(c.x, c.y + r)) {
            blob.clayBlob(c.x - r, c.y - r, c.x + r, c.y + r, 0.55f, 0.45f, 0.5f, 0.6f)
            drawPath(blob, Brush.verticalGradient(listOf(ClayStyle.MAUVE_TOP, ClayStyle.MAUVE), startY = c.y - r, endY = c.y + r), alpha = 0.94f * shown)
            val sheen = Offset(c.x - r * 0.35f, c.y - r * 0.4f)
            drawCircle(
                Brush.radialGradient(listOf(Color.White.copy(alpha = 0.45f), Color.Transparent), center = sheen, radius = r * 0.5f),
                r * 0.5f,
                sheen,
                alpha = shown,
            )
            clayGlyph(playing, Color.White.copy(alpha = shown), c, r * 0.42f)
        }
    }
}
