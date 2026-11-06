package com.github.libretube.ui.yt.player

import kotlin.math.max
import kotlin.math.min

/** YT: a rectangle in px (left, top, right, bottom), for the layout of hold and drag. */
data class YtHoldBox(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f

    fun offset(dx: Float, dy: Float) = YtHoldBox(left + dx, top + dy, right + dx, bottom + dy)

    companion object {
        fun centred(cx: Float, cy: Float, w: Float, h: Float) = YtHoldBox(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)

        /** From [a] to [b] by [t] (0 is [a]; a spring may go past 1). */
        fun lerp(a: YtHoldBox, b: YtHoldBox, t: Float) = YtHoldBox(
            a.left + (b.left - a.left) * t,
            a.top + (b.top - a.top) * t,
            a.right + (b.right - a.right) * t,
            a.bottom + (b.bottom - a.bottom) * t,
        )
    }
}

/**
 * YT: where things go while a finger holds the playing video (package C, (d)): the video itself
 * shrinks towards the finger, and the four tiles of the bound actions sit around it. The video
 * keeps its aspect; it moves as close to the finger as the tiles leave room for, and stays on the
 * screen. Everything in px. Pure, so it is tested on the JVM.
 */
object YtHoldLayout {
    /** The shrunk video against the visible player (its width or height, whichever binds). */
    const val SCALE = 0.45f

    /** Never smaller than this, even if the tiles then do not all fit. */
    const val MIN_SCALE = 0.3f

    /**
     * Where the shrunk video goes in a view [viewW] × [viewH], from its [frame] (the video's frame
     * as laid out; with zoom it can be bigger than the view), for a finger at [fingerX], [fingerY].
     * [room] is what the tiles need beside it (left, top, right, bottom: tile plus gap, 0 where a
     * direction has no tile); [pad] is kept free at the view's edges.
     */
    fun video(
        viewW: Float,
        viewH: Float,
        frame: YtHoldBox,
        fingerX: Float,
        fingerY: Float,
        room: YtHoldBox,
        pad: Float,
    ): YtHoldBox {
        val fw = frame.width
        val fh = frame.height
        if (fw <= 0f || fh <= 0f || viewW <= 0f || viewH <= 0f) return frame
        // what is visible of the frame: with zoom it overflows the view
        val base = min(1f, min(viewW / fw, viewH / fh))
        val fits = min((viewW - room.left - room.right - 2 * pad) / fw, (viewH - room.top - room.bottom - 2 * pad) / fh)
        val scale = min(SCALE * base, fits).coerceAtLeast(MIN_SCALE * base)
        val w = fw * scale
        val h = fh * scale
        val cx = clampOrMiddle(fingerX, pad + room.left + w / 2f, viewW - pad - room.right - w / 2f)
        val cy = clampOrMiddle(fingerY, pad + room.top + h / 2f, viewH - pad - room.bottom - h / 2f)
        return YtHoldBox.centred(cx, cy, w, h)
    }

    /**
     * The tiles around [video] in a view [viewW] × [viewH]: up and down centred above and below
     * it, left and right beside it, [gap] away, each of its [sizes] (width to height), kept inside
     * the view by [pad]. A direction without a size has no tile.
     */
    fun tiles(
        viewW: Float,
        viewH: Float,
        video: YtHoldBox,
        sizes: Map<YtHoldDir, Pair<Float, Float>>,
        gap: Float,
        pad: Float,
    ): Map<YtHoldDir, YtHoldBox> = sizes.mapValues { (dir, size) ->
        val (w, h) = size
        when (dir) {
            YtHoldDir.UP -> {
                val cx = clampOrMiddle(video.centerX, pad + w / 2f, viewW - pad - w / 2f)
                val top = max(pad, video.top - gap - h)
                YtHoldBox(cx - w / 2f, top, cx + w / 2f, top + h)
            }

            YtHoldDir.DOWN -> {
                val cx = clampOrMiddle(video.centerX, pad + w / 2f, viewW - pad - w / 2f)
                val bottom = min(viewH - pad, video.bottom + gap + h)
                YtHoldBox(cx - w / 2f, bottom - h, cx + w / 2f, bottom)
            }

            YtHoldDir.LEFT -> {
                val cy = clampOrMiddle(video.centerY, pad + h / 2f, viewH - pad - h / 2f)
                val left = max(pad, video.left - gap - w)
                YtHoldBox(left, cy - h / 2f, left + w, cy + h / 2f)
            }

            YtHoldDir.RIGHT -> {
                val cy = clampOrMiddle(video.centerY, pad + h / 2f, viewH - pad - h / 2f)
                val right = min(viewW - pad, video.right + gap + w)
                YtHoldBox(right - w, cy - h / 2f, right, cy + h / 2f)
            }
        }
    }

    /**
     * [text] in at most [maxLines] lines of at most [maxWidth] (as [measure] says), broken at
     * spaces; what does not fit ends the last line with an ellipsis. A word longer than a line
     * is cut too.
     */
    fun wrap(text: String, maxWidth: Float, maxLines: Int, measure: (String) -> Float): List<String> {
        val words = text.trim().split(' ').filter { it.isNotEmpty() }
        if (words.isEmpty() || maxLines <= 0) return emptyList()
        val lines = ArrayList<String>()
        var current = ""
        var i = 0
        while (i < words.size) {
            val candidate = if (current.isEmpty()) words[i] else "$current ${words[i]}"
            if (measure(candidate) <= maxWidth || current.isEmpty()) {
                current = candidate
                i++
                if (measure(current) > maxWidth) break
            } else {
                if (lines.size == maxLines - 1) break
                lines.add(current)
                current = ""
            }
        }
        val rest = i < words.size || measure(current) > maxWidth
        lines.add(if (rest) ellipsize(if (i < words.size) "$current ${words[i]}" else current, maxWidth, measure) else current)
        return lines
    }

    // cut from the end until it fits with "…"
    private fun ellipsize(text: String, maxWidth: Float, measure: (String) -> Float): String {
        var end = text.length
        while (end > 0) {
            val candidate = text.substring(0, end).trimEnd() + ELLIPSIS
            if (measure(candidate) <= maxWidth) return candidate
            end--
        }
        return ELLIPSIS
    }

    // [value] between [lo] and [hi]; where they cross (too little room), the middle of the two
    private fun clampOrMiddle(value: Float, lo: Float, hi: Float): Float =
        if (lo > hi) (lo + hi) / 2f else value.coerceIn(lo, hi)

    private const val ELLIPSIS = "…"
}
