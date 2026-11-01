package com.github.libretube.ui.yt.player

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.os.bundleOf
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.TimeBar
import com.github.libretube.R
import com.github.libretube.services.AbstractPlayerService
import com.github.libretube.services.YtLoopModule
import com.github.libretube.services.YtServiceModules
import com.github.libretube.ui.models.PlayerViewModel
import com.github.libretube.ui.views.CustomExoPlayerView
import com.github.libretube.ui.views.DismissableTimeBar
import com.github.libretube.ui.yt.YtHaptics
import com.github.libretube.ui.yt.YtTestHooks
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference
import java.text.DecimalFormatSymbols

/**
 * YT: the explanation loop on the player page. The service runs the loop (YtLoopModule); this
 * shows it (the band on the time bar, the chip on the video, the entry in the player options)
 * and starts, changes and ends it: from a long press and drag on the time bar, from dragging an
 * end of the band, from the chip and from the sheet (YtLoopSheet). One per page view.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class YtLoopController(private val host: YtPlayerHost, private val player: CustomExoPlayerView) {
    private val chip = player.backgroundBinding.ytLoopChip
    private val bar = player.binding.exoProgress
    private val handler = Handler(Looper.getMainLooper())
    private var statusJob: Job? = null
    private var released = false

    /** The loop of this page's video as last shown; empty without one. */
    var shown = YtLoopStatus()
        private set

    private val refresh = Runnable { render(YtLoopModule.status.value) }

    private val marks = object : DismissableTimeBar.LoopMarks {
        override fun range(): LongArray? = shown.loop?.let { longArrayOf(it.aMs, it.bMs) }

        override fun canMark(): Boolean = !released && !player.playerLocked && host.controller() != null && !isLive()

        override fun onMarkStart(editing: Boolean) = player.ytHoldControls(true)

        override fun onMarked(fixedMs: Long, movingMs: Long, editing: Boolean) {
            player.ytHoldControls(false)
            val loop = YtLoopPlan.edited(shown.loop, fixedMs, movingMs, durationMs(), currentSpeed())
            if (loop == null) {
                YtHaptics.of(chip).reject()
                toast(R.string.yt_r12_c_loop_too_short)
                return
            }
            start(loop)
            YtHaptics.of(chip).confirm()
        }

        override fun onMarkCancelled(editing: Boolean) {
            player.ytHoldControls(false)
            if (!editing) YtHaptics.of(chip).release()
        }
    }

    // a scrub that ends out of the loop goes somewhere else: the loop ends instead of pulling back
    private val scrubs = object : TimeBar.OnScrubListener {
        override fun onScrubStart(timeBar: TimeBar, position: Long) = Unit

        override fun onScrubMove(timeBar: TimeBar, position: Long) = Unit

        override fun onScrubStop(timeBar: TimeBar, position: Long, canceled: Boolean) {
            val loop = shown.loop ?: return
            if (canceled || !YtLoopPlan.leaves(loop, position)) return
            send(YtLoopModule.CLEAR)
            YtTestHooks.log(LOG_TAG) { "scrub out of the loop to=$position" }
        }
    }

    init {
        bar.loopMarks = marks
        bar.addSeekBarListener(scrubs)
        chip.onBody = { onChipBody() }
        chip.onClose = { end() }
        player.ytLoopEntry = { openSheet() }
        player.ytLoopSummary = { summary() }
        active = WeakReference(this)
        statusJob = host.fragment().viewLifecycleOwner.lifecycleScope.launch {
            YtLoopModule.status.collect { render(it) }
        }
    }

    /** Another video plays: an A waiting for its B belonged to the old one. */
    fun onVideo(videoId: String) {
        val model = viewModel() ?: return
        if (model.ytLoop == null) model.ytLoopDraftAMs = -1L
        render(YtLoopModule.status.value)
        YtTestHooks.log(LOG_TAG) { "page video=$videoId" }
    }

    fun release() {
        if (released) return
        released = true
        statusJob?.cancel()
        statusJob = null
        handler.removeCallbacks(refresh)
        if (bar.loopMarks === marks) bar.loopMarks = null
        bar.removeSeekBarListener(scrubs)
        bar.setLoop(-1L, -1L)
        chip.onBody = null
        chip.onClose = null
        chip.pauseLeft = null
        chip.hide()
        player.ytLoopEntry = null
        player.ytLoopSummary = null
        if (active?.get() === this) active = null
    }

    // --- what the sheet asks for

    fun videoId(): String? = host.currentVideoId()

    fun positionMs(): Long = host.controller()?.currentPosition?.coerceAtLeast(0L) ?: 0L

    fun durationMs(): Long = host.controller()?.duration?.takeIf { it > 0L } ?: 0L

    fun isLive(): Boolean = host.controller()?.isCurrentMediaItemLive == true

    fun currentSpeed(): Float = host.controller()?.playbackParameters?.speed ?: 1f

    fun chapterStartsMs(): LongArray = bar.loopChapterStartsMs

    /** An A from the sheet that waits for its B, or -1. */
    var draftAMs: Long
        get() = viewModel()?.ytLoopDraftAMs ?: -1L
        set(value) {
            viewModel()?.ytLoopDraftAMs = value
        }

    /** The loops used on this video before, the newest first. */
    fun saved(): List<YtLoop> = videoId()?.let { YtLoops.get(it) }.orEmpty()

    /** Starts [loop] (or changes the running one to it), and keeps it for this video. */
    fun start(loop: YtLoop) {
        if (isLive()) {
            toast(R.string.yt_r12_c_loop_live)
            return
        }
        val id = videoId()
        send(
            YtLoopModule.SET,
            YtLoopModule.ARG_A to loop.aMs,
            YtLoopModule.ARG_B to loop.bMs,
            YtLoopModule.ARG_SPEED to loop.speed,
            YtLoopModule.ARG_PAUSE to loop.pauseMs,
            YtLoopModule.ARG_VIDEO to id,
        )
        if (id != null) YtLoops.remember(id, loop)
        draftAMs = -1L
    }

    /** Ends the loop. */
    fun end() {
        if (!shown.active) return
        send(YtLoopModule.CLEAR)
        YtHaptics.of(chip).release()
    }

    fun openSheet() {
        if (released) return
        val activity = host.fragment().activity ?: return
        if (activity.supportFragmentManager.isStateSaved) return
        YtLoopSheet().show(activity.supportFragmentManager)
    }

    /** "1:23 – 1:45" for the running loop, "Aus" without one. */
    fun summary(): String {
        val loop = shown.loop ?: return player.context.getString(R.string.yt_r12_c_loop_off)
        return player.context.getString(R.string.yt_r12_c_loop_range, time(loop.aMs), time(loop.bMs))
    }

    fun time(ms: Long): String {
        val withHours = durationMs() >= HOUR_MS
        return YtScrubPrecision.formatTime(ms.toDouble(), 0, withHours)
    }

    fun speedLabel(speed: Float): String =
        YtLoopPlan.formatSpeed(speed, DecimalFormatSymbols.getInstance(player.resources.configuration.locales[0]).decimalSeparator)

    /** [status] if it is the loop of this page's video, else no loop. */
    fun mine(status: YtLoopStatus): YtLoopStatus {
        val id = host.currentVideoId()
        return if (status.active && (status.videoId == null || status.videoId == id)) status else YtLoopStatus()
    }

    // --- showing it

    private fun render(status: YtLoopStatus) {
        handler.removeCallbacks(refresh)
        if (released) return
        val mine = mine(status)
        val before = shown
        shown = mine
        viewModel()?.ytLoop = mine.loop

        val loop = mine.loop
        if (loop == null) bar.setLoop(-1L, -1L) else bar.setLoop(loop.aMs, loop.bMs)

        // a light tick when a pass starts: right at B, or once the pause is over
        if (loop != null && before.loop != null && allowed()) {
            val started = (mine.passes > before.passes && !mine.pausing) || (before.pausing && !mine.pausing)
            if (started) YtHaptics.of(chip).tick()
        }

        if (loop != null && allowed()) showChip(mine, loop) else chip.hide()
        // the countdown's seconds while it thinks
        if (mine.pausing) handler.postDelayed(refresh, PAUSE_REFRESH_MS)
    }

    private fun showChip(status: YtLoopStatus, loop: YtLoop) {
        val context = player.context
        val now = SystemClock.uptimeMillis()
        val text: String
        val description: String
        if (status.pausing) {
            val seconds = YtLoopPlan.secondsLeft(status.pauseEndsAt, now).coerceAtLeast(1)
            text = context.getString(R.string.yt_r12_c_loop_chip_pause, seconds)
            description = context.getString(R.string.yt_r12_c_loop_chip_pause_description, seconds)
            val endsAt = status.pauseEndsAt
            val pauseMs = loop.pauseMs
            chip.pauseLeft = { YtLoopPlan.pauseLeft(endsAt, pauseMs, SystemClock.uptimeMillis()) }
        } else {
            val pass = context.getString(R.string.yt_r12_c_loop_chip, status.pass)
            text = if (loop.speed == 1f) pass else "$pass · ${speedLabel(loop.speed)}"
            description = context.getString(R.string.yt_r12_c_loop_chip_description, time(loop.aMs), time(loop.bMs), status.pass)
            chip.pauseLeft = null
        }
        // below the controls' top bar, as the return chip
        val topBar = player.binding.topBar
        val density = player.resources.displayMetrics.density
        val top = if (topBar.height > 0) topBar.bottom + (CHIP_GAP_DP * density).toInt() else (DEFAULT_TOP_DP * density).toInt()
        chip.updateLayoutParams<ViewGroup.MarginLayoutParams> { topMargin = top }
        chip.show(text, description, context.getString(R.string.yt_r12_c_loop_end), YtPlayerHudStyle.current(context))
    }

    private fun onChipBody() {
        if (shown.pausing) {
            send(YtLoopModule.SKIP_PAUSE)
            YtHaptics.of(chip).tick()
        } else {
            openSheet()
        }
    }

    // not on the mini player, in PiP or while locked
    private fun allowed(): Boolean = player.useController && !player.playerLocked

    private fun send(command: String, vararg args: Pair<String, Any?>) {
        val controller = host.controller() ?: return
        runCatching {
            controller.sendCustomCommand(
                AbstractPlayerService.runPlayerActionCommand,
                bundleOf(YtServiceModules.KEY to command, *args),
            )
        }
    }

    private fun viewModel(): PlayerViewModel? = runCatching {
        ViewModelProvider(host.fragment())[PlayerViewModel::class.java]
    }.getOrNull()

    private fun toast(text: Int) {
        Toast.makeText(player.context, text, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val LOG_TAG = "YtLoop"
        private const val PAUSE_REFRESH_MS = 250L
        private const val HOUR_MS = 3_600_000L
        private const val CHIP_GAP_DP = 6f
        private const val DEFAULT_TOP_DP = 52f

        // the page whose sheet opens (the newest one; a page that goes lets go of it)
        private var active: WeakReference<YtLoopController>? = null

        /** The loop of the page that is open now, for its sheet. */
        fun current(): YtLoopController? = active?.get()?.takeUnless { it.released }
    }
}
