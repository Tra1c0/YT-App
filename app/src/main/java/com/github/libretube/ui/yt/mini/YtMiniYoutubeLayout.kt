package com.github.libretube.ui.yt.mini

import kotlin.math.max
import kotlin.math.min

/*
 * YT: the YouTube neu mini player ("Rote Naht") in numbers: its long-press preview and how its
 * parts move with the minimize progress, the preview and a drop. Pure (px, no Android), so the
 * View constraints, the composables and the JVM tests read the same numbers.
 */

object YtMiniYoutubeLayout {
    /** How much the preview enlarges the picture's height (16:9 kept). */
    const val GROWTH = 2.2f

    /** From the grown box's top to the picture, and the side inset of the preview's parts. */
    const val TOP = 10
    const val PAD = 8

    /** The picture takes at most this share of the card's inner width (room for the column). */
    const val PICTURE_SHARE = 0.6f

    /** The column beside the picture: its gap to the picture, its inset from the card's end. */
    const val COLUMN_GAP = 12
    const val COLUMN_END = 12

    /** The play pill at the column's foot. */
    const val PILL = 36
    const val PILL_WIDTH = 148
    const val PILL_GAP = 6

    /** The "Als Nächstes" row under the picture: its gap to the picture, its height, its thumbnail. */
    const val ROW_GAP = 10
    const val ROW = 56
    const val ROW_THUMB_HEIGHT = 40

    /** How far the row's foot (and the picture's foot without a row) stays above the seam. */
    const val SEAM_GAP = 9

    /** The seam: the red line between card and bar, and its stitches. */
    const val SEAM = 2.5f
    const val STITCH = 6f
    const val STITCH_GAP = 4f
    const val STITCH_WIDTH = 1.5f

    /** How high the glow of the played part reaches into the card, at rest and with a drop near. */
    const val GLOW = 8f
    const val GLOW_HOVER = 18f

    /** The timer line under the next row fills in this long while the preview is held. */
    const val TIMER_MS = 1500

    /**
     * The preview of [spec] in a box [boxWidth] px wide ([density] px per dp), in px of the grown
     * box, left to right, its foot on the bar: the picture grown [GROWTH] times in 16:9 at the
     * top left, the title and the play pill beside it, the next video's row across the card under
     * it ([hasNext]), above the seam.
     */
    fun peek(spec: YtMiniSpec, boxWidth: Float, density: Float, hasNext: Boolean): YtMiniPeekGeometry {
        val d = density
        val pad = PAD * d
        val inner = max(boxWidth - 2f * pad, 1f)
        // 16:9 at the grown height, narrower where the screen leaves too little for the column
        val wanted = spec.thumbHeight * GROWTH * d * 16f / 9f
        val width = min(wanted, inner * PICTURE_SHARE)
        val height = width * 9f / 16f
        val player = YtMiniBox(pad, TOP * d, pad + width, TOP * d + height)

        val columnLeft = player.right + COLUMN_GAP * d
        val columnRight = max(boxWidth - COLUMN_END * d, columnLeft)
        val pillWidth = min(PILL_WIDTH * d, columnRight - columnLeft)
        val pill = YtMiniBox(columnLeft, player.bottom - PILL * d, columnLeft + pillWidth, player.bottom)
        val title = YtMiniBox(columnLeft, player.top, columnRight, max(player.top, pill.top - PILL_GAP * d))

        val next = if (hasNext) {
            val top = player.bottom + ROW_GAP * d
            YtMiniBox(pad, top, boxWidth - pad, top + ROW * d)
        } else {
            null
        }
        val foot = next?.bottom ?: player.bottom
        val rest = (spec.height + spec.overlap) * d
        val box = max(rest, foot + SEAM_GAP * d + spec.overlap * d)
        return YtMiniPeekGeometry(box, player, title, pill, next)
    }
}

/** YT: how the parts of the YouTube neu player move (pure curves, 0 to 1). */
object YtMiniYoutubeMath {
    fun smoothstep(from: Float, to: Float, x: Float): Float {
        if (to <= from) return if (x >= to) 1f else 0f
        val t = ((x - from) / (to - from)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /** The card's top corners round as the player shrinks onto the bar ([radius] at rest). */
    fun corner(progress: Float, radius: Float): Float = radius * smoothstep(0.2f, 1f, progress)

    /** Title and channel at rest come in at the end of minimizing and give way to the preview. */
    fun restText(progress: Float, peek: Float): Float =
        smoothstep(0.75f, 1f, progress) * (1f - (peek / 0.35f).coerceIn(0f, 1f))

    /** The seam shows only on the bar: it fades as the player lifts off. */
    fun seamShown(progress: Float): Float = smoothstep(0.9f, 1f, progress)

    /** The column beside the grown picture (title, pill) comes once the picture has grown a bit. */
    fun columnShown(peek: Float): Float = smoothstep(0.3f, 0.8f, peek)

    /** The next row rises out of the seam after the column, and sinks back into it first. */
    fun rowShown(peek: Float): Float = smoothstep(0.45f, 0.95f, peek)

    /**
     * The seam being sewn in on landing, [t] 0 to 1 of its time: where the needle is (0 to 1 of
     * the seam's length), eased out, so it runs fast and slows towards the end.
     */
    fun needle(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        return 1f - (1f - x) * (1f - x) * (1f - x)
    }

    /** The needle's light: bright while it runs, gone once the seam is through. */
    fun needleLight(t: Float): Float = 1f - smoothstep(0.75f, 1f, t)

    /**
     * The "+1" pill of an accepted drop over its [ms] since the drop (motion on): it rises out of
     * the seam (0 to 220), holds, widens into the label (520 to 780), holds 1400 ms, sinks back
     * into the seam (2180 to 2480). Returns rise (0 seam, 1 up), widen (0 "+1", 1 label) and
     * whether it is still to be drawn.
     */
    fun dropPill(ms: Float): DropPill {
        val rise = when {
            ms < 0f -> 0f
            ms < RISE_END -> back((ms / RISE_END).coerceIn(0f, 1f))
            ms < SINK_START -> 1f
            else -> 1f - smoothstep(SINK_START, DROP_END, ms)
        }
        val widen = smoothstep(WIDEN_START, WIDEN_END, ms)
        return DropPill(rise, widen, ms in 0f..DROP_END)
    }

    data class DropPill(val rise: Float, val widen: Float, val shown: Boolean)

    const val RISE_END = 220f
    const val WIDEN_START = 520f
    const val WIDEN_END = 780f
    const val SINK_START = 2180f
    const val DROP_END = 2480f

    /** An ease out with a small overshoot (the pill pops), 0 at 0 and 1 at 1. */
    fun back(t: Float): Float {
        val c = 1.4f
        val x = t - 1f
        return 1f + (c + 1f) * x * x * x + c * x * x
    }

    /** The seam's thickness in dp: a dragged video near makes it fuller. */
    fun seamWidth(hover: Float): Float = YtMiniYoutubeLayout.SEAM + 1f * hover.coerceIn(0f, 1f)

    /** How high the glow reaches into the card in dp, by how near a dragged video is. */
    fun glowHeight(hover: Float): Float =
        YtMiniYoutubeLayout.GLOW + (YtMiniYoutubeLayout.GLOW_HOVER - YtMiniYoutubeLayout.GLOW) * hover.coerceIn(0f, 1f)
}
