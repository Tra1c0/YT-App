package com.github.libretube.helpers

import androidx.annotation.IdRes
import androidx.annotation.StringRes
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

    /** Color of the played part of the player timeline, or null for the theme color. */
    val timelineColor: Int?
        get() = when (this) {
            STANDARD -> null
            YOUTUBE -> 0xFFFF334B.toInt()
            MOVO -> 0xFFC9F26F.toInt()
            AFTER -> 0xFFFF8B4B.toInt()
            SIDE -> 0xFFFFC68D.toInt()
        }

    /** The label of a bottom navigation destination in this design, or null to keep the default. */
    @StringRes
    fun navigationLabel(@IdRes itemId: Int): Int? = when (this) {
        STANDARD -> null
        YOUTUBE -> when (itemId) {
            R.id.homeFragment -> R.string.yt_nav_home
            R.id.subscriptionsFragment -> R.string.yt_nav_subscriptions
            R.id.libraryFragment -> R.string.yt_nav_you
            else -> null
        }
        MOVO -> when (itemId) {
            R.id.homeFragment -> R.string.yt_nav_discover
            R.id.subscriptionsFragment -> R.string.yt_nav_channels
            R.id.libraryFragment -> R.string.yt_nav_watchlist
            else -> null
        }
        AFTER -> when (itemId) {
            R.id.homeFragment -> R.string.yt_nav_today
            R.id.trendsFragment -> R.string.yt_nav_rooms
            R.id.libraryFragment -> R.string.yt_nav_collection
            else -> null
        }
        SIDE -> when (itemId) {
            R.id.homeFragment -> R.string.yt_nav_start
            R.id.subscriptionsFragment -> R.string.yt_nav_inbox
            R.id.libraryFragment -> R.string.yt_nav_mine
            else -> null
        }
    }

    companion object {
        const val PREF_KEY = "yt_design"

        fun current(): YtDesign {
            val key = PreferenceHelper.getString(PREF_KEY, STANDARD.key)
            return entries.firstOrNull { it.key == key } ?: STANDARD
        }
    }
}
