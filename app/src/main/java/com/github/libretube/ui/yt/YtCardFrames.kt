package com.github.libretube.ui.yt

import android.os.Build
import android.view.View
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.GraphicsContext
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.node.requireDensity
import androidx.compose.ui.node.requireGraphicsContext
import androidx.compose.ui.node.requireLayoutCoordinates
import androidx.compose.ui.node.requireView
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.github.libretube.ui.yt.news.YtNewsMotion
import com.github.libretube.ui.yt.news.YtNewsRect

/*
 * YT: the home cards as frames something can grow out of (the News window, stage 2). Every card
 * root with a ytHeldScope registers here while attached; nothing is measured or drawn for it until
 * a gesture asks: [YtCardFrames.at] looks up the card under a point once, and only the card that
 * is lent ([YtCardFrame.lend]) records its drawing into a layer and hides while the window shows
 * it. Main thread only.
 */

/** The registry of the attached card roots. */
object YtCardFrames {
    private val nodes = LinkedHashSet<YtHeldScopeNode>()

    /** The card lent out now, or null; every card's draw reads it (snapshot state). */
    internal var lent by mutableStateOf<YtCardFrame?>(null)
        private set

    internal fun attach(node: YtHeldScopeNode) {
        nodes += node
    }

    internal fun detach(node: YtHeldScopeNode) {
        nodes -= node
        lent?.takeIf { it.node === node }?.lost()
    }

    /**
     * The card under [at] (window px) among the cards of [view] inside [within] (the home's root):
     * the smallest one whose visible part holds the point; null over a gap or the header.
     */
    fun at(at: Offset, view: View, within: Rect?): YtCardFrame? {
        val candidates = nodes.filter { it.isAttached && runCatching { it.requireView() }.getOrNull() === view }
        val visible = candidates.map { node ->
            val clipped = node.visibleBounds() ?: return@map YtNewsRect(0f, 0f, 0f, 0f)
            if (within == null) clipped else clipped.intersect(within.toNews())
        }
        val i = YtNewsMotion.pick(visible, at.x, at.y)
        return if (i < 0) null else YtCardFrame(candidates[i])
    }

    internal fun lend(frame: YtCardFrame) {
        val before = lent
        if (before != null && before !== frame) before.giveBack()
        lent = frame
    }

    internal fun returned(frame: YtCardFrame) {
        if (lent === frame) lent = null
    }

    internal fun Rect.toNews() = YtNewsRect(left, top, right, bottom)
}

/**
 * One card under the fingers: its live place on the screen and, while lent, its picture.
 * [bounds] and [visible] are read when asked (in the window's draw), not kept.
 */
class YtCardFrame internal constructor(internal val node: YtHeldScopeNode) {
    /** The card's drawing while lent (Android 10 and later); null before or without. */
    var picture: GraphicsLayer? = null
        private set

    /** Who made [picture], to free it again (also after the card left). */
    private var graphics: GraphicsContext? = null

    /** Hidden on the home while the window shows its picture in its place. Read in the card's draw. */
    var hidden by mutableStateOf(false)

    /** The card left the home (scrolled away and reused, the home rebuilt). */
    var gone = false
        private set

    /** The visible card (its bounds less its inset), unclipped, in window px; null once gone. */
    fun bounds(): YtNewsRect? = if (gone || !node.isAttached) null else node.cardBounds()

    /** The card's full bounds (with its inset), where its picture is drawn; null once gone. */
    fun outer(): YtNewsRect? = if (gone || !node.isAttached) null else node.outerBounds()

    /** What of the card shows on the screen now (clipped by the list), null once gone. */
    fun visible(): YtNewsRect? = if (gone || !node.isAttached) null else node.visibleBounds()

    /** The card's corner radius in px, for the window's corners at the start. */
    fun cornerPx(): Float = if (gone || !node.isAttached) 0f else node.cornerPx()

    /** Starts recording the card's drawing (where layers can be shared); hides nothing yet. */
    fun lend() {
        if (gone) return
        if (picture == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && node.isAttached) {
            runCatching {
                val context = node.requireGraphicsContext()
                picture = context.createGraphicsLayer()
                graphics = context
            }
        }
        YtCardFrames.lend(this)
    }

    /** The window is gone: the card draws itself again and its picture is freed. */
    fun giveBack() {
        hidden = false
        YtCardFrames.returned(this)
        release()
    }

    internal fun lost() {
        gone = true
        hidden = false
        YtCardFrames.returned(this)
        release()
    }

    private fun release() {
        val layer = picture ?: return
        picture = null
        runCatching { graphics?.releaseGraphicsLayer(layer) }
        graphics = null
    }

    /** The lent card's draw: into its picture, and on the home only while not [hidden]. */
    internal fun draw(scope: ContentDrawScope) {
        val layer = picture
        if (layer == null || layer.isReleased) {
            if (!hidden) scope.drawContent()
            return
        }
        with(scope) {
            layer.record { this@with.drawContent() }
            if (!hidden) drawLayer(layer)
        }
    }

    /** Whether the picture holds a drawing yet (the card drew once since it was lent). */
    val recorded: Boolean get() = picture?.let { !it.isReleased && it.size != IntSize.Zero } == true
}

private fun YtHeldScopeNode.outerBounds(): YtNewsRect? {
    val coords = runCatching { requireLayoutCoordinates() }.getOrNull()?.takeIf { it.isAttached } ?: return null
    return coords.unclipped()
}

private fun LayoutCoordinates.unclipped(): YtNewsRect {
    val topLeft = localToWindow(Offset.Zero)
    val bottomRight = localToWindow(Offset(size.width.toFloat(), size.height.toFloat()))
    return YtNewsRect(topLeft.x, topLeft.y, bottomRight.x, bottomRight.y)
}

private fun YtHeldScopeNode.cardBounds(): YtNewsRect? {
    val outer = outerBounds() ?: return null
    val density = requireDensity()
    val ltr = LayoutDirection.Ltr
    // the window's px per layout px (the home may be scaled back behind the window)
    val coords = runCatching { requireLayoutCoordinates() }.getOrNull()
    val k = if (coords != null && coords.size.width > 0) outer.width / coords.size.width else 1f
    with(density) {
        return outer.inset(
            inset.calculateLeftPadding(ltr).toPx() * k,
            inset.calculateTopPadding().toPx() * k,
            inset.calculateRightPadding(ltr).toPx() * k,
            inset.calculateBottomPadding().toPx() * k,
        )
    }
}

private fun YtHeldScopeNode.visibleBounds(): YtNewsRect? {
    val coords = runCatching { requireLayoutCoordinates() }.getOrNull()?.takeIf { it.isAttached } ?: return null
    val card = cardBounds() ?: return null
    return with(YtCardFrames) { coords.boundsInWindow().toNews() }.intersect(card)
}

private fun YtHeldScopeNode.cornerPx(): Float {
    val density = requireDensity()
    // a card without a known shape: the common 16 dp
    val fallback = with(density) { 16.dp.toPx() }
    val s = shape ?: return fallback
    if (s === RectangleShape) return 0f
    val corner = s as? CornerBasedShape ?: return fallback
    val coords = runCatching { requireLayoutCoordinates() }.getOrNull() ?: return 0f
    val size = coords.size
    return corner.topStart.toPx(
        androidx.compose.ui.geometry.Size(size.width.toFloat(), size.height.toFloat()),
        density,
    )
}
