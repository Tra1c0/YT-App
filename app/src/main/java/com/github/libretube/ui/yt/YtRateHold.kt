package com.github.libretube.ui.yt

import android.content.Context
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
 * The hold gesture and the tiny rating mark of the like button. Goes in front of the button's
 * own modifiers (as [WatchAction.gesture]); it draws nothing but the mark.
 */
@Composable
internal fun Modifier.ytRateHold(state: YtRateState, markColor: Color): Modifier {
    if (state.videoId == null) return this
    val view = LocalView.current
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
            when (rating) {
                YtRate.LIKE -> stateDescription = likedText
                YtRate.DISLIKE -> stateDescription = dislikedText
                YtRate.NONE -> {}
            }
            customActions = listOf(
                CustomAccessibilityAction(likeLabel) { state.choose(YtRate.LIKE); true },
                CustomAccessibilityAction(dislikeLabel) { state.choose(YtRate.DISLIKE); true },
            )
        }
        .drawWithContent {
            drawContent()
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
                try {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
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
            RateTile(style, YtHoldDir.DOWN, R.drawable.ic_dislike, stringResource(R.string.yt_rate_dislike), state.hovered == YtRate.DISLIKE, state.rating == YtRate.DISLIKE)
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
            Text(
                text = label,
                color = content,
                maxLines = 1,
                style = TextStyle(
                    fontFamily = style.typeface?.let { FontFamily(it) },
                    fontWeight = FontWeight.Medium,
                    fontSize = 11.sp,
                ),
                modifier = Modifier.padding(top = 3.dp, start = 4.dp, end = 4.dp),
            )
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
