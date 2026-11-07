package com.github.libretube.helpers

/**
 * YT: the dark mode of the light designs (Clay, Editorial, Glas, Pop, Kiesel). They keep their
 * layout, shapes, type and motion and only swap to a dark variant of their own palette. The
 * choice is light, dark or the system setting; dark-only designs ignore it.
 * Mode resolution is plain Kotlin, so it can be tested on the JVM.
 */
object YtDesignDark {
    const val PREF_KEY = "yt_design_dark"
    const val LIGHT = "L"
    const val DARK = "D"
    const val SYSTEM = "A"

    /**
     * Whether a light design draws its dark palette right now. Set whenever an activity takes
     * its theme (ThemeHelper.applyOverlays), from the night mode of its configuration.
     */
    @Volatile
    var active: Boolean = false
        private set

    /** The theme mode letter BaseActivity applies for a design: light designs follow [choice]. */
    fun themeMode(designLight: Boolean, choice: String?): String = when {
        !designLight -> DARK
        choice == DARK || choice == SYSTEM -> choice
        else -> LIGHT
    }

    /** Takes the night mode of the activity that is being themed. */
    fun update(designLight: Boolean, night: Boolean) {
        active = designLight && night
    }

    /** The stored choice; light unless the user picked otherwise. */
    fun choice(): String = PreferenceHelper.getString(PREF_KEY, LIGHT)
}
