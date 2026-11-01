package com.github.libretube.ui.yt.player

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView

/** Where the source is on screen, read when the finger moves (not a state: nothing recomposes). */
private class SourceCoordinates {
    var coordinates: LayoutCoordinates? = null
}

/**
 * YT: makes the share action of a video page a source for sharing onto the timeline: held still
 * for a long press, the finger then drags it, and [bridge] is told in window px. A tap stays the
 * action's own click; moving before the long press stays a scroll. From the long press on the
 * gesture is taken on the Initial pass, so the action under it sees it cancelled and never clicks,
 * and the scroll view and the player's swipe above may not take it. Apply it before (outside) the
 * action's own clickable. Null [bridge]: nothing changes.
 */
@Composable
fun Modifier.ytShareScrubSource(bridge: YtShareScrubBridge?): Modifier {
    if (bridge == null) return this
    val view = LocalView.current
    val source = remember { SourceCoordinates() }
    return this
        .onGloballyPositioned { source.coordinates = it }
        .pointerInput(bridge) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                val slop = viewConfiguration.touchSlop
                var position = down.position
                var notLong = false
                // up, past the slop or a second finger before the long press: not ours
                withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == down.id }
                        if (change == null || !change.pressed || change.isConsumed || event.changes.size > 1 ||
                            (change.position - down.position).getDistance() > slop
                        ) {
                            notLong = true
                            break
                        }
                        position = change.position
                    }
                }
                if (notLong) return@awaitEachGesture
                val coordinates = source.coordinates
                if (coordinates == null || !coordinates.isAttached) return@awaitEachGesture

                view.parent?.requestDisallowInterceptTouchEvent(true)
                var last: Offset = coordinates.localToWindow(position)
                bridge.begin(last.x, last.y)
                var ended = false
                try {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        // ours now: the action below and the scrollers above see it consumed
                        event.changes.forEach { it.consume() }
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (coordinates.isAttached) last = coordinates.localToWindow(change.position)
                        if (!change.pressed) {
                            ended = true
                            bridge.end(last.x, last.y, cancelled = false)
                            break
                        }
                        bridge.move(last.x, last.y)
                    }
                } finally {
                    // the finger went another way, or the source left the screen
                    if (!ended) bridge.end(last.x, last.y, cancelled = true)
                }
            }
        }
}
