package com.github.libretube.services

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.PowerManager
import android.util.Log
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.github.libretube.LibreTubeApp
import com.github.libretube.helpers.YtBattery
import com.github.libretube.helpers.YtBatteryCapRules
import com.github.libretube.helpers.YtGuard

/**
 * YT: lower video quality on a low battery. While a video plays, the battery (and the power
 * saver) is watched; below the threshold (not charging), or with the power saver on, the
 * track selector gets a cap on the video height (the setting, 480p by default) and 30 fps.
 * The cap goes 5 % above the threshold, on the charger, or with the power saver off.
 *
 * The page's resolution request (PlayerCommand.SET_RESOLUTION) passes [resolution] and
 * [maxFrameRate] on its way to the track selector. A resolution the user picked by hand in
 * the player wins (no cap, any frame rate) until the next video; "Auto" by hand does not count.
 * Main thread only.
 */
@androidx.annotation.OptIn(UnstableApi::class)
object YtBatteryQualityModule : YtServiceModule {
    /** Extras of SET_RESOLUTION, put there by the page (CustomExoPlayerView.setPlayerResolution). */
    const val EXTRA_BY_USER = "yt_res_by_user"
    const val EXTRA_SHORT = "yt_res_short"

    private var held: ExoPlayer? = null
    private var registered = false
    private var active = false

    // the page's last request, as it came (already widened for shorts)
    private var requested = Int.MAX_VALUE
    private var requestedSet = false
    private var userPicked = false
    private var short = false

    // true once this module wrote the track selector itself (so lifting the cap writes it back)
    private var touched = false

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) register() else unregister()
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val reading = when (intent.action) {
                Intent.ACTION_BATTERY_CHANGED -> YtBattery.read(intent)
                PowerManager.ACTION_POWER_SAVE_MODE_CHANGED -> YtBattery.current(context)
                else -> return
            }
            evaluate(reading, YtBattery.powerSave(context))
        }
    }

    override fun onPlayer(exo: ExoPlayer, handler: Handler) {
        held?.removeListener(listener)
        unregister()
        held = exo
        active = false
        requested = Int.MAX_VALUE
        requestedSet = false
        userPicked = false
        short = false
        touched = false
        exo.addListener(listener)
        if (exo.isPlaying) register()
    }

    override fun onNavigate(videoId: String) {
        // a manual pick lasts until the next video
        if (!userPicked) return
        userPicked = false
        apply()
    }

    override fun release(exo: ExoPlayer) {
        if (exo !== held) return
        exo.removeListener(listener)
        unregister()
        held = null
        active = false
    }

    /** The height to ask for, given the page's [requested] height and its extras in [args]. */
    fun resolution(requested: Int, args: Bundle): Int {
        this.requested = requested
        requestedSet = true
        // a request not made by hand (a fullscreen change re-sends the stored pick) keeps the pick
        if (args.getBoolean(EXTRA_BY_USER, false)) {
            userPicked = YtBatteryCapRules.isManualPick(true, requested)
        }
        short = args.getBoolean(EXTRA_SHORT, false)
        return YtBatteryCapRules.height(requested, YtBattery.capHeight, active, userPicked, short)
    }

    /** The frame rate to allow along with [resolution]. */
    fun maxFrameRate(): Int = YtBatteryCapRules.maxFrameRate(active, userPicked)

    private fun register() {
        if (registered) return
        val app = runCatching { LibreTubeApp.instance }.getOrNull() ?: return
        if (!YtBattery.qualityEnabled && !active) return
        runCatching { YtBattery.register(app, receiver, powerSave = true) }
            .onSuccess { registered = true }
    }

    private fun unregister() {
        if (!registered) return
        registered = false
        val app = runCatching { LibreTubeApp.instance }.getOrNull() ?: return
        YtBattery.unregister(app, receiver)
    }

    private fun evaluate(reading: YtBattery.Reading, powerSave: Boolean) {
        val now = YtBatteryCapRules.active(
            was = active,
            level = reading.level,
            charging = reading.charging,
            powerSave = powerSave,
            enabled = YtBattery.qualityEnabled,
            threshold = YtBattery.threshold,
            usePowerSave = YtBattery.usePowerSave,
        )
        if (now == active) return
        active = now
        if (YtGuard.isEmulator) Log.i("YtBattery", "cap ${if (now) "on" else "off"} at ${reading.level} %")
        apply()
    }

    // writes the current answer into the track selector; untouched as long as there is nothing to change
    private fun apply() {
        val exo = held ?: return
        if (!active && !touched) return
        touched = true
        val height = YtBatteryCapRules.height(requested, YtBattery.capHeight, active, userPicked, short)
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
