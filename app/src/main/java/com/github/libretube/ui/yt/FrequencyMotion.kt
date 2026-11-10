package com.github.libretube.ui.yt

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * YT: the motion language of the design "Frequency", pure Kotlin (tested on the JVM,
 * FrequencyMotionTest). FrequencyPulse runs these once per display frame; the shapes read the
 * results while drawing.
 *
 * Two kinds of channel, kept apart on purpose:
 * - motion (size, position, rotation, shape, bar heights) follows the sound quickly, from the
 *   engine's unguarded values (YtSound.motion / YtSound.bands);
 * - brightness (glow, veils, the time bar's colour, the aura's light) comes from the guarded
 *   frame (YtSound.frame, ≤ 3 swings a second) and FrequencyPulseMath.follow's slew limit, and
 *   the shine sweeps come at most every [FrequencyShine.MIN_GAP_S].
 */

/**
 * Stretches a channel to the range it moved in lately, so music whose level hardly changes still
 * moves the shapes the whole way: a ceiling that jumps up and sinks back in [fallS], a floor that
 * drops at once and rises in [riseS], at least [minSpan] apart. The output mixes the stretched
 * value ([mix]) with the plain one, so a loud passage still reads louder than a quiet one.
 * Silence (0 in) stays 0 out. Frame-rate independent.
 */
class FrequencyStretch(
    private val fallS: Float = 1.6f,
    private val riseS: Float = 1.6f,
    private val minSpan: Float = 0.25f,
    private val mix: Float = 0.7f,
) {
    private var hi = 0f
    private var lo = 0f

    var value = 0f
        private set

    fun reset() {
        hi = 0f
        lo = 0f
        value = 0f
    }

    fun step(input: Float, dt: Float): Float {
        val x = FrequencyPulseMath.clamp01(input)
        val d = if (dt.isNaN()) 0f else dt.coerceIn(0f, FrequencyPulseMath.MAX_DT)
        hi = if (x >= hi) x else hi + (x - hi) * (1f - exp(-d / fallS))
        lo = if (x <= lo) x else lo + (x - lo) * (1f - exp(-d / riseS))
        val span = max(hi - lo, minSpan)
        val stretched = ((x - lo) / span).coerceIn(0f, 1f)
        value = FrequencyPulseMath.clamp01(mix * stretched + (1f - mix) * x)
        return value
    }
}

/**
 * Finds the hits in the punch channel: a kick when it rises by at least [minRise] within a frame
 * to at least [minLevel], and not within [refractoryS] of the last kick. Returns the kick's
 * strength (0 = none) for the springs.
 */
class FrequencyKick(
    private val minRise: Float = 0.1f,
    private val minLevel: Float = 0.22f,
    private val refractoryS: Float = 0.12f,
) {
    private var last = 0f
    private var since = 10f

    fun reset() {
        last = 0f
        since = 10f
    }

    fun step(punch: Float, dt: Float): Float {
        val p = FrequencyPulseMath.clamp01(punch)
        since += if (dt.isNaN()) 0f else dt.coerceAtLeast(0f)
        val rise = p - last
        last = p
        if (rise >= minRise && p >= minLevel && since >= refractoryS) {
            since = 0f
            return p
        }
        return 0f
    }
}

/**
 * A bouncy spring for the hits: kicked ([kick]) it jumps up, overshoots below rest and settles
 * ([hz] natural frequency, [damping] ratio < 1). Its recent positions are kept, so elements can
 * read it a little late ([at]) and bounce one after another. Semi-implicit Euler in sub-steps
 * of at most 1/240 s: stable at any frame rate. No allocation per step.
 */
class FrequencySpring(
    private val hz: Float = 3.2f,
    private val damping: Float = 0.3f,
    historyS: Float = 0.6f,
    maxFps: Int = 144,
) {
    private val omega = 2f * PI.toFloat() * hz
    private val size = (historyS * maxFps).toInt().coerceAtLeast(8)
    private val times = FloatArray(size)
    private val values = FloatArray(size)
    private var head = -1
    private var count = 0
    private var time = 0f

    var x = 0f
        private set
    var v = 0f
        private set

    fun reset() {
        x = 0f
        v = 0f
        head = -1
        count = 0
        time = 0f
    }

    /** Whether it has come to rest. */
    val resting: Boolean get() = abs(x) < REST && abs(v) < REST * omega

    /** Gives it a hit of [strength] (0..1): its peak comes out at about that height. */
    fun kick(strength: Float) {
        val s = FrequencyPulseMath.clamp01(strength)
        // an underdamped spring kicked from rest peaks near v0/ω·e^(−ζ·atan(√(1−ζ²)/ζ)/√(1−ζ²))
        v += s * omega * KICK_GAIN
    }

    fun step(dt: Float) {
        val d = if (dt.isNaN()) 0f else dt.coerceIn(0f, FrequencyPulseMath.MAX_DT)
        var left = d
        while (left > 0f) {
            val h = min(left, 1f / 240f)
            v += (-omega * omega * x - 2f * damping * omega * v) * h
            x += v * h
            left -= h
        }
        x = x.coerceIn(-MAX_DOWN, MAX_UP)
        if (resting) {
            x = 0f
            v = 0f
        }
        time += d
        head = (head + 1) % size
        times[head] = time
        values[head] = x
        if (count < size) count++
    }

    /** The position [delayS] seconds ago (linear between the kept steps; the oldest beyond). */
    fun at(delayS: Float): Float {
        if (count == 0) return x
        if (delayS <= 0f) return values[head]
        val want = time - delayS
        var i = head
        repeat(count - 1) {
            val prev = (i - 1 + size) % size
            if (times[prev] <= want) {
                val t0 = times[prev]
                val t1 = times[i]
                val f = if (t1 > t0) ((want - t0) / (t1 - t0)).coerceIn(0f, 1f) else 1f
                return values[prev] + (values[i] - values[prev]) * f
            }
            i = prev
        }
        return values[i]
    }

    companion object {
        const val REST = 0.002f
        const val MAX_UP = 1.2f
        const val MAX_DOWN = 0.6f

        /** Scales a kick so a full one peaks at about 1 with the default damping. */
        const val KICK_GAIN = 1.45f
    }
}

/**
 * The light that sweeps across cards, buttons and the seek bar: a sweep starts on a bright
 * onset (a hit, or the highs jumping) and runs [durationS]; a new one never starts within
 * [MIN_GAP_S] of the last, so each spot of the screen lights at most 1/MIN_GAP_S ≈ 2.2 times a
 * second (below the 3 flashes a second of WCAG 2.3.1).
 */
class FrequencyShine(private val durationS: Float = 0.55f) {
    private var since = 10f

    /** Where the current sweep is, 0..1 (≥ 1: none running). */
    var progress = 1f
        private set

    /** The strength of the current sweep (0..1). */
    var strength = 0f
        private set

    fun reset() {
        since = 10f
        progress = 1f
        strength = 0f
    }

    /** Whether a sweep runs now. */
    val active: Boolean get() = progress < 1f

    /** One frame: [trigger] is the strength of an onset now (0 = none). True when one started. */
    fun step(trigger: Float, dt: Float): Boolean {
        val d = if (dt.isNaN()) 0f else dt.coerceIn(0f, FrequencyPulseMath.MAX_DT)
        since += d
        if (progress < 1f) progress = min(1f, progress + d / durationS)
        val t = FrequencyPulseMath.clamp01(trigger)
        if (t >= MIN_TRIGGER && since >= MIN_GAP_S) {
            since = 0f
            progress = 0f
            strength = t
            return true
        }
        return false
    }

    /**
     * The sweep for element [index] (each starts [STAGGER] later, a cascade): its position
     * 0..1 across the element, or -1 while it is not on it.
     */
    fun positionFor(index: Int): Float {
        val p = (progress - STAGGER * index.coerceIn(0, MAX_STAGGERED)) / (1f - STAGGER * MAX_STAGGERED)
        return if (p < 0f || p > 1f || progress >= 1f) -1f else p
    }

    /** The sweep's alpha at [position] of its run (soft in, soft out), at most [ALPHA_MAX]. */
    fun alphaAt(position: Float): Float {
        if (position < 0f || position > 1f) return 0f
        return ALPHA_MAX * strength.coerceAtLeast(0.5f) * sin(PI.toFloat() * position)
    }

    companion object {
        const val MIN_GAP_S = 0.45f
        const val MIN_TRIGGER = 0.3f
        const val ALPHA_MAX = 0.32f
        const val STAGGER = 0.06f
        const val MAX_STAGGERED = 4
    }
}

/**
 * Shapes that turn into each other: every shape here is a radius around the centre per angle
 * (1 = the outer edge), so two of them blend by mixing their radii, and a morph is never more
 * than a lerp: no self-crossing, no allocation, any number of points.
 */
object FrequencyMorph {
    const val CIRCLE = 0
    const val COOKIE = 1
    const val BLOB = 2
    const val SQUIRCLE = 3
    const val SHAPES = 4

    /** Squircle (|x|⁴ + |y|⁴ = 1) radius at 45°, so the corners reach 1. */
    private val SQUIRCLE_PEAK = 1f / sqrt(sqrt(0.5f))

    /** How deep a blob's bumps go (fraction of the radius). */
    const val BLOB_DEPTH = 0.16f

    /**
     * The radius (0..1) of [shape] at [angle] (radians): a cookie of [lobes] and [depth], a blob
     * whose bumps drift with [flow] (radians), turned by [turn] (radians).
     */
    fun radius(shape: Int, angle: Float, lobes: Int, depth: Float, flow: Float, turn: Float): Float {
        val a = angle - turn
        return when (Math.floorMod(shape, SHAPES)) {
            CIRCLE -> 1f
            COOKIE -> FrequencyPulseMath.cookieRadius(a, lobes, depth)
            BLOB -> {
                val w = 0.6f * sin(2f * a + flow) + 0.4f * sin(3f * a - 1.3f * flow + 1f)
                1f - BLOB_DEPTH * (0.5f + 0.5f * w)
            }
            else -> {
                val c = cos(a)
                val s = sin(a)
                val q = c * c * c * c + s * s * s * s
                (1f / sqrt(sqrt(q))) / SQUIRCLE_PEAK
            }
        }
    }

    /** The shape [phase] starts from (phase counts shapes, cycling). */
    fun from(phase: Float): Int = Math.floorMod(floor(phase).toInt(), SHAPES)

    /** The shape [phase] goes to. */
    fun to(phase: Float): Int = Math.floorMod(floor(phase).toInt() + 1, SHAPES)

    /**
     * How far [phase] has morphed from [from] to [to]: each shape holds [HOLD] of its turn,
     * then eases over (smoothstep) into the next.
     */
    fun progress(phase: Float): Float {
        if (phase.isNaN()) return 0f
        val f = phase - floor(phase)
        val t = ((f - HOLD) / (1f - HOLD)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /** The blended radius at [angle] for [phase]: the morph between two shapes. */
    fun radiusAt(phase: Float, angle: Float, lobes: Int, depth: Float, flow: Float, turn: Float): Float {
        val p = progress(phase)
        val a = radius(from(phase), angle, lobes, depth, flow, turn)
        if (p <= 0f) return a
        val b = radius(to(phase), angle, lobes, depth, flow, turn)
        return a + (b - a) * p
    }

    /** Each shape stays this share of its turn before it morphs on. */
    const val HOLD = 0.35f
}

/**
 * How much of the motion language a phone gets, picked at runtime by [FrequencyTiers.pick].
 * [blobs] metaball lights in the background, [bars] visualizer bars in the header, whether
 * shapes morph, bounce and shine, whether the soft spectrum wave sits behind the home, and
 * [amplitude] scales every motion amplitude.
 */
enum class FrequencyTier(
    val blobs: Int,
    val bars: Int,
    val morph: Boolean,
    val bounce: Boolean,
    val shine: Boolean,
    val backgroundWave: Boolean,
    val amplitude: Float,
) {
    /** Everything at the display's rate; the gooey blobs with a RuntimeShader on Android 13+. */
    HIGH(blobs = 5, bars = 16, morph = true, bounce = true, shine = true, backgroundWave = true, amplitude = 1f),

    /** Fewer blobs (gradients, no shader) and bars, at most 60 frames a second. */
    MID(blobs = 3, bars = 12, morph = true, bounce = true, shine = true, backgroundWave = true, amplitude = 0.9f),

    /** Power saver, hot or small phones: shapes breathe and the visualizer runs, half rate. */
    LOW(blobs = 0, bars = 8, morph = false, bounce = false, shine = false, backgroundWave = false, amplitude = 0.7f),
}

/** What the phone says about itself, read by FrequencyPulse every few seconds. */
data class FrequencyDevice(
    val refreshHz: Float,
    val sdk: Int,
    val lowRam: Boolean,
    val totalRamMb: Long,
    val cores: Int,
    val powerSave: Boolean,
    /** PowerManager.THERMAL_STATUS_* (0 none … 6 shutdown), 0 where unknown. */
    val thermal: Int,
)

/** The tier for a phone, whether the blobs use a RuntimeShader, and how many display frames make one step. */
data class FrequencyTierChoice(val tier: FrequencyTier, val shader: Boolean, val frameDivider: Int) {
    /** Steps per second at [refreshHz]. */
    fun stepsPerSecond(refreshHz: Float): Float = refreshHz.coerceAtLeast(1f) / frameDivider
}

object FrequencyTiers {
    const val THERMAL_MODERATE = 2
    const val THERMAL_SEVERE = 3
    const val SHADER_SDK = 33

    /** Pure: the tier for [d]. */
    fun pick(d: FrequencyDevice): FrequencyTierChoice {
        val tier = when {
            d.powerSave || d.thermal >= THERMAL_SEVERE || d.lowRam || d.totalRamMb in 1 until 3_000 -> FrequencyTier.LOW
            d.thermal >= THERMAL_MODERATE || d.totalRamMb in 1 until 6_000 || d.cores in 1 until 6 || d.sdk < 29 -> FrequencyTier.MID
            else -> FrequencyTier.HIGH
        }
        val hz = if (d.refreshHz.isNaN() || d.refreshHz < 1f) 60f else d.refreshHz
        val target = when (tier) {
            FrequencyTier.HIGH -> hz
            FrequencyTier.MID -> min(hz, 60f)
            FrequencyTier.LOW -> min(hz, 60f) / 2f
        }
        // never above the target rate: 90 Hz at a target of 60 steps every second frame
        val divider = max(1, kotlin.math.ceil(hz / target - 0.01f).toInt())
        return FrequencyTierChoice(tier, shader = tier == FrequencyTier.HIGH && d.sdk >= SHADER_SDK, frameDivider = divider)
    }
}

/**
 * The mappings from the motion channels to what is drawn: all clamped, all at rest in silence.
 * Sizes are fractions of the element (or dp the caller converts), so they scale with the screen.
 */
object FrequencyMotionMath {
    /** The most a shape grows with the beat (+ the bounce): 8–20 % as the owner asked. */
    const val SCALE_MAX = 0.2f

    /** The beat's share of a shape's growth; the bounce adds the rest. */
    const val BEAT_SHARE = 0.6f

    /** Turning speed (degrees per second) while sound plays, and what the beat adds. */
    const val TURN_IDLE = 14f
    const val TURN_BEAT = 110f

    /** Morph speed (shapes per second) while sound plays, what the beat and a bounce add. */
    const val MORPH_IDLE = 0.08f
    const val MORPH_BEAT = 0.45f
    const val MORPH_BOUNCE = 0.6f

    /** A bar's height at rest (fraction of its full height) at the ends and the added arch. */
    const val BAR_REST = 0.12f
    const val BAR_ARCH = 0.16f

    /** The rest height of bar [i] of [count]: a calm arch, higher in the middle. */
    fun barRest(i: Int, count: Int): Float {
        if (count <= 0) return BAR_REST
        return BAR_REST + BAR_ARCH * sin(PI.toFloat() * (i.coerceIn(0, count - 1) + 0.5f) / count)
    }

    /** What makes shapes grow: mostly bass, some level (motion values, stretched). */
    fun beatTarget(level: Float, bass: Float): Float =
        FrequencyPulseMath.clamp01(0.65f * FrequencyPulseMath.clamp01(bass) + 0.35f * FrequencyPulseMath.clamp01(level))

    /**
     * Scale of a shape with growth [amount] (≤ [SCALE_MAX]) at [beat] and [bounce] (the spring,
     * may overshoot below 0), times the tier's [amplitude]. Never below 1 − amount/2.
     */
    fun scale(beat: Float, bounce: Float, amount: Float, amplitude: Float = 1f): Float {
        val a = (if (amount.isNaN()) 0f else amount.coerceIn(0f, SCALE_MAX)) * (if (amplitude.isNaN()) 0f else amplitude.coerceIn(0f, 1f))
        val b = if (bounce.isNaN()) 0f else bounce.coerceIn(-FrequencySpring.MAX_DOWN, FrequencySpring.MAX_UP)
        val drive = BEAT_SHARE * FrequencyPulseMath.clamp01(beat) + (1f - BEAT_SHARE) * b
        return 1f + a * drive.coerceIn(-0.5f, 1f)
    }

    /** Degrees per second the shapes turn: still in silence, faster with the beat. */
    fun turnSpeed(live: Boolean, beat: Float): Float =
        if (!live) 0f else TURN_IDLE + TURN_BEAT * FrequencyPulseMath.clamp01(beat)

    /** Shapes per second the morph moves on: still in silence. */
    fun morphSpeed(live: Boolean, beat: Float, bounce: Float): Float =
        if (!live) 0f else MORPH_IDLE + MORPH_BEAT * FrequencyPulseMath.clamp01(beat) +
            MORPH_BOUNCE * (if (bounce.isNaN()) 0f else bounce.coerceIn(0f, 1f))

    /**
     * Bar [i] of [count] from the [bands]: the mean of its share of the bands, lifted a little
     * (so mid-loud sound reaches high), on top of its rest height ([barRest]). 0..1 of the bar's full height.
     */
    fun bar(bands: FloatArray, i: Int, count: Int): Float {
        val rest = barRest(i, count)
        if (count <= 0 || bands.isEmpty()) return rest
        val n = bands.size
        val from = (i.coerceIn(0, count - 1) * n) / count
        val to = max(from + 1, ((i.coerceIn(0, count - 1) + 1) * n) / count)
        var sum = 0f
        for (b in from until min(to, n)) sum += FrequencyPulseMath.clamp01(bands[b])
        val mean = sum / (min(to, n) - from)
        val lifted = sqrt(mean)
        return rest + (1f - rest) * lifted
    }

    /**
     * The blobs' drift: how far blob [i] of [count] sits from the centre (fraction of the
     * screen's shorter side) at [pull] (the bass, 1 = all together: they merge) and [spread]
     * (the mids push them apart: they split).
     */
    fun blobDistance(pull: Float, spread: Float): Float =
        (0.34f - 0.22f * FrequencyPulseMath.clamp01(pull) + 0.12f * FrequencyPulseMath.clamp01(spread)).coerceIn(0.08f, 0.46f)
}
