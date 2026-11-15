package com.github.libretube.ui.yt.builder

import androidx.compose.ui.graphics.toArgb
import com.github.libretube.helpers.YtDesignDark
import com.github.libretube.ui.yt.YtContrast
import com.github.libretube.ui.yt.YtPalette
import com.github.libretube.ui.yt.search.YtSearchInks

/**
 * YT: colours that shared screens of "Eigenes" take from its theme, made to read (Pitfall P1):
 * every text colour is lifted until it reaches 4.5:1 on the surface it lies on.
 */
object YtBuilderInks {
    private fun palette(): YtPalette = YtBuilderActive.palette(YtDesignDark.active)

    /** The design search: an almost opaque scrim of the background, the field and chips raised. */
    fun search(): YtSearchInks = search(palette())

    fun search(p: YtPalette): YtSearchInks {
        val back = p.background.toArgb() or 0xFF000000.toInt()
        val scrim = (back and 0x00FFFFFF) or (0xF5 shl 24)
        // the scrim lies over whatever screen is below: text must read over a black and a white one
        val bases = intArrayOf(YtContrast.over(scrim, 0xFF000000.toInt()), YtContrast.over(scrim, 0xFFFFFFFF.toInt()))
        val field = p.raised.toArgb() or 0xFF000000.toInt()
        val text = p.text.toArgb()
        val secondary = p.textSecondary.toArgb()
        return YtSearchInks(
            scrim = scrim,
            panel = 0,
            field = field,
            fieldText = YtContrast.readable(text, field),
            fieldHint = YtContrast.readable(secondary, field),
            rowText = YtContrast.readableOnAll(text, bases),
            rowSecondary = YtContrast.readableOnAll(secondary, bases),
            chip = field,
            chipText = YtContrast.readable(text, field),
            mark = p.accent.toArgb(),
        )
    }

    /** The accent as ARGB (Shorts, the player timeline). */
    fun accent(): Int = palette().accent.toArgb()
}
