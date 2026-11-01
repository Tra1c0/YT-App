package com.github.libretube.ui.yt.mini

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.github.libretube.R
import com.github.libretube.api.obj.StreamItem
import com.github.libretube.helpers.ImageHelper
import com.github.libretube.ui.yt.LocalYtMotion
import com.github.libretube.ui.yt.YtDock
import com.github.libretube.ui.yt.YtDrag
import com.github.libretube.ui.yt.YtFonts
import com.github.libretube.ui.yt.YtPalette
import kotlinx.coroutines.delay
import kotlin.math.floor
import kotlin.math.roundToInt

/*
 * YT: what the After player draws behind its picture, inside the box: the bordeaux body with the
 * sprocket row (the progress) and the black gate around the picture, title and channel, the
 * preview's title, ticket and credits line, and a dropped card's film frame with its label.
 * Moving values (minimize progress, preview, hover, position) are read while drawing or laying
 * out only.
 */

/** The phases of a dropped card's film frame. */
private object DropPhase {
    const val IDLE = 0

    /** Accepted: pulled into the gate, then the label. */
    const val PULLING = 1

    /** Refused: thrown back out to the right. */
    const val THROWING = 2
}

/** The drop's film frame and label; [thumbnail] is the dragged card's, kept after the drag ends. */
@Stable
private class AfterDrop {
    val pull = Animatable(0f)
    val out = Animatable(0f)
    val label = Animatable(0f)
    var phase by mutableIntStateOf(DropPhase.IDLE)
    var thumbnail by mutableStateOf<String?>(null)

    /** How much the text of the docked row gives way (to the frame passing, to the label). */
    fun covering(): Float = if (phase == DropPhase.PULLING) 1f else label.value
}

/** The holes of the sprocket row, punched through to the dark. */
private val Hole = Color(0xFF0E0407)

@Composable
internal fun AfterUnder(scene: YtMiniScene, palette: YtPalette, look: YtMiniLook) {
    val density = LocalDensity.current.density
    val motion = LocalYtMotion.current
    val mirror = LocalLayoutDirection.current == LayoutDirection.Rtl
    val spec = YtMiniAfter.spec
    // the box's height when docked, as the constraints give it (whole px)
    val restBox = ((spec.height + spec.overlap) * density).roundToInt().toFloat()

    // the landing threads the film hole by hole and the title rises (1 = at rest)
    val settle = remember { Animatable(1f) }
    val landings = scene.landings
    LaunchedEffect(landings) {
        if (landings > 0 && motion) {
            settle.snapTo(0f)
            settle.animateTo(1f, tween(620, easing = LinearOutSlowInEasing))
        }
    }
    val settled = { settle.value }

    val drop = remember { AfterDrop() }
    DropEffects(scene, drop, motion)

    Box(Modifier.fillMaxSize().clearAndSetSemantics {}) {
        Body(scene, look, palette.accent, settled, mirror)
        RestRow(scene, look, palette, settled, drop, restBox)
        Preview(scene, palette, look)
    }
}

/**
 * The body joined to the bar, its rim lit tangerine as a dragged card comes near, the black gate
 * around the picture (following it into the preview) and the sprocket row along the top edge:
 * the holes fill tangerine as the video plays, the lamp glows at the one it has reached.
 */
@Composable
private fun Body(scene: YtMiniScene, look: YtMiniLook, accent: Color, settle: () -> Float, mirror: Boolean) {
    val density = LocalDensity.current.density
    Box(
        Modifier
            .fillMaxSize()
            // right to left: the picture and the progress swap sides with the views
            .graphicsLayer { if (mirror) scaleX = -1f }
            .drawWithCache {
                val w = size.width
                val h = size.height
                val body = YtMiniAfter.bodyPath(look, w, h, density, open = false, grow = 0f)
                val rim = YtMiniAfter.bodyPath(look, w, h, density, open = true, grow = 0f)
                val holes = YtAfterLayout.sprockets(w, look.radius * density, density)
                val rest = YtMiniAfter.geometry(w, density)
                val holeSize = Size(holes.width, holes.height)
                val holeCorner = CornerRadius(0.8f * density)
                val gate = YtAfterLayout.GATE * density
                val gateCorner = CornerRadius((look.spec.thumbRadius + YtAfterLayout.GATE) * density)
                val rimStroke = Stroke(look.borderWidth * density)
                val hoverStroke = Stroke(1.5f * density)
                val glow = 4f * density
                onDrawBehind {
                    drawPath(body, look.fill)
                    look.border?.let { drawPath(rim, it, style = rimStroke) }
                    val peek = YtDock.peek.coerceIn(0f, 1f)
                    val near = if (peek <= 0f) scene.hover() else 0f
                    if (near > 0f) drawPath(rim, accent, alpha = 0.9f * near, style = hoverStroke)

                    // the gate: the picture's constraints run linearly with the preview's
                    // progress, so the frame follows it from the docked box to the grown one.
                    // Only once the picture has arrived: while minimizing it is elsewhere
                    val arrived = YtAfterMath.smoothstep(0.92f, 1f, YtDock.progress)
                    if (arrived > 0f) {
                        val grown = scene.peekLayout
                        val picture = if (grown != null && peek > 0f) lerpBox(rest.player, grown.player, peek) else rest.player
                        drawRoundRect(
                            YtAfterColors.gate,
                            topLeft = Offset(picture.left - gate, picture.top - gate),
                            size = Size(picture.width + 2f * gate, picture.height + 2f * gate),
                            cornerRadius = gateCorner,
                            alpha = arrived,
                        )
                    }

                    val fraction = scene.info.fraction
                    val s = settle()
                    for (i in 0 until holes.count) {
                        val shown = YtAfterMath.thread(i, holes.count, s)
                        if (shown <= 0f) continue
                        val at = Offset(holes.left(i), holes.top)
                        drawRoundRect(Hole, at, holeSize, holeCorner, alpha = shown)
                        val fill = YtAfterMath.holeFill(i, holes.count, fraction)
                        if (fill > 0f) {
                            drawRoundRect(accent, at, Size(holes.width * fill, holes.height), holeCorner, alpha = shown)
                        } else if (near > 0f) {
                            // the reel is ready to take the film
                            drawRoundRect(accent, at, holeSize, holeCorner, alpha = 0.3f * near * shown)
                        }
                    }
                    if (holes.count > 0 && fraction > 0f && fraction < 1f && s >= 1f) {
                        val k = floor(fraction * holes.count).toInt().coerceIn(0, holes.count - 1)
                        drawCircle(accent, glow, Offset(holes.left(k) + holes.width / 2f, holes.top + holes.height / 2f), alpha = 0.22f)
                    }
                }
            },
    )
}

private fun lerpBox(a: YtMiniBox, b: YtMiniBox, t: Float) = YtMiniBox(
    YtAfterMath.lerp(a.left, b.left, t),
    YtAfterMath.lerp(a.top, b.top, t),
    YtAfterMath.lerp(a.right, b.right, t),
    YtAfterMath.lerp(a.bottom, b.bottom, t),
)

/**
 * The docked row: title in Anton caps and the channel letter-spaced beside the picture; over
 * them the drop's label and the dropped card's film frame. It gives way as the preview grows.
 */
@Composable
private fun BoxScope.RestRow(scene: YtMiniScene, look: YtMiniLook, palette: YtPalette, settle: () -> Float, drop: AfterDrop, restBox: Float) {
    val density = LocalDensity.current.density
    val text by remember { derivedStateOf { scene.info.title.uppercase() to scene.info.channel.uppercase() } }
    val (title, channel) = text
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .requiredHeight((restBox / density).dp)
            .align(Alignment.TopStart)
            .graphicsLayer { alpha = YtMiniParts.restFade(scene.peek()) },
    ) {
        val w = constraints.maxWidth.toFloat()
        val g = remember(w, density) { YtMiniAfter.geometry(w, density) }
        val textWidth = ((g.text.right - g.text.left).coerceAtLeast(0f) / density).dp
        val rowHeight = YtMiniAfter.spec.height.dp

        Box(
            Modifier
                .offset { IntOffset(g.text.left.roundToInt(), 0) }
                .width(textWidth)
                .height(rowHeight)
                .graphicsLayer {
                    val s = settle()
                    val near = if (scene.peek() <= 0f) scene.hover() else 0f
                    alpha = s * (1f - 0.6f * near) * (1f - drop.covering())
                    translationY = 6f * density * (1f - s)
                },
        ) {
            Column(Modifier.align(Alignment.CenterStart)) {
                Text(text = title, style = look.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (channel.isNotBlank()) {
                    Spacer(Modifier.height(1.dp))
                    Text(text = channel, style = look.subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }

        val last = YtDock.lastDrop
        if (last != null) {
            val label = if (last.next) {
                stringResource(R.string.yt_mini_after_drop_next)
            } else {
                stringResource(R.string.yt_mini_after_drop_end, last.position)
            }
            Box(
                Modifier
                    .offset { IntOffset(g.text.left.roundToInt(), 0) }
                    .width(textWidth)
                    .height(rowHeight)
                    .graphicsLayer {
                        val v = drop.label.value
                        alpha = v
                        translationY = 6f * density * (1f - v)
                    },
            ) {
                Text(
                    text = label,
                    style = TextStyle(color = palette.accent, fontFamily = YtFonts.anton, fontSize = 14.sp, letterSpacing = 0.06.em),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.align(Alignment.CenterStart),
                )
            }
        }

        DropFrame(scene, drop, g)
    }
}

/**
 * A dragged card as a film frame (its thumbnail between two perforated bands): it comes in from
 * the end of the text as the card nears, is pulled into the gate in three jerks when the player
 * takes it (and vanishes behind the picture), or is thrown back out when it does not.
 */
@Composable
private fun DropFrame(scene: YtMiniScene, drop: AfterDrop, g: YtMiniGeometry) {
    val density = LocalDensity.current.density
    val frame = YtAfterLayout.FRAME_WIDTH * density
    val outside = g.text.right
    val inside = g.text.right - frame - 4f * density
    val gateAt = g.player.left + (g.player.width - frame) / 2f
    fun x(): Float = when (drop.phase) {
        DropPhase.PULLING -> YtAfterMath.lerp(inside, gateAt, YtAfterMath.intermittent(drop.pull.value, YtAfterMath.PULLS))
        DropPhase.THROWING -> drop.out.value.let { o -> YtAfterMath.lerp(inside, outside, o * o) }
        else -> {
            val near = if (scene.peek() <= 0f) scene.hover() else 0f
            YtAfterMath.lerp(outside, inside, 1f - (1f - near) * (1f - near))
        }
    }
    fun shown(): Float = when (drop.phase) {
        // behind the picture at the end: gone
        DropPhase.PULLING -> if (drop.pull.value < 0.97f) 1f else 0f
        DropPhase.THROWING -> 1f - drop.out.value
        else -> if (scene.peek() <= 0f && scene.hover() > 0f) 1f else 0f
    }
    // the frame moves from the picture to the end of the text and is cut off there
    Box(
        Modifier
            .width((outside / density).coerceAtLeast(0f).dp)
            .fillMaxHeight()
            .clipToBounds(),
    ) {
        Box(
            Modifier
                .offset {
                    val judder = if (drop.phase == DropPhase.THROWING) YtAfterMath.judder(drop.out.value) * 2f * density else 0f
                    IntOffset(x().roundToInt(), (g.player.top + judder).roundToInt())
                }
                .size(YtAfterLayout.FRAME_WIDTH.dp, (g.player.height / density).dp)
                .graphicsLayer { alpha = shown() }
                .drawBehind { filmFrame(YtAfterLayout.FRAME_BAND.dp.toPx()) }
                .padding(horizontal = 2.dp, vertical = YtAfterLayout.FRAME_BAND.dp),
        ) {
            AsyncImage(
                model = drop.thumbnail,
                contentDescription = null,
                imageLoader = ImageHelper.loader(),
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** A piece of film: black, with light perforations in a band of [band] px above and below. */
private fun DrawScope.filmFrame(band: Float) {
    drawRect(YtAfterColors.gate)
    val holeHeight = band * 0.42f
    val holeWidth = holeHeight * 1.4f
    val pitch = holeWidth * 2.2f
    val count = ((size.width - holeWidth) / pitch).toInt() + 1
    val first = (size.width - ((count - 1) * pitch + holeWidth)) / 2f
    val corner = CornerRadius(holeHeight / 3f)
    for (row in 0..1) {
        val y = if (row == 0) (band - holeHeight) / 2f else size.height - band + (band - holeHeight) / 2f
        for (i in 0 until count) {
            drawRoundRect(YtAfterColors.bone, Offset(first + i * pitch, y), Size(holeWidth, holeHeight), corner, alpha = 0.55f)
        }
    }
}

/** The thumbnail of the dragged card, the accept (pull, then the label) and the reject (throw out). */
@Composable
private fun DropEffects(scene: YtMiniScene, drop: AfterDrop, motion: Boolean) {
    LaunchedEffect(drop) {
        snapshotFlow { (YtDrag.session?.payload as? StreamItem)?.thumbnail }
            .collect { if (it != null) drop.thumbnail = it }
    }

    val accepted = YtDock.accepted
    var seenAccept by remember { mutableIntStateOf(accepted) }
    LaunchedEffect(accepted) {
        if (accepted == seenAccept) return@LaunchedEffect
        seenAccept = accepted
        drop.out.snapTo(0f)
        if (motion) {
            drop.phase = DropPhase.PULLING
            drop.pull.snapTo(0f)
            drop.pull.animateTo(1f, tween(YtAfterMath.PULL_MS, easing = LinearEasing))
            // the text stays covered until the label has come up
            drop.label.animateTo(1f, tween(180, easing = LinearEasing))
            drop.phase = DropPhase.IDLE
            drop.pull.snapTo(0f)
            delay(1400)
            drop.label.animateTo(0f, tween(220, easing = LinearEasing))
        } else {
            drop.label.snapTo(1f)
            delay(1400)
            drop.label.snapTo(0f)
        }
    }

    val rejects = scene.rejects
    var seenReject by remember { mutableIntStateOf(rejects) }
    LaunchedEffect(rejects) {
        if (rejects == seenReject) return@LaunchedEffect
        seenReject = rejects
        if (!motion) return@LaunchedEffect
        drop.phase = DropPhase.THROWING
        drop.out.snapTo(0f)
        drop.out.animateTo(1f, tween(300, easing = LinearEasing))
        drop.phase = DropPhase.IDLE
        drop.out.snapTo(0f)
    }
}

/**
 * The preview's parts, placed from the bottom of the box (which grows upward from the bar): the
 * title in Anton caps and the ticket beside the opened frame, the credits line under it.
 */
@Composable
private fun Preview(scene: YtMiniScene, palette: YtPalette, look: YtMiniLook) {
    val grown = scene.peekLayout ?: return
    val next = scene.next
    val target = scene.target
    val title by remember { derivedStateOf { scene.info.title.uppercase() } }
    val playing by remember { derivedStateOf { scene.info.playing } }
    Layout(
        modifier = Modifier.fillMaxSize(),
        content = {
            Box(
                Modifier
                    .layoutId("title")
                    .graphicsLayer {
                        val s = YtAfterMath.partsShown(scene.peek())
                        alpha = s
                        translationY = 8.dp.toPx() * (1f - s)
                    },
            ) {
                Text(
                    text = title,
                    style = TextStyle(color = palette.text, fontFamily = YtFonts.anton, fontSize = 17.sp, lineHeight = 19.sp, letterSpacing = 0.02.em),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Ticket(scene, playing, target == YtMiniTarget.PILL, palette, look.fill, Modifier.layoutId("pill"))
            if (next != null) {
                Credits(scene, next, target == YtMiniTarget.NEXT, palette, Modifier.layoutId("next"))
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
            // from the bottom: the box's foot stays on the bar while it grows
            placed.forEach { (placeable, box) ->
                placeable.placeRelative(box.left.roundToInt(), (h - (grown.boxHeight - box.top)).roundToInt())
            }
        }
    }
}

/**
 * Play and pause as a cinema ticket: bone paper (tangerine under the finger) with notches on its
 * ends and a perforated stub holding the glyph, "LÄUFT JETZT" or "PAUSE" in Anton.
 */
@Composable
private fun Ticket(scene: YtMiniScene, playing: Boolean, active: Boolean, palette: YtPalette, body: Color, modifier: Modifier) {
    val ink = palette.ink
    val paper = if (active) palette.accent else palette.text
    val label = stringResource(if (playing) R.string.yt_mini_after_now_showing else R.string.yt_mini_after_intermission)
    Box(
        modifier = modifier
            .graphicsLayer {
                val s = YtAfterMath.partsShown(scene.peek())
                alpha = s
                translationY = 8.dp.toPx() * (1f - s)
                val k = if (active) 1.05f else 1f
                scaleX = k
                scaleY = k
            }
            .drawBehind {
                val inset = 4.dp.toPx()
                val bottom = size.height - inset
                drawRoundRect(paper, Offset(0f, inset), Size(size.width, bottom - inset), CornerRadius(3.dp.toPx()))
                // the notches, in the body's colour
                val notch = 5.dp.toPx()
                drawCircle(body, notch, Offset(0f, size.height / 2f))
                drawCircle(body, notch, Offset(size.width, size.height / 2f))
                // the stub's perforation
                val stub = STUB.dp.toPx()
                val dot = 0.9f.dp.toPx()
                val step = 4.dp.toPx()
                var y = inset + step
                while (y < bottom - step / 2f) {
                    drawCircle(ink, dot, Offset(stub, y), alpha = 0.35f)
                    y += step
                }
            },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxSize()) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.width(STUB.dp).fillMaxHeight()) {
                Canvas(Modifier.size(11.dp)) { afterGlyph(playing, center, size.minDimension / 2f, ink, 1f) }
            }
            Text(
                text = label,
                style = TextStyle(color = ink, fontFamily = YtFonts.anton, fontSize = 13.sp, letterSpacing = 0.08.em),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 8.dp, end = 10.dp),
            )
        }
    }
}

/** The ticket's stub, in dp. */
private const val STUB = 30

/**
 * The next video as a credits line under the frame: its thumbnail as a film frame, then
 * "NÄCHSTE VORSTELLUNG" in tangerine and its title in condensed caps. It slides up as the gate
 * opens and lights up under the finger.
 */
@Composable
private fun Credits(scene: YtMiniScene, next: YtMiniNext, active: Boolean, palette: YtPalette, modifier: Modifier) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .graphicsLayer {
                val s = YtAfterMath.creditsShown(scene.peek())
                alpha = s
                translationY = 14.dp.toPx() * (1f - s)
                val k = if (active) 1.02f else 1f
                scaleX = k
                scaleY = k
            }
            .drawBehind {
                // a hairline above: the credits under the screen
                drawRect(palette.text, size = Size(size.width, 1.dp.toPx()), alpha = 0.1f)
                if (active) {
                    drawRect(palette.accent, alpha = 0.16f)
                    drawRect(palette.accent, size = Size(2.dp.toPx(), size.height))
                }
            }
            .padding(horizontal = 6.dp, vertical = 3.dp),
    ) {
        Box(
            Modifier
                .size(52.dp, 30.dp)
                .drawBehind { filmFrame(5.dp.toPx()) }
                .padding(horizontal = 2.dp, vertical = 5.dp),
        ) {
            AsyncImage(
                model = next.thumbnail,
                contentDescription = null,
                imageLoader = ImageHelper.loader(),
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Spacer(Modifier.width(10.dp))
        Column {
            Text(
                text = stringResource(R.string.yt_mini_after_next_showing),
                style = TextStyle(color = palette.accent, fontFamily = YtFonts.geist, fontWeight = FontWeight.SemiBold, fontSize = 9.sp, letterSpacing = 0.22.em),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = next.title.uppercase(),
                style = TextStyle(color = palette.text, fontFamily = YtFonts.archivoNarrow, fontWeight = FontWeight.Bold, fontSize = 13.sp, letterSpacing = 0.04.em),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
