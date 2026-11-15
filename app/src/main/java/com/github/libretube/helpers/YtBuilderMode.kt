package com.github.libretube.helpers

/**
 * YT: the few facts about the active "Eigenes" theme that code outside the theme builder needs
 * before any theme document is loaded (the design enum, the activity theme, the player timeline).
 * The builder writes them whenever the active theme is saved or switched (YtBuilderStore), so
 * reading them never touches the theme files.
 */
object YtBuilderMode {
    const val KEY_LIGHT = "yt_custom_light"
    const val KEY_WORDMARK = "yt_custom_wordmark"
    const val KEY_TIMELINE = "yt_custom_timeline"
    const val KEY_BASE = "yt_custom_base"

    /** Whether the active theme has a light look (it then follows the light/dark choice). */
    val light: Boolean
        get() = runCatching { PreferenceHelper.getBoolean(KEY_LIGHT, false) }.getOrDefault(false)

    /** The wordmark of the toolbar as plain text (HTML special characters are escaped). */
    val wordmark: String
        get() = runCatching { PreferenceHelper.getString(KEY_WORDMARK, "YT") }.getOrDefault("YT")
            .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    /** ARGB of the played part of the player timeline. */
    val timeline: Int
        get() = runCatching { PreferenceHelper.getInt(KEY_TIMELINE, DEFAULT_TIMELINE) }.getOrDefault(DEFAULT_TIMELINE)

    /**
     * The design the active theme started from: the classic screens (settings, dialogs) take its
     * overlay, since a theme cannot be built at run time from arbitrary colours.
     */
    val base: YtDesign
        get() {
            val key = runCatching { PreferenceHelper.getString(KEY_BASE, YtDesign.YOUTUBE.key) }.getOrDefault(YtDesign.YOUTUBE.key)
            return YtDesign.entries.firstOrNull { it.key == key && it != YtDesign.CUSTOM && it != YtDesign.STANDARD } ?: YtDesign.YOUTUBE
        }

    /** The style overlay of the classic screens while "Eigenes" is active. */
    fun classicOverlay(): Int? = base.overlay

    private const val DEFAULT_TIMELINE = 0xFFFF334B.toInt()

    /** Stores the facts of the theme that became active. */
    fun write(light: Boolean, wordmark: String, timeline: Int, base: String) {
        PreferenceHelper.putString(KEY_BASE, base)
        PreferenceHelper.putBoolean(KEY_LIGHT, light)
        PreferenceHelper.putString(KEY_WORDMARK, wordmark.take(40))
        PreferenceHelper.putInt(KEY_TIMELINE, timeline)
    }
}
