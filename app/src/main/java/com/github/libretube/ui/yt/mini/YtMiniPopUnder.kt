package com.github.libretube.ui.yt.mini

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
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
import com.github.libretube.api.obj.StreamItem
import com.github.libretube.helpers.ImageHelper
import com.github.libretube.ui.yt.LocalYtMotion
import com.github.libretube.ui.yt.YtDock
import com.github.libretube.ui.yt.YtDrag
import com.github.libretube.ui.yt.YtFonts
import com.github.libretube.ui.yt.ytSpec
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/*
 * YT: what the Pop player draws behind its picture: the lavender pill that inflates into a
 * balloon, the pink sticker shadow of the picture, the plum blob behind play (with the mouth
 * that gulps dropped cards; in the preview it stretches into the pill of play and pause), title,
 * channel and the pink underline, the next video's yellow sticker, the drop's label. Every
 * moving value is read while laying out or drawing.
 */

/** Where the parts sit in the composable's current size (its foot on the bar), in px, as drawn. */
private class PopSpace(private val design: YtMiniPop, private val density: Float) {
    private var width = -1f
    private var height = -1f
    private var keyRtl = false
    private var keyNext = false

    /** The docked box's height and the grown one's. */
    var restBox = 0f
    var peekBox = 0f
    var restPlayer = YtMiniBox(0f, 0f, 0f, 0f)
    var restBlob = restPlayer
    var restText = restPlayer
    var peekPlayer = restPlayer
    var peekNext: YtMiniBox? = null
    var peekPill = restPlayer
    var peekTitle = restPlayer
    var rtl = false

    fun at(size: Size, rtl: Boolean, hasNext: Boolean): PopSpace {
        if (size.width == width && size.height == height && rtl == keyRtl && hasNext == keyNext) return this
        width = size.width
        height = size.height
        keyRtl = rtl
        keyNext = hasNext
        this.rtl = rtl
        val w = size.width
        val h = size.height
        val g = design.geometry(w, density)
        val p = design.peekGeometry(w, density, hasNext)
        restBox = g.boxHeight
        peekBox = p.boxHeight
        fun rest(box: YtMiniBox) = YtPopLayout.fromFoot(YtMiniLayout.directed(box, w, rtl), g.boxHeight, h)
        fun peek(box: YtMiniBox) = YtPopLayout.fromFoot(YtMiniLayout.directed(box, w, rtl), p.boxHeight, h)
        restPlayer = rest(g.player)
        restBlob = rest(YtPopLayout.blob(g.play ?: g.player, density))
        restText = rest(g.text)
        peekPlayer = peek(p.player)
        peekNext = p.next?.let { peek(it) }
        peekPill = peek(p.pill)
        peekTitle = peek(p.title)
        return this
    }
}

/** What the drop target remembers of the dragged card (plain fields, read while drawing). */
private class PopDrop {
    /** The finger, in px of the box, the last time it was seen during the drag. */
    var pointer: Offset? = null
    var thumbnail by mutableStateOf<String?>(null)
}

@Composable
internal fun PopUnder(design: YtMiniPop, scene: YtMiniScene) {
    val palette = scene.palette ?: return
    val look = remember(palette) { design.look(palette) }
    val density = LocalDensity.current.density
    val motion = LocalYtMotion.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val space = remember(density) { PopSpace(design, density) }
    val mirror = if (rtl) -1f else 1f

    val plum = palette.accent
    val pink = palette.pink
    val band = PopColors.band
    val yellow = PopColors.yellow

    // the landing: the sticker shadow slaps on under the picture, then the blob pops
    val shadowSnap = remember { Animatable(1f) }
    val blobPop = remember { Animatable(1f) }
    val landings = scene.landings
    var seenLanding by remember { mutableIntStateOf(landings) }
    LaunchedEffect(landings) {
        if (landings == seenLanding) return@LaunchedEffect
        if (!motion) {
            seenLanding = landings
            return@LaunchedEffect
        }
        shadowSnap.snapTo(0f)
        blobPop.snapTo(0.6f)
        seenLanding = landings
        launch { shadowSnap.animateTo(1f, spring(dampingRatio = 0.4f, stiffness = 520f)) }
        launch {
            // with the second tick of the dock's feel
            delay(140)
            blobPop.animateTo(1f, spring(dampingRatio = 0.32f, stiffness = 620f))
        }
    }
    // until the landing has started, its parts wait (no frame of them at rest before it)
    fun snap(value: Float): Float = if (scene.landings != seenLanding) 0f else value

    // the balloon's width: a softer spring than the preview's that only turns round when the
    // preview does, so it lags the height going up and swings past it when the height settles
    val wide = remember { Animatable(0f) }
    LaunchedEffect(motion) {
        if (!motion) {
            wide.snapTo(0f)
            return@LaunchedEffect
        }
        var last = 0f
        var direction = 0
        var job: Job? = null
        snapshotFlow { YtDock.peek }.collect { value ->
            val now = when {
                value > last + 0.001f -> 1
                value < last - 0.001f -> -1
                else -> direction
            }
            last = value
            if (now != direction && now != 0) {
                direction = now
                job?.cancel()
                job = launch { wide.animateTo(if (now > 0) 1f else 0f, spring(dampingRatio = 0.38f, stiffness = 170f)) }
            }
        }
    }

    // the next video's sticker slaps on once the balloon is nearly full, peels off as it shrinks
    val slap = remember { Animatable(0f) }
    LaunchedEffect(motion) {
        snapshotFlow { scene.peekLayout != null && YtDock.peek >= 0.85f }
            .distinctUntilChanged()
            .collect { on ->
                when {
                    !motion -> slap.snapTo(if (on) 1f else 0f)
                    on -> {
                        slap.snapTo(0f)
                        slap.animateTo(1f, spring(dampingRatio = 0.42f, stiffness = 650f))
                    }
                    else -> slap.animateTo(0f, tween(140, easing = LinearEasing))
                }
            }
    }

    // the dragged card: where the finger was (the mouth faces it), its thumbnail (the gulp)
    val drop = remember { PopDrop() }
    LaunchedEffect(drop) {
        snapshotFlow {
            val session = YtDrag.session
            val bounds = YtDrag.miniBounds
            if (session != null && bounds != null) {
                Offset(session.pointer.x - bounds.left, session.pointer.y - bounds.top)
            } else {
                null
            }
        }.collect { if (it != null) drop.pointer = it }
    }
    LaunchedEffect(drop) {
        snapshotFlow { (YtDrag.session?.payload as? StreamItem)?.thumbnail }
            .collect { if (it != null) drop.thumbnail = it }
    }

    // a dropped card: gulped (flight, chomp, swell), then the label as a yellow sticker
    val gulp = remember { Animatable(0f) }
    val label = remember { Animatable(0f) }
    val accepted = YtDock.accepted
    var seenDrop by remember { mutableIntStateOf(accepted) }
    LaunchedEffect(accepted) {
        if (accepted == seenDrop) return@LaunchedEffect
        seenDrop = accepted
        if (motion) {
            launch {
                gulp.snapTo(0f)
                gulp.animateTo(1f, tween(YtPopMath.GULP_MS, easing = LinearEasing))
                gulp.snapTo(0f)
            }
            delay(260)
            label.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = 560f))
            delay(1400)
            label.animateTo(0f, tween(200, easing = LinearEasing))
        } else {
            label.snapTo(1f)
            delay(1400)
            label.snapTo(0f)
        }
    }

    // a refused card: the blob shakes its head with its mouth shut, the rim flashes pink
    val reject = remember { Animatable(0f) }
    val rejects = scene.rejects
    var seenReject by remember { mutableIntStateOf(rejects) }
    LaunchedEffect(rejects) {
        if (rejects == seenReject) return@LaunchedEffect
        seenReject = rejects
        if (motion) {
            reject.snapTo(0f)
            reject.animateTo(1f, tween(480, easing = LinearEasing))
            reject.snapTo(0f)
        } else {
            reject.snapTo(0.5f)
            delay(400)
            reject.snapTo(0f)
        }
    }

    // the finger over a part of the preview: the sticker lifts, the pill swells
    val target = scene.target
    val lifted by animateFloatAsState(
        if (target == YtMiniTarget.NEXT) 1f else 0f,
        ytSpec(spring<Float>(dampingRatio = 0.5f, stiffness = 600f)),
        label = "popLift",
    )
    val pillPress by animateFloatAsState(
        if (target == YtMiniTarget.PILL) 1.07f else 1f,
        ytSpec(spring<Float>(dampingRatio = 0.45f, stiffness = 600f)),
        label = "popPill",
    )

    // what changes rarely (not the position, which the poll renews every time)
    val text by remember { derivedStateOf { scene.info.let { Triple(it.title, it.channel, it.playing) } } }
    val (title, channel, playing) = text

    // the blob breathes while the video plays on the bar, its rim rolling a little
    val live = motion && playing && YtDock.docked
    val breath = if (live) {
        rememberInfiniteTransition(label = "popBreath").animateFloat(
            0f, 1f, infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Reverse), label = "popBreathValue",
        )
    } else {
        null
    }

    val next = scene.next
    // the layout the host chose when the preview started; between previews the current next
    val hasNext = { scene.peekLayout?.let { it.next != null } ?: (scene.next != null) }

    fun spaceAt(size: Size) = space.at(size, rtl, hasNext())
    fun peeking() = scene.peekLayout != null
    fun peekValue() = if (peeking()) YtDock.peek.coerceIn(0f, 1.15f) else 0f

    /** The parts beside the picture show once the player sits on the bar. */
    fun docked() = YtPopMath.smoothstep(0.9f, 1f, YtDock.progress)

    /** How far the blob has become the pill (0 docked, 1 in the full preview, a little past). */
    fun pillPhase(): Float {
        val p = peekValue()
        return if (p <= 0.1f) 0f else ((p - 0.1f) / 0.9f).coerceAtMost(1.1f)
    }

    val bodyPath = remember { Path() }
    val blobPath = remember { Path() }
    val points = remember { FloatArray(2 * (BLOB_POINTS + 1)) }

    /** Places [content] at the box [of] gives for the current size (nothing when it is null). */
    fun Modifier.at(of: (PopSpace) -> YtMiniBox?): Modifier = layout { measurable, constraints ->
        val s = spaceAt(Size(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat()))
        val box = of(s)
        val placeable = measurable.measure(
            Constraints.fixed(box?.width?.roundToInt()?.coerceAtLeast(0) ?: 0, box?.height?.roundToInt()?.coerceAtLeast(0) ?: 0),
        )
        layout(constraints.maxWidth, constraints.maxHeight) {
            if (box != null) placeable.place(box.left.roundToInt(), box.top.roundToInt())
        }
    }

    Box(Modifier.fillMaxSize().clearAndSetSemantics {}) {
        // 1: the pill, inflating into a balloon; its width follows the softer spring
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = YtPopMath.squash(wide.value, YtDock.peek)
                    transformOrigin = TransformOrigin(0.5f, 1f)
                }
                .drawBehind {
                    val s = spaceAt(size)
                    val w = size.width
                    val p = peekValue()
                    // a spring swinging past full size lifts the balloon's top above the box
                    val bodyHeight = if (peeking()) max(size.height, s.restBox + (s.peekBox - s.restBox) * p) else size.height
                    val top = size.height - bodyHeight
                    val grown = ((bodyHeight - s.restBox) / (s.peekBox - s.restBox).coerceAtLeast(1f)).coerceIn(0f, 1f)
                    val rest = look.radius * density
                    val topRadius = min(YtPopMath.lerp(rest, YtPopLayout.BALLOON_RADIUS * density, grown), min(w, bodyHeight) / 2f)
                    val footRadius = min(rest, min(w, bodyHeight) / 2f)
                    bodyPath.reset()
                    bodyPath.addRoundRect(
                        RoundRect(
                            left = 0f,
                            top = top,
                            right = w,
                            bottom = size.height,
                            topLeftCornerRadius = CornerRadius(topRadius),
                            topRightCornerRadius = CornerRadius(topRadius),
                            bottomRightCornerRadius = CornerRadius(footRadius),
                            bottomLeftCornerRadius = CornerRadius(footRadius),
                        ),
                    )
                    drawPath(bodyPath, band)
                    // the balloon's shine, high on the start side
                    val shine = if (peeking()) YtPopMath.smoothstep(0.3f, 0.9f, p) else 0f
                    if (shine > 0f) {
                        val sw = w * 0.2f
                        val sx = if (rtl) w - w * 0.07f - sw else w * 0.07f
                        drawOval(Color.White.copy(alpha = 0.55f * shine), Offset(sx, top + 9f * density), Size(sw, 10f * density))
                        val dot = sx + if (rtl) -16f * density else sw + 6f * density
                        drawOval(Color.White.copy(alpha = 0.4f * shine), Offset(dot, top + 11f * density), Size(10f * density, 7f * density))
                    }
                    // a dragged card comes near: the rim turns pink
                    val hover = scene.hover()
                    if (hover > 0f && !peeking()) drawPath(bodyPath, pink.copy(alpha = hover), style = Stroke(2.5f * density))
                    val r = reject.value
                    if (r > 0f) drawPath(bodyPath, pink.copy(alpha = 1f - r), style = Stroke(3f * density))
                },
        )

        // 2: the pink sticker shadow under the picture: slapped on with the landing, deeper in
        // the preview
        Box(
            Modifier
                .fillMaxSize()
                .drawBehind {
                    val s = spaceAt(size)
                    val p = peekValue().coerceIn(0f, 1f)
                    val box = if (peeking()) YtPopMath.lerp(s.restPlayer, s.peekPlayer, p) else s.restPlayer
                    val offset = YtPopMath.lerp(YtPopLayout.SHADOW * snap(shadowSnap.value), YtPopLayout.SHADOW_PEEK.toFloat(), p) * density
                    val alpha = docked()
                    if (alpha <= 0f || box.width <= 0f) return@drawBehind
                    drawRoundRect(
                        pink.copy(alpha = alpha),
                        topLeft = Offset(box.left + mirror * offset, box.top + offset),
                        size = Size(box.width, box.height),
                        cornerRadius = CornerRadius(min(look.spec.thumbRadius * density, box.height / 2f)),
                    )
                },
        )

        // 3: the docked row's text and the pink underline, which give way to the preview first
        Column(
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .at { it.restText }
                .graphicsLayer {
                    alpha = docked() * YtMiniParts.restFade(peekValue()) * (1f - label.value.coerceIn(0f, 1f))
                },
        ) {
            Text(text = title, style = look.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (channel.isNotBlank()) {
                Spacer(Modifier.height(1.dp))
                Text(text = channel, style = look.subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(6.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(look.barWidth.dp)
                    .drawBehind {
                        val r = CornerRadius(size.height / 2f)
                        drawRoundRect(look.track, cornerRadius = r)
                        val played = size.width * scene.info.fraction.coerceIn(0f, 1f)
                        if (played > 0f) {
                            val left = if (rtl) size.width - played else 0f
                            drawRoundRect(look.barColor, topLeft = Offset(left, 0f), size = Size(played, size.height), cornerRadius = r)
                        }
                    },
            )
        }

        // 4: the plum blob: behind play on the bar (opening its mouth for a card), the pill of
        // play and pause in the preview
        Box(
            Modifier
                .fillMaxSize()
                .drawBehind {
                    val s = spaceAt(size)
                    val t = pillPhase()
                    val box = YtPopMath.lerp(s.restBlob, s.peekPill, t)
                    if (box.width <= 0f || box.height <= 0f) return@drawBehind
                    val rest = (1f - t).coerceIn(0f, 1f)
                    val b = breath?.value ?: 0f
                    val g = gulp.value
                    val swell = 1f + 0.16f * YtPopMath.gulpSwell(g) * rest
                    val press = 1f + (pillPress - 1f) * t.coerceIn(0f, 1f)
                    val pop = snap(blobPop.value)
                    val scaleX = (1f + 0.05f * b * rest) * swell * press * pop
                    val scaleY = (1f - 0.03f * b * rest) * swell * press * pop
                    val cx = box.centerX
                    val cy = box.centerY

                    // the mouth: towards the dragged card, wide open as it gulps, shut to refuse
                    val hover = if (peeking()) 0f else scene.hover()
                    val r = reject.value
                    val mouth = if (r > 0f) 0f else max(YtPopMath.mouthFromHover(hover), YtPopMath.gulpMouth(g)) * rest
                    val finger = drop.pointer
                    val facing = if (finger != null) YtPopMath.mouthFacing(cx, cy, finger.x, finger.y) else -90f
                    // it leans a little towards the card
                    val lean = 3f * density * hover
                    val rad = facing * PI.toFloat() / 180f
                    val lx = cx + lean * kotlin.math.cos(rad)
                    val ly = cy + lean * kotlin.math.sin(rad)

                    val count = YtPopMath.blobPoints(
                        cx = lx,
                        cy = ly,
                        rx = box.width / 2f * scaleX,
                        ry = box.height / 2f * scaleY * (1f - 0.08f * r * (1f - r) * 4f),
                        n = 2.2f,
                        pill = YtPopMath.smoothstep(0f, 0.8f, t),
                        amp = YtPopMath.lerp(0.05f, 0.012f, t.coerceIn(0f, 1f)),
                        phase = 0.8f + 1.4f * b,
                        mouth = mouth,
                        facing = facing,
                        count = BLOB_POINTS,
                        out = points,
                    )
                    blobPath.reset()
                    blobPath.moveTo(points[0], points[1])
                    var i = 2
                    while (i + 1 < count) {
                        blobPath.lineTo(points[i], points[i + 1])
                        i += 2
                    }
                    blobPath.close()
                    val color = if (target == YtMiniTarget.PILL) lerp(plum, pink, 0.22f) else plum
                    // shaking its head: a damped turn either way
                    val turn = if (r > 0f) 16f * YtPopMath.wobble(r, 3f, 4.5f) else 0f
                    rotate(turn, Offset(cx, cy)) {
                        drawPath(blobPath, color.copy(alpha = docked()))
                    }
                },
        )

        // 5: the preview: the title, the pill's glyph and word, the next video's sticker
        Column(
            verticalArrangement = Arrangement.Top,
            modifier = Modifier
                .at { if (peeking()) it.peekTitle else null }
                .graphicsLayer {
                    val shown = YtMiniParts.peekFade(peekValue())
                    alpha = shown
                    translationY = 8f * density * (1f - shown)
                },
        ) {
            Text(
                text = title,
                style = TextStyle(color = PopColors.onBand, fontFamily = YtFonts.nunito, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp, lineHeight = 18.sp),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (channel.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(text = channel, style = look.subtitle.copy(fontSize = 12.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier
                .at { if (peeking()) it.peekPill else null }
                .graphicsLayer {
                    alpha = YtPopMath.smoothstep(0.5f, 0.9f, peekValue())
                    val press = pillPress
                    scaleX = press
                    scaleY = press
                }
                .padding(horizontal = 12.dp),
        ) {
            YtMiniParts.Glyph(playing, palette.onAccent, Modifier.size(11.dp))
            Spacer(Modifier.width(7.dp))
            Text(
                text = stringResource(if (playing) R.string.yt_mini_peek_playing else R.string.yt_mini_peek_paused),
                style = TextStyle(color = palette.onAccent, fontFamily = YtFonts.nunito, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (next != null) {
            NextSticker(
                next = next,
                pink = pink,
                yellow = yellow,
                modifier = Modifier
                    .at { if (peeking()) it.peekNext else null }
                    .graphicsLayer {
                        val v = slap.value
                        val scale = YtPopMath.slapScale(v) * (1f + 0.04f * lifted)
                        scaleX = scale
                        scaleY = scale
                        rotationZ = mirror * YtPopMath.slapTurn(v, lifted)
                        alpha = YtPopMath.slapAlpha(v) * YtPopMath.smoothstep(0.3f, 0.6f, peekValue())
                    },
                lifted = { lifted },
            )
        }

        // 6: the dropped card, sucked into the open mouth (a few frames, then it is gone)
        Box(
            Modifier
                .layout { measurable, constraints ->
                    val w = (GHOST_WIDTH * density).roundToInt()
                    val h = (GHOST_HEIGHT * density).roundToInt()
                    val placeable = measurable.measure(Constraints.fixed(w, h))
                    layout(constraints.maxWidth, constraints.maxHeight) {
                        val g = gulp.value
                        if (YtPopMath.inFlight(g)) {
                            val s = spaceAt(Size(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat()))
                            val to = Offset(s.restBlob.centerX, s.restBlob.centerY)
                            val from = drop.pointer ?: Offset(to.x, to.y - 56f * density)
                            val f = YtPopMath.gulpFlight(g)
                            val x = YtPopMath.lerp(from.x, to.x, f)
                            val y = YtPopMath.lerp(from.y, to.y, f)
                            placeable.place((x - w / 2f).roundToInt(), (y - h / 2f).roundToInt())
                        }
                    }
                }
                .graphicsLayer {
                    val g = gulp.value
                    val f = YtPopMath.gulpFlight(g)
                    val scale = YtPopMath.lerp(1f, 0.15f, f)
                    scaleX = scale
                    scaleY = scale
                    rotationZ = mirror * -30f * f
                    alpha = if (YtPopMath.inFlight(g)) 1f else 0f
                }
                .clip(RoundedCornerShape(9.dp))
                .background(PopColors.white)
                .padding(2.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(pink),
        ) {
            val thumbnail = drop.thumbnail
            if (thumbnail != null) {
                AsyncImage(
                    model = thumbnail,
                    contentDescription = null,
                    imageLoader = ImageHelper.loader(),
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        // 7: the drop's label as a yellow sticker slapped over the text
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .at { it.restText }
                .graphicsLayer {
                    val v = label.value
                    alpha = v.coerceIn(0f, 1f)
                    val scale = 0.5f + 0.5f * v
                    scaleX = scale
                    scaleY = scale
                    rotationZ = mirror * (-4f - 10f * (1f - v))
                },
        ) {
            val result = YtDock.lastDrop
            if (result != null) {
                Text(
                    text = if (result.next) stringResource(R.string.yt_mini_drop_next) else stringResource(R.string.yt_mini_drop_end, result.position),
                    style = TextStyle(color = PopColors.plum, fontFamily = YtFonts.nunito, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .drawBehind {
                            val o = 3f * density
                            drawRoundRect(pink, topLeft = Offset(mirror * o, o), size = size, cornerRadius = CornerRadius(12f * density))
                        }
                        .clip(RoundedCornerShape(12.dp))
                        .background(PopColors.white)
                        .padding(2.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(yellow)
                        .padding(horizontal = 12.dp, vertical = 5.dp),
                )
            }
        }
    }
}

private const val BLOB_POINTS = 56
private const val GHOST_WIDTH = 44
private const val GHOST_HEIGHT = 26

/**
 * The next video as a yellow sticker with a white border on a pink shadow (deeper while the
 * finger is on it, [lifted]): its picture, "ALS NÄCHSTES" in pink, its title in plum.
 */
@Composable
private fun NextSticker(next: YtMiniNext, pink: Color, yellow: Color, modifier: Modifier, lifted: () -> Float) {
    val density = LocalDensity.current.density
    val mirror = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1f else 1f
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .drawBehind {
                val o = (4f + 3f * lifted()) * density
                drawRoundRect(pink, topLeft = Offset(mirror * o, o), size = size, cornerRadius = CornerRadius(18f * density))
            }
            .clip(RoundedCornerShape(18.dp))
            .background(PopColors.white)
            .padding(3.dp)
            .clip(RoundedCornerShape(15.dp))
            .background(yellow)
            .padding(5.dp),
    ) {
        AsyncImage(
            model = next.thumbnail,
            contentDescription = null,
            imageLoader = ImageHelper.loader(),
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxHeight()
                .width(70.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(PopColors.plum.copy(alpha = 0.14f)),
        )
        Spacer(Modifier.width(9.dp))
        Column(verticalArrangement = Arrangement.Center) {
            Text(
                text = stringResource(R.string.yt_mini_peek_next).uppercase(),
                style = TextStyle(color = pink, fontFamily = YtFonts.nunito, fontWeight = FontWeight.Black, fontSize = 10.sp, letterSpacing = 0.08.em),
                maxLines = 1,
            )
            Text(
                text = next.title,
                style = TextStyle(color = PopColors.plum, fontFamily = YtFonts.nunito, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp, lineHeight = 15.sp),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
