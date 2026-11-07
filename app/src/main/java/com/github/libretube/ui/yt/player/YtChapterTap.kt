package com.github.libretube.ui.yt.player

import com.github.libretube.helpers.PreferenceHelper

/** YT: what a double tap on the left or right of the video does (pref [YtChapterTap.PREF]). */
enum class YtChapterTapMode(val value: String) {
    /** The seek step (±N s), as upstream. */
    OFF("off"),

    /** To the next chapter or back to this one / the one before, when the video has chapters. */
    CHAPTERS("chapters"),

    /** As [CHAPTERS], but only in the outer [YtChapterTap.EDGE_FRACTION] of the width. */
    EDGE("edge"),
    ;

    companion object {
        fun of(value: String?): YtChapterTapMode = entries.firstOrNull { it.value == value } ?: OFF
    }
}

/**
 * YT: the double tap's chapter jump, pure. Right: the start of the next chapter; left: the start
 * of the current chapter, or of the one before within its first 3 s (the hold gesture's rules in
 * [YtHoldDirection]), so a repeated tap walks on through the chapters. Wherever no chapter jump
 * fits (switched off, outside the edge, no chapters, live, in the last chapter) the double tap
 * keeps its seek step.
 */
object YtChapterTap {
    const val PREF = "yt_double_tap_chapter"

    /** In [YtChapterTapMode.EDGE], the share of the width on each side that jumps chapters. */
    const val EDGE_FRACTION = 0.15f

    val mode: YtChapterTapMode get() = YtChapterTapMode.of(PreferenceHelper.getString(PREF, YtChapterTapMode.OFF.value))

    /** Whether a double tap at [fraction] of the width (0 = left edge) jumps chapters in [mode]. */
    fun appliesAt(mode: YtChapterTapMode, fraction: Float): Boolean = when (mode) {
        YtChapterTapMode.OFF -> false
        YtChapterTapMode.CHAPTERS -> true
        YtChapterTapMode.EDGE -> fraction < EDGE_FRACTION || fraction > 1f - EDGE_FRACTION
    }

    /** Where a chapter jump from [positionMs] goes; null without chapters or past the last one. */
    fun targetMs(positionMs: Long, startsMs: LongArray, forward: Boolean): Long? =
        if (forward) YtHoldDirection.nextChapterMs(positionMs, startsMs)
        else YtHoldDirection.previousChapterMs(positionMs, startsMs)

    /** The position the double tap jumps to, or null to keep the seek step. */
    fun decide(
        mode: YtChapterTapMode,
        fraction: Float,
        positionMs: Long,
        startsMs: LongArray,
        forward: Boolean,
        live: Boolean = false,
    ): Long? {
        if (live || startsMs.isEmpty() || !appliesAt(mode, fraction)) return null
        return targetMs(positionMs.coerceAtLeast(0L), startsMs, forward)
    }
}
