package com.github.libretube.ui.yt.mini

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.github.libretube.R
import com.github.libretube.helpers.ImageHelper
import com.github.libretube.ui.yt.LocalYtMotion
import com.github.libretube.ui.yt.YtDock
import com.github.libretube.ui.yt.YtFonts
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/*
 * YT: what the YouTube neu player ("Rote Naht") draws. Under the picture: the dark card that is
 * one surface with the bar, the red seam between them (stitches, the played part solid and
 * glowing), title and channel, the preview's column and "Als Nächstes" row, the "+1" pill of a
 * drop. Over the picture: the red edge of a drop near, the played line of the preview, the glyph
 * flash. Every moving value (progress, preview, hover, the seam's needle) is read while drawing
 * or laying out, so nothing composes again while the player moves.
 */

/** The colours of the YouTube neu player (the bar's: YtNavBar's style for YouTube). */
internal object YtMiniYoutubeStyle {
    val card = Color(0xFF17171D)
    val cardTop = Color(0xFF1B1B22)
    val border = Color(0xFF2A2A33)
    val title = Color(0xFFE6E6EC)
    val secondary = Color(0xFF9A9AA6)
    val pill = Color(0xFF282830)
    val row = Color(0xFF1E1E25)
    val rowActive = Color(0xFF2A2A33)
    val thumbGround = Color(0xFF282830)

    /** A rejected drop: the thread loses its colour for a moment. */
    val dull = Color(0xFF5A5A66)

    /** The light at the needle's point and the played part's head. */
    val hot = Color(0xFFFF8A98)
}

/** The docked geometry for the box's current width, computed once per width. */
private class YoutubeSpace(private val design: YtMiniDesign) {
    private var width = -1f
    private var density = 0f
    private var cached: YtMiniGeometry? = null

    fun of(width: Float, density: Float): YtMiniGeometry {
        val c = cached
        if (c != null && width == this.width && density == this.density) return c
        this.width = width
        this.density = density
        return design.geometry(width, density).also { cached = it }
    }
}

/** The card: top corners of [radius], its foot open on the bar ([open] for the border). */
private fun Path.card(width: Float, height: Float, radius: Float, inset: Float, open: Boolean) {
    reset()
    val r = radius.coerceIn(0f, max(0f, min(width, height) / 2f - inset))
    moveTo(inset, height)
    lineTo(inset, inset + r)
    if (r > 0f) arcTo(Rect(inset, inset, inset + 2f * r, inset + 2f * r), 180f, 90f, false) else lineTo(inset, inset)
    lineTo(width - inset - r, inset)
    if (r > 0f) arcTo(Rect(width - inset - 2f * r, inset, width - inset, inset + 2f * r), 270f, 90f, false) else lineTo(width - inset, inset)
    lineTo(width - inset, height)
    if (!open) close()
}

/** A soft round light of [color] at [center], [radius] wide. */
private fun DrawScope.halo(color: Color, center: Offset, radius: Float) {
    if (radius <= 0f || color.alpha <= 0f) return
    drawCircle(Brush.radialGradient(listOf(color, color.copy(alpha = 0f)), center, radius), radius, center)
}

/** The player of YouTube neu behind the picture, inside the box. */
@Composable
internal fun YoutubeUnder(scene: YtMiniScene, design: YtMiniDesign) {
    val palette = scene.palette ?: return
    val red = palette.accent
    val density = LocalDensity.current.density
    val motion = LocalYtMotion.current
    val mirror = LocalLayoutDirection.current == LayoutDirection.Rtl
    val spec = design.spec
    val space = remember(design) { YoutubeSpace(design) }
    // the docked box's height as the constraints give it (whole px); the foot stays on the bar
    val restBox = ((spec.height + spec.overlap) * density).roundToInt().toFloat()
    val overlap = spec.overlap * density

    // the seam is sewn in once the player sits on the bar, and unpicked when it lifts off; a
    // short lift that falls back keeps it sewn
    val sewn = remember { Animatable(1f) }
    LaunchedEffect(motion) {
        snapshotFlow { YtDock.progress >= 0.999f }.collectLatest { docked ->
            if (docked) {
                if (sewn.value < 1f) {
                    if (motion) sewn.animateTo(1f, tween(620, easing = LinearEasing)) else sewn.snapTo(1f)
                }
            } else {
                snapshotFlow { YtDock.progress < 0.9f }.collect { lifted -> if (lifted) sewn.snapTo(0f) }
            }
        }
    }

    // the preview's timer line: fills while the preview is held open, rests while it closes,
    // empties once it is gone (a visual timer only, nothing plays by itself)
    val timer = remember { Animatable(0f) }
    LaunchedEffect(motion) {
        snapshotFlow {
            val p = YtDock.peek
            when {
                p >= 0.9f -> 2
                p >= 0.3f -> 1
                else -> 0
            }
        }.collectLatest { stage ->
            when (stage) {
                2 -> if (motion) {
                    val left = ((1f - timer.value) * YtMiniYoutubeLayout.TIMER_MS).roundToInt()
                    if (left > 0) timer.animateTo(1f, tween(left, easing = LinearEasing))
                } else {
                    timer.snapTo(1f)
                }
                0 -> timer.snapTo(0f)
                else -> Unit
            }
        }
    }

    // a drop taken: ms since it (-1: none), drives the "+1" pill and the pulse along the seam
    val drop = remember { Animatable(-1f) }
    val accepted = YtDock.accepted
    var seenDrop by remember { mutableIntStateOf(accepted) }
    LaunchedEffect(accepted) {
        if (accepted == seenDrop) return@LaunchedEffect
        seenDrop = accepted
        if (motion) {
            drop.snapTo(0f)
            drop.animateTo(YtMiniYoutubeMath.DROP_END, tween(YtMiniYoutubeMath.DROP_END.roundToInt(), easing = LinearEasing))
        } else {
            // no motion: the label stands there, then is gone
            drop.snapTo(YtMiniYoutubeMath.WIDEN_END)
            delay(1400)
        }
        drop.snapTo(-1f)
    }

    // a drop not taken: the thread dulls for a moment
    val reject = remember { Animatable(0f) }
    val rejects = scene.rejects
    var seenReject by remember { mutableIntStateOf(rejects) }
    LaunchedEffect(rejects) {
        if (rejects == seenReject) return@LaunchedEffect
        seenReject = rejects
        reject.snapTo(1f)
        if (motion) {
            reject.animateTo(0f, tween(560, easing = LinearEasing))
        } else {
            delay(400)
            reject.snapTo(0f)
        }
    }

    val lastDrop = YtDock.lastDrop
    val dropLabel = when {
        lastDrop == null -> ""
        lastDrop.next -> stringResource(R.string.yt_mini_drop_next)
        else -> stringResource(R.string.yt_mini_drop_end, lastDrop.position)
    }
    val measurer = rememberTextMeasurer()
    val pillStyle = remember {
        TextStyle(color = Color.White, fontFamily = YtFonts.geist, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
    }
    val cardPath = remember { Path() }
    val borderPath = remember { Path() }

    Box(Modifier.fillMaxSize().clearAndSetSemantics {}) {
        // 1: the card, one surface with the bar; its top corners round as it lands
        Canvas(Modifier.fillMaxSize()) {
            val r = YtMiniYoutubeMath.corner(YtDock.progress, spec.radius * density)
            cardPath.card(size.width, size.height, r, 0f, open = false)
            drawPath(cardPath, Brush.verticalGradient(listOf(YtMiniYoutubeStyle.cardTop, YtMiniYoutubeStyle.card), endY = size.height))
            val stroke = 1f * density
            borderPath.card(size.width, size.height, r, stroke / 2f, open = true)
            drawPath(borderPath, YtMiniYoutubeStyle.border, style = Stroke(stroke))
        }

        // 2: title and channel beside the docked picture
        RestText(scene, space, restBox, drop)

        // 3: the preview's column and the next video, rising out of the seam
        YoutubePeek(scene, red, timer)

        // 4: the seam at the joint and the "+1" of a drop
        Canvas(Modifier.fillMaxSize().graphicsLayer { if (mirror) scaleX = -1f }) {
            val shown = YtMiniYoutubeMath.seamShown(YtDock.progress)
            if (shown <= 0f) return@Canvas
            val g = space.of(size.width, density)
            val near = scene.hover()
            val thread = lerp(red, YtMiniYoutubeStyle.dull, reject.value)
            val y = size.height - overlap / 2f
            val left = 1f * density
            val right = size.width - left
            val length = right - left
            val stitchT = sewn.value
            val reach = YtMiniYoutubeMath.needle(stitchT)
            val sewnEnd = left + length * reach
            val played = length * min(scene.info.fraction.coerceIn(0f, 1f), reach)

            // the glow of the played part, up into the card; a video dragged near lights it all
            val glow = YtMiniYoutubeMath.glowHeight(near) * density
            if (played > 0f) {
                drawRect(
                    Brush.verticalGradient(listOf(Color.Transparent, thread.copy(alpha = 0.24f * shown)), startY = y - glow, endY = y),
                    topLeft = Offset(left, y - glow),
                    size = Size(played, glow),
                )
            }
            if (near > 0f) {
                drawRect(
                    Brush.verticalGradient(listOf(Color.Transparent, thread.copy(alpha = 0.34f * near * shown)), startY = y - glow, endY = y),
                    topLeft = Offset(left, y - glow),
                    size = Size(length * reach, glow),
                )
            }

            // the stitches of the part still to play, fixed in place along the seam
            val step = (YtMiniYoutubeLayout.STITCH + YtMiniYoutubeLayout.STITCH_GAP) * density
            val stitch = YtMiniYoutubeLayout.STITCH * density
            val stitchWidth = (YtMiniYoutubeLayout.STITCH_WIDTH + 0.8f * near) * density
            val stitchColor = thread.copy(alpha = (0.4f + 0.5f * near) * shown)
            var x = left + (played / step).toInt() * step
            while (x < sewnEnd) {
                val from = max(x, left + played)
                val to = min(x + stitch, sewnEnd)
                if (to > from) drawLine(stitchColor, Offset(from, y), Offset(to, y), stitchWidth, StrokeCap.Round)
                x += step
            }

            // the played part: one solid red line with a lit head
            if (played > 0f) {
                drawLine(
                    thread.copy(alpha = shown),
                    Offset(left, y),
                    Offset(left + played, y),
                    YtMiniYoutubeMath.seamWidth(near) * density,
                    StrokeCap.Butt,
                )
                val head = Offset(left + played, y)
                halo(thread.copy(alpha = 0.5f * shown), head, (6f + 4f * near) * density)
                drawCircle(YtMiniYoutubeStyle.hot.copy(alpha = shown), 1.8f * density, head)
            }

            // the needle running ahead while the seam is sewn in
            val light = YtMiniYoutubeMath.needleLight(stitchT)
            if (stitchT < 1f && light > 0f) {
                val point = Offset(sewnEnd, y)
                halo(thread.copy(alpha = 0.6f * light * shown), point, 11f * density)
                drawCircle(Color.White.copy(alpha = light * shown), 1.6f * density, point)
            }

            // a drop taken: a pulse runs out along the seam from under the pill
            val ms = drop.value
            if (ms >= 0f) {
                val t = ((ms - 120f) / 640f).coerceIn(0f, 1f)
                if (t > 0f && t < 1f) {
                    // this canvas is mirrored right to left already
                    val from = g.text.centerX
                    for (sign in listOf(-1f, 1f)) {
                        val px = (from + sign * t * length * 0.6f).coerceIn(left, right)
                        halo(thread.copy(alpha = 0.7f * (1f - t) * shown), Offset(px, y), 9f * density)
                    }
                }
            }
        }

        // 5: the "+1" pill of a drop, out of the seam and back into it
        Canvas(Modifier.fillMaxSize()) {
            val ms = drop.value
            if (ms < 0f) return@Canvas
            val state = YtMiniYoutubeMath.dropPill(ms)
            if (!state.shown) return@Canvas
            val g = space.of(size.width, density)
            val restTop = size.height - restBox
            val seamY = size.height - overlap / 2f
            val plus = measurer.measure("+1", pillStyle, maxLines = 1, softWrap = false)
            val label = if (dropLabel.isEmpty()) null else measurer.measure(dropLabel, pillStyle, maxLines = 1, softWrap = false)
            val padH = 10f * density
            val gap = 7f * density
            val pillH = 26f * density
            val small = max(pillH, plus.size.width + 2f * padH)
            val full = if (label == null) small else min(plus.size.width + gap + label.size.width + 2f * padH, max(small, g.text.width))
            val w = small + (full - small) * state.widen
            val rise = state.rise
            val h = 3f * density + (pillH - 3f * density) * rise.coerceIn(0f, 1f)
            val cy = seamY + (restTop + g.content / 2f - seamY) * rise
            val cx0 = if (mirror) size.width - g.text.centerX else g.text.centerX
            val cx = cx0.coerceIn(w / 2f, max(w / 2f, size.width - w / 2f))
            val rect = Rect(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
            // a thin thread still ties the rising pill to the seam
            if (rise in 0.01f..0.99f) {
                drawLine(red.copy(alpha = 0.8f * (1f - rise)), Offset(cx, seamY), Offset(cx, rect.bottom), 1.5f * density)
            }
            halo(red.copy(alpha = 0.35f * rise.coerceIn(0f, 1f)), rect.center, w * 0.7f)
            drawRoundRect(red, rect.topLeft, rect.size, CornerRadius(h / 2f))
            val textAlpha = ((rise - 0.5f) / 0.5f).coerceIn(0f, 1f)
            if (textAlpha > 0f) {
                clipRect(rect.left, rect.top, rect.right, rect.bottom) {
                    val textY = rect.center.y - plus.size.height / 2f
                    val start = if (mirror) rect.right - padH - plus.size.width else rect.left + padH
                    // "+1" centred in the small pill, moving to the start as it widens
                    val centred = rect.center.x - plus.size.width / 2f
                    drawText(plus, topLeft = Offset(centred + (start - centred) * state.widen, textY), alpha = textAlpha)
                    if (label != null && state.widen > 0f) {
                        val lx = if (mirror) start - gap - label.size.width else start + plus.size.width + gap
                        drawText(label, topLeft = Offset(lx, rect.center.y - label.size.height / 2f), alpha = textAlpha * state.widen)
                    }
                }
            }
        }
    }
}

/** Title and channel beside the docked picture, at the foot of the box; they dim under a drop's pill. */
@Composable
private fun RestText(scene: YtMiniScene, space: YoutubeSpace, restBox: Float, drop: Animatable<Float, AnimationVector1D>) {
    val density = LocalDensity.current.density
    val info = scene.info
    Layout(
        modifier = Modifier.fillMaxSize(),
        content = {
            Box(
                Modifier
                    .layoutId("text")
                    .graphicsLayer {
                        val ms = drop.value
                        val pill = if (ms >= 0f) YtMiniYoutubeMath.dropPill(ms).rise.coerceIn(0f, 1f) else 0f
                        alpha = YtMiniYoutubeMath.restText(YtDock.progress, YtDock.peek) * (1f - 0.85f * pill)
                    },
            ) {
                Column(Modifier.align(Alignment.CenterStart)) {
                    Text(
                        text = info.title,
                        style = TextStyle(color = YtMiniYoutubeStyle.title, fontFamily = YtFonts.geist, fontWeight = FontWeight.Medium, fontSize = 13.5.sp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (info.channel.isNotBlank()) {
                        Spacer(Modifier.height(1.dp))
                        Text(
                            text = info.channel,
                            style = TextStyle(color = YtMiniYoutubeStyle.secondary, fontFamily = YtFonts.geist, fontWeight = FontWeight.Normal, fontSize = 11.5.sp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        },
    ) { measurables, constraints ->
        val w = constraints.maxWidth
        val h = constraints.maxHeight
        val g = space.of(w.toFloat(), density)
        val box = g.text
        val placeable = measurables.first().measure(
            Constraints.fixed(box.width.roundToInt().coerceAtLeast(0), g.content.roundToInt().coerceAtLeast(0)),
        )
        layout(w, h) {
            placeable.placeRelative(box.left.roundToInt(), (h - restBox).roundToInt())
        }
    }
}

/**
 * The preview beside and under the grown picture: title, channel and the play pill in a column,
 * the "Als Nächstes" row across the card with its red timer line. Placed from the box's foot, so
 * the parts rise with it; clipped at the foot, so the row comes up out of the seam.
 */
@Composable
private fun YoutubePeek(scene: YtMiniScene, red: Color, timer: Animatable<Float, AnimationVector1D>) {
    val grown = scene.peekLayout ?: return
    val density = LocalDensity.current.density
    val next = scene.next
    val target = scene.target
    val playing = scene.info.playing
    Layout(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { clip = true },
        content = {
            Column(
                verticalArrangement = Arrangement.Top,
                modifier = Modifier
                    .layoutId("title")
                    .graphicsLayer {
                        val shown = YtMiniYoutubeMath.columnShown(YtDock.peek)
                        alpha = shown
                        translationY = 6f * density * (1f - shown)
                    },
            ) {
                Text(
                    text = scene.info.title,
                    style = TextStyle(color = YtMiniYoutubeStyle.title, fontFamily = YtFonts.geist, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 18.sp),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (scene.info.channel.isNotBlank()) {
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = scene.info.channel,
                        style = TextStyle(color = YtMiniYoutubeStyle.secondary, fontFamily = YtFonts.geist, fontSize = 12.sp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            // the pill names what letting go on it does
            val onPill = target == YtMiniTarget.PILL
            Box(
                contentAlignment = Alignment.CenterStart,
                modifier = Modifier
                    .layoutId("pill")
                    .graphicsLayer {
                        val shown = YtMiniYoutubeMath.columnShown(YtDock.peek)
                        alpha = shown
                        translationY = 6f * density * (1f - shown)
                        val s = if (onPill) 1.04f else 1f
                        scaleX = s
                        scaleY = s
                    }
                    .clip(RoundedCornerShape(50))
                    .background(if (onPill) red else YtMiniYoutubeStyle.pill),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 14.dp)) {
                    YtMiniParts.Glyph(!playing, Color.White, Modifier.size(11.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(if (playing) R.string.yt_mini_youtube_pause else R.string.yt_mini_youtube_resume),
                        style = TextStyle(color = Color.White, fontFamily = YtFonts.geist, fontWeight = FontWeight.SemiBold, fontSize = 13.sp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            if (next != null) {
                NextRow(next, red, target == YtMiniTarget.NEXT, timer, Modifier.layoutId("next"))
            }
        },
    ) { measurables, constraints ->
        val w = constraints.maxWidth
        val h = constraints.maxHeight
        val parts = listOfNotNull(
            measurables.firstOrNull { it.layoutId == "title" }?.let { it to grown.title },
            measurables.firstOrNull { it.layoutId == "pill" }?.let { it to grown.pill },
            grown.next?.let { box -> measurables.firstOrNull { it.layoutId == "next" }?.let { it to box } },
        )
        val placed = parts.map { (measurable, box) ->
            val width = box.width.roundToInt().coerceAtLeast(0)
            val height = box.height.roundToInt().coerceAtLeast(0)
            measurable.measure(Constraints.fixed(width, height)) to box
        }
        layout(w, h) {
            // from the foot: the box's foot stays on the bar while it grows
            placed.forEach { (placeable, box) ->
                placeable.placeRelative(box.left.roundToInt(), (h - (grown.boxHeight - box.top)).roundToInt())
            }
        }
    }
}

/** "Als Nächstes": the next thumbnail, title and channel, a red line filling under it while held. */
@Composable
private fun NextRow(next: YtMiniNext, red: Color, active: Boolean, timer: Animatable<Float, AnimationVector1D>, modifier: Modifier) {
    val density = LocalDensity.current.density
    val shape = RoundedCornerShape(12.dp)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .graphicsLayer {
                val shown = YtMiniYoutubeMath.rowShown(YtDock.peek)
                alpha = shown
                translationY = 20f * density * (1f - shown)
                val s = if (active) 1.02f else 1f
                scaleX = s
                scaleY = s
            }
            .clip(shape)
            .background(if (active) YtMiniYoutubeStyle.rowActive else YtMiniYoutubeStyle.row)
            .drawBehind {
                // the timer: a thin red line along the row's foot, between its rounded corners
                val line = 2f * density
                val inset = 12f * density
                val y = size.height - line / 2f
                val length = (size.width - 2f * inset).coerceAtLeast(0f)
                drawLine(Color.White.copy(alpha = 0.08f), Offset(inset, y), Offset(inset + length, y), line)
                val t = timer.value
                if (t > 0f) {
                    drawLine(red.copy(alpha = if (active) 1f else 0.85f), Offset(inset, y), Offset(inset + length * t, y), line, StrokeCap.Round)
                }
            }
            .padding(horizontal = 8.dp, vertical = 8.dp),
    ) {
        AsyncImage(
            model = next.thumbnail,
            contentDescription = null,
            imageLoader = ImageHelper.loader(),
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxHeight()
                .width((YtMiniYoutubeLayout.ROW_THUMB_HEIGHT * 16f / 9f).dp)
                .clip(RoundedCornerShape(8.dp))
                .background(YtMiniYoutubeStyle.thumbGround),
        )
        Spacer(Modifier.width(10.dp))
        Column(verticalArrangement = Arrangement.Center) {
            Text(
                text = stringResource(if (active) R.string.yt_mini_youtube_release else R.string.yt_mini_peek_next),
                style = TextStyle(color = red, fontFamily = YtFonts.geist, fontWeight = FontWeight.SemiBold, fontSize = 11.sp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = next.title,
                style = TextStyle(color = YtMiniYoutubeStyle.title, fontFamily = YtFonts.geist, fontWeight = FontWeight.Medium, fontSize = 13.5.sp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (next.channel.isNotBlank()) {
                Text(
                    text = next.channel,
                    style = TextStyle(color = YtMiniYoutubeStyle.secondary, fontFamily = YtFonts.geist, fontSize = 11.5.sp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * Over the picture: its edge lights red as a dragged video comes near, the preview shows the
 * played part at the picture's foot (as the home's thumbnails do), and the glyph flash of a
 * toggle: a dark disc with the glyph and a red ring running out of it.
 */
@Composable
internal fun YoutubeOver(scene: YtMiniScene, thumbRadius: Float) {
    val red = scene.palette?.accent ?: Color(0xFFFF334B)
    val motion = LocalYtMotion.current
    val mirror = LocalLayoutDirection.current == LayoutDirection.Rtl
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
    Canvas(Modifier.fillMaxSize().clearAndSetSemantics {}) {
        val peek = YtDock.peek
        val near = scene.hover()
        if (near > 0f && peek <= 0f) {
            val sw = (1.5f + 1f * near) * density
            drawRoundRect(
                red.copy(alpha = 0.85f * near),
                topLeft = Offset(sw / 2f, sw / 2f),
                size = Size(size.width - sw, size.height - sw),
                cornerRadius = CornerRadius(max(0f, thumbRadius * density - sw / 2f)),
                style = Stroke(sw),
            )
        }

        val shown = YtMiniYoutubeMath.columnShown(peek)
        if (shown > 0f) {
            val h = 3f * density
            val played = size.width * scene.info.fraction.coerceIn(0f, 1f)
            drawRect(Color.White.copy(alpha = 0.3f * shown), Offset(0f, size.height - h), Size(size.width, h))
            drawRect(red.copy(alpha = shown), Offset(if (mirror) size.width - played else 0f, size.height - h), Size(played, h))
        }

        val t = flash.value
        if (t > 0f) {
            val alpha = if (t < 0.25f) t / 0.25f else 1f - (t - 0.25f) / 0.75f
            val radius = min(size.minDimension * 0.34f, 24f * density) * (0.88f + 0.16f * t)
            drawCircle(Color.Black.copy(alpha = 0.55f * alpha), radius, center)
            val ring = radius * (1f + 0.6f * t)
            drawCircle(red.copy(alpha = 0.9f * (1f - t)), ring, center, style = Stroke((2.2f * (1f - t) + 0.6f) * density))
            flashGlyph(playing, Color.White.copy(alpha = alpha), center, radius * 0.42f)
        }
    }
}

/** ▶ while it plays, ❚❚ while paused. */
private fun DrawScope.flashGlyph(playing: Boolean, color: Color, center: Offset, radius: Float) {
    if (playing) {
        val path = Path().apply {
            moveTo(center.x - radius * 0.7f, center.y - radius)
            lineTo(center.x + radius, center.y)
            lineTo(center.x - radius * 0.7f, center.y + radius)
            close()
        }
        drawPath(path, color)
    } else {
        val bar = radius * 0.6f
        drawRoundRect(color, Offset(center.x - radius * 0.85f, center.y - radius), Size(bar, radius * 2f), CornerRadius(bar / 4f))
        drawRoundRect(color, Offset(center.x + radius * 0.85f - bar, center.y - radius), Size(bar, radius * 2f), CornerRadius(bar / 4f))
    }
}
