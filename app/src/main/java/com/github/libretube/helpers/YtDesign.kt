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
    /** Light designs run the app in the light theme, all others in the dark theme. */
    val light: Boolean = false,
) {
    STANDARD("standard", null, R.style.TextAppearance_YT_Brand, false),
    YOUTUBE("youtube", R.style.YtDesign_YouTube, R.style.TextAppearance_YT_YouTube_Brand, true),
    MOVO("movo", R.style.YtDesign_Movo, R.style.TextAppearance_YT_Movo_Brand, true),
    AFTER("after", R.style.YtDesign_After, R.style.TextAppearance_YT_After_Brand, true),
    SIDE("side", R.style.YtDesign_Side, R.style.TextAppearance_YT_Side_Brand, true),
    CLAY("clay", R.style.YtDesign_Clay, R.style.TextAppearance_YT_YouTube_Brand, true, light = true),
    EDITORIAL("editorial", R.style.YtDesign_Editorial, R.style.TextAppearance_YT_YouTube_Brand, true, light = true),
    GLASS("glass", R.style.YtDesign_Glass, R.style.TextAppearance_YT_YouTube_Brand, true, light = true),
    POP("pop", R.style.YtDesign_Pop, R.style.TextAppearance_YT_YouTube_Brand, true, light = true),
    SHAPES("shapes", R.style.YtDesign_Shapes, R.style.TextAppearance_YT_YouTube_Brand, true),
    FROST("frost", R.style.YtDesign_Frost, R.style.TextAppearance_YT_YouTube_Brand, true),
    KIESEL("kiesel", R.style.YtDesign_Kiesel, R.style.TextAppearance_YT_YouTube_Brand, false, light = true);

    /** Designs with a Shorts tab (in place of the trends tab) and the tabs Home, Shorts, Abos, Du. */
    val hasShortsTab: Boolean
        get() = this == CLAY || this == EDITORIAL || this == GLASS || this == POP || this == SHAPES || this == FROST

    /** The wordmark in the toolbar, as HTML; [accentHex] colors the accented part. */
    fun wordmarkHtml(accentHex: String): String = when (this) {
        STANDARD -> "Y<span style='color:$accentHex';>T</span>"
        YOUTUBE -> "YouTube"
        MOVO -> "movo"
        AFTER -> "after"
        SIDE -> "side<span style='color:$accentHex';>.</span>"
        else -> "YouTube"
    }

    /** Color of the played part of the player timeline, or null for the theme color. */
    val timelineColor: Int?
        get() = when (this) {
            STANDARD -> null
            YOUTUBE -> 0xFFFF334B.toInt()
            MOVO -> 0xFFC9F26F.toInt()
            AFTER -> 0xFFFF8B4B.toInt()
            SIDE -> 0xFFFFC68D.toInt()
            CLAY -> 0xFF9D6A6C.toInt()
            EDITORIAL -> 0xFF0F54F6.toInt()
            GLASS -> 0xFFF03A3A.toInt()
            POP -> 0xFFE0457F.toInt()
            SHAPES -> 0xFFC6F36B.toInt()
            FROST -> 0xFFF08A3C.toInt()
            KIESEL -> 0xFFB4A7F5.toInt()
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
        KIESEL -> when (itemId) {
            R.id.homeFragment -> R.string.yt_nav_home
            R.id.trendsFragment -> R.string.yt_nav_discover
            R.id.subscriptionsFragment -> R.string.yt_nav_subscriptions
            R.id.libraryFragment -> R.string.yt_nav_you
            else -> null
        }
        else -> when (itemId) {
            R.id.homeFragment -> R.string.yt_nav_home
            R.id.trendsFragment -> R.string.yt_nav_shorts
            R.id.subscriptionsFragment -> R.string.yt_nav_subscriptions
            R.id.libraryFragment -> R.string.yt_nav_you
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
