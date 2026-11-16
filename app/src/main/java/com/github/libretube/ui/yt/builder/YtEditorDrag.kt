package com.github.libretube.ui.yt.builder

/**
 * YT: what a drag or a handle on the editor's canvas does to the document (pure). The canvas
 * moves the element in px of the preview while the finger is down; on release these turn the
 * movement into the node's own values in dp: x/y by its anchors in a free container, a new place
 * among its siblings in a flow, a new size from a handle (with the aspect kept if locked).
 */
object YtEditorDrag {
    /** [node]'s x/y after it moved [dxPx], [dyPx] in a free parent; END anchors count the other way. */
    fun moved(node: YtNode, dxPx: Float, dyPx: Float, density: Float, pixelSnap: Boolean = true): YtNode {
        val dx = dxPx / density
        val dy = dyPx / density
        fun round(v: Float) = if (pixelSnap) (Math.round(v * 2f) / 2f) else v
        val x = when (node.anchorH) {
            YtAnchor.END -> node.x - dx
            else -> node.x + dx
        }
        val y = when (node.anchorV) {
            YtAnchor.END -> node.y - dy
            else -> node.y + dy
        }
        return node.copy(x = round(x), y = round(y))
    }

    /**
     * Where a child dragged to [centerMain] (its centre along the flow, px) lands among its
     * siblings, whose centres along the flow are [siblingCenters] in their order (the dragged one
     * included at [from]). Returns the index it takes after being removed.
     */
    fun insertIndex(siblingCenters: List<Float>, from: Int, centerMain: Float): Int {
        var at = 0
        for ((i, c) in siblingCenters.withIndex()) {
            if (i == from) continue
            if (centerMain > c) at++
        }
        return at
    }

    enum class Handle { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT, RIGHT, BOTTOM }

    /**
     * The node after a resize handle moved [dxPx], [dyPx] from a box [startW] x [startH] px.
     * Width and height become FIXED; [lock] keeps the start's aspect (the larger change leads);
     * a handle on the left or top also moves a free child so its other edge stays put.
     */
    fun resized(node: YtNode, handle: Handle, dxPx: Float, dyPx: Float, startW: Float, startH: Float, density: Float, lock: Boolean, freeParent: Boolean): YtNode {
        val left = handle == Handle.TOP_LEFT || handle == Handle.BOTTOM_LEFT
        val top = handle == Handle.TOP_LEFT || handle == Handle.TOP_RIGHT
        val changesW = handle != Handle.BOTTOM
        val changesH = handle != Handle.RIGHT
        var w = if (changesW) startW + if (left) -dxPx else dxPx else startW
        var h = if (changesH) startH + if (top) -dyPx else dyPx else startH
        val min = 4f * density
        w = w.coerceAtLeast(min)
        h = h.coerceAtLeast(min)
        if (lock && startW > 0f && startH > 0f) {
            val ratio = startW / startH
            if (!changesH || (changesW && kotlin.math.abs(w - startW) / startW >= kotlin.math.abs(h - startH) / startH)) h = w / ratio else w = h * ratio
        }
        var n = node.copy(
            width = if (changesW || lock) YtSize.fixed(round1(w / density)) else node.width,
            height = if (changesH || lock) YtSize.fixed(round1(h / density)) else node.height,
            aspect = if (lock) (w / h) else node.aspect?.let { null },
        )
        if (freeParent) {
            val dw = (w - startW) / density
            val dh = (h - startH) / density
            n = n.copy(
                x = if (left && node.anchorH == YtAnchor.START) node.x - dw else if (!left && node.anchorH == YtAnchor.END) node.x - dw else n.x,
                y = if (top && node.anchorV == YtAnchor.START) node.y - dh else if (!top && node.anchorV == YtAnchor.END) node.y - dh else n.y,
            )
        }
        return n
    }

    /** A rotation in degrees from the centre to the finger, snapped to 15° steps within 4°. */
    fun rotation(cx: Float, cy: Float, px: Float, py: Float, snap: Boolean): Float {
        val deg = Math.toDegrees(kotlin.math.atan2((py - cy).toDouble(), (px - cx).toDouble())).toFloat() + 90f
        val norm = ((deg % 360f) + 360f) % 360f
        if (!snap) return round1(norm)
        val step = 15f
        val nearest = Math.round(norm / step) * step
        return if (kotlin.math.abs(norm - nearest) <= 4f) nearest % 360f else round1(norm)
    }

    private fun round1(v: Float) = Math.round(v * 10f) / 10f

    /**
     * Whether a drag in the whole-screen preview scrolls its feed instead of editing: it starts
     * in the feed, not on a selected element (that one is moved; select first to move one), and
     * runs more up or down than sideways.
     */
    fun scrollsFeed(inFeed: Boolean, onSelected: Boolean, dx: Float, dy: Float): Boolean =
        inFeed && !onSelected && kotlin.math.abs(dy) >= kotlin.math.abs(dx)
}
