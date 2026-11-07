package com.github.libretube.ui.yt

/*
 * YT: the small like/dislike bar next to the like button (Return YouTube Dislike numbers) and
 * the short hover that reveals the dislike count in the hold tiles. Everything here is plain
 * Kotlin on ARGB ints, so the split, the colours and the timing are tested on the JVM.
 */

/** How the bar sits at the like button of a design. */
enum class YtSentimentPlacement {
    /** A short bar just below the button, centred. */
    UNDER,

    /** A short bar inside the button's own frame, near its bottom edge (film frame, table cell). */
    INSIDE,

    /** An arc that hugs the lower edge of a round button. */
    ARC,

    /** A small wave just below the button (the wavy lines of Formen). */
    WAVE,
}

/**
 * The bar's form for one design, in dp. [gapDp] is the distance from the button's bottom edge
 * (outwards for UNDER, ARC and WAVE; inwards for INSIDE); [startDp] aligns the bar to the start
 * instead of centring it (Editorial's text sits at the start of its cell).
 */
data class YtSentimentStyle(
    val placement: YtSentimentPlacement,
    val thicknessDp: Float,
    val gapDp: Float,
    val widthFraction: Float,
    val roundCaps: Boolean,
    val startDp: Float? = null,
    /** ARC only: the angle the arc spans, centred on the bottom. */
    val sweepDegrees: Float = 0f,
    /** WAVE only: the height of a crest. */
    val amplitudeDp: Float = 0f,
) {
    companion object {
        /**
         * Gaps are kept below the room each page leaves under the like button (captions start
         * 6 dp below the round buttons, rows of tiles are 10 dp apart), so nothing overlaps.
         */
        fun of(signature: YtSignature): YtSentimentStyle = when (signature) {
            YtSignature.AFTER -> YtSentimentStyle(YtSentimentPlacement.INSIDE, 2f, 6f, 0.46f, roundCaps = false)
            YtSignature.CLAY -> YtSentimentStyle(YtSentimentPlacement.UNDER, 4f, 3.5f, 0.5f, roundCaps = true)
            YtSignature.EDITORIAL -> YtSentimentStyle(YtSentimentPlacement.INSIDE, 2f, 9f, 0.55f, roundCaps = false, startDp = 6f)
            YtSignature.GLASS, YtSignature.FROST ->
                YtSentimentStyle(YtSentimentPlacement.ARC, 2.5f, 2.5f, 1f, roundCaps = true, sweepDegrees = 84f)
            YtSignature.POP -> YtSentimentStyle(YtSentimentPlacement.UNDER, 4f, 3.5f, 0.44f, roundCaps = true)
            YtSignature.SHAPES -> YtSentimentStyle(YtSentimentPlacement.WAVE, 1.8f, 3f, 0.62f, roundCaps = true, amplitudeDp = 1.1f)
            YtSignature.KIESEL -> YtSentimentStyle(YtSentimentPlacement.UNDER, 4f, 5f, 0.5f, roundCaps = true)
            else -> YtSentimentStyle(YtSentimentPlacement.UNDER, 3f, 4f, 0.56f, roundCaps = true)
        }
    }
}

object YtSentiment {
    /** Half the width of the soft blend between green and red, as a fraction of the bar. */
    const val BLEND = 0.09f

    /** Neither colour disappears completely, so a 99 % video still shows its other end. */
    const val MIN_END = 0.03f

    /** The bar is graphics, not text: 3:1 against the page is enough (WCAG 1.4.11). */
    const val MIN_CONTRAST = 3.0

    /**
     * The liked share, 0 to 1, or null when there is nothing to show: no RYD answer (negative
     * dislikes), hidden likes, or no votes at all.
     */
    fun share(likes: Long, dislikes: Long): Float? {
        if (dislikes < 0 || likes <= 0) return null
        return (likes.toDouble() / (likes.toDouble() + dislikes.toDouble())).toFloat()
    }

    /** [share] in whole percent, cut and not rounded up, so 99.6 % never reads as 100 %. */
    fun percent(share: Float): Int = (share * 100f).toInt().coerceIn(0, 100)

    /**
     * Colour stops of the bar from its start: green up to the liked share, then a short blend,
     * then red. With [rtl] the bar starts at the right, so the stops are mirrored.
     */
    fun stops(share: Float, green: Int, red: Int, rtl: Boolean = false): List<Pair<Float, Int>> {
        val split = share.coerceIn(MIN_END, 1f - MIN_END)
        // a pure piece of each colour stays at both ends
        val a = (split - BLEND).coerceAtLeast(MIN_END)
        val b = (split + BLEND).coerceAtMost(1f - MIN_END)
        val ltr = listOf(0f to green, a to green, b to red, 1f to red)
        return if (!rtl) ltr else ltr.reversed().map { (at, color) -> (1f - at) to color }
    }

    /**
     * The design's own green and red, made to stand out from [background] by [MIN_CONTRAST]
     * and from each other in lightness (green lighter on dark pages, darker on light ones), so
     * the split still reads without telling red from green.
     */
    fun colors(signature: YtSignature, background: Int): Pair<Int, Int> {
        val dark = YtContrast.luminance(background) < 0.25
        val (g, r) = base(signature, dark)
        var green = YtContrast.readable(g, background, MIN_CONTRAST)
        var red = YtContrast.readable(r, background, MIN_CONTRAST)
        // too alike in lightness: move the one nearer the page further away from it (red darker
        // on light pages, green lighter on dark ones); that only adds contrast to the page
        var t = 0.0
        while (YtContrast.ratio(green, red) < MIN_APART && t < 1.0) {
            t += 0.05
            if (dark) green = YtContrast.mix(green, WHITE, 0.05) else red = YtContrast.mix(red, BLACK, 0.05)
        }
        return green to red
    }

    /** Green and red apart by at least this ratio (lightness, not hue). */
    const val MIN_APART = 1.3

    /** The tones each design starts from, for light and dark pages. */
    private fun base(signature: YtSignature, dark: Boolean): Pair<Int, Int> = when (signature) {
        // graphite with YouTube red
        YtSignature.YOUTUBE -> 0xFF5EE29A.toInt() to 0xFFFF334B.toInt()
        // wine poster: olive film green, warm cinema red
        YtSignature.AFTER -> 0xFFB5D88A.toInt() to 0xFFE8505B.toInt()
        // dough: sage and terracotta
        YtSignature.CLAY -> if (dark) 0xFFA9CFA0.toInt() to 0xFFE08A7E.toInt() else 0xFF6E9A68.toInt() to 0xFFC0574F.toInt()
        // print: ink green and newspaper red
        YtSignature.EDITORIAL -> if (dark) 0xFF6CCB8E.toInt() to 0xFFFF6B6B.toInt() else 0xFF1E7A46.toInt() to 0xFFC8102E.toInt()
        // mint glass: teal green, the glass red
        YtSignature.GLASS -> if (dark) 0xFF7FE0C2.toInt() to 0xFFFF7A7A.toInt() else 0xFF1F8A6A.toInt() to 0xFFE03434.toInt()
        // candy: mint and the Pop pink
        YtSignature.POP -> if (dark) 0xFF8FE8B8.toInt() to 0xFFF27AA6.toInt() else 0xFF2E9E66.toInt() to 0xFFD0386F.toInt()
        // night shapes: lime and salmon
        YtSignature.SHAPES -> 0xFFC6F36B.toInt() to 0xFFF28B82.toInt()
        // frosted sage: ice mint and coral
        YtSignature.FROST -> 0xFFD2F5DC.toInt() to 0xFFFF9E8A.toInt()
        // pebbles: pebble green and brick
        YtSignature.KIESEL -> if (dark) 0xFFA8D8B0.toInt() to 0xFFF2A08A.toInt() else 0xFF4F8A5B.toInt() to 0xFFB8573F.toInt()
        // Movo indigo page, Side's warm grey
        YtSignature.MOVO, YtSignature.SIDE -> 0xFF6FD99A.toInt() to 0xFFFF6F7A.toInt()
    }

    private const val BLACK = -0x1000000 // 0xFF000000
    private const val WHITE = -0x1 // 0xFFFFFFFF
}

/**
 * The short hover on the dislike tile that reveals the count: it is due [holdMs] after the
 * finger came onto the tile and stayed there; leaving the tile starts over.
 */
class YtRevealTimer(private val holdMs: Long = HOLD_MS) {
    private var since: Long? = null

    /** The finger is ([on]) or is not over the dislike tile at [now]. */
    fun hover(on: Boolean, now: Long) {
        since = if (on) since ?: now else null
    }

    /** Milliseconds left until the reveal, or null when the finger is not on the tile. */
    fun remaining(now: Long): Long? = since?.let { (it + holdMs - now).coerceAtLeast(0) }

    /** The hover lasted long enough at [now]. */
    fun due(now: Long): Boolean = remaining(now) == 0L

    fun reset() {
        since = null
    }

    companion object {
        const val HOLD_MS = 600L
    }
}
