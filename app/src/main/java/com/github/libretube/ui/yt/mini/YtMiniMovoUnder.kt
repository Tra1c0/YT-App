package com.github.libretube.ui.yt.mini

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
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
import com.github.libretube.helpers.ImageHelper
import com.github.libretube.ui.yt.LocalYtMotion
import com.github.libretube.ui.yt.YtDock
import com.github.libretube.ui.yt.YtFonts
import com.github.libretube.ui.yt.ytSpec
import com.github.libretube.util.PlayingQueue
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/*
 * YT: what the Movo player draws behind its picture: the dark body joined to the bar, the mosaic
 * of tiles (the pink tile turned behind the picture, the cobalt play tile hidden under it, the
 * lime disc behind play), title, channel and the cobalt underline. In the preview the same tiles
 * move into the mosaic, so nothing appears from nowhere: the pink tile flips up as the next
 * video, the cobalt tile slides out from under the picture as play and pause, the disc becomes
 * the queue's count. Every moving value is read while laying out or drawing.
 */

/** The tilt under the finger, shared by the body (tiles) and the overlay (shading on the picture). */
internal object YtMovoShared {
    val tiltX = Animatable(0f)
    val tiltY = Animatable(0f)
}

/** Where the parts sit in the composable's current size (its foot on the bar), in px, as drawn. */
private class MovoSpace(private val design: YtMiniMovo, private val density: Float) {
    private var key: List<Any>? = null
    var height = 0f
    var restPlayer = YtMiniBox(0f, 0f, 0f, 0f)
    var restBacker = restPlayer
    var restDisc = restPlayer
    var restText = restPlayer
    var peekPlayer = restPlayer
    var peekNext: YtMiniBox? = null
    var peekPill = restPlayer
    var peekCount = restPlayer

    /** The docked picture as laid out (for the tilt), set with the first size. */
    var tile: YtMiniBox? = null
    var rtl = false

    fun at(size: Size, rtl: Boolean, hasNext: Boolean): MovoSpace {
        val k = listOf(size.width, size.height, rtl, hasNext)
        if (k == key) return this
        key = k
        this.rtl = rtl
        val w = size.width
        val h = size.height
        height = h
        val g = design.geometry(w, density)
        val p = YtMovoLayout.peek(design.spec, w, density, hasNext)
        fun rest(box: YtMiniBox) = YtMovoLayout.fromFoot(YtMiniLayout.directed(box, w, rtl), g.boxHeight, h)
        fun peek(box: YtMiniBox) = YtMovoLayout.fromFoot(YtMiniLayout.directed(box, w, rtl), p.frame.boxHeight, h)
        restPlayer = rest(g.player)
        restBacker = rest(YtMovoLayout.backer(g.player, density))
        restDisc = rest(YtMovoLayout.disc(g.play ?: p.count, density))
        restText = rest(g.text)
        peekPlayer = peek(p.frame.player)
        peekNext = p.frame.next?.let { peek(it) }
        peekPill = peek(p.frame.pill)
        peekCount = peek(p.count)
        // the tilt: the picture at rest, in the box's coordinates (the finger's)
        tile = YtMiniLayout.directed(g.player, w, rtl)
        return this
    }
}

/** A tile's rectangle with its corner, drawn by [drawTile]. */
private fun DrawScope.drawTile(box: YtMiniBox, corner: Float, color: Color, outline: Color? = null) {
    if (box.width <= 0f || box.height <= 0f || color.alpha <= 0f) return
    val r = min(corner, min(box.width, box.height) / 2f)
    drawRoundRect(color, Offset(box.left, box.top), Size(box.width, box.height), CornerRadius(r))
    if (outline != null) {
        val s = 2.dp.toPx()
        drawRoundRect(
            outline,
            Offset(box.left + s / 2f, box.top + s / 2f),
            Size(box.width - s, box.height - s),
            CornerRadius((r - s / 2f).coerceAtLeast(0f)),
            style = Stroke(s),
        )
    }
}

/** The part of the drop's shuffle at accept [t]: from 0.4 on, over the rest. */
private fun shuffleOf(t: Float): Float = YtMovoMath.shuffle(((t - 0.4f) / 0.6f).coerceIn(0f, 1f))

@Composable
internal fun MovoUnder(design: YtMiniMovo, scene: YtMiniScene) {
    val palette = scene.palette ?: return
    val look = remember(palette) { design.look(palette) }
    val density = LocalDensity.current.density
    val motion = LocalYtMotion.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val space = remember(density) { MovoSpace(design, density) }
    val mirror = if (rtl) -1f else 1f

    val cobalt = palette.accent
    val pink = palette.pink
    val lime = palette.lime
    val ink = palette.ink

    // the landing: the tiles snap in one after the other (with the two clicks of the dock)
    val tileSnap = remember { Animatable(1f) }
    val discSnap = remember { Animatable(1f) }
    val textSnap = remember { Animatable(1f) }
    val landings = scene.landings
    var seenLanding by remember { mutableIntStateOf(landings) }
    LaunchedEffect(landings) {
        if (landings == seenLanding) return@LaunchedEffect
        if (!motion) {
            seenLanding = landings
            return@LaunchedEffect
        }
        tileSnap.snapTo(0f)
        discSnap.snapTo(0f)
        textSnap.snapTo(0f)
        seenLanding = landings
        launch { tileSnap.animateTo(1f, spring(dampingRatio = 0.42f, stiffness = 520f)) }
        launch {
            delay(60)
            discSnap.animateTo(1f, spring(dampingRatio = 0.38f, stiffness = 620f))
        }
        launch {
            delay(40)
            textSnap.animateTo(1f, spring(dampingRatio = 0.72f, stiffness = 380f))
        }
    }
    // until the landing has started, the tiles wait (no frame of them at rest before it)
    fun snap(value: Float): Float = if (scene.landings != seenLanding) 0f else value

    // the tilt under the finger: the pressed side gives way, it springs back flat when let go
    LaunchedEffect(scene, motion) {
        if (!motion) {
            YtMovoShared.tiltX.snapTo(0f)
            YtMovoShared.tiltY.snapTo(0f)
            return@LaunchedEffect
        }
        snapshotFlow { scene.finger }.collectLatest { finger ->
            val tile = space.tile
            val target = if (finger != null && tile != null && YtDock.peek <= 0f && YtDock.progress >= 1f) {
                YtMovoMath.tilt(finger.x, finger.y, tile, YtMovoLayout.TILT_REACH * density, YtMovoLayout.MAX_TILT)
            } else {
                YtMovoTilt.FLAT
            }
            val spec = if (finger != null) spring<Float>(dampingRatio = 0.8f, stiffness = 700f) else spring<Float>(dampingRatio = 0.5f, stiffness = 320f)
            coroutineScope {
                launch { YtMovoShared.tiltX.animateTo(target.x, spec) }
                launch { YtMovoShared.tiltY.animateTo(target.y, spec) }
            }
        }
    }

    // a dropped video: a small tile falls into the mosaic, the tiles shuffle once, the label
    val accept = remember { Animatable(0f) }
    val label = remember { Animatable(0f) }
    val accepted = YtDock.accepted
    var seenDrop by remember { mutableIntStateOf(accepted) }
    LaunchedEffect(accepted) {
        if (accepted == seenDrop) return@LaunchedEffect
        seenDrop = accepted
        if (motion) {
            launch {
                accept.snapTo(0f)
                accept.animateTo(1f, tween(900, easing = LinearEasing))
                accept.snapTo(0f)
            }
            delay(420)
            label.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = 520f))
            delay(1400)
            label.animateTo(0f, tween(200, easing = LinearEasing))
        } else {
            label.snapTo(1f)
            delay(1400)
            label.snapTo(0f)
        }
    }

    // a drop the player did not take: the body's rim flashes pink
    val reject = remember { Animatable(0f) }
    val rejects = scene.rejects
    var seenReject by remember { mutableIntStateOf(rejects) }
    LaunchedEffect(rejects) {
        if (rejects == seenReject) return@LaunchedEffect
        seenReject = rejects
        reject.snapTo(1f)
        if (motion) reject.animateTo(0f, tween(520, easing = LinearEasing)) else {
            delay(400)
            reject.snapTo(0f)
        }
    }

    // the finger over a part of the preview: that tile swells a little
    val target = scene.target
    val nextPress by animateFloatAsState(
        if (target == YtMiniTarget.NEXT) 1.04f else 1f,
        ytSpec(spring<Float>(dampingRatio = 0.5f, stiffness = 600f)),
        label = "movoNext",
    )
    val pillPress by animateFloatAsState(
        if (target == YtMiniTarget.PILL) 1.05f else 1f,
        ytSpec(spring<Float>(dampingRatio = 0.5f, stiffness = 600f)),
        label = "movoPill",
    )

    // what changes rarely (not the position, which the poll renews every time)
    val text by remember { derivedStateOf { scene.info.let { Triple(it.title, it.channel, it.playing) } } }
    val (title, channel, playing) = text

    val revision by PlayingQueue.revision.collectAsState()
    val upcoming = remember(revision) { PlayingQueue.upcomingCount() }
    val next = scene.next
    // the layout the host chose when the preview started; between previews the current next
    val hasNext = { scene.peekLayout?.let { it.next != null } ?: (scene.next != null) }

    fun spaceAt(size: Size) = space.at(size, rtl, hasNext())
    fun peekValue() = YtDock.peek.coerceIn(0f, 1.15f)

    /** The tiles show once the player sits on the bar (they snap in with the landing). */
    fun docked() = YtMovoMath.smoothstep(0.9f, 1f, YtDock.progress)

    // the pink tile: turned behind the picture; in the preview it flips up as the next video
    // (without one it stays behind the grown picture)
    fun pinkBox(s: MovoSpace): YtMiniBox {
        val t = YtMovoMath.phase(peekValue(), 0.15f)
        val to = s.peekNext ?: YtMovoLayout.backer(s.peekPlayer, density).let { if (s.rtl) it.offset(-2f * YtMovoLayout.BACKER_DX * density, 0f) else it }
        return YtMovoMath.lerp(s.restBacker, to, t)
    }

    fun pinkTurn(s: MovoSpace): Float {
        val t = if (s.peekNext != null) YtMovoMath.phase(peekValue(), 0.15f).coerceAtMost(1f) else 0f
        val hover = -6f * scene.hover() * (1f - t)
        val landing = -12f * (1f - snap(tileSnap.value))
        return mirror * (YtMovoLayout.BACKER_TURN * (1f - t) + hover + landing + shuffleOf(accept.value))
    }

    /** The flip up of the preview's tiles: −8° to flat, past it with the spring, and back. */
    fun flip(t: Float): Float = YtMovoLayout.PEEK_FLIP * (1f - t) * YtMovoMath.smoothstep(0f, 0.15f, t)

    // the cobalt tile: hidden under the picture at rest, it slides out as play and pause
    fun cobaltBox(s: MovoSpace): YtMiniBox = YtMovoMath.lerp(s.restPlayer, s.peekPill, YtMovoMath.phase(peekValue(), 0.05f))

    // the lime disc behind play, the queue's count in the preview
    fun limeBox(s: MovoSpace): YtMiniBox = YtMovoMath.lerp(s.restDisc, s.peekCount, YtMovoMath.phase(peekValue(), 0.3f))

    fun limeCorner(t: Float): Float =
        YtMovoMath.lerp(YtMovoLayout.DISC / 2f, YtMovoLayout.SMALL_CORNER.toFloat(), t.coerceIn(0f, 1f)) * density

    /** A full-size layer that turns, flips and tilts around the centre of [box]. */
    fun androidx.compose.ui.graphics.GraphicsLayerScope.tileLayer(box: YtMiniBox, turn: Float, flip: Float, tilt: Boolean, scale: Float) {
        if (size.width <= 0f || size.height <= 0f) return
        transformOrigin = TransformOrigin(box.centerX / size.width, box.centerY / size.height)
        rotationZ = turn
        rotationX = flip + if (tilt) YtMovoShared.tiltX.value else 0f
        rotationY = if (tilt) YtMovoShared.tiltY.value else 0f
        scaleX = scale
        scaleY = scale
        cameraDistance = 14f * density
    }

    /** Places [content] at the box [of] gives for the current size (nothing when it is null). */
    fun Modifier.at(of: (MovoSpace) -> YtMiniBox?): Modifier = layout { measurable, constraints ->
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
        // 1: the body, one surface with the bar; a dragged video lights its rim lime
        Box(
            Modifier
                .fillMaxSize()
                .drawBehind {
                    val body = design.bodyPath(look, size.width, size.height, density, open = false, grow = 0f)
                    drawPath(body, look.fill)
                    val rim = design.bodyPath(look, size.width, size.height, density, open = true, grow = 0f)
                    look.border?.let { drawPath(rim, it, style = Stroke(look.borderWidth * density)) }
                    val hover = scene.hover()
                    if (hover > 0f && YtDock.peek <= 0f) drawPath(rim, lime.copy(alpha = hover), style = Stroke(2f * density))
                    val r = reject.value
                    if (r > 0f) drawPath(rim, pink.copy(alpha = r), style = Stroke(2.5f * density))
                },
        )

        // 2: the pink tile, with the next video on it in the preview
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val s = spaceAt(size)
                    val box = pinkBox(s)
                    val t = YtMovoMath.phase(peekValue(), 0.15f)
                    val lift = 1f + 0.05f * scene.hover() * (1f - t.coerceAtMost(1f))
                    val land = 0.7f + 0.3f * snap(tileSnap.value)
                    val press = if (s.peekNext != null) 1f + (nextPress - 1f) * t.coerceIn(0f, 1f) else 1f
                    tileLayer(box, pinkTurn(s), if (s.peekNext != null) flip(t) else 0f, tilt = true, scale = lift * land * press)
                    alpha = docked()
                }
                .drawBehind {
                    val s = spaceAt(size)
                    val active = target == YtMiniTarget.NEXT
                    drawTile(pinkBox(s), YtMovoLayout.TILE_CORNER * density, pink, if (active) ink else null)
                },
        ) {
            if (next != null) {
                NextContent(
                    next = next,
                    ink = ink,
                    modifier = Modifier
                        .at { it.peekNext }
                        .graphicsLayer { alpha = YtMovoMath.smoothstep(0.55f, 0.9f, peekValue()) },
                )
            }
        }

        // 3: the cobalt tile: play and pause in the preview
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val s = spaceAt(size)
                    val t = YtMovoMath.phase(peekValue(), 0.05f)
                    val press = 1f + (pillPress - 1f) * t.coerceIn(0f, 1f)
                    tileLayer(cobaltBox(s), 0f, flip(t), tilt = false, scale = press)
                    // under the picture at rest: nothing of it shows until the preview starts
                    alpha = if (YtDock.peek > 0f) 1f else 0f
                }
                .drawBehind {
                    val s = spaceAt(size)
                    val active = target == YtMiniTarget.PILL
                    drawTile(cobaltBox(s), YtMovoLayout.TILE_CORNER * density, if (active) lerp(cobalt, Color.White, 0.12f) else cobalt, if (active) Color.White else null)
                },
        ) {
            PlayContent(
                playing = playing,
                title = title,
                modifier = Modifier
                    .at { it.peekPill }
                    .graphicsLayer { alpha = YtMovoMath.smoothstep(0.5f, 0.9f, peekValue()) },
            )
        }

        // 4: the lime disc, the queue's count in the preview
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val s = spaceAt(size)
                    val t = YtMovoMath.phase(peekValue(), 0.3f)
                    val a = accept.value
                    val hop = if (a > 0.45f && a < 0.75f) -6f * density * sin(PI.toFloat() * (a - 0.45f) / 0.3f) else 0f
                    val scale = snap(discSnap.value) * (1f + 0.1f * scene.hover() * (1f - t.coerceAtMost(1f)))
                    tileLayer(limeBox(s), 0f, flip(t), tilt = true, scale = scale)
                    translationY = hop
                    alpha = docked()
                }
                .drawBehind {
                    val s = spaceAt(size)
                    val t = YtMovoMath.phase(peekValue(), 0.3f)
                    drawTile(limeBox(s), limeCorner(t), lime)
                },
        ) {
            Box(
                contentAlignment = Alignment.CenterStart,
                modifier = Modifier
                    .at { it.peekCount }
                    .graphicsLayer { alpha = YtMovoMath.smoothstep(0.65f, 0.95f, peekValue()) }
                    .padding(horizontal = 10.dp),
            ) {
                Text(
                    text = stringResource(R.string.yt_mini_movo_queue, upcoming),
                    style = TextStyle(color = ink, fontFamily = YtFonts.geist, fontWeight = FontWeight.Bold, fontSize = 12.sp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        // 5: the docked row's text, which gives way to the preview first
        Column(
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .at { it.restText }
                .graphicsLayer {
                    val shown = snap(textSnap.value)
                    alpha = docked() * YtMiniParts.restFade(YtDock.peek) * shown.coerceIn(0f, 1f) * (1f - label.value.coerceIn(0f, 1f))
                    translationX = mirror * 12f * density * (1f - shown)
                },
        ) {
            Text(text = title, style = look.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (channel.isNotBlank()) {
                Spacer(Modifier.height(1.dp))
                Text(text = channel, style = look.subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(6.dp))
            // the cobalt underline: drawn, so the position only redraws it
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

        // 6: the dropped video as a small lavender tile falling into the mosaic
        Box(
            Modifier
                .fillMaxSize()
                .drawBehind {
                    val a = accept.value
                    if (a <= 0f || a >= 0.6f) return@drawBehind
                    val s = spaceAt(size)
                    val side = 22f * density
                    val home = s.restBacker
                    val cx = if (s.rtl) home.left + side else home.right - side
                    val fall = (a / 0.4f).coerceIn(0f, 1f)
                    val merge = ((a - 0.4f) / 0.2f).coerceIn(0f, 1f)
                    val cy = YtMovoMath.lerp(-side, home.top + side / 2f, fall * fall)
                    val half = side / 2f * (1f - merge)
                    if (half <= 0f) return@drawBehind
                    drawTile(YtMiniBox(cx - half, cy - half, cx + half, cy + half), 6f * density, palette.lavender)
                },
        )

        // 7: the drop's label as a lime tile over the text
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .at { it.restText }
                .graphicsLayer {
                    val v = label.value
                    alpha = v.coerceIn(0f, 1f)
                    scaleX = 0.6f + 0.4f * v
                    scaleY = 0.6f + 0.4f * v
                    rotationZ = mirror * -6f * (1f - v)
                },
        ) {
            val drop = YtDock.lastDrop
            if (drop != null) {
                Text(
                    text = if (drop.next) stringResource(R.string.yt_mini_drop_next) else stringResource(R.string.yt_mini_drop_end, drop.position),
                    style = TextStyle(color = ink, fontFamily = YtFonts.geist, fontWeight = FontWeight.Bold, fontSize = 12.sp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .clip(RoundedCornerShape(YtMovoLayout.SMALL_CORNER.dp))
                        .background(lime)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
    }
}

/** The next video on the pink tile: its picture, "ALS NÄCHSTES", its title in black Geist. */
@Composable
private fun NextContent(next: YtMiniNext, ink: Color, modifier: Modifier) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier.padding(6.dp)) {
        AsyncImage(
            model = next.thumbnail,
            contentDescription = null,
            imageLoader = ImageHelper.loader(),
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxHeight()
                .width(92.dp)
                .clip(RoundedCornerShape(YtMovoLayout.SMALL_CORNER.dp))
                .background(ink.copy(alpha = 0.16f)),
        )
        Spacer(Modifier.width(10.dp))
        Column(verticalArrangement = Arrangement.Center) {
            Text(
                text = stringResource(R.string.yt_mini_peek_next).uppercase(),
                style = TextStyle(color = ink.copy(alpha = 0.72f), fontFamily = YtFonts.geist, fontWeight = FontWeight.Bold, fontSize = 10.sp, letterSpacing = 0.1.em),
                maxLines = 1,
            )
            Text(
                text = next.title,
                style = TextStyle(color = ink, fontFamily = YtFonts.geist, fontWeight = FontWeight.Bold, fontSize = 14.sp, lineHeight = 15.sp),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Play and pause on the cobalt tile: the glyph and its word, the current title under it. */
@Composable
private fun PlayContent(playing: Boolean, title: String, modifier: Modifier) {
    Column(verticalArrangement = Arrangement.Center, modifier = modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            YtMiniParts.Glyph(playing, Color.White, Modifier.size(10.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                text = stringResource(if (playing) R.string.yt_mini_peek_playing else R.string.yt_mini_peek_paused),
                style = TextStyle(color = Color.White.copy(alpha = 0.8f), fontFamily = YtFonts.geist, fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = 0.04.em),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = title,
            style = TextStyle(color = Color.White, fontFamily = YtFonts.geist, fontWeight = FontWeight.Bold, fontSize = 13.sp, lineHeight = 15.sp),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
