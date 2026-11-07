package com.github.libretube.helpers

import com.github.libretube.constants.PreferenceKeys

/**
 * YT: which appearance options have an effect under a design. Every design other than the
 * standard one fixes the theme mode (BaseActivity.attachBaseContext) and draws over accent and
 * pure theme with its own palette (ThemeHelper.applyOverlays), so offering those choices there
 * only led to a restart that changed nothing. The light designs offer their own light, dark or
 * system choice instead (YtDesignDark). Plain Kotlin, so it can be tested on the JVM.
 */
object YtAppearanceOptions {
    /** Options that only the standard design reads. */
    val STANDARD_ONLY: Set<String> = setOf(
        PreferenceKeys.THEME_MODE,
        PreferenceKeys.PURE_THEME,
        PreferenceKeys.ACCENT_COLOR,
    )

    /**
     * Whether the option with [key] does something while the design [designKey] is selected.
     * [designLight]: the design is one of the light ones, which offer light, dark or system.
     */
    fun applies(key: String, designKey: String, designLight: Boolean = false): Boolean = when (key) {
        DESIGN_DARK -> designLight
        else -> designKey == STANDARD_KEY || key !in STANDARD_ONLY
    }

    /**
     * Accent values that can be chosen: "my" (Material You) needs dynamic colors, which only
     * some phones have.
     */
    fun accentValues(values: List<String>, dynamicColors: Boolean): List<String> =
        if (dynamicColors) values else values.filterNot { it == MATERIAL_YOU }

    /** True when picking [newValue] would change nothing, so no recreate is needed. */
    fun isNoChange(oldValue: Any?, newValue: Any?): Boolean = oldValue == newValue

    /** Same as YtDesign.STANDARD.key; kept here as a literal so this file stays free of R. */
    const val STANDARD_KEY = "standard"
    const val MATERIAL_YOU = "my"

    /** Same as YtDesignDark.PREF_KEY. */
    const val DESIGN_DARK = "yt_design_dark"
}
