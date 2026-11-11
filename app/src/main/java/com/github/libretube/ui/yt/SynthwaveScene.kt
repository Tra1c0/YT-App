package com.github.libretube.ui.yt

import android.animation.ValueAnimator
import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import com.github.libretube.helpers.YtDesign
import com.github.libretube.ui.yt.FrequencyPulseMath.follow
import com.github.libretube.ui.yt.SynthwaveMath as S
import com.github.libretube.ui.yt.sound.YtSound
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * YT: the colours of Synthwave's scene, shared by the drawing and the contrast test
 * (YtSynthwavePaletteTest checks the words on the glass cards over the brightest of them).
 */
object SynthwaveColors {
    /** The sky, top to the haze above the horizon. */
    val skyTop = Color(0xFF0D0620)
    val skyMid = Color(0xFF240A45)
    val skyLow = Color(0xFF5A1258)

    /** The sun, top to bottom: yellow, orange, pink. */
    val sunTop = Color(0xFFFFE45E)
    val sunMid = Color(0xFFFF9E3D)
    val sunLow = Color(0xFFFF4F7B)
    val sunBottom = Color(0xFFFF2E97)

    /** The halo around the sun (alpha from SynthwaveMath.sunGlow) and its faint rings. */
    val halo = Color(0xFFFF4FA8)

    /** The floor, the grid's neon and the horizon. */
    val floorTop = Color(0xFF1C0734)
    val floorBottom = Color(0xFF07030F)
    val grid = Color(0xFF36E4FF)
    val gridFar = Color(0xFFFF3FD8)
    val horizon = Color(0xFFFF8AE8)
    val haze = Color(0xFFFF3FD8)

    /** The wireframe range, far (violet) to near (electric blue), and its dark body. */
    val ridgeFar = Color(0xFFA98BFF)
    val ridgeNear = Color(0xFF4F9DFF)
    val ridgeBodyFar = Color(0xFF1E0B40)
    val ridgeBodyNear = Color(0xFF0C0624)

    /** The palms and the stars. */
    val palm = Color(0xFF06020D)
    val star = Color(0xFFFFF4FF)

    /** How strong the halo's centre is at its loudest (alpha of [halo] where it meets the sun). */
    const val HALO_CORE = 0.6f

    /** The haze band at the horizon at its strongest. */
    const val HAZE_PEAK = 0.42f

    /**
     * The night veil over the scene behind the shared screens (Du, Abos, Shorts): their words
     * stand on the background itself, so the scene shows dimmed there; with this veil even the
     * sun's top keeps every text role at 4.5:1 (YtSynthwavePaletteTest).
     */
    const val TAB_VEIL = 0.86f

    /** The brightest colour the scene shows anywhere (the sun's top), for the contrast test. */
    val brightest: Color get() = sunTop
}

/**
 * YT: the scene of the design "Synthwave", run once for all its screens: the floor's drive, the
 * sun's slits, the stars' clock and what the sound does to them. A Choreographer callback steps
 * plain fields and bumps [tick]; the backdrop reads [tick] while drawing, so nothing recomposes
 * and a step allocates nothing.
 *
 * - Screens hold the scene while they are started ([SynthwaveSceneHost]); with no holder (the
 *   app in the background, another design) there is no frame loop.
 * - While the player page covers the screens (opened, not docked) the loop pauses and looks
 *   again a few times a second.
 * - With the design animations off or Android's "remove animations" (animator scale 0) the scene
 *   is one still, finished picture ([SynthwaveTiers.STILL]) and nothing runs.
 * - The phone's tier (FrequencyTiers via FrequencyDeviceProbe, read every 5 s) decides the
 *   detail ([SynthwaveTiers.of]) and the rate (frameDivider): high = everything at the display's
 *   rate; mid = fewer stars and lines, ≤ 60 steps a second; low / power saver = a still sky with
 *   only the floor driving at ≈ 30 steps a second.
 * - The sound comes through FrequencyPulse (held at the same time, it listens only while a
 *   sound-reactive design is chosen): the bass speeds up the drive and the bands sway the
 *   ridges (motion channels), the guarded level lights the sun's halo and the guarded highs
 *   deepen the stars' twinkle (brightness: slew-limited, ≤ 3 swings a second). In silence the
 *   floor drives at its calm speed.
 * Main thread only.
 */
object SynthwaveScene {
    /** Counts the steps: read it in a draw block before the fields below. */
    val tick = mutableIntStateOf(0)

    /** How much of the scene is drawn now (the tier's, or the still picture). */
    val detail = mutableStateOf(SynthwaveTiers.STILL)

    /** How far the floor has driven within one row (0..1). */
    var drive = SynthwaveTiers.STILL_DRIVE
        private set

    /** How far the sun's slits have scrolled within one slit (0..1). */
    var slits = SynthwaveTiers.STILL_SLITS
        private set

    /** The stars' clock, in seconds. */
    var clock = SynthwaveTiers.STILL_CLOCK
        private set

    /** The sun's halo (brightness, guarded, 0..1). */
    var glow = 0f
        private set

    /** How deep the stars twinkle beyond rest (brightness, guarded highs, 0..1). */
    var sparkle = 0f
        private set

    /** The bass that speeds up the floor (motion, 0..1). */
    var bass = 0f
        private set

    /** How far each ridge line stretches up (motion, fractions). */
    val sway = FloatArray(SynthwaveTiers.MAX_RIDGES)

    private var choice: FrequencyTierChoice = FrequencyTierChoice(FrequencyTier.MID, shader = false, frameDivider = 1)
    private var holders = 0
    private var running = false
    private var covered = false
    private var lastNs = 0L
    private var frames = 0
    private var pending = 0f
    private var sinceDevice = 0f
    private val handler = Handler(Looper.getMainLooper())

    /** Called by the screens of the design while they are started. */
    fun hold() {
        holders++
        if (holders == 1) choice = FrequencyDeviceProbe.choice(full = true) ?: choice
        update()
    }

    /** The screen stopped or left. */
    fun release() {
        if (holders == 0) return
        holders--
        update()
    }

    /** Animator duration scale > 0 (Android's "remove animations" sets 0; 0.5x stays on). */
    private val motionAllowed: Boolean
        get() = YtMotionPrefs.enabled && ValueAnimator.areAnimatorsEnabled()

    /** The player page lies over the screens: open, not on its way to the bar. */
    private fun pageCovers(): Boolean =
        YtPlaybackClock.clock.value.videoId != null && YtDock.progress <= 0.001f && !YtDock.docked

    fun diagnosis(): String =
        "holders $holders, loop ${if (running) "running" else if (covered) "paused (page open)" else "still"}, " +
            "motion ${if (motionAllowed) "on" else "off"}, tier ${choice.tier}, every ${choice.frameDivider}. frame, " +
            "stars ${detail.value.stars}, rows ${detail.value.rows}"

    private fun update() {
        if (holders > 0 && motionAllowed && YtDesign.current() == YtDesign.SYNTHWAVE) {
            detail.value = SynthwaveTiers.of(choice.tier)
            start()
        } else {
            stop()
            still()
        }
    }

    /** The still picture: every value at its chosen moment, the sound at rest. */
    private fun still() {
        detail.value = SynthwaveTiers.STILL
        drive = SynthwaveTiers.STILL_DRIVE
        slits = SynthwaveTiers.STILL_SLITS
        clock = SynthwaveTiers.STILL_CLOCK
        glow = 0f
        sparkle = 0f
        bass = 0f
        sway.fill(0f)
        tick.intValue++
    }

    private fun start() {
        handler.removeCallbacks(recheck)
        if (running) return
        running = true
        covered = false
        lastNs = 0L
        frames = 0
        pending = 0f
        Choreographer.getInstance().postFrameCallback(frame)
    }

    private fun stop() {
        handler.removeCallbacks(recheck)
        covered = false
        if (!running) return
        running = false
        Choreographer.getInstance().removeFrameCallback(frame)
    }

    /** While the page covers the screens: a look every [RECHECK_MS] whether it went away. */
    private val recheck = Runnable {
        if (holders == 0 || running) return@Runnable
        if (pageCovers()) {
            pauseCovered()
        } else {
            covered = false
            start()
        }
    }

    private fun pauseCovered() {
        covered = true
        handler.postDelayed(recheck, RECHECK_MS)
    }

    private val frame = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            if (pageCovers()) {
                running = false
                pauseCovered()
                return
            }
            val dt = if (lastNs == 0L) 0f else (frameTimeNanos - lastNs) / 1_000_000_000f
            lastNs = frameTimeNanos
            pending += dt
            frames++
            if (frames % choice.frameDivider == 0) {
                step(pending.coerceAtMost(FrequencyPulseMath.MAX_DT))
                pending = 0f
            }
            if (running) Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private fun step(dt: Float) {
        sinceDevice += dt
        if (sinceDevice >= DEVICE_POLL_S) {
            sinceDevice = 0f
            // Android's "remove animations" switched on meanwhile: the still picture, no loop
            if (!ValueAnimator.areAnimatorsEnabled()) {
                stop()
                still()
                return
            }
            val next = FrequencyDeviceProbe.choice(full = false)
            if (next != null && next != choice) {
                choice = next
                detail.value = SynthwaveTiers.of(choice.tier)
            }
        }
        val d = detail.value
        // the sound (rests at 0 when nothing plays or the pulse does not listen)
        val listen = d.sound
        val pulseBass = if (listen) FrequencyPulse.bass.floatValue else 0f
        bass = follow(bass, pulseBass, dt, BASS_ATTACK, BASS_RELEASE, BASS_SLEW)
        glow = follow(glow, if (listen) FrequencyPulse.glow.floatValue else 0f, dt)
        val high = if (listen && YtSound.frame.value.playing) YtSound.frame.value.high else 0f
        sparkle = follow(sparkle, high, dt)
        val bands = FrequencyPulse.bands
        for (r in sway.indices) {
            val target = if (listen && r < d.ridges) S.ridgeSway(bands[S.ridgeBand(r, d.ridges, bands.size)]) else 0f
            sway[r] = follow(sway[r], target, dt, 0.05f, 0.25f, 1f)
        }

        drive = S.frac(drive + S.driveSpeed(bass) * dt)
        if (d.sky) {
            slits = S.frac(slits + S.SLIT_SPEED * dt)
            clock = (clock + dt) % CLOCK_WRAP_S
        }
        tick.intValue++
    }

    private const val DEVICE_POLL_S = 5f
    private const val RECHECK_MS = 350L
    private const val CLOCK_WRAP_S = 3600f
    private const val BASS_ATTACK = 0.12f
    private const val BASS_RELEASE = 0.6f
    private const val BASS_SLEW = 3f
}

/**
 * Holds Synthwave's scene and the sound pulse while this screen is started (call it once in
 * every screen of the design that shows the scene or follows the sound).
 */
@Composable
fun SynthwaveSceneHost() {
    FrequencyPulseHost()
    YtHoldWhileStarted(SynthwaveScene::hold, SynthwaveScene::release)
}

/** True for the palette of Synthwave. */
val YtPalette.isSynthwave: Boolean get() = signature == YtSignature.SYNTHWAVE

/**
 * The scene of Synthwave as the full background of a screen (put it first in a Box with
 * Modifier.matchParentSize()): a starry violet sky, a huge striped sun (its slits scroll down
 * slowly) with a halo that glows with the level, wireframe mountains on the horizon whose lines
 * sway a little with the bands, a neon floor driving towards the viewer (faster with the bass),
 * a soft haze along the horizon and palm silhouettes left and right. [horizon] is where the
 * horizon lies (a fraction of the height); [veil] dims the whole scene with the night (for screens
 * whose words stand on it); [scroll] (px the content above scrolled) lifts the
 * scene a little, the near layers more than the far ones.
 *
 * It draws in its own layer: a step redraws the layer, never the list above it. Brushes, paths
 * and the stars are made once per size (and per detail); a frame only moves and draws them.
 */
@Composable
fun SynthwaveBackdrop(
    modifier: Modifier = Modifier,
    horizon: Float = 0.6f,
    /** A night veil over the whole scene (0: none), where words stand on it directly. */
    veil: Float = 0f,
    scroll: (() -> Float)? = null,
) {
    Spacer(
        modifier
            .clearAndSetSemantics {}
            .graphicsLayer()
            .drawWithCache {
                val w = size.width
                val h = size.height
                val d = SynthwaveScene.detail.value
                val c = SynthwaveColors
                val horizonY = h * horizon
                val sunR = min(w * 0.38f, h * 0.22f)
                val sunX = w * 0.5f
                val sunY = horizonY - sunR * 0.32f
                val mountainH = h * 0.13f
                val maxShift = 40.dp.toPx()

                val sky = Brush.verticalGradient(
                    0f to c.skyTop, 0.55f to c.skyMid, 1f to c.skyLow,
                    startY = 0f, endY = horizonY,
                )
                val sun = Brush.verticalGradient(
                    0f to c.sunTop, 0.45f to c.sunMid, 0.75f to c.sunLow, 1f to c.sunBottom,
                    startY = sunY - sunR, endY = sunY + sunR,
                )
                val sunPath = Path().apply {
                    addOval(androidx.compose.ui.geometry.Rect(Offset(sunX, sunY), sunR))
                }
                val halo = Brush.radialGradient(
                    0f to c.halo, 0.42f to c.halo.copy(alpha = 0.55f), 1f to c.halo.copy(alpha = 0f),
                    center = Offset(sunX, sunY), radius = sunR * 2.2f,
                )
                val ring = Stroke(1.dp.toPx())
                val floor = Brush.verticalGradient(listOf(c.floorTop, c.floorBottom), startY = horizonY, endY = h)
                val hazeHalf = 46.dp.toPx()
                val haze = Brush.verticalGradient(
                    0f to c.haze.copy(alpha = 0f), 0.5f to c.haze.copy(alpha = SynthwaveColors.HAZE_PEAK), 1f to c.haze.copy(alpha = 0f),
                    startY = horizonY - hazeHalf, endY = horizonY + hazeHalf,
                )
                val reflection = Brush.radialGradient(
                    listOf(c.sunLow.copy(alpha = 0.28f), c.sunLow.copy(alpha = 0f)),
                    center = Offset(sunX, horizonY), radius = sunR * 1.2f,
                )
                val columnBrush = Brush.verticalGradient(
                    0f to c.gridFar.copy(alpha = 0f), 0.25f to c.grid.copy(alpha = 0.35f), 1f to c.grid.copy(alpha = 0.85f),
                    startY = horizonY, endY = h,
                )
                val px = 1.dp.toPx()

                // the ridges: a line and a closed body per contour, made once per size and detail
                val ridgeLines = Array(d.ridges) { Path() }
                val ridgeBodies = Array(d.ridges) { Path() }
                val steps = 56
                for (r in 0 until d.ridges) {
                    val line = ridgeLines[r]
                    val body = ridgeBodies[r]
                    for (i in 0..steps) {
                        val x = w * i / steps
                        val y = horizonY - mountainH * S.ridge(i.toFloat() / steps, r, d.ridges)
                        if (i == 0) {
                            line.moveTo(x, y)
                            body.moveTo(x, horizonY + 2f * px)
                            body.lineTo(x, y)
                        } else {
                            line.lineTo(x, y)
                            body.lineTo(x, y)
                        }
                    }
                    body.lineTo(w, horizonY + 2f * px)
                    body.close()
                }
                val ridgeStroke = Stroke(1.2f * px)
                val ridgeGlow = Stroke(3.5f * px)

                // the palms, left and right, made once per size
                val palms = Path()
                synthwavePalm(palms, w * 0.06f, h * 0.86f, h * 0.44f, lean = 0.22f, flip = false)
                synthwavePalm(palms, -w * 0.06f, h * 0.9f, h * 0.36f, lean = 0.32f, flip = false)
                synthwavePalm(palms, w * 0.94f, h * 0.88f, h * 0.4f, lean = 0.2f, flip = true)
                synthwavePalm(palms, w * 1.05f, h * 0.84f, h * 0.48f, lean = 0.28f, flip = true)

                // the stars: places and sizes once per size
                val starX = FloatArray(SynthwaveTiers.MAX_STARS) { w * S.hash(it, 0) }
                val starY = FloatArray(SynthwaveTiers.MAX_STARS) { horizonY * 0.92f * S.hash(it, 1) }
                val starR = FloatArray(SynthwaveTiers.MAX_STARS) { px * (0.6f + 1.1f * S.hash(it, 5) * S.hash(it, 6)) }

                onDrawBehind {
                    SynthwaveScene.tick.intValue
                    val shift = scroll?.let { S.parallax(it(), 0.06f, maxShift) } ?: 0f
                    val hy = horizonY + shift

                    // 1 the sky and its stars
                    drawRect(sky, Offset.Zero, Size(w, hy + 1f))
                    val depth = S.twinkleDepth(SynthwaveScene.sparkle)
                    val time = SynthwaveScene.clock
                    for (i in 0 until min(d.stars, SynthwaveTiers.MAX_STARS)) {
                        val y = starY[i] + shift * 0.4f
                        if (y > hy - mountainH * 0.4f) continue
                        drawCircle(c.star, starR[i], Offset(starX[i], y), alpha = S.starLight(i, time, depth))
                    }

                    // 2 the sun: halo, rings, the disc with its slits
                    translate(0f, shift * 0.7f) {
                        drawCircle(halo, sunR * 2.2f, Offset(sunX, sunY), alpha = S.sunGlow(SynthwaveScene.glow) * SynthwaveColors.HALO_CORE / S.GLOW_PEAK)
                        drawCircle(c.halo, sunR * 1.32f, Offset(sunX, sunY), alpha = 0.16f, style = ring)
                        drawCircle(c.halo, sunR * 1.62f, Offset(sunX, sunY), alpha = 0.09f, style = ring)
                        drawPath(sunPath, sun)
                        clipPath(sunPath) {
                            for (i in 0 until d.slits) {
                                val top = sunY + sunR * S.slitTop(i, d.slits, SynthwaveScene.slits)
                                val tall = sunR * S.slitHeight(i, d.slits, SynthwaveScene.slits)
                                if (tall < 0.5f) continue
                                // the sky shows through the slit (the same brush, so it matches)
                                drawRect(sky, Offset(sunX - sunR, top), Size(sunR * 2f, tall))
                            }
                        }
                    }

                    // 3 the wireframe range, back to front, each line stretching with its band
                    translate(0f, shift) {
                        for (r in 0 until d.ridges) {
                            val near = if (d.ridges > 1) r.toFloat() / (d.ridges - 1) else 1f
                            val stretch = 1f + SynthwaveScene.sway[r]
                            scale(1f, stretch, Offset(0f, horizonY)) {
                                drawPath(ridgeBodies[r], lerp(c.ridgeBodyFar, c.ridgeBodyNear, near))
                                val line = lerp(c.ridgeFar, c.ridgeNear, near)
                                if (d.glowLines) drawPath(ridgeLines[r], line, alpha = 0.18f, style = ridgeGlow)
                                drawPath(ridgeLines[r], line, alpha = 0.55f + 0.4f * near, style = ridgeStroke)
                            }
                        }
                    }

                    // 4 the floor and its neon grid, driving towards the viewer
                    drawRect(floor, Offset(0f, hy), Size(w, h - hy))
                    // the sun's light on the floor (below the horizon only)
                    translate(0f, shift) {
                        drawRect(reflection, Offset(sunX - sunR * 1.2f, horizonY), Size(sunR * 2.4f, sunR * 1.2f), alpha = 0.6f + 0.4f * SynthwaveScene.glow)
                    }
                    val floorH = h - hy
                    val spacing = w * 0.2f
                    val farY = hy + floorH * S.ROW_MIN
                    val columns = d.columns
                    for (j in -columns..columns) {
                        val x0 = S.columnX(j, S.ROW_MIN, sunX, spacing)
                        val x1 = S.columnX(j, 1f, sunX, spacing)
                        drawLine(columnBrush, Offset(x0, farY), Offset(x1, h), strokeWidth = 1.1f * px)
                    }
                    for (k in 0..d.rows) {
                        val at = S.rowAt(k, SynthwaveScene.drive)
                        if (at > 1.02f) continue
                        if (at < S.ROW_MIN) break
                        val y = hy + floorH * at
                        val a = S.rowAlpha(at)
                        val color = lerp(c.gridFar, c.grid, S.smooth(0.05f, 0.4f, at))
                        if (d.glowLines) drawLine(color, Offset(0f, y), Offset(w, y), strokeWidth = 4f * px, alpha = 0.16f * a)
                        drawLine(color, Offset(0f, y), Offset(w, y), strokeWidth = px * (0.7f + 1.1f * at), alpha = 0.85f * a, cap = StrokeCap.Butt)
                    }

                    // 5 the haze and the line of the horizon
                    drawRect(haze, Offset(0f, hy - hazeHalf), Size(w, hazeHalf * 2f))
                    drawLine(c.horizon, Offset(0f, hy), Offset(w, hy), strokeWidth = 1.4f * px, alpha = 0.85f)

                    // 6 the palms in front, lifted a little more than the rest
                    translate(0f, shift * 1.6f) { drawPath(palms, c.palm) }

                    // 7 the veil of the shared screens
                    if (veil > 0f) drawRect(c.skyTop, alpha = veil)
                }
            },
    )
}

/**
 * A palm silhouette into [path] (added, not reset): a leaning, tapering trunk from its foot at
 * [baseX], [baseY] up [height], and nine drooping fronds from its crown. [flip] mirrors it (the
 * palms on the right lean to the left).
 */
fun synthwavePalm(path: Path, baseX: Float, baseY: Float, height: Float, lean: Float, flip: Boolean) {
    val dir = if (flip) -1f else 1f
    val topX = baseX + dir * height * lean
    val topY = baseY - height
    // the trunk: a quadratic curve, wide at the foot, narrow at the crown
    val ctrlX = baseX + dir * height * lean * 0.15f
    val ctrlY = baseY - height * 0.55f
    val n = 14
    val foot = height * 0.035f
    val neck = height * 0.014f
    for (side in 0..1) {
        for (s in 0..n) {
            val t = if (side == 0) s.toFloat() / n else 1f - s.toFloat() / n
            val u = 1f - t
            val x = u * u * baseX + 2f * u * t * ctrlX + t * t * topX
            val y = u * u * baseY + 2f * u * t * ctrlY + t * t * topY
            val half = foot + (neck - foot) * t
            val off = if (side == 0) -half else half
            if (side == 0 && s == 0) path.moveTo(x + off, y) else path.lineTo(x + off, y)
        }
    }
    path.close()
    // the fronds: drooping leaves, longer to the sides
    val fronds = 9
    for (f in 0 until fronds) {
        val a = (-PI.toFloat() * 0.95f) + (PI.toFloat() * 0.9f) * f / (fronds - 1)
        val length = height * (0.36f + 0.12f * kotlin.math.abs(cos(a)))
        val dx = cos(a) * dir
        val dy = sin(a)
        val droop = length * (0.32f + 0.18f * kotlin.math.abs(cos(a)))
        val width = length * 0.11f
        val m = 10
        for (side in 0..1) {
            for (s in 0..m) {
                val t = if (side == 0) s.toFloat() / m else 1f - s.toFloat() / m
                val x = topX + dx * length * t
                val y = topY + dy * length * t * 0.55f + droop * t * t
                // the leaf's normal (roughly): across its direction
                val nx = -dy
                val ny = dx
                val half = width * sin(PI.toFloat() * t) * (if (side == 0) 1f else -1f)
                if (side == 0 && s == 0) path.moveTo(x, y) else path.lineTo(x + nx * half, y + ny * half)
            }
        }
        path.close()
    }
}
