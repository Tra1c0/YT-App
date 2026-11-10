package com.github.libretube.ui.yt.sound

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Choreographer
import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.github.libretube.LibreTubeApp
import com.github.libretube.helpers.YtAudioTap
import com.github.libretube.helpers.YtBattery
import com.github.libretube.helpers.YtSoundFeatures
import com.github.libretube.ui.yt.YtMotionPrefs

/**
 * YT: fills [YtSound.frame] with the sound that is heard right now. Main thread only.
 *
 * Runs only while all hold: the UI listens ([YtSound.reactive]), the app is in front (an
 * activity started; PiP counts), the design animations switch is on, and the service's player
 * plays. Then [YtAudioTap] analyses the sound (sharing the FFT of the floating player's wave)
 * and a Choreographer callback, once per display frame, reads the features of the moment that is
 * heard now (the tap runs ahead by the speaker buffer, [YtAudioTap.leadMs]), shapes them
 * ([YtSoundShaper]) and publishes. When any of it ends, the values fade to SILENT in about 300 ms
 * and the callback stops; with the animations switch off, SILENT is published at once.
 * Power saver: every second display frame only.
 *
 * The player comes from the service ([attach]/[detach], through YtSoundModule); the online and
 * the offline service share it, the newer player wins.
 */
@OptIn(UnstableApi::class)
object YtSoundEngine {
    private const val TAG = "YtSound"

    /** A gap in the analysis (seek, new video) shorter than this keeps the last values. */
    private const val HOLD_NS = 500_000_000L

    /** How often the switches (animations, power saver) are read again while the UI listens. */
    private const val POLL_MS = 1000L

    private val main = Handler(Looper.getMainLooper())
    private val shaper = YtSoundShaper()
    private val features = FloatArray(YtAudioTap.FEATURES)
    private val held = FloatArray(YtAudioTap.FEATURES)

    private var player: ExoPlayer? = null
    private var observing = false
    private var foreground = true
    private var motion = true
    private var powerSave = false

    private var running = false
    private var lastNanos = 0L
    private var skip = false
    private var generation = -1
    private var start: Long? = null
    private var lastAt = Double.NaN
    private var lastFound = 0L
    private var published = YtSoundFrame.SILENT

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) = update()
    }

    /** Started activities (PiP counts: it stays started); the app is in front while > 0. */
    private var started = 0

    private val lifecycle = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityStarted(activity: Activity) {
            started++
            foregroundChanged()
        }

        override fun onActivityResumed(activity: Activity) {
            // the count began while the app ran (registered late): a resumed one is surely started
            if (started == 0) {
                started = 1
                foregroundChanged()
            }
        }

        override fun onActivityStopped(activity: Activity) {
            started = (started - 1).coerceAtLeast(0)
            foregroundChanged()
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
        override fun onActivityPaused(activity: Activity) {}
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
        override fun onActivityDestroyed(activity: Activity) {}
    }

    private fun foregroundChanged() {
        val now = started > 0
        if (now == foreground) return
        foreground = now
        update()
    }

    private val poll = object : Runnable {
        override fun run() {
            if (!YtSound.reactive) return
            readSwitches()
            update()
            main.postDelayed(this, POLL_MS)
        }
    }

    private val callback = Choreographer.FrameCallback { nanos -> frame(nanos) }

    /** The service's player is there (main thread). */
    fun attach(exo: ExoPlayer) {
        player?.let { if (it !== exo) runCatching { it.removeListener(listener) } }
        player = exo
        exo.addListener(listener)
        generation = -1
        update()
    }

    /** The service of [exo] goes away. */
    fun detach(exo: ExoPlayer) {
        if (player !== exo) return
        runCatching { exo.removeListener(listener) }
        player = null
        update()
    }

    /** [YtSound.reactive] changed (any thread). */
    fun reactiveChanged() {
        if (Looper.myLooper() == Looper.getMainLooper()) reactiveChangedOnMain() else main.post { reactiveChangedOnMain() }
    }

    private fun reactiveChangedOnMain() {
        if (!observing) {
            observing = true
            runCatching {
                // registered on first use: the activities started before are not counted, so
                // start from whether the app is visible now (the UI that called is one of them)
                val state = ActivityManager.RunningAppProcessInfo()
                ActivityManager.getMyMemoryState(state)
                started = if (state.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE) 1 else 0
                foreground = started > 0
                LibreTubeApp.instance.registerActivityLifecycleCallbacks(lifecycle)
            }.onFailure { Log.w(TAG, "no activity callbacks", it) }
        }
        main.removeCallbacks(poll)
        if (YtSound.reactive) {
            readSwitches()
            main.postDelayed(poll, POLL_MS)
        }
        update()
    }

    private fun readSwitches() {
        motion = YtMotionPrefs.enabled
        powerSave = runCatching { YtBattery.powerSave(LibreTubeApp.instance) }.getOrDefault(false)
    }

    /** Whether the analysis should run now. */
    private fun wanted(): Boolean {
        val exo = player ?: return false
        return YtSound.reactive && foreground && motion &&
            exo.applicationLooper == Looper.getMainLooper() && exo.isPlaying
    }

    /** Starts, keeps or winds down the analysis after any change. */
    private fun update() {
        val want = wanted()
        YtAudioTap.soundListening = want
        if (!motion || !YtSound.reactive && !running) {
            // the animations switch is off (or nobody listens and nothing fades): static at once
            stop()
            return
        }
        if (want || !shaper.settled) {
            if (!running) {
                running = true
                lastNanos = 0L
                skip = false
                Choreographer.getInstance().postFrameCallback(callback)
            }
        }
    }

    private fun stop() {
        if (running) Choreographer.getInstance().removeFrameCallback(callback)
        running = false
        YtAudioTap.soundListening = false
        shaper.reset()
        lastAt = Double.NaN
        publish(YtSoundFrame.SILENT)
    }

    private fun frame(nanos: Long) {
        if (!running) return
        val want = wanted()
        YtAudioTap.soundListening = want
        if (!motion) {
            stop()
            return
        }
        if (powerSave && want) {
            // power saver: every second display frame (half the work for the UI as well)
            skip = !skip
            if (skip) {
                Choreographer.getInstance().postFrameCallback(callback)
                return
            }
        }
        val dt = if (lastNanos == 0L) 1f / 60f else ((nanos - lastNanos) / 1e9f).coerceIn(0f, YtSoundShaper.MAX_DT)
        lastNanos = nanos
        var onset = 0f
        if (want) {
            val found = read()
            if (found) {
                onset = features[YtSoundFeatures.ONSET].coerceAtLeast(peakSince)
                System.arraycopy(features, 0, held, 0, held.size)
                lastFound = SystemClock.elapsedRealtimeNanos()
            } else if (SystemClock.elapsedRealtimeNanos() - lastFound > HOLD_NS) {
                // a long gap (no sound the tap can read): towards silence
                held.fill(0f)
            }
            // a short gap (the stream just restarted) keeps the last values, without onsets
        } else {
            held.fill(0f)
            lastAt = Double.NaN
        }
        shaper.step(
            dt,
            want,
            held[YtSoundFeatures.LEVEL],
            held[YtSoundFeatures.BASS],
            held[YtSoundFeatures.MID],
            held[YtSoundFeatures.HIGH],
            onset,
        )
        publish(shaper.frame(published))
        if (want || !shaper.settled) {
            Choreographer.getInstance().postFrameCallback(callback)
        } else {
            running = false
            shaper.reset()
            publish(YtSoundFrame.SILENT)
        }
    }

    private var peakSince = 0f

    /** Reads the features of the moment heard now into [features]; false if not known. */
    private fun read(): Boolean {
        peakSince = 0f
        val exo = player ?: return false
        val now = exo.currentPosition
        val speed = exo.playbackParameters.speed
        // the stream restarted (seek, new video, change of speed): its place is found anew
        val current = YtAudioTap.generation
        if (current != generation) {
            generation = current
            start = null
            lastAt = Double.NaN
        }
        val lead = YtAudioTap.leadMs(speed)
        if (start == null) start = YtAudioTap.startOf(current, now, lead)
        var at = start?.let { YtAudioTap.frameAt(now, it) } ?: Double.NaN
        if (!YtAudioTap.soundAt(at, features)) {
            at = YtAudioTap.latestFrame(lead)
            if (!YtAudioTap.soundAt(at, features)) return false
        }
        val from = lastAt
        lastAt = at
        // onsets of the frames passed since the last screen frame (none after a jump back)
        if (!from.isNaN() && at > from) peakSince = YtAudioTap.onsetPeak(from, at)
        return true
    }

    private fun publish(next: YtSoundFrame) {
        if (next === published) return
        published = next
        YtSound.publish(next)
    }
}
