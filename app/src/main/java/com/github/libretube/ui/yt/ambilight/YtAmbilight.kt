package com.github.libretube.ui.yt.ambilight

import android.animation.ValueAnimator
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.View
import com.github.libretube.helpers.PreferenceHelper
import com.github.libretube.helpers.YtBattery
import com.github.libretube.helpers.YtDesign
import com.github.libretube.ui.yt.FrequencyPulse
import com.github.libretube.ui.yt.YtContrast
import com.github.libretube.ui.yt.YtMotionPrefs
import com.github.libretube.ui.yt.mini.YtBleedMath
import com.github.libretube.ui.yt.sound.YtSound
import com.github.libretube.ui.yt.sound.YtSwingGuard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.roundToInt
import com.github.libretube.ui.yt.ambilight.YtAmbilightZones as Z

/**
 * YT: the ambilight. While the video page, the docked mini player or fullscreen is on screen and
 * the video plays, a tiny copy of its frame is taken a few times a second (YtAmbilightSampler,
 * off the main thread), its edge zones become colours (YtAmbilightMath), and these glide on the
 * main thread towards each new sample. The painters (YtAmbilightPaint, from the page's scroll
 * view, the player's bars and the activity's layer) read [pageColor], [barColor], their caps
 * and [presence] while drawing; the listeners only invalidate their own view, so no screen is
 * recomposed or laid out for it.
 *
 * - Rate: YtAmbilightRules (4–12 Hz by how busy the picture is; lower docked, with animations
 *   off, when warm, when a sample costs much; none in power saver, paused: one sample).
 * - Fallback: when PixelCopy fails (protected video, emulator), the storyboard still of the
 *   position or the thumbnail (YtAmbilightFallback), tried again with PixelCopy now and then.
 * - Animations off: no glide and no pulse, the colour changes per scene (at cuts) only.
 * - Synthwave and Frequency: the glow's brightness follows the sound (bass and level, through a
 *   flash guard: at most 3 swings a second), subtly.
 * Main thread only.
 */
object YtAmbilight {
    /** Where the colours come from. */
    enum class Source(val label: String) {
        NONE("none"),
        PIXEL_COPY("PixelCopy"),
        STORYBOARD("storyboard"),
        THUMBNAIL("thumbnail"),
    }

    /** The video page, as far as the ambilight needs it (the page module's glue). */
    interface Page {
        fun context(): Context?
        fun surfaceView(): SurfaceView?
        fun place(): YtAmbilightPlace
        fun barsShown(): Boolean

        /** The playback position now, ms (for the storyboard fallback). */
        fun positionMs(): Long
    }

    /** A view that shows the glow: told after every change, it invalidates itself if it shows it. */
    fun interface Listener {
        fun onAmbilight()
    }

    var settings = YtAmbilightSettings()
        private set
    var profile = YtAmbilightProfile.CLASSIC
        private set

    /** The glow colour of each zone ([Z.DISPLAY]) on the page's and screens' background. */
    val pageColor = IntArray(Z.DISPLAY)

    /** The most alpha a glow layer of that zone may have there (contrast guard, 3 layers). */
    val pageCap = FloatArray(Z.DISPLAY)

    /** The same for the player's black bars (white controls over them). */
    val barColor = IntArray(Z.DISPLAY)
    val barCap = FloatArray(Z.DISPLAY)

    /** How present each zone's glow is (a dark zone glows little), 0..1. */
    val presence = FloatArray(Z.DISPLAY)

    /** The brightness lift of the beat (1 = none). */
    var beatGain = 1f
        private set

    /** True while there are colours to draw. */
    var showing = false
        private set

    /** Where the player was at the last check. */
    var place = YtAmbilightPlace.HIDDEN
        private set

    /** The picture's area inside the last copy (letterbox), and the copy's size. */
    private val area = IntArray(4)
    private val copySize = IntArray(2)

    private var page: Page? = null
    private val listeners = ArrayList<Listener>(4)
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val sampler = YtAmbilightSampler(main) { onCopied() }
    private val fallback = YtAmbilightFallback()
    private var fallbackJob: Job? = null

    private val target = FloatArray(Z.DISPLAY * Z.CHANNELS)
    private val incoming = FloatArray(Z.COUNT * Z.CHANNELS)
    private val smoother = YtAmbilightSmoother()
    private val scene = YtAmbilightScene()
    private val lastCapColor = IntArray(Z.DISPLAY * 2)
    private val scratch = FloatArray(3)

    // ink of the page for the contrast guard
    private var back = BLACK
    private var text = WHITE
    private var text2 = WHITE
    private var lightBack = false

    private var started = false
    private var playing = false
    private var needFrame = true
    private var motion = true
    private var powerSave = false
    private var thermal = 0
    private var deviceReadAt = 0L

    private var source = Source.NONE
    private var failures = 0
    private var noData = 0
    private var fallbackActive = false
    private var fallbackSince = 0L
    private var lastFailure: String? = null
    private var lastFailureAt = 0L
    private var rateHz = 0f
    private var tickMs = 0f
    private var accepted = 0

    private var looping = false
    private var ticking = false
    private var lastTickAt = 0L
    private var beating = false
    private var holding = false
    private var beat = 0f
    private val guard = YtSwingGuard()

    private var prefsWatched = false
    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == null || key.startsWith(YtAmbilightPrefs.PREFIX) || key == YtMotionPrefs.KEY) reload()
    }

    // -- the page ---------------------------------------------------------------------------------

    /**
     * The video page is there. [back], [text], [text2]: its background and text colours (the
     * design's palette, or the classic theme), for the contrast guard.
     */
    fun attachPage(page: Page, back: Int, text: Int, text2: Int) {
        this.page = page
        watchPrefs()
        profile = YtAmbilightProfile.of(YtDesign.current())
        settings = YtAmbilightPrefs.read()
        setInk(back, text, text2)
        resetSource()
        needFrame = true
        kick()
    }

    fun detachPage(page: Page) {
        if (this.page !== page) return
        this.page = null
        started = false
        stopLoop()
        fallbackJob?.cancel()
        fallbackJob = null
        clear()
    }

    /** The page started (visible) or stopped (the app in the background). */
    fun pageStarted(page: Page, on: Boolean) {
        if (this.page !== page) return
        started = on
        if (on) {
            needFrame = true
            kick()
        } else {
            stopLoop()
            updateBeat(false)
        }
    }

    /** The player plays or not (a pause shows the frame it stopped on). */
    fun playback(page: Page, playing: Boolean) {
        if (this.page !== page || playing == this.playing) return
        this.playing = playing
        needFrame = true
        kick()
    }

    /** The player jumped (a seek): while paused, the new frame is shown. */
    fun seeked(page: Page) {
        if (this.page !== page) return
        needFrame = true
        if (!playing) kick()
    }

    /** A new video: its stills for the fallback; the next sample counts as a scene cut. */
    fun video(page: Page, previews: List<com.github.libretube.api.obj.PreviewFrames>, thumbnailUrl: String?) {
        if (this.page !== page) return
        fallback.video(previews, thumbnailUrl)
        resetSource()
        needFrame = true
        kick()
    }

    fun addListener(listener: Listener) {
        if (listener !in listeners) listeners.add(listener)
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    private fun setInk(back: Int, text: Int, text2: Int) {
        this.back = back or OPAQUE
        this.text = text
        this.text2 = text2
        lightBack = YtContrast.luminance(this.back) > 0.4
        lastCapColor.fill(0)
    }

    private fun resetSource() {
        scene.reset()
        failures = 0
        noData = 0
        fallbackActive = false
        source = Source.NONE
    }

    private fun watchPrefs() {
        if (prefsWatched) return
        runCatching { PreferenceHelper.settings.registerOnSharedPreferenceChangeListener(prefListener) }
            .onSuccess { prefsWatched = true }
    }

    private fun reload() {
        settings = YtAmbilightPrefs.read()
        if (!settings.enabled) {
            clear()
        } else {
            lastCapColor.fill(0)
            needFrame = true
            // a new strength or switch shows at once, also while paused
            if (smoother.primed) publish()
        }
        kick()
    }

    // -- sampling ---------------------------------------------------------------------------------

    private val loop = Runnable { step() }

    private fun kick() {
        main.removeCallbacks(loop)
        looping = false
        if (page == null || !started || !settings.enabled) return
        looping = true
        main.post(loop)
    }

    private fun stopLoop() {
        main.removeCallbacks(loop)
        looping = false
    }

    private fun step() {
        looping = false
        val page = page ?: return
        if (!started || !settings.enabled) {
            updateBeat(false)
            return
        }
        readDevice(page)
        motion = YtMotionPrefs.enabled && ValueAnimator.areAnimatorsEnabled()
        place = page.place()
        val shows = YtAmbilightRules.shows(profile, settings, place, page.barsShown())
        val hz = if (shows) {
            YtAmbilightRules.rateHz(playing, place, motion, powerSave, thermal, scene.activity, sampler.copyMs + sampler.cpuMs)
        } else {
            0f
        }
        val interval = YtAmbilightRules.intervalOf(hz)
        rateHz = hz
        if (shows && (interval > 0L || needFrame)) {
            if (sample(page)) needFrame = false
        }
        updateBeat(shows && YtAmbilightRules.beats(profile, settings, motion, playing))
        val wait = when {
            !shows || interval <= 0L -> YtAmbilightRules.IDLE_MS
            fallbackActive -> max(interval, YtAmbilightRules.FALLBACK_MS)
            else -> interval
        }
        looping = true
        main.postDelayed(loop, wait)
    }

    /** Takes one sample; false if none could be asked for now (tried again at the next step). */
    private fun sample(page: Page): Boolean {
        val now = SystemClock.uptimeMillis()
        if (fallbackActive && now - fallbackSince < YtAmbilightRules.RETRY_MS) return sampleFallback(page)
        val view = page.surfaceView() ?: return false
        if (!view.isShown || view.windowVisibility != View.VISIBLE || view.width <= 0 || view.height <= 0) return false
        val surface = view.holder?.surface?.takeIf { it.isValid } ?: return false
        // the copy's size, as the Glas bleed's (48 px on the long side), without a Pair per sample
        val aspect = (view.width.toFloat() / view.height).coerceIn(0.4f, 4f)
        val long = YtBleedMath.LONG_SIDE
        val w = if (aspect >= 1f) long else (long * aspect).roundToInt().coerceAtLeast(4)
        val h = if (aspect >= 1f) (long / aspect).roundToInt().coerceAtLeast(4) else long
        return sampler.request(surface, w, h)
    }

    private fun onCopied() {
        val code = sampler.lastCode
        if (code == PixelCopy.SUCCESS) {
            failures = 0
            noData = 0
            fallbackActive = false
            source = Source.PIXEL_COPY
            sampler.take(incoming, area, copySize)
            accept(incoming)
            return
        }
        if (code == PixelCopy.ERROR_SOURCE_NO_DATA) noData++ else failures++
        lastFailure = codeName(code)
        lastFailureAt = SystemClock.elapsedRealtime()
        if (YtAmbilightRules.useFallback(failures, noData)) {
            fallbackActive = true
            fallbackSince = SystemClock.uptimeMillis()
            failures = 0
            noData = 0
            needFrame = true
        }
    }

    private fun sampleFallback(page: Page): Boolean {
        if (fallbackJob?.isActive == true) return false
        val at = page.positionMs()
        if (fallback.same(at) && source != Source.NONE && smoother.primed) return true
        val context = page.context()?.applicationContext ?: return false
        fallbackJob = scope.launch {
            val out = FloatArray(Z.COUNT * Z.CHANNELS)
            val kind = fallback.sample(context, at, out)
            if (this@YtAmbilight.page !== page) return@launch
            if (kind == null) {
                source = Source.NONE
                return@launch
            }
            source = if (kind == YtAmbilightFallback.Kind.STORYBOARD) Source.STORYBOARD else Source.THUMBNAIL
            area.fill(0)
            copySize.fill(0)
            accept(out)
        }
        return true
    }

    private fun readDevice(page: Page) {
        val now = SystemClock.uptimeMillis()
        if (deviceReadAt != 0L && now - deviceReadAt < DEVICE_MS) return
        deviceReadAt = now
        val context = page.context() ?: return
        powerSave = YtBattery.powerSave(context)
        thermal = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { context.getSystemService(PowerManager::class.java)?.currentThermalStatus }.getOrNull() ?: 0
        } else {
            0
        }
    }

    // -- the glow ---------------------------------------------------------------------------------

    /** A new sample's zone targets (main thread). */
    private fun accept(zones: FloatArray) {
        if (!settings.enabled) return
        accepted++
        val nowS = SystemClock.uptimeMillis() / 1000f
        val cut = scene.update(zones, nowS)
        zones.copyInto(target, 0, 0, Z.COUNT * Z.CHANNELS)
        YtAmbilightMath.mean(target)
        if (!motion) {
            // animations off: one colour per scene, changed at a cut (or when it drifted far)
            if (cut || !smoother.primed || YtAmbilightMath.distance(smoother.value, target) > DRIFT) {
                smoother.snap(target)
                publish()
            }
            return
        }
        startTicking()
    }

    private val tick = Runnable { onTick() }

    private fun startTicking() {
        if (ticking) return
        ticking = true
        lastTickAt = SystemClock.uptimeMillis()
        main.post(tick)
    }

    private fun onTick() {
        ticking = false
        val now = SystemClock.uptimeMillis()
        val dt = ((now - lastTickAt) / 1000f).coerceIn(0f, 0.1f)
        lastTickAt = now
        val moved = if (motion) smoother.step(target, dt, scene.tau(now / 1000f)) else 0f
        if (beating) {
            val frame = YtSound.frame.value
            val input = if (frame.playing) max(frame.bass, frame.level * 0.85f) else 0f
            val guarded = guard.step(input, dt)
            beat += (guarded - beat) * YtAmbilightMath.follow(dt, BEAT_TAU)
        } else {
            beat += (0f - beat) * YtAmbilightMath.follow(dt, BEAT_TAU)
            if (beat < 0.002f) beat = 0f
        }
        beatGain = YtAmbilightRules.beatGain(beat)
        publish()
        if (moved > SETTLED || beating || beat > 0f) {
            ticking = true
            // docked, the whole window redraws for the halo: fewer steps, the glide is slow anyway
            main.postDelayed(tick, if (place == YtAmbilightPlace.DOCKED) TICK_DOCKED_MS else TICK_MS)
        }
    }

    private fun updateBeat(on: Boolean) {
        if (on == beating) return
        beating = on
        if (on) {
            guard.reset()
            if (!holding) {
                holding = true
                FrequencyPulse.hold()
            }
            startTicking()
        } else if (holding) {
            holding = false
            FrequencyPulse.release()
        }
    }

    /** The displayed values as colours and caps for the painters, then the listeners. */
    private fun publish() {
        val start = System.nanoTime()
        val v = smoother.value
        for (z in 0 until Z.DISPLAY) {
            val o = z * Z.CHANNELS
            presence[z] = v[o + 3].coerceIn(0f, 1f)
            val page = YtAmbilightMath.glow(v[o], v[o + 1], v[o + 2], lightBack, scratch)
            if (page != lastCapColor[z]) {
                lastCapColor[z] = page
                pageColor[z] = page
                val cap = minOf(
                    YtAmbilightMath.maxAlpha(text, back, page, CAP),
                    YtAmbilightMath.maxAlpha(text2, back, page, CAP),
                )
                pageCap[z] = YtAmbilightMath.perLayer(cap, LAYERS)
            }
            val bar = if (lightBack) YtAmbilightMath.glow(v[o], v[o + 1], v[o + 2], false, scratch) else page
            if (bar != lastCapColor[Z.DISPLAY + z]) {
                lastCapColor[Z.DISPLAY + z] = bar
                barColor[z] = bar
                barCap[z] = YtAmbilightMath.perLayer(YtAmbilightMath.maxAlpha(WHITE, BLACK, bar, CAP), LAYERS)
            }
        }
        showing = settings.enabled && smoother.primed
        tickMs = tickMs + ((System.nanoTime() - start) / 1e6f - tickMs) * 0.1f
        notifyListeners()
    }

    private fun notifyListeners() {
        // backwards: a listener may remove itself
        var i = listeners.size - 1
        while (i >= 0) {
            listeners.getOrNull(i)?.onAmbilight()
            i--
        }
    }

    /** No glow (switched off, the page gone): the painters draw nothing from now on. */
    private fun clear() {
        main.removeCallbacks(tick)
        ticking = false
        updateBeat(false)
        beat = 0f
        beatGain = 1f
        smoother.clear()
        presence.fill(0f)
        lastCapColor.fill(0)
        showing = false
        notifyListeners()
    }

    // -- diagnosis --------------------------------------------------------------------------------

    /** For the diagnosis page: switch, source, rate, cost, letterbox, last failure. */
    fun diagnosis(): String {
        val s = settings.takeIf { page != null } ?: YtAmbilightPrefs.read()
        if (!s.enabled) return "off"
        val failure = lastFailure?.let { "$it ${(SystemClock.elapsedRealtime() - lastFailureAt) / 1000} s ago" } ?: "none"
        val box = if (copySize[0] > 0) {
            "picture ${area[0]},${area[1]}–${area[2]},${area[3]} of ${copySize[0]}x${copySize[1]}"
        } else {
            "picture ?"
        }
        return "on, source ${source.label}${if (fallbackActive) " (fallback)" else ""}, place ${place.name.lowercase()}, " +
            "${"%.1f".format(rateHz)} Hz, copy ${"%.1f".format(sampler.copyMs)} ms + cpu ${"%.2f".format(sampler.cpuMs)} ms avg " +
            "(${sampler.samples} copies, $accepted samples), draw prep ${"%.2f".format(tickMs)} ms, $box, " +
            "last failure $failure, power saver ${if (powerSave) "yes" else "no"}, thermal $thermal, " +
            "motion ${if (motion) "on" else "off"}, beat ${if (beating) "on" else "off"}, " +
            "strength ${"%.2f".format(s.intensity)}, bar+background ${if (s.chrome) "on" else "off"}, bars ${if (s.bars) "on" else "off"}"
    }

    private fun codeName(code: Int): String = when (code) {
        PixelCopy.ERROR_UNKNOWN -> "unknown"
        PixelCopy.ERROR_TIMEOUT -> "timeout"
        PixelCopy.ERROR_SOURCE_NO_DATA -> "no data"
        PixelCopy.ERROR_SOURCE_INVALID -> "source invalid"
        PixelCopy.ERROR_DESTINATION_INVALID -> "destination invalid"
        else -> "code $code"
    }

    private const val OPAQUE = -0x1000000
    private const val BLACK = -0x1000000
    private const val WHITE = -0x1

    /** Glow layers that may lie over one another at a point (the contrast cap is split). */
    private const val LAYERS = 3

    /** The most alpha a glow may reach anywhere. */
    private const val CAP = 0.9f

    private const val TICK_MS = 33L
    private const val TICK_DOCKED_MS = 50L
    private const val SETTLED = 0.0005f
    private const val DRIFT = 0.12f
    private const val BEAT_TAU = 0.08f
    private const val DEVICE_MS = 5000L
}
