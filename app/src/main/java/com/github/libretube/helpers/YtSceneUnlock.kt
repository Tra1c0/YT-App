package com.github.libretube.helpers

/**
 * YT (comments follow the scene): how far the viewer has seen the video, the mark up to which
 * comments are no spoilers. The mark only grows, and only by watching: playback that runs on
 * from what was seen carries it along. No seek moves it, whatever made it (a scrub, the return
 * chip, a loop, a link, a double tap): after a jump forward it follows only once [dwellMs] of
 * the video were played there, so scrubbing past something or a jump by mistake reveals nothing.
 * Jumps the player did not report (the screen was away) count as seeks too.
 *
 * Pure Kotlin (YtSceneUnlockTest). Times in ms; the wall times come from a monotonic clock.
 */
class YtSceneUnlock(private val dwellMs: Long = DWELL_MS) {
    /** Seen up to here. */
    var unlockedMs = 0L
        private set

    private var lastPositionMs = 0L

    // -1 before the first reading
    private var lastWallMs = -1L

    // played since a jump forward, -1 while none waits
    private var dwellPlayedMs = -1L

    /** True while a jump forward waits for [dwellMs] of playback. */
    val waiting: Boolean get() = dwellPlayedMs >= 0

    /** A new video, from its start: nothing is seen yet. */
    fun reset() {
        unlockedMs = 0L
        lastPositionMs = 0L
        lastWallMs = -1L
        dwellPlayedMs = -1L
    }

    /** Watching goes on from [positionMs] after a while without readings; up to there counts as seen. */
    fun restart(positionMs: Long, wallMs: Long) {
        unlockedMs = maxOf(unlockedMs, positionMs)
        lastPositionMs = positionMs
        lastWallMs = wallMs
        dwellPlayedMs = -1L
    }

    /** Seen up to [ms] at least (where the viewer stopped last time). True if the mark moved. */
    fun raiseTo(ms: Long): Boolean {
        if (ms <= unlockedMs) return false
        unlockedMs = ms
        if (lastPositionMs <= unlockedMs + SLACK_MS) dwellPlayedMs = -1L
        return true
    }

    /** A reading of the player. True if the mark moved. */
    fun tick(positionMs: Long, playing: Boolean, speed: Float, wallMs: Long): Boolean {
        val delta = positionMs - lastPositionMs
        val elapsed = if (lastWallMs < 0) 0L else (wallMs - lastWallMs).coerceAtLeast(0L)
        lastPositionMs = positionMs
        lastWallMs = wallMs
        val expected = (elapsed * speed.coerceIn(MIN_SPEED, MAX_SPEED)).toLong() + SLACK_MS
        val before = unlockedMs
        when {
            // a jump: forward into what was not seen, it waits as a seek does
            delta < -SLACK_MS || delta > expected -> waitAt(positionMs)
            !playing || delta <= 0 -> Unit
            dwellPlayedMs >= 0 -> {
                dwellPlayedMs += delta
                if (dwellPlayedMs >= dwellMs) {
                    dwellPlayedMs = -1L
                    unlockedMs = maxOf(unlockedMs, positionMs)
                }
            }
            else -> unlockedMs = maxOf(unlockedMs, positionMs)
        }
        return unlockedMs != before
    }

    /** The player seeked to [toMs], for whatever reason: the mark stays. */
    fun seek(toMs: Long, wallMs: Long) {
        lastPositionMs = toMs
        lastWallMs = wallMs
        waitAt(toMs)
    }

    private fun waitAt(positionMs: Long) {
        // back into what was seen, playback carries the mark again once it passes it
        dwellPlayedMs = if (positionMs > unlockedMs + SLACK_MS) 0L else -1L
    }

    companion object {
        /** Playback needed at a spot reached by a jump before it counts as seen. */
        const val DWELL_MS = 5_000L

        // what the position may differ from the time passed between two readings and still be
        // playback: the readings come every 250 ms, not exactly
        const val SLACK_MS = 750L

        private const val MIN_SPEED = 0.1f
        private const val MAX_SPEED = 16f
    }
}
