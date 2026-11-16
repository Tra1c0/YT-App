package com.github.libretube.ui.yt.builder

import kotlin.math.ceil

/**
 * YT: frame durations of the theme editor (pure): a ring of the last [capacity] frames and the
 * figures shown in its overlay and in Diagnose (P50/P90/P95/P99 in ms, janky frames). The durations
 * come from JankStats (YtEditorMetrics); nothing here allocates per frame.
 */
class YtFrameStats(private val capacity: Int = 1200) {
    private val ring = LongArray(capacity)
    private var next = 0
    private var filled = 0

    var frames = 0L
        private set
    var janky = 0L
        private set

    fun add(durationNanos: Long, jank: Boolean) {
        ring[next] = durationNanos
        next = (next + 1) % capacity
        if (filled < capacity) filled++
        frames++
        if (jank) janky++
    }

    fun reset() {
        next = 0
        filled = 0
        frames = 0
        janky = 0
    }

    data class Summary(val p50: Double, val p90: Double, val p95: Double, val p99: Double, val frames: Long, val janky: Long) {
        /** One line, e.g. "P50 8.1 ms · P90 11.0 · P95 13.2 · P99 21.0 · jank 3/1240". */
        fun line(): String = "P50 %.1f ms · P90 %.1f · P95 %.1f · P99 %.1f · jank %d/%d".format(java.util.Locale.ROOT, p50, p90, p95, p99, janky, frames)

        /** GREEN: P95 within the frame budget [budgetMs] (one refresh of the display); AMBER up to 1.5×; RED above. */
        fun rating(budgetMs: Double): Rating = when {
            p95 <= budgetMs -> Rating.GREEN
            p95 <= budgetMs * 1.5 -> Rating.AMBER
            else -> Rating.RED
        }
    }

    enum class Rating { GREEN, AMBER, RED }

    /** Percentiles over the frames in the ring (nearest rank); null before the first frame. */
    fun summary(): Summary? {
        if (filled == 0) return null
        val sorted = ring.copyOf(filled).also { it.sort() }
        fun p(q: Double): Double {
            val rank = ceil(q * filled).toInt().coerceIn(1, filled)
            return sorted[rank - 1] / 1_000_000.0
        }
        return Summary(p(0.5), p(0.9), p(0.95), p(0.99), frames, janky)
    }
}
