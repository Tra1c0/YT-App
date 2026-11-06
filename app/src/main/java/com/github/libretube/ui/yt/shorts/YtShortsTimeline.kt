package com.github.libretube.ui.yt.shorts

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.snap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import com.github.libretube.ui.yt.LocalYtMotion
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.math.abs
import kotlin.math.roundToInt

/** What the timeline tells the view. */
class YtShortsTimelineEvents(
    /** The timeline opened (finger down in the zone) or closed again (after the fade). */
    val onOpen: (Boolean) -> Unit,
    /** The finger began to scrub, moved to a position, and let go there. */
    val onScrubStart: () -> Unit,
    val onScrub: (Long) -> Unit,
    val onSeek: (Long) -> Unit,
    /** Haptics: the finger takes hold, a new second passes under it, it lets go. */
    val onGrab: () -> Unit,
    val onSecond: () -> Unit,
    val onRelease: () -> Unit,
)

/**
 * YT: the hidden timeline of the Shorts view (8.1). Only a thin line shows while a short plays
 * (or nothing, see the setting); a finger on the zone along the bottom edge, above the
 * navigation bar, makes the bar grow in under it. Dragging sideways scrubs to exact positions
 * with the time above the finger and a still of that moment where the short has stills;
 * letting go seeks there, and the bar fades out [YtShortsScrub.HIDE_AFTER_MS] later.
 *
 * The position is read every frame while it plays and drawn in the draw and layout phases
 * only, so playback does not recompose the view.
 *
 * [bottomInset] is the navigation bar; [frame] gives the still of a moment, or null when the
 * short has none.
 */
@Composable
fun YtShortsTimeline(
    style: YtShortsStyle,
    position: () -> Long,
    duration: () -> Long,
    playing: Boolean,
    showLine: Boolean,
    bottomInset: Dp,
    frame: (suspend (Long) -> ImageBitmap?)?,
    events: YtShortsTimelineEvents,
    description: String,
    modifier: Modifier = Modifier,
) {
    val motion = LocalYtMotion.current
    val density = LocalDensity.current
    val currentEvents by rememberUpdatedState(events)
    val currentPosition by rememberUpdatedState(position)
    val currentDuration by rememberUpdatedState(duration)

    var now by remember { mutableLongStateOf(position()) }
    var total by remember { mutableLongStateOf(duration()) }
    var scrubMs by remember { mutableLongStateOf(0L) }
    var scrubbing by remember { mutableStateOf(false) }
    var fingerDown by remember { mutableStateOf(false) }
    var open by remember { mutableStateOf(false) }
    var lifted by remember { mutableIntStateOf(0) }
    var still by remember { mutableStateOf<ImageBitmap?>(null) }
    var labelWidth by remember { mutableIntStateOf(0) }
    var boxWidth by remember { mutableIntStateOf(0) }
    val reveal = remember { Animatable(0f) }
    val press = remember { Animatable(0f) }

    // the clock: every frame while it plays, a few times a second while it rests
    LaunchedEffect(playing) {
        while (true) {
            if (playing) withFrameNanos { } else delay(200)
            if (!scrubbing) now = currentPosition()
            total = currentDuration()
        }
    }
    LaunchedEffect(open) {
        reveal.animateTo(if (open) 1f else 0f, if (motion) style.reveal() else snap())
    }
    LaunchedEffect(scrubbing) {
        press.animateTo(if (scrubbing) 1f else 0f, if (motion) style.press() else snap())
    }
    // the fade after the finger lifts; a new touch in the meantime keeps it open
    LaunchedEffect(lifted) {
        if (lifted == 0) return@LaunchedEffect
        delay(YtShortsScrub.HIDE_AFTER_MS)
        if (!fingerDown) {
            open = false
            currentEvents.onOpen(false)
        }
    }
    // the still of the moment under the finger, every half second of the timeline
    LaunchedEffect(frame) {
        // a new short: no still of the last one
        still = null
        val source = frame ?: return@LaunchedEffect
        snapshotFlow { if (scrubbing) scrubMs / 500L else -1L }
            .distinctUntilChanged()
            .collectLatest { step ->
                if (step < 0) return@collectLatest
                val bitmap = runCatching { source(step * 500L) }.getOrNull()
                if (bitmap != null) still = bitmap
            }
    }

    val zone = YtShortsScrub.ZONE_DP.dp
    val padding = 16.dp
    val shown = { if (scrubbing) scrubMs else now }
    // the label changes once a second; the clock ticks every frame without recomposing this
    val label by remember {
        derivedStateOf { YtShortsScrub.label(if (scrubbing) scrubMs else now) + " / " + YtShortsScrub.label(total) }
    }

    Box(
        modifier
            .fillMaxWidth()
            .height(zone + bottomInset + 190.dp)
            .onSizeChanged { boxWidth = it.width },
    ) {
        // the scrim that keeps the time and the bar readable over a bright picture
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(zone + bottomInset + 150.dp)
                .graphicsLayer { alpha = reveal.value.coerceIn(0f, 1f) }
                .background(Brush.verticalGradient(0f to Color.Transparent, 1f to Color(0xB3000000))),
        )

        // the still and the time above the handle
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(bottom = bottomInset + zone - 6.dp)
                .onSizeChanged { labelWidth = it.width }
                .offset { offsetAt(shown(), total, boxWidth.toFloat(), padding.toPx(), labelWidth, 8.dp.toPx()) }
                .graphicsLayer {
                    val r = reveal.value.coerceIn(0f, 1f)
                    alpha = r
                    translationY = (1f - r) * 12.dp.toPx()
                },
        ) {
            val bitmap = still
            if (bitmap != null && scrubbing) {
                val height = 132.dp
                val width = with(density) {
                    if (bitmap.height > 0) (height.toPx() * bitmap.width / bitmap.height).toDp() else height
                }.coerceIn(56.dp, 200.dp)
                Image(
                    bitmap = bitmap,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .padding(bottom = 8.dp)
                        .size(width, height)
                        .clip(style.chipShape)
                        .border(2.dp, style.chip, style.chipShape),
                )
            }
            Text(
                text = label,
                color = style.chipInk,
                style = TextStyle(fontFamily = style.timeFont, fontWeight = FontWeight.Bold, fontSize = 14.sp, lineHeight = 18.sp),
                modifier = Modifier
                    .clip(style.chipShape)
                    .background(style.chip)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }

        // the bar: a thin line at rest, the full bar while open
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = bottomInset)
                .fillMaxWidth()
                .height(zone)
                .drawBehind {
                    val r = reveal.value.coerceIn(0f, 1.2f)
                    val lineAlpha = if (showLine) 0.9f else 0f
                    val alpha = lerp(lineAlpha, 1f, r.coerceAtMost(1f))
                    if (alpha <= 0f) return@drawBehind
                    val thick = lerp(2.dp.toPx(), style.barHeight.toPx(), r) * (1f + 0.25f * press.value)
                    val side = lerp(0f, padding.toPx(), r.coerceAtMost(1f))
                    val centerY = lerp(size.height - 1.dp.toPx(), size.height / 2f, r.coerceAtMost(1f))
                    val top = centerY - thick / 2f
                    val width = size.width - 2f * side
                    val played = if (total > 0) (shown().toFloat() / total).coerceIn(0f, 1f) else 0f
                    val corner = if (style.barShape == RectangleShape) CornerRadius.Zero else CornerRadius(thick / 2f)
                    drawRoundRect(style.track, Offset(side, top), Size(width, thick), corner, alpha = alpha)
                    drawRoundRect(style.accent, Offset(side, top), Size(width * played, thick), corner, alpha = alpha)
                },
        )

        // the handle
        Box(
            Modifier
                .align(Alignment.BottomStart)
                .padding(bottom = bottomInset + (zone - style.knobHeight) / 2)
                .offset {
                    val x = YtShortsScrub.x(shown(), total, boxWidth.toFloat(), padding.toPx())
                    IntOffset((x - style.knobWidth.toPx() / 2f).roundToInt(), 0)
                }
                .size(style.knobWidth, style.knobHeight)
                .graphicsLayer {
                    val r = reveal.value.coerceAtLeast(0f)
                    val swell = 1f + 0.3f * press.value
                    scaleX = r * swell
                    scaleY = r * swell
                    alpha = r.coerceAtMost(1f)
                    if (style.knobTurns && total > 0) rotationZ = 360f * shown() / total
                }
                .then(
                    if (style.knobOutline) {
                        Modifier.border(3.dp, style.accent, style.knobShape).background(Color(0x99000000), style.knobShape)
                    } else {
                        Modifier.background(style.accent, style.knobShape).border(1.5.dp, Color(0x66000000), style.knobShape)
                    },
                ),
        )

        // the zone that wakes the timeline; it takes every touch that starts in it, so the
        // pager does not scroll from there
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(zone + bottomInset)
                .testTag("yt_shorts_timeline")
                .semantics { contentDescription = description }
                .pointerInput(Unit) {
                    val padPx = padding.toPx()
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        fingerDown = true
                        open = true
                        currentEvents.onOpen(true)
                        currentEvents.onGrab()
                        val startX = down.position.x
                        var moved = false
                        var last = now
                        scrubMs = now
                        try {
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) {
                                    change.consume()
                                    break
                                }
                                if (!moved && abs(change.position.x - startX) > viewConfiguration.touchSlop) {
                                    moved = true
                                    scrubbing = true
                                    currentEvents.onScrubStart()
                                }
                                if (moved) {
                                    val fraction = YtShortsScrub.fraction(change.position.x, size.width.toFloat(), padPx)
                                    val ms = YtShortsScrub.position(fraction, total)
                                    if (YtShortsScrub.crossedSecond(last, ms)) currentEvents.onSecond()
                                    last = ms
                                    scrubMs = ms
                                    currentEvents.onScrub(ms)
                                }
                                change.consume()
                            }
                        } finally {
                            fingerDown = false
                            if (moved) {
                                now = scrubMs
                                currentEvents.onSeek(scrubMs)
                            }
                            scrubbing = false
                            currentEvents.onRelease()
                            lifted++
                        }
                    }
                },
        )
    }
}

// the label above the handle, kept on screen
private fun offsetAt(position: Long, total: Long, width: Float, padding: Float, label: Int, margin: Float): IntOffset {
    val x = YtShortsScrub.x(position, total, width, padding)
    val left = (x - label / 2f).coerceIn(margin, (width - label - margin).coerceAtLeast(margin))
    return IntOffset(left.roundToInt(), 0)
}
