package com.github.libretube.ui.yt.mini

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/*
 * YT: the Editorial mini player ("Druckspalte") in numbers. A square picture in a black rule
 * frame on the white slip of the bar, the kicker "JETZT — KANAL" in mono, the title in Inter
 * bold, the progress as the blue mark on the black rule. The preview unfolds a printed block:
 * a skybox line "02 — NÄCHSTES: …" over a hairline, the picture at 16:9 in its frame with the
 * kicker, the time and the play slip beside it, the headline under it. Pure (px, no Android), so
 * the constraints, the composables and the JVM tests read the same numbers.
 */

/**
 * The preview of Editorial in px of the grown box (its foot stays on the bar), left to right:
 * the shared [geometry] (picture, pill, next row, whose title box is the headline) and the parts
 * only this design has.
 */
data class YtEditorialPeek(
    val geometry: YtMiniPeekGeometry,
    /** Beside the picture, above the pill: the kicker and the time. */
    val column: YtMiniBox,
    /** Under the picture, across the body: the headline in Inter bold. */
    val headline: YtMiniBox,
    /** The top of the hairline between the skybox (the next video) and the picture; null without one. */
    val rule: Float?,
)

object YtEditorialLayout {
    /** The block's thick top rule (the masthead), in dp; the docked slip has the 1 dp hairline. */
    const val MASTHEAD = 3f

    /** The inset of the preview's parts from the body's edges. */
    const val PAD = 12

    /** Without a skybox the picture starts this far under the masthead. */
    const val TOP = 14

    /** The skybox: its top, its height, the gap to its hairline and from there to the picture. */
    const val SKY_TOP = 8
    const val SKY = 34
    const val SKY_GAP = 6
    const val RULE_GAP = 9

    /** The column beside the picture: its gap to the frame. */
    const val COLUMN_GAP = 12

    /** The play slip: its height, its widest and its gap to the column above. */
    const val PILL = 34
    const val PILL_WIDTH = 128
    const val PILL_GAP = 6

    /** The headline under the picture: its gap to the frame, its height (two lines). */
    const val HEADLINE_GAP = 9
    const val HEADLINE = 40

    /** From the headline to the seam on the bar. */
    const val FOOT = 10

    /** 16:9: the square picture opens to the video's own cut. */
    const val WIDE = 16f / 9f

    /** The picture takes at most this share of the body's width in the preview. */
    const val PICTURE_SHARE = 0.56f

    /** The measures of Editorial (round 10's, 1:1). */
    val spec: YtMiniSpec = YtMiniSpecs.EDITORIAL

    /**
     * The preview of [spec] in a box [boxWidth] px wide. The picture grows to [YtMiniSpec.peekGrowth]
     * times its docked height at 16:9. The skybox with the next video ([hasNext]) runs across the
     * top, so the finger that took the picture, which stays where it was near the bar, rests on
     * the headline (no target) and reaches the next video only by a deliberate move up, as in the
     * neutral preview; the play slip stands beside the picture.
     */
    fun peek(spec: YtMiniSpec, boxWidth: Float, density: Float, hasNext: Boolean): YtEditorialPeek {
        val d = density
        val pad = PAD * d
        val right = max(pad, boxWidth - pad)

        val sky = if (hasNext) YtMiniBox(pad, SKY_TOP * d, right, (SKY_TOP + SKY) * d) else null
        val rule = sky?.let { it.bottom + SKY_GAP * d }
        val top = rule?.let { it + RULE_GAP * d } ?: (TOP * d)

        val growth = spec.peekGrowth.coerceIn(1f, 3f)
        var height = spec.thumbHeight * growth * d
        var width = height * WIDE
        // narrow screens: the frame gives way to the column, keeping 16:9
        val most = ((boxWidth - 2f * pad) * PICTURE_SHARE).coerceAtLeast(1f)
        if (width > most) {
            width = most
            height = width / WIDE
        }
        val player = YtMiniBox(pad, top, pad + width, top + height)

        val columnLeft = player.right + COLUMN_GAP * d
        val columnRight = max(columnLeft, right)
        val pillHeight = min(PILL * d, player.height / 2f)
        val pillWidth = min(PILL_WIDTH * d, columnRight - columnLeft)
        val pill = YtMiniBox(columnLeft, player.bottom - pillHeight, columnLeft + pillWidth, player.bottom)
        val column = YtMiniBox(columnLeft, player.top, columnRight, max(player.top, pill.top - PILL_GAP * d))

        val headlineTop = player.bottom + HEADLINE_GAP * d
        val headline = YtMiniBox(pad, headlineTop, right, headlineTop + HEADLINE * d)
        val foot = headline.bottom + FOOT * d
        val boxHeight = max((spec.height + spec.overlap) * d, foot + spec.overlap * d)
        return YtEditorialPeek(
            geometry = YtMiniPeekGeometry(boxHeight, player, headline, pill, sky),
            column = column,
            headline = headline,
            rule = rule,
        )
    }
}

/** YT: how the parts of the Editorial player move (pure). No springs: straight ramps and steps. */
object YtEditorialMath {
    fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    fun lerpBox(a: YtMiniBox, b: YtMiniBox, t: Float) = YtMiniBox(
        lerp(a.left, b.left, t),
        lerp(a.top, b.top, t),
        lerp(a.right, b.right, t),
        lerp(a.bottom, b.bottom, t),
    )

    /** A straight ramp from 0 at [from] to 1 at [to], no easing (print does not ease). */
    fun ramp(from: Float, to: Float, x: Float): Float {
        if (to <= from) return if (x >= to) 1f else 0f
        return ((x - from) / (to - from)).coerceIn(0f, 1f)
    }

    /**
     * Minimizing as a straight wipe: the docked row is printed in from the start edge over the
     * last part of the transition, while the picture settles into its frame.
     */
    fun wipe(progress: Float): Float = ramp(0.55f, 1f, progress)

    /** The preview prints its lines one after another, each left to right, as the block unfolds. */
    fun skyboxPrint(peek: Float): Float = ramp(0.5f, 0.95f, peek)
    fun headlinePrint(peek: Float): Float = ramp(0.3f, 0.8f, peek)
    fun timePrint(peek: Float): Float = ramp(0.45f, 0.85f, peek)

    /** The rules of the block (masthead, hairline, the frame of the slip) come before the type. */
    fun rulesShown(peek: Float): Float = ramp(0.1f, 0.4f, peek)

    /** How thick the top rule is: the docked hairline thickening to the masthead. */
    fun masthead(peek: Float, hairline: Float, thick: Float): Float = lerp(hairline, thick, peek.coerceIn(0f, 1f))

    /** The typewriter: at most this many strokes, this many ms apart (the haptic ticks with them). */
    const val TYPE_STEPS = 8
    const val TYPE_MS = 28

    /** How many strokes type a text of [length] letters: one per letter, at most [TYPE_STEPS]. */
    fun typeSteps(length: Int): Int = length.coerceIn(1, TYPE_STEPS)

    /**
     * How many of [length] letters stand after [ms] of typing in [steps] strokes [stepMs] apart.
     * The first stroke lands at once (with the first tick), the last at (steps - 1) * stepMs.
     */
    fun typedChars(length: Int, steps: Int, ms: Float, stepMs: Int): Int {
        if (length <= 0) return 0
        if (steps <= 0 || stepMs <= 0) return length
        val stroke = min(steps, floor(max(0f, ms) / stepMs).toInt() + 1)
        return min(length, ceil(length.toFloat() * stroke / steps).toInt())
    }

    /** How long typing [steps] strokes takes, the last one included. */
    fun typeMs(steps: Int, stepMs: Int): Int = (steps - 1).coerceAtLeast(0) * stepMs + stepMs

    /** The drop's label: each letter of it this many ms after the last, then the number counts. */
    const val LABEL_MS = 22
    const val COUNT_MS = 55

    /** The count runs at most this many numbers up to the position. */
    const val COUNT_SPAN = 6

    /** The number the drop label shows [ms] after the count started, counting up to [position]. */
    fun countAt(position: Int, ms: Float): Int {
        if (position <= 1) return position
        val from = max(1, position - COUNT_SPAN)
        val step = floor(max(0f, ms) / COUNT_MS).toInt()
        return min(position, from + step)
    }

    /** How long the count takes until it stands at [position]. */
    fun countMs(position: Int): Int = if (position <= 1) 0 else (position - max(1, position - COUNT_SPAN)) * COUNT_MS

    /** A number as the home's list prints it: two digits at least ("03"). */
    fun number(n: Int): String = if (n in 0..9) "0$n" else n.toString()

    /** A position in ms as mono time: "4:07", "12:03", "1:02:09". */
    fun clock(ms: Long): String {
        val total = (max(0L, ms) / 1000L)
        val h = total / 3600L
        val m = (total % 3600L) / 60L
        val s = total % 60L
        fun two(v: Long) = if (v < 10) "0$v" else v.toString()
        return if (h > 0) "$h:${two(m)}:${two(s)}" else "$m:${two(s)}"
    }

    /** The time line: "4:07 / 12:30", only the position while the length is unknown. */
    fun time(positionMs: Long, durationMs: Long): String =
        if (durationMs > 0L) "${clock(min(positionMs, durationMs))} / ${clock(durationMs)}" else clock(positionMs)

    /**
     * A key strike, in dp down, from [ms] after it started: down at once for [down] ms, then
     * straight back over [back] ms, then 0. It jumps down on purpose: type lands, it does not
     * ease in.
     */
    fun strike(ms: Float, depth: Float, down: Int, back: Int): Float = when {
        ms < 0f -> 0f
        ms < down -> depth
        ms < down + back -> depth * (1f - (ms - down) / back)
        else -> 0f
    }

    /** A jammed key: offsets in units of the shake's width, each held 40 ms, then 0. */
    private val jam = floatArrayOf(1f, -1f, 0.6f, -0.35f, 0f)
    const val JAM_MS = 40

    fun jam(ms: Float): Float {
        if (ms < 0f) return 0f
        val i = (ms / JAM_MS).toInt()
        return if (i < jam.size) jam[i] else 0f
    }

    /** How long the jam runs. */
    val jamMs: Int get() = jam.size * JAM_MS

    /** The caret of a waiting drop blinks: on for the first half of each [period] ms. */
    fun caretOn(ms: Float, period: Int): Boolean = period <= 0 || (max(0f, ms) % period) < period / 2f
}
