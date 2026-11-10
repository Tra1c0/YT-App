package com.github.libretube.ui.yt.mini

import kotlin.math.max
import kotlin.math.min

/*
 * YT: the geometry of the docked mini player, in px, as one pure function of the design's
 * measures. The View constraints (YtMiniDesign.constrain) and the composables drawn under and
 * over the video read the same numbers, so they cannot drift apart. No Android in here: the
 * JVM tests check every design at several widths.
 */

/**
 * How the docked player joins the navigation bar:
 * - STACK: as wide as the bar and set on it; the bar squares its top corners, so player and bar
 *   are one surface with a seam (YouTube, Movo, After, Editorial, Glas, Frost).
 * - BULGE: a narrower body that grows out of the bar with concave curves (Side, Formen, Kiesel).
 * - TUCK: a blob pressed onto the blobs of the bar, overlapping them (Clay, Pop).
 */
enum class YtMiniJoint { STACK, BULGE, TUCK }

/** A rectangle in px, left to right, in the coordinates of the player's box (main_container). */
data class YtMiniBox(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f

    fun contains(x: Float, y: Float): Boolean = x >= left && x <= right && y >= top && y <= bottom

    /** True if the two share an area (touching edges do not count). */
    fun overlaps(other: YtMiniBox): Boolean =
        left < other.right && other.left < right && top < other.bottom && other.top < bottom

    fun inflate(by: Float) = YtMiniBox(left - by, top - by, right + by, bottom + by)

    fun offset(dx: Float, dy: Float) = YtMiniBox(left + dx, top + dy, right + dx, bottom + dy)

    /** The same box seen in a box [width] wide that is laid out right to left. */
    fun mirror(width: Float) = YtMiniBox(width - right, top, width - left, bottom)
}

/**
 * The measures of a design's docked player, in dp of the bar (the reference dp that the design
 * screens scale with the screen width). The defaults are those most designs share.
 */
data class YtMiniSpec(
    val joint: YtMiniJoint,
    /** The edges of the bar's surface, from the screen edges. */
    val start: Int,
    val end: Int = start,
    /** Frost: the bar is as wide as its tabs and centered. */
    val width: Int? = null,
    /** From the top of the navigation view to the top of its surface. */
    val navTop: Int = 6,
    /** How far the player reaches down into the bar. */
    val overlap: Int = 1,
    /** The player's own height above the bar. */
    val height: Int,
    val radius: Int,
    /** BULGE: the corner radius of the bar and the curves from the bar up into the body. */
    val navRadius: Int = 0,
    val fillet: Int = 0,
    val thumbWidth: Int,
    val thumbHeight: Int,
    val thumbStart: Int,
    val thumbRadius: Int,
    /** Whether the play button and the close button sit beside the picture. */
    val play: Boolean = true,
    val close: Boolean = true,
    /** How much the long-press preview enlarges the picture ("leicht vergrößert"), 1.25 to 2.4. */
    val peekGrowth: Float = 1.8f,
) {
    /** BULGE: the inset of the body from the bar's edges. */
    val body: Int get() = if (joint == YtMiniJoint.BULGE) navRadius + fillet else 0
}

/**
 * Where the parts of the docked player sit, in px of the box (main_container), left to right
 * (a right-to-left layout mirrors them; the constraints use start and end anyway).
 */
data class YtMiniGeometry(
    val boxWidth: Float,
    val boxHeight: Float,
    /** The part above the bar: the row of picture, text and buttons runs from 0 to here. */
    val content: Float,
    val overlap: Float,
    /** The body's edges (inset from the box for BULGE). */
    val bodyStart: Float,
    val bodyEnd: Float,
    val player: YtMiniBox,
    val play: YtMiniBox?,
    val close: YtMiniBox?,
    /** Title and channel. */
    val text: YtMiniBox,
    val margins: YtMiniMargins,
)

/**
 * The numbers the end ConstraintSet takes, in px. None of them depends on the width of the box,
 * so they can be set before the page is laid out.
 */
data class YtMiniMargins(
    val boxStart: Float,
    val boxEnd: Float,
    /** A fixed width, centered (Frost), or null to run between [boxStart] and [boxEnd]. */
    val boxWidth: Float?,
    val boxHeight: Float,
    val playerWidth: Float,
    val playerHeight: Float,
    val playerStart: Float,
    val playerBottom: Float,
    val button: Float,
    val closeEnd: Float,
    val buttonBottom: Float,
    val playEnd: Float,
)

/**
 * The long-press preview: the box grows upward from the bar (its bottom stays), the picture grows
 * at its foot, title and the play pill stand beside it, the next video above. In px of the grown
 * box, left to right.
 */
data class YtMiniPeekGeometry(
    val boxHeight: Float,
    val player: YtMiniBox,
    val title: YtMiniBox,
    /** Letting go here toggles play and pause. */
    val pill: YtMiniBox,
    /** Letting go here plays the next video; null when there is none. */
    val next: YtMiniBox?,
)

/** What the finger is over in the preview. */
enum class YtMiniTarget { NONE, PILL, NEXT }

object YtMiniLayout {
    /** The play and close buttons: their size, the gap between them and the inset of close. */
    const val BUTTON = 40
    const val BUTTON_GAP = 2
    const val CLOSE_END = 6
    const val TEXT_GAP = 12

    /** The preview: the least inset of its parts, the next video's row, the play pill. */
    const val PEEK_PAD = 8
    const val PEEK_ROW = 56
    const val PEEK_PILL = 40
    const val PEEK_PILL_WIDTH = 148

    /** The picture of the preview takes at most this share of the body's width. */
    const val PEEK_PICTURE_SHARE = 0.56f

    /** [box] in a box [boxWidth] wide, mirrored if [rtl]. */
    fun directed(box: YtMiniBox, boxWidth: Float, rtl: Boolean): YtMiniBox = if (rtl) box.mirror(boxWidth) else box

    /** The docked player of [spec] in a box [boxWidth] px wide, with [density] px per dp. */
    fun rest(spec: YtMiniSpec, boxWidth: Float, density: Float): YtMiniGeometry {
        val d = density
        val content = spec.height * d
        val overlap = spec.overlap * d
        val bodyStart = spec.body * d
        val bodyEnd = boxWidth - spec.body * d
        val thumbTop = (content - spec.thumbHeight * d) / 2f
        val thumbLeft = bodyStart + spec.thumbStart * d
        val player = YtMiniBox(thumbLeft, thumbTop, thumbLeft + spec.thumbWidth * d, thumbTop + spec.thumbHeight * d)

        val button = BUTTON * d
        val buttonTop = (content - button) / 2f
        val close = if (spec.close) {
            val right = bodyEnd - CLOSE_END * d
            YtMiniBox(right - button, buttonTop, right, buttonTop + button)
        } else {
            null
        }
        val play = if (spec.play) {
            val right = close?.let { it.left - BUTTON_GAP * d } ?: (bodyEnd - CLOSE_END * d)
            YtMiniBox(right - button, buttonTop, right, buttonTop + button)
        } else {
            null
        }
        // the text ends a little before the play button (before close, or the body's end)
        val textEnd = when {
            play != null -> play.centerX - (BUTTON / 2f + 4f) * d
            close != null -> close.left - 4f * d
            else -> bodyEnd - CLOSE_END * d
        }
        val text = YtMiniBox(player.right + TEXT_GAP * d, 0f, max(textEnd, player.right + TEXT_GAP * d), content)

        val margins = YtMiniMargins(
            boxStart = if (spec.width != null) 0f else spec.start * d,
            boxEnd = if (spec.width != null) 0f else spec.end * d,
            boxWidth = spec.width?.let { it * d },
            boxHeight = (spec.height + spec.overlap) * d,
            playerWidth = spec.thumbWidth * d,
            playerHeight = spec.thumbHeight * d,
            playerStart = (spec.body + spec.thumbStart) * d,
            playerBottom = overlap,
            button = button,
            closeEnd = (spec.body + CLOSE_END) * d,
            buttonBottom = overlap,
            playEnd = BUTTON_GAP * d,
        )
        return YtMiniGeometry(
            boxWidth = boxWidth,
            boxHeight = (spec.height + spec.overlap) * d,
            content = content,
            overlap = overlap,
            bodyStart = bodyStart,
            bodyEnd = bodyEnd,
            player = player,
            play = play,
            close = close,
            text = text,
            margins = margins,
        )
    }

    /**
     * The neutral preview of [spec] (designs may lay out their own): the picture grows by the
     * design's factor from the foot of the box, keeping its cut, so the finger that holds it stays
     * on it; title and the play pill stand beside it, the next video's row runs above it across the
     * body ([hasNext]), to be reached by moving the finger up.
     */
    fun peek(spec: YtMiniSpec, boxWidth: Float, density: Float, hasNext: Boolean): YtMiniPeekGeometry {
        val d = density
        val pad = max(spec.thumbStart, PEEK_PAD) * d
        val inner = YtMiniBox(spec.body * d, 0f, boxWidth - spec.body * d, 0f)
        val growth = spec.peekGrowth.coerceIn(1f, 3f)
        // the picture keeps its cut; it leaves room for the title and the pill beside it
        val most = ((inner.width - 2f * pad) * PEEK_PICTURE_SHARE).coerceAtLeast(1f)
        val width = min(spec.thumbWidth * growth * d, most)
        val height = width * spec.thumbHeight / spec.thumbWidth.coerceAtLeast(1)

        val next = if (hasNext) YtMiniBox(inner.left + pad, pad, inner.right - pad, pad + PEEK_ROW * d) else null
        val top = (next?.bottom ?: 0f) + pad
        val player = YtMiniBox(inner.left + pad, top, inner.left + pad + width, top + height)
        val columnLeft = player.right + TEXT_GAP * d
        val columnRight = max(inner.right - pad, columnLeft)
        val pillHeight = PEEK_PILL * d
        val pillWidth = min(PEEK_PILL_WIDTH * d, columnRight - columnLeft)
        val pill = YtMiniBox(columnLeft, player.bottom - pillHeight, columnLeft + pillWidth, player.bottom)
        val title = YtMiniBox(columnLeft, player.top, columnRight, max(player.top, pill.top - 4f * d))
        val rest = (spec.height + spec.overlap) * d
        val box = max(rest, player.bottom + pad + spec.overlap * d)
        return YtMiniPeekGeometry(box, player, title, pill, next)
    }

    /**
     * What a finger at [x], [y] is over in the preview [peek] (px of the grown box), with [slop]
     * px of grace around each target.
     */
    fun target(peek: YtMiniPeekGeometry, x: Float, y: Float, slop: Float): YtMiniTarget = when {
        peek.next?.inflate(slop)?.contains(x, y) == true -> YtMiniTarget.NEXT
        peek.pill.inflate(slop).contains(x, y) -> YtMiniTarget.PILL
        else -> YtMiniTarget.NONE
    }
}

/** The measures of each design's docked player (the round-10 looks, 1:1). */
object YtMiniSpecs {
    val YOUTUBE = YtMiniSpec(
        joint = YtMiniJoint.STACK, start = 12, height = 64, radius = 24,
        thumbWidth = 89, thumbHeight = 50, thumbStart = 7, thumbRadius = 10, peekGrowth = 2.2f,
    )
    val MOVO = YtMiniSpec(
        joint = YtMiniJoint.STACK, start = 12, height = 68, radius = 26,
        thumbWidth = 96, thumbHeight = 54, thumbStart = 7, thumbRadius = 14, peekGrowth = 1.9f,
    )

    // cinemascope: the picture is cut to 2.39:1
    val AFTER = YtMiniSpec(
        joint = YtMiniJoint.STACK, start = 12, height = 58, radius = 22,
        thumbWidth = 100, thumbHeight = 42, thumbStart = 8, thumbRadius = 3, peekGrowth = 2.0f,
    )
    val SIDE = YtMiniSpec(
        joint = YtMiniJoint.BULGE, start = 12, navRadius = 26, fillet = 12, height = 60, radius = 22,
        thumbWidth = 80, thumbHeight = 45, thumbStart = 8, thumbRadius = 12, peekGrowth = 1.8f,
    )
    val CLAY = YtMiniSpec(
        joint = YtMiniJoint.TUCK, start = 22, overlap = 12, height = 66, radius = 30,
        thumbWidth = 90, thumbHeight = 51, thumbStart = 8, thumbRadius = 20, peekGrowth = 1.7f,
    )

    // a square cut in a black frame, as the pictures of a newspaper column
    val EDITORIAL = YtMiniSpec(
        joint = YtMiniJoint.STACK, start = 14, height = 60, radius = 0,
        thumbWidth = 44, thumbHeight = 44, thumbStart = 10, thumbRadius = 0, peekGrowth = 2.0f,
    )
    // the Mint reference: a wide strip in one glass slab with the bar; its own layout
    // (YtGlassLayout), these are the measures the shared code reads
    val GLASS = YtGlassLayout.spec
    val POP = YtMiniSpec(
        joint = YtMiniJoint.TUCK, start = 18, navTop = 12, overlap = 4, height = 60, radius = 30,
        thumbWidth = 84, thumbHeight = 48, thumbStart = 6, thumbRadius = 24, peekGrowth = 1.6f,
    )
    val SHAPES = YtMiniSpec(
        joint = YtMiniJoint.BULGE, start = 14, navRadius = 32, fillet = 12, height = 62, radius = 28,
        thumbWidth = 84, thumbHeight = 48, thumbStart = 8, thumbRadius = 24, peekGrowth = 1.8f,
    )

    // Frequency: YouTube neu's stacked card, the picture rounder (Formen's softer corners)
    val FREQUENCY = YtMiniSpec(
        joint = YtMiniJoint.STACK, start = 12, height = 64, radius = 26,
        thumbWidth = 89, thumbHeight = 50, thumbStart = 7, thumbRadius = 14, peekGrowth = 2.2f,
    )

    /** Frost: the bar is as wide as its [tabs] (one wide active pill, the others round). */
    fun frost(tabs: Int) = YtMiniSpec(
        joint = YtMiniJoint.STACK, start = 0, width = 12 + 118 + 54 * (tabs - 1).coerceAtLeast(0), height = 60, radius = 30,
        thumbWidth = 76, thumbHeight = 43, thumbStart = 8, thumbRadius = 14, peekGrowth = 1.8f,
    )

    // a pebble: the picture is cut to a stadium
    val KIESEL = YtMiniSpec(
        joint = YtMiniJoint.BULGE, start = 16, navRadius = 26, fillet = 14, height = 62, radius = 26,
        thumbWidth = 78, thumbHeight = 44, thumbStart = 9, thumbRadius = 22, peekGrowth = 1.9f,
    )

    /**
     * The classic mini player (player_scene.xml's end set, in plain dp): a 54 dp strip across the
     * page, the picture 100 dp wide at its start. Only its preview uses these numbers.
     */
    val STANDARD = YtMiniSpec(
        joint = YtMiniJoint.STACK, start = 0, navTop = 0, overlap = 0, height = 54, radius = 0,
        thumbWidth = 100, thumbHeight = 54, thumbStart = 0, thumbRadius = 0, peekGrowth = 1.6f,
    )
}

/** YT: how the bar under a stacked player answers the minimize progress (pure, for the navs). */
object YtMiniDockMath {
    /**
     * How far the bar's top corners are squared, 0 (round) to 1 (square), at minimize [progress]:
     * nothing while the page still shrinks, then smoothly until the player sits on the bar.
     */
    fun square(progress: Float): Float {
        val t = ((progress - 0.35f) / 0.6f).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /**
     * How near a dragged pointer at [x], [y] is to the box [left]..[bottom] (window px): 0 beyond
     * [reach] px, rising to 1 at its edge and inside.
     */
    fun nearness(x: Float, y: Float, left: Float, top: Float, right: Float, bottom: Float, reach: Float): Float {
        val dx = max(max(left - x, x - right), 0f)
        val dy = max(max(top - y, y - bottom), 0f)
        val distance = kotlin.math.sqrt(dx * dx + dy * dy)
        if (reach <= 0f) return if (distance == 0f) 1f else 0f
        return (1f - distance / reach).coerceIn(0f, 1f)
    }
}
