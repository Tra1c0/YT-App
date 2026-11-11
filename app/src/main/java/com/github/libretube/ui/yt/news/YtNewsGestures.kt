package com.github.libretube.ui.yt.news

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.math.max

/**
 * YT: a two-finger pinch that opens the News overlay (fingers spread apart, on the home) or
 * closes it ([inward]: fingers pinched together, on the overlay).
 *
 * It watches in the Initial pass, before the content: one finger, and two fingers that do not
 * (yet) clearly spread, go on untouched to the list, the card swipes and holds and the pull to
 * refresh. Only once the spread is recognised (YtNewsGestureRules.step) are the gesture's events
 * consumed, so the content lets go of it (a list drag stops when its change is consumed). A
 * second finger after the first one already scrolled, a third finger and a two-finger pan never
 * start it. [onProgress] follows a claimed pinch (0..1, and the fingers' centre in window px),
 * [onCommit] fires once at the threshold, [onCancel] when a claimed pinch ends without it (also
 * when the gesture is cancelled or the node leaves, Pitfall P2).
 */
fun Modifier.ytNewsPinch(
    enabled: Boolean,
    inward: Boolean,
    toWindow: (Offset) -> Offset,
    /** Asked when a pinch would be claimed: false leaves it to the content (a card being dragged, a sheet open). */
    allowed: () -> Boolean = { true },
    onProgress: (Float, Offset) -> Unit,
    onCommit: (Offset) -> Unit,
    onCancel: () -> Unit,
): Modifier = if (!enabled) this else pointerInput(inward) {
    val spec = YtNewsGestureRules.Spec(
        claimPx = 28.dp.toPx(),
        commitPx = (if (inward) 110.dp else 96.dp).toPx(),
    )
    val slop = viewConfiguration.touchSlop
    awaitEachGesture {
        val first = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        var firstTravel = 0f
        var ids: Pair<PointerId, PointerId>? = null
        var startGap = 0f
        var startCentre = Offset.Zero
        var claimed = false
        var committed = false
        var finished = false
        try {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val down = event.changes.filter { it.pressed }
                if (down.isEmpty()) break
                // decided: a committed pinch keeps the rest of the gesture (no fling, no tap below)
                if (finished) {
                    if (committed) event.changes.forEach { it.consume() }
                    continue
                }
                val pair = ids
                if (pair == null) {
                    if (down.size == 1) {
                        if (down[0].id == first.id) firstTravel = max(firstTravel, (down[0].position - first.position).getDistance())
                        continue
                    }
                    if (down.size > 2 || !YtNewsGestureRules.acceptsSecond(firstTravel, slop * 2)) {
                        finished = true
                        continue
                    }
                    ids = down[0].id to down[1].id
                    startGap = (down[0].position - down[1].position).getDistance()
                    startCentre = centre(down[0], down[1])
                    continue
                }
                val a = event.changes.firstOrNull { it.id == pair.first }
                val b = event.changes.firstOrNull { it.id == pair.second }
                if (a == null || b == null || !a.pressed || !b.pressed || down.size != 2) {
                    // a finger lifted or a third came: the pinch is over, unfinished
                    if (claimed) {
                        event.changes.forEach { it.consume() }
                        claimed = false
                        onCancel()
                    }
                    finished = true
                    continue
                }
                val gap = (a.position - b.position).getDistance()
                val now = centre(a, b)
                val drift = (now - startCentre).getDistance()
                val step = YtNewsGestureRules.step(startGap, gap, drift, claimed, spec, inward)
                if (!claimed && step != YtNewsGestureRules.Step.WAIT && step != YtNewsGestureRules.Step.REJECT && !allowed()) {
                    finished = true
                    continue
                }
                when (step) {
                    YtNewsGestureRules.Step.WAIT -> if (claimed) {
                        event.changes.forEach { it.consume() }
                        onProgress(YtNewsGestureRules.progress(startGap, gap, spec, inward), toWindow(now))
                    }
                    YtNewsGestureRules.Step.CLAIM -> {
                        claimed = true
                        event.changes.forEach { it.consume() }
                        onProgress(YtNewsGestureRules.progress(startGap, gap, spec, inward), toWindow(now))
                    }
                    YtNewsGestureRules.Step.COMMIT -> {
                        event.changes.forEach { it.consume() }
                        claimed = false
                        committed = true
                        finished = true
                        onCommit(toWindow(now))
                    }
                    YtNewsGestureRules.Step.REJECT -> finished = true
                }
            }
        } finally {
            if (claimed) onCancel()
        }
    }
}

private fun centre(a: PointerInputChange, b: PointerInputChange): Offset = (a.position + b.position) / 2f
