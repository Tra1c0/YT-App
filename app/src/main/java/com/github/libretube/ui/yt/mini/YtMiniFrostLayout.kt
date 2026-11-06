package com.github.libretube.ui.yt.mini

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/*
 * YT: the Frost mini player ("Eisfenster") in numbers. A pane of dark frosted glass as wide as the
 * narrow Frost bar grows out of it; the video is a 16:9 window in the pane whose edges are frosted
 * over. Pure (px, no Android), so the constraints, both composables and the JVM tests read the
 * same numbers.
 */
object YtFrostLayout {
    /** The pane above the bar, how far it reaches into the bar, its top corners (the bar's radius). */
    const val HEIGHT = 62
    const val OVERLAP = 1
    const val RADIUS = 30

    /** The window of the video: 16:9, inset from the pane's start, its corners. */
    const val THUMB_WIDTH = 80
    const val THUMB_HEIGHT = 45
    const val THUMB_START = 9
    const val THUMB_RADIUS = 14

    /** How far the frost veil creeps inward from the window's edges once docked. */
    const val VEIL = 10

    /** The preview enlarges the window 1.8 times. */
    const val GROWTH = 1.8f

    /** The preview: its padding, the title row, the gap below it, the gap between the columns. */
    const val PAD = 10
    const val ROW = 34
    const val ROW_GAP = 8
    const val COLUMN_GAP = 10

    /** The play pill of the preview. */
    const val PILL = 30
    const val PILL_WIDTH = 112
    const val PILL_GAP = 6

    /** What the column beside the window needs without a next video: two title lines and the pill. */
    const val COLUMN_MIN = 72

    /** The window takes at most this share of the pane's inner width in the preview. */
    const val PICTURE_SHARE = 0.56f

    /** The Frost bar is as wide as its tabs: one wide active pill (118), the others round (48 + 6). */
    fun barWidth(tabs: Int): Int = 12 + 118 + 54 * (tabs - 1).coerceAtLeast(0)

    /** The measures of the docked pane for a bar of [tabs] tabs. */
    fun spec(tabs: Int) = YtMiniSpec(
        joint = YtMiniJoint.STACK,
        start = 0,
        width = barWidth(tabs),
        overlap = OVERLAP,
        height = HEIGHT,
        radius = RADIUS,
        thumbWidth = THUMB_WIDTH,
        thumbHeight = THUMB_HEIGHT,
        thumbStart = THUMB_START,
        thumbRadius = THUMB_RADIUS,
        peekGrowth = GROWTH,
    )

    /**
     * The preview in px of the grown box (its foot stays on the bar). The window grows from the
     * pane's bottom start corner, so the finger that holds it stays on it. With a next video
     * ([hasNext]) the title and the pill run in a row above, the frozen card of the next video
     * stands beside the window; without one, title and pill take the column beside it.
     */
    fun peek(boxWidth: Float, density: Float, hasNext: Boolean): YtMiniPeekGeometry {
        val d = density
        val pad = PAD * d
        val inner = max(boxWidth - 2f * pad, 1f)
        val width = min(THUMB_WIDTH * GROWTH * d, (inner - COLUMN_GAP * d) * PICTURE_SHARE).coerceAtLeast(1f)
        val height = width * THUMB_HEIGHT / THUMB_WIDTH
        val foot = OVERLAP * d + pad
        val columnLeft = pad + width + COLUMN_GAP * d
        val columnRight = max(boxWidth - pad, columnLeft)

        if (hasNext) {
            val box = pad + ROW * d + ROW_GAP * d + height + foot
            val player = YtMiniBox(pad, box - foot - height, pad + width, box - foot)
            val pillWidth = min(PILL_WIDTH * d, inner * 0.42f)
            val pillTop = pad + (ROW - PILL) * d / 2f
            val pill = YtMiniBox(boxWidth - pad - pillWidth, pillTop, boxWidth - pad, pillTop + PILL * d)
            val title = YtMiniBox(pad, pad, max(pad, pill.left - ROW_GAP * d), pad + ROW * d)
            val card = YtMiniBox(columnLeft, player.top, columnRight, player.bottom)
            return YtMiniPeekGeometry(box, player, title, pill, card)
        }

        val column = max(height, COLUMN_MIN * d)
        val box = pad + column + foot
        val player = YtMiniBox(pad, box - foot - height, pad + width, box - foot)
        val pillWidth = min(PILL_WIDTH * d, columnRight - columnLeft)
        val pill = YtMiniBox(columnLeft, player.bottom - PILL * d, columnLeft + pillWidth, player.bottom)
        val title = YtMiniBox(columnLeft, box - foot - column, columnRight, max(box - foot - column, pill.top - PILL_GAP * d))
        return YtMiniPeekGeometry(box, player, title, pill, null)
    }
}

/** YT: how the frost of the Frost player moves (pure). */
object YtFrostMath {
    fun smoothstep(from: Float, to: Float, x: Float): Float {
        if (to <= from) return if (x >= to) 1f else 0f
        val t = ((x - from) / (to - from)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    /** The window freezes from its edges inward late in minimizing, done as it docks. */
    fun freeze(progress: Float): Float = smoothstep(0.55f, 1f, progress)

    /**
     * The veil's width as a share of [YtFrostLayout.VEIL]: frozen with the minimize [progress], a
     * little past it and back while it settles after landing ([settle] 0 to 1, 1 at rest), receding
     * to a rim while the preview [peek] thaws the window.
     */
    fun veil(progress: Float, settle: Float, peek: Float): Float {
        val s = settle.coerceIn(0f, 1f)
        val overshoot = 1f + 0.4f * sin(PI.toFloat() * s) * (1f - s)
        return freeze(progress) * overshoot * lerp(1f, 0.3f, thaw(peek))
    }

    /** In the preview the window frosts over at once as the press takes ... */
    fun cover(peek: Float): Float = smoothstep(0f, 0.12f, peek)

    /** ... and thaws from the finger outward as it grows (0: all frost, 1: clear). */
    fun thaw(peek: Float): Float = smoothstep(0.08f, 0.8f, peek.coerceAtMost(1f))

    /** The radius that clears a [width] x [height] window from [x], [y] to its farthest corner. */
    fun reach(width: Float, height: Float, x: Float, y: Float): Float {
        val dx = max(x, width - x)
        val dy = max(y, height - y)
        return hypot(dx, dy)
    }

    /**
     * The crystals along the inner rim of a [width] x [height] window, [density] px per dp: per
     * crystal its base (x, y), its direction (unit dx, dy, pointing inward), its length and the
     * share of freezing at which it starts to grow; [STRIDE] floats each. The same window gives the
     * same crystals (a fixed seed), so they do not jump between frames.
     */
    fun crystals(width: Float, height: Float, density: Float, corner: Float): FloatArray {
        if (width <= 0f || height <= 0f || density <= 0f) return FloatArray(0)
        val random = Random(SEED)
        val spacing = 9f * density
        val out = ArrayList<Float>()
        // (start x, start y, along x, along y, edge length, inward x, inward y)
        val edges = listOf(
            floatArrayOf(corner, 0f, 1f, 0f, width - 2f * corner, 0f, 1f),
            floatArrayOf(width, corner, 0f, 1f, height - 2f * corner, -1f, 0f),
            floatArrayOf(width - corner, height, -1f, 0f, width - 2f * corner, 0f, -1f),
            floatArrayOf(0f, height - corner, 0f, -1f, height - 2f * corner, 1f, 0f),
        )
        for (e in edges) {
            val length = e[4]
            if (length <= 0f) continue
            val count = (length / spacing).toInt().coerceAtLeast(1)
            for (i in 0 until count) {
                val along = (i + 0.2f + 0.6f * random.nextFloat()) * length / count
                val x = e[0] + e[2] * along
                val y = e[1] + e[3] * along
                // inward, turned up to 35 degrees either way
                val turn = (random.nextFloat() - 0.5f) * 2f * (35f * PI.toFloat() / 180f)
                val dx = e[5] * cos(turn) - e[6] * sin(turn)
                val dy = e[5] * sin(turn) + e[6] * cos(turn)
                val reach = (3f + 5f * random.nextFloat()) * density
                val start = 0.15f + 0.6f * random.nextFloat()
                out += x; out += y; out += dx; out += dy; out += reach; out += start
            }
        }
        return out.toFloatArray()
    }

    /** How far a crystal that starts at [start] has grown at [freeze] (0 to 1). */
    fun grown(start: Float, freeze: Float): Float = smoothstep(start, start + 0.25f, freeze)

    const val STRIDE = 6
    private const val SEED = 0x1CE
}
