package com.github.libretube.services

import android.os.Bundle
import android.os.Handler
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.github.libretube.LibreTubeApp
import com.github.libretube.helpers.YtBattery
import com.github.libretube.helpers.YtBatteryCapRules
import com.github.libretube.helpers.YtBatteryCapWatch

/**
 * YT: lower video quality on a low battery. For the player's whole life ([onPlayer] to
 * [release]) the battery, the power saver and the yt_battery_* settings are watched
 * ([YtBatteryCapWatch]); below the threshold (not charging), or with the power saver on, the
 * track selector gets a cap on the video height (the setting, 480p by default) and 30 fps.
 * The cap goes 5 % above the threshold, on the charger (unless the power saver keeps it), or
 * with the power saver off. A change while paused applies at once, not only on the next play.
 *
 * The page's resolution request (PlayerCommand.SET_RESOLUTION) passes [resolution] and
 * [maxFrameRate] on its way to the track selector; [resolution] reads the battery first, so the
 * first request of a video is capped already. A resolution the user picked by hand in the
 * player wins (no cap, any frame rate) until the next video, but only for requests of exactly
 * that height ([YtBatteryCapRules.exempt]); "Auto" by hand does not count. Main thread only.
 */
@androidx.annotation.OptIn(UnstableApi::class)
object YtBatteryQualityModule : YtServiceModule {
    /** Extras of SET_RESOLUTION, put there by the page (CustomExoPlayerView.setPlayerResolution). */
    const val EXTRA_BY_USER = "yt_res_by_user"
    const val EXTRA_SHORT = "yt_res_short"

    private var held: ExoPlayer? = null

    // the page's last request, as it came (already widened for shorts)
    private var requested = Int.MAX_VALUE
    private var requestedSet = false
    // the height the user picked by hand for this video (null = none)
    private var picked: Int? = null
    private var short = false

    // true once this module wrote the track selector itself (so lifting the cap writes it back)
    private var touched = false

    private val watch = YtBatteryCapWatch { apply() }

    private val active: Boolean get() = watch.active
    private val exempt: Boolean get() = YtBatteryCapRules.exempt(picked, requested)

    override fun onPlayer(exo: ExoPlayer, handler: Handler) {
        watch.stop()
        held = exo
        requested = Int.MAX_VALUE
        requestedSet = false
        picked = null
        short = false
        touched = false
        val app = runCatching { LibreTubeApp.instance }.getOrNull() ?: return
        watch.start(app)
        apply()
    }

    override fun onNavigate(videoId: String) {
        // a manual pick lasts until the next video
        if (picked == null) return
        picked = null
        apply()
    }

    override fun release(exo: ExoPlayer) {
        if (exo !== held) return
        watch.stop()
        held = null
    }

    /** The height to ask for, given the page's [requested] height and its extras in [args]. */
    fun resolution(requested: Int, args: Bundle): Int {
        // the current battery state, not the last broadcast's (the caller writes the selector)
        watch.refresh()
        this.requested = requested
        requestedSet = true
        picked = YtBatteryCapRules.pickAfter(picked, requested, args.getBoolean(EXTRA_BY_USER, false))
        short = args.getBoolean(EXTRA_SHORT, false)
        return YtBatteryCapRules.height(requested, YtBattery.capHeight, active, exempt, short)
    }

    /** The frame rate to allow along with [resolution]. */
    fun maxFrameRate(): Int = YtBatteryCapRules.maxFrameRate(active, exempt)

    /**
     * The cap on the current video as (height, frame rate), or null while none applies (no low
     * battery, or the user's pick by hand plays). For the quality sheet.
     */
    fun capNow(): Pair<Int, Int>? {
        if (held == null || !active || exempt) return null
        return YtBattery.capHeight to YtBatteryCapRules.CAPPED_FPS
    }

    // writes the current answer into the track selector; untouched as long as there is nothing to change
    private fun apply() {
        val exo = held ?: return
        if (!active && !touched) return
        touched = true
        val height = YtBatteryCapRules.height(requested, YtBattery.capHeight, active, exempt, short)
        val fps = maxFrameRate()
        runCatching {
            val builder = exo.trackSelectionParameters.buildUpon()
            if (requestedSet || active) {
                // as the page's request does it (AbstractPlayerService, SET_RESOLUTION)
                builder.setMinVideoSize(Int.MIN_VALUE, height)
                builder.setMaxVideoSize(Int.MAX_VALUE, height)
            } else {
                builder.clearVideoSizeConstraints()
                builder.setMinVideoSize(0, 0)
            }
            builder.setMaxVideoFrameRate(fps)
            exo.trackSelectionParameters = builder.build()
        }
    }
}
