package com.github.libretube.ui.yt

import androidx.annotation.IdRes
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.platform.InspectorInfo
import com.github.libretube.R

/**
 * YT: a long press on a tab of the design navigation bars (for now only "Abos", which brings
 * back the closed mini player, see `mini/YtMiniRestore`).
 *
 * The bars of the designs handle their touches in very different ways (clickables per tab,
 * a draggable drop or blob over the active tab), so the long press is not added to each of
 * them: the whole bar watches the finger on the initial pass ([ytNavHoldArea]) and each design
 * only marks where its tab is ([ytNavHoldTarget]). The tabs keep their taps and drags; only a
 * long press that was taken swallows the rest of that touch, so the tab under it does not
 * select as well.
 */
class YtNavHold {
    internal var area: LayoutCoordinates? = null
    internal val targets = HashMap<Int, LayoutCoordinates>()

    /** The tab under [position] (in the area's coordinates) that takes a long press, if any. */
    internal fun idAt(position: Offset): Int? {
        val area = area?.takeIf { it.isAttached } ?: return null
        for ((id, target) in targets) {
            if (!target.isAttached) continue
            if (area.localBoundingBoxOf(target, clipBounds = false).contains(position)) return id
        }
        return null
    }

    companion object {
        /** The tabs that have a long press. */
        fun holds(@IdRes id: Int) = id == R.id.subscriptionsFragment
    }
}

val LocalYtNavHold = staticCompositionLocalOf<YtNavHold?> { null }

/**
 * The bar's side: after the system's long-press time without the finger moving (and still on a
 * marked tab), [onHold] is asked; true means it took the press, and the rest of the touch is
 * consumed so the tabs neither select nor drag. Any lift, move or second finger before that
 * leaves the touch to the tabs as it was.
 */
fun Modifier.ytNavHoldArea(hold: YtNavHold, onHold: (Int) -> Boolean): Modifier =
    this
        .onGloballyPositioned { hold.area = it }
        .pointerInput(hold) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                val id = hold.idAt(down.position) ?: return@awaitEachGesture
                val ended = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == down.id }
                        if (change == null || !change.pressed || event.changes.size > 1) return@withTimeoutOrNull true
                        if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) return@withTimeoutOrNull true
                    }
                    @Suppress("UNREACHABLE_CODE")
                    true
                }
                if (ended != null) return@awaitEachGesture
                // the finger may have stayed while the bar moved under it
                if (hold.idAt(down.position) != id || !onHold(id)) return@awaitEachGesture
                // the long press took the touch: swallow it up to the lift
                do {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    event.changes.forEach { it.consume() }
                } while (event.changes.any { it.pressed })
            }
        }

/** A design's tab: marks where it is, for the bar's long press. No-op for tabs without one. */
fun Modifier.ytNavHoldTarget(@IdRes id: Int): Modifier =
    if (YtNavHold.holds(id)) this.then(HoldTargetElement(id)) else this

private data class HoldTargetElement(val id: Int) : ModifierNodeElement<HoldTargetNode>() {
    override fun create() = HoldTargetNode(id)

    override fun update(node: HoldTargetNode) {
        node.id = id
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "ytNavHoldTarget"
        properties["id"] = id
    }
}

private class HoldTargetNode(var id: Int) :
    Modifier.Node(),
    CompositionLocalConsumerModifierNode,
    GlobalPositionAwareModifierNode {
    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        currentValueOf(LocalYtNavHold)?.targets?.set(id, coordinates)
    }
}
