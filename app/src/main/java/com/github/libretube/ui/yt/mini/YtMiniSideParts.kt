package com.github.libretube.ui.yt.mini

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.github.libretube.R
import com.github.libretube.helpers.ImageHelper
import com.github.libretube.ui.yt.LocalYtMotion
import com.github.libretube.ui.yt.YtDock
import com.github.libretube.ui.yt.YtDrag
import com.github.libretube.ui.yt.YtDragKind
import com.github.libretube.ui.yt.YtFonts
import com.github.libretube.ui.yt.YtPalette
import com.github.libretube.util.PlayingQueue
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/*
 * YT: what the Side player ("Gesprächsblase") draws behind its picture: the speech bubble out of
 * the bar with the channel's avatar on its corner, title and channel, the apricot disc with its
 * ring of progress; the post card of the long press (byline, the next video as a reply bubble);
 * the drop target (someone typing, then a "+1" that bursts like the heart of a Side post and a
 * label in a bubble). Every moving value (minimize progress, preview, hover, the playback
 * position) is read while drawing or placing, so the player draws again but does not compose
 * again while it moves.
 */

/** The avatar's pop after the landing (YtMiniSide.land drives it), 0 to a little over 1. */
internal object YtMiniSidePop {
    var value by mutableFloatStateOf(1f)
}

/** The colours and type of the Side player. */
internal class SideStyle(palette: YtPalette) {
    val body = palette.surface
    val border = Color(0xFF3F4043)
    val raised = palette.raised
    val accent = palette.accent
    val onAccent = palette.onAccent
    val text = palette.text
    val track = palette.text.copy(alpha = 0.12f)
    val dots = palette.text.copy(alpha = 0.8f)

    val title = TextStyle(color = palette.text, fontFamily = YtFonts.figtree, fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp)
    val channel = TextStyle(color = palette.textSecondary, fontFamily = YtFonts.figtree, fontWeight = FontWeight.Medium, fontSize = 11.5.sp)
    val byName = TextStyle(color = palette.text, fontFamily = YtFonts.figtree, fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp)
    val byTitle = TextStyle(color = palette.text, fontFamily = YtFonts.figtree, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 17.sp)
    val nextLabel = TextStyle(color = palette.accent, fontFamily = YtFonts.figtree, fontWeight = FontWeight.SemiBold, fontSize = 10.5.sp)
    val nextTitle = TextStyle(color = palette.text, fontFamily = YtFonts.figtree, fontWeight = FontWeight.Medium, fontSize = 12.sp)
    val initial = TextStyle(color = palette.textSecondary, fontFamily = YtFonts.figtree, fontWeight = FontWeight.SemiBold, fontSize = 11.sp)
    val plus = TextStyle(color = palette.onAccent, fontFamily = YtFonts.figtree, fontWeight = FontWeight.Bold, fontSize = 11.sp)
    val label = TextStyle(color = palette.onAccent, fontFamily = YtFonts.figtree, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
}

/** The geometry for the box's current width, computed again only when the width changes. */
private class SideSpace(private val design: YtMiniDesign, private val density: Float) {
    private var restWidth = -1f
    private var restValue: YtMiniGeometry? = null
    private var cardWidth = -1f
    private var cardNext = false
    private var cardValue: YtMiniSideCard? = null

    fun rest(width: Float): YtMiniGeometry {
        val cached = restValue
        if (cached != null && width == restWidth) return cached
        return design.geometry(width, density).also {
            restValue = it
            restWidth = width
        }
    }

    fun card(width: Float, hasNext: Boolean): YtMiniSideCard {
        val cached = cardValue
        if (cached != null && width == cardWidth && hasNext == cardNext) return cached
        return YtMiniSideLayout.card(design.spec, width, density, hasNext).also {
            cardValue = it
            cardWidth = width
            cardNext = hasNext
        }
    }
}

private fun lerpF(a: Float, b: Float, t: Float) = a + (b - a) * t

/**
 * The bubble: from the bar's surface up the concave fillet into the body, round at the top
 * ([top] its upper edge, [joint] where it meets the bar), down the other fillet; [open] leaves
 * the foot off (for the border). The curves shrink with the bubble while it rises.
 */
private fun Path.sideBubble(w: Float, h: Float, top: Float, joint: Float, b: Float, f0: Float, r0: Float, inset: Float, open: Boolean) {
    reset()
    val avail = joint - top
    if (avail <= 0.5f) return
    val f = min(f0, avail / 2f)
    val r = min(min(r0, avail - f), (w - 2f * b) / 2f).coerceAtLeast(0f)
    val left = b + inset
    val right = w - b - inset
    val tp = top + inset
    moveTo(b - f, if (open) joint + inset else joint)
    arcTo(Rect(b - 2f * f, joint - 2f * f, b, joint), 90f, -90f, false)
    lineTo(left, tp + r)
    if (r > 0f) arcTo(Rect(left, tp, left + 2f * r, tp + 2f * r), 180f, 90f, false) else lineTo(left, tp)
    lineTo(right - r, tp)
    if (r > 0f) arcTo(Rect(right - 2f * r, tp, right, tp + 2f * r), 270f, 90f, false) else lineTo(right, tp)
    lineTo(right, joint - f)
    arcTo(Rect(w - b, joint - 2f * f, w - b + 2f * f, joint), 180f, -90f, false)
    if (!open) {
        lineTo(w - b + f, h)
        lineTo(b - f, h)
        close()
    }
}

/**
 * Places its content at the offset [at] gives for the parent's size and the content's own
 * size, read while placing, so a moving position only places again.
 */
private fun Modifier.placeAt(at: (parent: IntSize, self: IntSize) -> IntOffset): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
    val width = constraints.maxWidth
    val height = constraints.maxHeight
    layout(width, height) {
        placeable.place(at(IntSize(width, height), IntSize(placeable.width, placeable.height)))
    }
}

private fun TextMeasurer.line(text: String, style: TextStyle, width: Float, lines: Int = 1): TextLayoutResult =
    measure(
        text = text,
        style = style,
        overflow = TextOverflow.Ellipsis,
        maxLines = lines,
        constraints = Constraints(maxWidth = width.roundToInt().coerceAtLeast(1)),
    )

/** Where a line of [layout] starts in [box]: at its left, or right to left at its right. */
private fun startX(box: YtMiniBox, layout: TextLayoutResult, rtl: Boolean): Float =
    if (rtl) box.right - layout.size.width else box.left

@Composable
internal fun SideUnder(scene: YtMiniScene, design: YtMiniDesign) {
    val palette = scene.palette ?: return
    val style = remember(palette) { SideStyle(palette) }
    val density = LocalDensity.current.density
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val spec = design.spec
    val space = remember(design, density) { SideSpace(design, density) }
    val measurer = rememberTextMeasurer(cacheSize = 16)
    // the docked box's height as the constraints give it (whole px); the box grows from its foot
    val restBox = ((spec.height + spec.overlap) * density).roundToInt().toFloat()
    val overlap = spec.overlap * density

    // the avatar of the playing video's channel (the queue knows its current video)
    val avatar by produceState(PlayingQueue.getCurrent()?.uploaderAvatar) {
        PlayingQueue.revision.collect { value = PlayingQueue.getCurrent()?.uploaderAvatar }
    }
    val initial by remember { derivedStateOf { scene.info.channel.trim().take(1).uppercase() } }
    val next = scene.next
    val nextLabel = next?.channel?.takeIf { it.isNotBlank() }?.let { stringResource(R.string.yt_mini_side_next_from, it) }
        ?: stringResource(R.string.yt_mini_peek_next)
    val hasCard = scene.peekLayout != null

    fun mirrorX(x: Float, width: Float) = if (rtl) width - x else x
    fun mirror(box: YtMiniBox, width: Float) = if (rtl) box.mirror(width) else box

    // the preview's progress for the layout (the spring may swing past 1; the box does not)
    fun t() = scene.peek().coerceIn(0f, 1f)

    Box(
        Modifier
            .fillMaxSize()
            .clearAndSetSemantics {}
            .graphicsLayer {
                // a dragged video coming near: the bubble leans in a little, from its foot
                val near = if (scene.peek() <= 0f) scene.hover() else 0f
                val s = 1f + 0.02f * near
                scaleX = s
                scaleY = s
                transformOrigin = TransformOrigin(0.5f, 1f)
            },
    ) {
        val body = remember { Path() }
        val edge = remember { Path() }
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val g = space.rest(w)
            val t = t()
            val peek = scene.peek()
            val joint = h - overlap
            val restTop = h - restBox
            // 1: the bubble rises out of the bar while minimizing; the preview grows the box
            val top = if (peek > 0f) 0f else lerpF(joint, restTop, YtMiniSideLayout.rise(scene.progress()))
            val b = spec.body * density
            body.sideBubble(w, h, top, joint, b, spec.fillet * density, spec.radius * density, 0f, open = false)
            drawPath(body, style.body)
            edge.sideBubble(w, h, top, joint, b, spec.fillet * density, spec.radius * density, density / 2f, open = true)
            drawPath(edge, style.border, style = Stroke(density))
            val near = if (peek <= 0f) scene.hover() else 0f
            if (near > 0f) drawPath(edge, style.accent.copy(alpha = 0.9f * near), style = Stroke(1.5f * density))

            val info = scene.info
            val card = if (hasCard) space.card(w, scene.next != null) else null
            fun restY(y: Float) = h - (restBox - y)
            fun cardY(y: Float) = h - ((card?.geometry?.boxHeight ?: restBox) - y)

            // 2: the docked row: title and channel give way first as the card grows
            val restAlpha = YtMiniParts.restFade(t)
            if (restAlpha > 0f && g.text.width > 1f) {
                val box = mirror(g.text, w)
                val title = measurer.line(info.title, style.title, box.width)
                val channel = if (info.channel.isNotBlank()) measurer.line(info.channel, style.channel, box.width) else null
                val gap = density
                val total = title.size.height + (channel?.let { it.size.height + gap } ?: 0f)
                val y = restY((g.content - total) / 2f)
                drawText(title, topLeft = Offset(startX(box, title, rtl), y), alpha = restAlpha)
                channel?.let { drawText(it, topLeft = Offset(startX(box, it, rtl), y + title.size.height + gap), alpha = restAlpha) }
            }

            // 3: the apricot disc with its ring of progress, from beside the picture to the
            // byline's end (the play button rides with it, YtMiniSide.constrain)
            g.play?.let { play ->
                var cx = mirrorX(play.centerX, w)
                var cy = restY(play.centerY)
                if (card != null) {
                    cx = lerpF(cx, mirrorX(card.geometry.pill.centerX, w), t)
                    cy = lerpF(cy, cardY(card.geometry.pill.centerY), t)
                }
                val held = scene.target == YtMiniTarget.PILL
                val disc = YtMiniSideLayout.DISC / 2f * density * (if (held) 1.12f else 1f)
                val ring = (YtMiniSideLayout.DISC / 2f + 4f) * density
                val stroke = Stroke(2f * density, cap = StrokeCap.Round)
                val topLeft = Offset(cx - ring, cy - ring)
                drawArc(style.track, 0f, 360f, false, topLeft, Size(2f * ring, 2f * ring), style = stroke)
                val sweep = 360f * info.fraction
                drawArc(style.accent, -90f, if (rtl) -sweep else sweep, false, topLeft, Size(2f * ring, 2f * ring), style = stroke)
                drawCircle(style.accent, disc, Offset(cx, cy))
            }

            // 4: the post card: byline under the picture, the next video as a reply bubble
            if (card != null) {
                val shown = YtMiniParts.peekFade(t)
                if (shown > 0f) {
                    val box = mirror(card.text, w)
                    val lift = 8f * density * (1f - shown)
                    var y = cardY(card.text.top) + 3f * density + lift
                    if (info.channel.isNotBlank()) {
                        val name = measurer.line(info.channel, style.byName, box.width)
                        drawText(name, topLeft = Offset(startX(box, name, rtl), y), alpha = shown)
                        y += name.size.height + 2f * density
                    }
                    val title = measurer.line(info.title, style.byTitle, box.width, lines = 2)
                    drawText(title, topLeft = Offset(startX(box, title, rtl), y), alpha = shown)
                }
                val reply = card.geometry.next
                if (reply != null && next != null) {
                    val s = YtMiniSideLayout.smooth(t, 0.5f, 1f)
                    if (s > 0f) {
                        clipRect(top = 0f, bottom = joint) {
                            drawReply(reply, next.title, nextLabel, s, scene.target == YtMiniTarget.NEXT, style, measurer, w, rtl) { cardY(it) }
                        }
                    }
                }
            }
        }

        // the docked avatar on the bubble's corner: it comes last while minimizing, pops in
        // after the landing and gives way as the card grows (its own pops in the byline)
        val avatarSize = YtMiniSideLayout.AVATAR.dp
        SideAvatar(
            avatar, initial, style, avatarSize,
            Modifier
                .placeAt { parent, self ->
                    val (cx, cy) = YtMiniSideLayout.avatarCenter(spec, density)
                    val x = mirrorX(cx, parent.width.toFloat())
                    val y = parent.height - (restBox - cy)
                    IntOffset((x - self.width / 2f).roundToInt(), (y - self.height / 2f).roundToInt())
                }
                .graphicsLayer {
                    val s = YtMiniSideLayout.avatarGate(scene.progress()) * YtMiniSidePop.value *
                        (1f - YtMiniSideLayout.smooth(t(), 0f, 0.3f))
                    scaleX = s
                    scaleY = s
                    alpha = if (s <= 0.01f) 0f else 1f
                },
        )

        if (hasCard) {
            SideAvatar(
                avatar, initial, style, YtMiniSideLayout.CARD_AVATAR.dp,
                Modifier
                    .placeAt { parent, self ->
                        val card = space.card(parent.width.toFloat(), scene.next != null)
                        val a = mirror(card.avatar, parent.width.toFloat())
                        val y = parent.height - (card.geometry.boxHeight - a.centerY)
                        IntOffset((a.centerX - self.width / 2f).roundToInt(), (y - self.height / 2f).roundToInt())
                    }
                    .graphicsLayer {
                        val t = t()
                        val s = if (t <= 0.5f) 0f else YtMiniSideLayout.backOut((t - 0.5f) / 0.5f, 1.6f)
                        scaleX = s
                        scaleY = s
                        alpha = if (s <= 0.01f) 0f else 1f
                    },
            )
            if (next != null) {
                val thumbShape = remember { RoundedCornerShape(8.dp) }
                AsyncImage(
                    model = next.thumbnail,
                    contentDescription = null,
                    imageLoader = ImageHelper.loader(),
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .placeAt { parent, _ ->
                            val card = space.card(parent.width.toFloat(), true)
                            val reply = card.geometry.next ?: return@placeAt IntOffset(-10000, 0)
                            val thumb = mirror(replyThumb(reply, density), parent.width.toFloat())
                            val y = parent.height - (card.geometry.boxHeight - thumb.top)
                            IntOffset(thumb.left.roundToInt(), y.roundToInt())
                        }
                        .graphicsLayer {
                            val s = YtMiniSideLayout.smooth(t(), 0.5f, 1f)
                            alpha = s
                            translationY = REPLY_RISE * density * (1f - s)
                        }
                        .size(REPLY_THUMB_WIDTH.dp, REPLY_THUMB_HEIGHT.dp)
                        .clip(thumbShape)
                        .background(style.raised),
                )
            }
        }

        SideDrop(scene, style, space, measurer, rtl)
    }
}

/** The reply bubble's thumbnail, at its start, centred in its height. */
private const val REPLY_THUMB_WIDTH = 57
private const val REPLY_THUMB_HEIGHT = 32
private const val REPLY_RISE = 12f

private fun replyThumb(reply: YtMiniBox, density: Float): YtMiniBox {
    val top = reply.top + (reply.height - REPLY_THUMB_HEIGHT * density) / 2f
    val left = reply.left + 6f * density
    return YtMiniBox(left, top, left + REPLY_THUMB_WIDTH * density, top + REPLY_THUMB_HEIGHT * density)
}

/**
 * The next video as a reply in the conversation: a smaller bubble at the card's end, its
 * bottom end corner pinched like a chat reply, rising out of the joint ([shown] 0 to 1).
 */
private fun DrawScope.drawReply(
    reply: YtMiniBox,
    title: String,
    label: String,
    shown: Float,
    held: Boolean,
    style: SideStyle,
    measurer: TextMeasurer,
    width: Float,
    rtl: Boolean,
    y: (Float) -> Float,
) {
    val d = density
    val lift = REPLY_RISE * d * (1f - shown)
    val box = if (rtl) reply.mirror(width) else reply
    val top = y(reply.top) + lift
    val bottom = y(reply.bottom) + lift
    val round = CornerRadius(16f * d)
    val pinch = CornerRadius(5f * d)
    val shape = RoundRect(
        left = box.left,
        top = top,
        right = box.right,
        bottom = bottom,
        topLeftCornerRadius = round,
        topRightCornerRadius = round,
        bottomRightCornerRadius = if (rtl) round else pinch,
        bottomLeftCornerRadius = if (rtl) pinch else round,
    )
    val path = Path().apply { addRoundRect(shape) }
    drawPath(path, (if (held) lerp(style.raised, style.accent, 0.22f) else style.raised).copy(alpha = shown))
    if (held) drawPath(path, style.accent.copy(alpha = shown), style = Stroke(1.5f * d))

    val thumb = replyThumb(reply, d)
    val textLeft = thumb.right + 10f * d
    val textRight = reply.right - 12f * d
    val text = YtMiniBox(textLeft, reply.top, max(textRight, textLeft + 1f), reply.bottom).let { if (rtl) it.mirror(width) else it }
    val head = measurer.line(label, style.nextLabel, text.width)
    val name = measurer.line(title, style.nextTitle, text.width)
    val total = head.size.height + name.size.height + d
    val textTop = top + (bottom - top - total) / 2f
    drawText(head, topLeft = Offset(startX(text, head, rtl), textTop), alpha = shown)
    drawText(name, topLeft = Offset(startX(text, name, rtl), textTop + head.size.height + d), alpha = shown)
}

/** The avatar in its apricot ring, the channel's initial until the picture is there. */
@Composable
private fun SideAvatar(model: String?, initial: String, style: SideStyle, size: Dp, modifier: Modifier) {
    val ring = YtMiniSideLayout.RING.dp
    Box(
        modifier
            .size(size + ring * 2)
            .clip(CircleShape)
            .background(style.accent)
            .padding(ring),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .clip(CircleShape)
                .background(style.raised),
            contentAlignment = Alignment.Center,
        ) {
            if (initial.isNotEmpty()) Text(initial, style = style.initial, maxLines = 1)
            if (model != null) {
                AsyncImage(
                    model = model,
                    contentDescription = null,
                    imageLoader = ImageHelper.loader(),
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

/**
 * The drop target in the language of a conversation: while a dragged video comes near, someone
 * is typing on the bubble's other corner; a drop turns the dots into an apricot "+1" that
 * bursts like the heart of a Side post (ring and sparks), and the label answers in a bubble
 * above it.
 */
@Composable
private fun SideDrop(scene: YtMiniScene, style: SideStyle, space: SideSpace, measurer: TextMeasurer, rtl: Boolean) {
    val motion = LocalYtMotion.current
    val dragging by remember { derivedStateOf { YtDrag.session?.kind == YtDragKind.VIDEO } }
    // the dots move only while a video is dragged (and motion is on)
    val typing: State<Float>? = if (dragging && motion) {
        rememberInfiniteTransition(label = "sideTyping").animateFloat(
            0f, 1f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "sideTypingPhase",
        )
    } else {
        null
    }

    // the last accepted drop: 0 to 1 over the whole gesture, 1 when nothing shows
    val drop = remember { Animatable(1f) }
    val accepted = YtDock.accepted
    var seen by remember { mutableIntStateOf(accepted) }
    LaunchedEffect(accepted) {
        if (accepted == seen) return@LaunchedEffect
        seen = accepted
        if (motion) {
            drop.snapTo(0f)
            drop.animateTo(1f, tween(YtMiniSideLayout.DROP_MS, easing = LinearEasing))
        } else {
            drop.snapTo(YtMiniSideLayout.DROP_STILL)
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
    val plus = stringResource(R.string.yt_mini_side_plus_one)
    val spec = scene.design.spec

    Canvas(Modifier.fillMaxSize()) {
        if (scene.peek() > 0f) return@Canvas
        val d = density
        val w = size.width
        val h = size.height
        val restBox = ((spec.height + spec.overlap) * d).roundToInt().toFloat()
        val (bx0, by0) = YtMiniSideLayout.badgeCenter(spec, w, d)
        val center = Offset(if (rtl) w - bx0 else bx0, h - (restBox - by0))
        val t = drop.value
        val running = t > 0f && t < 1f

        // someone is typing: a small bubble with three dots, growing as the video comes near
        val near = scene.hover()
        val appear = if (running) 0f else YtMiniSideLayout.smooth(near, 0f, 0.6f)
        if (appear > 0f) {
            val bw = 34f * d * appear
            val bh = 20f * d * appear
            drawRoundRect(style.raised, Offset(center.x - bw / 2f, center.y - bh / 2f), Size(bw, bh), CornerRadius(bh / 2f))
            drawRoundRect(style.border, Offset(center.x - bw / 2f, center.y - bh / 2f), Size(bw, bh), CornerRadius(bh / 2f), style = Stroke(d))
            // the bubble's tail of two small rounds, towards the body
            val toward = if (rtl) 1f else -1f
            drawCircle(style.raised, 3f * d * appear, Offset(center.x + toward * 13f * d * appear, center.y + 11f * d * appear))
            drawCircle(style.raised, 1.6f * d * appear, Offset(center.x + toward * 18f * d * appear, center.y + 15f * d * appear))
            val phase = typing?.value ?: 0f
            for (k in 0..2) {
                val bounce = max(0f, sin(2f * PI.toFloat() * (phase - k * 0.15f)))
                drawCircle(
                    style.dots.copy(alpha = style.dots.alpha * appear),
                    2.2f * d * appear,
                    Offset(center.x + (k - 1) * 7f * d * appear, center.y - 2f * d * bounce * appear),
                )
            }
        }

        if (!running) return@Canvas
        // the "+1": pops with an overshoot, holds, shrinks away
        val pop = YtMiniSideLayout.badgeScale(t)
        val radius = YtMiniSideLayout.BADGE * d
        val q = YtMiniSideLayout.burst(t)
        if (q > 0f && q < 1f) {
            val fade = 1f - q
            drawCircle(style.accent.copy(alpha = 0.7f * fade), radius * (0.6f + 1.4f * q), center, style = Stroke(2f * d * fade))
            for (k in 0 until 6) {
                val angle = (k * 60f - 90f) * (PI.toFloat() / 180f)
                val distance = radius * (0.9f + 1.5f * q)
                drawCircle(style.accent.copy(alpha = fade), 2f * d * fade, center + Offset(cos(angle) * distance, sin(angle) * distance))
            }
        }
        if (pop > 0f) {
            drawCircle(style.accent, radius * pop, center)
            val text = measurer.measure(plus, style.plus)
            scale(pop, center) {
                drawText(text, topLeft = Offset(center.x - text.size.width / 2f, center.y - text.size.height / 2f))
            }
        }

        // the label answers in a bubble above the badge, its tail pointing at it
        val shown = YtMiniSideLayout.label(t)
        if (label != null && shown > 0f) {
            val g = space.rest(w)
            val most = (g.bodyEnd - g.bodyStart - 16f * d).coerceAtLeast(1f)
            val text = measurer.line(label, style.label, most)
            val padH = 12f * d
            val padV = 6f * d
            val bw = text.size.width + 2f * padH
            val bh = text.size.height + 2f * padV
            val rise = 6f * d * (1f - shown)
            val bottom = center.y - radius - 7f * d + rise
            val end = center.x + if (rtl) -4f * d else 4f * d
            val left = if (rtl) end else end - bw
            val color = style.accent.copy(alpha = shown)
            drawRoundRect(color, Offset(left, bottom - bh), Size(bw, bh), CornerRadius(bh / 2f))
            // the tail, down towards the badge
            val tailX = center.x
            val tail = Path().apply {
                moveTo(tailX - 5f * d, bottom - 1f)
                lineTo(tailX + 5f * d, bottom - 1f)
                lineTo(tailX, bottom + 5f * d)
                close()
            }
            drawPath(tail, color)
            drawText(text, topLeft = Offset(left + padH, bottom - bh + padV), alpha = shown)
        }
    }
}

/** The glyph flash of play and pause, in apricot. */
@Composable
internal fun SideOver(scene: YtMiniScene) {
    val palette = scene.palette
    val colors = if (palette != null) {
        YtMiniParts.FlashColors(palette.accent.copy(alpha = 0.92f), palette.onAccent)
    } else {
        YtMiniParts.flashColors(scene)
    }
    YtMiniParts.Flash(scene, colors)
}
