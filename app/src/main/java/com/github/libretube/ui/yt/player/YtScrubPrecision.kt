package com.github.libretube.ui.yt.player

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.roundToLong

/**
 * YT: the levels of the time magnifier ("Zeit unter der Lupe"), coarse to fine. The higher the
 * finger above the time bar, the slower the position follows it; at the top every few dp are
 * one frame of the video.
 */
enum class YtScrubLevel(
    /** The part of the bar's speed the position moves at (not used for [FRAME]). */
    val factor: Double,
    /** The name in the log of the emulator checks. */
    val logName: String,
    /** The mark in the lens and on the ladder ([FRAME] has a word instead, from the strings). */
    val glyph: String,
) {
    FULL(1.0, "1/1", "1×"),
    HALF(0.5, "1/2", "½×"),
    QUARTER(0.25, "1/4", "¼×"),
    EIGHTH(0.125, "1/8", "⅛×"),
    FRAME(0.0, "frame", "");

    /**
     * From here on the video itself shows the position (live seeks), not the storyboard: the
     * finger above the bar would cover the storyboard, and a frozen picture under a moving thumb
     * would read as a fault.
     */
    val livePicture: Boolean get() = ordinal >= HALF.ordinal
}

/** What one move of the finger did, for the haptics and the lens. */
data class YtScrubStep(
    val levelChanged: Boolean = false,
    /** Frames stepped by this move (negative backwards). */
    val frames: Long = 0L,
    /** +1: the position snapped to the start (letting go there changes nothing), -1: left it. */
    val magnet: Int = 0,
    /** +1: the finger went far enough below the bar to cancel, -1: came back. */
    val cancel: Int = 0,
    /** The position ran into the start or the end of the video. */
    val limit: Boolean = false,
    /** The position crossed the start of a chapter. */
    val chapter: Boolean = false,
)

/**
 * YT: the time magnifier's arithmetic for one scrub. The position moves only by the finger's
 * horizontal deltas, scaled by the level its height above the bar picks, so changing level never
 * makes it jump. Distances are px; heights are above the bar's centre (negative below it).
 *
 * @param levelStartsPx where ½, ¼, ⅛ and the frames begin, rising
 * @param hysteresisPx how far past a level line the finger goes before the level changes
 * @param framePx finger travel per frame
 * @param magnetPx how close (as finger travel at the current level) the start pulls the position in
 * @param cancelBelowPx how far below the bar letting go cancels
 * @param levels false: always [YtScrubLevel.FULL] (live streams, the setting off)
 */
class YtScrubPrecision(
    private val levelStartsPx: FloatArray,
    private val hysteresisPx: Float,
    private val framePx: Float,
    private val magnetPx: Float,
    private val cancelBelowPx: Float,
    private val levels: Boolean = true,
) {
    init {
        require(levelStartsPx.size == 4) { "four level lines: ½, ¼, ⅛, frames" }
    }

    var durationMs = 0L
        private set
    var originMs = 0L
        private set
    var frameMs = DEFAULT_FRAME_MS
        private set

    var level = YtScrubLevel.FULL
        private set

    /** Letting go now snaps back to where the video was (nothing changes). */
    var atOrigin = false
        private set

    /** Letting go now cancels (the finger is far below the bar). */
    var cancelling = false
        private set

    private var widthPx = 1f
    private var rawMs = 0.0
    private var frameIndex = 0L
    private var frameBase = 0L
    private var frameRestPx = 0f
    private var atLimit = false
    private var magnetArmed = true
    private var boundariesMs = LongArray(0)

    /** The position the bar shows, in ms (the start while cancelling or at it). */
    val positionMs: Double
        get() = when {
            cancelling || atOrigin -> originMs.toDouble()
            level == YtScrubLevel.FRAME -> frameIndex * frameMs
            else -> rawMs
        }

    /** The frames stepped since the frames began (0 outside of them). */
    val frameOffset: Long get() = if (level == YtScrubLevel.FRAME) frameIndex - frameBase else 0L

    /** How many px of finger travel one ms is at the current level (for the lens's ruler). */
    val pxPerMs: Double
        get() = if (level == YtScrubLevel.FRAME) {
            framePx / frameMs
        } else {
            widthPx / (durationMs.coerceAtLeast(1L) * level.factor)
        }

    /**
     * A scrub begins at [startMs] (where the finger touched the bar); the video was at
     * [originMs]. [widthPx] is the bar's length for the whole [durationMs]; [frameMs] the length
     * of one frame; [chapterStartsMs] the starts of the chapters.
     */
    fun start(
        startMs: Long,
        originMs: Long,
        durationMs: Long,
        widthPx: Float,
        frameMs: Double,
        heightPx: Float,
        chapterStartsMs: LongArray = LongArray(0),
    ) {
        this.durationMs = durationMs.coerceAtLeast(0L)
        this.originMs = originMs.coerceIn(0L, this.durationMs)
        this.widthPx = widthPx.coerceAtLeast(1f)
        this.frameMs = if (frameMs.isFinite() && frameMs >= MIN_FRAME_MS) frameMs else DEFAULT_FRAME_MS
        boundariesMs = chapterStartsMs.filter { it > 0L }.sorted().toLongArray()
        rawMs = startMs.coerceIn(0L, this.durationMs).toDouble()
        level = YtScrubLevel.FULL
        cancelling = false
        atLimit = false
        magnetArmed = true
        frameRestPx = 0f
        // the scrub may begin above the bar already (an upward first move)
        val first = levelFor(heightPx, YtScrubLevel.FULL)
        if (first == YtScrubLevel.FRAME) enterFrames()
        level = first
        atOrigin = inMagnet()
    }

    /** The finger moved by [dxPx] and is now [heightPx] above the bar. */
    fun move(dxPx: Float, heightPx: Float): YtScrubStep {
        val before = positionMs
        val wasOrigin = atOrigin

        // below the bar: letting go there cancels; the position waits where it was
        val wasCancelling = cancelling
        cancelling = if (cancelling) heightPx < -cancelBelowPx + hysteresisPx else heightPx < -cancelBelowPx
        val cancel = when {
            cancelling && !wasCancelling -> 1
            !cancelling && wasCancelling -> -1
            else -> 0
        }
        if (cancelling) {
            return YtScrubStep(cancel = cancel, chapter = crossed(before, positionMs))
        }

        val newLevel = levelFor(heightPx, level)
        val levelChanged = newLevel != level
        if (levelChanged) {
            if (level == YtScrubLevel.FRAME) rawMs = frameIndex * frameMs
            // held at the start: the new level goes on from the start itself, without a jump
            if (atOrigin) rawMs = originMs.toDouble()
            if (newLevel == YtScrubLevel.FRAME) enterFrames()
            level = newLevel
        }

        var frames = 0L
        var limit: Boolean
        if (level == YtScrubLevel.FRAME) {
            frameRestPx += dxPx
            val steps = (frameRestPx / framePx).toLong()
            frameRestPx -= steps * framePx
            val wanted = frameIndex + steps
            val clamped = wanted.coerceIn(0L, maxFrame())
            frames = clamped - frameIndex
            frameIndex = clamped
            limit = wanted != clamped
            if (limit) frameRestPx = 0f
        } else {
            val wanted = rawMs + dxPx / widthPx * durationMs * level.factor
            rawMs = wanted.coerceIn(0.0, durationMs.toDouble())
            limit = wanted != rawMs
            // out of the magnet's hold: on from the start itself, without jumping by its radius;
            // it pulls again only once the position was really away from the start
            val radius = magnetRadiusMs()
            val off = rawMs - originMs
            if (wasOrigin && abs(off) > radius) {
                rawMs = originMs + if (off > 0) off - radius else off + radius
                magnetArmed = false
            }
            if (!magnetArmed && abs(rawMs - originMs) > radius) magnetArmed = true
        }
        val limitHit = limit && !atLimit
        atLimit = limit

        atOrigin = inMagnet()
        val magnet = when {
            atOrigin && !wasOrigin -> 1
            !atOrigin && wasOrigin -> -1
            else -> 0
        }
        return YtScrubStep(
            levelChanged = levelChanged,
            frames = frames,
            magnet = magnet,
            cancel = cancel,
            limit = limitHit,
            chapter = crossed(before, positionMs),
        )
    }

    /**
     * Where to seek when the finger lets go now, or null to change nothing (cancelled, or back
     * at the start). Frames are sought between the frame before and the frame itself, so the
     * frame shows even if the video's timestamps are a little off the grid.
     */
    fun seekTarget(): Long? = when {
        cancelling || atOrigin -> null
        level == YtScrubLevel.FRAME -> frameSeekMs(frameIndex, frameMs).coerceAtMost(durationMs)
        else -> rawMs.roundToLong().coerceIn(0L, durationMs)
    }

    /** Where a live picture seek goes now (the start while cancelling or at it). */
    fun liveTarget(): Long = seekTarget() ?: originMs

    private fun levelFor(heightPx: Float, current: YtScrubLevel): YtScrubLevel {
        if (!levels) return YtScrubLevel.FULL
        var index = current.ordinal
        val half = hysteresisPx / 2f
        // up: past the next line by half the hysteresis; down: below the current line by as much
        while (index < YtScrubLevel.FRAME.ordinal && heightPx >= levelStartsPx[index] + half) index++
        while (index > 0 && heightPx < levelStartsPx[index - 1] - half) index--
        return YtScrubLevel.entries[index]
    }

    private fun enterFrames() {
        frameIndex = (rawMs / frameMs).roundToLong().coerceIn(0L, maxFrame())
        frameBase = frameIndex
        frameRestPx = 0f
    }

    private fun maxFrame(): Long = floor(durationMs / frameMs).toLong().coerceAtLeast(0L)

    private fun magnetRadiusMs(): Double = magnetPx / widthPx * durationMs * level.factor

    private fun inMagnet(): Boolean {
        if (level == YtScrubLevel.FRAME) return frameIndex == (originMs / frameMs).roundToLong()
        return magnetArmed && abs(rawMs - originMs) <= magnetRadiusMs()
    }

    private fun crossed(fromMs: Double, toMs: Double): Boolean {
        if (fromMs == toMs) return false
        val low = minOf(fromMs, toMs)
        val high = maxOf(fromMs, toMs)
        return boundariesMs.any { it > low && it <= high }
    }

    companion object {
        /** 30 fps, when the video does not say. */
        const val DEFAULT_FRAME_MS = 1000.0 / 30.0

        /** Faster than 240 fps is a wrong value, not a video. */
        const val MIN_FRAME_MS = 1000.0 / 240.0

        /** The ms to seek to for frame [index]: half a frame before it, so it is the first one shown. */
        fun frameSeekMs(index: Long, frameMs: Double): Long =
            if (index <= 0L) 0L else floor((index - 0.5) * frameMs).toLong()

        /** The length of one frame for [frameRate] frames per second (null if unknown). */
        fun frameMsFor(frameRate: Float): Double =
            if (frameRate.isFinite() && frameRate >= 1f && frameRate <= 240f) 1000.0 / frameRate else DEFAULT_FRAME_MS

        /**
         * How many decimals of a second the lens shows: as many as the finger can set, so the
         * last digit changes every few px. [msPerPx] is the position's change per px of finger.
         */
        fun decimalsFor(msPerPx: Double): Int {
            if (!msPerPx.isFinite() || msPerPx <= 0.0) return 0
            // a digit worth about two px of finger travel
            return ceil(-log10(msPerPx * 2.0 / 1000.0)).toInt().coerceIn(0, 2)
        }

        /** "1:02:03.4", "12:03", "0:07.25" for [ms] with [decimals] (0 to 3) decimals. */
        fun formatTime(ms: Double, decimals: Int, withHours: Boolean = false): String {
            val d = decimals.coerceIn(0, 3)
            val unit = when (d) {
                0 -> 1000L
                1 -> 100L
                2 -> 10L
                else -> 1L
            }
            // truncated, not rounded: 12:03.99 is still in second 12:03
            val total = floor(ms.coerceAtLeast(0.0) / unit).toLong()
            val perSecond = 1000L / unit
            val seconds = total / perSecond
            val fraction = total % perSecond
            val h = seconds / 3600
            val m = (seconds % 3600) / 60
            val s = seconds % 60
            val clock = if (h > 0 || withHours) {
                "$h:${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}"
            } else {
                "$m:${s.toString().padStart(2, '0')}"
            }
            return if (d == 0) clock else "$clock.${fraction.toString().padStart(d, '0')}"
        }

        /** "+7", "−3", "±0": frames from where the frames began. */
        fun formatFrames(offset: Long): String = when {
            offset > 0L -> "+$offset"
            offset < 0L -> "−${-offset}"
            else -> "±0"
        }

        /** The time steps the lens's ruler can mark, in ms. */
        private val RULER_STEPS = longArrayOf(
            10, 20, 50, 100, 200, 500, 1_000, 2_000, 5_000, 10_000, 15_000, 30_000, 60_000,
            120_000, 300_000, 600_000, 900_000, 1_800_000, 3_600_000,
        )

        /**
         * The ruler's step in ms for [pxPerMs]: the smallest one at least [minPx] apart. In the
         * frames ([frameMs] set) every frame is a step if they are far enough apart.
         */
        fun rulerStepMs(pxPerMs: Double, minPx: Double, frameMs: Double? = null): Double {
            if (!pxPerMs.isFinite() || pxPerMs <= 0.0) return RULER_STEPS.last().toDouble()
            if (frameMs != null && frameMs * pxPerMs >= minPx) return frameMs
            for (step in RULER_STEPS) if (step * pxPerMs >= minPx) return step.toDouble()
            return RULER_STEPS.last().toDouble()
        }
    }
}
