package com.github.libretube.ui.yt

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.libretube.R
import com.github.libretube.api.obj.StreamItem
import com.github.libretube.ui.yt.feed.YtBox
import com.github.libretube.ui.yt.feed.YtDragRules
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/*
 * YT: the search icon held and dragged onto a home card searches for that card's channel by
 * name (never opening the channel). A long press lifts the icon as a lens in the design's
 * material; the cards publish where they are while the lens is out (ytDropTarget), the card
 * under it lights up its channel name, and each new card is felt as a tick. Let go over a
 * card, the search runs as if the name had been typed; let go elsewhere, the lens springs back
 * into its icon. A tap still opens the search as before.
 */

/** The lens on its way. */
@Stable
internal class YtSearchGhost(
    val palette: YtPalette,
    /** The search icon it came from, in window px. */
    val origin: Rect,
    /** The finger when it was lifted, in window px. */
    val grab: Offset,
    val motion: Boolean,
    /** The button it was lifted from: only that one fades while the lens is out. */
    val source: Any,
) {
    /** 0: the size and place of the icon, 1: the lens under the finger. */
    val grow = Animatable(0f)
    val hover = Animatable(0f)
    val sink = Animatable(0f)
}

/** The search drag across the screens; the source calls begin, move and end. */
object YtSearchDrag {
    private var next = 0

    internal var current by mutableStateOf<YtSearchGhost?>(null)
        private set

    /** A key for a new drop target. */
    internal fun newKey(): String = "card-" + next++

    internal fun begin(scope: CoroutineScope, palette: YtPalette, origin: Rect, finger: Offset, motion: Boolean, source: Any): Boolean {
        // a ghost left from a drag the layer ended (its activity went away) does not count
        if (YtDrag.session != null) return false
        val ghost = YtSearchGhost(palette, origin, finger, motion, source)
        current = ghost
        YtDrag.hoverKey = null
        YtDrag.ghost = { _ -> SearchLens(ghost) }
        YtDrag.session = YtDragSession(YtDragKind.SEARCH, null, origin, finger)
        scope.launch {
            if (motion) ghost.grow.animateTo(1f, spring(dampingRatio = 0.6f, stiffness = 500f)) else ghost.grow.snapTo(1f)
        }
        YtTestHooks.log("YtSearchDrag") { "lift" }
        return true
    }

    internal fun move(scope: CoroutineScope, finger: Offset, haptics: YtHaptics) {
        val session = YtDrag.session ?: return
        val ghost = current ?: return
        if (session.kind != YtDragKind.SEARCH) return
        session.pointer = finger
        val boxes = YtDrag.cards.mapValues { (_, card) ->
            YtBox(card.bounds.left, card.bounds.top, card.bounds.right, card.bounds.bottom)
        }
        val key = YtDragRules.cardAt(finger.x, finger.y, boxes)
        if (key == YtDrag.hoverKey) return
        val was = YtDrag.hoverKey
        YtDrag.hoverKey = key
        // a tick for every new card the lens comes over
        if (key != null) haptics.detent() else if (was != null) haptics.threshold(false)
        YtTestHooks.log("YtSearchDrag") { "hover=${key?.let { YtDrag.cards[it]?.item?.uploaderName }}" }
        scope.launch {
            val to = if (key != null) 1f else 0f
            if (ghost.motion) ghost.hover.animateTo(to, spring(dampingRatio = 0.5f, stiffness = 600f)) else ghost.hover.snapTo(to)
        }
    }

    internal fun end(scope: CoroutineScope, haptics: YtHaptics, cancelled: Boolean) {
        val ghost = current ?: return
        val session = YtDrag.session
        val card = if (cancelled) null else YtDrag.hoverKey?.let { YtDrag.cards[it] }
        val name = YtDragRules.searchName(card?.item?.uploaderName)
        val search = YtChrome.searchFor
        YtDrag.hoverKey = null
        if (card != null && name != null && search != null) {
            haptics.land(0.4f)
            YtTestHooks.log("YtSearchDrag") { "search name=$name" }
            search(name)
            settle(scope, ghost) { ghost.sink.animateTo(1f, tween(220)) }
        } else {
            if (card != null) haptics.reject() else haptics.release()
            session?.pointer = ghost.grab
            YtTestHooks.log("YtSearchDrag") { "back" }
            settle(scope, ghost) {
                launch { ghost.hover.animateTo(0f, spring(dampingRatio = 1f, stiffness = 600f)) }
                ghost.grow.animateTo(0f, spring(dampingRatio = 0.75f, stiffness = 420f))
            }
        }
    }

    private fun settle(scope: CoroutineScope, ghost: YtSearchGhost, animation: suspend CoroutineScope.() -> Unit) {
        if (!ghost.motion || !scope.isActive) {
            finish(ghost)
            return
        }
        scope.launch {
            try {
                this.animation()
            } finally {
                finish(ghost)
            }
        }
    }

    private fun finish(ghost: YtSearchGhost) {
        if (current !== ghost) return
        current = null
        if (YtDrag.session?.kind == YtDragKind.SEARCH) {
            YtDrag.session = null
            YtDrag.ghost = null
        }
        YtDrag.hoverKey = null
    }
}

/**
 * Lets a search button be held and dragged onto a card (see above). Put it before the button's
 * click: a tap still reaches the click, a long press takes the finger. [label] names the button
 * for TalkBack when it has no text of its own.
 */
@Composable
fun Modifier.ytSearchDragSource(palette: YtPalette, label: String? = null): Modifier {
    val described = if (label != null) this.semantics { contentDescription = label } else this
    val switchedOn = remember { YtHomeDragPrefs.enabled }
    if (!switchedOn) return described
    val motion = LocalYtMotion.current
    val haptics = rememberYtHaptics()
    val scope = rememberCoroutineScope()
    val holder = remember { YtCoords() }
    val gesture = Modifier.pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            if (YtDrag.session != null) return@awaitEachGesture
            val hold = awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
            val coords = holder.coords?.takeIf { it.isAttached } ?: return@awaitEachGesture
            hold.consume()
            haptics.grab()
            val started = YtSearchDrag.begin(scope, palette, coords.boundsInWindow(), coords.localToWindow(hold.position), motion, holder)
            var released = false
            try {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == hold.id }
                    // the lens owns the finger: the button's click must not fire on letting go
                    event.changes.forEach { it.consume() }
                    if (change == null || !change.pressed) {
                        released = change != null
                        break
                    }
                    if (started && coords.isAttached) YtSearchDrag.move(scope, coords.localToWindow(change.position), haptics)
                }
            } finally {
                if (started) YtSearchDrag.end(scope, haptics, cancelled = !released)
            }
        }
    }
    return described
        .onGloballyPositioned { holder.coords = it }
        .then(gesture)
        // the icon stays as a faint place while its lens is out
        .graphicsLayer {
            val lens = YtSearchDrag.current?.takeIf { it.source === holder }
            alpha = 1f - 0.6f * (lens?.grow?.value?.coerceIn(0f, 1f) ?: 0f)
        }
}

/**
 * Lets a home card take the search lens (see above). Put it on the card's root with [shape]
 * the card's shape and [inset] the room between the modifier's bounds and the visible card.
 */
@Composable
fun Modifier.ytDropTarget(
    palette: YtPalette,
    video: StreamItem,
    shape: Shape = ytCardShape(palette),
    inset: PaddingValues = PaddingValues(0.dp),
): Modifier {
    val switchedOn = remember { YtHomeDragPrefs.enabled }
    if (!switchedOn) return this
    val key = remember { YtSearchDrag.newKey() }
    val holder = remember { YtCoords() }
    val current by rememberUpdatedState(video)
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val searching by remember { derivedStateOf { YtDrag.session?.kind == YtDragKind.SEARCH } }
    val hovered by remember { derivedStateOf { YtDrag.hoverKey == key } }
    val glow = remember { Animatable(0f) }
    val motion = LocalYtMotion.current

    fun publish() {
        val coords = holder.coords?.takeIf { it.isAttached } ?: return
        YtDrag.cards[key] = YtDropCard(cardBounds(coords, inset, density, layoutDirection), current)
    }

    // only while a lens is out: the cards cost nothing the rest of the time
    LaunchedEffect(searching) {
        if (searching) publish() else YtDrag.cards.remove(key)
    }
    DisposableEffect(key) {
        onDispose { YtDrag.cards.remove(key) }
    }
    LaunchedEffect(hovered) {
        val to = if (hovered) 1f else 0f
        if (motion) glow.animateTo(to, spring(dampingRatio = 0.6f, stiffness = 520f)) else glow.snapTo(to)
    }

    val name = YtDragRules.searchName(video.uploaderName)
    val label = name?.let { stringResource(R.string.yt_home_search_channel, it) }
    val measurer = rememberTextMeasurer()
    val outlineShape = remember(shape, inset) { YtInsetShape(shape, inset) }
    val style = remember(palette) {
        TextStyle(fontFamily = palette.font, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = palette.onAccent)
    }

    return this
        .onGloballyPositioned {
            holder.coords = it
            if (YtDrag.session?.kind == YtDragKind.SEARCH) publish()
        }
        .drawWithCache {
            val outline = outlineShape.createOutline(size, layoutDirection, this)
            val left = inset.calculateLeftPadding(layoutDirection).toPx()
            val top = inset.calculateTopPadding().toPx()
            val right = inset.calculateRightPadding(layoutDirection).toPx()
            val pad = 10.dp.toPx()
            val padX = 12.dp.toPx()
            val padY = 6.dp.toPx()
            val room = (size.width - left - right - 2 * pad - 2 * padX).toInt().coerceAtLeast(1)
            val text = label?.let {
                measurer.measure(it, style, overflow = TextOverflow.Ellipsis, maxLines = 1, constraints = Constraints(maxWidth = room))
            }
            onDrawWithContent {
                drawContent()
                val k = glow.value
                if (k <= 0.01f) return@onDrawWithContent
                val a = k.coerceIn(0f, 1f)
                drawOutline(outline, palette.accent, alpha = a, style = Stroke(2.dp.toPx() * a))
                if (text != null) {
                    // the channel name rises into a pill at the top of the card
                    val w = text.size.width + 2 * padX
                    val h = text.size.height + 2 * padY
                    val at = Offset(left + pad, top + pad + (1f - a) * 6.dp.toPx())
                    drawRoundRect(palette.accent, at, Size(w, h), CornerRadius(h / 2f), alpha = a)
                    drawText(text, topLeft = Offset(at.x + padX, at.y + padY), alpha = a)
                }
            }
        }
}

private fun cardBounds(coords: LayoutCoordinates, inset: PaddingValues, density: Density, dir: androidx.compose.ui.unit.LayoutDirection): Rect {
    val box = coords.boundsInWindow()
    return with(density) {
        Rect(
            box.left + inset.calculateLeftPadding(dir).toPx(),
            box.top + inset.calculateTopPadding().toPx(),
            box.right - inset.calculateRightPadding(dir).toPx(),
            box.bottom - inset.calculateBottomPadding().toPx(),
        )
    }
}

// ---------------------------------------------------------------------------------------------
// The lens per design

private class YtLensLook(
    val shape: Shape,
    val fill: Brush,
    val rim: Color?,
    val rimWidth: Dp,
    val tint: Color,
    /** Opaque lenses cast a shadow; glass ones do not, it would show through. */
    val shadow: Boolean,
)

private fun lensLook(palette: YtPalette): YtLensLook {
    fun solid(color: Color) = Brush.linearGradient(listOf(color, color))
    return when (palette.signature) {
        YtSignature.GLASS -> YtLensLook(
            CircleShape,
            Brush.radialGradient(listOf(Color(0x33FFFFFF), Color(0x8CFFFFFF))),
            Color(0xE6FFFFFF), 1.5.dp, palette.text, false,
        )
        YtSignature.FROST -> YtLensLook(
            CircleShape,
            Brush.verticalGradient(listOf(Color(0x66FFFFFF), Color(0x2EFFFFFF))),
            Color(0x99FFFFFF), 1.dp, palette.text, false,
        )
        YtSignature.SHAPES -> YtLensLook(CookieShape(9), solid(palette.lime), null, 0.dp, palette.ink, true)
        YtSignature.EDITORIAL -> YtLensLook(RectangleShape, solid(Color.Black), null, 0.dp, Color.White, false)
        YtSignature.POP -> YtLensLook(BlobShape(0.6f, 0.5f, 0.56f, 0.64f), solid(Color(0xFFDCD6F5)), null, 0.dp, palette.text, true)
        YtSignature.CLAY -> YtLensLook(
            CircleShape,
            Brush.verticalGradient(listOf(Color(0xFFFFF3E9), Color(0xFFF4D8C4))),
            Color(0x99FFFFFF), 1.dp, palette.text, true,
        )
        YtSignature.MOVO -> YtLensLook(CircleShape, solid(Color(0xFF1E1E22)), palette.lime, 2.dp, palette.text, true)
        YtSignature.KIESEL -> YtLensLook(CircleShape, solid(palette.lavender), null, 0.dp, palette.ink, true)
        YtSignature.AFTER -> YtLensLook(CircleShape, solid(palette.surface), palette.accent, 2.dp, palette.text, true)
        YtSignature.SIDE -> YtLensLook(CircleShape, solid(palette.raised), palette.accent, 1.5.dp, palette.text, true)
        YtSignature.YOUTUBE -> YtLensLook(CircleShape, solid(palette.raised), null, 0.dp, palette.text, true)
    }
}

/** The lens in YtDragLayer: grows out of the search icon, swells over a card, sinks in on a drop. */
@Composable
private fun SearchLens(ghost: YtSearchGhost) {
    val look = remember(ghost) { lensLook(ghost.palette) }
    val lensSize = 60.dp
    val lensPx = with(LocalDensity.current) { lensSize.toPx() }
    // Formen: the cookie keeps turning, as on the header
    val spin = if (ghost.palette.signature == YtSignature.SHAPES && ghost.motion) {
        rememberInfiniteTransition(label = "lensSpin").animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(6000, easing = LinearEasing), RepeatMode.Restart),
            label = "lensSpinValue",
        )
    } else {
        null
    }
    val from = (minOf(ghost.origin.width, ghost.origin.height) / lensPx).coerceIn(0.3f, 1f)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(lensSize)
            .graphicsLayer {
                val k = ghost.grow.value
                val hover = ghost.hover.value
                val sink = ghost.sink.value
                // the layer centres the lens on the finger; at first it still sits on the icon
                translationX = (ghost.origin.center.x - ghost.grab.x) * (1f - k)
                translationY = (ghost.origin.center.y - ghost.grab.y) * (1f - k)
                val scale = (from + (1f - from) * k) * (1f + 0.15f * hover) * (1f - 0.6f * sink)
                scaleX = scale
                scaleY = scale
                alpha = 1f - sink
                rotationZ = spin?.value ?: 0f
                if (look.shadow) {
                    shadowElevation = 10.dp.toPx() * k.coerceIn(0f, 1f)
                    shape = look.shape
                    clip = false
                }
            }
            .clip(look.shape)
            .background(look.fill)
            .then(if (look.rim != null) Modifier.border(look.rimWidth, look.rim, look.shape) else Modifier),
    ) {
        Icon(
            painterResource(R.drawable.ic_yt_search),
            contentDescription = null,
            tint = look.tint,
            modifier = Modifier.size(26.dp),
        )
    }
}

/** The label for search buttons without text of their own. */
@Composable
fun ytSearchLabel(): String = stringResource(R.string.yt_home_search)
