package com.github.libretube.ui.yt.mini

import android.graphics.Bitmap
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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.github.libretube.ui.yt.LocalYtMotion
import com.github.libretube.ui.yt.YtDock
import com.github.libretube.ui.yt.YtDrag
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/*
 * YT: what the Frost player draws on its picture (the overlay over the video, clipped to the
 * window's rounded outline): the frost veil creeping in from the edges, the crystals along the
 * rim, the frost that covers the window and thaws from the finger in the preview, the fog of a
 * card dragged near that clears where it lands, and the glyph flash on a disc of ice.
 *
 * The video is a SurfaceView, which nothing can blur or mask, so the frost is drawn over it: a
 * blurred copy of the frame (YtMiniBleed) under a white veil and a fine grain. Every moving value
 * (progress, preview, hover, the copy) is read while drawing.
 */

/** The colours of Frost's mini player. */
internal object YtFrostStyle {
    /** The pane: the Frost bar's dark glass over blur, and solid where nothing can be blurred. */
    val pane = Color(0x9E1F2A26)
    val paneSolid = Color(0xF21F2A26)
    val shadow = Color(0x59000000)
    val ink = Color(0xFF1F2A26)

    /** The veil over the picture: white 16 %; a little more where no frame could be copied. */
    val veil = Color(0x29FFFFFF)
    val veilFallback = Color(0x4DFFFFFF)
    val crystal = Color(0xFFFFFFFF)

    /** The frost tiles of the preview (pill, card) and the drop's label. */
    val tile = Color(0x2EFFFFFF)
    val tileBorder = Color(0x59FFFFFF)
    val secondary = Color(0xA6FFFFFF)
    val track = Color(0x1FFFFFFF)
}

/**
 * The fine grain of frost: a small tile of white specks, repeated. Made once; drawn as a brush,
 * so it costs one shader per draw.
 */
internal object YtFrostGrain {
    private const val SIDE = 72

    val image: ImageBitmap by lazy {
        val random = Random(0x5EED)
        val pixels = IntArray(SIDE * SIDE) {
            // about a third of the pixels carry a speck of white, of varying strength
            if (random.nextFloat() < 0.34f) ((random.nextFloat() * 96f).roundToInt() shl 24) or 0xFFFFFF else 0
        }
        Bitmap.createBitmap(pixels, SIDE, SIDE, Bitmap.Config.ARGB_8888).asImageBitmap()
    }

    val brush: Brush by lazy { ShaderBrush(ImageShader(image, TileMode.Repeated, TileMode.Repeated)) }
}

/** What the overlay keeps between frames: the crystals of the window's size, the drop's place. */
private class FrostSpace {
    private var key = Triple(0f, 0f, 0f)
    private var cached = FloatArray(0)
    val path = Path()

    /** The overlay's top left and size, the last pointer of a drag near it (window px). */
    var origin = Offset.Zero
    var size = Size.Zero
    var pointer = Offset.Unspecified

    /** Where the last dropped card landed, in the overlay's px. */
    var landing = Offset.Unspecified

    fun crystals(width: Float, height: Float, density: Float): FloatArray {
        val k = Triple(width, height, density)
        if (k != key) {
            key = k
            cached = YtFrostMath.crystals(width, height, density, YtFrostLayout.THUMB_RADIUS * density)
        }
        return cached
    }
}

private val veilPaint = Paint()
private val coverPaint = Paint()

// a crystal's side branches leave it at 55 degrees
private val branchCos = kotlin.math.cos(55f * PI.toFloat() / 180f)
private val branchSin = kotlin.math.sin(55f * PI.toFloat() / 180f)

/** The blurred frame where the video lies ([frame], YtBleedMath.zoomFrame). */
private fun DrawScope.drawFrame(image: ImageBitmap, frame: FloatArray, alpha: Float = 1f) {
    drawImage(
        image = image,
        srcOffset = IntOffset.Zero,
        srcSize = IntSize(image.width, image.height),
        dstOffset = IntOffset(frame[0].roundToInt(), frame[1].roundToInt()),
        dstSize = IntSize(frame[2].roundToInt().coerceAtLeast(1), frame[3].roundToInt().coerceAtLeast(1)),
        alpha = alpha,
        filterQuality = FilterQuality.Low,
    )
}

/** Frost over [rect]: the blurred video, the white veil and the grain. */
private fun DrawScope.frost(image: ImageBitmap?, frame: FloatArray, rect: Rect, extra: Float = 0f) {
    if (image != null) drawFrame(image, frame)
    val veil = if (image != null) YtFrostStyle.veil else YtFrostStyle.veilFallback
    drawRect(veil.copy(alpha = (veil.alpha + extra).coerceAtMost(1f)), rect.topLeft, rect.size)
    drawRect(YtFrostGrain.brush, rect.topLeft, rect.size, alpha = 0.8f)
}

/**
 * The veil: in a band of [width] px along each edge frost, opaque at the edge, gone inward. Each
 * band in a layer of its own, masked by its gradient; where two meet the corner is frostier, as
 * frost gathers in a window's corners.
 */
private fun DrawScope.drawVeil(image: ImageBitmap?, frame: FloatArray, width: Float) {
    val w = size.width
    val h = size.height
    val band = min(width, min(w, h) / 2f)
    if (band <= 0.5f) return
    val fade = arrayOf(0f to Color.Black, 0.4f to Color.Black.copy(alpha = 0.6f), 1f to Color.Transparent)
    val back = arrayOf(0f to Color.Transparent, 0.6f to Color.Black.copy(alpha = 0.6f), 1f to Color.Black)
    val bands = listOf(
        Rect(0f, 0f, band, h) to Brush.horizontalGradient(*fade, startX = 0f, endX = band),
        Rect(w - band, 0f, w, h) to Brush.horizontalGradient(*back, startX = w - band, endX = w),
        Rect(0f, 0f, w, band) to Brush.verticalGradient(*fade, startY = 0f, endY = band),
        Rect(0f, h - band, w, h) to Brush.verticalGradient(*back, startY = h - band, endY = h),
    )
    for ((rect, mask) in bands) {
        drawContext.canvas.saveLayer(rect, veilPaint)
        frost(image, frame, rect)
        drawRect(mask, rect.topLeft, rect.size, blendMode = BlendMode.DstIn)
        drawContext.canvas.restore()
    }
}

/** The crystals, grown to [freeze] (0 to 1), in white at [alpha]. */
private fun DrawScope.drawCrystals(space: FrostSpace, freeze: Float, alpha: Float) {
    if (freeze <= 0.01f || alpha <= 0f) return
    val c = space.crystals(size.width, size.height, density)
    val path = space.path
    path.reset()
    var i = 0
    var any = false
    while (i + YtFrostMath.STRIDE <= c.size) {
        val g = YtFrostMath.grown(c[i + 5], freeze)
        if (g > 0.02f) {
            any = true
            val x = c[i]
            val y = c[i + 1]
            val dx = c[i + 2]
            val dy = c[i + 3]
            val length = c[i + 4] * g
            path.moveTo(x, y)
            path.lineTo(x + dx * length, y + dy * length)
            // two short branches from a little past the middle, as a frost fern
            val bx = x + dx * length * 0.55f
            val by = y + dy * length * 0.55f
            val b = length * 0.38f
            path.moveTo(bx, by)
            path.lineTo(bx + (dx * branchCos - dy * branchSin) * b, by + (dx * branchSin + dy * branchCos) * b)
            path.moveTo(bx, by)
            path.lineTo(bx + (dx * branchCos + dy * branchSin) * b, by + (-dx * branchSin + dy * branchCos) * b)
        }
        i += YtFrostMath.STRIDE
    }
    if (any) {
        drawPath(path, YtFrostStyle.crystal, alpha = alpha.coerceIn(0f, 1f), style = Stroke(1.dp.toPx(), cap = StrokeCap.Round))
    }
}

/**
 * Frost over the whole window at [alpha], cleared in a circle of [radius] px around [center]
 * with a soft rim of [soft] px (radius 0: no clearing).
 */
private fun DrawScope.drawCover(image: ImageBitmap?, frame: FloatArray, alpha: Float, center: Offset, radius: Float, soft: Float) {
    if (alpha <= 0.01f) return
    val bounds = Rect(Offset.Zero, size)
    coverPaint.alpha = alpha.coerceIn(0f, 1f)
    drawContext.canvas.saveLayer(bounds, coverPaint)
    frost(image, frame, bounds, extra = 0.08f)
    if (radius > 0.5f) {
        val inner = ((radius - soft) / radius).coerceIn(0f, 0.999f)
        drawCircle(
            Brush.radialGradient(0f to Color.Black, inner to Color.Black, 1f to Color.Transparent, center = center, radius = radius),
            radius = radius,
            center = center,
            blendMode = BlendMode.DstOut,
        )
    }
    drawContext.canvas.restore()
}

/** ▶ while it plays, ❚❚ while paused. */
private fun DrawScope.frostGlyph(playing: Boolean, center: Offset, radius: Float, color: Color) {
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
        drawRect(color, Offset(center.x - radius * 0.85f, center.y - radius), Size(bar, radius * 2f))
        drawRect(color, Offset(center.x + radius * 0.85f - bar, center.y - radius), Size(bar, radius * 2f))
    }
}

/** The parts of the Frost player over its picture. */
@Composable
internal fun FrostOverlay(scene: YtMiniScene) {
    val motion = LocalYtMotion.current

    // the colour of the video for the frost, copied while docked, playing and on screen
    val bleed = remember { YtMiniBleed() }
    LaunchedEffect(bleed) {
        bleed.run(surface = scene.surface, active = { YtDock.docked && YtDock.progress >= 0.99f && scene.info.playing })
    }
    val images = remember { YtBleedImage() }
    val space = remember { FrostSpace() }

    // after landing the frost reaches a little further in and draws back, the crystals glint
    val settle = remember { Animatable(1f) }
    val landings = scene.landings
    var seenLanding by remember { mutableIntStateOf(landings) }
    LaunchedEffect(landings) {
        if (landings == seenLanding) return@LaunchedEffect
        seenLanding = landings
        if (!motion) return@LaunchedEffect
        settle.snapTo(0f)
        settle.animateTo(1f, tween(760, easing = LinearEasing))
    }

    // the glyph flash of a toggle (the play button, the pill)
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

    // a dropped card: the fog it breathed onto the window clears in a circle where it lands
    val clearing = remember { Animatable(0f) }
    val accepted = YtDock.accepted
    var seenDrop by remember { mutableIntStateOf(accepted) }
    LaunchedEffect(accepted) {
        if (accepted == seenDrop) return@LaunchedEffect
        seenDrop = accepted
        val s = space.size
        val p = space.pointer
        space.landing = if (p.isSpecified && s.width > 0f) {
            Offset((p.x - space.origin.x).coerceIn(0f, s.width), (p.y - space.origin.y).coerceIn(0f, s.height))
        } else {
            Offset.Unspecified
        }
        space.pointer = Offset.Unspecified
        if (!motion) return@LaunchedEffect
        clearing.snapTo(0.001f)
        clearing.animateTo(1f, tween(760, easing = LinearEasing))
        clearing.snapTo(0f)
    }

    // a refused drop: the frost flinches in and out once
    val flinch = remember { Animatable(0f) }
    val rejects = scene.rejects
    var seenReject by remember { mutableIntStateOf(rejects) }
    LaunchedEffect(rejects) {
        if (rejects == seenReject) return@LaunchedEffect
        seenReject = rejects
        if (!motion) return@LaunchedEffect
        flinch.snapTo(0f)
        flinch.animateTo(1f, tween(340, easing = LinearEasing))
        flinch.snapTo(0f)
    }

    Canvas(
        Modifier
            .fillMaxSize()
            .clearAndSetSemantics {}
            .onGloballyPositioned { space.origin = it.positionInWindow() },
    ) {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@Canvas
        space.size = size
        val image = images.of(bleed.frame)
        val frame = YtBleedMath.zoomFrame(w, h, bleed.aspect)
        val progress = YtDock.progress
        val peek = YtDock.peek.coerceIn(0f, 1.15f)
        val hover = scene.hover()
        if (hover > 0f) YtDrag.session?.let { space.pointer = it.pointer }

        // 1: the veil creeping in from the edges
        val s = settle.value
        val f = flinch.value
        val veil = YtFrostLayout.VEIL.dp.toPx() * YtFrostMath.veil(progress, s, peek) +
            6.dp.toPx() * sin(PI.toFloat() * f) * YtFrostMath.freeze(progress)
        drawVeil(image, frame, veil)

        // 2: the crystals along the rim; they melt back in the preview and glint while settling
        val freeze = YtFrostMath.freeze(progress) * (1f - 0.85f * YtFrostMath.thaw(peek))
        val glint = 0.25f * sin(PI.toFloat() * s)
        drawCrystals(space, freeze, 0.7f + glint)

        // 3: the preview frosts the window over and thaws it from the finger outward; a card
        // dragged near fogs it, and the fog clears where the card lands
        val soft = 18.dp.toPx()
        val cover = YtFrostMath.cover(peek)
        if (cover > 0f) {
            val press = scene.press
            val center = if (press.isSpecified) Offset(press.x * w, press.y * h) else Offset(w / 2f, h / 2f)
            val radius = YtFrostMath.thaw(peek) * (YtFrostMath.reach(w, h, center.x, center.y) + soft)
            drawCover(image, frame, 0.92f * cover, center, radius, soft)
        } else {
            val k = clearing.value
            val fog = maxOf(0.6f * hover, if (k > 0f) 0.6f * (1f - YtFrostMath.smoothstep(0.8f, 1f, k)) else 0f)
            if (fog > 0f) {
                val landing = space.landing
                val center = if (k > 0f && landing.isSpecified) landing else Offset(w / 2f, h / 2f)
                val radius = if (k > 0f) YtFrostMath.smoothstep(0f, 0.8f, k) * (YtFrostMath.reach(w, h, center.x, center.y) + soft) else 0f
                drawCover(image, frame, fog, center, radius, soft)
            }
        }

        // 4: the glyph on a disc of ice
        val t = flash.value
        if (t > 0f) {
            val shown = if (t < 0.22f) t / 0.22f else 1f - (t - 0.22f) / 0.78f
            val radius = min(h * 0.36f, 22.dp.toPx()) * (0.86f + 0.2f * t)
            val center = Offset(w / 2f, h / 2f)
            val disc = Path().apply { addOval(Rect(center, radius)) }
            coverPaint.alpha = shown.coerceIn(0f, 1f)
            drawContext.canvas.saveLayer(Rect(center, radius + 2.dp.toPx()), coverPaint)
            clipPath(disc) { frost(image, frame, Rect(Offset.Zero, size), extra = 0.12f) }
            drawCircle(Color.White.copy(alpha = 0.8f), radius, center, style = Stroke(1.dp.toPx()))
            frostGlyph(scene.flashPlaying, center, radius * 0.4f, Color.White)
            drawContext.canvas.restore()
        }
    }
}
