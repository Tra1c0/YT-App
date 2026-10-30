package com.github.libretube.ui.yt

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.awaitVerticalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.gestures.verticalDrag
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sign

/*
 * YT: gestures the designs share, after Apple's "Designing Fluid Interfaces": whatever is
 * touched follows the finger 1:1, resists at a boundary instead of stopping (rubber band), and
 * when let go the spring takes over the finger's velocity, so there is no seam between dragging
 * and animating. Everything stays interruptible: a new touch simply retargets the spring.
 */

/** How far past a boundary something moves for [overshoot] px of pull: less and less. */
fun ytRubberBand(overshoot: Float, dimension: Float, constant: Float = 0.55f): Float =
    (overshoot * dimension * constant) / (dimension + constant * abs(overshoot))

/** Where a flick at [velocity] px/s would come to rest, as scrolling decelerates. */
fun ytProject(velocity: Float, decelerationRate: Float = 0.998f): Float =
    (velocity / 1000f) * decelerationRate / (1f - decelerationRate)

/** A horizontal or vertical swipe of one element, in px; read [offset] where the element is drawn. */
class YtSwipeState internal constructor(internal val scope: CoroutineScope) {
    val offset = Animatable(0f)

    /** True while the swipe is past its threshold, i.e. letting go would commit. */
    var armed by mutableStateOf(false)
        internal set

    internal var raw = 0f
}

@Composable
fun rememberYtSwipe(): YtSwipeState {
    val scope = rememberCoroutineScope()
    return remember { YtSwipeState(scope) }
}

/**
 * Lets the element be swiped along one axis: it follows 1:1 up to [thresholdPx], then with a
 * rubber band; a tick when the threshold is crossed; letting go past the threshold (or with a
 * clear flick that way) commits with [onCommit] and a confirm, and the element springs back
 * carrying the finger's velocity. [direction] +1 allows only right (or down), -1 only left (or
 * up), 0 both. Taps and the other axis stay with the content below.
 */
fun Modifier.ytSwipe(
    state: YtSwipeState,
    thresholdPx: Float,
    direction: Int,
    haptics: YtHaptics,
    enabled: Boolean = true,
    vertical: Boolean = false,
    /** False: after a commit the caller moves the element on (e.g. off the screen) itself. */
    settle: Boolean = true,
    onCommit: (direction: Int) -> Unit,
): Modifier = if (!enabled) this else pointerInput(state, thresholdPx, direction, vertical, settle) {
    val dimension = (if (vertical) size.height else size.width).toFloat().coerceAtLeast(1f)
    fun band(raw: Float): Float {
        val over = abs(raw) - thresholdPx
        return if (over <= 0f) raw else sign(raw) * (thresholdPx + ytRubberBand(over, dimension))
    }
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val tracker = VelocityTracker()
        val slop: (androidx.compose.ui.input.pointer.PointerInputChange, Float) -> Unit = { change, over ->
            if (direction == 0 || sign(over) == direction.toFloat() || abs(over) < 0.5f) {
                change.consume()
                state.raw = over
                state.scope.launch { state.offset.snapTo(band(over)) }
            }
        }
        val start = if (vertical) {
            awaitVerticalTouchSlopOrCancellation(down.id, slop)
        } else {
            awaitHorizontalTouchSlopOrCancellation(down.id, slop)
        } ?: return@awaitEachGesture
        tracker.addPointerInputChange(start)
        val onDrag: (androidx.compose.ui.input.pointer.PointerInputChange) -> Unit = { change ->
            tracker.addPointerInputChange(change)
            val delta = change.positionChange().let { if (vertical) it.y else it.x }
            change.consume()
            var raw = state.raw + delta
            if (direction != 0 && sign(raw) != direction.toFloat()) raw = 0f
            state.raw = raw
            val shown = band(raw)
            val armed = abs(shown) >= thresholdPx
            if (armed && !state.armed) haptics.tick()
            state.armed = armed
            state.scope.launch { state.offset.snapTo(shown) }
        }
        if (vertical) verticalDrag(start.id, onDrag) else horizontalDrag(start.id, onDrag)

        val velocity = tracker.calculateVelocity().let { if (vertical) it.y else it.x }
        val shown = state.offset.value
        val flick = abs(velocity) > 1400f && sign(velocity) == sign(shown) && abs(shown) > thresholdPx * 0.35f
        val commit = state.armed || flick
        val way = if (shown == 0f) 0 else sign(shown).toInt()
        state.raw = 0f
        state.armed = false
        if (commit && way != 0) {
            haptics.confirm()
            onCommit(way)
        }
        if (settle || !commit || way == 0) {
            state.scope.launch {
                state.offset.animateTo(0f, spring(dampingRatio = 0.72f, stiffness = 380f), initialVelocity = velocity)
            }
        }
    }
}

/** How far a list has been scrolled in all, in px, for things that turn or drift with scrolling. */
class YtScrollTotal {
    var value by mutableFloatStateOf(0f)
        private set

    val connection = object : NestedScrollConnection {
        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            value -= consumed.y
            return Offset.Zero
        }
    }
}

@Composable
fun rememberYtScrollTotal(): YtScrollTotal = remember { YtScrollTotal() }

/**
 * A press that inflates: [inflate] rises slowly towards 1 while the finger holds, and springs
 * back when it lifts. Reads only, the click stays with the content below.
 */
fun Modifier.ytHold(inflate: Animatable<Float, *>, scope: CoroutineScope, enabled: Boolean = true): Modifier =
    if (!enabled) this else pointerInput(inflate) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            scope.launch { inflate.animateTo(1f, spring(dampingRatio = 1f, stiffness = 28f)) }
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) break
            }
            scope.launch { inflate.animateTo(0f, spring(dampingRatio = 0.45f, stiffness = 420f)) }
        }
    }
