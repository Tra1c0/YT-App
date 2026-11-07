package com.github.libretube.ui.yt

import android.content.Context
import android.os.SystemClock
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import com.github.libretube.helpers.PlayerHelper
import kotlin.math.PI
import kotlin.math.sin
import android.widget.Toast
import androidx.annotation.DrawableRes
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.github.libretube.R
import com.github.libretube.ui.yt.player.YtHoldDir
import com.github.libretube.ui.yt.player.YtHoldTileShape
import com.github.libretube.ui.yt.player.YtHoldTileStyle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/*
 * YT: rating from the like button. The button keeps its look and its tap; held, two tiles
 * (like, dislike) pop out above it in the style of the design's hold tiles. Sliding the finger
 * onto one arms it (a tick), letting go there chooses it (a confirm); letting go elsewhere
 * changes nothing. Choosing the rating the video already has takes it back. The chosen rating
 * stays on the button as a tiny mark: a dot for a like, a small down thumb for a dislike.
 * Resting on the dislike tile for a moment (YtRevealTimer) reveals the Return YouTube Dislike
 * count in it, with a tick; and a small green-red bar at the button shows the liked share
 * (YtSentiment), in the form of the design.
 */

private val TileWidth = 76.dp
private val TileHeight = 60.dp
private val TileGap = 10.dp

// room around the tiles for the armed scale and the offset shadows
private val TilePad = 8.dp
private val PopupGap = 8.dp

/** The rating of one video page and its hold gesture. */
@Stable
internal class YtRateState(val videoId: String?, private val context: Context, private val scope: CoroutineScope) {
    var rating by mutableStateOf(YtRate.NONE)
        internal set
    var open by mutableStateOf(false)
        private set
    var hovered by mutableStateOf<YtRate?>(null)
        internal set

    /** The Return YouTube Dislike count of the video, negative when there is none. */
    var dislikes by mutableStateOf(-1L)
        internal set

    /** The dislike count was revealed by a hover; it then shows at once on every later hold. */
    var revealed by mutableStateOf(false)
        internal set

    val canReveal: Boolean get() = videoId != null && dislikes >= 0

    /** The button in window coordinates, taken when the hold starts. */
    var anchor by mutableStateOf<Rect?>(null)
        private set

    internal var coordinates: LayoutCoordinates? = null

    /** Where the popup put the tiles, in window coordinates (set while it is laid out). */
    internal var tilesOrigin: Offset? = null

    internal fun openAt(bounds: Rect) {
        tilesOrigin = null
        hovered = null
        anchor = bounds
        open = true
    }

    internal fun close() {
        open = false
        hovered = null
    }

    /** The tile under [window] (a point in window coordinates), with a little slack around each. */
    internal fun tileAt(window: Offset, density: Float): YtRate? {
        val origin = tilesOrigin ?: return null
        val pad = TilePad.value * density
        val w = TileWidth.value * density
        val h = TileHeight.value * density
        val gap = TileGap.value * density
        val slack = 10f * density
        listOf(YtRate.LIKE, YtRate.DISLIKE).forEachIndexed { i, rate ->
            val left = origin.x + pad + i * (w + gap)
            val top = origin.y + pad
            val rect = Rect(left - slack, top - slack, left + w + slack, top + h + slack)
            if (rect.contains(window)) return rate
        }
        return null
    }

    /** Applies [chosen]; choosing the rating it already has takes it back. */
    internal fun choose(chosen: YtRate) {
        val id = videoId ?: return
        val previous = rating
        val next = if (chosen == previous) YtRate.NONE else chosen
        rating = next
        YtRatings.storeLocal(id, next)
        if (YtRatings.atYouTube) {
            scope.launch {
                YtRatings.send(id, next).onFailure { error ->
                    // only undo if nothing newer was chosen in the meantime
                    if (rating == next) {
                        rating = previous
                        YtRatings.storeLocal(id, previous)
                    }
                    val reason = error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName
                    Toast.makeText(context, context.getString(R.string.yt_rate_failed, reason), Toast.LENGTH_LONG).show()
                }
            }
        } else if (!YtRatings.localHintShown) {
            YtRatings.localHintShown = true
            Toast.makeText(context, R.string.yt_rate_local_only, Toast.LENGTH_LONG).show()
        }
    }
}

@Composable
internal fun rememberYtRateState(videoId: String?): YtRateState {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state = remember(videoId) { YtRateState(videoId, context.applicationContext, scope) }
    LaunchedEffect(state) {
        val id = videoId ?: return@LaunchedEffect
        state.rating = YtRatings.local(id)
        // signed in, YouTube knows better (a rating made on another device)
        if (YtRatings.atYouTube) {
            YtRatings.remote(id)?.let { remote ->
                state.rating = remote
                YtRatings.storeLocal(id, remote)
            }
        }
    }
    return state
}

/**
 * What the small bar at the like button shows: the liked [share] (0 to 1) between the design's
 * [green] and [red]. [description] is read out with the button.
 */
@Immutable
internal class YtSentimentBar(
    val share: Float,
    val green: Color,
    val red: Color,
    val style: YtSentimentStyle,
    val description: String,
)

/** The bar for [likes] and [dislikes] in [palette], or null when RYD has no numbers or is off. */
@Composable
internal fun rememberYtSentimentBar(likes: Long, dislikes: Long, palette: YtPalette): YtSentimentBar? {
    val share = YtSentiment.share(likes, dislikes)
    val text = if (share != null) {
        stringResource(R.string.yt_ryd_bar_description, YtSentiment.percent(share), YtCountFormat.format(dislikes))
    } else {
        null
    }
    return remember(share, palette, text) {
        if (share == null || text == null || !PlayerHelper.localRYD) return@remember null
        val (green, red) = YtSentiment.colors(palette.signature, palette.background.toArgb())
        YtSentimentBar(share, Color(green), Color(red), YtSentimentStyle.of(palette.signature), text)
    }
}

/**
 * The hold gesture and the tiny rating mark of the like button. Goes in front of the button's
 * own modifiers (as [WatchAction.gesture]); it draws nothing but the mark.
 */
@Composable
internal fun Modifier.ytRateHold(state: YtRateState, markColor: Color, bar: YtSentimentBar? = null): Modifier {
    if (state.videoId == null) return this
    val view = LocalView.current
    // the bar grows out from the like end once, when the numbers are there
    val grow = remember(state.videoId) { Animatable(0f) }
    val growSpec = ytSpec(tween<Float>(560, easing = YtEaseOut))
    LaunchedEffect(bar != null) { if (bar != null) grow.animateTo(1f, growSpec) }
    val countText = if (state.dislikes >= 0) stringResource(R.string.yt_ryd_count_description, YtCountFormat.format(state.dislikes)) else null
    val haptics = rememberYtHaptics()
    val down = painterResource(R.drawable.ic_dislike)
    val likeLabel = stringResource(R.string.yt_rate_like)
    val dislikeLabel = stringResource(R.string.yt_rate_dislike)
    val likedText = stringResource(R.string.yt_rate_state_liked)
    val dislikedText = stringResource(R.string.yt_rate_state_disliked)
    val rating = state.rating
    return this
        .onGloballyPositioned { state.coordinates = it }
        .semantics {
            val rated = when (rating) {
                YtRate.LIKE -> likedText
                YtRate.DISLIKE -> dislikedText
                YtRate.NONE -> null
            }
            // a contentDescription would replace the button's own label, so the numbers go here
            val numbers = bar?.description ?: countText?.takeIf { state.revealed }
            listOfNotNull(rated, numbers).joinToString(". ").takeIf { it.isNotEmpty() }?.let { stateDescription = it }
            customActions = listOf(
                CustomAccessibilityAction(likeLabel) { state.choose(YtRate.LIKE); true },
                CustomAccessibilityAction(dislikeLabel) { state.choose(YtRate.DISLIKE); true },
            )
        }
        .drawWithContent {
            drawContent()
            if (bar != null) drawSentiment(bar, grow.value)
            // the mark sits inside the top end corner, also inside a pill's rounding
            val inset = 9.dp.toPx()
            val center = Offset(size.width - inset, inset)
            when (rating) {
                YtRate.LIKE -> drawCircle(markColor, radius = 2.5.dp.toPx(), center = center)
                YtRate.DISLIKE -> {
                    val side = 9.dp.toPx()
                    translate(center.x - side / 2, center.y - side / 2) {
                        with(down) { draw(Size(side, side), colorFilter = ColorFilter.tint(markColor)) }
                    }
                }
                YtRate.NONE -> {}
            }
        }
        .pointerInput(state) {
            awaitEachGesture {
                val first = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                val slop = viewConfiguration.touchSlop
                var notLong = false
                // up, past the slop or a second finger before the long press: a tap or a scroll, not ours
                withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == first.id }
                        if (change == null || !change.pressed || change.isConsumed || event.changes.size > 1 ||
                            (change.position - first.position).getDistance() > slop
                        ) {
                            notLong = true
                            break
                        }
                    }
                }
                if (notLong) return@awaitEachGesture
                val coordinates = state.coordinates?.takeIf { it.isAttached } ?: return@awaitEachGesture

                view.parent?.requestDisallowInterceptTouchEvent(true)
                state.openAt(Rect(coordinates.localToWindow(Offset.Zero), coordinates.size.toSize()))
                haptics.tick()
                val reveal = YtRevealTimer()
                try {
                    while (true) {
                        // resting on the dislike tile: wake up when the reveal is due, even without a move
                        val wait = if (state.canReveal && !state.revealed) reveal.remaining(SystemClock.uptimeMillis()) else null
                        val event = if (wait != null) {
                            withTimeoutOrNull(wait.coerceAtLeast(1L)) { awaitPointerEvent(PointerEventPass.Initial) }
                        } else {
                            awaitPointerEvent(PointerEventPass.Initial)
                        }
                        if (event == null) {
                            if (reveal.due(SystemClock.uptimeMillis()) && state.hovered == YtRate.DISLIKE) {
                                state.revealed = true
                                haptics.threshold(true)
                            }
                            continue
                        }
                        // ours now: the button's tap and the scrollers above see it consumed
                        event.changes.forEach { it.consume() }
                        val change = event.changes.firstOrNull { it.id == first.id } ?: break
                        val over = if (coordinates.isAttached) {
                            state.tileAt(coordinates.localToWindow(change.position), density)
                        } else {
                            null
                        }
                        if (!change.pressed) {
                            if (over != null) {
                                haptics.confirm()
                                state.choose(over)
                            }
                            break
                        }
                        if (over != state.hovered) {
                            state.hovered = over
                            reveal.hover(over == YtRate.DISLIKE, SystemClock.uptimeMillis())
                            if (over != null) haptics.tick()
                        }
                    }
                } finally {
                    state.close()
                    view.parent?.requestDisallowInterceptTouchEvent(false)
                }
            }
        }
}

/** The two tiles while the like button is held. Placed anywhere in the page; it positions itself. */
@Composable
internal fun YtRateTiles(state: YtRateState) {
    if (!state.open) return
    val anchor = state.anchor ?: return
    val context = LocalContext.current
    val density = LocalDensity.current
    val style = remember { YtHoldTileStyle.current(context) }
    val provider = remember(anchor) {
        object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect,
                windowSize: IntSize,
                layoutDirection: LayoutDirection,
                popupContentSize: IntSize,
            ): IntOffset {
                val gap = with(density) { PopupGap.toPx() }
                val w = popupContentSize.width.toFloat()
                val h = popupContentSize.height.toFloat()
                val x = (anchor.center.x - w / 2).coerceIn(0f, (windowSize.width - w).coerceAtLeast(0f))
                // above the button, or below it when there is no room above
                var y = anchor.top - gap - h
                if (y < 0f) y = anchor.bottom + gap
                val offset = IntOffset(x.toInt(), y.toInt())
                state.tilesOrigin = Offset(offset.x.toFloat(), offset.y.toFloat())
                return offset
            }
        }
    }
    val appear = remember { Animatable(0f) }
    val appearSpec = ytSpec(spring<Float>(dampingRatio = style.damping, stiffness = style.stiffness))
    LaunchedEffect(Unit) { appear.animateTo(1f, appearSpec) }
    Popup(popupPositionProvider = provider, properties = PopupProperties(focusable = false)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(TileGap),
            modifier = Modifier
                .graphicsLayer {
                    val a = appear.value
                    alpha = a.coerceIn(0f, 1f)
                    scaleX = 0.7f + 0.3f * a
                    scaleY = 0.7f + 0.3f * a
                    translationY = (1f - a) * 12.dp.toPx()
                    transformOrigin = TransformOrigin(0.5f, 1f)
                }
                .padding(TilePad),
        ) {
            RateTile(style, YtHoldDir.UP, R.drawable.ic_like, stringResource(R.string.yt_rate_like), state.hovered == YtRate.LIKE, state.rating == YtRate.LIKE)
            val count = if (state.revealed && state.dislikes >= 0) YtCountFormat.format(state.dislikes) else null
            RateTile(style, YtHoldDir.DOWN, R.drawable.ic_dislike, stringResource(R.string.yt_rate_dislike), state.hovered == YtRate.DISLIKE, state.rating == YtRate.DISLIKE, count)
        }
    }
}

/** One tile, drawn like the design's hold tiles on the video: its fill, edge, shadow and armed colour. */
@Composable
private fun RateTile(
    style: YtHoldTileStyle,
    dir: YtHoldDir,
    @DrawableRes icon: Int,
    label: String,
    armed: Boolean,
    current: Boolean,
    count: String? = null,
) {
    val fill = Color(if (armed) style.armedFill else style.fill(dir))
    val content = Color(style.on(fill.toArgb()))
    val shape: Shape = when {
        style.cornerDp < 0f -> RoundedCornerShape(50)
        else -> RoundedCornerShape(style.cornerDp.dp)
    }
    val scale by animateFloatAsState(
        if (armed) 1.08f else 1f,
        ytSpec(spring(dampingRatio = style.damping, stiffness = style.stiffness)),
        label = "rateTile",
    )
    // Movo frames only the armed tile; the other designs always draw their edge
    val strokeOn = style.stroke != 0 && style.strokeDp > 0f && (style.shape != YtHoldTileShape.TILE || armed)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .size(TileWidth, TileHeight)
            .drawBehind {
                if (style.shadow != 0) {
                    val outline = shape.createOutline(size, layoutDirection, this)
                    translate(style.shadowDxDp.dp.toPx(), style.shadowDyDp.dp.toPx()) {
                        drawOutline(outline, Color(style.shadow))
                    }
                }
            }
            .clip(shape)
            .background(fill)
            .then(if (strokeOn) Modifier.border(style.strokeDp.dp, Color(style.stroke), shape) else Modifier),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(painterResource(icon), contentDescription = null, tint = content, modifier = Modifier.size(22.dp))
            val family = style.typeface?.let { FontFamily(it) }
            // the revealed dislike count takes the label's place, marked as an estimate
            Crossfade(targetState = count, animationSpec = ytFinite(tween(200)), label = "rateCount") { shown ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = shown ?: label,
                        color = content,
                        maxLines = 1,
                        style = TextStyle(
                            fontFamily = family,
                            fontWeight = if (shown != null) FontWeight.Bold else FontWeight.Medium,
                            fontSize = if (shown != null) 12.sp else 11.sp,
                        ),
                        modifier = Modifier.padding(top = 3.dp, start = 4.dp, end = 4.dp),
                    )
                    if (shown != null) {
                        Text(
                            text = stringResource(R.string.yt_ryd_estimate),
                            color = content.copy(alpha = 0.75f),
                            maxLines = 1,
                            style = TextStyle(fontFamily = family, fontSize = 8.5.sp),
                            modifier = Modifier.padding(horizontal = 4.dp),
                        )
                    }
                }
            }
        }
        // the rating the video has now: a small dot in the corner, so choosing it again reads as "take back"
        if (current) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 7.dp, end = 7.dp)
                    .size(5.dp)
                    .background(content, CircleShape),
            )
        }
    }
}

/** The green-red bar of [bar] at this button, grown to [grow] (0 to 1) from the like end. */
private fun DrawScope.drawSentiment(bar: YtSentimentBar, grow: Float) {
    if (grow <= 0f) return
    val s = bar.style
    val rtl = layoutDirection == LayoutDirection.Rtl
    val thick = s.thicknessDp.dp.toPx()
    val gap = s.gapDp.dp.toPx()
    val cap = if (s.roundCaps) StrokeCap.Round else StrokeCap.Butt
    // round caps reach past the ends: keep the whole bar inside its width
    val capRoom = if (s.roundCaps) thick / 2f else 0f
    val stops = YtSentiment.stops(bar.share, bar.green.toArgb(), bar.red.toArgb(), rtl)
        .map { (at, color) -> at to Color(color) }.toTypedArray()

    val left: Float
    val width: Float
    if (s.placement == YtSentimentPlacement.ARC) {
        val radius = minOf(size.width, size.height) / 2f + gap + thick / 2f
        val half = (s.sweepDegrees / 2f) * (PI.toFloat() / 180f)
        width = 2f * radius * sin(half) + thick
        left = center.x - width / 2f
    } else {
        width = size.width * s.widthFraction
        left = s.startDp?.let { start -> if (rtl) size.width - start.dp.toPx() - width else start.dp.toPx() }
            ?: ((size.width - width) / 2f)
    }
    if (width <= thick) return
    val brush = Brush.horizontalGradient(*stops, startX = left, endX = left + width)
    val shown = width * grow.coerceIn(0f, 1f)
    val clipLeft = if (rtl) left + width - shown else left
    clipRect(clipLeft, -size.height, clipLeft + shown, size.height * 3f) {
        when (s.placement) {
            YtSentimentPlacement.UNDER, YtSentimentPlacement.INSIDE -> {
                val y = if (s.placement == YtSentimentPlacement.UNDER) size.height + gap + thick / 2f else size.height - gap - thick / 2f
                drawLine(brush, Offset(left + capRoom, y), Offset(left + width - capRoom, y), thick, cap)
            }
            YtSentimentPlacement.ARC -> {
                val radius = minOf(size.width, size.height) / 2f + gap + thick / 2f
                drawArc(
                    brush = brush,
                    startAngle = 90f - s.sweepDegrees / 2f,
                    sweepAngle = s.sweepDegrees,
                    useCenter = false,
                    topLeft = Offset(center.x - radius, center.y - radius),
                    size = Size(radius * 2f, radius * 2f),
                    style = Stroke(thick, cap = cap),
                )
            }
            YtSentimentPlacement.WAVE -> {
                val amp = s.amplitudeDp.dp.toPx()
                val y0 = size.height + gap + amp
                val wave = 8.dp.toPx()
                val from = left + capRoom
                val to = left + width - capRoom
                val path = Path().apply {
                    moveTo(from, y0)
                    var x = from
                    val step = 1.dp.toPx()
                    while (x < to) {
                        x = minOf(x + step, to)
                        lineTo(x, y0 + amp * sin((x - from) / wave * 2f * PI.toFloat()))
                    }
                }
                drawPath(path, brush, style = Stroke(thick, cap = cap, join = StrokeJoin.Round))
            }
        }
    }
}
