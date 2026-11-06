package com.github.libretube.helpers

/**
 * YT: lets open activities take a new design, theme mode, accent or animation setting without
 * killing the app. The settings bump [generation]; every activity remembers the generation it
 * was created with and recreates itself when it comes back to the front and finds it stale.
 * Main thread only, like the preference listeners and lifecycle callbacks that use it.
 */
object YtThemeRefresh {
    var generation: Int = 0
        private set

    /** Marks every activity created before now as stale. */
    fun bump() {
        generation++
    }

    /** Whether an activity created at generation [seen] still shows the old look. */
    fun isStale(seen: Int): Boolean = seen != generation
}
