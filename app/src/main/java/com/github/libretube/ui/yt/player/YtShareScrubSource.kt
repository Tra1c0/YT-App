package com.github.libretube.ui.yt.player

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView

private class SourceCoordinates {
    var coordinates: LayoutCoordinates? = null
}

/** The whole share action is a long-press drag source; a tap keeps its ordinary click. */
@Composable
fun Modifier.ytShareScrubSource(bridge: YtShareScrubBridge?): Modifier {
    if (bridge == null) return this
    val view = LocalView.current
    val source = remember { SourceCoordinates() }
    return onGloballyPositioned { source.coordinates = it }
        .pointerInput(bridge) {
            var active = false
            var last = Offset.Zero
            fun position(at: Offset): Offset? = source.coordinates
                ?.takeIf { it.isAttached }?.localToWindow(at)
            fun finish(cancelled: Boolean) {
                if (active) bridge.end(last.x, last.y, cancelled)
                active = false
                view.parent?.requestDisallowInterceptTouchEvent(false)
            }
            detectDragGesturesAfterLongPress(
                onDragStart = { at ->
                    position(at)?.let {
                        last = it
                        active = true
                        view.parent?.requestDisallowInterceptTouchEvent(true)
                        bridge.begin(it.x, it.y)
                    }
                },
                onDrag = { change, _ ->
                    if (active) position(change.position)?.let {
                        last = it
                        bridge.move(it.x, it.y)
                    }
                },
                onDragEnd = { finish(cancelled = false) },
                onDragCancel = { finish(cancelled = true) },
            )
        }
}
