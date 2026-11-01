package com.github.libretube.ui.yt.player

import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.ViewGroup
import android.view.accessibility.AccessibilityManager
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.TimeBar
import com.github.libretube.R
import com.github.libretube.ui.views.CustomExoPlayerView
import com.github.libretube.ui.views.DismissableTimeBar
import com.github.libretube.ui.yt.YtHaptics
import com.github.libretube.ui.yt.YtPlaybackClock
import com.github.libretube.ui.yt.YtSeekReason
import com.github.libretube.ui.yt.YtTestHooks
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * YT: the rewind memory on the player page. It hands the seeks (from YtPlaybackClock, with their
 * reasons), the scrubs of the time bar and the passing time to [YtRewindMemory], and shows what
 * that decides: the chip on the video and the ghost of the return point on the time bar. A tap
 * on the chip seeks back exactly, tagged as the chip's own seek. One per page view.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class YtRewindChip(private val host: YtPlayerHost, private val player: CustomExoPlayerView) {
    private val chip = player.backgroundBinding.ytReturnChip
    private val bar = player.binding.exoProgress
    private val handler = Handler(Looper.getMainLooper())
    private var state = YtRewindState()
    private var seeks: Job? = null
    private var preScrubMs = -1L
    private var shownReturnMs = -1L
    private var chipPresence = 0f
    private var released = false

    // longer with a screen reader, as Android recommends for content with controls
    private val showMs: Long = run {
        val am = player.context.getSystemService(AccessibilityManager::class.java)
        when {
            am == null -> YtRewindMemory.SHOW_MS
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> am.getRecommendedTimeoutMillis(
                YtRewindMemory.SHOW_MS.toInt(),
                AccessibilityManager.FLAG_CONTENT_CONTROLS or AccessibilityManager.FLAG_CONTENT_TEXT,
            ).toLong().coerceAtLeast(YtRewindMemory.SHOW_MS)
            am.isTouchExplorationEnabled -> SCREEN_READER_SHOW_MS
            else -> YtRewindMemory.SHOW_MS
        }
    }

    private val tick = Runnable { onTick() }

    private val scrubs = object : TimeBar.OnScrubListener {
        override fun onScrubStart(timeBar: TimeBar, position: Long) {
            // where the video was, not where the finger touched the bar
            preScrubMs = (timeBar as? DismissableTimeBar)?.scrubOriginMs?.takeIf { it >= 0L }
                ?: host.controller()?.currentPosition ?: -1L
            reduce(YtRewindEvent.ScrubStart(now()))
        }

        override fun onScrubMove(timeBar: TimeBar, position: Long) = Unit

        override fun onScrubStop(timeBar: TimeBar, position: Long, canceled: Boolean) {
            val clock = YtPlaybackClock.now()
            reduce(
                YtRewindEvent.ScrubStop(
                    fromMs = preScrubMs,
                    toMs = position,
                    cancelled = canceled || preScrubMs < 0L,
                    durationMs = clock.durationMs,
                    videoId = clock.videoId ?: host.currentVideoId(),
                    live = clock.isLive,
                    nowMs = now(),
                ),
            )
            preScrubMs = -1L
        }
    }

    init {
        bar.addSeekBarListener(scrubs)
        chip.setOnClickListener { onTap() }
        chip.remaining = { state.remaining(now()) }
        chip.onPresence = { presence -> showMarker(presence) }
        val owner = host.fragment().viewLifecycleOwner
        seeks = owner.lifecycleScope.launch {
            YtPlaybackClock.seeks.collect { seek ->
                val clock = YtPlaybackClock.now()
                val cause = when (seek.reason) {
                    YtSeekReason.USER_SCRUB -> YtRewindCause.SCRUB
                    YtSeekReason.CHIP -> YtRewindCause.CHIP
                    YtSeekReason.LOOP -> YtRewindCause.LOOP
                    YtSeekReason.SKIP, YtSeekReason.OTHER -> YtRewindCause.PLAIN
                }
                reduce(
                    YtRewindEvent.Seek(
                        fromMs = seek.fromMs,
                        toMs = seek.toMs,
                        durationMs = clock.durationMs,
                        videoId = clock.videoId ?: host.currentVideoId(),
                        live = clock.isLive,
                        cause = cause,
                        nowMs = now(),
                    ),
                )
            }
        }
    }

    /** Another video plays: what was remembered belonged to the old one. */
    fun onVideo(videoId: String) = reduce(YtRewindEvent.Video(videoId, now()))

    fun release() {
        if (released) return
        released = true
        seeks?.cancel()
        seeks = null
        handler.removeCallbacks(tick)
        bar.removeSeekBarListener(scrubs)
        bar.setReturnMarker(-1L, 0f)
        chip.setOnClickListener(null)
        chip.onPresence = null
        chip.remaining = { 0f }
        chip.hide()
    }

    /** Jumps back to the return point, as a tap on the chip does (hold and drag's action); false if there is none. */
    fun returnNow(): Boolean {
        if (released || !state.hasReturn) return false
        val target = state.returnMs
        reduce(YtRewindEvent.Tap(now()))
        host.seekTagged(target, YtSeekReason.CHIP)
        YtTestHooks.log(LOG_TAG) { "return hold to=$target" }
        return true
    }

    private fun onTap() {
        val now = now()
        if (!state.visible(now)) return
        val target = state.returnMs
        reduce(YtRewindEvent.Tap(now))
        host.seekTagged(target, YtSeekReason.CHIP)
        YtHaptics.of(chip).confirm()
        YtTestHooks.log(LOG_TAG) { "return tap to=$target" }
    }

    private fun onTick() {
        if (released) return
        val controller = host.controller() ?: return render()
        reduce(YtRewindEvent.Tick(controller.currentPosition, host.currentVideoId(), now()))
    }

    private fun reduce(event: YtRewindEvent) {
        if (released) return
        state = YtRewindMemory.reduce(state, event, showMs)
        render()
    }

    // what the state says now, and when to look again
    private fun render() {
        handler.removeCallbacks(tick)
        if (released) return
        val now = now()
        val offered = state.visible(now) && allowed()
        if (offered) {
            if (!chip.isOffered || shownReturnMs != state.returnMs) show(state.returnMs)
        } else if (chip.isOffered) {
            chip.hide()
        }
        // the ghost fades with the chip; once the chip is gone, it is gone too (except while scrubbing)
        if (!chip.isVisible || state.scrubbing) showMarker(if (chip.isVisible) chipPresence else 0f)
        if (state.hasReturn) {
            val wait = if (now < state.showAtMs) (state.showAtMs - now).coerceAtMost(TICK_MS) else TICK_MS
            handler.postDelayed(tick, wait.coerceAtLeast(1L))
        }
    }

    private fun show(returnMs: Long) {
        val duration = YtPlaybackClock.now().durationMs
        val time = YtScrubPrecision.formatTime(returnMs.toDouble(), 0, withHours = duration >= HOUR_MS)
        // below the controls' top bar, clear of the play button, the subtitles and the skip button
        val topBar = player.binding.topBar
        val top = if (topBar.height > 0) topBar.bottom + chipGapPx() else defaultTopPx()
        chip.updateLayoutParams<ViewGroup.MarginLayoutParams> { topMargin = top }
        chip.show(
            time,
            player.context.getString(R.string.yt_r12_c_return_chip_description, time),
            YtPlayerHudStyle.current(player.context),
        )
        shownReturnMs = returnMs
        YtTestHooks.log(LOG_TAG) { "return chip shown return=$returnMs time=$time" }
    }

    private fun showMarker(presence: Float) {
        chipPresence = presence
        val returnMs = if (state.hasReturn) state.returnMs else shownReturnMs
        // while scrubbing, the ghost stays on the bar as a way back
        val alpha = if (state.scrubbing && state.hasReturn) maxOf(presence, SCRUB_MARKER_ALPHA) else presence
        bar.setReturnMarker(if (alpha > 0f) returnMs else -1L, alpha)
    }

    // not on the mini player, in PiP, during the autoplay countdown, locked or while 2x runs
    private fun allowed(): Boolean =
        YtPlayerGesturePrefs.returnChip &&
            player.useController &&
            !player.playerLocked &&
            !player.backgroundBinding.fastForwardView.isVisible &&
            !player.backgroundBinding.autoplayCountdown.isVisible

    private fun chipGapPx(): Int = (CHIP_GAP_DP * player.resources.displayMetrics.density).toInt()

    private fun defaultTopPx(): Int = (DEFAULT_TOP_DP * player.resources.displayMetrics.density).toInt()

    private fun now(): Long = SystemClock.uptimeMillis()

    private companion object {
        const val LOG_TAG = "YtGesture"
        const val TICK_MS = 100L
        const val SCREEN_READER_SHOW_MS = 15_000L
        const val SCRUB_MARKER_ALPHA = 0.6f
        const val HOUR_MS = 3_600_000L
        const val CHIP_GAP_DP = 6f
        const val DEFAULT_TOP_DP = 52f
    }
}
