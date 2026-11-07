package com.github.libretube.ui.yt.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.github.libretube.helpers.YtBattery
import com.github.libretube.helpers.YtBatteryCues
import com.github.libretube.helpers.YtBatteryMarks
import com.github.libretube.ui.yt.YtBatteryVibes
import com.github.libretube.ui.yt.YtMotionPrefs

/**
 * YT: the battery cue on the player page. While the page is visible (full page, fullscreen or
 * the docked mini player), the battery is watched; when it falls to 20, 15, 10 or 5 % the video
 * insets briefly in the level's colour ([YtBatteryCueView]) and the level's vibration plays
 * ([YtBatteryVibes]). Once per fall to a mark ([YtBatteryMarks]).
 *
 * In the background or in picture-in-picture nothing shows and nothing vibrates: a mark passed
 * there is only noted (the first reading after the page comes back is quiet), so the cue never
 * comes late for a level reached long before.
 */
object YtBatteryCueModule : YtPlayerModule {
    private val marks = YtBatteryMarks()

    private var watch: Watch? = null

    override fun onViewCreated(host: YtPlayerHost) {
        watch?.stop()
        val watch = Watch(host)
        this.watch = watch
        host.fragment().viewLifecycleOwner.lifecycle.addObserver(watch)
    }

    override fun onDestroyView(host: YtPlayerHost) {
        val watch = watch ?: return
        if (watch.host !== host) return
        watch.stop()
        host.pageBinding()?.player?.let { player ->
            YtBatteryCueView.find(player)?.stop(restore = true)
        }
        this.watch = null
    }

    private class Watch(val host: YtPlayerHost) : BroadcastReceiver(), LifecycleEventObserver {
        private var registeredWith: Context? = null

        override fun onStateChanged(source: androidx.lifecycle.LifecycleOwner, event: Lifecycle.Event) {
            when (event) {
                Lifecycle.Event.ON_START -> start()
                Lifecycle.Event.ON_STOP -> stop()
                Lifecycle.Event.ON_DESTROY -> {
                    stop()
                    source.lifecycle.removeObserver(this)
                }
                else -> Unit
            }
        }

        fun start() {
            if (registeredWith != null || !YtBattery.cueEnabled) return
            val app = host.fragment().context?.applicationContext ?: return
            runCatching { YtBattery.register(app, this, powerSave = false) }
                .onSuccess { registeredWith = app }
        }

        fun stop() {
            val app = registeredWith ?: return
            registeredWith = null
            YtBattery.unregister(app, this)
            // the next reading after a pause only takes note
            marks.pause()
        }

        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != Intent.ACTION_BATTERY_CHANGED) return
            val reading = YtBattery.read(intent)
            val announce = !isInitialStickyBroadcast && YtBattery.cueEnabled && visible()
            val mark = marks.observe(reading.level, reading.charging, announce) ?: return
            val cue = YtBatteryCues.of(mark) ?: return
            val player = host.pageBinding()?.player ?: return
            YtBatteryCueView.of(player)?.let { (view, frame) ->
                view.play(frame, cue, motion = YtMotionPrefs.enabled)
            }
            YtBatteryVibes.play(context, cue)
        }

        private fun visible(): Boolean {
            val fragment = host.fragment()
            if (!fragment.isAdded || !fragment.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return false
            val activity = fragment.activity ?: return false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && activity.isInPictureInPictureMode) return false
            val player = host.pageBinding()?.player ?: return false
            return player.isShown && player.width > 0 && player.height > 0
        }
    }
}
