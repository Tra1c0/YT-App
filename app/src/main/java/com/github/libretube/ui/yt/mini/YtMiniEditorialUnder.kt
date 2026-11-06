package com.github.libretube.ui.yt.mini

import android.content.res.Resources
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
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
import com.github.libretube.ui.yt.YtDropResult
import com.github.libretube.ui.yt.YtFonts
import com.github.libretube.ui.yt.YtPalette
import com.github.libretube.ui.yt.YtPlaybackClock
import com.github.libretube.ui.yt.ytGrayscale
import kotlinx.coroutines.delay
import kotlin.math.floor
import kotlin.math.roundToInt

/*
 * YT: what the Editorial player draws behind its picture, inside the box: the white slip with
 * its black rules, the picture's frame, the docked row (kicker, title), the seam of progress,
 * the preview's printed block and the drop's caret and label. Moving values (minimize progress,
 * preview, hover, the typing) are read while drawing or laying out only; the label's text
 * changes in steps (a derived state), not every frame.
 */

/** The drop's label (typed, then counted) and the strike-out of a refused one. */
@Stable
private class EditorialDrop {
    /** ms into the label's typing and counting. */
    val ms = Animatable(0f)

    /** The black slip of the label: 1 shown, wiped off to 0. */
    val slip = Animatable(0f)

    /** A refused drop strikes the row out: 1 at the strike, running down to 0. */
    val struck = Animatable(0f)
}

/** Shown at once when motion is off: typed and counted through. */
private const val LABEL_DONE = 1_000_000f

@Composable
internal fun EditorialUnder(scene: YtMiniScene, palette: YtPalette, look: YtMiniLook) {
    val density = LocalDensity.current.density
    val motion = LocalYtMotion.current
    val mirror = LocalLayoutDirection.current == LayoutDirection.Rtl
    val spec = YtMiniEditorial.spec
    // the box's height when docked, as the constraints give it (whole px)
    val restBox = ((spec.height + spec.overlap) * density).roundToInt().toFloat()

    // the landing: the rule draws through from the start edge (1 = at rest)
    val settle = remember { Animatable(1f) }
    val landings = scene.landings
    LaunchedEffect(landings) {
        if (landings > 0 && motion) {
            settle.snapTo(0f)
            settle.animateTo(1f, tween(360, easing = LinearEasing))
        }
    }
    val settled = { settle.value }

    // the kicker, docked and in the preview; the press's haptic types as many strokes as it has
    // letters (at most eight)
    val now = stringResource(R.string.yt_mini_now).uppercase()
    val channel by remember { derivedStateOf { scene.info.channel.uppercase() } }
    val kicker = if (channel.isBlank()) now else "$now — $channel"
    SideEffect { YtMiniEditorial.kickerLetters = YtEditorialMath.typeSteps(kicker.length) }

    val drop = remember { EditorialDrop() }
    DropEffects(scene, drop, motion)

    Box(Modifier.fillMaxSize().clearAndSetSemantics {}) {
        Body(scene, look, palette, settled, mirror)
        RestRow(scene, look, palette, kicker, drop, restBox, motion, mirror)
        Preview(scene, palette, kicker, motion)
    }
}

/**
 * Prints what it draws from the start edge: only the part up to [reveal] (0 to 1) shows, with a
 * thin print head ([head], null for none) at the edge while it runs. Read while drawing.
 */
private fun Modifier.printed(reveal: () -> Float, head: Color?): Modifier = drawWithContent {
    val r = reveal().coerceIn(0f, 1f)
    if (r <= 0f) return@drawWithContent
    if (r >= 1f) {
        drawContent()
        return@drawWithContent
    }
    val rtl = layoutDirection == LayoutDirection.Rtl
    val edge = if (rtl) size.width * (1f - r) else size.width * r
    if (rtl) {
        clipRect(left = edge) { this@drawWithContent.drawContent() }
    } else {
        clipRect(right = edge) { this@drawWithContent.drawContent() }
    }
    if (head != null) {
        val w = 1.5f.dp.toPx()
        drawRect(head, Offset(if (rtl) edge else edge - w, 0f), Size(w, size.height))
    }
}

/**
 * The white slip joined to the bar: hairline rules at its sides and top (the top thickening into
 * the block's masthead in the preview, blue as a dragged card comes near), the picture's black
 * frame (following it into the preview), the preview's hairline under the skybox and the seam of
 * progress at the joint: the black rule with the blue mark on it.
 */
@Composable
private fun Body(scene: YtMiniScene, look: YtMiniLook, palette: YtPalette, settle: () -> Float, mirror: Boolean) {
    val density = LocalDensity.current.density
    Box(
        Modifier
            .fillMaxSize()
            // right to left: the picture and the progress swap sides with the views
            .graphicsLayer { if (mirror) scaleX = -1f }
            .drawBehind {
                val d = density
                val w = size.width
                val h = size.height
                val hair = d
                val peek = YtDock.peek.coerceIn(0f, 1f)
                val content = h - look.spec.overlap * d

                drawRect(look.fill)
                val top = YtEditorialMath.masthead(peek, hair, YtEditorialLayout.MASTHEAD * d)
                drawRect(palette.text, Offset.Zero, Size(w, top))
                drawRect(palette.text, Offset.Zero, Size(hair, h))
                drawRect(palette.text, Offset(w - hair, 0f), Size(hair, h))

                // a dragged card near: the rules turn blue and double
                val near = if (peek <= 0f) scene.hover() else 0f
                if (near > 0f) {
                    val rim = 2f * d
                    drawRect(palette.accent, Offset.Zero, Size(w, rim), alpha = near)
                    drawRect(palette.accent, Offset.Zero, Size(rim, h), alpha = near)
                    drawRect(palette.accent, Offset(w - rim, 0f), Size(rim, h), alpha = near)
                }

                // the picture's frame, solid under the picture so no paper shows at its edges.
                // Only once the picture has arrived: while minimizing it is elsewhere
                val arrived = YtEditorialMath.ramp(0.92f, 1f, YtDock.progress)
                val grown = scene.peekLayout
                if (arrived > 0f) {
                    val rest = YtMiniEditorial.geometry(w, d)
                    // the picture's constraints run linearly with the preview's progress
                    val picture = if (grown != null && peek > 0f) YtEditorialMath.lerpBox(rest.player, grown.player, peek) else rest.player
                    drawRect(
                        palette.text,
                        Offset(picture.left - hair, picture.top - hair),
                        Size(picture.width + 2f * hair, picture.height + 2f * hair),
                        alpha = arrived,
                    )
                }

                // the preview's hairline under the skybox, drawn through from the start
                if (grown != null && peek > 0f) {
                    val block = YtMiniEditorial.editorialPeek(w, d, grown.next != null)
                    val rule = block.rule
                    if (rule != null) {
                        val pad = YtEditorialLayout.PAD * d
                        val y = h - (block.geometry.boxHeight - rule)
                        drawRect(palette.text, Offset(pad, y), Size((w - 2f * pad) * YtEditorialMath.rulesShown(peek), hair))
                    }
                }

                // the seam: the black rule and the blue mark of how far it played
                val s = settle()
                val left = hair
                val run = w - 2f * hair
                val track = look.trackWidth * d
                val bar = look.barWidth * d
                drawRect(look.track, Offset(left, content - track), Size(run * s, track))
                drawRect(look.barColor, Offset(left, content - bar), Size(run * scene.info.fraction.coerceIn(0f, 1f) * s, bar))
            },
    )
}

/**
 * The docked row: the kicker in mono and the title in Inter bold beside the picture, printed in
 * by the minimize wipe; a blinking caret as a dragged card comes near, the struck-out row of a
 * refused one and the drop's label on a black slip. It gives way as the preview grows.
 */
@Composable
private fun BoxScope.RestRow(
    scene: YtMiniScene,
    look: YtMiniLook,
    palette: YtPalette,
    kicker: String,
    drop: EditorialDrop,
    restBox: Float,
    motion: Boolean,
    mirror: Boolean,
) {
    val density = LocalDensity.current.density
    val title by remember { derivedStateOf { scene.info.title } }
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .requiredHeight((restBox / density).dp)
            // at the foot: the row stays on the bar while the block unfolds above it
            .align(Alignment.BottomStart)
            .graphicsLayer { alpha = YtMiniParts.restFade(scene.peek()) },
    ) {
        val w = constraints.maxWidth.toFloat()
        val g = remember(w, density) { YtMiniEditorial.geometry(w, density) }
        val textWidth = ((g.text.right - g.text.left).coerceAtLeast(0f) / density).dp
        val rowHeight = look.spec.height.dp

        Box(
            Modifier
                .offset { IntOffset(g.text.left.roundToInt(), 0) }
                .width(textWidth)
                .height(rowHeight)
                .graphicsLayer {
                    val near = if (scene.peek() <= 0f) scene.hover() else 0f
                    alpha = (1f - 0.55f * near) * (1f - drop.slip.value)
                }
                .printed({ YtEditorialMath.wipe(YtDock.progress) }, palette.accent)
                .drawWithContent {
                    drawContent()
                    // a refused drop: the row struck through with one black line, then gone
                    val s = drop.struck.value
                    if (s > 0f) {
                        val length = YtEditorialMath.ramp(1f, 0.8f, s)
                        val shown = YtEditorialMath.ramp(0f, 0.4f, s)
                        val y = size.height * 0.56f
                        val x = if (layoutDirection == LayoutDirection.Rtl) size.width * (1f - length) else 0f
                        drawRect(palette.text, Offset(x, y), Size(size.width * length, 1.5f.dp.toPx()), alpha = shown)
                    }
                },
        ) {
            Column(Modifier.align(Alignment.CenterStart)) {
                Text(text = kicker, style = look.subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(2.dp))
                Text(text = title, style = look.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }

        // the caret where the card will be set: it blinks only while a card is dragged
        val dragging = YtDrag.session != null
        val blink = if (dragging && motion) {
            rememberInfiniteTransition(label = "editorialCaret").animateFloat(
                0f, 1f, infiniteRepeatable(tween(CARET_MS, easing = LinearEasing)), label = "editorialCaretPhase",
            )
        } else {
            null
        }
        Canvas(Modifier.fillMaxSize().graphicsLayer { if (mirror) scaleX = -1f }) {
            val near = if (scene.peek() <= 0f) scene.hover() else 0f
            if (near <= 0f || drop.slip.value > 0f) return@Canvas
            if ((blink?.value ?: 0f) >= 0.5f) return@Canvas
            val caret = 26.dp.toPx()
            val x = g.text.left - 5.dp.toPx()
            drawRect(palette.accent, Offset(x, (g.content - caret) / 2f), Size(2.dp.toPx(), caret), alpha = near)
        }

        DropLabel(drop, palette, g, textWidth, rowHeight)
    }
}

/** The caret's blink, on and off. */
private const val CARET_MS = 1060

/** The label of the last accepted drop, typed on a black slip, its number counting up. */
@Composable
private fun DropLabel(drop: EditorialDrop, palette: YtPalette, g: YtMiniGeometry, width: androidx.compose.ui.unit.Dp, height: androidx.compose.ui.unit.Dp) {
    val last = YtDock.lastDrop ?: return
    val resources = LocalContext.current.resources
    val text by remember(last, resources) { derivedStateOf { typedLabel(resources, last, drop.ms.value) } }
    Box(
        Modifier
            .offset { IntOffset(g.text.left.roundToInt(), 0) }
            .width(width)
            .height(height)
            .graphicsLayer { alpha = if (drop.slip.value > 0f) 1f else 0f },
    ) {
        Text(
            text = text,
            style = TextStyle(color = palette.background, fontFamily = YtFonts.plexMono, fontWeight = FontWeight.Medium, fontSize = 11.sp, letterSpacing = 0.1.em),
            maxLines = 1,
            overflow = TextOverflow.Clip,
            modifier = Modifier
                .align(Alignment.CenterStart)
                // the slip is wiped off from its end, as the home's entries leave their slip
                .printed({ drop.slip.value }, null)
                .drawBehind { drawRect(palette.text) }
                .padding(horizontal = 8.dp, vertical = 5.dp),
        )
    }
}

/** The drop label for [drop] showing number [n], in caps. */
private fun label(resources: Resources, drop: YtDropResult, n: Int): String =
    if (drop.next) {
        resources.getString(R.string.yt_mini_editorial_drop_next)
    } else {
        resources.getString(R.string.yt_mini_editorial_drop_end, YtEditorialMath.number(n))
    }.uppercase()

/** How long the label types (every letter of it with the count's first number). */
private fun typingMs(resources: Resources, drop: YtDropResult): Int =
    label(resources, drop, YtEditorialMath.countAt(drop.position, 0f)).length * YtEditorialMath.LABEL_MS

/** The label [ms] into the drop: typed letter by letter, then the number counting up. */
private fun typedLabel(resources: Resources, drop: YtDropResult, ms: Float): String {
    val typing = typingMs(resources, drop)
    if (ms < typing) {
        val start = label(resources, drop, YtEditorialMath.countAt(drop.position, 0f))
        return start.take(floor(ms / YtEditorialMath.LABEL_MS).toInt() + 1)
    }
    if (drop.next) return label(resources, drop, drop.position)
    return label(resources, drop, YtEditorialMath.countAt(drop.position, ms - typing))
}

/** The accept (typing and counting, held, wiped off) and the reject (struck out). */
@Composable
private fun DropEffects(scene: YtMiniScene, drop: EditorialDrop, motion: Boolean) {
    val resources = LocalContext.current.resources
    val accepted = YtDock.accepted
    var seenAccept by remember { mutableIntStateOf(accepted) }
    LaunchedEffect(accepted) {
        if (accepted == seenAccept) return@LaunchedEffect
        seenAccept = accepted
        val last = YtDock.lastDrop ?: return@LaunchedEffect
        drop.struck.snapTo(0f)
        if (motion) {
            val total = typingMs(resources, last) + if (last.next) 0 else YtEditorialMath.countMs(last.position)
            drop.ms.snapTo(0f)
            drop.slip.snapTo(1f)
            drop.ms.animateTo(total.toFloat(), tween(total, easing = LinearEasing))
            delay(1300)
            drop.slip.animateTo(0f, tween(200, easing = LinearEasing))
        } else {
            drop.ms.snapTo(LABEL_DONE)
            drop.slip.snapTo(1f)
            delay(1500)
            drop.slip.snapTo(0f)
        }
    }

    val rejects = scene.rejects
    var seenReject by remember { mutableIntStateOf(rejects) }
    LaunchedEffect(rejects) {
        if (rejects == seenReject) return@LaunchedEffect
        seenReject = rejects
        if (!motion) return@LaunchedEffect
        drop.struck.snapTo(1f)
        drop.struck.animateTo(0f, tween(480, easing = LinearEasing))
    }
}

/**
 * The preview's printed block, placed from the bottom of the box (which grows upward from the
 * bar): the skybox with the next video at the top, the kicker and the time beside the picture
 * over the play slip, the headline under the picture. The rules come first, then each line
 * prints from the start edge; the kicker is typed in strokes, with the haptic of the press.
 */
@Composable
private fun Preview(scene: YtMiniScene, palette: YtPalette, kicker: String, motion: Boolean) {
    val grown = scene.peekLayout ?: return
    val next = scene.next
    val target = scene.target
    val title by remember { derivedStateOf { scene.info.title } }
    val playing by remember { derivedStateOf { scene.info.playing } }
    val density = LocalDensity.current.density

    // the kicker's typing, in ms since the press took
    val typing = remember { Animatable(0f) }
    val steps = YtEditorialMath.typeSteps(kicker.length)
    LaunchedEffect(Unit) {
        val total = YtEditorialMath.typeMs(steps, YtEditorialMath.TYPE_MS)
        if (motion) {
            typing.animateTo(total.toFloat(), tween(total, easing = LinearEasing))
        } else {
            typing.snapTo(total.toFloat())
        }
    }

    Layout(
        modifier = Modifier.fillMaxSize(),
        content = {
            if (next != null && grown.next != null) {
                Skybox(scene, next, target == YtMiniTarget.NEXT, palette, Modifier.layoutId("next"))
            }
            Column(
                Modifier
                    .layoutId("column")
                    .graphicsLayer { alpha = YtEditorialMath.ramp(0.05f, 0.25f, scene.peek()) },
            ) {
                Text(
                    text = kicker,
                    style = TextStyle(color = palette.accent, fontFamily = YtFonts.plexMono, fontWeight = FontWeight.Medium, fontSize = 10.sp, letterSpacing = 0.12.em),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    // mono: every stroke's letters are an equal share of the line
                    modifier = Modifier.printed(
                        {
                            val n = kicker.length.coerceAtLeast(1)
                            YtEditorialMath.typedChars(n, steps, typing.value, YtEditorialMath.TYPE_MS).toFloat() / n
                        },
                        palette.accent,
                    ),
                )
                Spacer(Modifier.height(4.dp))
                TimeLine(scene, palette)
            }
            PlaySlip(scene, playing, target == YtMiniTarget.PILL, palette, Modifier.layoutId("pill"))
            Text(
                text = title,
                style = TextStyle(color = palette.text, fontFamily = YtFonts.inter, fontWeight = FontWeight.Bold, fontSize = 15.sp, lineHeight = 19.sp, letterSpacing = (-0.2).sp),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .layoutId("headline")
                    .printed({ YtEditorialMath.headlinePrint(scene.peek()) }, palette.accent),
            )
        },
    ) { measurables, constraints ->
        val w = constraints.maxWidth
        val h = constraints.maxHeight
        val block = YtMiniEditorial.editorialPeek(w.toFloat(), density, grown.next != null)
        val boxes: Map<Any?, YtMiniBox?> = mapOf(
            "next" to block.geometry.next,
            "column" to block.column,
            "pill" to block.geometry.pill,
            "headline" to block.headline,
        )
        val placed = measurables.mapNotNull { measurable ->
            val box = boxes[measurable.layoutId] ?: return@mapNotNull null
            val width = box.width.roundToInt().coerceAtLeast(0)
            val height = box.height.roundToInt().coerceAtLeast(0)
            val placeable = if (measurable.layoutId == "column") {
                // the column takes its own height, up to the room above the slip
                measurable.measure(Constraints(minWidth = width, maxWidth = width, maxHeight = height))
            } else {
                measurable.measure(Constraints.fixed(width, height))
            }
            placeable to box
        }
        layout(w, h) {
            // from the bottom: the box's foot stays on the bar while it grows
            placed.forEach { (placeable, box) ->
                placeable.placeRelative(box.left.roundToInt(), (h - (block.geometry.boxHeight - box.top)).roundToInt())
            }
        }
    }
}

/** Where the video stands, in mono: "4:07 / 12:30", or the live badge. Read only while the preview shows. */
@Composable
private fun TimeLine(scene: YtMiniScene, palette: YtPalette) {
    val clock by YtPlaybackClock.clock.collectAsState()
    val text = if (clock.isLive) {
        stringResource(R.string.yt_live_badge)
    } else {
        YtEditorialMath.time(clock.positionMs, clock.durationMs)
    }
    Text(
        text = text,
        style = TextStyle(color = palette.text, fontFamily = YtFonts.plexMono, fontWeight = FontWeight.Medium, fontSize = 11.sp),
        maxLines = 1,
        overflow = TextOverflow.Clip,
        modifier = Modifier.printed({ YtEditorialMath.timePrint(scene.peek()) }, palette.accent),
    )
}

/**
 * Play and pause as a slip in a black rule: the glyph and "PAUSE" or "WEITER" in mono; under
 * the finger it inverts to black with the blue mark on its top edge, as the nav marks its tab.
 */
@Composable
private fun PlaySlip(scene: YtMiniScene, playing: Boolean, active: Boolean, palette: YtPalette, modifier: Modifier) {
    val paper = palette.background
    val ink = palette.text
    val label = stringResource(if (playing) R.string.yt_mini_editorial_pause else R.string.yt_mini_editorial_play).uppercase()
    Box(
        modifier = modifier
            .graphicsLayer { alpha = YtEditorialMath.rulesShown(scene.peek()) }
            .drawBehind {
                val hair = 1.dp.toPx()
                if (active) {
                    drawRect(ink)
                    drawRect(palette.accent, size = Size(size.width, 3.dp.toPx()))
                } else {
                    drawRect(paper)
                    drawRect(ink, Offset.Zero, Size(size.width, hair))
                    drawRect(ink, Offset(0f, size.height - hair), Size(size.width, hair))
                    drawRect(ink, Offset.Zero, Size(hair, size.height))
                    drawRect(ink, Offset(size.width - hair, 0f), Size(hair, size.height))
                }
            },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 10.dp)
                .printed({ YtEditorialMath.timePrint(scene.peek()) }, null),
        ) {
            // the glyph of what the slip does: pause while it plays, play while paused
            Canvas(Modifier.size(10.dp)) { editorialGlyph(!playing, center, size.minDimension / 2f, if (active) paper else ink) }
            Spacer(Modifier.width(8.dp))
            Text(
                text = label,
                style = TextStyle(color = if (active) paper else ink, fontFamily = YtFonts.plexMono, fontWeight = FontWeight.Medium, fontSize = 11.sp, letterSpacing = 0.1.em),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * The next video as the skybox over the block: its thumbnail in black and white in a rule frame
 * (in colour under the finger, as the home's entries colour when pressed), then
 * "02 — NÄCHSTES:" in blue mono and its title. Under the finger it inverts to black.
 */
@Composable
private fun Skybox(scene: YtMiniScene, next: YtMiniNext, active: Boolean, palette: YtPalette, modifier: Modifier) {
    val ink = palette.text
    val paper = palette.background
    val lead = "02 — " + stringResource(R.string.yt_mini_editorial_next).uppercase() + ":"
    val line = remember(lead, next.title, active, palette) {
        buildAnnotatedString {
            withStyle(SpanStyle(color = if (active) paper else palette.accent)) { append(lead) }
            append(" ")
            append(next.title)
        }
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .graphicsLayer { alpha = YtEditorialMath.rulesShown(scene.peek()) }
            .drawBehind {
                if (active) {
                    drawRect(ink)
                    drawRect(palette.accent, size = Size(3.dp.toPx(), size.height))
                }
            }
            .padding(start = 6.dp, end = 6.dp),
    ) {
        Box(
            Modifier
                .size(48.dp, 27.dp)
                .drawBehind { drawRect(if (active) paper else ink) }
                .padding(1.dp),
        ) {
            AsyncImage(
                model = next.thumbnail,
                contentDescription = null,
                imageLoader = ImageHelper.loader(),
                contentScale = ContentScale.Crop,
                colorFilter = if (active) null else ytGrayscale,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Spacer(Modifier.width(10.dp))
        Text(
            text = line,
            style = TextStyle(color = if (active) paper else ink, fontFamily = YtFonts.plexMono, fontWeight = FontWeight.Medium, fontSize = 11.sp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.printed({ YtEditorialMath.skyboxPrint(scene.peek()) }, palette.accent),
        )
    }
}

/** ▶ for [play], ❚❚ otherwise, at [center] ([r] half its height), hard edged. */
internal fun DrawScope.editorialGlyph(play: Boolean, center: Offset, r: Float, color: Color) {
    if (play) {
        val path = Path().apply {
            moveTo(center.x - r * 0.7f, center.y - r)
            lineTo(center.x + r * 0.9f, center.y)
            lineTo(center.x - r * 0.7f, center.y + r)
            close()
        }
        drawPath(path, color)
    } else {
        val bar = r * 0.62f
        drawRect(color, Offset(center.x - r * 0.8f, center.y - r), Size(bar, r * 2f))
        drawRect(color, Offset(center.x + r * 0.8f - bar, center.y - r), Size(bar, r * 2f))
    }
}
