package com.github.libretube.ui.yt.player

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot

/** YT: the four ways a held finger can go on the playing video. */
enum class YtHoldDir { UP, DOWN, LEFT, RIGHT }

/** How far the held finger went towards its target. */
enum class YtHoldStage {
    /** Near where it was held: letting go does nothing. */
    CENTRE,

    /** A target lights up: it is the one the finger points at. */
    HIGHLIGHT,

    /** Far enough: letting go runs the target's action. */
    ARMED,
}

/** Where a hold and drag is: the target it points at (null in the centre), how far, and whether it ever left the centre. */
data class YtHoldState(
    val dir: YtHoldDir? = null,
    val stage: YtHoldStage = YtHoldStage.CENTRE,
    val leftCentre: Boolean = false,
)

/** What the finger feels when the state changes. */
enum class YtHoldFeedback {
    /** A target lights up, another one takes over, or the finger goes back to the centre. */
    DETENT,

    /** Past the point from which letting go runs it. */
    ARM,

    /** Back behind that point. */
    DISARM,
}

/**
 * YT: the rules of hold and drag on the playing video (package C, (d)): which target the finger
 * points at, when it lights up and when it arms, what the knob of the compass does beyond its
 * ring, and where the chapter jumps go. Everything in dp and ms. Pure, so it is tested on the JVM.
 */
object YtHoldDirection {
    /** From here a target lights up. */
    const val HIGHLIGHT_DP = 40f

    /** From here letting go runs the action. */
    const val ARM_DP = 88f

    /** A lit or armed target stays so until the finger is this much closer again, so it does not flicker. */
    const val HYSTERESIS_DP = 8f

    /** One axis has to lead the other by this much to pick a target; between, the target it had stays. */
    const val DOMINANCE = 1.4f

    /** No hold in fullscreen this close to the left and right edge: there the system's back gesture lives. */
    const val EDGE_DP = 24f

    /** The previous chapter means the start of this one while it played less than this. */
    const val CHAPTER_GRACE_MS = 3_000L

    /** The next chapter has to start at least this much after the position. */
    const val CHAPTER_AHEAD_MS = 500L

    /** The speeds the speed actions step through. */
    val SPEEDS = floatArrayOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f, 4f)

    /** The state after the finger moved to [dxDp], [dyDp] from where it was held (y grows down). */
    fun next(prev: YtHoldState, dxDp: Float, dyDp: Float): YtHoldState {
        val ax = abs(dxDp)
        val ay = abs(dyDp)
        val dominant = when {
            ax == 0f && ay == 0f -> null
            ax >= ay * DOMINANCE -> if (dxDp < 0f) YtHoldDir.LEFT else YtHoldDir.RIGHT
            ay >= ax * DOMINANCE -> if (dyDp < 0f) YtHoldDir.UP else YtHoldDir.DOWN
            else -> null
        }
        // on the diagonal the target it had stays, as long as its axis still leads
        val dir = dominant ?: prev.dir?.takeIf { leads(it, dxDp, dyDp) }
        val along = dir?.let { along(it, dxDp, dyDp) } ?: 0f
        val same = dir != null && dir == prev.dir
        val stage = when {
            dir == null -> YtHoldStage.CENTRE
            along >= ARM_DP -> YtHoldStage.ARMED
            same && prev.stage == YtHoldStage.ARMED && along >= ARM_DP - HYSTERESIS_DP -> YtHoldStage.ARMED
            along >= HIGHLIGHT_DP -> YtHoldStage.HIGHLIGHT
            same && prev.stage != YtHoldStage.CENTRE && along >= HIGHLIGHT_DP - HYSTERESIS_DP -> YtHoldStage.HIGHLIGHT
            else -> YtHoldStage.CENTRE
        }
        val left = prev.leftCentre || hypot(dxDp, dyDp) > HIGHLIGHT_DP
        return YtHoldState(if (stage == YtHoldStage.CENTRE) null else dir, stage, left)
    }

    /** What to feel going from [prev] to [next]; null for nothing. */
    fun feedback(prev: YtHoldState, next: YtHoldState): YtHoldFeedback? {
        if (prev.dir == next.dir && prev.stage == next.stage) return null
        return when {
            next.stage == YtHoldStage.ARMED -> YtHoldFeedback.ARM
            prev.stage == YtHoldStage.ARMED && prev.dir == next.dir -> YtHoldFeedback.DISARM
            else -> YtHoldFeedback.DETENT
        }
    }

    /** Whether letting go in [state] runs its target's action. */
    fun runs(state: YtHoldState): Boolean = state.stage == YtHoldStage.ARMED && state.dir != null

    /**
     * How far the compass's knob is from the centre when the finger is [distance] away: 1:1 up to
     * most of [limit], then it gives less and less and never passes [limit] (a rubber band, with
     * no kink where it starts).
     */
    fun knob(distance: Float, limit: Float): Float {
        if (distance <= 0f || limit <= 0f) return 0f
        val free = limit * FREE_PART
        if (distance <= free) return distance
        val rest = limit - free
        return free + rest * (1f - exp(-(distance - free) / rest))
    }

    /** Whether a hold at [x] of a view [width] wide starts in the edge strip of [edge] (all px). */
    fun inEdge(x: Float, width: Float, edge: Float): Boolean = x < edge || x > width - edge

    /**
     * Where "previous chapter" goes from [positionMs]: the start of this chapter once it played a
     * while, else the start of the one before; null without chapters.
     */
    fun previousChapterMs(positionMs: Long, startsMs: LongArray): Long? {
        if (startsMs.isEmpty()) return null
        val starts = startsMs.sorted()
        val index = starts.indexOfLast { it <= positionMs }
        // before the first chapter: the very start
        if (index < 0) return 0L
        val current = starts[index]
        return if (positionMs - current > CHAPTER_GRACE_MS || index == 0) current else starts[index - 1]
    }

    /** Where "next chapter" goes from [positionMs]; null without chapters or in the last one. */
    fun nextChapterMs(positionMs: Long, startsMs: LongArray): Long? =
        startsMs.sorted().firstOrNull { it > positionMs + CHAPTER_AHEAD_MS }

    /** The next speed up from [speed]; the fastest stays. */
    fun speedUp(speed: Float): Float = SPEEDS.firstOrNull { it > speed + SPEED_EPSILON } ?: SPEEDS.last()

    /** The next speed down from [speed]; the slowest stays. */
    fun speedDown(speed: Float): Float = SPEEDS.lastOrNull { it < speed - SPEED_EPSILON } ?: SPEEDS.first()

    private fun leads(dir: YtHoldDir, dx: Float, dy: Float): Boolean = when (dir) {
        YtHoldDir.UP -> dy < 0f && abs(dy) > abs(dx)
        YtHoldDir.DOWN -> dy > 0f && abs(dy) > abs(dx)
        YtHoldDir.LEFT -> dx < 0f && abs(dx) > abs(dy)
        YtHoldDir.RIGHT -> dx > 0f && abs(dx) > abs(dy)
    }

    private fun along(dir: YtHoldDir, dx: Float, dy: Float): Float = when (dir) {
        YtHoldDir.UP -> -dy
        YtHoldDir.DOWN -> dy
        YtHoldDir.LEFT -> -dx
        YtHoldDir.RIGHT -> dx
    }

    private const val FREE_PART = 0.7f
    private const val SPEED_EPSILON = 0.01f
}

/**
 * YT: what a direction of hold and drag does, as stored in its setting (R.xml.yt_r12_c_prefs,
 * the values of R.array.yt_r12_c_hold_values in this order). Pure, so it is tested on the JVM.
 */
enum class YtHoldAction(val value: String) {
    NONE("none"),
    PLAY_PAUSE("play_pause"),
    SEEK_BACK("seek_back"),
    SEEK_FORWARD("seek_forward"),
    CHAPTER_PREV("chapter_prev"),
    CHAPTER_NEXT("chapter_next"),
    SPEED_UP("speed_up"),
    SPEED_DOWN("speed_down"),
    SPEED_RESET("speed_reset"),
    CAPTIONS("captions"),
    ZOOM("zoom"),
    REPEAT("repeat"),
    SLEEP_TIMER("sleep_timer"),
    LOCK("lock"),
    QUEUE("queue"),
    CHAPTERS("chapters"),
    LOOP_LAST("loop_last"),
    RETURN_POINT("return_point"),
    MINIMIZE("minimize"),
    PIP("pip"),
    AUDIO("audio"),
    SCREENSHOT("screenshot"),
    SHARE("share"),
    SAVE("save"),
    COMMENTS("comments"),
    FULLSCREEN("fullscreen"),
    ;

    companion object {
        /** The stored [value], or the direction's default for anything unknown. */
        fun of(value: String?, dir: YtHoldDir): YtHoldAction =
            entries.firstOrNull { it.value == value } ?: default(dir)

        /** Up enters or leaves fullscreen, down minimizes (leaves fullscreen there), left and right go by chapter. */
        fun default(dir: YtHoldDir): YtHoldAction = when (dir) {
            YtHoldDir.UP -> FULLSCREEN
            YtHoldDir.DOWN -> MINIMIZE
            YtHoldDir.LEFT -> CHAPTER_PREV
            YtHoldDir.RIGHT -> CHAPTER_NEXT
        }
    }
}
