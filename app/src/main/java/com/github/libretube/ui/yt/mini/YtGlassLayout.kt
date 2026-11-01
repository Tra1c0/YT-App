package com.github.libretube.ui.yt.mini

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/*
 * YT: the Glas mini player (the user's Mint reference) in numbers. One glass slab: the floating
 * Glas bar grown upward, a wide strip of the video in it whose bottom tucks under the tab row's
 * rim, a grab handle above it, round glass buttons for the previous and next video on it and
 * "Titel · Kanal" over its foot. Pure (px, no Android), so the View constraints, both
 * composables and the JVM tests read the same numbers.
 */

/** Where the parts of the docked Glas player sit, in px of the box (main_container). */
data class YtGlassRest(
    val boxWidth: Float,
    val boxHeight: Float,
    /** The video strip (R.id.player). */
    val picture: YtBox,
    /** Where the tab row's rim lies over the strip (box y). */
    val rim: Float,
    val handle: YtBox,
    val prev: YtBox,
    val next: YtBox,
    /** The room of "Titel · Kanal": between the buttons, its foot above the rim. */
    val title: YtBox,
)

/**
 * The parts drawn over the picture, in px of the picture itself (the overlay's coordinates).
 * Everything hangs from the rim, so the parts stay put while the picture grows upward.
 */
data class YtGlassOver(
    val width: Float,
    val height: Float,
    /** The tab row's rim over the strip's foot. */
    val rim: Float,
    val prev: YtBox,
    val next: YtBox,
    /** The most the title may take (between the buttons) and its foot. */
    val titleLeft: Float,
    val titleRight: Float,
    val titleBottom: Float,
    /** The next video's row and the "Wiedergabe" pill where the preview has them at rest. */
    val row: YtBox,
    val pillCenterY: Float,
    val pillCenterYWithoutRow: Float,
)

object YtGlassLayout {
    /** The picture's inset from the slab's sides, in dp. */
    const val INSET = 6

    /** From the slab's top to the picture. */
    const val PICTURE_TOP = 16

    /** The strip's height, with the part under the tab row's rim. */
    const val PICTURE = 74

    /** How far the strip tucks under the tab row's rim (the spec's overlap). */
    const val RIM = 6

    const val HANDLE_WIDTH = 36
    const val HANDLE_HEIGHT = 4
    const val HANDLE_TOP = 8

    /** The glass circles of previous and next and their inset from the picture's sides. */
    const val BUTTON = 36
    const val BUTTON_INSET = 10

    /** The title's foot above the rim, its gap to the buttons, its line height. */
    const val TITLE_GAP = 6
    const val TITLE_SIDE_GAP = 8
    const val TITLE_HEIGHT = 16

    /** The soft edges: how far the picture dissolves into the glass. */
    const val EDGE = 18

    /** The picture's top corners and the slab's. */
    const val CORNER = 26
    const val SLAB_CORNER = 32

    /** The bar under it (LiquidNav of Glas): its height and the margins of its glass. */
    const val BAR = 59
    const val START = 13
    const val END = 11
    const val NAV_TOP = 6

    /** The preview: how much the strip grows ("leicht vergrößert", ×2.3 in the reference). */
    const val GROWTH = 2.3f

    /** The next video's dark glass row: height, inset from the picture's sides, gap to the rim. */
    const val ROW = 46
    const val ROW_INSET = 12
    const val ROW_GAP = 10
    const val ROW_THUMB_WIDTH = 58
    const val ROW_THUMB_HEIGHT = 32

    /** The "Wiedergabe" pill: its height, the gap to the row, the area a finger lets go on. */
    const val PILL = 26
    const val PILL_GAP = 12
    const val PILL_HIT_WIDTH = 168
    const val PILL_HIT_HEIGHT = 40

    /** The docked box's height (the slab above the bar plus the strip's part under the rim). */
    fun boxHeight(density: Float): Float = (PICTURE_TOP + PICTURE) * density

    /** The grown box's height in the preview. */
    fun peekBoxHeight(density: Float): Float = (PICTURE_TOP + PICTURE * GROWTH) * density

    fun rest(boxWidth: Float, density: Float): YtGlassRest {
        val d = density
        val boxHeight = boxHeight(d)
        val picture = YtBox(INSET * d, PICTURE_TOP * d, boxWidth - INSET * d, boxHeight)
        val rim = boxHeight - RIM * d
        val handle = YtBox(
            boxWidth / 2f - HANDLE_WIDTH * d / 2f,
            HANDLE_TOP * d,
            boxWidth / 2f + HANDLE_WIDTH * d / 2f,
            (HANDLE_TOP + HANDLE_HEIGHT) * d,
        )
        val over = over(picture.width, picture.height, d)
        val title = YtBox(over.titleLeft, over.titleBottom - TITLE_HEIGHT * d, over.titleRight, over.titleBottom)
        return YtGlassRest(
            boxWidth = boxWidth,
            boxHeight = boxHeight,
            picture = picture,
            rim = rim,
            handle = handle,
            prev = over.prev.offset(picture.left, picture.top),
            next = over.next.offset(picture.left, picture.top),
            title = title.offset(picture.left, picture.top),
        )
    }

    /** The parts over a picture [width] x [height] px (it grows upward in the preview). */
    fun over(width: Float, height: Float, density: Float): YtGlassOver {
        val d = density
        val rim = height - RIM * d
        // the buttons sit on the part of the strip that shows at rest, from the rim up
        val restTop = rim - (PICTURE - RIM) * d
        val centerY = (restTop + rim) / 2f
        val r = BUTTON * d / 2f
        val prevX = BUTTON_INSET * d + r
        val nextX = width - BUTTON_INSET * d - r
        val prev = YtBox(prevX - r, centerY - r, prevX + r, centerY + r)
        val next = YtBox(nextX - r, centerY - r, nextX + r, centerY + r)
        val titleLeft = prev.right + TITLE_SIDE_GAP * d
        val titleRight = max(titleLeft, next.left - TITLE_SIDE_GAP * d)
        val rowBottom = rim - ROW_GAP * d
        val row = YtBox(ROW_INSET * d, rowBottom - ROW * d, max(ROW_INSET * d, width - ROW_INSET * d), rowBottom)
        val pill = PILL * d / 2f
        return YtGlassOver(
            width = width,
            height = height,
            rim = rim,
            prev = prev,
            next = next,
            titleLeft = titleLeft,
            titleRight = titleRight,
            titleBottom = rim - TITLE_GAP * d,
            row = row,
            pillCenterY = row.top - PILL_GAP * d - pill,
            pillCenterYWithoutRow = rim - (ROW_GAP + PILL_GAP) * d - pill,
        )
    }

    /**
     * The grown preview, in px of the grown box (the box keeps its foot on the bar): the picture
     * 2.3 times as high, the next video's row over its foot ([hasNext]) and the pill above it.
     * [YtMiniPeekGeometry.pill] is the area a finger lets go on, wider than the pill drawn.
     */
    fun peek(boxWidth: Float, density: Float, hasNext: Boolean): YtMiniPeekGeometry {
        val d = density
        val boxHeight = peekBoxHeight(d)
        val picture = YtBox(INSET * d, PICTURE_TOP * d, boxWidth - INSET * d, boxHeight)
        val over = over(picture.width, picture.height, d)
        val centerY = picture.top + if (hasNext) over.pillCenterY else over.pillCenterYWithoutRow
        val hitWidth = min(PILL_HIT_WIDTH * d, picture.width)
        val pill = YtBox(
            boxWidth / 2f - hitWidth / 2f,
            centerY - PILL_HIT_HEIGHT * d / 2f,
            boxWidth / 2f + hitWidth / 2f,
            centerY + PILL_HIT_HEIGHT * d / 2f,
        )
        // what the eye sees of the pill (the title morphs into it)
        val title = YtBox(boxWidth / 2f - 60f * d, centerY - PILL * d / 2f, boxWidth / 2f + 60f * d, centerY + PILL * d / 2f)
        val next = if (hasNext) over.row.offset(picture.left, picture.top) else null
        return YtMiniPeekGeometry(boxHeight, picture, title, pill, next)
    }

    /** The measures of Glas for the shared code (the box, the dock offset, the slab extent). */
    val spec = YtMiniSpec(
        joint = YtMiniJoint.STACK,
        start = START,
        end = END,
        navTop = NAV_TOP,
        overlap = RIM,
        height = PICTURE_TOP + PICTURE - RIM,
        radius = SLAB_CORNER,
        // nominal (393 dp wide screen); the strip runs between the slab's sides
        thumbWidth = 393 - START - END - 2 * INSET,
        thumbHeight = PICTURE,
        thumbStart = INSET,
        thumbRadius = CORNER,
        play = false,
        close = false,
        peekGrowth = GROWTH,
    )

    /**
     * The docked player as the shared geometry has it: the strip is the picture, no play or
     * close button; the margins carry the strip's inset ([YtMiniMargins.playerStart] and
     * [YtMiniMargins.closeEnd]) and a player width of 0 (between them).
     */
    fun geometry(boxWidth: Float, density: Float): YtMiniGeometry {
        val d = density
        val rest = rest(boxWidth, d)
        return YtMiniGeometry(
            boxWidth = boxWidth,
            boxHeight = rest.boxHeight,
            content = rest.rim,
            overlap = RIM * d,
            bodyStart = 0f,
            bodyEnd = boxWidth,
            player = rest.picture,
            play = null,
            close = null,
            text = rest.title,
            margins = YtMiniMargins(
                boxStart = START * d,
                boxEnd = END * d,
                boxWidth = null,
                boxHeight = rest.boxHeight,
                playerWidth = 0f,
                playerHeight = PICTURE * d,
                playerStart = INSET * d,
                playerBottom = 0f,
                button = BUTTON * d,
                closeEnd = INSET * d,
                buttonBottom = 0f,
                playEnd = 0f,
            ),
        )
    }
}

/** YT: how the parts of the Glas player move with the minimize progress and the preview (pure). */
object YtGlassMath {
    fun smoothstep(from: Float, to: Float, x: Float): Float {
        if (to <= from) return if (x >= to) 1f else 0f
        val t = ((x - from) / (to - from)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    /** How far the slab has risen out of the bar at minimize [progress]: it meets the player. */
    fun slabRise(progress: Float): Float = smoothstep(0.3f, 1f, progress)

    /** The radius of the slab's top corners when it reaches [extent] px above the bar. */
    fun slabTop(extent: Float, barRadius: Float, slabRadius: Float): Float {
        if (extent <= 0f) return barRadius
        return lerp(barRadius, slabRadius, smoothstep(0f, slabRadius, extent))
    }

    /**
     * How far the slab reaches above the bar's own glass, in px: the player's extent above the
     * navigation view ([slabPx], YtDock.slabPx) plus the bar's top margin [top], which the slab
     * takes in over its first [top] px, so it starts from nothing without a step.
     */
    fun slabGrowth(slabPx: Float, top: Float): Float {
        if (slabPx <= 0f) return 0f
        if (top <= 0f) return slabPx
        return slabPx + top * (slabPx / top).coerceAtMost(1f)
    }

    /** How strongly the tab row's rim shows over the slab: only with the slab risen. */
    fun plateRim(extent: Float, barRadius: Float): Float = smoothstep(0f, barRadius, extent)

    /** The title fades in late in minimizing and gives way to the pill in the preview. */
    fun titleAlpha(progress: Float, peek: Float): Float =
        smoothstep(0.8f, 1f, progress) * (1f - smoothstep(0.2f, 0.4f, peek))

    /** The glass buttons fade and shrink to 0.8 in the first 30 % of the preview. */
    fun buttonAlpha(peek: Float): Float = 1f - (peek / 0.3f).coerceIn(0f, 1f)

    fun buttonScale(peek: Float): Float = 1f - 0.2f * (peek / 0.3f).coerceIn(0f, 1f)

    /** The dark glass grows behind the title first ... */
    fun pillShown(peek: Float): Float = smoothstep(0f, 0.3f, peek)

    /** ... then moves and takes the label's size ... */
    fun pillMorph(peek: Float): Float = smoothstep(0.2f, 0.6f, peek)

    /** ... while the title turns into the label. */
    fun labelAlpha(peek: Float): Float = smoothstep(0.3f, 0.5f, peek)

    /**
     * The label's letter spacing in em: wide (0.30) while the pill moves, tight (0.06) at rest.
     * In steps of 0.005 em, so a resting label is measured once.
     */
    fun spacing(peek: Float): Float {
        val wide = 0.30f - 0.24f * smoothstep(0.55f, 1f, min(peek, 1f))
        return (wide / 0.005f).roundToInt() * 0.005f
    }

    /** The next video's row slides up after the pill, and down first on the way back. */
    fun rowShown(peek: Float): Float = smoothstep(0.35f, 0.85f, peek)

    /**
     * "Titel · Kanal" in the room [fits] allows: whole if it fits, else the title shortened in its
     * middle ("Anf…nde · Kanal"), else the title alone shortened, else nothing.
     */
    fun titleLine(title: String, channel: String, fits: (String) -> Boolean): String {
        val t = title.trim()
        val c = channel.trim()
        if (c.isEmpty()) return middle(t, "", fits)
        if (t.isEmpty()) return if (fits(c)) c else ""
        val tail = " · $c"
        val whole = t + tail
        if (fits(whole)) return whole
        if (fits("…$tail")) return middle(t, tail, fits)
        return middle(t, "", fits)
    }

    /** The longest "head…end[tail]" of [text] that [fits] (binary search over the kept chars). */
    private fun middle(text: String, tail: String, fits: (String) -> Boolean): String {
        if (fits(text + tail)) return text + tail
        var low = 0
        var high = text.length - 1
        var best = if (fits("…$tail")) "…$tail" else ""
        while (low <= high) {
            val keep = (low + high) / 2
            val candidate = cut(text, keep) + tail
            if (fits(candidate)) {
                best = candidate
                low = keep + 1
            } else {
                high = keep - 1
            }
        }
        return best
    }

    /** [text] with [keep] of its chars left, the ellipsis in the middle (at a space if near). */
    fun cut(text: String, keep: Int): String {
        if (keep >= text.length) return text
        if (keep <= 0) return "…"
        val head = (keep + 1) / 2
        val end = keep - head
        return text.take(head).trimEnd() + "…" + text.takeLast(end).trimStart()
    }
}
