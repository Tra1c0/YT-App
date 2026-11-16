package com.github.libretube.ui.yt.builder

import kotlin.math.abs

/**
 * YT: the editor's reorder after a drop inside a flow container (pure, px of the parent). The
 * children take their new places in the layout at once; each one that moved is drawn shifted back
 * to where it was ([start]: old place minus new place, the dropped child also by the offset the
 * finger left it at), and the shift springs to zero. With motion off nothing is shifted: the new
 * order shows at once.
 */
object YtReorderAnim {
    /** Below this many px a child counts as not moved. */
    const val STILL_PX = 0.5f

    /**
     * The shift a child starts with: from its old layout place ([oldX], [oldY]; null when it had
     * none, e.g. it was just added) to its new one, plus [dropX]/[dropY] for the dropped child.
     * Null: it is drawn where it is.
     */
    fun start(oldX: Float?, oldY: Float?, newX: Float, newY: Float, dropped: Boolean, dropX: Float, dropY: Float, motion: Boolean): Pair<Float, Float>? {
        if (!motion || oldX == null || oldY == null) return null
        val x = oldX - newX + if (dropped) dropX else 0f
        val y = oldY - newY + if (dropped) dropY else 0f
        return if (abs(x) < STILL_PX && abs(y) < STILL_PX) null else x to y
    }

    /** The starts of every child of a parent, by id ([old] and [new]: layout places by id). */
    fun starts(
        old: Map<String, Pair<Float, Float>>,
        new: Map<String, Pair<Float, Float>>,
        dropped: String?,
        dropX: Float,
        dropY: Float,
        motion: Boolean,
    ): Map<String, Pair<Float, Float>> = buildMap {
        for ((id, p) in new) {
            val o = old[id]
            start(o?.first, o?.second, p.first, p.second, id == dropped, dropX, dropY, motion)?.let { put(id, it) }
        }
    }

    /** The shift at [fraction] of the way back (1 at the start, 0 at rest; a spring may pass 0). */
    fun at(start: Pair<Float, Float>, fraction: Float): Pair<Float, Float> = start.first * fraction to start.second * fraction
}
