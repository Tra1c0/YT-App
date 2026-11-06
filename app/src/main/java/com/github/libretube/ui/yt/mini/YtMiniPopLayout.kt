package com.github.libretube.ui.yt.mini

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin

/*
 * YT: the Pop mini player ("Sticker") in numbers. Pop's home is lavender with butter-yellow
 * cards, pink accents and a plum blob that slides along the nav band, so the docked player is a
 * lavender pill pressed onto the band with the picture as a sticker (pink shadow) and the plum
 * blob behind play. The long press inflates the pill like a balloon (1.6 times the picture); the
 * next video slaps on as a yellow sticker turned -6 degrees. The blob has a mouth that opens for
 * a dragged card and gulps it. Pure (px, no Android), so the View constraints, both composables
 * and the JVM tests read the same numbers.
 */
object YtPopLayout {
    /** The preview enlarges the picture 1.6 times. */
    const val GROWTH = 1.6f

    /** The preview's inset, the gap under the sticker, the gap between picture and column. */
    const val PAD = 10
    const val GAP = 10
    const val COLUMN_GAP = 12

    /** The next video's sticker: its height, the share of the inner width, its turn. */
    const val STICKER = 58
    const val STICKER_SHARE = 0.86f
    const val STICKER_TURN = -6f

    /** The play pill of the preview (the blob, stretched), and the least height of the column. */
    const val PILL = 40
    const val PILL_WIDTH = 136
    const val PILL_GAP = 6
    const val COLUMN_MIN = 88

    /** The picture takes at most this share of the inner width in the preview. */
    const val PICTURE_SHARE = 0.56f

    /** The plum blob behind play, the pink sticker shadow behind the picture (offset). */
    const val BLOB = 44
    const val SHADOW = 3
    const val SHADOW_PEEK = 5

    /** The balloon's top corners in the preview (the rest pill has the spec's radius). */
    const val BALLOON_RADIUS = 46

    /**
     * How far the corners of a [width] x [height] box turned by [degrees] around its centre
     * reach above (and below) the box: what the sticker needs of room.
     */
    fun rise(width: Float, height: Float, degrees: Float): Float {
        val a = abs(degrees) * PI.toFloat() / 180f
        return width / 2f * sin(a) + height / 2f * (1f - cos(a))
    }

    /**
     * The preview of a box [boxWidth] px wide (its foot stays on the bar): with a next video
     * ([hasNext]) its sticker across the top, room left for its turn; below it the picture grown
     * 1.6 times at the box's start (where the finger holds it, the foot near the bar); beside it
     * the title and, at the picture's foot, the plum pill of play and pause.
     */
    fun peek(spec: YtMiniSpec, boxWidth: Float, density: Float, hasNext: Boolean): YtMiniPeekGeometry {
        val d = density
        val pad = PAD * d
        val left = pad
        val right = max(boxWidth - pad, left + 1f)
        val inner = right - left
        val most = ((inner - COLUMN_GAP * d) * PICTURE_SHARE).coerceAtLeast(1f)
        val width = min(spec.thumbWidth * GROWTH * d, most)
        val height = width * spec.thumbHeight / spec.thumbWidth.coerceAtLeast(1)
        val row = max(height, COLUMN_MIN * d)

        var rowTop = pad
        var next: YtMiniBox? = null
        if (hasNext) {
            val sw = inner * STICKER_SHARE
            val sh = STICKER * d
            val rise = rise(sw, sh, STICKER_TURN)
            val top = pad + rise
            val l = left + (inner - sw) / 2f
            next = YtMiniBox(l, top, l + sw, top + sh)
            rowTop = next.bottom + rise + GAP * d
        }

        val player = YtMiniBox(left, rowTop + row - height, left + width, rowTop + row)
        val columnLeft = player.right + COLUMN_GAP * d
        val columnRight = max(right, columnLeft)
        val pillWidth = min(PILL_WIDTH * d, columnRight - columnLeft)
        val pill = YtMiniBox(columnLeft, player.bottom - PILL * d, columnLeft + pillWidth, player.bottom)
        val title = YtMiniBox(columnLeft, rowTop, columnRight, max(rowTop, pill.top - PILL_GAP * d))
        val rest = (spec.height + spec.overlap) * d
        val box = max(rest, player.bottom + pad + spec.overlap * d)
        return YtMiniPeekGeometry(box, player, title, pill, next)
    }

    /** The blob's square around the play button [play]. */
    fun blob(play: YtMiniBox, density: Float): YtMiniBox {
        val r = BLOB * density / 2f
        return YtMiniBox(play.centerX - r, play.centerY - r, play.centerX + r, play.centerY + r)
    }

    /** [box] of a box [height] px tall seen in one [current] px tall: its foot stays on the bar. */
    fun fromFoot(box: YtMiniBox, height: Float, current: Float): YtMiniBox = box.offset(0f, current - height)
}

/** YT: how the Pop player moves (pure). */
object YtPopMath {
    fun smoothstep(from: Float, to: Float, x: Float): Float {
        if (to <= from) return if (x >= to) 1f else 0f
        val t = ((x - from) / (to - from)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    fun lerp(a: YtMiniBox, b: YtMiniBox, t: Float): YtMiniBox =
        YtMiniBox(lerp(a.left, b.left, t), lerp(a.top, b.top, t), lerp(a.right, b.right, t), lerp(a.bottom, b.bottom, t))

    /** A damped swing from 0 (as YtMiniDesign.wobble), for the composables. */
    fun wobble(t: Float, frequency: Float, decay: Float): Float =
        exp(-decay * t) * sin(2f * PI.toFloat() * frequency * t)

    /**
     * The balloon's width while it inflates: [wide] is a softer spring that follows the preview
     * [peek] (0 to 1); behind it the balloon is narrower, swinging past it wider, so the width
     * lags the height as jelly does. 1 at rest, kept within a few percent (the picture beside
     * the balloon's edge does not scale with it).
     */
    fun squash(wide: Float, peek: Float): Float = (1f + 0.12f * (wide - peek.coerceIn(0f, 1f))).coerceIn(0.965f, 1.035f)

    /** The most the blob's mouth opens, in degrees. */
    const val MOUTH = 74f

    /**
     * Where the mouth faces, in degrees (0 is the end side, -90 up), towards a pointer at [px],
     * [py] from the blob's centre [cx], [cy]: kept to the upper half, the bar is below.
     */
    fun mouthFacing(cx: Float, cy: Float, px: Float, py: Float): Float {
        val dx = px - cx
        val dy = py - cy
        if (dx * dx + dy * dy < 1f) return -90f
        val angle = atan2(dy, dx) * 180f / PI.toFloat()
        // below the centre: the side it is on, up a little
        val up = if (angle > 0f) (if (angle < 90f) -20f else -160f) else angle
        return up.coerceIn(-160f, -20f)
    }

    /** How far the mouth opens (0 to 1) for a dragged card's nearness [hover] (0 to 1). */
    fun mouthFromHover(hover: Float): Float = smoothstep(0.05f, 0.8f, hover)

    // the gulp after a drop, [t] 0 to 1 over GULP_MS: the card flies into the open mouth, the
    // mouth snaps shut, the blob swells as it swallows and settles
    const val GULP_MS = 900
    private const val FLIGHT = 0.13f
    private const val CHOMP = 0.2f

    /** How far the card has flown from where it was let go into the mouth (eased in, sucked). */
    fun gulpFlight(t: Float): Float {
        val f = (t / FLIGHT).coerceIn(0f, 1f)
        return f * f
    }

    /** The mouth while gulping: wide open during the flight, shut by [CHOMP]. */
    fun gulpMouth(t: Float): Float = when {
        t <= 0f || t >= 1f -> 0f
        t < FLIGHT -> 1f
        t < CHOMP -> 1f - smoothstep(FLIGHT, CHOMP, t)
        else -> 0f
    }

    /** The blob's swell while it swallows (0 at rest). */
    fun gulpSwell(t: Float): Float {
        if (t <= CHOMP || t >= 1f) return 0f
        val s = (t - CHOMP) / (1f - CHOMP)
        return wobble(s, 1.4f, 4.2f).coerceAtLeast(-0.4f)
    }

    /** Whether the card is still in flight (drawn) at [t]. */
    fun inFlight(t: Float): Boolean = t > 0f && t < FLIGHT

    /**
     * The point where a ray from the centre at [c], [s] (cosine and sine of its angle) leaves a
     * stadium with half-axes [a], [b], relative to the centre, into [out] at [at].
     */
    fun stadium(a: Float, b: Float, c: Float, s: Float, out: FloatArray, at: Int) {
        if (a < b) {
            // upright: the same with the axes swapped
            stadium(b, a, s, c, out, at)
            val x = out[at]
            out[at] = out[at + 1]
            out[at + 1] = x
            return
        }
        val straight = a - b
        if (abs(s) > 1e-6f) {
            val x = b * c / abs(s)
            if (abs(x) <= straight) {
                out[at] = x
                out[at + 1] = b * sign(s)
                return
            }
        }
        // on a round end: the circle of radius b around (+-straight, 0)
        val t = straight * abs(c) + kotlin.math.sqrt((b * b - straight * straight * s * s).coerceAtLeast(0f))
        out[at] = t * c
        out[at + 1] = t * s
    }

    /**
     * The blob's outline: [count] points (x, y pairs into [out]) around [cx], [cy] with half-axes
     * [rx], [ry]: a superellipse of exponent [n] (2 is an ellipse) that becomes a stadium as
     * [pill] goes to 1 (the blob stretching into the preview's pill), its rim wobbling by [amp]
     * (a share of the radius) with [phase]. A [mouth] (0 to 1 of [MOUTH] degrees) facing
     * [facing] degrees cuts a wedge out of it whose tip lies 35 % of the way to the rim, so the
     * glyph at the centre stays on the blob. Returns how many floats it wrote ([out] needs
     * 2 * (count + 1)).
     */
    fun blobPoints(
        cx: Float,
        cy: Float,
        rx: Float,
        ry: Float,
        n: Float,
        pill: Float,
        amp: Float,
        phase: Float,
        mouth: Float,
        facing: Float,
        count: Int,
        out: FloatArray,
    ): Int {
        val open = (mouth.coerceIn(0f, 1f) * MOUTH)
        val hasMouth = open > 0.5f
        val toRad = PI.toFloat() / 180f
        val from = if (hasMouth) (facing + open / 2f) else facing
        val sweep = if (hasMouth) 360f - open else 360f
        val steps = count.coerceAtLeast(3)
        val e = 2f / n.coerceAtLeast(0.5f)
        val stretch = pill.coerceIn(0f, 1f)
        var i = 0
        // closed: the last point would repeat the first, so it stops one step short
        val last = if (hasMouth) steps - 1 else steps
        for (k in 0 until steps) {
            val a = (from + sweep * k / last) * toRad
            val c = cos(a)
            val s = sin(a)
            val f = 1f + amp * (0.6f * sin(3f * a + phase) + 0.4f * sin(2f * a - 1.3f * phase))
            var x = rx * sign(c) * abs(c).pow(e)
            var y = ry * sign(s) * abs(s).pow(e)
            if (stretch > 0f) {
                stadium(rx, ry, c, s, out, i)
                x = lerp(x, out[i], stretch)
                y = lerp(y, out[i + 1], stretch)
            }
            out[i++] = cx + x * f
            out[i++] = cy + y * f
        }
        if (hasMouth) {
            val a = facing * toRad
            out[i++] = cx + rx * 0.35f * cos(a)
            out[i++] = cy + ry * 0.35f * sin(a)
        }
        return i
    }

    /**
     * The next video's sticker slapping on, [s] its spring (0 to 1, swinging past): it comes
     * down from larger and more turned, squashes a little under the hand and settles at -6.
     */
    fun slapScale(s: Float): Float = lerp(1.45f, 1f, s)

    fun slapTurn(s: Float, lifted: Float): Float = lerp(-18f, YtPopLayout.STICKER_TURN, s) + 3f * lifted

    fun slapAlpha(s: Float): Float = (s * 2.5f).coerceIn(0f, 1f)

    /** The glyph flash: pops past its size in a fifth, settles, fades in the last 40 %. */
    fun flashScale(t: Float): Float = if (t < 0.2f) lerp(0.4f, 1.15f, t / 0.2f) else lerp(1.15f, 1f, ((t - 0.2f) / 0.25f).coerceAtMost(1f))

    fun flashAlpha(t: Float): Float = if (t < 0.6f) 1f else (1f - (t - 0.6f) / 0.4f).coerceIn(0f, 1f)
}
