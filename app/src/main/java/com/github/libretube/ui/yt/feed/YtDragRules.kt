package com.github.libretube.ui.yt.feed

import kotlin.math.sqrt

/** A rect in window px, kept apart from Compose and android.graphics for the JVM tests. */
data class YtBox(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f

    fun inflate(by: Float) = YtBox(left - by, top - by, right + by, bottom + by)

    fun contains(x: Float, y: Float) = x in left..right && y in top..bottom
}

/** Where the ghost goes for a finger: [x], [y] in window px, [pull] how far the magnet has it (0 to 1). */
data class YtGhostAt(val x: Float, val y: Float, val pull: Float, val hover: Boolean)

/**
 * YT: the rules of the drags on the home screens (a card onto the mini player, the search icon
 * onto a card), apart from Compose for the JVM tests. All distances in window px.
 */
object YtDragRules {
    /** How far the ghost is pulled to the centre of the target at most, while over it. */
    const val MAX_PULL = 0.45f

    /** The distance from ([x], [y]) to [box], 0 inside it. */
    fun distance(x: Float, y: Float, box: YtBox): Float {
        val dx = maxOf(box.left - x, 0f, x - box.right)
        val dy = maxOf(box.top - y, 0f, y - box.bottom)
        return sqrt(dx * dx + dy * dy)
    }

    /**
     * The ghost for a finger at ([x], [y]) and the mini player at [mini] (null: none docked):
     * over the mini player inflated by [hitPx] it hovers and is pulled [MAX_PULL] of the way to
     * the centre; within [magnetPx] of that it is pulled less and less with the distance, so it
     * slides into the target instead of jumping onto it.
     */
    fun ghost(x: Float, y: Float, mini: YtBox?, hitPx: Float, magnetPx: Float): YtGhostAt {
        if (mini == null) return YtGhostAt(x, y, 0f, false)
        val hit = mini.inflate(hitPx)
        val d = distance(x, y, hit)
        val hover = d <= 0f
        val pull = when {
            hover -> MAX_PULL
            magnetPx <= 0f || d >= magnetPx -> 0f
            else -> {
                // eases in: barely at the edge of the field, then more
                val t = 1f - d / magnetPx
                MAX_PULL * t * t
            }
        }
        return YtGhostAt(x + (mini.centerX - x) * pull, y + (mini.centerY - y) * pull, pull, hover)
    }

    /**
     * The card under the finger among [cards] (key to bounds), or null. Cards that overlap (a
     * card scrolling under a pinned header) give the one whose centre is nearest.
     */
    fun cardAt(x: Float, y: Float, cards: Map<String, YtBox>): String? {
        var best: String? = null
        var bestD = Float.MAX_VALUE
        for ((key, box) in cards) {
            if (!box.contains(x, y)) continue
            val dx = box.centerX - x
            val dy = box.centerY - y
            val d = dx * dx + dy * dy
            if (d < bestD) {
                bestD = d
                best = key
            }
        }
        return best
    }

    /**
     * The name a search for a card's channel uses: auto-generated music channels end in
     * " - Topic", which no one types. Null if there is no name to search for.
     */
    fun searchName(uploaderName: String?): String? =
        uploaderName?.trim()?.removeSuffix("- Topic")?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * Glas: where the loupe sits for a finger at ([x], [y]) in a picture [width] x [height]
     * (local px): it follows the finger but stays inside the picture.
     */
    fun loupe(x: Float, y: Float, width: Float, height: Float): Pair<Float, Float> =
        x.coerceIn(0f, width.coerceAtLeast(0f)) to y.coerceIn(0f, height.coerceAtLeast(0f))

    /** Glas: true once the finger is [margin] px past an edge of the picture: the loupe becomes the ghost. */
    fun leftPicture(x: Float, y: Float, width: Float, height: Float, margin: Float): Boolean =
        x < -margin || y < -margin || x > width + margin || y > height + margin

    /** A visible drop target can overlap a picture that continues behind the mini player. */
    fun liftLoupe(x: Float, y: Float, width: Float, height: Float, margin: Float, overTarget: Boolean): Boolean =
        overTarget || leftPicture(x, y, width, height, margin)
}
