package com.github.libretube.ui.yt

import android.animation.ValueAnimator
import android.app.ActivityManager
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.Choreographer
import android.view.Display
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.center
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.github.libretube.LibreTubeApp
import com.github.libretube.helpers.YtBattery
import com.github.libretube.helpers.YtDesign
import com.github.libretube.ui.yt.FrequencyMotionMath as F
import com.github.libretube.ui.yt.FrequencyPulseMath as M
import com.github.libretube.ui.yt.sound.YtSound
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * YT: the pulse of the design "Frequency": the sound (YtSound) turned once per display frame
 * into the channels that the design's shapes read while drawing.
 *
 * - Every screen of the design holds the pulse while it is started ([FrequencyPulseHost]); with
 *   no holder (another design, the app in the background) the analysis is switched off
 *   (YtSound.setReactive(false)) and the frame loop stops.
 * - The analysis also stays off while the player page is open but paused, and while the motion
 *   switch (or Android's "remove animations", animator duration scale 0) is off; then every
 *   channel rests, the look of silence. A reduced scale (0.5x, common on Xiaomi) counts as on.
 * - Two kinds of channel (see FrequencyMotion.kt): motion ([energy] = the beat, [bass]/[mid]/
 *   [high], the bounce [spring], [turn], the [morph], the [bands]) follows YtSound.motion and
 *   YtSound.bands quickly; brightness ([glow], [punch], [shimmer], [auraLight]) follows the
 *   guarded YtSound.frame with FrequencyPulseMath.follow's slew limit (≤ 3 swings a second).
 * - The phone's tier ([choice], FrequencyTiers) decides which effects run and how often: it
 *   is read again every few seconds (power saver, heat).
 * - The values are float states and plain fields written from a Choreographer callback, with
 *   [tick] counting the steps: readers in the draw or layer phase read [tick] first and redraw,
 *   nothing recomposes, and a step allocates nothing.
 * Main thread only.
 */
object FrequencyPulse {
    /** The beat (bass and level, stretched): what makes shapes grow (motion, 0..1). */
    val energy = mutableFloatStateOf(0f)

    /** Light of the accent glow: the level (brightness, guarded, 0..1). */
    val glow = mutableFloatStateOf(0f)

    /** Short hits for veils of light (brightness, guarded, ≤ 3 swings a second, 0..1). */
    val punch = mutableFloatStateOf(0f)

    /** Highs and shine for the time bar's colour (brightness, guarded, 0..1). */
    val shimmer = mutableFloatStateOf(0f)

    /** The three bands, stretched (motion, 0..1): bars, drift of the aura. */
    val bass = mutableFloatStateOf(0f)
    val mid = mutableFloatStateOf(0f)
    val high = mutableFloatStateOf(0f)

    /** Degrees the shapes have turned; advances only while there is sound. */
    val turn = mutableFloatStateOf(0f)

    /** Counts the steps: read it in a draw/layer block before the plain fields below. */
    val tick = mutableIntStateOf(0)

    /** The light of the background aura (brightness, guarded and slower still, 0..1). */
    var auraLight = 0f
        private set

    /** The bounce: kicked by hits, overshoots and settles (read with [bounce]). */
    val spring = FrequencySpring()

    /** The light sweeps (read with [FrequencyShine.positionFor]). */
    val shine = FrequencyShine()

    /** The morph's phase, in shapes (FrequencyMorph): moves on with the sound. */
    var morph = 0f
        private set

    /** The blobs' and waves' own clock (radians), faster with the beat. */
    var flow = 0f
        private set

    /** The bass pulling the blobs together and the mids pushing them apart (motion, 0..1). */
    var pull = 0f
        private set
    var spread = 0f
        private set

    /** The spectrum, bass to treble (motion, 0..1). */
    val bands = FloatArray(YtSound.BANDS)

    /** The tier of this phone (read again every [DEVICE_POLL_S] s while it runs). */
    var choice: FrequencyTierChoice = FrequencyTierChoice(FrequencyTier.MID, shader = false, frameDivider = 1)
        private set

    /** The tier of this phone (shortcut). */
    val tier: FrequencyTier get() = choice.tier

    /** The bounce of element [index]: each one a little later than the one before it. */
    fun bounce(index: Int): Float =
        if (!choice.tier.bounce) 0f else spring.at(STAGGER_S * index.coerceIn(0, MAX_STAGGER))

    /** How long element n+1 bounces after element n. */
    const val STAGGER_S = 0.045f
    private const val MAX_STAGGER = 10

    /**
     * Views that follow the pulse (the player's time bar): called after every frame's step on
     * the main thread, and once more after the pulse came to rest. No allocation per frame.
     */
    private val viewListeners = ArrayList<() -> Unit>(2)

    fun addViewListener(listener: () -> Unit) {
        if (listener !in viewListeners) viewListeners.add(listener)
    }

    fun removeViewListener(listener: () -> Unit) {
        viewListeners.remove(listener)
    }

    private fun notifyViews() {
        for (i in viewListeners.indices) viewListeners[i]()
    }

    private var holders = 0
    private var running = false
    private var lastNs = 0L
    private var pageSaysPaused = false
    private var clockJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Called by the screens of the design while they are started. */
    fun hold() {
        holders++
        if (holders == 1) {
            readDevice(full = true)
            // the player page, when one is open, says whether something plays
            clockJob = scope.launch {
                YtPlaybackClock.clock.collect { clock ->
                    pageSaysPaused = clock.videoId != null && !clock.playing
                    update()
                }
            }
        }
        update()
    }

    /** The screen stopped or left. */
    fun release() {
        if (holders == 0) return
        holders--
        if (holders == 0) {
            clockJob?.cancel()
            clockJob = null
            pageSaysPaused = false
        }
        update()
    }

    /** Animator duration scale > 0 (Android's "remove animations" sets 0; 0.5x stays on). */
    private val motionAllowed: Boolean
        get() = YtMotionPrefs.enabled && ValueAnimator.areAnimatorsEnabled()

    /** Whether the analysis is wanted now. */
    private val wanted: Boolean
        get() = holders > 0 && motionAllowed && !pageSaysPaused && YtDesign.current() == YtDesign.FREQUENCY

    /**
     * [wanted] as last worked out (on every hold, release and change of the playback clock):
     * the frame loop reads this instead of the preferences every frame.
     */
    private var active = false

    /** For the diagnosis page: holders, whether it listens, runs, and the tier. */
    fun diagnosis(): String =
        "holders $holders, listening ${if (active) "yes" else "no"}, loop ${if (running) "running" else if (idling) "idle" else "stopped"}, " +
            "motion ${if (motionAllowed) "on" else "off"}, page paused ${if (pageSaysPaused) "yes" else "no"}, " +
            "tier ${choice.tier}${if (choice.shader) " (shader)" else ""}, every ${choice.frameDivider}. frame"

    private fun update() {
        active = wanted
        YtSound.setReactive(active)
        if (holders > 0 && motionAllowed) {
            // runs on while paused, so the shapes settle back to rest smoothly; at rest it only
            // looks a few times a second whether sound came (no frame loop in silence)
            if (!running && (!idling || soundComing)) start()
        } else {
            stop()
            stopIdle()
            rest(motionOff = true)
            notifyViews()
        }
    }

    /** Whether there is sound to follow now. */
    private val soundComing: Boolean
        get() = active && YtSound.frame.value.playing

    private fun start() {
        stopIdle()
        if (running) return
        running = true
        lastNs = 0L
        pending = 0f
        frames = 0
        Choreographer.getInstance().postFrameCallback(frame)
    }

    private fun stop() {
        if (!running) return
        running = false
        Choreographer.getInstance().removeFrameCallback(frame)
    }

    private var idling = false
    private val handler = Handler(Looper.getMainLooper())
    private val idleCheck = Runnable {
        idling = false
        if (holders > 0) {
            if (soundComing) start() else idle()
        }
    }

    /** At rest and silent: the frame loop stops, a check every [IDLE_MS] waits for sound. */
    private fun idle() {
        if (idling) return
        idling = true
        handler.postDelayed(idleCheck, IDLE_MS)
    }

    private fun stopIdle() {
        if (!idling) return
        idling = false
        handler.removeCallbacks(idleCheck)
    }

    private const val IDLE_MS = 200L

    // -- the phone ----------------------------------------------------------------------------

    private const val DEVICE_POLL_S = 5f
    private var sinceDevice = 0f
    private var lowRam = false
    private var totalRamMb = 0L
    private var cores = 0

    /** Reads what the tier depends on; [full] also what never changes (RAM, cores). */
    private fun readDevice(full: Boolean) {
        val context: Context = runCatching { LibreTubeApp.instance }.getOrNull() ?: return
        if (full || totalRamMb == 0L) {
            runCatching {
                val am = context.getSystemService(ActivityManager::class.java)
                lowRam = am?.isLowRamDevice == true
                val info = ActivityManager.MemoryInfo()
                am?.getMemoryInfo(info)
                totalRamMb = info.totalMem / (1024L * 1024L)
            }
            cores = Runtime.getRuntime().availableProcessors()
        }
        val refresh = runCatching {
            context.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)?.refreshRate
        }.getOrNull() ?: 60f
        val thermal = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { context.getSystemService(PowerManager::class.java)?.currentThermalStatus }.getOrNull() ?: 0
        } else {
            0
        }
        choice = FrequencyTiers.pick(
            FrequencyDevice(
                refreshHz = refresh,
                sdk = Build.VERSION.SDK_INT,
                lowRam = lowRam,
                totalRamMb = totalRamMb,
                cores = cores,
                powerSave = YtBattery.powerSave(context),
                thermal = thermal,
            ),
        )
    }

    // -- the channels -------------------------------------------------------------------------

    private val beatStretch = FrequencyStretch()
    private val bassStretch = FrequencyStretch()
    private val midStretch = FrequencyStretch()
    private val highStretch = FrequencyStretch()
    private val glowStretch = FrequencyStretch(mix = 0.5f)
    private val kick = FrequencyKick()
    private var highSlow = 0f
    private var turnRate = 0f

    private fun rest(motionOff: Boolean) {
        energy.floatValue = 0f
        glow.floatValue = 0f
        punch.floatValue = 0f
        shimmer.floatValue = 0f
        bass.floatValue = 0f
        mid.floatValue = 0f
        high.floatValue = 0f
        auraLight = 0f
        pull = 0f
        spread = 0f
        highSlow = 0f
        turnRate = 0f
        bands.fill(0f)
        spring.reset()
        shine.reset()
        kick.reset()
        beatStretch.reset()
        bassStretch.reset()
        midStretch.reset()
        highStretch.reset()
        glowStretch.reset()
        if (motionOff) {
            // animations off: every shape in its own first form, standing still
            morph = 0f
            flow = 0f
            turn.floatValue = 0f
        }
        tick.intValue++
    }

    private var frames = 0
    private var pending = 0f

    private val frame = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            val dt = if (lastNs == 0L) 0f else (frameTimeNanos - lastNs) / 1_000_000_000f
            lastNs = frameTimeNanos
            // the tier's rate: one step per frameDivider display frames (the others draw nothing new)
            pending += dt
            frames++
            if (frames % choice.frameDivider == 0) {
                step(pending)
                pending = 0f
            }
            if (running) Choreographer.getInstance().postFrameCallback(this)
        }
    }

    /** One step: every channel follows the sound; silence while not wanted. */
    private fun step(dt: Float) {
        sinceDevice += dt
        if (sinceDevice >= DEVICE_POLL_S) {
            sinceDevice = 0f
            readDevice(full = false)
        }
        val t = choice.tier
        val sound = YtSound.frame.value
        val live = active && sound.playing
        val m = YtSound.motion

        // motion: quick, from the unguarded values, stretched to the range they moved in lately
        val mLevel = if (live) m[YtSound.MOTION_LEVEL] else 0f
        val mBass = if (live) m[YtSound.MOTION_BASS] else 0f
        val mMid = if (live) m[YtSound.MOTION_MID] else 0f
        val mHigh = if (live) m[YtSound.MOTION_HIGH] else 0f
        val mPunch = if (live) m[YtSound.MOTION_PUNCH] else 0f
        val beat = M.follow(energy.floatValue, beatStretch.step(F.beatTarget(mLevel, mBass), dt), dt, MOTION_ATTACK, MOTION_RELEASE, MOTION_SLEW)
        energy.floatValue = beat
        bass.floatValue = M.follow(bass.floatValue, bassStretch.step(mBass, dt), dt, MOTION_ATTACK, MOTION_RELEASE, MOTION_SLEW)
        mid.floatValue = M.follow(mid.floatValue, midStretch.step(mMid, dt), dt, MOTION_ATTACK, MOTION_RELEASE, MOTION_SLEW)
        high.floatValue = M.follow(high.floatValue, highStretch.step(mHigh, dt), dt, MOTION_ATTACK, MOTION_RELEASE, MOTION_SLEW)
        for (b in bands.indices) {
            val target = if (live) YtSound.bands[b] else 0f
            bands[b] = M.follow(bands[b], target, dt, 0.02f, 0.12f, BAND_SLEW)
        }
        pull = M.follow(pull, bass.floatValue, dt, 0.08f, 0.4f, 4f)
        spread = M.follow(spread, mid.floatValue, dt, 0.15f, 0.5f, 3f)

        // hits: the springs bounce, a light sweeps on a hit or when the highs jump
        val hit = if (t.bounce || t.shine) kick.step(mPunch, dt) else 0f
        if (hit > 0f && t.bounce) spring.kick(hit * t.amplitude)
        spring.step(dt)
        val jump = (mHigh - highSlow - 0.12f) * 3f
        highSlow = M.follow(highSlow, mHigh, dt, 0.3f, 0.3f, 10f)
        shine.step(if (t.shine && live) max(hit, jump) else 0f, dt)

        // brightness: from the guarded frame, slew-limited (≤ 3 swings a second)
        val gLevel = if (live) sound.level else 0f
        val gPunch = if (live) sound.punch else 0f
        val gHigh = if (live) sound.high else 0f
        glow.floatValue = M.follow(glow.floatValue, M.glowTarget(glowStretch.step(gLevel, dt)), dt)
        punch.floatValue = M.follow(punch.floatValue, gPunch, dt)
        // the time bar lifts with the highs and with each sweep (a sweep is ≥ 0.45 s apart)
        val sweep = if (shine.active) shine.strength else 0f
        shimmer.floatValue = M.follow(shimmer.floatValue, max(gHigh, sweep), dt)
        auraLight = M.follow(auraLight, gLevel, dt, 0.2f, 0.6f, AURA_SLEW)

        // turning and morphing glide to a stop in silence
        val wantTurn = F.turnSpeed(live, beat) * t.amplitude
        turnRate += (wantTurn - turnRate) * (1f - kotlin.math.exp(-dt / TURN_EASE_S))
        if (abs(turnRate) < 0.05f && !live) turnRate = 0f
        if (turnRate != 0f) turn.floatValue = (turn.floatValue + turnRate * dt) % 360f
        if (t.morph) {
            val speed = if (live) {
                F.morphSpeed(true, beat, spring.x)
            } else if (FrequencyMorph.progress(morph) > 0f) {
                // in silence the morph finishes its way into the next whole shape, then holds
                SETTLE_MORPH
            } else {
                0f
            }
            morph = (morph + speed * dt) % (FrequencyMorph.SHAPES * 64f)
        }
        if (live) flow = (flow + dt * (0.3f + 1.6f * beat)) % (M.TAU * 64f)

        tick.intValue++
        // the loudest channel left (no array: nothing is allocated per frame)
        var most = maxOf(maxOf(beat, glow.floatValue), maxOf(punch.floatValue, shimmer.floatValue))
        most = maxOf(most, maxOf(maxOf(bass.floatValue, mid.floatValue), maxOf(high.floatValue, auraLight)))
        most = maxOf(most, maxOf(pull, spread))
        for (b in bands.indices) most = maxOf(most, bands[b])
        val moving = !spring.resting || shine.active || turnRate != 0f || (t.morph && FrequencyMorph.progress(morph) > 0f)
        if (!live && most < M.REST_EPSILON && !moving) {
            // settled into silence: exactly the rest state, then no frame loop until sound comes
            rest(motionOff = false)
            stop()
            idle()
        }
        notifyViews()
    }

    private const val MOTION_ATTACK = 0.03f
    private const val MOTION_RELEASE = 0.16f
    private const val MOTION_SLEW = 14f
    private const val BAND_SLEW = 20f
    private const val AURA_SLEW = 2f
    private const val TURN_EASE_S = 0.35f
    private const val SETTLE_MORPH = 0.6f
}

/**
 * Holds the pulse while this screen is started (call once in every screen of the design). The
 * analysis runs only while at least one is held, so it stops in the background.
 */
@Composable
fun FrequencyPulseHost() {
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        var held = false
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> if (!held) {
                    held = true
                    FrequencyPulse.hold()
                }
                Lifecycle.Event.ON_STOP -> if (held) {
                    held = false
                    FrequencyPulse.release()
                }
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            if (held) FrequencyPulse.release()
        }
    }
}

/** True for the palette of Frequency. */
val YtPalette.isFrequency: Boolean get() = signature == YtSignature.FREQUENCY

/** The shape colours of Frequency in turn: aqua, violet, coral, amber. */
fun frequencyTag(palette: YtPalette, index: Int): Color = when (Math.floorMod(index, 4)) {
    0 -> palette.lime
    1 -> palette.lavender
    2 -> palette.accent
    else -> palette.pink
}

/*
 * The modifiers below read the pulse only while drawing (or in the layer block), never while
 * composing. In silence they draw the rest state. Sizes are fractions of the element or dp, so
 * they scale with every screen.
 */

/**
 * Grows with the beat by up to [amount] (8–20 %) and bounces on hits (element [index] a little
 * after the one before), around its centre. Motion only.
 */
fun Modifier.frequencyBreath(amount: Float = 0.16f, index: Int = 0): Modifier = graphicsLayer {
    FrequencyPulse.tick.intValue
    val s = F.scale(FrequencyPulse.energy.floatValue, FrequencyPulse.bounce(index), amount, FrequencyPulse.tier.amplitude)
    scaleX = s
    scaleY = s
}

/**
 * Jumps up by [lift] on a hit and lands with an overshoot (a spring), element [index] a little
 * after the one before; grows by [amount] with the beat as [frequencyBreath]. Motion only.
 */
fun Modifier.frequencyBounce(index: Int, lift: Dp = 4.dp, amount: Float = 0.1f): Modifier = graphicsLayer {
    FrequencyPulse.tick.intValue
    val b = FrequencyPulse.bounce(index)
    val s = F.scale(FrequencyPulse.energy.floatValue, b, amount, FrequencyPulse.tier.amplitude)
    scaleX = s
    scaleY = s
    translationY = -lift.toPx() * b * FrequencyPulse.tier.amplitude
}

/** Turns with the music (and stands still in silence); [factor] scales and reverses it. */
fun Modifier.frequencyTurn(factor: Float = 1f): Modifier = graphicsLayer {
    rotationZ = FrequencyPulse.turn.floatValue * factor
}

/**
 * A soft round light of [color] behind the element, [reach] times its half size (a little more
 * with the beat); its alpha goes from [rest] (silence) to [peak] with the level (guarded).
 */
fun Modifier.frequencyGlow(
    color: Color,
    reach: Float = 1.25f,
    rest: Float = M.GLOW_REST,
    peak: Float = M.GLOW_PEAK,
): Modifier = drawWithCache {
    val radius = max(size.width, size.height) / 2f * reach
    val brush = Brush.radialGradient(listOf(color, color.copy(alpha = 0f)), Offset.Zero, radius.coerceAtLeast(1f))
    onDrawBehind {
        val grow = 1f + 0.22f * FrequencyPulse.energy.floatValue * FrequencyPulse.tier.amplitude
        translate(center.x, center.y) {
            drawCircle(brush, radius * grow, Offset.Zero, alpha = M.glowAlpha(FrequencyPulse.glow.floatValue, rest, peak))
        }
    }
}

/**
 * The outline of a Frequency shape into [path] (reset first): around [center] with outer
 * [radius], at morph [phase] (FrequencyMorph: circle, cookie, blob, squircle), a cookie of
 * [lobes] at [depth], turned by [turn] radians.
 */
fun frequencyShapePath(path: Path, center: Offset, radius: Float, lobes: Int, depth: Float, phase: Float, turn: Float) {
    path.reset()
    val flow = FrequencyPulse.flow
    val steps = max(48, lobes * 10)
    for (i in 0..steps) {
        val angle = M.TAU * i / steps
        val r = radius * FrequencyMorph.radiusAt(phase, angle, lobes, depth, flow, turn)
        val x = center.x + r * sin(angle)
        val y = center.y - r * cos(angle)
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
}

/** The morph phase of element [index] whose own form is [base] (a FrequencyMorph shape). */
fun frequencyPhase(index: Int, base: Int = FrequencyMorph.COOKIE): Float =
    if (!FrequencyPulse.tier.morph) base.toFloat() else FrequencyPulse.morph + base + 0.37f * index

/**
 * A scalloped "cookie" of [color] behind the element (Formen's shape), [lobes] scallops, [rest]
 * deep in silence. With the sound it deepens and grows with the beat, bounces on hits, turns,
 * and morphs on into a circle, a blob and a squircle and back ([index] puts it at its own place
 * in that cycle). [ring] > 0 draws only its outline, that many px wide.
 */
fun Modifier.frequencyCookie(color: Color, lobes: Int = 9, rest: Float = 0.07f, ring: Float = 0f, index: Int = 0): Modifier = drawWithCache {
    val path = Path()
    val stroke = if (ring > 0f) Stroke(ring) else null
    onDrawBehind {
        FrequencyPulse.tick.intValue
        val e = FrequencyPulse.energy.floatValue
        val depth = M.cookieDepth(rest, e)
        val turn = Math.toRadians(FrequencyPulse.turn.floatValue.toDouble()).toFloat()
        val grow = F.scale(e, FrequencyPulse.bounce(index), 0.16f, FrequencyPulse.tier.amplitude)
        val outer = (min(size.width, size.height) / 2f - (stroke?.width ?: 0f) / 2f) * grow
        frequencyShapePath(path, center, outer, lobes, depth, frequencyPhase(index), turn)
        if (stroke != null) drawPath(path, color, style = stroke) else drawPath(path, color)
    }
}

/** Builds a cookie of [lobes] around [center] with outer [radius] into [path] (reset first). */
fun cookiePath(path: Path, center: Offset, radius: Float, lobes: Int, depth: Float, turn: Float) {
    path.reset()
    val steps = (lobes * 10).coerceAtLeast(24)
    for (i in 0..steps) {
        val angle = M.TAU * i / steps
        val r = radius * M.cookieRadius(angle, lobes, depth, turn)
        val x = center.x + r * sin(angle)
        val y = center.y - r * cos(angle)
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
}

/**
 * A filled Frequency shape of [color] behind the content (instead of clip + background): it
 * morphs from its own form [base] through the others, grows with the beat by [amount], bounces
 * as element [index], and a light sweeps across it on hits (clipped to the shape, over the
 * content). In silence: [base] at rest. The content (an icon) stays where it is.
 */
fun Modifier.frequencyShape(
    color: Color,
    index: Int,
    base: Int = FrequencyMorph.COOKIE,
    lobes: Int = 9,
    rest: Float = 0.08f,
    amount: Float = 0.18f,
): Modifier = drawWithCache {
    val path = Path()
    val band = size.width * 0.45f
    val sheen = Brush.horizontalGradient(
        listOf(Color.White.copy(alpha = 0f), Color.White, Color.White.copy(alpha = 0f)),
        startX = 0f,
        endX = band.coerceAtLeast(1f),
    )
    onDrawWithContent {
        FrequencyPulse.tick.intValue
        val e = FrequencyPulse.energy.floatValue
        val grow = F.scale(e, FrequencyPulse.bounce(index), amount, FrequencyPulse.tier.amplitude)
        val turn = Math.toRadians(FrequencyPulse.turn.floatValue.toDouble() * if (index % 2 == 0) 1 else -1).toFloat()
        frequencyShapePath(path, center, min(size.width, size.height) / 2f * grow, lobes, M.cookieDepth(rest, e), frequencyPhase(index, base), turn)
        drawPath(path, color)
        drawContent()
        drawSweep(path, sheen, band, index)
    }
}

/** The shine sweep of element [index] across this element, clipped to [clip]. */
private fun DrawScope.drawSweep(clip: Path, sheen: Brush, band: Float, index: Int) {
    val at = FrequencyPulse.shine.positionFor(index)
    if (at < 0f) return
    val a = FrequencyPulse.shine.alphaAt(at)
    if (a <= 0.003f) return
    val x = -band + (size.width + band) * at
    clipPath(clip) {
        translate(x, 0f) { drawRect(sheen, Offset.Zero, Size(band, size.height), alpha = a) }
    }
}

/**
 * A soft band of light that sweeps across the element (clipped to [shape]) on hits and when the
 * highs jump: element [index] a little after the one before. [strength] scales its light (cards
 * get less than buttons). Nothing in silence.
 */
fun Modifier.frequencyShimmer(
    color: Color = Color.White,
    shape: Shape = RectangleShape,
    index: Int = 0,
    strength: Float = 1f,
): Modifier = drawWithCache {
    val band = size.width * 0.35f
    val brush = Brush.horizontalGradient(
        listOf(color.copy(alpha = 0f), color, color.copy(alpha = 0f)),
        startX = 0f,
        endX = band.coerceAtLeast(1f),
    )
    val outline = shape.createOutline(size, layoutDirection, this)
    val clip = Path().apply { addOutline(outline) }
    onDrawWithContent {
        drawContent()
        FrequencyPulse.tick.intValue
        val at = FrequencyPulse.shine.positionFor(index)
        if (at < 0f) return@onDrawWithContent
        val a = FrequencyPulse.shine.alphaAt(at) * strength.coerceIn(0f, 1f)
        if (a <= 0.003f) return@onDrawWithContent
        val x = -band + (size.width + band) * at
        clipPath(clip) {
            translate(x, 0f) { drawRect(brush, Offset.Zero, Size(band, size.height), alpha = a) }
        }
    }
}
