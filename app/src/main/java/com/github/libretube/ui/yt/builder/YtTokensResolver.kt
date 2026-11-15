package com.github.libretube.ui.yt.builder

/**
 * YT: resolves the token references of a theme for one look (pure): colours of a role or a named
 * token (day, or night for the dark look of a BOTH theme), type tokens, radii. References to
 * tokens that do not exist fall back to the role defaults, never fail; a token that names another
 * token is followed at most a few steps (a loop ends at the fallback).
 */
class YtTokensResolver(private val doc: YtThemeDoc, private val night: Boolean) {
    private val tokens = doc.tokens

    /** The colour of token [name]; gradients give their first stop. */
    fun argb(name: String): Int = argb(name, 0)

    private fun argb(name: String, depth: Int): Int {
        val token = tokens.colors[name] ?: return YtThemeSeeds.fallbackRole(name)
        val paint = (if (night && doc.mode == YtThemeMode.BOTH) token.night else null) ?: token.day
        return solidOf(paint, depth + 1)
    }

    private fun solidOf(p: YtPaint, depth: Int): Int {
        val ref = p.ref
        if (ref != null && depth < 4) return argb(ref, depth)
        if (ref != null) return YtThemeSeeds.fallbackRole(ref)
        return when (p.type) {
            YtPaintType.SOLID -> p.color
            else -> p.stops.firstOrNull()?.let { s -> s.ref?.let { argb(it, depth) } ?: s.color } ?: p.color
        }
    }

    /** A paint with every reference replaced by its colour (stops too), ready to draw. */
    fun resolve(p: YtPaint): YtPaint {
        val stops = if (p.type == YtPaintType.SOLID) emptyList() else YtColor.normalizeStops(
            p.stops.map { s -> if (s.ref != null) s.copy(color = argb(s.ref), ref = null) else s },
            solidOf(p, 0),
        )
        return when (p.type) {
            YtPaintType.SOLID -> YtPaint.solid(solidOf(p, 0)).copy(enabled = p.enabled)
            else -> p.copy(stops = stops, ref = null)
        }
    }

    /** A colour as the eye sees it on the theme background (for contrast checks). */
    fun opaque(p: YtPaint): Int = YtColor.over(solidOf(p, 0), argb(YtRole.BACKGROUND))

    fun type(name: String): YtTypeToken = tokens.type[name] ?: YtThemeSeeds.fallbackType(name)

    /** The four radii of [c] in dp (a radius token wins over the corner values). */
    fun corners(c: YtCorners): YtCorners {
        val r = c.ref?.let { tokens.radii[it] } ?: return c
        return c.copy(topStart = r, topEnd = r, bottomEnd = r, bottomStart = r)
    }
}
