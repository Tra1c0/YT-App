package com.github.libretube.ui.yt.mini

import kotlin.math.max
import kotlin.math.min

/*
 * YT: the Movo mini player ("Kachel") in numbers. Movo's home is a mosaic of coloured tiles
 * (cobalt, pink, lime), so the docked player is one too: the picture as a cobalt-framed tile on a
 * pink tile turned a little behind it, a lime disc behind play. The long press splits it into a
 * mosaic: the pink tile flips up above as "Als Nächstes", a cobalt play tile slides out from
 * behind the picture, the lime disc becomes a small tile with the queue count. Pure (px, no
 * Android), so the View constraints, both composables and the JVM tests read the same numbers.
 */

/**
 * Movo's preview in px of the grown box (its foot stays on the bar), left to right: [frame] is
 * what the host reads (picture, the cobalt play tile as the pill, the pink next tile), [count]
 * the small lime tile under the play tile.
 */
data class YtMovoPeek(
    val frame: YtMiniPeekGeometry,
    val count: YtMiniBox,
)

/** How far a tile is turned towards the finger, in degrees (graphicsLayer rotationX, rotationY). */
data class YtMovoTilt(val x: Float, val y: Float) {
    companion object {
        val FLAT = YtMovoTilt(0f, 0f)
    }
}

object YtMovoLayout {
    /** The preview enlarges the picture 1.9 times ("leicht vergrößert"). */
    const val GROWTH = 1.9f

    /** The preview's inset, the gap between its tiles, the next tile's and the count tile's height. */
    const val PAD = 8
    const val GAP = 8
    const val NEXT_ROW = 64
    const val COUNT = 30

    /** The picture of the preview takes at most this share of the inner width. */
    const val PICTURE_SHARE = 0.58f

    /** The cobalt frame of the picture and the corners of the mosaic's tiles. */
    const val BORDER = 3
    const val TILE_CORNER = 16
    const val SMALL_CORNER = 10

    /** The lime disc behind play. */
    const val DISC = 40

    /** The pink tile behind the picture: how far it stands out and how far it is turned. */
    const val BACKER_DX = 4
    const val BACKER_DY = -1
    const val BACKER_TURN = -4f

    /** The preview's tiles flip up from this angle (rotationX) and swing past flat. */
    const val PEEK_FLIP = -8f

    /** The most a tile tilts under the finger, and how far beside it the finger still counts. */
    const val MAX_TILT = 5f
    const val TILT_REACH = 12

    /**
     * The preview of a box [boxWidth] px wide: the next video's pink tile across the top
     * ([hasNext]), below it the picture grown 1.9 times at the box's start (where the finger
     * holds it), beside the picture the cobalt play tile with the lime count tile under it.
     */
    fun peek(spec: YtMiniSpec, boxWidth: Float, density: Float, hasNext: Boolean): YtMovoPeek {
        val d = density
        val pad = PAD * d
        val gap = GAP * d
        val left = spec.body * d + pad
        val right = boxWidth - spec.body * d - pad
        val inner = max(right - left, 1f)
        // the picture keeps its cut and leaves the play tile at least a third of the row
        val width = min(spec.thumbWidth * GROWTH * d, inner * PICTURE_SHARE).coerceAtLeast(spec.thumbWidth * d)
        val height = width * spec.thumbHeight / spec.thumbWidth.coerceAtLeast(1)

        val next = if (hasNext) YtMiniBox(left, pad, right, pad + NEXT_ROW * d) else null
        val top = next?.let { it.bottom + gap } ?: pad
        val player = YtMiniBox(left, top, left + width, top + height)
        val columnLeft = player.right + gap
        val columnRight = max(right, columnLeft)
        val count = YtMiniBox(columnLeft, player.bottom - COUNT * d, columnRight, player.bottom)
        val pill = YtMiniBox(columnLeft, player.top, columnRight, count.top - gap)
        val boxHeight = max((spec.height + spec.overlap) * d, player.bottom + pad + spec.overlap * d)
        return YtMovoPeek(YtMiniPeekGeometry(boxHeight, player, pill, pill, next), count)
    }

    /** The pink tile behind the docked picture [player] (before it is turned). */
    fun backer(player: YtMiniBox, density: Float): YtMiniBox = player.offset(BACKER_DX * density, BACKER_DY * density)

    /** The lime disc around the play button [play]. */
    fun disc(play: YtMiniBox, density: Float): YtMiniBox {
        val r = DISC * density / 2f
        return YtMiniBox(play.centerX - r, play.centerY - r, play.centerX + r, play.centerY + r)
    }

    /** [box] of a box [height] px tall seen in one [current] px tall: its foot stays on the bar. */
    fun fromFoot(box: YtMiniBox, height: Float, current: Float): YtMiniBox = box.offset(0f, current - height)
}

/** YT: how the Movo tiles move (pure). */
object YtMovoMath {
    fun smoothstep(from: Float, to: Float, x: Float): Float {
        if (to <= from) return if (x >= to) 1f else 0f
        val t = ((x - from) / (to - from)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    /** Between [a] and [b]; past 1 it runs on beyond [b] (the springs swing past their rest). */
    fun lerp(a: YtMiniBox, b: YtMiniBox, t: Float): YtMiniBox =
        YtMiniBox(lerp(a.left, b.left, t), lerp(a.top, b.top, t), lerp(a.right, b.right, t), lerp(a.bottom, b.bottom, t))

    /**
     * A tile's own share of the preview at [peek] (0 to about 1.15): it starts at [delay] and
     * arrives with the rest at 1, so the tiles come one after another; what the spring swings
     * past 1 goes on (a little more), so the tiles overshoot with it.
     */
    fun phase(peek: Float, delay: Float): Float {
        if (peek <= delay) return 0f
        if (delay >= 1f) return if (peek >= 1f) 1f else 0f
        return ((peek - delay) / (1f - delay)).coerceAtMost(1.25f)
    }

    /**
     * The tilt of a [tile] under a finger at [x], [y] (same px): the pressed side gives way, as
     * Movo's cards do (ytTilt): the right edge turns away (rotationY > 0), the top tips back
     * (rotationX < 0). None when the finger is more than [reach] px beside the tile.
     */
    fun tilt(x: Float, y: Float, tile: YtMiniBox, reach: Float, most: Float): YtMovoTilt {
        if (tile.width <= 0f || tile.height <= 0f || !tile.inflate(reach).contains(x, y)) return YtMovoTilt.FLAT
        val nx = ((x - tile.left) / tile.width - 0.5f).coerceIn(-0.5f, 0.5f) * 2f
        val ny = ((y - tile.top) / tile.height - 0.5f).coerceIn(-0.5f, 0.5f) * 2f
        // 0f - … keeps a resting finger in the middle from giving -0.0
        return YtMovoTilt(0f - ny * most, nx * most)
    }

    /**
     * The once-over shuffle of the tiles after a drop, [t] 0 to 1: the pink tile swings to the
     * other side and back (degrees added to its turn), settling at 0.
     */
    fun shuffle(t: Float): Float {
        if (t <= 0f || t >= 1f) return 0f
        val swing = kotlin.math.sin(t * 2f * kotlin.math.PI.toFloat())
        return 9f * swing * (1f - t)
    }
}
