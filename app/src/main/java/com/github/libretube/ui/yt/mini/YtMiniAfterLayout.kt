package com.github.libretube.ui.yt.mini

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/*
 * YT: the After mini player ("Filmvorführung") in numbers. A bordeaux strip on the bar with a
 * row of sprocket holes along its top edge (the progress), the picture cut to cinemascope in a
 * black gate, the title in Anton caps. The preview opens the gate to 16:9 and runs a credits line
 * "NÄCHSTE VORSTELLUNG" under the frame. Pure (px, no Android), so the constraints, the
 * composables and the JVM tests read the same numbers.
 */

/** Where the sprocket holes of the top edge sit, in px of the box. */
data class YtAfterSprockets(
    /** The left edge of the first hole. */
    val first: Float,
    val pitch: Float,
    val count: Int,
    val top: Float,
    val width: Float,
    val height: Float,
) {
    fun left(index: Int): Float = first + index * pitch
}

object YtAfterLayout {
    /** The body above the bar: a little taller than round 10's 58 dp, for the sprocket row. */
    const val HEIGHT = 62

    /** The sprocket holes: size, pitch, distance from the body's top edge. */
    const val HOLE_WIDTH = 5f
    const val HOLE_HEIGHT = 3f
    const val HOLE_PITCH = 9f
    const val HOLE_TOP = 3.5f

    /** The black gate around the picture. */
    const val GATE = 1.5f

    /** The preview: the picture's top under the sprocket row, the inset at its start. */
    const val PEEK_TOP = 12
    const val PEEK_PAD = 8

    /** The credits line under the frame, its gap to the frame and to the bar. */
    const val CREDITS = 36
    const val CREDITS_GAP = 6
    const val CREDITS_FOOT = 6

    /** Without a next video the frame keeps the docked picture's distance to the bar. */
    const val FOOT = 10

    /** The column beside the frame: its gap to the frame, its inset from the body's end. */
    const val COLUMN_GAP = 12
    const val COLUMN_END = 10

    /** The ticket (the play pill): the area a finger lets go on, its widest, gap to the title. */
    const val TICKET = 40
    const val TICKET_WIDTH = 150
    const val TICKET_GAP = 4

    /** The film frame of a dropped card (its thumbnail between two perforated bands). */
    const val FRAME_WIDTH = 54
    const val FRAME_BAND = 7

    /** 16:9, the gate fully open. */
    const val WIDE = 16f / 9f

    /** The picture takes at most this share of the body's width in the preview. */
    const val PICTURE_SHARE = 0.56f

    /** The measures of After: round 10's cinemascope strip, the body 4 dp taller. */
    val spec: YtMiniSpec = YtMiniSpecs.AFTER.copy(height = HEIGHT)

    /** The cut of the docked picture (100:42, cinemascope). */
    fun scope(spec: YtMiniSpec): Float = spec.thumbWidth.toFloat() / spec.thumbHeight.coerceAtLeast(1)

    /** The sprocket row along the top edge of a body [width] px wide, between its corners. */
    fun sprockets(width: Float, radius: Float, density: Float): YtAfterSprockets {
        val d = density
        val w = HOLE_WIDTH * d
        val pitch = HOLE_PITCH * d
        val from = radius
        val to = width - radius
        val room = to - from
        val count = if (room < w) 0 else floor((room - w) / pitch).toInt() + 1
        val used = if (count == 0) 0f else (count - 1) * pitch + w
        return YtAfterSprockets(
            first = from + (room - used) / 2f,
            pitch = pitch,
            count = count,
            top = HOLE_TOP * d,
            width = w,
            height = HOLE_HEIGHT * d,
        )
    }

    /**
     * The preview in px of the grown box (its foot stays on the bar): the picture twice as high
     * ([YtMiniSpec.peekGrowth]) and 16:9, at the start under the sprocket row; the title and the
     * ticket in a column beside it; the credits line of the next video under it ([hasNext]).
     */
    fun peek(spec: YtMiniSpec, boxWidth: Float, density: Float, hasNext: Boolean): YtMiniPeekGeometry {
        val d = density
        val left = max(spec.thumbStart, PEEK_PAD) * d
        val growth = spec.peekGrowth.coerceIn(1f, 3f)
        var height = spec.thumbHeight * growth * d
        var width = height * WIDE
        // narrow screens: the frame gives way to the column, keeping 16:9
        val most = ((boxWidth - 2f * left) * PICTURE_SHARE).coerceAtLeast(1f)
        if (width > most) {
            width = most
            height = width / WIDE
        }
        val top = PEEK_TOP * d
        val player = YtMiniBox(left, top, left + width, top + height)
        val next = if (hasNext) {
            val rowTop = player.bottom + CREDITS_GAP * d
            YtMiniBox(left, rowTop, max(left, boxWidth - left), rowTop + CREDITS * d)
        } else {
            null
        }
        val foot = next?.let { it.bottom + CREDITS_FOOT * d } ?: (player.bottom + FOOT * d)
        val boxHeight = max((spec.height + spec.overlap) * d, foot + spec.overlap * d)

        val columnLeft = player.right + COLUMN_GAP * d
        val columnRight = max(columnLeft, boxWidth - COLUMN_END * d)
        val ticketHeight = min(TICKET * d, player.height / 2f)
        val ticketWidth = min(TICKET_WIDTH * d, columnRight - columnLeft)
        val pill = YtMiniBox(columnLeft, player.bottom - ticketHeight, columnLeft + ticketWidth, player.bottom)
        val title = YtMiniBox(columnLeft, player.top, columnRight, max(player.top, pill.top - TICKET_GAP * d))
        return YtMiniPeekGeometry(boxHeight, player, title, pill, next)
    }
}

/** YT: how the parts of the After player move (pure). */
object YtAfterMath {
    fun smoothstep(from: Float, to: Float, x: Float): Float {
        if (to <= from) return if (x >= to) 1f else 0f
        val t = ((x - from) / (to - from)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    /**
     * The height of each black bar of the gate over a picture [width] x [height] px: what the
     * picture has beyond the cut [scope] (width / height), times how far the gate is [closed].
     */
    fun gateBar(width: Float, height: Float, scope: Float, closed: Float): Float {
        if (scope <= 0f || width <= 0f) return 0f
        return max(0f, (height - width / scope) / 2f) * closed.coerceIn(0f, 1f)
    }

    /** Minimizing: the gate closes early, so the frame shrinks on in cinemascope. */
    fun minimizeClosed(progress: Float): Float = smoothstep(0.1f, 0.65f, progress)

    /** The preview: the frame grows in cinemascope first, then the bars retract to 16:9. */
    fun peekClosed(peek: Float): Float = 1f - smoothstep(0.4f, 0.95f, peek)

    /** How closed the gate is: by the preview while one runs, else by minimizing. */
    fun gateClosed(progress: Float, peek: Float): Float =
        if (peek > 0f) peekClosed(peek) else minimizeClosed(progress)

    /** How much of sprocket hole [index] of [count] is filled at [fraction] of the video. */
    fun holeFill(index: Int, count: Int, fraction: Float): Float =
        (fraction.coerceIn(0f, 1f) * count - index).coerceIn(0f, 1f)

    /**
     * The landing threads the film: hole [index] of [count] shows as [settle] runs from 0 to 1,
     * left to right, each one fading in over the last 30 %.
     */
    fun thread(index: Int, count: Int, settle: Float): Float {
        if (count <= 0) return 1f
        val start = 0.7f * index / count
        return smoothstep(start, start + 0.3f, settle)
    }

    /** The preview's title and ticket come once the frame has grown a little. */
    fun partsShown(peek: Float): Float = smoothstep(0.3f, 0.8f, peek)

    /** The credits line slides up after the gate starts opening, and goes first on the way back. */
    fun creditsShown(peek: Float): Float = smoothstep(0.45f, 0.9f, peek)

    /**
     * A film frame pulled through in [pulls] jerks, as a projector's claw does: each pull moves
     * over the first 60 % of its share of [t] (eased), then the frame stands. 0 to 1, rising.
     */
    fun intermittent(t: Float, pulls: Int): Float {
        if (pulls <= 0) return t.coerceIn(0f, 1f)
        val x = t.coerceIn(0f, 1f) * pulls
        if (x >= pulls) return 1f
        val done = floor(x)
        val within = ((x - done) / 0.6f).coerceIn(0f, 1f)
        return (done + within * within * (3f - 2f * within)) / pulls
    }

    /** How long a dropped card's frame takes to be pulled into the gate, and in how many jerks. */
    const val PULL_MS = 420
    const val PULLS = 3

    /** The landing's rattle: the picture jumps in the gate, in dp, in four 35 ms steps, then rests. */
    private val rattle = floatArrayOf(1f, -0.7f, 0.45f, -0.2f)
    const val RATTLE_STEP = 35
    const val RATTLE_STEPS = 4

    fun rattle(ms: Float): Float {
        if (ms < 0f) return 0f
        val step = (ms / RATTLE_STEP).toInt()
        return if (step < rattle.size) rattle[step] else 0f
    }

    /** The lamp strikes with the rattle: the brightness of each step, then dark. */
    private val flicker = floatArrayOf(0.12f, 0f, 0.07f, 0f)

    fun flicker(ms: Float): Float {
        if (ms < 0f) return 0f
        val step = (ms / RATTLE_STEP).toInt()
        return if (step < flicker.size) flicker[step] else 0f
    }

    /**
     * A jammed film: a shake held for 40 ms at a time (as frames at 25 per second), from [t]
     * 0 to 1, starting and ending at 0.
     */
    fun judder(t: Float): Float {
        if (t <= 0f || t >= 1f) return 0f
        val held = floor(t * 9f) / 9f
        // the same damped swing as YtMiniDesign.wobble (kept here, so this file needs no Android)
        return exp(-5f * held) * sin(2f * PI.toFloat() * 3f * held)
    }
}
