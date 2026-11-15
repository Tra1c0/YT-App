package com.github.libretube.ui.yt.ambilight

import com.github.libretube.helpers.YtDesign
import kotlin.math.min
import kotlin.math.roundToLong

/*
 * YT: when and how often the ambilight samples, and how strongly each design glows where. Pure
 * Kotlin (tested on the JVM); the Android side (YtAmbilight) only feeds in what it reads.
 */

/** Where the player is, as far as the ambilight cares. */
enum class YtAmbilightPlace {
    /** The full video page. */
    OPEN,

    /** Between the page and the mini player (a drag, the minimize animation). */
    MOVING,

    /** The mini player docked on the bar. */
    DOCKED,

    /** Fullscreen (the player in its dialog). */
    FULLSCREEN,

    /** Not on screen: the app in the background, picture-in-picture, the page stopped. */
    HIDDEN,
}

/** The ambilight switches of the settings ("Darstellung"). */
data class YtAmbilightSettings(
    val enabled: Boolean = false,
    /** Strength, 1 = the default (slider 60 of 10..100). */
    val intensity: Float = 1f,
    /** "Auch Leiste & Hintergrund": nav bar, screen edges, page background. */
    val chrome: Boolean = true,
    /** "Im Takt": Synthwave and Frequency pulse with the sound. */
    val beat: Boolean = true,
    /** Letterbox and pillarbox bars of the player glow (windowed and fullscreen). */
    val bars: Boolean = true,
) {
    companion object {
        const val INTENSITY_DEFAULT = 60f

        /** The slider's value (10..100) as a strength factor. */
        fun intensityOf(slider: Float): Float =
            (if (slider.isFinite()) slider else INTENSITY_DEFAULT).coerceIn(10f, 100f) / INTENSITY_DEFAULT
    }
}

/**
 * How a design glows: the strength (peak alpha before the contrast guard) of each place and the
 * shape. [page] glow under the player on the video page; [wash] the page and screen background
 * (with "Leiste & Hintergrund"); [edges] the screen edges and the header while docked; [nav]
 * the nav bar's edge; [halo] around the docked mini player. [spread] scales how far the glows
 * reach, [haloDp] the halo's width. [glass] tints the glass bar itself; [beat] pulses with the
 * sound (Synthwave, Frequency).
 */
data class YtAmbilightProfile(
    val page: Float,
    val wash: Float = 0f,
    val edges: Float = 0f,
    val nav: Float = 0f,
    val halo: Float = 0f,
    val spread: Float = 1f,
    val haloDp: Float = 18f,
    val glass: Boolean = false,
    val beat: Boolean = false,
) {
    companion object {
        /** The classic look: a glow behind (under) the player only. */
        val CLASSIC = YtAmbilightProfile(page = 0.5f)

        private val DARK = YtAmbilightProfile(page = 0.6f, wash = 0.07f, edges = 0.32f, nav = 0.42f, halo = 0.55f)
        private val LIGHT = YtAmbilightProfile(page = 0.45f, wash = 0.06f, edges = 0.26f, nav = 0.32f, halo = 0.45f, spread = 0.9f)

        fun of(design: YtDesign): YtAmbilightProfile = when (design) {
            YtDesign.STANDARD -> CLASSIC
            // cinematic: a long, strong spill under the picture
            YtDesign.AFTER -> DARK.copy(page = 0.7f, spread = 1.25f, edges = 0.36f)
            YtDesign.MOVO, YtDesign.SIDE, YtDesign.YOUTUBE -> DARK
            YtDesign.SHAPES -> DARK.copy(haloDp = 22f)
            // print: restrained
            YtDesign.EDITORIAL -> LIGHT.copy(page = 0.35f, edges = 0.18f, nav = 0.22f, halo = 0.32f, spread = 0.8f)
            // puffy, round surfaces: a wider, softer halo
            YtDesign.CLAY, YtDesign.KIESEL -> LIGHT.copy(haloDp = 24f)
            YtDesign.POP -> LIGHT.copy(page = 0.5f, nav = 0.36f)
            // glass: the colour tints the glass bar too
            YtDesign.GLASS -> LIGHT.copy(glass = true, nav = 0.3f)
            YtDesign.FROST -> DARK.copy(glass = true, page = 0.5f, nav = 0.3f)
            // sound designs: neon, and in time with the music
            YtDesign.FREQUENCY -> DARK.copy(page = 0.65f, beat = true)
            YtDesign.SYNTHWAVE -> DARK.copy(page = 0.65f, edges = 0.36f, beat = true)
            else -> DARK
        }
    }
}

/** What the rate rules read. [thermal]: PowerManager's thermal status (0 none .. 6 shutdown). */
data class YtAmbilightRateInput(
    val playing: Boolean,
    val place: YtAmbilightPlace,
    val motion: Boolean,
    val powerSave: Boolean,
    val thermal: Int,
    /** How busy the picture is (YtAmbilightScene.activity). */
    val activity: Float,
    /** Mean CPU time of a sample, ms. */
    val costMs: Float,
)

object YtAmbilightRules {
    const val MIN_HZ = 4f
    const val MAX_HZ = 12f

    /** The idle check (place, pause, settings) while nothing is sampled. */
    const val IDLE_MS = 500L

    /** Fallback (storyboard, thumbnail): seldom, a still per few seconds is enough. */
    const val FALLBACK_MS = 2000L

    /** Thermal status from which the ambilight stops sampling (PowerManager.THERMAL_STATUS_CRITICAL). */
    const val THERMAL_STOP = 4

    /** Activity (mean Oklab distance per sample) at which the rate reaches [MAX_HZ]. */
    const val BUSY = 0.06f

    /**
     * How often to sample (Hz) now, 0 for not at all (paused: one sample is taken on the pause
     * and then none; power saver and a hot phone keep the last colour). The rate follows the
     * picture: 4 Hz for a still scene up to 12 Hz for a busy one; lower docked, with animations
     * off, when warm, and when a sample costs much.
     */
    fun rateHz(input: YtAmbilightRateInput): Float = with(input) {
        rateHz(playing, place, motion, powerSave, thermal, activity, costMs)
    }

    /** [rateHz] with plain values (the engine's loop allocates nothing). */
    fun rateHz(
        playing: Boolean,
        place: YtAmbilightPlace,
        motion: Boolean,
        powerSave: Boolean,
        thermal: Int,
        activity: Float,
        costMs: Float,
    ): Float {
        if (!playing || place == YtAmbilightPlace.HIDDEN) return 0f
        if (powerSave || thermal >= THERMAL_STOP) return 0f
        val busy = (activity / BUSY).coerceIn(0f, 1f)
        var hz = MIN_HZ + (MAX_HZ - MIN_HZ) * busy
        // animations off: the colour changes per scene only, so the cuts are all that count
        if (!motion) hz = min(hz, 3f)
        if (place == YtAmbilightPlace.DOCKED) hz = min(hz, 6f)
        hz = when (thermal) {
            0 -> hz
            1 -> min(hz, 8f)
            2 -> min(hz, 4f)
            else -> min(hz, 2f)
        }
        if (costMs > 16f) {
            hz = min(hz, 3f)
        } else if (costMs > 8f) {
            hz = min(hz, 6f)
        }
        return hz
    }

    /** [rateHz] as the wait between samples, ms; 0 = no periodic sampling. */
    fun intervalMs(input: YtAmbilightRateInput): Long = intervalOf(rateHz(input))

    /** The wait between samples at [hz], ms; 0 for 0 Hz. */
    fun intervalOf(hz: Float): Long = if (hz <= 0f) 0L else (1000f / hz).roundToLong()

    /**
     * Whether anything would show the colours at [place], so sampling is worth it. [barsShown]:
     * the player has letterbox or pillarbox bars now.
     */
    fun shows(profile: YtAmbilightProfile, settings: YtAmbilightSettings, place: YtAmbilightPlace, barsShown: Boolean): Boolean {
        if (!settings.enabled) return false
        val bars = settings.bars && barsShown
        return when (place) {
            YtAmbilightPlace.OPEN, YtAmbilightPlace.MOVING -> profile.page > 0f || bars
            YtAmbilightPlace.DOCKED -> profile.halo > 0f || bars ||
                (settings.chrome && (profile.edges > 0f || profile.nav > 0f || profile.glass))
            YtAmbilightPlace.FULLSCREEN -> bars
            YtAmbilightPlace.HIDDEN -> false
        }
    }

    /** Whether the glow pulses with the sound now. */
    fun beats(profile: YtAmbilightProfile, settings: YtAmbilightSettings, motion: Boolean, playing: Boolean): Boolean =
        settings.enabled && settings.beat && profile.beat && motion && playing

    /** How much the beat (0..1, after the flash guard) lifts the glow: subtle. */
    const val BEAT_DEPTH = 0.25f

    fun beatGain(beat: Float): Float = 1f + BEAT_DEPTH * beat.coerceIn(0f, 1f)

    /**
     * PixelCopy results that count against the copy: after [MAX_FAILURES] in a row the
     * fallback takes over (storyboard, thumbnail), and PixelCopy is tried again every [RETRY_MS].
     * "No data yet" (no frame decoded) needs [MAX_NO_DATA] in a row, as it is normal at a start.
     */
    const val MAX_FAILURES = 3
    const val MAX_NO_DATA = 12
    const val RETRY_MS = 10_000L

    /** True when the copy should give way to the fallback. */
    fun useFallback(failures: Int, noData: Int): Boolean = failures >= MAX_FAILURES || noData >= MAX_NO_DATA
}
