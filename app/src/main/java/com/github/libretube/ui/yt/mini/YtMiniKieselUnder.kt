package com.github.libretube.ui.yt.mini

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.github.libretube.R
import com.github.libretube.helpers.ImageHelper
import com.github.libretube.ui.yt.LocalYtMotion
import com.github.libretube.ui.yt.YtDock
import com.github.libretube.ui.yt.YtFonts
import com.github.libretube.ui.yt.YtPalette
import kotlinx.coroutines.delay
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/*
 * YT: what the Kiesel player ("Schwarzer Kiesel") draws behind its picture: the black pebble out
 * of the black bar (a free stone while it falls, its foot flowing into the bar's curves as it
 * lands), title and channel in cream, the lavender disc with its cream ring of progress; the tall
 * pebble of the long press (squeezed first, then sprung up), its byline and the peach capsule of
 * the next video rolling in; the drop target (a cream counter capsule that pops "+1" and opens
 * into the label). Every moving value (minimize progress, preview, hover, the playback position)
 * is read while drawing or placing, so the player draws again but does not compose again while
 * it moves.
 */

/** The colours and type of the Kiesel player. */
internal class KieselStyle(palette: YtPalette) {
    val ink = Color(0xFF0E0E0E)
    val cream = Color(0xFFEDE8E3)
    val lavender = palette.accent
    val peach = palette.pink
    val track = cream.copy(alpha = 0.14f)
    val sheen = cream.copy(alpha = 0.08f)

    val title = TextStyle(color = cream, fontFamily = YtFonts.jakarta, fontWeight = FontWeight.Bold, fontSize = 13.5.sp)
    val channel = TextStyle(color = cream.copy(alpha = 0.55f), fontFamily = YtFonts.jakarta, fontWeight = FontWeight.Medium, fontSize = 11.sp)
    val byTitle = TextStyle(color = cream, fontFamily = YtFonts.jakarta, fontWeight = FontWeight.ExtraBold, fontSize = 14.5.sp)
    val byChannel = TextStyle(color = cream.copy(alpha = 0.6f), fontFamily = YtFonts.jakarta, fontWeight = FontWeight.Medium, fontSize = 11.5.sp)
    val nextLabel = TextStyle(
        color = ink.copy(alpha = 0.62f), fontFamily = YtFonts.jakarta, fontWeight = FontWeight.Bold,
        fontSize = 10.sp, letterSpacing = 0.04.em,
    )
    val nextTitle = TextStyle(color = ink, fontFamily = YtFonts.jakarta, fontWeight = FontWeight.ExtraBold, fontSize = 12.5.sp)
    val counter = TextStyle(color = ink, fontFamily = YtFonts.jakarta, fontWeight = FontWeight.ExtraBold, fontSize = 12.sp)
    val label = TextStyle(color = ink, fontFamily = YtFonts.jakarta, fontWeight = FontWeight.Bold, fontSize = 12.sp)
}

/** The geometry for the box's current width, computed again only when the width changes. */
private class KieselSpace(private val design: YtMiniDesign, private val density: Float) {
    private var restWidth = -1f
    private var restValue: YtMiniGeometry? = null
    private var peekWidth = -1f
    private var peekNext = false
    private var peekValue: YtMiniKieselPeek? = null

    fun rest(width: Float): YtMiniGeometry {
        val cached = restValue
        if (cached != null && width == restWidth) return cached
        return design.geometry(width, density).also {
            restValue = it
            restWidth = width
        }
    }

    fun peek(width: Float, hasNext: Boolean): YtMiniKieselPeek {
        val cached = peekValue
        if (cached != null && width == peekWidth && hasNext == peekNext) return cached
        return YtMiniKieselLayout.peek(design.spec, width, density, hasNext).also {
            peekValue = it
            peekWidth = width
            peekNext = hasNext
        }
    }
}

private fun kieselLerp(a: Float, b: Float, t: Float) = a + (b - a) * t

/**
 * The stone: [left] to [right], from [top] to [bottom], its top corners [r] round and its top
 * edge domed up by [dome] in the middle (a pebble is never quite flat), its bottom corners [rb]
 * round (0 once it sits in the bar).
 */
private fun Path.kieselStone(left: Float, top: Float, right: Float, bottom: Float, r0: Float, rb0: Float, dome: Float) {
    reset()
    val width = right - left
    val height = bottom - top - dome
    if (width <= 1f || height <= 1f) return
    val r = min(r0, min(width / 2f, height / 2f)).coerceAtLeast(0f)
    val rb = min(rb0, min(width / 2f, height - r)).coerceAtLeast(0f)
    val edge = top + dome
    moveTo(left, bottom - rb)
    lineTo(left, edge + r)
    if (r > 0f) arcTo(Rect(left, edge, left + 2f * r, edge + 2f * r), 180f, 90f, false) else lineTo(left, edge)
    // a cubic whose middle lies exactly on [top]: both controls at top - dome / 3
    val span = (right - r) - (left + r)
    cubicTo(left + r + span / 3f, top - dome / 3f, right - r - span / 3f, top - dome / 3f, right - r, edge)
    if (r > 0f) arcTo(Rect(right - 2f * r, edge, right, edge + 2f * r), 270f, 90f, false) else lineTo(right, edge)
    lineTo(right, bottom - rb)
    if (rb > 0f) arcTo(Rect(right - 2f * rb, bottom - 2f * rb, right, bottom), 0f, 90f, false) else lineTo(right, bottom)
    lineTo(left + rb, bottom)
    if (rb > 0f) arcTo(Rect(left, bottom - 2f * rb, left + 2f * rb, bottom), 90f, 90f, false) else lineTo(left, bottom)
    close()
}

/**
 * The foot that joins the stone to the bar: up the concave curves of [f] from the bar's surface
 * at [joint] to the stone's sides at [left] and [right], down to the box's bottom [h].
 */
private fun Path.kieselFoot(left: Float, right: Float, joint: Float, h: Float, f: Float) {
    reset()
    if (f <= 0.5f) return
    moveTo(left - f, joint)
    arcTo(Rect(left - 2f * f, joint - 2f * f, left, joint), 90f, -90f, false)
    lineTo(right, joint - f)
    arcTo(Rect(right, joint - 2f * f, right + 2f * f, joint), 180f, -90f, false)
    lineTo(right + f, h)
    lineTo(left - f, h)
    close()
}

/** The outline of the docked pebble out of the bar, open at the foot, for the hover rim. */
private fun Path.kieselRim(left: Float, top: Float, right: Float, joint: Float, f: Float, r0: Float, dome: Float) {
    reset()
    val r = min(r0, min((right - left) / 2f, (joint - top) / 2f)).coerceAtLeast(0f)
    val edge = top + dome
    moveTo(left - f, joint)
    arcTo(Rect(left - 2f * f, joint - 2f * f, left, joint), 90f, -90f, false)
    lineTo(left, edge + r)
    arcTo(Rect(left, edge, left + 2f * r, edge + 2f * r), 180f, 90f, false)
    val span = (right - r) - (left + r)
    cubicTo(left + r + span / 3f, top - dome / 3f, right - r - span / 3f, top - dome / 3f, right - r, edge)
    arcTo(Rect(right - 2f * r, edge, right, edge + 2f * r), 270f, 90f, false)
    lineTo(right, joint - f)
    arcTo(Rect(right, joint - 2f * f, right + 2f * f, joint), 180f, -90f, false)
}

private fun TextMeasurer.kieselLine(text: String, style: TextStyle, width: Float): TextLayoutResult =
    measure(
        text = text,
        style = style,
        overflow = TextOverflow.Ellipsis,
        maxLines = 1,
        constraints = Constraints(maxWidth = width.roundToInt().coerceAtLeast(1)),
    )

/** Where a line of [layout] starts in [box]: at its left, or right to left at its right. */
private fun kieselStart(box: YtMiniBox, layout: TextLayoutResult, rtl: Boolean): Float =
    if (rtl) box.right - layout.size.width else box.left

@Composable
internal fun KieselUnder(scene: YtMiniScene, design: YtMiniDesign) {
    val palette = scene.palette ?: return
    val style = remember(palette) { KieselStyle(palette) }
    val density = LocalDensity.current.density
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val spec = design.spec
    val space = remember(design, density) { KieselSpace(design, density) }
    val measurer = rememberTextMeasurer(cacheSize = 16)
    // the docked box's height as the constraints give it (whole px); the box grows from its foot
    val restBox = ((spec.height + spec.overlap) * density).roundToInt().toFloat()
    val overlap = spec.overlap * density
    val next = scene.next
    val grown = scene.peekLayout != null

    fun mirrorX(x: Float, width: Float) = if (rtl) width - x else x
    fun mirror(box: YtMiniBox, width: Float) = if (rtl) box.mirror(width) else box

    // the preview's progress for the layout (the spring may swing past 1; the box does not)
    fun t() = scene.peek().coerceIn(0f, 1f)

    Box(
        Modifier
            .fillMaxSize()
            .clearAndSetSemantics {}
            .graphicsLayer {
                // a dragged video coming near: the stone rises to meet it a little, from its foot
                val near = if (scene.peek() <= 0f) scene.hover() else 0f
                scaleX = 1f + 0.015f * near
                scaleY = 1f + 0.04f * near
                transformOrigin = TransformOrigin(0.5f, 1f)
            },
    ) {
        val stone = remember { Path() }
        val foot = remember { Path() }
        val rim = remember { Path() }
        Canvas(Modifier.fillMaxSize()) {
            val d = density
            val w = size.width
            val h = size.height
            val g = space.rest(w)
            val peek = scene.peek()
            val t = t()
            val joint = h - overlap
            val restTop = h - restBox
            val card = if (grown) space.peek(w, scene.next != null) else null

            // 1: the pebble. Minimizing, a free stone falls and its foot flows into the bar just
            // before it lands; the preview squeezes it, then springs it up into the tall pebble
            val merge = if (peek > 0f) 1f else YtMiniKieselLayout.merge(scene.progress())
            val squeeze = YtMiniKieselLayout.squeeze(t)
            val stretch = YtMiniKieselLayout.stretch(peek)
            val side = (squeeze * YtMiniKieselLayout.SQUEEZE_WIDEN - stretch * YtMiniKieselLayout.STRETCH_NARROW) * d
            val left = spec.body * d - side
            val right = w - spec.body * d + side
            val top = (if (peek > 0f) 0f else restTop) + squeeze * YtMiniKieselLayout.SQUEEZE_DROP * d
            val radius = if (card != null) kieselLerp(spec.radius * d, card.bodyRadius, t) else spec.radius * d
            val dome = YtMiniKieselLayout.DOME * d * (1f - t)
            val gap = 3f * d * (1f - merge)
            stone.kieselStone(left, top, right, joint - gap, radius, spec.radius * d * (1f - merge), dome)
            drawPath(stone, style.ink)
            foot.kieselFoot(left, right, joint, h, spec.fillet * d * merge)
            drawPath(foot, style.ink)
            // a little light on the stone's crown
            drawPath(stone, Brush.verticalGradient(listOf(style.sheen, Color.Transparent), startY = top, endY = top + 10f * d))

            // the hover rim: cream, as the dragged video comes near
            val near = if (peek <= 0f) scene.hover() else 0f
            if (near > 0f && merge >= 1f) {
                rim.kieselRim(left, top, right, joint, spec.fillet * d, radius, dome)
                drawPath(rim, style.cream.copy(alpha = 0.75f * near), style = Stroke(1.5f * d))
            }

            fun restY(y: Float) = h - (restBox - y)
            fun cardY(y: Float) = h - ((card?.geometry?.boxHeight ?: restBox) - y)
            val info = scene.info

            // 2: the docked row: title and channel give way first as the pebble grows
            val restAlpha = YtMiniParts.restFade(t) * if (peek > 0f) 1f else YtMiniKieselLayout.smooth(scene.progress(), 0.7f, 1f)
            if (restAlpha > 0f && g.text.width > 1f) {
                val box = mirror(g.text, w)
                val title = measurer.kieselLine(info.title, style.title, box.width)
                val channel = if (info.channel.isNotBlank()) measurer.kieselLine(info.channel, style.channel, box.width) else null
                val total = title.size.height + (channel?.let { it.size.height + d } ?: 0f)
                val y = restY((g.content - total) / 2f)
                drawText(title, topLeft = Offset(kieselStart(box, title, rtl), y), alpha = restAlpha)
                channel?.let { drawText(it, topLeft = Offset(kieselStart(box, it, rtl), y + title.size.height + d), alpha = restAlpha) }
            }

            // 3: the lavender disc with its cream ring, from beside the picture to the byline's
            // end (the play button rides with it, YtMiniKiesel.constrain)
            g.play?.let { play ->
                var cx = mirrorX(play.centerX, w)
                var cy = restY(play.centerY)
                var discDp = YtMiniKieselLayout.DISC.toFloat()
                if (card != null) {
                    cx = kieselLerp(cx, mirrorX(card.geometry.pill.centerX, w), t)
                    cy = kieselLerp(cy, cardY(card.geometry.pill.centerY), t)
                    discDp = kieselLerp(discDp, YtMiniKieselLayout.PEEK_DISC.toFloat(), t)
                }
                val held = scene.target == YtMiniTarget.PILL
                val disc = discDp / 2f * d * (if (held) 1.1f else 1f)
                val ring = disc + 4f * d
                val ringStroke = Stroke(2f * d, cap = StrokeCap.Round)
                val topLeft = Offset(cx - ring, cy - ring)
                val ringSize = Size(2f * ring, 2f * ring)
                drawArc(style.track, 0f, 360f, false, topLeft, ringSize, style = ringStroke)
                val sweep = 360f * info.fraction
                drawArc(style.cream, -90f, if (rtl) -sweep else sweep, false, topLeft, ringSize, style = ringStroke)
                drawCircle(style.lavender, disc, Offset(cx, cy))
            }

            // 4: the byline of the tall pebble, rising into place
            if (card != null) {
                val shown = YtMiniParts.peekFade(t)
                if (shown > 0f) {
                    val box = mirror(card.byline, w)
                    val title = measurer.kieselLine(info.title, style.byTitle, box.width)
                    val channel = if (info.channel.isNotBlank()) measurer.kieselLine(info.channel, style.byChannel, box.width) else null
                    val total = title.size.height + (channel?.let { it.size.height + d } ?: 0f)
                    val lift = 8f * d * (1f - shown)
                    val y = cardY(card.byline.top) + (card.byline.height - total) / 2f + lift
                    drawText(title, topLeft = Offset(kieselStart(box, title, rtl), y), alpha = shown)
                    channel?.let { drawText(it, topLeft = Offset(kieselStart(box, it, rtl), y + title.size.height + d), alpha = shown) }
                }
            }
        }

        // the peach capsule of the next video: it rolls in from the pebble's end under the
        // picture, its round thumbnail turning as it rolls; clipped to the pebble's sides, so it
        // comes out of the stone
        if (grown && next != null) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = spec.body.dp)
                    .clipToBounds(),
            ) {
                KieselNext(next, style, scene, space, rtl) { t() }
            }
        }

        KieselDrop(scene, style, measurer, rtl)
    }
}

/** How far the capsule of the next video rolls: from fully past the pebble's end into place. */
private fun kieselTravel(capsuleWidth: Float, density: Float): Float =
    capsuleWidth + (YtMiniKieselLayout.PAD + 4f) * density

/** The capsule of the next video, placed at its box of the grown pebble (px from the foot). */
@Composable
private fun KieselNext(next: YtMiniNext, style: KieselStyle, scene: YtMiniScene, space: KieselSpace, rtl: Boolean, t: () -> Float) {
    val density = LocalDensity.current.density
    val inset = scene.design.spec.body * density
    val held = scene.target == YtMiniTarget.NEXT
    val label = stringResource(R.string.yt_mini_peek_next)
    val thumb = YtMiniKieselLayout.NEXT_THUMB
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .layout { measurable, constraints ->
                // the parent is the pebble's width (the box less its insets on both sides)
                val boxWidth = constraints.maxWidth + 2f * inset
                val peek = space.peek(boxWidth, true)
                val box = peek.geometry.next
                val width = box?.width?.roundToInt()?.coerceAtLeast(0) ?: 0
                val height = box?.height?.roundToInt()?.coerceAtLeast(0) ?: 0
                val placeable = measurable.measure(Constraints.fixed(width, height))
                layout(constraints.maxWidth, constraints.maxHeight) {
                    if (box != null) {
                        val x = (if (rtl) box.mirror(boxWidth) else box).left - inset
                        val y = constraints.maxHeight - (peek.geometry.boxHeight - box.top)
                        placeable.place(x.roundToInt(), y.roundToInt())
                    }
                }
            }
            .graphicsLayer {
                val shown = t()
                // still rolling: the distance it has to go, from out past the pebble's end
                val left = YtMiniKieselLayout.roll(shown) * kieselTravel(size.width, density)
                translationX = if (rtl) -left else left
                alpha = if (shown <= 0.4f) 0f else 1f
                val s = if (held) 1.04f else 1f
                scaleX = s
                scaleY = s
            }
            .clip(RoundedCornerShape(50))
            .background(if (held) lerp(style.peach, style.cream, 0.35f) else style.peach)
            .padding(YtMiniKieselLayout.NEXT_INSET.dp),
    ) {
        AsyncImage(
            model = next.thumbnail,
            contentDescription = null,
            imageLoader = ImageHelper.loader(),
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(thumb.dp)
                .graphicsLayer {
                    // a wheel: it turns by the distance the capsule still has to roll
                    val capsule = scene.peekLayout?.next?.width ?: 0f
                    val distance = YtMiniKieselLayout.roll(t()) * kieselTravel(capsule, density)
                    val turn = YtMiniKieselLayout.turn(distance, size.width / 2f)
                    rotationZ = if (rtl) -turn else turn
                }
                .clip(CircleShape)
                .background(style.ink.copy(alpha = 0.12f)),
        )
        Spacer(Modifier.width(10.dp))
        Column(verticalArrangement = Arrangement.Center, modifier = Modifier.padding(end = 12.dp)) {
            Text(label, style = style.nextLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(next.title, style = style.nextTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * The drop target in the language of the pebbles: as a dragged video comes near, a small cream
 * capsule peeks out of the stone's top end corner; a drop pops it up with "+1" (the stone takes
 * the weight and hops, YtMiniKiesel.accept), it stretches sideways into the label, holds it,
 * closes again and sinks back into the stone.
 */
@Composable
private fun KieselDrop(scene: YtMiniScene, style: KieselStyle, measurer: TextMeasurer, rtl: Boolean) {
    val motion = LocalYtMotion.current
    // the last accepted drop: 0 to 1 over the whole gesture, 1 when nothing shows
    val drop = remember { Animatable(1f) }
    val accepted = YtDock.accepted
    var seen by remember { mutableIntStateOf(accepted) }
    LaunchedEffect(accepted) {
        if (accepted == seen) return@LaunchedEffect
        seen = accepted
        if (motion) {
            drop.snapTo(0f)
            drop.animateTo(1f, tween(YtMiniKieselLayout.DROP_MS, easing = LinearEasing))
        } else {
            drop.snapTo(YtMiniKieselLayout.DROP_STILL)
            delay(1400)
            drop.snapTo(1f)
        }
    }
    val last = YtDock.lastDrop
    val label = when {
        last == null -> null
        last.next -> stringResource(R.string.yt_mini_drop_next)
        else -> stringResource(R.string.yt_mini_drop_end, last.position)
    }
    val plus = stringResource(R.string.yt_mini_kiesel_plus_one)
    val spec = scene.design.spec

    Canvas(Modifier.fillMaxSize()) {
        if (scene.peek() > 0f) return@Canvas
        val d = density
        val w = size.width
        val h = size.height
        val restBox = ((spec.height + spec.overlap) * d).roundToInt().toFloat()
        val bodyEnd = w - spec.body * d
        val cx0 = bodyEnd - YtMiniKieselLayout.COUNTER_IN * d
        // the capsule's end stays put while it opens towards the start
        val endX = if (rtl) w - (cx0 + YtMiniKieselLayout.COUNTER_WIDTH / 2f * d) else cx0 + YtMiniKieselLayout.COUNTER_WIDTH / 2f * d
        val cy = h - restBox + 2f * d
        val ch = YtMiniKieselLayout.COUNTER_HEIGHT * d
        val cw = YtMiniKieselLayout.COUNTER_WIDTH * d
        val t = drop.value
        val running = t > 0f && t < 1f

        if (!running) {
            // a dragged video near: the capsule peeks out of the stone, a cream "+" on it
            val near = scene.hover()
            val s = 0.6f * YtMiniKieselLayout.smooth(near, 0f, 0.6f)
            if (s <= 0f) return@Canvas
            val center = Offset(if (rtl) endX + cw / 2f else endX - cw / 2f, cy + (1f - s / 0.6f) * 8f * d)
            scale(s, center) {
                drawRoundRect(style.cream, Offset(center.x - cw / 2f, center.y - ch / 2f), Size(cw, ch), CornerRadius(ch / 2f))
                val arm = 4.5f * d
                val stroke = 2f * d
                drawLine(style.ink, Offset(center.x - arm, center.y), Offset(center.x + arm, center.y), stroke, StrokeCap.Round)
                drawLine(style.ink, Offset(center.x, center.y - arm), Offset(center.x, center.y + arm), stroke, StrokeCap.Round)
            }
            return@Canvas
        }

        val pop = YtMiniKieselLayout.counter(t)
        if (pop <= 0f) return@Canvas
        val open = YtMiniKieselLayout.open(t)
        val sink = YtMiniKieselLayout.sink(t)
        val most = (bodyEnd - spec.body * d - 24f * d).coerceAtLeast(cw)
        val text = label?.let { measurer.kieselLine(it, style.label, most - 24f * d) }
        val openWidth = text?.let { max(cw, it.size.width + 24f * d) } ?: cw
        val width = kieselLerp(cw, openWidth, open.coerceAtLeast(0f))
        val left = if (rtl) endX else endX - width
        translate(top = sink * 12f * d) {
            val center = Offset(left + width / 2f, cy)
            scale(pop * (1f - sink), center) {
                drawRoundRect(style.cream, Offset(left, cy - ch / 2f), Size(width, ch), CornerRadius(ch / 2f), alpha = 1f - sink)
                // "+1" while closed, the label once it has opened past halfway
                val labelAlpha = YtMiniKieselLayout.smooth(open, 0.45f, 0.8f)
                val plusAlpha = 1f - labelAlpha
                if (plusAlpha > 0f) {
                    val p = measurer.measure(plus, style.counter)
                    val px = if (rtl) left + cw / 2f else left + width - cw / 2f
                    drawText(p, topLeft = Offset(px - p.size.width / 2f, cy - p.size.height / 2f), alpha = plusAlpha * (1f - sink))
                }
                if (text != null && labelAlpha > 0f) {
                    drawText(text, topLeft = Offset(left + (width - text.size.width) / 2f, cy - text.size.height / 2f), alpha = labelAlpha * (1f - sink))
                }
            }
        }
    }
}
