package com.github.libretube.ui.yt.bloom

/**
 * YT: the maths of the update dialog's bloom (pure, no Android): a small ARGB frame becomes a soft,
 * blurred, glowing still picture. Steps: saturation, blur, glow (bright parts blurred wider and
 * screen-blended back), a slight dim and a hint of the design's accent as a multiplicative tint
 * (so a black frame stays black). Channels are worked on as floats in 0..1.
 */
object YtBloomMath {
    data class Params(
        val blurRadius: Int = 2,
        val glowRadius: Int = 5,
        val threshold: Float = 0.6f,
        val glowGain: Float = 0.9f,
        val saturation: Float = 1.25f,
        val dim: Float = 0.25f,
        /** Accent as ARGB (alpha ignored), or null: no tint. */
        val accent: Int? = null,
        val tint: Float = 0.12f,
    )

    /** The bloom of [src] (ARGB, [width] x [height]); same size, fully opaque. */
    fun bloom(src: IntArray, width: Int, height: Int, params: Params = Params()): IntArray {
        val size = width * height
        if (width <= 0 || height <= 0 || src.size < size) return IntArray(maxOf(size, 0))
        val r = FloatArray(size)
        val g = FloatArray(size)
        val b = FloatArray(size)
        for (i in 0 until size) {
            val c = src[i]
            r[i] = ((c shr 16) and 0xFF) / 255f
            g[i] = ((c shr 8) and 0xFF) / 255f
            b[i] = (c and 0xFF) / 255f
        }
        saturate(r, g, b, params.saturation)
        for (ch in arrayOf(r, g, b)) blur(ch, width, height, params.blurRadius)

        // glow: what is brighter than the threshold, blurred wider, screen-blended back
        val gr = FloatArray(size)
        val gg = FloatArray(size)
        val gb = FloatArray(size)
        val t = params.threshold.coerceIn(0f, 0.99f)
        for (i in 0 until size) {
            gr[i] = ((r[i] - t) / (1f - t)).coerceAtLeast(0f)
            gg[i] = ((g[i] - t) / (1f - t)).coerceAtLeast(0f)
            gb[i] = ((b[i] - t) / (1f - t)).coerceAtLeast(0f)
        }
        for (ch in arrayOf(gr, gg, gb)) blur(ch, width, height, params.glowRadius)
        val gain = params.glowGain.coerceIn(0f, 4f)
        val keep = 1f - params.dim.coerceIn(0f, 0.9f)
        val accent = params.accent
        val tint = params.tint.coerceIn(0f, 1f)
        val mr = tintFactor(accent, 16, tint)
        val mg = tintFactor(accent, 8, tint)
        val mb = tintFactor(accent, 0, tint)

        val out = IntArray(size)
        for (i in 0 until size) {
            val or = screen(r[i], gr[i] * gain) * keep * mr
            val og = screen(g[i], gg[i] * gain) * keep * mg
            val ob = screen(b[i], gb[i] * gain) * keep * mb
            out[i] = (0xFF shl 24) or (to8(or) shl 16) or (to8(og) shl 8) or to8(ob)
        }
        return out
    }

    /** Pulls the colours towards (>1: away from) their grey; clamps to 0..1. */
    fun saturate(r: FloatArray, g: FloatArray, b: FloatArray, amount: Float) {
        val s = amount.coerceIn(0f, 3f)
        for (i in r.indices) {
            val grey = 0.299f * r[i] + 0.587f * g[i] + 0.114f * b[i]
            r[i] = (grey + (r[i] - grey) * s).coerceIn(0f, 1f)
            g[i] = (grey + (g[i] - grey) * s).coerceIn(0f, 1f)
            b[i] = (grey + (b[i] - grey) * s).coerceIn(0f, 1f)
        }
    }

    /** Three box-blur passes (about a Gaussian) of [radius] in both directions, edges clamped. */
    fun blur(ch: FloatArray, width: Int, height: Int, radius: Int) {
        if (radius <= 0 || width <= 0 || height <= 0) return
        val line = FloatArray(maxOf(width, height))
        repeat(3) {
            for (y in 0 until height) {
                for (x in 0 until width) line[x] = ch[y * width + x]
                boxLine(line, width, radius)
                for (x in 0 until width) ch[y * width + x] = line[x]
            }
            for (x in 0 until width) {
                for (y in 0 until height) line[y] = ch[y * width + x]
                boxLine(line, height, radius)
                for (y in 0 until height) ch[y * width + x] = line[y]
            }
        }
    }

    private fun boxLine(line: FloatArray, n: Int, radius: Int) {
        // edge-clamped moving average; the window sum is kept running
        val copy = line.copyOf(n)
        val window = 2 * radius + 1
        var sum = 0f
        for (k in -radius..radius) sum += copy[k.coerceIn(0, n - 1)]
        for (i in 0 until n) {
            line[i] = sum / window
            sum += copy[(i + radius + 1).coerceIn(0, n - 1)] - copy[(i - radius).coerceIn(0, n - 1)]
        }
    }

    private fun screen(a: Float, b: Float) = 1f - (1f - a.coerceIn(0f, 1f)) * (1f - b.coerceIn(0f, 1f))

    private fun tintFactor(accent: Int?, shift: Int, amount: Float): Float {
        if (accent == null) return 1f
        val channel = ((accent shr shift) and 0xFF) / 255f
        return 1f + amount * (channel - 1f)
    }

    private fun to8(v: Float) = (v.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
}
