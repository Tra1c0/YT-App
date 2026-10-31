package com.github.libretube.ui.yt.player

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * YT: an explanation loop ("Erklärschleife"): the passage from [aMs] to [bMs] plays again and
 * again, at [speed], with a pause of [pauseMs] to think at its end before the next pass.
 */
data class YtLoop(
    val aMs: Long,
    val bMs: Long,
    val speed: Float = 1f,
    val pauseMs: Long = 0L,
) {
    val lengthMs: Long get() = bMs - aMs

    /** Whether [positionMs] is in the loop, give or take [slackMs]. */
    fun covers(positionMs: Long, slackMs: Long = 0L): Boolean =
        positionMs >= aMs - slackMs && positionMs <= bMs + slackMs
}

/**
 * YT: what the service's loop is doing, for the page: the [loop] of [videoId] (null: none),
 * how many passes ended, and while it thinks, when its pause ends ([pauseEndsAt], uptime ms;
 * 0 outside of a pause).
 */
data class YtLoopStatus(
    val loop: YtLoop? = null,
    val videoId: String? = null,
    val passes: Int = 0,
    val pauseEndsAt: Long = 0L,
) {
    val active: Boolean get() = loop != null
    val pausing: Boolean get() = loop != null && pauseEndsAt > 0L

    /** The pass that plays now (or comes after the pause), from 1. */
    val pass: Int get() = passes + 1
}

/** YT: what the loop asks the player for. */
enum class YtLoopAction {
    NONE,

    /** Back to A, playing on. */
    SEEK_A,

    /** Hold at B for the pause (until [YtLoopRun.pauseEndsAt]). */
    PAUSE,

    /** The pause is over: back to A and play. */
    RESUME,
}

/**
 * YT: the passes of one loop, without a player: the service tells it when B is reached (its
 * player message, or its own tick when a jump went past B) and when time passes, and does what
 * it answers. Pure, so it is tested on the JVM.
 */
class YtLoopRun(loop: YtLoop) {
    var loop: YtLoop = loop
        private set

    /** The passes that reached B. */
    var passes: Int = 0
        private set

    /** While it holds at B: when the pause ends (uptime ms); 0 otherwise. */
    var pauseEndsAt: Long = 0L
        private set

    val pausing: Boolean get() = pauseEndsAt > 0L

    private var lastEndAt = Long.MIN_VALUE / 2

    /**
     * New ends or settings for the same run; the passes go on counting. RESUME if it held at B
     * for a pause the new settings no longer have.
     */
    fun update(loop: YtLoop): YtLoopAction {
        this.loop = loop
        return if (loop.pauseMs <= 0L) resume() else YtLoopAction.NONE
    }

    /** Playback reached B at [nowMs]. */
    fun atB(nowMs: Long): YtLoopAction {
        if (pausing) return YtLoopAction.NONE
        // the player message and the tick can both see the same end
        if (nowMs - lastEndAt < YtLoopPlan.SAME_END_MS) return YtLoopAction.NONE
        lastEndAt = nowMs
        passes++
        if (loop.pauseMs <= 0L) return YtLoopAction.SEEK_A
        pauseEndsAt = (nowMs + loop.pauseMs).coerceAtLeast(1L)
        return YtLoopAction.PAUSE
    }

    /** The service's own look every few hundred ms: [positionMs] at [nowMs]. */
    fun tick(positionMs: Long, nowMs: Long): YtLoopAction = when {
        pausing -> if (nowMs >= pauseEndsAt) resume() else YtLoopAction.NONE
        // a jump past B (a SponsorBlock skip, a double tap) that the player message cannot see
        positionMs > loop.bMs + YtLoopPlan.PAST_B_MS -> atB(nowMs)
        else -> YtLoopAction.NONE
    }

    /** The pause ends now: its time is up, the HUD was tapped, or the user pressed play. */
    fun resume(): YtLoopAction {
        if (!pausing) return YtLoopAction.NONE
        pauseEndsAt = 0L
        return YtLoopAction.RESUME
    }
}

/** YT: the rules of the explanation loop: its ends, its choices, how it is kept per video. */
object YtLoopPlan {
    /** Shorter than this is no passage, a slip of the finger. */
    const val MIN_LENGTH_MS = 1_000L

    /** B stays this far from the end, so the video never ends (and autoplays) inside a loop. */
    const val END_MARGIN_MS = 200L

    /** Past B by more than this, the service's tick takes it as the end of a pass. */
    const val PAST_B_MS = 250L

    /** Two ends closer than this are one (the player message and the tick together). */
    const val SAME_END_MS = 400L

    /** A position this close to an end still counts as in the loop. */
    const val SLACK_MS = 500L

    /** Two loops whose ends are both this close are the same passage. */
    const val SAME_PASSAGE_MS = 1_500L

    /** How many loops are kept per video. */
    const val KEEP = 4

    /** The sheet's speeds. */
    val SPEEDS = floatArrayOf(1f, 0.75f, 0.5f)

    /** The sheet's pauses, in ms. */
    val PAUSES_MS = longArrayOf(0L, 2_000L, 4_000L, 8_000L)

    /**
     * The loop between two marks in either order, inside a video of [durationMs] (≤ 0: not
     * known yet, then only the start is clamped); null if it is too short.
     */
    fun of(firstMs: Long, secondMs: Long, durationMs: Long, speed: Float = 1f, pauseMs: Long = 0L): YtLoop? =
        clamp(YtLoop(minOf(firstMs, secondMs), maxOf(firstMs, secondMs), speed, pauseMs), durationMs)

    /** [loop] inside a video of [durationMs] (B kept off the end); null if too short then. */
    fun clamp(loop: YtLoop, durationMs: Long): YtLoop? {
        val a = loop.aMs.coerceAtLeast(0L)
        var b = loop.bMs
        if (durationMs > 0L) b = b.coerceAtMost(durationMs - END_MARGIN_MS)
        if (b - a < MIN_LENGTH_MS) return null
        val speed = if (loop.speed.isFinite() && loop.speed > 0f) loop.speed else 1f
        val clamped = YtLoop(a, b, speed, loop.pauseMs.coerceAtLeast(0L))
        return if (clamped == loop) loop else clamped
    }

    /**
     * The chapter at [positionMs] as a loop: from its start to the next chapter's start (or the
     * end). [chapterStartsMs] in any order; null without chapters or a duration.
     */
    fun chapter(positionMs: Long, chapterStartsMs: LongArray, durationMs: Long, speed: Float = 1f, pauseMs: Long = 0L): YtLoop? {
        if (durationMs <= 0L || chapterStartsMs.isEmpty()) return null
        val starts = chapterStartsMs.filter { it in 0L until durationMs }.distinct().sorted()
        if (starts.isEmpty()) return null
        val index = starts.indexOfLast { it <= positionMs }
        // before the first chapter: the part up to it
        val a = if (index < 0) 0L else starts[index]
        val b = starts.getOrNull(index + 1) ?: durationMs
        return of(a, b, durationMs, speed, pauseMs)
    }

    /**
     * The loop after one of its ends was dragged on the time bar: [fixedMs] stayed, the other
     * end went to [movingMs]. The settings of [current] stay. Null if it got too short.
     */
    fun edited(current: YtLoop?, fixedMs: Long, movingMs: Long, durationMs: Long, speed: Float): YtLoop? =
        of(fixedMs, movingMs, durationMs, current?.speed ?: speed, current?.pauseMs ?: 0L)

    /**
     * Whether a scrub that ended at [positionMs] leaves [loop]: the user wants somewhere else,
     * so the loop ends instead of pulling back.
     */
    fun leaves(loop: YtLoop, positionMs: Long): Boolean = !loop.covers(positionMs, SLACK_MS)

    /** The sheet's speed closest to [speed], so a chip is always selected. */
    fun nearestSpeed(speed: Float): Float = SPEEDS.minByOrNull { abs(it - speed) } ?: 1f

    /** The sheet's pause closest to [pauseMs]. */
    fun nearestPause(pauseMs: Long): Long = PAUSES_MS.minByOrNull { abs(it - pauseMs) } ?: 0L

    /**
     * The loops kept for a video after [loop] was used: it comes first, an older one of the
     * same passage goes, at most [KEEP].
     */
    fun remember(saved: List<YtLoop>, loop: YtLoop): List<YtLoop> =
        (listOf(loop) + saved.filterNot { samePassage(it, loop) }).take(KEEP)

    fun samePassage(one: YtLoop, other: YtLoop): Boolean =
        abs(one.aMs - other.aMs) <= SAME_PASSAGE_MS && abs(one.bMs - other.bMs) <= SAME_PASSAGE_MS

    /** "a:b:speed:pause" per loop, separated by ";". */
    fun encode(loops: List<YtLoop>): String =
        loops.joinToString(";") { "${it.aMs}:${it.bMs}:${(it.speed * 100).roundToInt()}:${it.pauseMs}" }

    /** What [encode] wrote; anything broken is left out. */
    fun decode(text: String?): List<YtLoop> {
        if (text.isNullOrBlank()) return emptyList()
        return text.split(';').mapNotNull { part ->
            val f = part.split(':')
            if (f.size != 4) return@mapNotNull null
            val a = f[0].toLongOrNull() ?: return@mapNotNull null
            val b = f[1].toLongOrNull() ?: return@mapNotNull null
            val speed = f[2].toIntOrNull()?.takeIf { it in 10..400 } ?: return@mapNotNull null
            val pause = f[3].toLongOrNull()?.takeIf { it in 0L..60_000L } ?: return@mapNotNull null
            if (a < 0L || b - a < MIN_LENGTH_MS) return@mapNotNull null
            YtLoop(a, b, speed / 100f, pause)
        }.take(KEEP)
    }

    /** "0.75×" with [decimalSeparator] ("0,75×" in German); "1×" without decimals. */
    fun formatSpeed(speed: Float, decimalSeparator: Char = '.'): String {
        val hundredths = (speed * 100).roundToLong()
        val whole = hundredths / 100
        val rest = hundredths % 100
        val text = when {
            rest == 0L -> "$whole"
            rest % 10 == 0L -> "$whole$decimalSeparator${rest / 10}"
            else -> "$whole$decimalSeparator${rest.toString().padStart(2, '0')}"
        }
        return "$text×"
    }

    /** The whole seconds left of a pause ending at [endsAt], at [nowMs], rounded up. */
    fun secondsLeft(endsAt: Long, nowMs: Long): Int =
        if (endsAt <= nowMs) 0 else ((endsAt - nowMs + 999L) / 1000L).toInt()

    /** How much of a pause of [pauseMs] ending at [endsAt] is left at [nowMs], 1 to 0. */
    fun pauseLeft(endsAt: Long, pauseMs: Long, nowMs: Long): Float {
        if (endsAt <= 0L || pauseMs <= 0L) return 0f
        return ((endsAt - nowMs).toFloat() / pauseMs).coerceIn(0f, 1f)
    }
}
