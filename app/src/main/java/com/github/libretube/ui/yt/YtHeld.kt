package com.github.libretube.ui.yt

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.TraversableNode
import androidx.compose.ui.node.findNearestAncestor
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.Constraints
import com.github.libretube.ui.yt.feed.YtHeldSet

/*
 * YT: a home card held (pressed long). Its watched bar grows and shows the time left
 * (ytSeenTop), and the chips the pill covers fade (ytHeldFade). The card's gesture marks its
 * own instance in [YtCardDrag.held] with a [YtHeldToken]; the token reaches the drawing nodes
 * inside the card through [ytHeldScope] (a node on the card's root that they look up as an
 * ancestor), so nothing is read in composition and only the held instance redraws.
 */

/** One card instance that can be held; identity only. */
@Stable
class YtHeldToken

/** Marks [token] held (the gesture's long press fired). */
internal fun YtCardDrag.hold(token: YtHeldToken) {
    held.value = YtHeldSet.add(held.value, token)
}

/** Lets go of [token]; every end of the gesture calls it (release, cancel, the card leaving). */
internal fun YtCardDrag.letGo(token: YtHeldToken) {
    held.value = YtHeldSet.remove(held.value, token)
}

/** Puts [token] on this card for the watched mark and chips inside it. Put it on the card's root. */
fun Modifier.ytHeldScope(token: YtHeldToken): Modifier = this.then(HeldScopeElement(token))

private data class HeldScopeElement(val token: YtHeldToken) : ModifierNodeElement<YtHeldScopeNode>() {
    override fun create() = YtHeldScopeNode(token)

    override fun update(node: YtHeldScopeNode) {
        node.token = token
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "ytHeldScope"
    }
}

/** The card's end of the link; it lives as long as the card's layout node. */
internal class YtHeldScopeNode(token: YtHeldToken) : Modifier.Node(), TraversableNode {
    override val traverseKey: Any get() = KEY

    /** The card instance (a reused list item gets a new one through update). */
    var token by mutableStateOf(token)

    /** While a time-left pill is shown on this card: how far it is in, 0 to 1. Set by ytSeenTop. */
    var pill by mutableStateOf<(() -> Float)?>(null)

    val isHeld: Boolean get() = token in YtCardDrag.held.value

    companion object {
        const val KEY = "com.github.libretube.ui.yt.YtHeldScope"
    }
}

/** The nearest card around this node, or null (no hold gesture on it, or not attached). */
internal fun DelegatableNode.heldScope(): YtHeldScopeNode? =
    if (!node.isAttached) null else findNearestAncestor(YtHeldScopeNode.KEY) as? YtHeldScopeNode

/**
 * The held mark for cards whose long press does something else (or nothing): it only watches
 * the finger. A press that stays down and still for the long-press time marks the card held
 * until every finger is up. It never consumes and ignores what others consume, so the card's
 * click, long click (quick sheet), scroll and swipe work as before; a move past the touch slop
 * before the time is up (a scroll or swipe) does not count. Put it before any press scale of
 * the card (graphicsLayer), so the slop is measured without it.
 */
@Composable
fun Modifier.ytHeldMark(): Modifier {
    val token = remember { YtHeldToken() }
    return this
        .ytHeldScope(token)
        .pointerInput(token) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                // null: the time ran out with the finger still down and still
                val ended = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Final)
                        val change = event.changes.firstOrNull { it.id == down.id }
                        if (change == null || !change.pressed) break
                        if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) break
                    }
                    true
                }
                if (ended != null) return@awaitEachGesture
                YtCardDrag.hold(token)
                try {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Final)
                        if (event.changes.none { it.pressed }) break
                    }
                } finally {
                    YtCardDrag.letGo(token)
                }
            }
        }
}

/**
 * Fades this chip (duration, badge, play button) of a held card while its time-left pill shows:
 * the pill replaces what it said. Put it first on the chip, after its align.
 */
fun Modifier.ytHeldFade(): Modifier = this.then(HeldFadeElement)

private data object HeldFadeElement : ModifierNodeElement<HeldFadeNode>() {
    override fun create() = HeldFadeNode()

    override fun update(node: HeldFadeNode) = Unit

    override fun InspectorInfo.inspectableProperties() {
        name = "ytHeldFade"
    }
}

private class HeldFadeNode : Modifier.Node(), LayoutModifierNode {
    // read in the layer: the fade redraws without measuring or composing again
    private val layer: GraphicsLayerScope.() -> Unit = {
        val pill = heldScope()?.pill?.invoke() ?: 0f
        alpha = (1f - pill).coerceIn(0f, 1f)
    }

    override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
        val placeable = measurable.measure(constraints)
        return layout(placeable.width, placeable.height) { placeable.placeWithLayer(0, 0, layerBlock = layer) }
    }
}
