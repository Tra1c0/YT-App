package com.github.libretube.ui.yt

import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.platform.testTag as withTestTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import coil3.compose.AsyncImage
import com.github.libretube.R
import com.github.libretube.api.obj.StreamItem
import com.github.libretube.extensions.toID
import com.github.libretube.helpers.ImageHelper
import com.github.libretube.helpers.YtDesign
import com.github.libretube.helpers.PreferenceHelper
import com.github.libretube.helpers.YtHidden
import com.github.libretube.ui.yt.feed.YtFlightSpring
import com.github.libretube.ui.yt.feed.YtQueueMove
import com.github.libretube.ui.yt.feed.YtSwipePhysics
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.min

/*
 * YT: the card swipe of the home screens. Pushed to the left a card hides (it flies off and a
 * pill offers "Rückgängig" and "Kanal 1 Woche pausieren"); pushed to the right it goes to the
 * end of the queue: the card springs back and a small copy of it flies to the docked mini
 * player, or, while nothing plays, to the pill of the draft queue. The card follows the finger
 * 1:1 up to the threshold, then with a rubber band; behind it the field it slides off is drawn
 * in the design's own way (only where the card has left, so cards with an open background
 * keep it); a design haptic marks the threshold and the commit, and letting go carries the
 * finger's velocity into the spring. Only on cards in vertical lists: horizontal pagers and
 * rows keep their own swipe.
 */

/** The switch "Karten wischen" (Gesten & Interaktion). */
object YtCardSwipePrefs {
    const val KEY = "yt_card_swipe"
    val enabled: Boolean get() = PreferenceHelper.getBoolean(KEY, true)
}

/**
 * Cards brought back by "Rückgängig": they slide in again from where they left, if they come
 * back on screen right away (a card scrolled out of view meanwhile just appears).
 */
internal object YtCardReturns {
    private val ids = HashMap<String, Long>()

    fun mark(videoId: String) {
        ids[videoId] = System.currentTimeMillis()
    }

    fun take(videoId: String): Boolean {
        val at = ids.remove(videoId) ?: return false
        return System.currentTimeMillis() - at < 1500L
    }
}

@Stable
private class YtCardSwipeState(start: Float) {
    val offset = Animatable(start)

    /** -1 while letting go would hide, 1 while it would queue, else 0. */
    var armed = 0

    /** The underlay's answer to arming (0 to 1, springs). */
    val pop = Animatable(0f)
    var detents = 0
    var leaving = false
    var coords: LayoutCoordinates? = null
}

/** Hides [video] from the home screens and offers to take it back. */
fun ytHideVideo(video: StreamItem) {
    val id = video.url?.toID() ?: return
    YtHidden.hide(id)
    YtUndo.hidden(video)
}

/**
 * Puts [video] at the end of the queue (or into the draft while nothing plays), without the
 * flight: for menus and accessibility actions.
 * @return false if it was rejected (it is the video that plays)
 */
fun ytQueueVideo(video: StreamItem): Boolean {
    val result = YtQueue.appendToEnd(video)
    when (result.move) {
        YtQueueMove.APPEND -> YtQueue.announce(YtDropResult(next = false, position = result.position, title = video.title.orEmpty()))
        YtQueueMove.DRAFT -> YtQueue.draftLanded++
        else -> return false
    }
    return true
}

/**
 * Puts [video] at the end of the queue (or into the draft) and lets a small copy of it fly
 * from [from] (the card, in window px; null: no flight) with the finger's [velocity] (px/s) to
 * the docked mini player or the draft pill, which take it in when it lands. [texture] is the
 * design's commit feel.
 * @return false if it was rejected (it is the video that plays)
 */
fun ytQueueCard(
    palette: YtPalette,
    video: StreamItem,
    from: Rect?,
    velocity: Float,
    haptics: YtHaptics,
    motion: Boolean,
    texture: YtHapticTexture = YtHapticTexture.land(0.4f),
): Boolean {
    val result = YtQueue.appendToEnd(video)
    if (result.move == YtQueueMove.REJECT) return false
    haptics.play(texture)
    val toMini = result.move == YtQueueMove.APPEND
    val fly = motion && from != null
    if (!toMini && fly) YtQueue.draftInFlight++
    val flight = YtFlight(palette, video.thumbnail, from, Offset(velocity, -velocity * 0.15f), toMini) {
        if (toMini) {
            YtQueue.announce(YtDropResult(next = false, position = result.position, title = video.title.orEmpty()))
        } else {
            if (fly) YtQueue.draftInFlight = (YtQueue.draftInFlight - 1).coerceAtLeast(0)
            YtQueue.draftLanded++
            if (fly) haptics.land(0.3f)
        }
    }
    YtFlightLayer.launch(flight, fly)
    return true
}

/** The commit feel of the card swipe of [palette]'s design, for swipes of their own (Kiesel's stack). */
fun ytSwipeTexture(palette: YtPalette, queue: Boolean): YtHapticTexture =
    swipeLook(palette).let { if (queue) it.queue else it.hide }

/**
 * Lets a home card be swiped: left hides [video], right queues it (see above). Put it where
 * the card's own shape starts (before its click), with [shape] the card's shape and [inset]
 * the room between the modifier's bounds and the visible card (e.g. a gap below the card), so
 * the field behind matches the card. [testTag] names the card for the emulator checks.
 */
@Composable
fun Modifier.ytCardSwipe(
    palette: YtPalette,
    video: StreamItem,
    shape: Shape = ytCardShape(palette),
    inset: PaddingValues = PaddingValues(0.dp),
    enabled: Boolean = true,
    testTag: String? = null,
): Modifier {
    val tagged = if (testTag != null) this.withTestTag(testTag) else this
    val id = video.url?.toID()
    val switchedOn = remember { YtCardSwipePrefs.enabled }
    if (!enabled || id == null || !switchedOn) return tagged

    val view = LocalView.current
    val motion = LocalYtMotion.current
    val haptics = rememberYtHaptics()
    val scope = rememberCoroutineScope()
    val look = remember(palette) { swipeLook(palette) }
    val state = remember(id) {
        // a card that comes back by "Rückgängig" starts where it flew to
        val back = YtCardReturns.take(id) && motion
        YtCardSwipeState(if (back) -view.resources.displayMetrics.widthPixels * 1.1f else 0f)
    }
    LaunchedEffect(state) {
        if (state.offset.value != 0f) state.offset.animateTo(0f, spring(dampingRatio = 0.78f, stiffness = 260f))
    }
    val density = LocalDensity.current
    val threshold = with(density) { 96.dp.toPx() }
    val margin = with(density) { 32.dp.toPx() }
    val measurer = rememberTextMeasurer()
    val queueIcon = painterResource(R.drawable.ic_queue)
    val hideIcon = painterResource(R.drawable.ic_yt_hide)
    val queueLabel = stringResource(R.string.yt_home_swipe_queue)
    val hideLabel = stringResource(R.string.yt_home_swipe_hide)
    val queueAction = stringResource(R.string.yt_home_action_queue)
    val hideAction = stringResource(R.string.yt_home_action_hide)
    val current by rememberUpdatedState(video)

    fun settle(velocity: Float) {
        scope.launch {
            if (motion) {
                state.offset.animateTo(0f, spring(dampingRatio = 0.72f, stiffness = 380f), initialVelocity = velocity)
            } else {
                state.offset.snapTo(0f)
            }
        }
    }

    fun arm(side: Int, feel: Boolean = true) {
        if (side == state.armed) return
        if (feel) {
            if (side != 0) haptics.play(look.arm) else haptics.threshold(false)
        }
        state.armed = side
        scope.launch {
            val target = if (side != 0) 1f else 0f
            if (motion) state.pop.animateTo(target, spring(dampingRatio = look.popDamping, stiffness = 600f)) else state.pop.snapTo(target)
        }
    }

    fun hide(velocity: Float, animate: Boolean) {
        val item = current
        val videoId = item.url?.toID() ?: return
        state.leaving = true
        haptics.play(look.hide)
        scope.launch {
            if (animate && motion) {
                val right = state.coords?.takeIf { it.isAttached }?.boundsInWindow()?.right ?: view.width.toFloat()
                state.offset.animateTo(
                    YtSwipePhysics.flyOut(right, margin),
                    spring(dampingRatio = 1f, stiffness = 520f),
                    initialVelocity = min(velocity, -1800f),
                )
            }
            YtHidden.hide(videoId)
            YtUndo.hidden(item)
            // a list that keeps the video (it does not filter) would keep a gap: it comes back
            delay(900)
            state.leaving = false
            if (motion) state.offset.animateTo(0f, spring(dampingRatio = 0.8f, stiffness = 300f)) else state.offset.snapTo(0f)
        }
    }

    fun queue(velocity: Float, fly: Boolean) {
        val from = state.coords?.takeIf { it.isAttached }?.boundsInWindow()?.translate(state.offset.value, 0f)
        val queued = ytQueueCard(palette, current, if (fly) from else null, velocity, haptics, motion, look.queue)
        settle(velocity)
        if (!queued) haptics.reject()
    }

    val gesture = Modifier.pointerInput(state, threshold) {
        val width = size.width.toFloat().coerceAtLeast(1f)
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            if (state.leaving) return@awaitEachGesture
            val tracker = VelocityTracker()
            tracker.addPointerInputChange(down)
            var raw = 0f
            fun follow(next: Float) {
                raw = next
                val shown = YtSwipePhysics.band(next, threshold, width)
                arm(YtSwipePhysics.side(shown, threshold))
                if (look.detents) {
                    val passed = YtSwipePhysics.detents(shown, threshold)
                    if (passed > state.detents) haptics.play(ShapesDetent)
                    state.detents = passed
                }
                scope.launch { state.offset.snapTo(shown) }
            }
            // a card still springing back is picked up where it is
            val start = awaitHorizontalTouchSlopOrCancellation(down.id) { change, over ->
                change.consume()
                follow(YtSwipePhysics.unband(state.offset.value, threshold, width) + over)
            } ?: return@awaitEachGesture
            tracker.addPointerInputChange(start)
            val lifted = horizontalDrag(start.id) { change ->
                tracker.addPointerInputChange(change)
                change.consume()
                follow(raw + change.positionChange().x)
            }
            val velocity = if (lifted) tracker.calculateVelocity().x else 0f
            val way = if (lifted) YtSwipePhysics.decide(state.offset.value, velocity, threshold) else 0
            // letting go: the commit (or nothing) is felt, not the threshold left behind
            arm(0, feel = false)
            state.detents = 0
            when (way) {
                -1 -> hide(velocity, animate = true)
                1 -> queue(velocity, fly = true)
                else -> settle(velocity)
            }
        }
    }

    val actions = Modifier.semantics {
        customActions = listOf(
            CustomAccessibilityAction(queueAction) {
                queue(0f, fly = false)
                true
            },
            CustomAccessibilityAction(hideAction) {
                hide(0f, animate = false)
                true
            },
        )
    }

    val underlay = Modifier.drawWithCache {
        val left = inset.calculateLeftPadding(layoutDirection).toPx()
        val right = inset.calculateRightPadding(layoutDirection).toPx()
        val area = Rect(left, inset.calculateTopPadding().toPx(), size.width - right, size.height - inset.calculateBottomPadding().toPx())
        val outline = if (area.width > 0f && area.height > 0f) shape.createOutline(area.size, layoutDirection, this) else null
        var queueText: TextLayoutResult? = null
        var hideText: TextLayoutResult? = null
        onDrawWithContent {
            val x = state.offset.value
            if (x != 0f && outline != null) {
                val text = if (x > 0f) {
                    queueText ?: measurer.measure(look.cased(queueLabel), look.text).also { queueText = it }
                } else {
                    hideText ?: measurer.measure(look.cased(hideLabel), look.text).also { hideText = it }
                }
                drawUnderlay(look, x, threshold, state.pop.value, area, outline, if (x > 0f) queueIcon else hideIcon, text)
            }
            drawContent()
        }
    }

    val move = Modifier.graphicsLayer {
        val x = state.offset.value
        translationX = x
        if (look.tilt != 0f) rotationZ = look.tilt * x / size.width.coerceAtLeast(1f)
    }

    return tagged
        .onPlaced { state.coords = it }
        .then(actions)
        .then(gesture)
        .then(underlay)
        .then(move)
}

// ---------------------------------------------------------------------------------------------
// How each design draws what the card slides off, and how it feels

private class YtSwipeLook(
    val signature: YtSignature,
    val queueField: Color,
    val hideField: Color,
    val queueInk: Color,
    val hideInk: Color,
    val text: TextStyle,
    val uppercase: Boolean = false,
    val icon: Float = 26f,
    /** Below 1 the underlay overshoots when it arms. */
    val popDamping: Float = 0.5f,
    /** Degrees the card leans at a full width of pull. */
    val tilt: Float = 0f,
    /** Formen: the cookie clicks every 30 degrees it turns. */
    val detents: Boolean = false,
    val arm: YtHapticTexture,
    val hide: YtHapticTexture,
    val queue: YtHapticTexture,
) {
    fun cased(label: String) = if (uppercase) label.uppercase() else label
}

private fun step(primitive: Int, scale: Float, delay: Int = 0) = YtHapticStep(primitive, scale, delay)

private fun armTexture(name: String, vararg steps: YtHapticStep) =
    YtHapticTexture.of("swipe_arm_$name", YtFeedback.GESTURE_THRESHOLD_ACTIVATE, *steps)

private fun commitTexture(name: String, vararg steps: YtHapticStep) =
    YtHapticTexture.of(name, YtFeedback.CONFIRM, *steps)

private val ShapesDetent = YtHapticTexture.of("swipe_detent_shapes", YtFeedback.SEGMENT_TICK, step(YtPrimitive.TICK, 0.5f))

private val KieselLavenderField = Color(0xFFB4A7F5)
private val KieselPeachField = Color(0xFFF6C6A2)
private val GlassRim = Color(0x405C8C85)

private fun swipeLook(palette: YtPalette): YtSwipeLook {
    val s = palette.signature
    val name = s.name.lowercase()
    // one commit feel per design, the same for both sides; the names tell them apart in the log
    fun pair(vararg steps: YtHapticStep) = commitTexture("swipe_hide_$name", *steps) to commitTexture("swipe_queue_$name", *steps)
    return when (s) {
        YtSignature.YOUTUBE -> {
            val (hide, queue) = pair(step(YtPrimitive.CLICK, 0.7f))
            YtSwipeLook(
                s, queueField = palette.raised, hideField = palette.live, queueInk = palette.text, hideInk = Color.White,
                text = TextStyle(fontFamily = YtFonts.geist, fontWeight = FontWeight.SemiBold, fontSize = 13.sp),
                popDamping = 0.7f,
                arm = armTexture(name, step(YtPrimitive.CLICK, 0.5f)), hide = hide, queue = queue,
            )
        }
        YtSignature.MOVO -> {
            val (hide, queue) = pair(step(YtPrimitive.CLICK, 0.6f), step(YtPrimitive.CLICK, 0.8f, 60))
            YtSwipeLook(
                s, queueField = palette.lime, hideField = palette.pink, queueInk = palette.ink, hideInk = palette.ink,
                text = TextStyle(fontFamily = YtFonts.geist, fontWeight = FontWeight.Black, fontSize = 15.sp),
                uppercase = true, icon = 30f, popDamping = 0.45f,
                arm = armTexture(name, step(YtPrimitive.CLICK, 0.6f)), hide = hide, queue = queue,
            )
        }
        YtSignature.AFTER -> {
            // the projector: a rattle, then the frame drops into place
            val rattle = arrayOf(
                step(YtPrimitive.TICK, 0.3f), step(YtPrimitive.TICK, 0.3f, 35), step(YtPrimitive.TICK, 0.3f, 35),
                step(YtPrimitive.TICK, 0.3f, 35), step(YtPrimitive.THUD, 0.4f, 35),
            )
            val (hide, queue) = pair(*rattle)
            YtSwipeLook(
                s, queueField = palette.surface, hideField = palette.surface, queueInk = palette.accent, hideInk = palette.accent,
                text = TextStyle(fontFamily = YtFonts.archivoNarrow, fontWeight = FontWeight.Bold, fontSize = 13.sp, letterSpacing = 1.2.sp),
                uppercase = true, icon = 22f, popDamping = 0.9f,
                arm = armTexture(name, step(YtPrimitive.TICK, 0.4f)), hide = hide, queue = queue,
            )
        }
        YtSignature.SIDE -> {
            // a heartbeat, as the heart this swipe used to give
            val (hide, queue) = pair(step(YtPrimitive.TICK, 0.5f), step(YtPrimitive.CLICK, 0.8f, 120))
            YtSwipeLook(
                s, queueField = Color.Transparent, hideField = Color.Transparent, queueInk = palette.accent, hideInk = palette.accent,
                text = TextStyle(fontFamily = YtFonts.figtree, fontWeight = FontWeight.Medium, fontSize = 13.sp),
                icon = 30f, popDamping = 0.45f,
                arm = armTexture(name, step(YtPrimitive.TICK, 0.4f)), hide = hide, queue = queue,
            )
        }
        YtSignature.CLAY -> {
            val (hide, queue) = pair(step(YtPrimitive.THUD, 0.8f))
            YtSwipeLook(
                s, queueField = Color(0xFFE9C0AA), hideField = Color(0xFFE9C0AA), queueInk = palette.accent, hideInk = palette.accent,
                text = TextStyle(fontFamily = YtFonts.nunito, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp),
                popDamping = 0.4f, tilt = 2f,
                arm = armTexture(name, step(YtPrimitive.LOW_TICK, 0.6f)), hide = hide, queue = queue,
            )
        }
        YtSignature.EDITORIAL -> {
            val (hide, queue) = pair(step(YtPrimitive.CLICK, 1f))
            YtSwipeLook(
                s, queueField = Color.Black, hideField = Color.Black, queueInk = Color.White, hideInk = Color.White,
                text = TextStyle(fontFamily = YtFonts.plexMono, fontWeight = FontWeight.Medium, fontSize = 12.sp, letterSpacing = 2.sp),
                uppercase = true, popDamping = 1f,
                arm = armTexture(name, step(YtPrimitive.TICK, 0.6f)), hide = hide, queue = queue,
            )
        }
        YtSignature.GLASS -> {
            val (hide, queue) = pair(step(YtPrimitive.TICK, 0.4f), step(YtPrimitive.LOW_TICK, 0.6f, 50))
            YtSwipeLook(
                s, queueField = Color.White, hideField = Color.White, queueInk = palette.text, hideInk = palette.text,
                text = TextStyle(fontFamily = YtFonts.googleSans, fontWeight = FontWeight.Medium, fontSize = 13.sp),
                icon = 22f, popDamping = 0.55f,
                arm = armTexture(name, step(YtPrimitive.QUICK_RISE, 0.4f)), hide = hide, queue = queue,
            )
        }
        YtSignature.POP -> {
            val (hide, queue) = pair(step(YtPrimitive.CLICK, 0.7f), step(YtPrimitive.SPIN, 0.4f, 40))
            YtSwipeLook(
                s, queueField = palette.lime, hideField = palette.lavender, queueInk = palette.text, hideInk = palette.text,
                text = TextStyle(fontFamily = YtFonts.nunito, fontWeight = FontWeight.Black, fontSize = 14.sp),
                icon = 24f, popDamping = 0.35f, tilt = 3f,
                arm = armTexture(name, step(YtPrimitive.QUICK_RISE, 0.6f)), hide = hide, queue = queue,
            )
        }
        YtSignature.SHAPES -> {
            val (hide, queue) = pair(step(YtPrimitive.SPIN, 0.5f))
            YtSwipeLook(
                s, queueField = palette.lime, hideField = palette.pink, queueInk = palette.ink, hideInk = palette.ink,
                text = TextStyle(fontFamily = YtFonts.googleSans, fontWeight = FontWeight.Bold, fontSize = 13.sp),
                icon = 22f, popDamping = 0.45f, detents = true,
                arm = armTexture(name, step(YtPrimitive.CLICK, 0.5f)), hide = hide, queue = queue,
            )
        }
        YtSignature.FROST -> {
            // crystals: three fading ticks, then the frost gives way
            val (hide, queue) = pair(step(YtPrimitive.SLOW_RISE, 0.3f), step(YtPrimitive.TICK, 0.4f, 160))
            YtSwipeLook(
                s, queueField = Color.White, hideField = Color.White, queueInk = Color.White, hideInk = Color.White,
                text = TextStyle(fontFamily = YtFonts.jakarta, fontWeight = FontWeight.SemiBold, fontSize = 13.sp),
                popDamping = 0.7f,
                arm = armTexture(
                    name,
                    step(YtPrimitive.LOW_TICK, 0.5f), step(YtPrimitive.LOW_TICK, 0.35f, 40), step(YtPrimitive.LOW_TICK, 0.2f, 40),
                ),
                hide = hide, queue = queue,
            )
        }
        YtSignature.KIESEL -> {
            // a pebble lands and bounces out
            val (hide, queue) = pair(
                step(YtPrimitive.THUD, 0.9f), step(YtPrimitive.TICK, 0.6f, 140),
                step(YtPrimitive.TICK, 0.35f, 100), step(YtPrimitive.TICK, 0.15f, 80),
            )
            YtSwipeLook(
                s, queueField = KieselLavenderField, hideField = KieselPeachField, queueInk = KieselInk, hideInk = KieselInk,
                text = TextStyle(fontFamily = YtFonts.jakarta, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp),
                icon = 20f, popDamping = 0.4f, tilt = 3f,
                arm = armTexture(name, step(YtPrimitive.TICK, 0.6f)), hide = hide, queue = queue,
            )
        }
    }
}

/**
 * What the card at [x] has uncovered: the field of the side it moves to, clipped to the strip
 * it left, with the design's mark and word near the edge it came from.
 */
private fun DrawScope.drawUnderlay(
    look: YtSwipeLook,
    x: Float,
    threshold: Float,
    pop: Float,
    area: Rect,
    outline: Outline,
    icon: Painter,
    label: TextLayoutResult,
) {
    val queue = x > 0f
    val p = (abs(x) / threshold).coerceAtMost(1.6f)
    val a = p.coerceAtMost(1f)
    val field = if (queue) look.queueField else look.hideField
    val ink = if (queue) look.queueInk else look.hideInk
    val strip = if (queue) Rect(0f, 0f, x, size.height) else Rect(size.width + x, 0f, size.width, size.height)
    val edge = 30.dp.toPx()
    val anchor = Offset(if (queue) area.left + edge else area.right - edge, area.center.y)
    val iconSize = look.icon.dp.toPx() * (0.55f + 0.45f * a) * (1f + 0.12f * pop)
    // the word joins the mark once the pull means something, if the card is wide enough
    val roomy = area.width > label.size.width + iconSize + edge * 3f
    val wordAlpha = if (roomy) ((p - 0.45f) / 0.4f).coerceIn(0f, 1f) else 0f
    val gap = 10.dp.toPx()

    fun drawIcon(color: Color, px: Float = iconSize, alpha: Float = 1f) {
        translate(anchor.x - px / 2f, anchor.y - px / 2f) {
            with(icon) { draw(Size(px, px), alpha = alpha, colorFilter = ColorFilter.tint(color)) }
        }
    }

    fun drawWord(color: Color, alpha: Float = wordAlpha, shift: Float = 0f, after: Float = iconSize / 2f + gap) {
        if (alpha <= 0f) return
        val left = if (queue) anchor.x + after + shift else anchor.x - after - label.size.width - shift
        drawText(label, color = color, topLeft = Offset(left, anchor.y - label.size.height / 2f), alpha = alpha)
    }

    fun drawField(alpha: Float, brush: Brush? = null) {
        translate(area.left, area.top) {
            if (brush != null) drawOutline(outline, brush, alpha = alpha) else drawOutline(outline, field, alpha = alpha)
        }
    }

    clipRect(strip.left, strip.top, strip.right, strip.bottom) {
        when (look.signature) {
            YtSignature.YOUTUBE -> {
                drawField(0.35f + 0.65f * a)
                drawIcon(ink)
                drawWord(ink)
            }
            YtSignature.MOVO -> {
                drawField((p * 1.5f).coerceAtMost(1f))
                drawIcon(ink)
                drawWord(ink)
            }
            YtSignature.AFTER -> {
                drawField(0.55f + 0.45f * a)
                // a tangerine film strip runs along the card's edge, with its sprocket holes
                val band = 9.dp.toPx()
                val bandLeft = if (queue) x - band else size.width + x
                drawRect(look.queueInk, Offset(bandLeft, area.top), Size(band, area.height), alpha = a)
                val hole = Size(band * 0.45f, 5.dp.toPx())
                var y = area.top + 6.dp.toPx()
                while (y + hole.height < area.bottom - 4.dp.toPx()) {
                    drawRoundRect(
                        look.queueField,
                        Offset(bandLeft + (band - hole.width) / 2f, y),
                        hole,
                        CornerRadius(1.5.dp.toPx()),
                    )
                    y += 12.dp.toPx()
                }
                drawIcon(ink)
                drawWord(ink)
            }
            YtSignature.SIDE -> {
                // no field: the mark grows like the heart did
                val grow = look.icon.dp.toPx() * (0.5f + 0.55f * p.coerceAtMost(1.3f)) * (1f + 0.12f * pop)
                drawIcon(ink, px = grow, alpha = (p * 2f).coerceAtMost(1f))
                drawWord(ink, after = grow / 2f + gap)
            }
            YtSignature.CLAY -> {
                // a dent in the dough: darker at the top edge, lit at the bottom
                drawField(
                    a,
                    Brush.verticalGradient(
                        0f to Color(0xFFD9A991),
                        0.18f to field,
                        1f to Color(0xFFF4D6C4),
                        startY = 0f,
                        endY = area.height,
                    ),
                )
                drawIcon(ink)
                drawWord(ink)
            }
            YtSignature.EDITORIAL -> {
                // the black slip with the word in mono, set in as the card slides off
                drawField((p * 1.5f).coerceAtMost(1f))
                drawWord(ink, alpha = (p * 1.4f).coerceAtMost(1f), shift = (1f - a) * 24.dp.toPx(), after = -edge + 6.dp.toPx())
            }
            YtSignature.GLASS -> {
                // a glass drop swells under the card
                val r = (16.dp.toPx() + 10.dp.toPx() * a) * (1f + 0.14f * pop)
                drawCircle(GlassRim, r + 3.dp.toPx(), anchor, alpha = 0.6f * a)
                drawCircle(
                    Brush.radialGradient(listOf(Color(0xE6FFFFFF), Color(0x80FFFFFF)), center = anchor - Offset(r * 0.3f, r * 0.3f), radius = r * 1.4f),
                    r,
                    anchor,
                    alpha = a,
                )
                drawCircle(Color.White, r, anchor, alpha = 0.9f * a, style = Stroke(1.dp.toPx()))
                drawIcon(ink, px = min(iconSize, r * 1.1f))
                drawWord(ink, after = r + gap)
            }
            YtSignature.POP -> {
                // a blob that inflates with the pull and jiggles when it arms
                val blob = 54.dp.toPx() * (0.6f + 0.4f * a) * (1f + 0.15f * pop)
                val shape = BlobShape(0.62f, 0.5f, 0.56f, 0.64f).createOutline(Size(blob, blob), layoutDirection, this)
                translate(anchor.x - blob / 2f, anchor.y - blob / 2f) { drawOutline(shape, field) }
                drawIcon(ink)
                drawWord(ink, after = blob / 2f + gap)
            }
            YtSignature.SHAPES -> {
                // a cookie that turns with the pull, a detent every 30 degrees
                val cookie = 52.dp.toPx() * (0.6f + 0.4f * a) * (1f + 0.1f * pop)
                val shape = CookieShape(9, 0.08f).createOutline(Size(cookie, cookie), layoutDirection, this)
                rotate(p * 90f * (if (queue) 1f else -1f), anchor) {
                    translate(anchor.x - cookie / 2f, anchor.y - cookie / 2f) { drawOutline(shape, field) }
                }
                drawIcon(ink)
                drawWord(Color.White, after = cookie / 2f + gap)
            }
            YtSignature.FROST -> {
                // frost that clears around the mark as the pull grows
                drawField(1f, Brush.radialGradient(
                    listOf(Color.Transparent, Color(0x14FFFFFF), Color(0x52FFFFFF)),
                    center = anchor - Offset(area.left, area.top),
                    radius = 22.dp.toPx() + 110.dp.toPx() * a,
                ))
                drawIcon(ink)
                drawWord(ink)
            }
            YtSignature.KIESEL -> {
                // a capsule that grows out of the mark, word inside
                val h = 40.dp.toPx() * (1f + 0.08f * pop)
                val reach = if (roomy) (label.size.width + gap * 1.6f) * ((p - 0.3f) / 0.6f).coerceIn(0f, 1f) else 0f
                val w = h + reach
                val left = if (queue) anchor.x - h / 2f else anchor.x + h / 2f - w
                drawRoundRect(field, Offset(left, anchor.y - h / 2f), Size(w, h), CornerRadius(h / 2f), alpha = (a * 1.4f).coerceAtMost(1f))
                drawIcon(ink)
                drawWord(ink, alpha = ((p - 0.45f) / 0.4f).coerceIn(0f, 1f) * (if (roomy) 1f else 0f))
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// The token that flies to the mini player or the draft pill

/** A queued card's small copy on its way. [onLand] runs once, also when the flight is cut short. */
internal class YtFlight(
    val palette: YtPalette,
    val thumbnail: String?,
    /** The card where it was let go, in window px. */
    val from: Rect?,
    /** px/s: the finger's throw. */
    val velocity: Offset,
    /** To the docked mini player, else to the draft pill. */
    val toMini: Boolean,
    private val onLand: () -> Unit,
) {
    private var landed = false

    fun land() {
        if (landed) return
        landed = true
        onLand()
    }
}

/**
 * YT: the layer the tokens fly in, above everything in the window (the player container, the
 * navigation bar) but below the drag layer. It never takes a touch and draws nothing while no
 * token flies.
 */
object YtFlightLayer {
    private const val TAG = "yt_flight_layer"

    private val flights = mutableStateListOf<YtFlight>()

    /** Layers in a window right now (a recreated activity can briefly have two). */
    private var live = 0

    /** Adds the layer to [activity]'s content; once per activity, only for the designs. */
    fun attach(activity: ComponentActivity) {
        if (YtDesign.current().palette == null) return
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        if (content.findViewWithTag<View>(TAG) != null) return

        val host = Host(activity).apply {
            tag = TAG
            // above the player container (20dp), below the drag layer (24dp)
            elevation = 22f * resources.displayMetrics.density
            outlineProvider = null
            isClickable = false
            isLongClickable = false
            isFocusable = false
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            setViewTreeLifecycleOwner(activity)
            setViewTreeSavedStateRegistryOwner(activity)
            setViewTreeViewModelStoreOwner(activity)
        }
        val layer = ComposeView(activity).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent { Layer() }
        }
        host.addView(layer, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        host.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                live++
            }

            override fun onViewDetachedFromWindow(v: View) {
                live = (live - 1).coerceAtLeast(0)
                if (live > 0) return
                // whatever was still flying lands at once, so the counts stay right
                flights.toList().forEach { it.land() }
                flights.clear()
            }
        })
        content.addView(host, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    /** Lets [flight] fly, or land at once when there is no motion or no layer. */
    internal fun launch(flight: YtFlight, fly: Boolean) {
        if (fly && live > 0 && flight.from != null) flights += flight else flight.land()
    }

    private class Host(context: Context) : FrameLayout(context) {
        override fun dispatchTouchEvent(ev: MotionEvent): Boolean = false

        override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean = false
    }

    @Composable
    private fun Layer() {
        var root by remember { mutableStateOf(Offset.Zero) }
        var size by remember { mutableStateOf(IntSize.Zero) }
        Box(
            Modifier
                .fillMaxSize()
                .onGloballyPositioned {
                    root = it.positionInWindow()
                    size = it.size
                },
        ) {
            for (flight in flights) {
                key(flight) { Token(flight, { root }, { size }) }
            }
        }
    }

    @Composable
    private fun Token(flight: YtFlight, root: () -> Offset, layer: () -> IntSize) {
        val density = LocalDensity.current
        val start = flight.from?.center ?: Offset.Zero
        var at by remember { mutableStateOf(start) }
        var progress by remember { mutableFloatStateOf(0f) }
        var lean by remember { mutableFloatStateOf(0f) }
        var end by remember { mutableFloatStateOf(0.35f) }
        val appear = remember { Animatable(0.8f) }
        val tokenWidth = 112.dp
        val tokenHeight = 63.dp
        val shape = tokenShape(flight.palette)

        fun target(): Offset {
            if (flight.toMini) {
                YtDrag.miniBounds?.let { return Offset(it.exactCenterX(), it.exactCenterY()) }
            } else {
                YtQueue.draftPillBounds?.let { return it.center }
            }
            // no target on screen yet: just above the bar, in the middle
            val r = root()
            val s = layer()
            return Offset(r.x + s.width / 2f, r.y + s.height - YtChrome.navOverlapPx - with(density) { 44.dp.toPx() })
        }

        LaunchedEffect(flight) {
            launch { appear.animateTo(1f, spring(dampingRatio = 0.6f, stiffness = 700f)) }
            val physics = YtFlightSpring(start.x, start.y, flight.velocity.x, flight.velocity.y)
            val first = target()
            val total = (start - first).getDistance().coerceAtLeast(1f)
            // the token ends a little smaller than the mini player's picture, or as a dot on the pill
            val miniHeight = YtDrag.miniBounds?.height()?.toFloat()
            end = if (flight.toMini && miniHeight != null) {
                (miniHeight * 0.55f / with(density) { tokenHeight.toPx() }).coerceIn(0.22f, 0.6f)
            } else 0.28f
            try {
                var last = nextFrame()
                val begin = last
                while (true) {
                    val now = nextFrame()
                    val t = target()
                    physics.step(t.x, t.y, (now - last) / 1_000_000_000f)
                    last = now
                    at = Offset(physics.x, physics.y)
                    progress = (1f - physics.distanceTo(t.x, t.y) / total).coerceIn(0f, 1f)
                    lean = (physics.vx / 4000f * 14f).coerceIn(-14f, 14f)
                    if (physics.arrived(t.x, t.y) || now - begin > 1_600_000_000L) break
                }
            } finally {
                flight.land()
                flights.remove(flight)
            }
        }

        Box(
            Modifier
                .size(tokenWidth, tokenHeight)
                .graphicsLayer {
                    val r = root()
                    val k = progress * progress
                    val scale = (1f + (end - 1f) * k) * appear.value
                    translationX = at.x - r.x - size.width / 2f
                    translationY = at.y - r.y - size.height / 2f
                    scaleX = scale
                    scaleY = scale
                    rotationZ = lean
                    alpha = 1f - ((progress - 0.86f) / 0.14f).coerceIn(0f, 1f)
                    shadowElevation = 10.dp.toPx()
                    this.shape = shape
                    clip = true
                }
                .background(flight.palette.surface)
                .then(if (flight.palette.signature == YtSignature.EDITORIAL) Modifier.border(1.dp, Color.Black) else Modifier),
        ) {
            AsyncImage(
                model = flight.thumbnail,
                contentDescription = null,
                imageLoader = ImageHelper.loader(),
                contentScale = ContentScale.Crop,
                colorFilter = if (flight.palette.signature == YtSignature.EDITORIAL) ytGrayscale else null,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }

    private fun tokenShape(palette: YtPalette): Shape = when (palette.signature) {
        YtSignature.EDITORIAL -> RectangleShape
        YtSignature.POP -> BlobShape(0.6f, 0.5f, 0.56f, 0.64f)
        YtSignature.SHAPES -> RoundedCornerShape(topStart = 22.dp, topEnd = 8.dp, bottomEnd = 22.dp, bottomStart = 8.dp)
        YtSignature.KIESEL -> RoundedCornerShape(50)
        YtSignature.CLAY -> RoundedCornerShape(18.dp)
        YtSignature.AFTER -> RoundedCornerShape(4.dp)
        else -> RoundedCornerShape(10.dp)
    }
}

/** The time of the next frame, in ns. */
private suspend fun nextFrame(): Long = withFrameNanos { it }
