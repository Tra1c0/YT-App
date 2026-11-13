package com.github.libretube.ui.yt.mini

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
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
import com.github.libretube.ui.yt.CookieShape
import com.github.libretube.ui.yt.LocalYtMotion
import com.github.libretube.ui.yt.YtDock
import com.github.libretube.ui.yt.YtFonts
import com.github.libretube.ui.yt.ytSpec
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/*
 * YT: what the Formen player draws behind its picture: the night-blue body that grows out of the
 * bar, the pink cookie behind play (turning while the video plays), title and channel in Google
 * Sans; in the preview the title beside the grown window, the pink play pill and the next video
 * in a small cookie that turns in; the drop's lime ripple of scallops and its label on a pink
 * pill. Every moving value is read while laying out or drawing.
 */

/** Where the parts sit in the composable's current size (its foot on the bar), in px, as drawn. */
private class ShapesSpace(private val design: YtMiniShapes, private val density: Float) {
    private var key: List<Any>? = null
    var restHeight = 0f
    var restPlayer = YtMiniBox(0f, 0f, 0f, 0f)
    var restMark = restPlayer
    var restText = restPlayer
    var peekTitle = restPlayer
    var peekPill = restPlayer
    var peekNext: YtMiniBox? = null
    var peekThumb: YtMiniBox? = null

    fun at(size: Size, rtl: Boolean, hasNext: Boolean): ShapesSpace {
        val k = listOf(size.width, size.height, rtl, hasNext)
        if (k == key) return this
        key = k
        val w = size.width
        val h = size.height
        val g = design.geometry(w, density)
        val p = YtShapesLayout.peek(design.spec, w, density, hasNext)
        // the box at rest as the constraints lay it out (whole px)
        restHeight = g.boxHeight.roundToInt().toFloat()
        fun rest(box: YtMiniBox) = YtShapesLayout.fromFoot(YtMiniLayout.directed(box, w, rtl), g.boxHeight, h)
        fun peek(box: YtMiniBox) = YtShapesLayout.fromFoot(YtMiniLayout.directed(box, w, rtl), p.frame.boxHeight, h)
        restPlayer = rest(g.player)
        val play = g.play ?: g.player
        val mark = YtShapesLayout.MARK * density / 2f
        restMark = rest(YtMiniBox(play.centerX - mark, play.centerY - mark, play.centerX + mark, play.centerY + mark))
        restText = rest(g.text)
        peekTitle = peek(p.frame.title)
        peekPill = peek(p.frame.pill)
        peekNext = p.frame.next?.let { peek(it) }
        peekThumb = p.thumb?.let { peek(it) }
        return this
    }
}

@Composable
internal fun ShapesUnder(design: YtMiniShapes, scene: YtMiniScene) {
    val palette = scene.palette ?: return
    val look = remember(palette) { design.look(palette) }
    val density = LocalDensity.current.density
    val motion = LocalYtMotion.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val space = remember(density) { ShapesSpace(design, density) }
    val lime = palette.lime
    val pink = palette.pink
    val ink = palette.ink
    val merge = remember(palette) { design.merge(palette) }

    // what changes rarely (not the position, which the poll renews every time)
    val text by remember { derivedStateOf { scene.info.let { Triple(it.title, it.channel, it.playing) } } }
    val (title, channel, playing) = text
    val clock = rememberShapesClock(motion && playing && YtDock.docked)

    // the landing: the pink cookie turns in and pops, the text slides in after it
    val landed = remember { Animatable(1f) }
    val textIn = remember { Animatable(1f) }
    ShapesLandingReset(motion, listOf(landed, textIn))
    val landings = scene.landings
    var seenLanding by remember { mutableIntStateOf(landings) }
    LaunchedEffect(landings) {
        if (landings == seenLanding) return@LaunchedEffect
        if (!motion) {
            seenLanding = landings
            return@LaunchedEffect
        }
        landed.snapTo(0f)
        textIn.snapTo(0f)
        seenLanding = landings
        launch { landed.animateTo(1f, spring(dampingRatio = 0.4f, stiffness = 420f)) }
        launch {
            delay(70)
            textIn.animateTo(1f, spring(dampingRatio = 0.75f, stiffness = 380f))
        }
    }
    // until the landing has started the parts wait (no frame of them at rest before it)
    fun landing(value: Float): Float = if (scene.landings != seenLanding) 0f else value

    // a taken video: a lime ripple of scallops runs out over the body, then the label
    val ripple = remember { Animatable(0f) }
    val label = remember { Animatable(0f) }
    val accepted = YtDock.accepted
    var seenDrop by remember { mutableIntStateOf(accepted) }
    LaunchedEffect(accepted) {
        if (accepted == seenDrop) return@LaunchedEffect
        seenDrop = accepted
        if (motion) {
            launch {
                ripple.snapTo(0f)
                ripple.animateTo(1f, tween(620, easing = LinearEasing))
                ripple.snapTo(0f)
            }
            delay(ACCEPT_LIFT_MS.toLong())
            label.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = 480f))
            delay(1400)
            label.animateTo(0f, tween(200, easing = LinearEasing))
        } else {
            label.snapTo(1f)
            delay(1400)
            label.snapTo(0f)
        }
    }

    // a refused drop: the body's rim flashes pink
    val reject = remember { Animatable(0f) }
    val rejects = scene.rejects
    var seenReject by remember { mutableIntStateOf(rejects) }
    LaunchedEffect(rejects) {
        if (rejects == seenReject) return@LaunchedEffect
        seenReject = rejects
        reject.snapTo(1f)
        if (motion) {
            reject.animateTo(0f, tween(520, easing = LinearEasing))
        } else {
            delay(400)
            reject.snapTo(0f)
        }
    }

    // the finger over a part of the preview: it swells, the next video's cookie turns a step
    val target = scene.target
    val nextPress by animateFloatAsState(
        if (target == YtMiniTarget.NEXT) 1f else 0f,
        ytSpec(spring<Float>(dampingRatio = 0.45f, stiffness = 520f)),
        label = "shapesNext",
    )
    val pillPress by animateFloatAsState(
        if (target == YtMiniTarget.PILL) 1.06f else 1f,
        ytSpec(spring<Float>(dampingRatio = 0.5f, stiffness = 600f)),
        label = "shapesPill",
    )

    val next = scene.next
    // the layout the host chose when the preview started; between previews the current next
    val hasNext = { scene.peekLayout?.let { it.next != null } ?: (scene.next != null) }
    fun spaceAt(size: Size) = space.at(size, rtl, hasNext())
    fun peekValue() = YtDock.peek.coerceIn(0f, 1.15f)

    /** The rest row shows once the player sits on the bar and gives way to the preview first. */
    fun restShown() = YtShapesWindow.smoothstep(0.88f, 1f, YtDock.progress) * YtMiniParts.restFade(YtDock.peek)

    /** Places the content at the box [of] gives for the current size (nothing when it is null). */
    fun Modifier.at(of: (ShapesSpace) -> YtMiniBox?): Modifier = layout { measurable, constraints ->
        val s = spaceAt(Size(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat()))
        val box = of(s)
        val placeable = measurable.measure(
            Constraints.fixed(box?.width?.roundToInt()?.coerceAtLeast(0) ?: 0, box?.height?.roundToInt()?.coerceAtLeast(0) ?: 0),
        )
        layout(constraints.maxWidth, constraints.maxHeight) {
            if (box != null) placeable.place(box.left.roundToInt(), box.top.roundToInt())
        }
    }

    val scallop = remember { Path() }

    Box(Modifier.fillMaxSize().clearAndSetSemantics {}) {
        // 1: the body, one surface with the bar, grown with the preview; a dragged video lights
        // its rim lime, a refused one pink; a taken one sends a ripple of scallops over it
        Box(
            Modifier
                .fillMaxSize()
                .drawBehind {
                    val s = spaceAt(size)
                    // the body fills its box down to the bar at every size: grown by the preview or between
                    // page and bar while minimizing or dragged (a rest-sized body at the top of a
                    // taller box left the screen behind visible between body and bar)
                    val grow = YtShapesLayout.grow(size.height, s.restHeight)
                    // the seamless minimize: between page and bar the body is the window that
                    // takes on the bar's width, corners and colour (docked and in the preview it
                    // is the bulge as before, which the window ends in)
                    val window = merge?.takeIf { YtDock.peek <= 0f && scene.pageProgress < 1f }?.let {
                        YtMiniMergeMath.window(it, scene.pageProgress, size.width, size.height, density, motion)
                    }
                    val content = look.height * density + grow
                    val body = if (window != null) {
                        shapesWindowPath(window, size.width, size.height, content, open = false)
                    } else {
                        design.bodyPath(look, size.width, size.height, density, open = false, grow = grow)
                    }
                    drawPath(body, if (window != null && merge != null) Color(YtMiniMergeMath.color(merge, window.tint)) else look.fill)
                    val rim = if (window != null) {
                        shapesWindowPath(window, size.width, size.height, content, open = true, inset = look.borderWidth * density / 2f)
                    } else {
                        design.bodyPath(look, size.width, size.height, density, open = true, grow = grow)
                    }
                    look.border?.let { border ->
                        val tint = window?.tint ?: 1f
                        if (tint > 0f) drawPath(rim, border.copy(alpha = border.alpha * tint), style = Stroke(look.borderWidth * density))
                    }
                    val hover = scene.hover()
                    if (hover > 0f && YtDock.peek <= 0f) drawPath(rim, lime.copy(alpha = hover), style = Stroke(2f * density))
                    val r = reject.value
                    if (r > 0f) drawPath(rim, pink.copy(alpha = r), style = Stroke(2.5f * density))
                    val t = ripple.value
                    if (t > 0f) {
                        val center = Offset(s.restPlayer.centerX, s.restPlayer.centerY)
                        val radius = s.restPlayer.width / 2f + 70f * density * YtShapesWindow.smoothstep(0f, 1f, t)
                        scallops(scallop, center, radius, YtShapesLayout.LOBES, 0.05f, 30f * t)
                        clipPath(body) {
                            drawPath(scallop, lime.copy(alpha = 0.8f * (1f - t)), style = Stroke(2f * density * (1f - 0.5f * t)))
                        }
                    }
                },
        )

        // 2: the pink cookie behind play: turns while the video plays, turns in on landing
        Box(
            Modifier
                .at { it.restMark }
                .graphicsLayer {
                    val l = landing(landed.value)
                    val pop = 0.4f + 0.6f * l
                    val lean = 1f + 0.08f * scene.hover()
                    scaleX = pop * lean
                    scaleY = pop * lean
                    // 60° back: its six scallops look the same, so the turn starts without a jump
                    rotationZ = clock.floatValue * SHAPES_MARK_SPEED - 60f * (1f - l)
                    alpha = restShown()
                }
                .clip(CookieShape(6, 0.1f))
                .background(pink),
        )

        // 3: title and channel, which give way to the preview and to the drop's label
        Column(
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .at { it.restText }
                .graphicsLayer {
                    val shown = landing(textIn.value)
                    alpha = restShown() * shown.coerceIn(0f, 1f) * (1f - label.value.coerceIn(0f, 1f))
                    translationX = (if (rtl) -1f else 1f) * 10f * density * (1f - shown)
                },
        ) {
            Text(text = title, style = look.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (channel.isNotBlank()) {
                Spacer(Modifier.height(1.dp))
                Text(text = channel, style = look.subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }

        // 4: the drop's label on a pink pill that turns in like a shape set into place
        Box(
            contentAlignment = Alignment.CenterStart,
            modifier = Modifier
                .at { it.restText }
                .graphicsLayer {
                    val v = label.value
                    alpha = v.coerceIn(0f, 1f) * YtMiniParts.restFade(YtDock.peek)
                    scaleX = 0.6f + 0.4f * v
                    scaleY = 0.6f + 0.4f * v
                    rotationZ = (if (rtl) 1f else -1f) * 14f * (1f - v)
                },
        ) {
            val drop = YtDock.lastDrop
            if (drop != null) {
                Text(
                    text = if (drop.next) stringResource(R.string.yt_mini_drop_next) else stringResource(R.string.yt_mini_drop_end, drop.position),
                    style = TextStyle(color = ink, fontFamily = YtFonts.googleSans, fontWeight = FontWeight.Medium, fontSize = 12.sp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(pink)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }

        // 5: the preview's parts, from the foot of the grown box
        if (scene.peekLayout != null) {
            // the title beside the grown window
            Box(
                Modifier
                    .at { it.peekTitle }
                    .graphicsLayer {
                        val a = YtShapesWindow.smoothstep(0.3f, 0.75f, peekValue())
                        alpha = a
                        translationY = 8f * density * (1f - a)
                    },
            ) {
                Text(
                    text = title,
                    style = look.title.copy(fontSize = 14.sp, lineHeight = 17.sp),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // play and pause on a pink pill that blooms out of a cookie-sized dot
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .at { it.peekPill }
                    .graphicsLayer {
                        val a = YtShapesWindow.smoothstep(0.3f, 0.8f, peekValue())
                        alpha = a
                        val s = (0.7f + 0.3f * a) * pillPress
                        scaleX = s
                        scaleY = s
                    }
                    .clip(RoundedCornerShape(50))
                    .background(if (target == YtMiniTarget.PILL) lime else pink),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 14.dp)) {
                    YtMiniParts.Glyph(playing, ink, Modifier.size(12.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(if (playing) R.string.yt_mini_peek_playing else R.string.yt_mini_peek_paused),
                        style = TextStyle(color = ink, fontFamily = YtFonts.googleSans, fontWeight = FontWeight.Medium, fontSize = 12.sp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            if (next != null) {
                // the next video's row: a soft pill across the body, lime when the finger is on it
                val active = target == YtMiniTarget.NEXT
                Box(
                    modifier = Modifier
                        .at { it.peekNext }
                        .graphicsLayer {
                            val a = YtShapesWindow.smoothstep(0.5f, 0.95f, peekValue())
                            alpha = a
                            translationY = 12f * density * (1f - a)
                            val s = 1f + 0.03f * nextPress
                            scaleX = s
                            scaleY = s
                        }
                        .clip(RoundedCornerShape(50))
                        .background(if (active) lime.copy(alpha = 0.2f) else Color(0x14FFFFFF))
                        .border(1.dp, if (active) lime else Color(0x1FFFFFFF), RoundedCornerShape(50)),
                ) {
                    Column(
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(start = (6 + YtShapesLayout.NEXT_THUMB + 12).dp, end = 18.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.yt_mini_peek_next).uppercase(),
                            style = TextStyle(color = lime, fontFamily = YtFonts.googleSans, fontWeight = FontWeight.Medium, fontSize = 10.sp, letterSpacing = 0.1.em),
                            maxLines = 1,
                        )
                        Text(
                            text = next.title,
                            style = look.title.copy(fontSize = 13.sp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val sub = if (active) stringResource(R.string.yt_mini_shapes_release) else next.channel
                        if (sub.isNotBlank()) {
                            Text(
                                text = sub,
                                style = look.subtitle.copy(fontSize = 11.sp, color = if (active) lime else look.subtitle.color),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }

                // its picture in a small cookie that turns in, and a step further under the finger
                AsyncImage(
                    model = next.thumbnail,
                    contentDescription = null,
                    imageLoader = ImageHelper.loader(),
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .at { it.peekThumb }
                        .graphicsLayer {
                            val a = YtShapesWindow.smoothstep(0.5f, 1f, peekValue())
                            alpha = YtShapesWindow.smoothstep(0.5f, 0.75f, peekValue())
                            val s = (0.5f + 0.5f * a) * (1f + 0.06f * nextPress)
                            scaleX = s
                            scaleY = s
                            rotationZ = (if (rtl) 1f else -1f) * 120f * (1f - a) + 30f * nextPress
                        }
                        .clip(CookieShape(9, 0.08f))
                        .background(palette.lavender.copy(alpha = 0.25f)),
                )
            }
        }
    }
}

/**
 * The Formen window of the seamless minimize ([YtMiniMergeMath.window]) in a box [width] ×
 * [height] px whose body stands [content] px above the bar: a rounded rectangle with the bar's
 * convex corners at its foot while it approaches, or (once it narrows) the bulge's body with its
 * fillets curving out into the bar, as [YtMiniLookDesign.bodyPath] draws it docked. [open]: the
 * rim, inside the edge by [inset], without the foot that lies on the bar.
 */
internal fun shapesWindowPath(window: YtMiniWindow, width: Float, height: Float, content: Float, open: Boolean, inset: Float = 0f): Path {
    val path = Path()
    val b = window.inset
    val r = window.top
    val f = window.fillet
    if (f <= 0f) {
        val i = if (open) inset else 0f
        path.addRoundRect(
            androidx.compose.ui.geometry.RoundRect(
                left = b + i,
                top = i,
                right = width - b - i,
                bottom = height - i,
                topLeftCornerRadius = androidx.compose.ui.geometry.CornerRadius((r - i).coerceAtLeast(0f)),
                topRightCornerRadius = androidx.compose.ui.geometry.CornerRadius((r - i).coerceAtLeast(0f)),
                bottomRightCornerRadius = androidx.compose.ui.geometry.CornerRadius((window.foot - i).coerceAtLeast(0f)),
                bottomLeftCornerRadius = androidx.compose.ui.geometry.CornerRadius((window.foot - i).coerceAtLeast(0f)),
            ),
        )
        return path
    }
    // the bulge: from the bar's surface, curving up into the body and down again
    val i = if (open) inset else 0f
    val foot = if (open) content + i else content
    path.moveTo(b - f, foot)
    path.arcTo(androidx.compose.ui.geometry.Rect(b - 2 * f, content - 2 * f, b, content), 90f, -90f, false)
    path.lineTo(b + i, r)
    path.arcTo(androidx.compose.ui.geometry.Rect(b + i, i, b + i + 2 * r, i + 2 * r), 180f, 90f, false)
    path.lineTo(width - b - i - r, i)
    path.arcTo(androidx.compose.ui.geometry.Rect(width - b - i - 2 * r, i, width - b - i, i + 2 * r), 270f, 90f, false)
    path.lineTo(width - b - i, content - f)
    path.arcTo(androidx.compose.ui.geometry.Rect(width - b, content - 2 * f, width - b + 2 * f, content), 180f, -90f, false)
    if (!open) {
        path.lineTo(width - b + f, height)
        path.lineTo(b - f, height)
        path.close()
    }
    return path
}
