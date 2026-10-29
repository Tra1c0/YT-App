package com.github.libretube.helpers

import androidx.annotation.StyleRes
import com.github.libretube.R

/**
 * YT: the design switch. Each design (except [STANDARD]) is a complete look with its own colors,
 * type and wordmark, based on the design briefs in docs/DESIGNS.md. Designs are dark only.
 */
enum class YtDesign(
    val key: String,
    @StyleRes val overlay: Int?,
    @StyleRes val brandTextAppearance: Int,
    val labeledNavigation: Boolean,
) {
    STANDARD("standard", null, R.style.TextAppearance_YT_Brand, false),
    YOUTUBE("youtube", R.style.YtDesign_YouTube, R.style.TextAppearance_YT_YouTube_Brand, true),
    MOVO("movo", R.style.YtDesign_Movo, R.style.TextAppearance_YT_Movo_Brand, true),
    AFTER("after", R.style.YtDesign_After, R.style.TextAppearance_YT_After_Brand, true),
    SIDE("side", R.style.YtDesign_Side, R.style.TextAppearance_YT_Side_Brand, true);

    /** The wordmark in the toolbar, as HTML; [accentHex] colors the accented part. */
    fun wordmarkHtml(accentHex: String): String = when (this) {
        STANDARD -> "Y<span style='color:$accentHex';>T</span>"
        YOUTUBE -> "<span style='color:$accentHex';>&#9654;</span> YouTube"
        MOVO -> "movo"
        AFTER -> "after"
        SIDE -> "side<span style='color:$accentHex';>.</span>"
    }

    companion object {
        const val PREF_KEY = "yt_design"

        fun current(): YtDesign {
            val key = PreferenceHelper.getString(PREF_KEY, STANDARD.key)
            return entries.firstOrNull { it.key == key } ?: STANDARD
        }
    }
}
