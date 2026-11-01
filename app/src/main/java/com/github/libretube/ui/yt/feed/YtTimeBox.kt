package com.github.libretube.ui.yt.feed

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToLong

/** A video the time box may plan, in the order the home ranks it. */
data class YtBoxCandidate(
    val id: String,
    val durationSec: Long,
    /** Where it goes on (continue watching), else 0. */
    val startSec: Long = 0,
    val isLive: Boolean = false,
    val isShort: Boolean = false,
    val isUpcoming: Boolean = false,
) {
    /** What is left of it to watch, in seconds of the video. */
    val remainingSec: Long get() = (durationSec - startSec).coerceAtLeast(0)
}

/**
 * One planned video: from [startSec] to [endSec] (null: to its end). [seconds] is how much of
 * the video that is, at normal speed.
 */
data class YtBoxItem(val id: String, val startSec: Long, val endSec: Long?, val seconds: Long)

/**
 * What [YtTimeBox.plan] gives: the [items] in the order they play, the content [budgetSec] with
 * its [allowanceSec], the candidates left over ([spare], in rank order, for "Noch ein Video") and
 * at most two long ones worth a chapter cut ([cutCandidates]; their chapters still have to be
 * fetched).
 */
data class YtBoxPlan(
    val items: List<YtBoxItem>,
    val budgetSec: Long,
    val allowanceSec: Long,
    val spare: List<YtBoxCandidate>,
    val cutCandidates: List<YtBoxCandidate>,
) {
    val totalSec: Long get() = items.sumOf { it.seconds }

    /** Seconds of the budget still free; negative when the plan runs over a little. */
    val gapSec: Long get() = budgetSec - totalSec
}

/**
 * YT: "Heute habe ich … Minuten", the planner. Pure, so it is tested on the JVM.
 *
 * The budget is the user's minutes times the playback speed (at 1.5x, 30 minutes hold 45 minutes
 * of video). The best-ranked videos go first as long as they fit (greedy), then the gap is
 * packed with the biggest leftovers that still fit (best fit). The plan may run over by at most
 * min(10 %, 3 min), and only when that ends closer to the budget than leaving the gap. A gap of
 * five minutes or more may still take the first part of a long video, cut where a chapter ends
 * ([cut]); nothing is ever cut in the middle of a chapter.
 */
object YtTimeBox {
    /** The minutes offered in the minutes row; "Eigene" lets the user pick others. */
    val MINUTES = listOf(15, 30, 45, 60, 90)

    const val MIN_MINUTES = 5
    const val MAX_MINUTES = 240
    const val STEP_MINUTES = 5

    /** A video with less left than this is not worth a place (a continue item nearly done). */
    const val MIN_ITEM_SEC = 60L

    /** The gap from which a chapter cut is tried. */
    const val CUT_MIN_GAP_SEC = 300L

    /** At most this many long videos get their chapters fetched. */
    const val MAX_CUT_FETCHES = 2

    private const val MAX_OVERSHOOT_SEC = 180L

    /** The budget in seconds of video for [minutes] at [speed]. */
    fun budgetSec(minutes: Int, speed: Float): Long =
        (minutes * 60L * speed.coerceIn(0.25f, 4f)).roundToLong()

    /** How far the plan may run over: min(10 %, 3 min). */
    fun allowanceSec(budgetSec: Long): Long = minOf(budgetSec / 10, MAX_OVERSHOOT_SEC)

    /** Minutes the user can pick with "Eigene": in steps of [STEP_MINUTES], within the bounds. */
    fun clampMinutes(minutes: Int): Int {
        val stepped = ((minutes + STEP_MINUTES / 2) / STEP_MINUTES) * STEP_MINUTES
        return stepped.coerceIn(MIN_MINUTES, MAX_MINUTES)
    }

    /** The longest the plan may take, in real minutes, rounded up (the emulator check reads it). */
    fun capMinutes(budgetSec: Long, speed: Float): Int =
        ceil((budgetSec + allowanceSec(budgetSec)) / speed.coerceIn(0.25f, 4f) / 60.0).toInt()

    /** Whether the time box takes [candidate] at all: a finished, regular video with enough left. */
    fun usable(candidate: YtBoxCandidate): Boolean =
        !candidate.isLive && !candidate.isShort && !candidate.isUpcoming &&
            candidate.durationSec > 0 && candidate.remainingSec >= MIN_ITEM_SEC

    fun plan(candidates: List<YtBoxCandidate>, budgetSec: Long): YtBoxPlan {
        val allowance = allowanceSec(budgetSec)
        val usable = candidates.filter(::usable).distinctBy { it.id }
        val taken = BooleanArray(usable.size)
        var total = 0L

        // greedy: in rank order while it fits
        for ((index, candidate) in usable.withIndex()) {
            if (total + candidate.remainingSec > budgetSec) break
            taken[index] = true
            total += candidate.remainingSec
        }
        // best fit: the biggest leftover that still fits, again and again
        while (true) {
            val gap = budgetSec - total
            var best = -1
            for ((index, candidate) in usable.withIndex()) {
                if (taken[index] || candidate.remainingSec > gap) continue
                if (best < 0 || candidate.remainingSec > usable[best].remainingSec) best = index
            }
            if (best < 0) break
            taken[best] = true
            total += usable[best].remainingSec
        }
        // a little over, if that ends closer to the budget than the gap does
        val gap = budgetSec - total
        if (gap > 0) {
            var best = -1
            for ((index, candidate) in usable.withIndex()) {
                val over = candidate.remainingSec - gap
                if (taken[index] || over <= 0 || over > allowance || over >= gap) continue
                if (best < 0 || candidate.remainingSec < usable[best].remainingSec) best = index
            }
            if (best >= 0) {
                taken[best] = true
                total += usable[best].remainingSec
            }
        }

        val items = usable.filterIndexed { index, _ -> taken[index] }
            .map { YtBoxItem(it.id, it.startSec, null, it.remainingSec) }
        val spare = usable.filterIndexed { index, _ -> !taken[index] }
        val left = budgetSec - total
        val cutCandidates = if (left >= CUT_MIN_GAP_SEC) {
            spare.filter { it.remainingSec > left + allowance }.take(MAX_CUT_FETCHES)
        } else {
            emptyList()
        }
        return YtBoxPlan(items, budgetSec, allowance, spare, cutCandidates)
    }

    /**
     * The first part of [candidate] that fills the gap of [plan], ending where a chapter ends
     * ([chapterStartsSec]: where each chapter starts, in seconds; the next one's start is where
     * a chapter ends). It must fill at least half of the gap and run over by no more than the
     * allowance; the end closest to the gap wins, the earlier one on a tie.
     * @return the cut item, or null if no chapter end fits
     */
    fun cut(plan: YtBoxPlan, candidate: YtBoxCandidate, chapterStartsSec: List<Long>): YtBoxItem? {
        val gap = plan.gapSec
        if (gap < CUT_MIN_GAP_SEC) return null
        var best: Long? = null
        for (end in chapterStartsSec.distinct().sorted()) {
            val seconds = end - candidate.startSec
            if (end >= candidate.durationSec || seconds * 2 < gap || seconds > gap + plan.allowanceSec) continue
            if (best == null || abs(gap - seconds) < abs(gap - (best - candidate.startSec))) best = end
        }
        return best?.let { YtBoxItem(candidate.id, candidate.startSec, it, it - candidate.startSec) }
    }

    /** Of the cuts found for the candidates, the one that fills the gap best (the better-ranked on a tie). */
    fun bestCut(plan: YtBoxPlan, cuts: List<YtBoxItem?>): YtBoxItem? =
        cuts.filterNotNull().minByOrNull { abs(plan.gapSec - it.seconds) }

    /** [plan] with [cut] played last. */
    fun withCut(plan: YtBoxPlan, cut: YtBoxItem): YtBoxPlan =
        plan.copy(items = plan.items + cut, spare = plan.spare.filter { it.id != cut.id }, cutCandidates = emptyList())

    /**
     * "Noch ein Video?": the best-ranked spare video that is not planned yet ([planned], ids) and
     * takes no more than [maxSec] (a short one is kinder at the end of the time); if none is that
     * short, the shortest spare one.
     */
    fun oneMore(spare: List<YtBoxCandidate>, planned: Set<String>, maxSec: Long = 15 * 60L): YtBoxCandidate? {
        val open = spare.filter { it.id !in planned && usable(it) }
        return open.firstOrNull { it.remainingSec <= maxSec } ?: open.minByOrNull { it.remainingSec }
    }
}

/**
 * The running time box as the player and the mini player see it. Time is counted only while a
 * video plays, in real milliseconds (SystemClock.elapsedRealtime, passed in as now): [spentMs]
 * up to the last change, plus the time since [sinceMs] while it plays (-1 while paused).
 */
data class YtSessionState(
    val key: Long,
    val minutes: Int,
    val speed: Float,
    val budgetMs: Long,
    val items: List<YtBoxItem>,
    val spare: List<YtBoxCandidate> = emptyList(),
    val spentMs: Long = 0,
    val sinceMs: Long = -1,
    /** The time is up: autoplay is held and the end card shows. */
    val done: Boolean = false,
    /** The video that stopped at its end point when the time was up, if it did. */
    val stoppedAt: String? = null,
) {
    val lastId: String? get() = items.lastOrNull()?.id

    fun remainingMs(now: Long): Long {
        val running = if (sinceMs >= 0) (now - sinceMs).coerceAtLeast(0) else 0
        return (budgetMs - spentMs - running).coerceAtLeast(0)
    }

    /** What is left, 1 at the start to 0 at the end. */
    fun fraction(now: Long): Float =
        if (budgetMs <= 0) 0f else (remainingMs(now).toFloat() / budgetMs).coerceIn(0f, 1f)

    /** The state once playback started ([playing]) or stopped at [now]. */
    fun accrue(now: Long, playing: Boolean): YtSessionState = when {
        playing && sinceMs < 0 -> copy(sinceMs = now)
        !playing && sinceMs >= 0 -> copy(spentMs = spentMs + (now - sinceMs).coerceAtLeast(0), sinceMs = -1)
        else -> this
    }

    /** Where [id] stops, in ms of the video, or null if it plays to its end. */
    fun endAtMs(id: String?): Long? = items.firstOrNull { it.id == id }?.endSec?.times(1000)

    /**
     * Whether autoplay must wait after [currentId] [ended]: the time is up, or the last planned
     * video is over. A next video asked for while one still plays always goes.
     */
    fun holds(currentId: String?, ended: Boolean, now: Long): Boolean =
        ended && (done || remainingMs(now) <= 0 || (currentId != null && currentId == lastId))

    /**
     * "Noch ein Video": [item] plays after the plan, and the budget grows by its length (from the
     * time already spent, if that ran past the budget).
     */
    fun plusOne(item: YtBoxItem): YtSessionState = copy(
        items = items.filter { it.id != item.id } + item,
        spare = spare.filter { it.id != item.id },
        budgetMs = maxOf(budgetMs, spentMs) + realMs(item.seconds),
        done = false,
        stoppedAt = null,
    )

    /** "Weiterschauen" at an end point: [id] plays to its end ([durationSec]), the budget grows by the rest. */
    fun watchToEnd(id: String, durationSec: Long): YtSessionState {
        val item = items.firstOrNull { it.id == id } ?: return copy(done = false, stoppedAt = null)
        val rest = (durationSec - (item.endSec ?: durationSec)).coerceAtLeast(0)
        return copy(
            items = items.map { if (it.id == id) it.copy(endSec = null, seconds = it.seconds + rest) else it },
            budgetMs = maxOf(budgetMs, spentMs) + realMs(rest),
            done = false,
            stoppedAt = null,
        )
    }

    /** Seconds of video as real milliseconds at this speed. */
    fun realMs(seconds: Long): Long = (seconds * 1000 / speed.coerceIn(0.25f, 4f)).roundToLong()
}
