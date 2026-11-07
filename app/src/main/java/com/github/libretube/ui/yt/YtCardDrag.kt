package com.github.libretube.ui.yt

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import coil3.compose.AsyncImage
import com.github.libretube.R
import com.github.libretube.api.obj.StreamItem
import com.github.libretube.extensions.toID
import com.github.libretube.helpers.ImageHelper
import com.github.libretube.helpers.PreferenceHelper
import com.github.libretube.ui.yt.feed.YtBox
import com.github.libretube.ui.yt.feed.YtDragRules
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/*
 * YT: a home card held and dragged onto the docked mini player. A long press lifts the card in
 * the design's way; letting go without moving does what the long press did before (the quick
 * sheet). Moving lifts a small copy of the card into YtDragLayer that follows the finger; near
 * the mini player a magnet draws it in, over it the player is told (YtDock.hover) and a tick is
 * felt. Dropped there, the video plays next (nothing else waits) or goes to the end
 * (YtQueue.dropOnMini) and the copy sinks into the player; dropped anywhere else it springs
 * back into its card. While nothing plays, the navigation bar takes the drop into the draft
 * queue instead.
 */

/** The switch "Karten und Suche ziehen" (Gesten & Interaktion). */
object YtHomeDragPrefs {
    const val KEY = "yt_home_drag"
    val enabled: Boolean get() = PreferenceHelper.getBoolean(KEY, true)
}

internal enum class YtGhostPhase { DRAG, RETURN, SINK }

/** One card on its way: what its ghost shows and how far it has come. */
@Stable
internal class YtVideoGhost(
    val palette: YtPalette,
    val video: StreamItem,
    /** Where the ghost starts and returns to (the card, or Glas's loupe), in window px. */
    val card: Rect,
    /** The finger when the card was lifted, in window px. */
    val grab: Offset,
    val motion: Boolean,
) {
    /** 0: the ghost covers [card], 1: it is the small copy under the finger. */
    val shrink = Animatable(0f)

    /** Over a target, 0 to 1. */
    val hover = Animatable(0f)

    /** Dropped on a target: it sinks in, 0 to 1. */
    val sink = Animatable(0f)

    var phase by mutableStateOf(YtGhostPhase.DRAG)

    val id: String? = video.url?.toID()
}

/** The drag of a video card across the screens; the source calls begin, move and end. */
object YtCardDrag {
    private const val NONE = 0
    private const val MINI = 1
    private const val BAR = 2

    /** The card being dragged, also while its ghost springs back or sinks in. */
    internal var current by mutableStateOf<YtVideoGhost?>(null)
        private set

    private var target = NONE

    /** True while [video]'s card is the one being dragged (its card then dims under the ghost). */
    internal fun draggedShare(id: String?): Float {
        val ghost = current ?: return 0f
        return if (id != null && ghost.id == id) ghost.shrink.value.coerceIn(0f, 1f) else 0f
    }

    /**
     * Lifts [video]'s ghost off [card] at [finger] (window px). False if another drag runs.
     */
    internal fun begin(
        scope: CoroutineScope,
        palette: YtPalette,
        video: StreamItem,
        card: Rect,
        finger: Offset,
        motion: Boolean,
    ): Boolean {
        // a ghost left from a drag the layer ended (its activity went away) does not count
        if (YtDrag.session != null) return false
        val ghost = YtVideoGhost(palette, video, card, finger, motion)
        current = ghost
        target = NONE
        YtDock.hover = false
        YtDrag.ghost = { _ -> VideoGhost(ghost) }
        YtDrag.session = YtDragSession(YtDragKind.VIDEO, video, card, finger)
        scope.launch {
            if (motion) ghost.shrink.animateTo(1f, spring(dampingRatio = 0.72f, stiffness = 520f)) else ghost.shrink.snapTo(1f)
        }
        YtTestHooks.log("YtDrag") { "lift id=${ghost.id}" }
        return true
    }

    /**
     * The finger moved to [finger] (window px). [screen] is the window's size in px: while no
     * mini player is docked, the navigation bar at its bottom takes the drop.
     */
    internal fun move(scope: CoroutineScope, finger: Offset, haptics: YtHaptics, density: Density, screen: Size) {
        val session = YtDrag.session ?: return
        val ghost = current ?: return
        if (session.kind != YtDragKind.VIDEO || ghost.phase != YtGhostPhase.DRAG) return
        val (box, kind) = targetBox(screen)
        val hit = with(density) { 24.dp.toPx() }
        val magnet = with(density) { 80.dp.toPx() }
        val at = YtDragRules.ghost(finger.x, finger.y, box, hit, magnet)
        session.pointer = Offset(at.x, at.y)
        val now = if (at.hover) kind else NONE
        if (now == target) return
        target = now
        haptics.threshold(now != NONE)
        YtDock.hover = now == MINI
        YtDrag.hoverKey = if (now == MINI) YtDrag.MINI else null
        YtTestHooks.log("YtDrag") { "hover=${if (now == MINI) "mini" else if (now == BAR) "bar" else "none"}" }
        scope.launch {
            val to = if (now != NONE) 1f else 0f
            if (ghost.motion) ghost.hover.animateTo(to, spring(dampingRatio = 0.55f, stiffness = 600f)) else ghost.hover.snapTo(to)
        }
    }

    /** The finger let go ([cancelled]: the gesture was taken away): drop, or spring back. */
    internal fun end(scope: CoroutineScope, haptics: YtHaptics, screen: Size, cancelled: Boolean) {
        val ghost = current ?: return
        val session = YtDrag.session
        val over = if (cancelled) NONE else target
        target = NONE
        YtDock.hover = false
        YtDrag.hoverKey = null
        val (box, _) = targetBox(screen)
        val accepted = when (over) {
            MINI -> YtQueue.dropOnMini(ghost.video) != null
            BAR -> ytQueueVideo(ghost.video)
            else -> false
        }
        when {
            accepted -> {
                haptics.land(if (over == MINI) 0.6f else 0.4f)
                // into the player, or into the draft pill that appears above the bar
                val into = YtQueue.draftPillBounds?.center?.takeIf { over == BAR }
                    ?: box?.let { Offset(it.centerX, it.centerY) }
                if (into != null) session?.pointer = into
                ghost.phase = YtGhostPhase.SINK
                YtTestHooks.log("YtDrag") { "drop ${if (over == MINI) "mini" else "bar"} id=${ghost.id}" }
                settle(scope, ghost) { ghost.sink.animateTo(1f, spring(dampingRatio = 1f, stiffness = 420f)) }
            }
            else -> {
                if (over != NONE) haptics.reject() else haptics.release()
                session?.pointer = ghost.grab
                ghost.phase = YtGhostPhase.RETURN
                YtTestHooks.log("YtDrag") { "back id=${ghost.id}" }
                settle(scope, ghost) {
                    launch { ghost.hover.animateTo(0f, spring(dampingRatio = 1f, stiffness = 600f)) }
                    ghost.shrink.animateTo(0f, spring(dampingRatio = 0.78f, stiffness = 380f))
                }
            }
        }
    }

    /** Runs the last animation of [ghost], then ends the drag, also when it is cut short. */
    private fun settle(scope: CoroutineScope, ghost: YtVideoGhost, animation: suspend CoroutineScope.() -> Unit) {
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

    private fun finish(ghost: YtVideoGhost) {
        if (current !== ghost) return
        current = null
        if (YtDrag.session?.kind == YtDragKind.VIDEO) {
            YtDrag.session = null
            YtDrag.ghost = null
        }
        YtDrag.hoverKey = null
        YtDock.hover = false
    }

    /** The docked mini player, else (nothing plays) the navigation bar, in window px. */
    private fun targetBox(screen: Size): Pair<YtBox?, Int> {
        YtDrag.miniBounds?.let { r ->
            return YtBox(r.left.toFloat(), r.top.toFloat(), r.right.toFloat(), r.bottom.toFloat()) to MINI
        }
        val bar = YtChrome.navOverlapPx
        if (bar > 0 && screen.height > 0f && !YtQueue.hasPlayer()) {
            return YtBox(0f, screen.height - bar, screen.width, screen.height) to BAR
        }
        return null to NONE
    }
}

/**
 * Lets a home card be held and dragged onto the mini player (see above). Put it on the card's
 * root after ytCardSwipe, with [shape] the card's shape and [inset] the room between the
 * modifier's bounds and the visible card. [onRelease] is what the long press did before
 * (the quick sheet): it now runs when the finger lets go without moving, so the card's own
 * click must no longer take a long click. The long press stays reachable for TalkBack.
 */
@Composable
fun Modifier.ytLiftToQueue(
    palette: YtPalette,
    video: StreamItem,
    shape: Shape = ytCardShape(palette),
    inset: PaddingValues = PaddingValues(0.dp),
    onRelease: (() -> Unit)? = null,
    enabled: Boolean = true,
): Modifier {
    val dragOn = remember { YtHomeDragPrefs.enabled }
    val id = video.url?.toID()
    val release by rememberUpdatedState(onRelease)
    val current by rememberUpdatedState(video)
    val moreActions = stringResource(R.string.yt_home_more_actions)
    val actions = if (onRelease != null) {
        Modifier.semantics {
            onLongClick(label = moreActions) {
                release?.invoke()
                true
            }
        }
    } else {
        Modifier
    }
    if (!enabled || id == null) return this.then(actions)

    val view = LocalView.current
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val motion = LocalYtMotion.current
    val haptics = rememberYtHaptics()
    val scope = rememberCoroutineScope()
    val look = remember(palette) { liftLook(palette) }
    val lifted = remember(id) { Animatable(0f) }
    val holder = remember(id) { YtCoords() }
    val outlineShape = remember(shape, inset) { YtInsetShape(shape, inset) }

    fun lift(up: Boolean) {
        scope.launch {
            val to = if (up) 1f else 0f
            if (motion) lifted.animateTo(to, spring(dampingRatio = look.damping, stiffness = look.stiffness)) else lifted.snapTo(to)
        }
    }

    fun screen() = Size(view.rootView.width.toFloat(), view.rootView.height.toFloat())

    fun cardInWindow(coords: LayoutCoordinates): Rect {
        val box = coords.boundsInWindow()
        val dir = layoutDirection
        return with(density) {
            Rect(
                box.left + inset.calculateLeftPadding(dir).toPx(),
                box.top + inset.calculateTopPadding().toPx(),
                box.right - inset.calculateRightPadding(dir).toPx(),
                box.bottom - inset.calculateBottomPadding().toPx(),
            )
        }
    }

    val gesture = Modifier.pointerInput(id, dragOn) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            if (YtDrag.session != null) return@awaitEachGesture
            val hold = awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
            hold.consume()
            haptics.grab()
            if (!dragOn) {
                // as before: the long press acts at once
                release?.invoke()
                consumeUntilUp(hold.id)
                return@awaitEachGesture
            }
            lift(true)
            val coords = holder.coords?.takeIf { it.isAttached }
            val grab = coords?.localToWindow(hold.position)
            var moved = false
            var dragging = false
            var released = false
            try {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == hold.id }
                    // the card owns the finger now: no scroll, no swipe, no click
                    event.changes.forEach { it.consume() }
                    if (change == null || !change.pressed) {
                        released = change != null
                        break
                    }
                    if (coords == null || grab == null || !coords.isAttached) continue
                    val finger = coords.localToWindow(change.position)
                    if (!moved && (change.position - hold.position).getDistance() > viewConfiguration.touchSlop) {
                        moved = true
                        dragging = YtCardDrag.begin(scope, palette, current, cardInWindow(coords), grab, motion)
                        if (dragging) lift(false)
                    }
                    if (dragging) YtCardDrag.move(scope, finger, haptics, density, screen())
                }
            } finally {
                when {
                    dragging -> YtCardDrag.end(scope, haptics, screen(), cancelled = !released)
                    released && !moved -> {
                        lift(false)
                        release?.invoke()
                    }
                    else -> lift(false)
                }
            }
        }
    }

    val visuals = Modifier
        .graphicsLayer {
            val k = lifted.value
            val scale = 1f + (look.scale - 1f) * k
            scaleX = scale
            scaleY = scale
            rotationZ = look.tilt * k
            if (look.shadow > 0.dp) {
                shadowElevation = look.shadow.toPx() * k.coerceIn(0f, 1f)
                this.shape = outlineShape
                clip = false
            }
            // the card stays as a faint place while its ghost is out
            alpha = 1f - 0.65f * YtCardDrag.draggedShare(id)
        }
        .drawWithCache {
            val outline = outlineShape.createOutline(size, layoutDirection, this)
            val ring = look.ringWidth.toPx()
            onDrawWithContent {
                val k = lifted.value.coerceIn(0f, 1f)
                // opaque while lifted, so the shadow does not show through an open card
                if (k > 0f && look.fill) drawOutline(outline, palette.background, alpha = k)
                drawContent()
                if (k > 0f && look.ring != null) drawOutline(outline, look.ring, alpha = k, style = Stroke(ring))
            }
        }

    return this
        .onGloballyPositioned { holder.coords = it }
        .then(actions)
        .then(gesture)
        .then(visuals)
}

/** Consumes the pointer [id] until it is lifted, so nothing below acts on it. */
private suspend fun AwaitPointerEventScope.consumeUntilUp(id: PointerId) {
    while (true) {
        val event = awaitPointerEvent(PointerEventPass.Initial)
        val change = event.changes.firstOrNull { it.id == id }
        event.changes.forEach { it.consume() }
        if (change == null || !change.pressed) return
    }
}

/** Where a modifier sits, for gestures that need window coordinates. */
internal class YtCoords {
    var coords: LayoutCoordinates? = null
}

/** [shape] laid inside [inset], for outlines of cards whose modifier bounds hold a gap. */
internal class YtInsetShape(private val shape: Shape, private val inset: PaddingValues) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val left = with(density) { inset.calculateLeftPadding(layoutDirection).toPx() }
        val top = with(density) { inset.calculateTopPadding().toPx() }
        val right = with(density) { inset.calculateRightPadding(layoutDirection).toPx() }
        val bottom = with(density) { inset.calculateBottomPadding().toPx() }
        val inner = Size((size.width - left - right).coerceAtLeast(0f), (size.height - top - bottom).coerceAtLeast(0f))
        val outline = shape.createOutline(inner, layoutDirection, density)
        if (left == 0f && top == 0f) return outline
        val path = Path().apply {
            addOutline(outline)
            translate(Offset(left, top))
        }
        return Outline.Generic(path)
    }
}

// ---------------------------------------------------------------------------------------------
// How each design lifts a card, and how its ghost looks

private class YtLiftLook(
    val scale: Float,
    /** Degrees the card leans while lifted. */
    val tilt: Float,
    val damping: Float,
    val stiffness: Float,
    val shadow: Dp,
    /** A frame drawn around the lifted card, or null. */
    val ring: Color?,
    val ringWidth: Dp,
    /** Fill the card with the background while lifted (cards without a surface of their own). */
    val fill: Boolean,
)

private fun liftLook(palette: YtPalette): YtLiftLook = when (palette.signature) {
    YtSignature.YOUTUBE -> YtLiftLook(1.03f, 0f, 0.7f, 520f, 14.dp, null, 0.dp, true)
    // Movo: a quick tilt and a lime edge, as if picked off the mosaic
    YtSignature.MOVO -> YtLiftLook(1.04f, -1.2f, 0.5f, 520f, 16.dp, palette.lime, 2.dp, true)
    // After: slow and cinematic, the poster lit by a thin accent frame
    YtSignature.AFTER -> YtLiftLook(1.025f, 0f, 0.9f, 260f, 18.dp, palette.accent, 1.5.dp, true)
    // Side: posts have no surface, so no shadow; an accent rule marks the held one
    YtSignature.SIDE -> YtLiftLook(1.02f, 0f, 0.8f, 500f, 0.dp, palette.accent, 1.5.dp, false)
    // Clay: the dough puffs up with an overshoot
    YtSignature.CLAY -> YtLiftLook(1.045f, 0f, 0.42f, 420f, 12.dp, null, 0.dp, true)
    // Editorial: nothing moves, the print gets a hard frame
    YtSignature.EDITORIAL -> YtLiftLook(1f, 0f, 1f, 900f, 0.dp, palette.text, 2.dp, false)
    YtSignature.GLASS -> YtLiftLook(1.02f, 0f, 0.6f, 500f, 0.dp, Color.White, 1.5.dp, false)
    // Pop: a balloon, with a swing
    YtSignature.POP -> YtLiftLook(1.06f, 2f, 0.35f, 420f, 14.dp, null, 0.dp, true)
    YtSignature.SHAPES -> YtLiftLook(1.04f, 1.5f, 0.45f, 500f, 10.dp, null, 0.dp, true)
    // Frost: the frost card catches the light at its rim
    YtSignature.FROST -> YtLiftLook(1.03f, 0f, 0.7f, 380f, 0.dp, Color(0xCCFFFFFF), 1.5.dp, false)
    // Kiesel: a pebble picked up, leaning
    YtSignature.KIESEL -> YtLiftLook(1.05f, -2f, 0.4f, 480f, 12.dp, null, 0.dp, true)
}

private fun ghostShape(palette: YtPalette): Shape = when (palette.signature) {
    YtSignature.EDITORIAL, YtSignature.SIDE -> RectangleShape
    YtSignature.POP -> BlobShape(0.6f, 0.5f, 0.56f, 0.64f)
    YtSignature.SHAPES -> RoundedCornerShape(topStart = 22.dp, topEnd = 8.dp, bottomEnd = 22.dp, bottomStart = 8.dp)
    YtSignature.KIESEL -> RoundedCornerShape(50)
    YtSignature.CLAY -> RoundedCornerShape(18.dp)
    YtSignature.AFTER -> RoundedCornerShape(4.dp)
    YtSignature.GLASS -> RoundedCornerShape(18.dp)
    else -> RoundedCornerShape(12.dp)
}

/** The rim of the ghost per design, or null. */
private fun ghostRim(palette: YtPalette): Pair<Dp, Color>? = when (palette.signature) {
    YtSignature.EDITORIAL -> 1.5.dp to ytDayNight(Color.Black, palette.text)
    YtSignature.MOVO -> 2.dp to palette.lime
    YtSignature.GLASS -> 1.5.dp to ytDayNight(Color(0xE6FFFFFF), Color(0x80CFEDE7))
    YtSignature.FROST -> 1.dp to Color(0x99FFFFFF)
    YtSignature.AFTER -> 1.dp to palette.accent
    YtSignature.POP, YtSignature.CLAY -> 2.dp to Color.White
    else -> null
}

/** The ghost of a dragged card in YtDragLayer: from the card's size down to a small copy. */
@Composable
private fun VideoGhost(ghost: YtVideoGhost) {
    val density = LocalDensity.current
    val tokenWidth = with(density) { 128.dp.toPx() }
    val tokenHeight = with(density) { 72.dp.toPx() }
    val shape = remember(ghost) { ghostShape(ghost.palette) }
    val rim = remember(ghost) { ghostRim(ghost.palette) }
    val tilt = when (ghost.palette.signature) {
        YtSignature.POP -> 4f
        YtSignature.KIESEL -> -3f
        YtSignature.MOVO -> -2f
        else -> 0f
    }
    Box(
        Modifier
            .layout { measurable, _ ->
                // read here: the ghost changes size without recomposing
                val k = ghost.shrink.value
                val width = lerp(ghost.card.width, tokenWidth, k).roundToInt().coerceAtLeast(1)
                val height = lerp(ghost.card.height, tokenHeight, k).roundToInt().coerceAtLeast(1)
                val placeable = measurable.measure(Constraints.fixed(width, height))
                layout(width, height) { placeable.place(0, 0) }
            }
            .graphicsLayer {
                val k = ghost.shrink.value
                val hover = ghost.hover.value
                val sink = ghost.sink.value
                // the layer centres the ghost on the finger; at first it still covers the card
                translationX = (ghost.card.center.x - ghost.grab.x) * (1f - k)
                translationY = (ghost.card.center.y - ghost.grab.y) * (1f - k)
                val scale = (1f - 0.14f * hover) * (1f - 0.8f * sink)
                scaleX = scale
                scaleY = scale
                rotationZ = tilt * k * (1f - hover)
                alpha = ((0.55f + 0.45f * k) * (1f - sink)).coerceIn(0f, 1f)
                shadowElevation = 14.dp.toPx() * k.coerceIn(0f, 1f) * (1f - sink)
                this.shape = shape
                clip = true
            }
            .background(ghost.palette.surface)
            .then(if (rim != null) Modifier.border(rim.first, rim.second, shape) else Modifier),
    ) {
        AsyncImage(
            model = ghost.video.thumbnail,
            contentDescription = null,
            imageLoader = ImageHelper.loader(),
            contentScale = ContentScale.Crop,
            colorFilter = if (ghost.palette.signature == YtSignature.EDITORIAL) ytGrayscale else null,
            modifier = Modifier.fillMaxSize(),
        )
        if (ghost.palette.signature == YtSignature.GLASS) {
            // a glass sheen over the picture, as the loupe had
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Brush.verticalGradient(listOf(Color(0x40FFFFFF), Color.Transparent, Color(0x1AFFFFFF)))),
            )
        }
        // over the target: the queue mark
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .align(Alignment.Center)
                .graphicsLayer {
                    val hover = ghost.hover.value
                    alpha = hover.coerceIn(0f, 1f)
                    scaleX = 0.6f + 0.4f * hover
                    scaleY = 0.6f + 0.4f * hover
                }
                .size(32.dp)
                .clip(CircleShape)
                .background(ghost.palette.accent),
        ) {
            Icon(
                painterResource(R.drawable.ic_queue),
                contentDescription = null,
                tint = ghost.palette.onAccent,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/**
 * Both drags on a home card at once: it can be held and dragged onto the mini player
 * (ytLiftToQueue) and it takes the search lens (ytDropTarget). Put it on the card's root after
 * ytCardSwipe and before the card's click, which must no longer take a long click: [onRelease]
 * is what that long click did.
 */
@Composable
fun Modifier.ytHomeCard(
    palette: YtPalette,
    video: StreamItem,
    shape: Shape = ytCardShape(palette),
    inset: PaddingValues = PaddingValues(0.dp),
    onRelease: (() -> Unit)? = null,
): Modifier = this
    .ytDropTarget(palette, video, shape, inset)
    .ytLiftToQueue(palette, video, shape, inset, onRelease)
