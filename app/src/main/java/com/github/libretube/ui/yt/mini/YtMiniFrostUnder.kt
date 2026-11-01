package com.github.libretube.ui.yt.mini

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
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
import com.github.libretube.ui.yt.YtDrag
import com.github.libretube.ui.yt.YtEaseInOut
import com.github.libretube.ui.yt.YtEaseOut
import com.github.libretube.ui.yt.YtFonts
import com.github.libretube.ui.yt.frostGlass
import com.github.libretube.ui.yt.liquidGlass
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

/*
 * YT: what the Frost player draws behind its picture, inside the box: the pane of dark frosted
 * glass grown out of the bar (with fine grain and a soft glow inside its rim), the orange seam of
 * progress at its foot, the docked row (title, channel, the white disc behind play), the preview
 * (title row, the frost pill, the frozen card of the next video) and the drop (a card freezing
 * into the pane, the label on frost).
 */

/** The docked geometry for the box's width, made once per width (layout runs every frame while the box grows). */
private class FrostRest {
    private var width = -1f
    private var density = 0f
    private var g: YtMiniGeometry? = null

    fun of(width: Float, density: Float): YtMiniGeometry {
        val cached = g
        if (cached != null && width == this.width && density == this.density) return cached
        this.width = width
        this.density = density
        return YtMiniFrost.geometry(width, density).also { g = it }
    }
}

/** Where the box sits in the window and where the last card landed on it (px). */
private class FrostDrop {
    var origin = Offset.Zero
    var pointer = Offset.Unspecified
    var landing = Offset.Unspecified
}

/** The pane's outline in a box [width] x [height]: top corners of [radius], square at the foot. */
private fun panePath(width: Float, height: Float, radius: Float, open: Boolean): Path = Path().apply {
    val r = radius.coerceAtMost(width / 2f).coerceAtMost(height)
    if (open) {
        moveTo(0f, height)
        lineTo(0f, r)
        arcTo(Rect(0f, 0f, 2f * r, 2f * r), 180f, 90f, false)
        lineTo(width - r, 0f)
        arcTo(Rect(width - 2f * r, 0f, width, 2f * r), 270f, 90f, false)
        lineTo(width, height)
    } else {
        addRoundRect(
            RoundRect(
                left = 0f,
                top = 0f,
                right = width,
                bottom = height,
                topLeftCornerRadius = CornerRadius(r),
                topRightCornerRadius = CornerRadius(r),
                bottomRightCornerRadius = CornerRadius.Zero,
                bottomLeftCornerRadius = CornerRadius.Zero,
            ),
        )
    }
}

private val titleStyle = TextStyle(color = Color.White, fontFamily = YtFonts.jakarta, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
private val channelStyle = TextStyle(color = YtFrostStyle.secondary, fontFamily = YtFonts.jakarta, fontWeight = FontWeight.Medium, fontSize = 11.sp)
private val labelStyle = TextStyle(color = Color.White, fontFamily = YtFonts.jakarta, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)

/** The Frost player's body and everything beside its picture. */
@Composable
internal fun FrostUnder(scene: YtMiniScene, accent: Color) {
    val density = LocalDensity.current.density
    val motion = LocalYtMotion.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val backdrop = scene.backdrop
    val rest = remember { FrostRest() }
    val drop = remember { FrostDrop() }

    // what changes rarely (not the position, which the poll renews every time)
    val text by remember { derivedStateOf { scene.info.let { Triple(it.title, it.channel, it.playing) } } }
    val (title, channel, playing) = text
    val next = scene.next
    val grown = scene.peekLayout
    val target = scene.target

    // the drop: the card freezes into the pane, the label shows on frost
    val merge = remember { Animatable(0f) }
    val label = remember { Animatable(0f) }
    val accepted = YtDock.accepted
    var seenDrop by remember { mutableIntStateOf(accepted) }
    LaunchedEffect(accepted) {
        if (accepted == seenDrop) return@LaunchedEffect
        seenDrop = accepted
        val p = drop.pointer
        drop.landing = if (p.isSpecified) p - drop.origin else Offset.Unspecified
        drop.pointer = Offset.Unspecified
        if (motion) {
            launch {
                merge.snapTo(0.001f)
                merge.animateTo(1f, tween(900, easing = LinearEasing))
                merge.snapTo(0f)
            }
            delay(380)
            label.animateTo(1f, tween(220, easing = LinearEasing))
            delay(1400)
            label.animateTo(0f, tween(260, easing = LinearEasing))
        } else {
            label.snapTo(1f)
            delay(1400)
            label.snapTo(0f)
        }
    }

    // a refused drop: the rim flares once
    val flare = remember { Animatable(0f) }
    val rejects = scene.rejects
    var seenReject by remember { mutableIntStateOf(rejects) }
    LaunchedEffect(rejects) {
        if (rejects == seenReject) return@LaunchedEffect
        seenReject = rejects
        if (!motion) return@LaunchedEffect
        flare.snapTo(1f)
        flare.animateTo(0f, tween(420, easing = LinearEasing))
    }

    val dropText = YtDock.lastDrop?.let {
        if (it.next) stringResource(R.string.yt_mini_drop_next) else stringResource(R.string.yt_mini_drop_end, it.position)
    }.orEmpty()

    Box(
        Modifier
            .fillMaxSize()
            // the title view of the page speaks for the player (with its actions)
            .clearAndSetSemantics {}
            .onGloballyPositioned { drop.origin = it.positionInWindow() },
    ) {
        // 1: the pane, the Frost bar's dark glass grown upward
        val shape = RoundedCornerShape(topStart = YtFrostLayout.RADIUS.dp, topEnd = YtFrostLayout.RADIUS.dp)
        Box(
            Modifier
                .fillMaxSize()
                .then(
                    if (backdrop != null) {
                        Modifier.liquidGlass(backdrop, shape, YtFrostStyle.pane, YtFrostStyle.shadow, blur = 12.dp, refraction = 10.dp)
                    } else {
                        Modifier.clip(shape).background(YtFrostStyle.paneSolid)
                    },
                ),
        )

        // 2: grain, the glow inside the rim, the seam of progress; a dragged card lights the rim,
        // one dropped freezes into the pane, a refused one makes it flare
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            if (w <= 0f || h <= 0f) return@Canvas
            val hover = scene.hover()
            if (hover > 0f) YtDrag.session?.let { drop.pointer = it.pointer }
            val m = merge.value
            val radius = YtFrostLayout.RADIUS.dp.toPx()
            val closed = panePath(w, h, radius, open = false)
            val open = panePath(w, h, radius, open = true)
            // the rim glows up as a card is taken in
            val taken = if (m > 0f) sin(PI.toFloat() * YtFrostMath.smoothstep(0.45f, 1f, m)) else 0f
            val glow = 0.07f + 0.12f * hover + 0.2f * taken + 0.25f * flare.value

            drawPath(closed, YtFrostGrain.brush, alpha = 0.45f)
            clipPath(closed) {
                drawPath(open, Color.White.copy(alpha = glow.coerceAtMost(1f)), style = Stroke(10.dp.toPx()))
                drawPath(open, Color.White.copy(alpha = (glow + 0.04f).coerceAtMost(1f)), style = Stroke(4.dp.toPx()))
            }
            drawPath(
                open,
                Brush.verticalGradient(
                    listOf(Color.White.copy(alpha = (0.42f + 0.4f * hover).coerceAtMost(1f)), Color.White.copy(alpha = 0.04f)),
                    startY = 0f,
                    endY = h,
                ),
                style = Stroke(1.dp.toPx()),
            )

            // the seam at the pane's foot, where it meets the bar
            val foot = h - YtFrostLayout.OVERLAP.dp.toPx()
            val line = 2.dp.toPx()
            drawRect(YtFrostStyle.track, Offset(0f, foot - line), Size(w, line))
            val played = w * scene.info.fraction.coerceIn(0f, 1f)
            drawRect(accent, Offset(if (rtl) w - played else 0f, foot - line), Size(played, line))

            // the dropped card: from where it landed into the pane, frosting over as it goes,
            // then it flattens into the glass
            if (m > 0f) {
                val g = rest.of(w, density)
                val room = YtMiniLayout.directed(g.text, w, rtl)
                val landing = drop.landing
                val from = if (landing.isSpecified) {
                    Offset(landing.x.coerceIn(0f, w), landing.y.coerceIn(0f, h))
                } else {
                    Offset(w / 2f, -24.dp.toPx())
                }
                val to = Offset(room.centerX, room.centerY)
                val fly = YtFrostMath.smoothstep(0f, 0.5f, m)
                val flat = YtFrostMath.smoothstep(0.45f, 0.8f, m)
                val center = Offset(YtFrostMath.lerp(from.x, to.x, fly), YtFrostMath.lerp(from.y, to.y, fly))
                val cw = YtFrostMath.lerp(116.dp.toPx(), room.width.coerceAtLeast(40.dp.toPx()), fly)
                val ch = YtFrostMath.lerp(66.dp.toPx(), 34.dp.toPx(), fly) * (1f - 0.8f * flat)
                val alpha = 1f - YtFrostMath.smoothstep(0.6f, 0.85f, m)
                val rect = Rect(center.x - cw / 2f, center.y - ch / 2f, center.x + cw / 2f, center.y + ch / 2f)
                val corner = CornerRadius(YtFrostMath.lerp(18.dp.toPx(), 10.dp.toPx(), fly).coerceAtMost(ch / 2f))
                val card = Path().apply { addRoundRect(RoundRect(rect, corner)) }
                clipPath(card) {
                    drawRect(Color.White.copy(alpha = (0.14f + 0.26f * fly) * alpha), rect.topLeft, rect.size)
                    drawRect(YtFrostGrain.brush, rect.topLeft, rect.size, alpha = alpha)
                }
                drawPath(card, Color.White.copy(alpha = 0.6f * alpha), style = Stroke(1.dp.toPx()))
            }
        }

        // 3: the docked row: title and channel, the white disc behind play; it gives way to the
        // preview as that grows, and to the drop's label
        Layout(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = YtMiniParts.restFade(scene.peek()) },
            content = {
                Column(Modifier.layoutId("text").graphicsLayer { alpha = 1f - label.value }) {
                    Text(title, style = titleStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (channel.isNotBlank()) {
                        Spacer(Modifier.height(1.dp))
                        Text(channel, style = channelStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Box(
                    Modifier
                        .layoutId("disc")
                        .clip(CircleShape)
                        .background(Color.White),
                )
                if (dropText.isNotEmpty()) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .layoutId("label")
                            .graphicsLayer {
                                val v = label.value
                                alpha = v
                                scaleX = 0.92f + 0.08f * v
                                scaleY = 0.92f + 0.08f * v
                            },
                    ) {
                        Text(
                            dropText,
                            style = labelStyle,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(YtFrostStyle.tile)
                                .border(1.dp, YtFrostStyle.tileBorder, RoundedCornerShape(50))
                                .drawBehind { drawRect(YtFrostGrain.brush, alpha = 0.7f) }
                                .padding(horizontal = 12.dp, vertical = 5.dp),
                        )
                    }
                }
            },
        ) { measurables, constraints ->
            val w = constraints.maxWidth
            val h = constraints.maxHeight
            val g = rest.of(w.toFloat(), density)
            // the row keeps to the top of the box as the preview grows it
            fun at(box: YtMiniBox) = YtMiniLayout.directed(box, w.toFloat(), rtl)
            val disc = (36 * density).roundToInt()
            val placed = measurables.mapNotNull { measurable ->
                when (measurable.layoutId) {
                    "text" -> {
                        val box = at(g.text)
                        val p = measurable.measure(Constraints(maxWidth = box.width.roundToInt().coerceAtLeast(0), maxHeight = box.height.roundToInt().coerceAtLeast(0)))
                        Triple(p, box.left.roundToInt(), (box.centerY - p.height / 2f).roundToInt())
                    }
                    "disc" -> g.play?.let { play ->
                        val box = at(play)
                        Triple(measurable.measure(Constraints.fixed(disc, disc)), (box.centerX - disc / 2f).roundToInt(), (box.centerY - disc / 2f).roundToInt())
                    }
                    "label" -> {
                        val box = at(g.text)
                        val p = measurable.measure(Constraints(maxWidth = box.width.roundToInt().coerceAtLeast(0), maxHeight = box.height.roundToInt().coerceAtLeast(0)))
                        Triple(p, (box.centerX - p.width / 2f).roundToInt(), (box.centerY - p.height / 2f).roundToInt())
                    }
                    else -> null
                }
            }
            layout(w, h) { placed.forEach { (p, x, y) -> p.place(x, y) } }
        }

        // 4: the preview: the title row, the frost pill and the frozen card of the next video,
        // placed from the box's foot so they rise with it
        if (grown != null) {
            Layout(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val shown = YtMiniParts.peekFade(scene.peek())
                        alpha = shown
                        translationY = 10f * density * (1f - shown)
                    },
                content = {
                    Column(Modifier.layoutId("title")) {
                        Text(
                            title,
                            style = titleStyle.copy(fontSize = 14.sp),
                            maxLines = if (grown.next == null) 2 else 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (channel.isNotBlank()) {
                            Text(channel, style = channelStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    FrostPill(playing, target == YtMiniTarget.PILL, Modifier.layoutId("pill"))
                    if (next != null && grown.next != null) {
                        FrostCard(next, target == YtMiniTarget.NEXT, scene, Modifier.layoutId("card"))
                    }
                },
            ) { measurables, constraints ->
                val w = constraints.maxWidth
                val h = constraints.maxHeight
                val placed = measurables.mapNotNull { measurable ->
                    val box = when (measurable.layoutId) {
                        "title" -> grown.title
                        "pill" -> grown.pill
                        "card" -> grown.next
                        else -> null
                    } ?: return@mapNotNull null
                    val at = YtMiniLayout.directed(box, w.toFloat(), rtl)
                    val width = box.width.roundToInt().coerceAtLeast(0)
                    val height = box.height.roundToInt().coerceAtLeast(0)
                    val p = if (measurable.layoutId == "title") {
                        measurable.measure(Constraints(maxWidth = width, maxHeight = height))
                    } else {
                        measurable.measure(Constraints.fixed(width, height))
                    }
                    // from the foot: the box's foot stays on the bar while it grows
                    val top = h - (grown.boxHeight - box.top)
                    val y = if (measurable.layoutId == "title") top + (height - p.height) / 2f else top
                    Triple(p, at.left.roundToInt(), y.roundToInt())
                }
                layout(w, h) { placed.forEach { (p, x, y) -> p.place(x, y) } }
            }
        }
    }
}

/** Play or pause as a frost tile; white (as the bar's active tab) while the finger is on it. */
@Composable
private fun FrostPill(playing: Boolean, active: Boolean, modifier: Modifier) {
    val shape = RoundedCornerShape(50)
    val content = if (active) YtFrostStyle.ink else Color.White
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .graphicsLayer {
                val s = if (active) 1.06f else 1f
                scaleX = s
                scaleY = s
            }
            .clip(shape)
            .background(if (active) Color.White else YtFrostStyle.tile)
            .border(1.dp, if (active) Color.Transparent else YtFrostStyle.tileBorder, shape)
            .drawBehind { if (!active) drawRect(YtFrostGrain.brush, alpha = 0.7f) },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 12.dp)) {
            YtMiniParts.Glyph(playing, content, Modifier.size(11.dp))
            Spacer(Modifier.width(7.dp))
            Text(
                text = stringResource(if (playing) R.string.yt_mini_peek_playing else R.string.yt_mini_peek_paused),
                style = labelStyle.copy(color = content),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * The next video as a frozen frost card, the material of the Frost home's cards: its picture lies
 * under ice and thaws while the finger is on the card (slowly, and freezes back slower still).
 */
@Composable
private fun FrostCard(next: YtMiniNext, active: Boolean, scene: YtMiniScene, modifier: Modifier) {
    val motion = LocalYtMotion.current
    val warm = active
    val thaw by animateFloatAsState(
        targetValue = if (warm) 1f else 0f,
        animationSpec = when {
            !motion -> snap<Float>()
            warm -> tween<Float>(650, easing = YtEaseOut)
            else -> tween<Float>(1100, easing = YtEaseInOut)
        },
        label = "frostCardThaw",
    )
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier = modifier
            .graphicsLayer {
                val s = 1f + 0.03f * thaw
                scaleX = s
                scaleY = s
            }
            .frostGlass(scene.backdrop, shape, clear = { thaw })
            .border(1.dp, Color.White.copy(alpha = 0.22f + 0.3f * thaw), shape)
            .padding(6.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(10.dp)),
        ) {
            AsyncImage(
                model = next.thumbnail,
                contentDescription = null,
                imageLoader = ImageHelper.loader(),
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .matchParentSize()
                    .background(Color(0x33FFFFFF)),
            )
            // the ice over the picture: white, grain and a rim of crystals, melting away
            Box(
                Modifier
                    .matchParentSize()
                    .graphicsLayer { alpha = 1f - thaw }
                    .drawBehind {
                        drawRect(Color.White.copy(alpha = 0.42f))
                        drawRect(YtFrostGrain.brush)
                        drawRoundRect(
                            Color.White.copy(alpha = 0.7f),
                            cornerRadius = CornerRadius(10.dp.toPx()),
                            style = Stroke(1.5f.dp.toPx()),
                        )
                    },
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.yt_mini_peek_next).uppercase(),
            style = labelStyle.copy(color = YtFrostStyle.secondary, fontSize = 9.5.sp, letterSpacing = 0.1.em),
            maxLines = 1,
        )
        Text(
            text = next.title,
            style = labelStyle.copy(fontSize = 11.5.sp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
