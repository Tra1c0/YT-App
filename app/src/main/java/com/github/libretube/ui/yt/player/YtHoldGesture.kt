package com.github.libretube.ui.yt.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.view.isVisible
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.UnstableApi
import com.github.libretube.R
import com.github.libretube.constants.PreferenceKeys
import com.github.libretube.extensions.togglePlayPauseState
import com.github.libretube.helpers.PlayerHelper
import com.github.libretube.helpers.PreferenceHelper
import com.github.libretube.ui.views.CustomExoPlayerView
import com.github.libretube.ui.yt.YtHaptics
import com.github.libretube.ui.yt.YtTestHooks
import com.github.libretube.util.PlayingQueue
import java.text.DecimalFormatSymbols

/**
 * YT: hold and drag on the playing video (package C, (d)), in the page and in fullscreen. A long
 * press shrinks the video itself under the finger, with a tile for the action set for each
 * direction around it ([YtHoldTilesView], YtPlayerGesturePrefs); the finger lights a tile past
 * 40 dp and arms it past 88 dp, letting go runs it and the video springs back. Back to the
 * centre or a second finger cancels. Holding still keeps the long press's 2× if that is on. The action runs after the gesture has ended, so a window change (fullscreen,
 * picture-in-picture, a sheet) never meets a finger still down. One per page view.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class YtHoldGesture(
    private val host: YtPlayerHost,
    private val player: CustomExoPlayerView,
    /** Jumps back to the rewind memory's return point; false if there is none. */
    private val returnPoint: () -> Boolean,
    /** The page's explanation loop, for "loop the last 10 s". */
    private val loop: () -> YtLoopController?,
) : CustomExoPlayerView.HoldDrag {
    private val tiles = player.backgroundBinding.ytHoldTiles
    private val handler = Handler(Looper.getMainLooper())
    private val density = player.resources.displayMetrics.density
    private var actions: Map<YtHoldDir, YtHoldAction> = emptyMap()
    private var state = YtHoldState()
    private var holding = false
    private var released = false

    init {
        player.ytHold = this
    }

    fun release() {
        if (released) return
        released = true
        if (player.ytHold === this) player.ytHold = null
        handler.removeCallbacksAndMessages(null)
        tiles.clear()
    }

    override fun begin(x: Float, y: Float, fastForward: Boolean): Boolean {
        if (released || !YtPlayerGesturePrefs.hold || player.playerLocked || !player.useController) return false
        actions = YtHoldDir.entries.associateWith { YtPlayerGesturePrefs.holdAction(it) }
            .filterValues { it != YtHoldAction.NONE }
        if (actions.isEmpty()) return false

        val context = player.context
        val targets = actions.mapValues { (_, action) -> target(context, action) }
        state = YtHoldState()
        holding = true
        tiles.show(
            x, y, player.backgroundBinding.exoContentFrame, targets,
            if (fastForward) context.getString(R.string.yt_r12_c_hold_fast) else null, YtHoldTileStyle.current(context),
        )
        // the controls make room for the tiles, and come back after
        if (player.isControllerFullyVisible) player.hideController()
        YtHaptics.of(tiles).grab()
        YtTestHooks.log(LOG_TAG) { "hold begin x=${x.toInt()} y=${y.toInt()} fast=$fastForward ${tiles.layoutSummary}" }
        return true
    }

    override fun move(dx: Float, dy: Float): Boolean {
        if (!holding) return false
        val next = YtHoldDirection.next(state, dx / density, dy / density)
        // a direction without an action never lights up
        val shown = if (next.dir != null && next.dir !in actions) next.copy(dir = null, stage = YtHoldStage.CENTRE) else next
        val feedback = YtHoldDirection.feedback(state, shown)
        val leftNow = shown.leftCentre && !state.leftCentre
        state = shown
        tiles.move(dx, dy, shown)
        if (leftNow) tiles.centre(null)
        val haptics = YtHaptics.of(tiles)
        when (feedback) {
            YtHoldFeedback.DETENT -> haptics.detent()
            YtHoldFeedback.ARM -> haptics.threshold(true)
            YtHoldFeedback.DISARM -> haptics.threshold(false)
            null -> Unit
        }
        if (feedback != null) YtTestHooks.log(LOG_TAG) { "hold dir=${shown.dir} stage=${shown.stage}" }
        return shown.leftCentre
    }

    override fun end(dx: Float, dy: Float, cancelled: Boolean) {
        if (!holding) return
        holding = false
        val dir = state.dir
        val action = dir?.let { actions[it] }
        if (cancelled || dir == null || action == null || !YtHoldDirection.runs(state)) {
            tiles.dismiss()
            if (state.leftCentre) YtHaptics.of(tiles).release()
            YtTestHooks.log(LOG_TAG) { "hold cancel cancelled=$cancelled stage=${state.stage}" }
            return
        }
        tiles.commit(dir)
        YtHaptics.of(tiles).confirm()
        YtTestHooks.log(LOG_TAG) { "hold run action=${action.value} dir=$dir fullscreen=${player.isFullscreen()}" }
        // after the gesture: the touch stream ends first
        handler.post { if (!released) run(action) }
    }

    private fun target(context: Context, action: YtHoldAction): YtHoldTilesView.Target {
        val (icon, label) = look(context, action)
        val drawable = AppCompatResources.getDrawable(context, icon)?.mutate()
        return YtHoldTilesView.Target(drawable, label)
    }

    // the icon and the name of an action as it stands now (playing or not, fullscreen or not)
    private fun look(context: Context, action: YtHoldAction): Pair<Int, String> {
        val fullscreen = player.isFullscreen()
        val seconds = (PlayerHelper.seekIncrement / 1000).toInt()
        val hasChapters = chapterStarts().isNotEmpty()
        fun s(@StringRes id: Int) = context.getString(id)
        fun pair(@DrawableRes icon: Int, @StringRes label: Int) = icon to s(label)
        return when (action) {
            YtHoldAction.NONE -> pair(R.drawable.ic_close, R.string.yt_r12_c_hold_none)
            YtHoldAction.PLAY_PAUSE ->
                if (host.controller()?.isPlaying == true) pair(R.drawable.ic_pause, R.string.yt_r12_c_hold_pause)
                else pair(R.drawable.ic_play, R.string.yt_r12_c_hold_play)
            YtHoldAction.SEEK_BACK -> R.drawable.ic_rewind_md to context.getString(R.string.yt_r12_c_hold_seconds_back, seconds)
            YtHoldAction.SEEK_FORWARD -> R.drawable.ic_forward_md to context.getString(R.string.yt_r12_c_hold_seconds_forward, seconds)
            YtHoldAction.CHAPTER_PREV ->
                if (hasChapters) pair(R.drawable.ic_prev, R.string.yt_r12_c_hold_chapter_prev)
                else R.drawable.ic_rewind_md to context.getString(R.string.yt_r12_c_hold_seconds_back, seconds)
            YtHoldAction.CHAPTER_NEXT ->
                if (hasChapters) pair(R.drawable.ic_next, R.string.yt_r12_c_hold_chapter_next)
                else R.drawable.ic_forward_md to context.getString(R.string.yt_r12_c_hold_seconds_forward, seconds)
            YtHoldAction.SPEED_UP -> pair(R.drawable.ic_speed, R.string.yt_r12_c_hold_speed_up)
            YtHoldAction.SPEED_DOWN -> pair(R.drawable.ic_speed, R.string.yt_r12_c_hold_speed_down)
            YtHoldAction.SPEED_RESET -> pair(R.drawable.ic_reset, R.string.yt_r12_c_hold_speed_reset)
            YtHoldAction.CAPTIONS -> pair(R.drawable.ic_caption, R.string.yt_r12_c_hold_captions)
            YtHoldAction.ZOOM -> pair(R.drawable.ic_aspect_ratio, R.string.yt_r12_c_hold_zoom)
            YtHoldAction.REPEAT -> pair(R.drawable.ic_repeat, R.string.yt_r12_c_hold_repeat)
            YtHoldAction.SLEEP_TIMER -> pair(R.drawable.ic_sleep, R.string.yt_r12_c_hold_sleep_timer)
            YtHoldAction.LOCK -> pair(R.drawable.ic_locked, R.string.yt_r12_c_hold_lock)
            YtHoldAction.QUEUE -> pair(R.drawable.ic_queue, R.string.yt_r12_c_hold_queue)
            YtHoldAction.CHAPTERS -> pair(R.drawable.ic_list, R.string.yt_r12_c_hold_chapters)
            YtHoldAction.LOOP_LAST -> pair(R.drawable.ic_repeat_one, R.string.yt_r12_c_hold_loop_last)
            YtHoldAction.RETURN_POINT -> pair(R.drawable.ic_restore, R.string.yt_r12_c_hold_return_point)
            YtHoldAction.MINIMIZE ->
                if (fullscreen) pair(R.drawable.ic_fullscreen_exit, R.string.yt_r12_c_hold_fullscreen_exit)
                else pair(R.drawable.ic_arrow_down, R.string.yt_r12_c_hold_minimize)
            YtHoldAction.PIP -> pair(R.drawable.ic_open, R.string.yt_r12_c_hold_pip)
            YtHoldAction.AUDIO -> pair(R.drawable.ic_headphones, R.string.yt_r12_c_hold_audio)
            YtHoldAction.SCREENSHOT -> pair(R.drawable.ic_copy, R.string.yt_r12_c_hold_screenshot)
            YtHoldAction.SHARE -> pair(R.drawable.ic_share, R.string.yt_r12_c_hold_share)
            YtHoldAction.SAVE -> pair(R.drawable.ic_save, R.string.yt_r12_c_hold_save)
            YtHoldAction.COMMENTS -> pair(R.drawable.ic_comment, R.string.yt_r12_c_hold_comments)
            YtHoldAction.FULLSCREEN ->
                if (fullscreen) pair(R.drawable.ic_fullscreen_exit, R.string.yt_r12_c_hold_fullscreen_exit)
                else pair(R.drawable.ic_fullscreen, R.string.yt_r12_c_hold_fullscreen)
        }
    }

    // what an action did: a short word for the tiles view (null: it shows itself), and whether it could not
    private class Outcome(val text: String? = null, val rejected: Boolean = false)

    // runs [action]; what it did shows briefly in the middle where nothing else shows it
    private fun run(action: YtHoldAction) {
        val context = player.context
        val controller = host.controller()
        val page = host.pageBinding()
        val controls = player.binding
        val shown = Outcome()
        fun said(text: String) = Outcome(text)
        fun refused(@StringRes text: Int) = Outcome(context.getString(text), rejected = true)
        val outcome: Outcome = when (action) {
            YtHoldAction.NONE -> shown
            YtHoldAction.PLAY_PAUSE -> {
                val wasPlaying = controller?.isPlaying == true
                controller?.togglePlayPauseState()
                said(context.getString(if (wasPlaying) R.string.yt_r12_c_hold_paused else R.string.yt_r12_c_hold_playing))
            }
            // the double tap's overlay says it
            YtHoldAction.SEEK_BACK -> {
                player.ytSeekStep(false)
                shown
            }
            YtHoldAction.SEEK_FORWARD -> {
                player.ytSeekStep(true)
                shown
            }
            YtHoldAction.CHAPTER_PREV, YtHoldAction.CHAPTER_NEXT -> chapter(action == YtHoldAction.CHAPTER_NEXT)
            YtHoldAction.SPEED_UP, YtHoldAction.SPEED_DOWN, YtHoldAction.SPEED_RESET ->
                speed(action)?.let { said(it) } ?: shown
            YtHoldAction.CAPTIONS -> said(player.ytToggleCaptions())
            YtHoldAction.ZOOM -> said(player.ytToggleZoom())
            YtHoldAction.REPEAT -> {
                val modes = PlayerHelper.repeatModes
                val index = modes.indexOfFirst { it.first == PlayingQueue.repeatMode }
                val next = modes[(index + 1).mod(modes.size)]
                PlayingQueue.repeatMode = next.first
                said(context.getString(R.string.yt_r12_c_hold_repeat_result, context.getString(next.second)))
            }
            YtHoldAction.SLEEP_TIMER -> {
                player.onSleepTimerClicked()
                shown
            }
            YtHoldAction.LOCK -> {
                controls.lockPlayer.performClick()
                shown
            }
            YtHoldAction.QUEUE -> {
                controls.queueToggle.performClick()
                shown
            }
            YtHoldAction.CHAPTERS ->
                if (chapterStarts().isEmpty()) {
                    refused(R.string.yt_r12_c_hold_no_chapters)
                } else {
                    controls.chapterName.performClick()
                    shown
                }
            YtHoldAction.LOOP_LAST -> loopLast()
            YtHoldAction.RETURN_POINT -> if (returnPoint()) shown else refused(R.string.yt_r12_c_hold_no_return)
            YtHoldAction.MINIMIZE -> {
                if (player.isFullscreen()) host.toggleFullscreen() else host.minimize()
                shown
            }
            YtHoldAction.PIP -> {
                val pip = page?.relPlayerPip
                if (pip != null && pip.isVisible) {
                    pip.performClick()
                    shown
                } else {
                    refused(R.string.yt_r12_c_hold_unavailable)
                }
            }
            YtHoldAction.AUDIO -> {
                host.audioMode()
                shown
            }
            YtHoldAction.SCREENSHOT -> {
                page?.relPlayerScreenshot?.performClick()
                shown
            }
            YtHoldAction.SHARE -> {
                host.share(null, direct = true)
                shown
            }
            YtHoldAction.SAVE -> {
                page?.relPlayerSave?.performClick()
                shown
            }
            YtHoldAction.COMMENTS -> {
                page?.commentsToggle?.performClick()
                shown
            }
            YtHoldAction.FULLSCREEN -> {
                host.toggleFullscreen()
                shown
            }
        }
        outcome.text?.let { tiles.result(it) }
        if (outcome.rejected) YtHaptics.of(tiles).reject()
        YtTestHooks.log(LOG_TAG) { "hold done action=${action.value} result=${outcome.text ?: "-"} rejected=${outcome.rejected}" }
    }

    private fun chapterStarts(): LongArray = player.binding.exoProgress.loopChapterStartsMs

    // to the chapter before or after; without chapters the seek step, as the label said
    private fun chapter(next: Boolean): Outcome {
        val controller = host.controller() ?: return Outcome()
        val starts = chapterStarts()
        if (starts.isEmpty()) {
            player.ytSeekStep(next)
            return Outcome()
        }
        val position = controller.currentPosition.coerceAtLeast(0L)
        val target = (if (next) YtHoldDirection.nextChapterMs(position, starts) else YtHoldDirection.previousChapterMs(position, starts))
            ?: return Outcome(player.context.getString(R.string.yt_r12_c_hold_last_chapter), rejected = true)
        controller.seekTo(target)
        YtHaptics.of(tiles).segment()
        val name = player.ytChapterTitle(target)?.takeIf { it.isNotEmpty() }
            ?: YtScrubPrecision.formatTime(target.toDouble(), 0, withHours = controller.duration >= HOUR_MS)
        return Outcome(name)
    }

    // for the moment, as the long press's 2× (the speed setting stays as it was)
    private fun speed(action: YtHoldAction): String? {
        val controller = host.controller() ?: return null
        val now = controller.playbackParameters
        val speed = when (action) {
            YtHoldAction.SPEED_UP -> YtHoldDirection.speedUp(now.speed)
            YtHoldAction.SPEED_DOWN -> YtHoldDirection.speedDown(now.speed)
            else -> PreferenceHelper.getString(PreferenceKeys.PLAYBACK_SPEED, "1").replace("F", "").toFloatOrNull() ?: 1f
        }
        controller.playbackParameters = PlaybackParameters(speed, now.pitch)
        val separator = DecimalFormatSymbols.getInstance(player.resources.configuration.locales[0]).decimalSeparator
        return YtLoopPlan.formatSpeed(speed, separator)
    }

    // the last 10 s as an explanation loop; a running loop ends instead
    private fun loopLast(): Outcome {
        val context = player.context
        val loops = loop() ?: return Outcome(context.getString(R.string.yt_r12_c_hold_unavailable), rejected = true)
        if (loops.shown.active) {
            loops.end()
            return Outcome(context.getString(R.string.yt_r12_c_hold_loop_off))
        }
        if (loops.isLive()) return Outcome(context.getString(R.string.yt_r12_c_loop_live), rejected = true)
        val b = loops.positionMs()
        val a = (b - LOOP_LAST_MS).coerceAtLeast(0L)
        if (b - a < MIN_LOOP_MS) return Outcome(context.getString(R.string.yt_r12_c_loop_too_short), rejected = true)
        loops.start(YtLoop(a, b))
        return Outcome(context.getString(R.string.yt_r12_c_loop_range, loops.time(a), loops.time(b)))
    }

    private companion object {
        const val LOG_TAG = "YtGesture"
        const val LOOP_LAST_MS = 10_000L
        const val MIN_LOOP_MS = 1_000L
        const val HOUR_MS = 3_600_000L
    }
}
