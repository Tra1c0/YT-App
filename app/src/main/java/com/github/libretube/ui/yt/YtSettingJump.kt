package com.github.libretube.ui.yt

/**
 * YT: the pure part of "jump to a setting" (guide button → settings screen → the row scrolls into
 * view and lights up once). Timeline of the highlight and which row to scroll to; the views live
 * in [YtSettingJumpUi].
 */
object YtSettingJump {
    /** Fragment argument: the preference key to jump to. Removed once read. */
    const val ARG_KEY = "yt_jump_key"

    /** Wait for the screen's enter animation, so the pulse is not half over when it is seen. */
    const val START_DELAY_MS = 260L

    const val FADE_IN_MS = 200L
    const val HOLD_MS = 800L
    const val FADE_OUT_MS = 600L

    /** Motion off: a plain tint, shown and removed without a fade. */
    const val STATIC_MS = 1200L

    fun durationMs(motion: Boolean): Long = if (motion) FADE_IN_MS + HOLD_MS + FADE_OUT_MS else STATIC_MS

    /**
     * The strength (0..1) of the tint [ms] after the start. With [motion] it eases in, holds and
     * eases out; without it is fully on for [STATIC_MS] and then off.
     */
    fun alphaAt(ms: Long, motion: Boolean): Float {
        if (ms < 0) return 0f
        if (!motion) return if (ms < STATIC_MS) 1f else 0f
        return when {
            ms < FADE_IN_MS -> easeOut(ms.toFloat() / FADE_IN_MS)
            ms < FADE_IN_MS + HOLD_MS -> 1f
            ms < FADE_IN_MS + HOLD_MS + FADE_OUT_MS ->
                1f - easeInOut((ms - FADE_IN_MS - HOLD_MS).toFloat() / FADE_OUT_MS)
            else -> 0f
        }
    }

    /** The key to act on: none for a blank one, or when the screen was restored (rotation, back). */
    fun keyToUse(key: String?, restored: Boolean): String? =
        if (restored || key.isNullOrBlank()) null else key

    /**
     * Where to scroll for a preference that is nested in groups. [visible] holds the own
     * visibility of the preference and then of each parent up to (not including) the screen.
     * Returns the index of the deepest entry that is really shown (itself and every parent
     * visible), or null when nothing in the chain is shown. Index 0 means the row itself, which
     * then gets the highlight; a parent is only scrolled to.
     */
    fun scrollTarget(visible: List<Boolean>): Int? {
        var target: Int? = null
        for (i in visible.indices.reversed()) {
            if (!visible[i]) break
            target = i
        }
        return target
    }

    private fun easeOut(t: Float): Float {
        val u = 1f - t.coerceIn(0f, 1f)
        return 1f - u * u * u
    }

    private fun easeInOut(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        return if (x < 0.5f) 4f * x * x * x else 1f - (-2f * x + 2f).let { it * it * it } / 2f
    }
}
