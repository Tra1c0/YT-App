package com.github.libretube.ui.yt.mini

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.github.libretube.R
import com.github.libretube.helpers.ImageHelper
import com.github.libretube.ui.yt.LocalYtMotion
import com.github.libretube.ui.yt.YtDock
import com.github.libretube.ui.yt.YtFonts
import com.github.libretube.ui.yt.rememberSwell
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/*
 * YT: everything the Glas player draws on its picture (the overlay over the video, clipped to the
 * strip): the edges dissolving into the glass, the tab row's rim over the strip's foot, the glass
 * buttons, "Titel · Kanal", the preview's pill and next row, the glyph flash and the drop.
 *
 * The video is a SurfaceView, which nothing can blur or mask, so all glass on it is drawn: the
 * blurred copy of the frame (YtMiniBleed) where the video lies, a tint and a rim over it. Every
 * moving value (progress, preview, hover, the bleed) is read while drawing or laying out.
 */

/** The colours of Glas on the picture. */
object YtGlassOverStyle {
    val petrol = Color(0xFF0A8F7F)

    /** White glass of the buttons, the dark glass of the pill and the next video's row. */
    val light = Color(0x40FFFFFF)
    val pill = Color(0x66000000)
    val row = Color(0x80000000)
    val rowActive = Color(0x99000000)

    /** The edge band over the bleed, and alone where nothing could be copied. */
    val edge = Color(0x6BFFFFFF)
    val edgeFallback = Color(0xA6FFFFFF)

    /** The tab row's glass over the strip's foot. */
    val plate = Color(0x80FFFFFF)
    val plateFallback = Color(0xB3FFFFFF)
}

/** Where the parts sit for the picture's current size (set while laying out, read while drawing). */
private class GlassSpace {
    var over: YtGlassOver? = null
}

/** The title line, shortened to its room once per title and room. */
private class TitleLine {
    private var key: Triple<String, String, Int>? = null
    private var layout: TextLayoutResult? = null

    fun of(measurer: TextMeasurer, title: String, channel: String, room: Float, style: TextStyle): TextLayoutResult? {
        val k = Triple(title, channel, room.roundToInt())
        if (k == key) return layout
        key = k
        val text = YtGlassMath.titleLine(title, channel) { candidate ->
            measurer.measure(candidate, style, maxLines = 1, softWrap = false).size.width <= room
        }
        layout = if (text.isEmpty()) null else measurer.measure(text, style, maxLines = 1, softWrap = false)
        return layout
    }
}

private val layerPaint = Paint()

/** The blurred frame where the video lies ([frame], YtBleedMath.zoomFrame), moved by [dx], [dy]. */
private fun DrawScope.drawBleed(image: ImageBitmap, frame: FloatArray, dx: Float, dy: Float, alpha: Float = 1f) {
    drawImage(
        image = image,
        srcOffset = IntOffset.Zero,
        srcSize = IntSize(image.width, image.height),
        dstOffset = IntOffset((frame[0] + dx).roundToInt(), (frame[1] + dy).roundToInt()),
        dstSize = IntSize(frame[2].roundToInt().coerceAtLeast(1), frame[3].roundToInt().coerceAtLeast(1)),
        alpha = alpha,
        filterQuality = FilterQuality.Low,
    )
}

/**
 * A piece of glass over the video at [rect] (corner [radius]): the blurred video behind it, a
 * [tint], the light it catches at its top and a rim ([rim] its strength), all at [alpha].
 * [dx], [dy] move the picture's coordinates into this scope's.
 */
private fun DrawScope.glassPatch(
    image: ImageBitmap?,
    frame: FloatArray,
    dx: Float,
    dy: Float,
    rect: Rect,
    radius: Float,
    tint: Color,
    rim: Float,
    light: Float = 0.22f,
    alpha: Float = 1f,
) {
    if (alpha <= 0f || rect.width <= 0f || rect.height <= 0f) return
    val path = Path().apply { addRoundRect(RoundRect(rect = rect, cornerRadius = CornerRadius(radius))) }
    clipPath(path) {
        if (image != null) drawBleed(image, frame, dx, dy, alpha)
        drawRect(tint, rect.topLeft, rect.size, alpha = alpha)
        drawRect(
            Brush.verticalGradient(listOf(Color.White.copy(alpha = light), Color.Transparent), startY = rect.top, endY = rect.center.y),
            rect.topLeft,
            Size(rect.width, rect.height / 2f),
            alpha = alpha,
        )
    }
    drawPath(
        path,
        Brush.verticalGradient(
            listOf(Color.White.copy(alpha = 0.85f * rim), Color.White.copy(alpha = 0.2f * rim)),
            startY = rect.top,
            endY = rect.bottom,
        ),
        alpha = alpha,
        style = Stroke(1.dp.toPx()),
    )
}

/** ◀ or ▶, white with a soft shadow, at [center] ([half] the glyph's height). */
private fun DrawScope.arrow(forward: Boolean, center: Offset, half: Float, alpha: Float) {
    fun path(dy: Float) = Path().apply {
        val w = half * 1.05f
        // optically centred: a triangle's weight lies towards its flat side
        val shift = if (forward) w * 0.18f else -w * 0.18f
        val tip = if (forward) center.x + w * 0.62f + shift else center.x - w * 0.62f + shift
        val back = if (forward) center.x - w * 0.62f + shift else center.x + w * 0.62f + shift
        moveTo(tip, center.y + dy)
        lineTo(back, center.y - half + dy)
        lineTo(back, center.y + half + dy)
        close()
    }
    drawPath(path(1.dp.toPx()), Color.Black.copy(alpha = 0.22f * alpha))
    drawPath(path(0f), Color.White.copy(alpha = alpha))
}

/** ▶ while it plays, ❚❚ while paused. */
private fun DrawScope.glyph(playing: Boolean, center: Offset, radius: Float, color: Color) {
    if (playing) {
        val path = Path().apply {
            moveTo(center.x - radius * 0.6f, center.y - radius)
            lineTo(center.x + radius, center.y)
            lineTo(center.x - radius * 0.6f, center.y + radius)
            close()
        }
        drawPath(path, color)
    } else {
        val bar = radius * 0.6f
        drawRoundRect(color, Offset(center.x - radius * 0.85f, center.y - radius), Size(bar, radius * 2f), CornerRadius(bar / 3f))
        drawRoundRect(color, Offset(center.x + radius * 0.85f - bar, center.y - radius), Size(bar, radius * 2f), CornerRadius(bar / 3f))
    }
}

/**
 * The left, right and top edges of the strip dissolving into the glass: in a band of [edge] px
 * the blurred video under a light tint, opaque at the edge and gone inward. [glow] (a video
 * dragged near) lights the glass a little.
 */
private fun DrawScope.drawEdges(image: ImageBitmap?, frame: FloatArray, edge: Float, rim: Float, glow: Float) {
    val w = size.width
    if (w <= 0f || rim <= 0f) return
    val fade = arrayOf(0f to Color.Black, 0.45f to Color.Black.copy(alpha = 0.55f), 1f to Color.Transparent)
    val back = arrayOf(0f to Color.Transparent, 0.55f to Color.Black.copy(alpha = 0.55f), 1f to Color.Black)
    val bands = listOf(
        Rect(0f, 0f, edge, rim) to Brush.horizontalGradient(*fade, startX = 0f, endX = edge),
        Rect(w - edge, 0f, w, rim) to Brush.horizontalGradient(*back, startX = w - edge, endX = w),
        Rect(0f, 0f, w, edge) to Brush.verticalGradient(*fade, startY = 0f, endY = edge),
    )
    val tint = if (image != null) YtGlassOverStyle.edge else YtGlassOverStyle.edgeFallback
    val lit = tint.copy(alpha = (tint.alpha + 0.18f * glow).coerceAtMost(1f))
    for ((rect, mask) in bands) {
        // each band in a layer of its own, masked by its gradient
        drawContext.canvas.saveLayer(rect, layerPaint)
        if (image != null) drawBleed(image, frame, 0f, 0f)
        drawRect(lit, rect.topLeft, rect.size)
        drawRect(mask, rect.topLeft, rect.size, blendMode = BlendMode.DstIn)
        drawContext.canvas.restore()
    }
}

/**
 * The tab row's glass lying over the strip's foot from [rim] down (its round top corners reach
 * past the strip's sides by [inset]): the blurred video under a white tint, a bright rim and the
 * soft shadow of the raised row.
 */
private fun DrawScope.drawPlate(image: ImageBitmap?, frame: FloatArray, rim: Float, inset: Float, bar: Float) {
    fun plate(top: Float) = RoundRect(left = -inset, top = top, right = size.width + inset, bottom = top + bar, cornerRadius = CornerRadius(bar / 2f))
    val path = Path().apply { addRoundRect(plate(rim)) }
    // the shadow just above the rim, along its curve
    drawPath(Path().apply { addRoundRect(plate(rim - 1.5f.dp.toPx())) }, Color.Black.copy(alpha = 0.12f), style = Stroke(4.dp.toPx()))
    clipPath(path) {
        if (image != null) drawBleed(image, frame, 0f, 0f)
        drawRect(if (image != null) YtGlassOverStyle.plate else YtGlassOverStyle.plateFallback)
    }
    drawPath(path, Color.White.copy(alpha = 0.9f), style = Stroke(1.2f.dp.toPx()))
}

/** The parts of the Glas player over its picture. */
@Composable
internal fun GlassOverlay(scene: YtMiniScene) {
    val density = LocalDensity.current.density
    val motion = LocalYtMotion.current

    // the colour of the video for the glass, copied while docked, playing and on screen
    val bleed = remember { YtMiniBleed() }
    DisposableEffect(bleed) {
        YtGlassSlab.bleed = bleed
        onDispose { if (YtGlassSlab.bleed === bleed) YtGlassSlab.bleed = null }
    }
    LaunchedEffect(bleed) {
        bleed.run(surface = scene.surface, active = { YtDock.docked && YtDock.progress >= 0.99f && scene.info.playing })
    }

    val space = remember { GlassSpace() }
    val images = remember { YtBleedImage() }
    val titles = remember { TitleLine() }
    val measurer = rememberTextMeasurer()

    // what changes rarely (not the position, which the poll renews every time)
    val text by remember { derivedStateOf { scene.info.let { Triple(it.title, it.channel, it.playing) } } }
    val (title, channel, playing) = text
    val next = scene.next
    val label = stringResource(if (playing) R.string.yt_mini_peek_playing else R.string.yt_mini_peek_paused)
    val drop = YtDock.lastDrop
    val dropText = when {
        drop == null -> ""
        drop.next -> stringResource(R.string.yt_mini_drop_next)
        else -> stringResource(R.string.yt_mini_drop_end, drop.position)
    }

    val titleStyle = remember {
        TextStyle(
            color = Color.White,
            fontFamily = YtFonts.googleSans,
            fontWeight = FontWeight.Medium,
            fontSize = 11.sp,
            shadow = Shadow(Color.Black.copy(alpha = 0.6f), Offset(0f, 1f), 2f),
        )
    }
    val labelStyle = remember {
        TextStyle(color = Color.White, fontFamily = YtFonts.googleSans, fontWeight = FontWeight.Medium, fontSize = 12.sp)
    }

    // the glyph flash of a toggle (double tap, the pill)
    val flash = remember { Animatable(0f) }
    val flashes = scene.flashes
    var seenFlash by remember { mutableIntStateOf(flashes) }
    LaunchedEffect(flashes) {
        if (flashes == seenFlash) return@LaunchedEffect
        seenFlash = flashes
        if (motion) {
            flash.snapTo(0f)
            flash.animateTo(1f, tween(620, easing = LinearEasing))
        } else {
            flash.snapTo(0.3f)
            delay(450)
        }
        flash.snapTo(0f)
    }

    // a dropped video: a droplet of glass falls into the strip and merges with a ripple; the
    // label shows as the dark pill
    val merge = remember { Animatable(0f) }
    val dropLabel = remember { Animatable(0f) }
    val accepted = YtDock.accepted
    var seenDrop by remember { mutableIntStateOf(accepted) }
    LaunchedEffect(accepted) {
        if (accepted == seenDrop) return@LaunchedEffect
        seenDrop = accepted
        if (motion) {
            launch {
                merge.snapTo(0f)
                merge.animateTo(1f, tween(900, easing = LinearEasing))
                merge.snapTo(0f)
            }
            delay(380)
            dropLabel.animateTo(1f, tween(200, easing = LinearEasing))
            delay(1400)
            dropLabel.animateTo(0f, tween(240, easing = LinearEasing))
        } else {
            dropLabel.snapTo(1f)
            delay(1400)
            dropLabel.snapTo(0f)
        }
    }

    val pillTarget = { scene.target == YtMiniTarget.PILL }
    // the overlay moves with the video into fullscreen, where it is hidden: its buttons must not
    // take the taps there
    val docked = YtDock.docked

    Layout(
        modifier = Modifier.fillMaxSize(),
        content = {
            // the soft edges, then "Titel · Kanal" and the pill it turns into
            Canvas(Modifier.layoutId("back").clearAndSetSemantics {}) {
                val o = space.over ?: return@Canvas
                val image = images.of(bleed.frame)
                val frame = YtBleedMath.zoomFrame(size.width, size.height, bleed.aspect)
                drawEdges(image, frame, YtGlassLayout.EDGE.dp.toPx(), o.rim, scene.hover())

                val progress = YtDock.progress
                val peek = YtDock.peek.coerceIn(0f, 1.15f)
                val cx = size.width / 2f
                val line = titles.of(measurer, title, channel, o.titleRight - o.titleLeft, titleStyle)
                val tw = line?.size?.width?.toFloat() ?: 0f
                val th = line?.size?.height?.toFloat() ?: (YtGlassLayout.TITLE_HEIGHT.dp.toPx())
                val titleTop = o.titleBottom - th

                val shown = YtGlassMath.pillShown(peek)
                if (shown > 0f) {
                    val spacing = YtGlassMath.spacing(peek)
                    val labelLayout = measurer.measure(label, labelStyle.copy(letterSpacing = spacing.em), maxLines = 1, softWrap = false)
                    // the pill grows from the title's bounds into the label's
                    val from = Rect(cx - tw / 2f - 8.dp.toPx(), titleTop - 3.dp.toPx(), cx + tw / 2f + 8.dp.toPx(), o.titleBottom + 3.dp.toPx())
                    val pillY = if (next != null) o.pillCenterY else o.pillCenterYWithoutRow
                    val pw = labelLayout.size.width + 2f * 16.dp.toPx()
                    val ph = YtGlassLayout.PILL.dp.toPx()
                    val to = Rect(cx - pw / 2f, pillY - ph / 2f, cx + pw / 2f, pillY + ph / 2f)
                    val rect = lerp(from, to, YtGlassMath.pillMorph(peek))
                    val s = if (pillTarget()) 1.06f else 1f
                    scale(s, s, rect.center) {
                        glassPatch(
                            image, frame, 0f, 0f, rect, rect.height / 2f,
                            if (pillTarget()) YtGlassOverStyle.rowActive else YtGlassOverStyle.pill,
                            rim = 0.5f, light = 0.08f, alpha = shown,
                        )
                        drawText(
                            labelLayout,
                            topLeft = Offset(rect.center.x - labelLayout.size.width / 2f, rect.center.y - labelLayout.size.height / 2f),
                            alpha = YtGlassMath.labelAlpha(peek),
                        )
                    }
                }
                val titleAlpha = YtGlassMath.titleAlpha(progress, peek)
                if (line != null && titleAlpha > 0f) {
                    drawText(line, topLeft = Offset(cx - tw / 2f, titleTop), alpha = titleAlpha)
                }
            }

            // the next video's row slides up from under the tab row
            if (next != null) {
                GlassRow(next, scene, space, images, bleed, Modifier.layoutId("row"))
            }

            // the tab row's rim over the strip's foot, the progress just above it
            Canvas(Modifier.layoutId("front").clearAndSetSemantics {}) {
                val o = space.over ?: return@Canvas
                val image = images.of(bleed.frame)
                val frame = YtBleedMath.zoomFrame(size.width, size.height, bleed.aspect)
                val fraction = scene.info.fraction
                if (fraction > 0f) {
                    val line = 2.dp.toPx()
                    drawRect(
                        YtGlassOverStyle.petrol.copy(alpha = 0.9f),
                        Offset(0f, o.rim - line),
                        Size(size.width * fraction.coerceIn(0f, 1f), line),
                    )
                }
                drawPlate(image, frame, o.rim, YtGlassLayout.INSET.dp.toPx(), YtGlassLayout.BAR.dp.toPx())
            }

            GlassArrow(
                forward = false,
                enabled = docked && scene.hasPrev,
                description = stringResource(R.string.yt_mini_glass_prev),
                space = space,
                images = images,
                bleed = bleed,
                onClick = { scene.onPrev() },
                modifier = Modifier.layoutId("prev"),
            )
            GlassArrow(
                forward = true,
                enabled = docked && next != null,
                description = stringResource(R.string.yt_mini_glass_next),
                space = space,
                images = images,
                bleed = bleed,
                onClick = { scene.onNext() },
                modifier = Modifier.layoutId("next"),
            )

            // the glyph flash, the droplet and the drop's label
            Canvas(Modifier.layoutId("top").clearAndSetSemantics {}) {
                val o = space.over ?: return@Canvas
                val image = images.of(bleed.frame)
                val frame = YtBleedMath.zoomFrame(size.width, size.height, bleed.aspect)
                val restTop = o.rim - (YtGlassLayout.PICTURE - YtGlassLayout.RIM).dp.toPx()
                val center = Offset(size.width / 2f, (restTop + o.rim) / 2f)

                val t = flash.value
                if (t > 0f) {
                    val shown = if (t < 0.22f) t / 0.22f else 1f - (t - 0.22f) / 0.78f
                    val radius = 22.dp.toPx() * (0.86f + 0.22f * t)
                    val rect = Rect(center, radius)
                    glassPatch(image, frame, 0f, 0f, rect, radius, YtGlassOverStyle.light, rim = 1f, light = 0.3f, alpha = shown)
                    glyph(scene.flashPlaying, center, radius * 0.4f, Color.White.copy(alpha = shown))
                }

                val m = merge.value
                if (m > 0f) {
                    val r = 13.dp.toPx()
                    // falls in (accelerating), then flattens into the glass
                    val fall = (m / 0.45f).coerceIn(0f, 1f)
                    val flat = ((m - 0.42f) / 0.18f).coerceIn(0f, 1f)
                    if (flat < 1f) {
                        val y = -r + (center.y + r) * fall * fall
                        val rx = r * (1f + 0.5f * flat) * (1f - 0.6f * flat)
                        val ry = r * (1f - 0.7f * flat)
                        val rect = Rect(center.x - rx, y - ry, center.x + rx, y + ry)
                        glassPatch(image, frame, 0f, 0f, rect, ry, YtGlassOverStyle.light, rim = 1f, light = 0.35f, alpha = 1f - flat)
                    }
                    // two rings of refraction run out along the strip
                    for (k in 0..1) {
                        val q = ((m - 0.45f - 0.12f * k) / 0.55f).coerceIn(0f, 1f)
                        if (q <= 0f || q >= 1f) continue
                        val rx = q * size.width * 0.55f
                        val ry = q * (o.rim - restTop) * 0.5f
                        drawOval(
                            Color.White.copy(alpha = 0.7f * (1f - q) * (if (k == 0) 1f else 0.6f)),
                            topLeft = Offset(center.x - rx, center.y - ry),
                            size = Size(rx * 2f, ry * 2f),
                            style = Stroke((2.4f * (1f - q) + 0.6f).dp.toPx()),
                        )
                    }
                }

                val v = dropLabel.value
                if (v > 0f && dropText.isNotEmpty()) {
                    val layout = measurer.measure(dropText, labelStyle.copy(letterSpacing = 0.04.em), maxLines = 1, softWrap = false)
                    val pw = (layout.size.width + 2f * 14.dp.toPx()).coerceAtMost(size.width - 2f * 8.dp.toPx())
                    val ph = YtGlassLayout.PILL.dp.toPx()
                    val rect = Rect(center.x - pw / 2f, center.y - ph / 2f, center.x + pw / 2f, center.y + ph / 2f)
                    val s = 0.9f + 0.1f * v
                    scale(s, s, rect.center) {
                        glassPatch(image, frame, 0f, 0f, rect, ph / 2f, YtGlassOverStyle.pill, rim = 0.5f, light = 0.08f, alpha = v)
                        drawText(
                            layout,
                            topLeft = Offset(rect.center.x - layout.size.width / 2f, rect.center.y - layout.size.height / 2f),
                            alpha = v,
                        )
                    }
                }
            }
        },
    ) { measurables, constraints ->
        val w = constraints.maxWidth
        val h = constraints.maxHeight
        val o = YtGlassLayout.over(w.toFloat(), h.toFloat(), density)
        space.over = o
        val full = Constraints.fixed(w, h)
        fun fixed(box: YtBox) = Constraints.fixed(box.width.roundToInt().coerceAtLeast(0), box.height.roundToInt().coerceAtLeast(0))
        val placed = measurables.map { measurable ->
            when (measurable.layoutId) {
                "prev" -> measurable.measure(fixed(o.prev)) to o.prev
                "next" -> measurable.measure(fixed(o.next)) to o.next
                "row" -> measurable.measure(fixed(o.row)) to o.row
                else -> measurable.measure(full) to null
            }
        }
        layout(w, h) {
            placed.forEach { (placeable, box) ->
                placeable.place(box?.left?.roundToInt() ?: 0, box?.top?.roundToInt() ?: 0)
            }
        }
    }
}

/** A round glass button over the picture: ◀ or ▶, dimmed when there is nothing before or after. */
@Composable
private fun GlassArrow(
    forward: Boolean,
    enabled: Boolean,
    description: String,
    space: GlassSpace,
    images: YtBleedImage,
    bleed: YtMiniBleed,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    val source = remember { MutableInteractionSource() }
    val swell: State<Float> = rememberSwell(source)
    Box(
        modifier
            .semantics { contentDescription = description }
            .graphicsLayer {
                val peek = YtDock.peek
                val s = YtGlassMath.buttonScale(peek) * (1f + 0.12f * swell.value)
                scaleX = s
                scaleY = s
                alpha = YtGlassMath.buttonAlpha(peek) * (if (enabled) 1f else 0.4f)
            }
            // without a target (or in fullscreen) no touch handling at all: the tap goes through to
            // the player below
            .then(if (enabled) Modifier.clickable(interactionSource = source, indication = null, role = Role.Button, onClick = onClick) else Modifier)
            .drawBehind {
                val o = space.over ?: return@drawBehind
                val box = if (forward) o.next else o.prev
                val frame = YtBleedMath.zoomFrame(o.width, o.height, bleed.aspect)
                val radius = size.minDimension / 2f
                glassPatch(
                    images.of(bleed.frame), frame, -box.left, -box.top,
                    Rect(Offset.Zero, size), radius, YtGlassOverStyle.light, rim = 1f, light = 0.25f,
                )
                arrow(forward, center, radius * 0.36f, 1f)
            },
    )
}

/** The next video in dark glass: thumbnail, then "Kanal · Titel", spaced as the reference. */
@Composable
private fun GlassRow(
    next: YtMiniNext,
    scene: YtMiniScene,
    space: GlassSpace,
    images: YtBleedImage,
    bleed: YtMiniBleed,
    modifier: Modifier,
) {
    val density = LocalDensity.current.density
    val text = if (next.channel.isBlank()) next.title else "${next.channel} · ${next.title}"
    fun slide(o: YtGlassOver?): Float =
        (o?.let { it.rim - it.row.top } ?: (56f * density)) * (1f - YtGlassMath.rowShown(YtDock.peek))
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .clearAndSetSemantics {}
            .graphicsLayer {
                translationY = slide(space.over)
                alpha = (YtGlassMath.rowShown(YtDock.peek) * 1.5f).coerceAtMost(1f)
                val s = if (scene.target == YtMiniTarget.NEXT) 1.03f else 1f
                scaleX = s
                scaleY = s
            }
            .drawBehind {
                val o = space.over ?: return@drawBehind
                val frame = YtBleedMath.zoomFrame(o.width, o.height, bleed.aspect)
                glassPatch(
                    images.of(bleed.frame), frame, -o.row.left, -o.row.top - slide(o),
                    Rect(Offset.Zero, size), 14.dp.toPx(),
                    if (scene.target == YtMiniTarget.NEXT) YtGlassOverStyle.rowActive else YtGlassOverStyle.row,
                    rim = 0.45f, light = 0.06f,
                )
            }
            .padding(horizontal = 7.dp),
    ) {
        AsyncImage(
            model = next.thumbnail,
            contentDescription = null,
            imageLoader = ImageHelper.loader(),
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(58.dp, 32.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Color(0x33FFFFFF)),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = text,
            style = TextStyle(
                color = Color.White,
                fontFamily = YtFonts.googleSans,
                fontWeight = FontWeight.Medium,
                fontSize = 12.sp,
                letterSpacing = 0.12.em,
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
