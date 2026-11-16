package com.github.libretube.ui.yt.mini

import android.graphics.BlurMaskFilter
import android.os.SystemClock
import android.view.ViewConfiguration
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import com.github.libretube.ui.yt.YtDock
import com.github.libretube.ui.yt.builder.YtAnimInput
import com.github.libretube.ui.yt.builder.YtBuilderPaint
import com.github.libretube.ui.yt.builder.YtComponentKind
import com.github.libretube.ui.yt.builder.YtDocOps
import com.github.libretube.ui.yt.builder.YtNode
import com.github.libretube.ui.yt.builder.YtSlot
import com.github.libretube.ui.yt.builder.YtSlotData
import com.github.libretube.ui.yt.builder.YtTapArbiter
import com.github.libretube.ui.yt.builder.YtThemeComponent
import com.github.libretube.ui.yt.builder.YtThemeSource
import com.github.libretube.ui.yt.builder.YtTriggerKind
import com.github.libretube.ui.yt.builder.rememberYtAppThemeSource
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/*
 * YT: the mini player of "Eigenes" drawn from the theme's mini player component, as the editor's
 * preview shows it: its whole node tree (fills, gradients, outlines, texts, shapes, images, the
 * title, channel and progress slots with the real video's values) at the docked size, clipped to
 * the body that joins the bar, with the component's animations running (touches on the docked
 * box, appear when it docks, the player playing).
 *
 * The video is a SurfaceView (Pitfall P5): nothing is drawn over it but outline-following shapes.
 * A THUMBNAIL slot in the tree keeps its place but does not draw there: its shadows and fills are
 * drawn behind the real picture, following its live bounds (minimize, preview), and its outline
 * along the picture's own rounded outline over the video ([YtMiniCustomOver]).
 */

/** The first shown THUMBNAIL slot of the mini player component (the picture's own look), or null. */
private fun pictureNode(src: YtThemeSource): YtNode? =
    src.doc?.let { d -> YtDocOps.all(d.components.mini).firstOrNull { it.visible && it.slot == YtSlot.THUMBNAIL } }

@Composable
internal fun YtMiniCustomUnder(design: YtMiniCustom, scene: YtMiniScene, look: YtMiniLook, style: YtMiniParts.Style) {
    val src = rememberYtAppThemeSource()
    val density = LocalDensity.current.density
    // the box's height when docked, as the constraints give it (whole px)
    val restBox = ((look.height + look.spec.overlap) * density).roundToInt().toFloat()
    // how far the preview has grown the box: the foot of the body stays on the bar
    fun grow(height: Float): Float = if (YtDock.peek > 0f) (height - restBox).coerceAtLeast(0f) else 0f
    val info = scene.info
    val slots = remember(info.title, info.channel) {
        YtSlotData(progress = { scene.info.fraction }, title = info.title, channel = info.channel, hostPicture = true)
    }
    val touches = rememberYtMiniTouches(scene, src)
    // appear when it docks, disappear when it opens; the loops rest while the page covers it
    val input = remember(touches) { YtAnimInput(visible = { YtDock.docked }, events = touches, shown = { YtDock.progress > 0f }) }
    val picture by remember(src) { derivedStateOf { pictureNode(src) } }
    val bodyShape = RoundedCornerShape(topStart = look.radius.dp, topEnd = look.radius.dp)

    Box(Modifier.fillMaxSize()) {
        // 1: the body's shadow, its foot in the bar, and its plain surface while the preview grows it
        val body = remember(look) {
            GenericShape { size, _ -> addPath(design.bodyPath(look, size.width, size.height, density, open = false, grow = grow(size.height))) }
        }
        Box(
            Modifier
                .fillMaxSize()
                .then(if (look.shadow != null) Modifier.shadow(10.dp, body, clip = false, ambientColor = look.shadow, spotColor = look.shadow) else Modifier)
                .drawBehind {
                    val grown = grow(size.height)
                    val content = look.height * density + grown
                    if (size.height > content) drawRect(look.fill, Offset(0f, content), Size(size.width, size.height - content))
                    val plain = 1f - YtMiniParts.restFade(scene.peek())
                    if (plain > 0f) {
                        val path = design.bodyPath(look, size.width, size.height, density, open = false, grow = grown)
                        drawPath(path, Brush.verticalGradient(listOf(look.fillTop ?: look.fill, look.fill), endY = content), alpha = plain)
                        look.border?.let { border ->
                            drawPath(design.bodyPath(look, size.width, size.height, density, open = true, grow = grown), border, alpha = plain, style = Stroke(look.borderWidth * density))
                        }
                    }
                },
        )

        // 2: the picture's own shadows and fills behind the video, where the picture is now
        picture?.let { node -> PictureBehind(scene, src, node, look.spec.thumbRadius * density) }

        // 3: the theme's mini player at its docked size, clipped to the body; it gives way to the preview
        Box(
            Modifier
                .fillMaxWidth()
                .requiredHeight(look.height.dp)
                .align(Alignment.TopStart)
                .graphicsLayer {
                    alpha = YtMiniParts.restFade(scene.peek())
                    shape = bodyShape
                    clip = true
                },
        ) {
            YtThemeComponent(src, YtComponentKind.MINI, slots, Modifier.fillMaxSize(), input = input)
        }

        // 4: the drop target (outline and label) on the docked row, then the preview's parts
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .requiredHeight((restBox / density).dp)
                .align(Alignment.TopStart)
                .graphicsLayer { alpha = YtMiniParts.restFade(scene.peek()) },
        ) {
            val w = constraints.maxWidth.toFloat()
            val g = remember(w, density) { design.geometry(w, density) }
            val outline: (Size) -> androidx.compose.ui.graphics.Path = remember(look, density) {
                { size: Size -> design.bodyPath(look, size.width, size.height, density, open = true, grow = 0f) }
            }
            YtMiniParts.Drop(scene, style, g.text, outline)
        }
        YtMiniParts.Peek(scene, style)
    }
}

/** The THUMBNAIL slot's drop shadows and fills around the live picture (rounded as the picture is). */
@Composable
private fun PictureBehind(scene: YtMiniScene, src: YtThemeSource, node: YtNode, radiusPx: Float) {
    val shadows = node.style.shadows.filter { it.enabled && !it.inner }
    val fills = node.style.fills.filter { it.enabled }
    if (shadows.isEmpty() && fills.isEmpty()) return
    val paints = remember(shadows) {
        shadows.map { s ->
            android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = s.color
                if (s.blur > 0f) maskFilter = BlurMaskFilter((s.blur * scene.density / 2f).coerceAtLeast(0.5f), BlurMaskFilter.Blur.NORMAL)
            }
        }
    }
    Canvas(Modifier.fillMaxSize().clearAndSetSemantics {}) {
        scene.pictureMoves
        val r = scene.picture() ?: return@Canvas
        val radius = radiusPx * YtDock.progress
        drawIntoCanvas { c ->
            val nc = c.nativeCanvas
            shadows.forEachIndexed { i, s ->
                val sp = s.spread * density
                nc.drawRoundRect(
                    r.left + s.x * density - sp, r.top + s.y * density - sp, r.right + s.x * density + sp, r.bottom + s.y * density + sp,
                    radius + sp, radius + sp, paints[i],
                )
            }
        }
        val t = src.resolver ?: return@Canvas
        translate(r.left, r.top) {
            for (f in fills) {
                runCatching { YtBuilderPaint.brush(t.resolve(f), r.size) }.getOrNull()?.let { b ->
                    drawRoundRect(b, size = r.size, cornerRadius = CornerRadius(radius))
                }
            }
        }
    }
}

/**
 * Over the video: only the THUMBNAIL slot's outline along the picture's rounded outline (the
 * overlay is the picture's own size and clipped by that outline), then the play/pause flash.
 */
@Composable
internal fun YtMiniCustomOver(scene: YtMiniScene, spec: YtMiniSpec) {
    val src = rememberYtAppThemeSource()
    val picture by remember(src) { derivedStateOf { pictureNode(src) } }
    val strokes = picture?.style?.strokes?.filter { it.enabled && it.width > 0f }.orEmpty()
    if (strokes.isNotEmpty()) {
        Canvas(Modifier.fillMaxSize().clearAndSetSemantics {}) {
            val t = src.resolver ?: return@Canvas
            // the outline the host gives the picture: its corner grows with the minimize progress
            val radius = spec.thumbRadius * density * YtDock.progress
            var inset = 0f
            for (s in strokes) {
                val w = s.width * density
                val color = runCatching { YtBuilderPaint.color(t.resolve(s.paint)) }.getOrNull() ?: continue
                val half = inset + w / 2f
                drawRoundRect(
                    color,
                    topLeft = Offset(half, half),
                    size = Size((size.width - 2f * half).coerceAtLeast(0f), (size.height - 2f * half).coerceAtLeast(0f)),
                    cornerRadius = CornerRadius((radius - half).coerceAtLeast(0f)),
                    style = Stroke(w),
                )
                inset += w
            }
        }
    }
    YtMiniParts.Flash(scene, YtMiniParts.flashColors(scene))
}

/**
 * The touches on the docked box as the animator's events. The player page takes these touches
 * itself, so they come from the host's finger signal ([YtMiniScene.finger], only on the docked box,
 * not on its buttons): touch down/up, long press, and tap or double tap by YtTapArbiter (a tap
 * waits for the double-tap window only where the mini player has a double-tap animation).
 */
@Composable
private fun rememberYtMiniTouches(scene: YtMiniScene, src: YtThemeSource): Flow<YtTriggerKind> {
    val context = LocalContext.current
    val events = remember { MutableSharedFlow<YtTriggerKind>(extraBufferCapacity = 16) }
    LaunchedEffect(scene, src) {
        val config = ViewConfiguration.get(context)
        val slop = config.scaledTouchSlop.toFloat()
        val longMs = ViewConfiguration.getLongPressTimeout().toLong()
        val taps = YtTapArbiter(false, ViewConfiguration.getDoubleTapTimeout().toLong(), slop)
        var start: Offset? = null
        var moved = false
        var long = false
        var hold: Job? = null
        var expiry: Job? = null
        fun send(t: YtTriggerKind?) {
            if (t != null) events.tryEmit(t)
        }
        snapshotFlow { scene.finger }.collect { f ->
            val now = SystemClock.uptimeMillis()
            val s = start
            when {
                f != null && s == null -> {
                    taps.waitForDouble = src.animIndex(YtComponentKind.MINI)?.doubleTap == true
                    expiry?.cancel()
                    start = f
                    moved = false
                    long = false
                    send(taps.down(now, f.x, f.y))
                    send(YtTriggerKind.TOUCH_DOWN)
                    hold = launch {
                        delay(longMs)
                        if (!moved) {
                            long = true
                            send(YtTriggerKind.LONG_PRESS)
                        }
                    }
                }
                f != null && s != null -> if (!moved && (f - s).getDistance() > slop) {
                    // a drag of the docked player began: no longer pressed
                    moved = true
                    hold?.cancel()
                    send(YtTriggerKind.TOUCH_UP)
                }
                f == null && s != null -> {
                    hold?.cancel()
                    if (!moved) send(YtTriggerKind.TOUCH_UP)
                    start = null
                    send(if (!moved && !long) taps.tap(now, s.x, s.y) else taps.other())
                    taps.waitMs(now)?.let { w ->
                        expiry = launch {
                            delay(w)
                            send(taps.expire(SystemClock.uptimeMillis()))
                        }
                    }
                }
                else -> Unit
            }
        }
    }
    return events
}
