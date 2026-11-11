package com.github.libretube.ui.yt.news

import com.github.libretube.helpers.YtDesign
import com.github.libretube.ui.yt.YtFeedback
import com.github.libretube.ui.yt.YtHapticStep
import com.github.libretube.ui.yt.YtHapticTexture
import com.github.libretube.ui.yt.YtPrimitive

/** YT: the moments of the News overlay that each design gives a feel of its own. */
enum class YtNewsHaptic(
    /** What plays where the design's steps cannot (before Android 11, "Eigene Haptik" off). */
    val fallback: Int,
) {
    /** A pinch on the home is claimed: the card starts to grow into the window. */
    CLAIM(YtFeedback.GESTURE_START),

    /** The pinch went far enough: the window opens. */
    OPEN(YtFeedback.CONFIRM),

    /** The pager passes into another card. */
    PAGE(YtFeedback.SEGMENT_TICK),

    /** A card swiped up went to the queue. */
    QUEUE(YtFeedback.CONFIRM),

    /** The window closes (pinched together, swiped down, back, the cross). */
    CLOSE(YtFeedback.GESTURE_END),

    /** Something did not take: a pinch let go too early, a card the queue refused. */
    REJECT(YtFeedback.REJECT),
}

/**
 * YT: the feel of the News overlay per design, in the language of the design's mini player
 * (YtMini*Textures): the projector of After rattles, Clay's dough thuds, Editorial's typewriter
 * strikes, and so on. Every design of [YtDesign] has its own (an exhaustive `when`, and
 * YtNewsTexturesTest checks the names); the classic design, which has no News, plays the system's
 * touch feedback only. Pure, for the JVM tests; where the primitives cannot play, each moment
 * falls back to [YtNewsHaptic.fallback]. Names: "news_<moment>_<design key>" for the emulator log.
 */
object YtNewsTextures {
    fun of(design: YtDesign, moment: YtNewsHaptic): YtHapticTexture {
        val steps = when (design) {
            YtDesign.STANDARD -> emptyList()
            YtDesign.YOUTUBE -> youtube(moment)
            YtDesign.MOVO -> movo(moment)
            YtDesign.AFTER -> after(moment)
            YtDesign.SIDE -> side(moment)
            YtDesign.CLAY -> clay(moment)
            YtDesign.EDITORIAL -> editorial(moment)
            YtDesign.GLASS -> glass(moment)
            YtDesign.POP -> pop(moment)
            YtDesign.SHAPES -> shapes(moment)
            YtDesign.FROST -> frost(moment)
            YtDesign.KIESEL -> kiesel(moment)
            YtDesign.FREQUENCY -> frequency(moment)
            YtDesign.SYNTHWAVE -> synthwave(moment)
        }
        return YtHapticTexture("news_${moment.name.lowercase()}_${design.key}", steps, moment.fallback)
    }

    private fun s(primitive: Int, scale: Float, delayMs: Int = 0) = YtHapticStep(primitive, scale, delayMs)

    /** YouTube neu, the red seam: clicks where something is decided, light ticks for what only moves. */
    private fun youtube(m: YtNewsHaptic) = when (m) {
        YtNewsHaptic.CLAIM -> listOf(s(YtPrimitive.TICK, 0.4f))
        YtNewsHaptic.OPEN -> listOf(s(YtPrimitive.CLICK, 0.6f), s(YtPrimitive.TICK, 0.3f, 50))
        YtNewsHaptic.PAGE -> listOf(s(YtPrimitive.TICK, 0.45f))
        YtNewsHaptic.QUEUE -> listOf(s(YtPrimitive.CLICK, 0.55f), s(YtPrimitive.TICK, 0.35f, 60))
        YtNewsHaptic.CLOSE -> listOf(s(YtPrimitive.TICK, 0.5f))
        YtNewsHaptic.REJECT -> listOf(s(YtPrimitive.TICK, 0.4f), s(YtPrimitive.TICK, 0.4f, 50))
    }

    /** Movo, tiles knocking into place: two clicks, quick rises as a tile flips up. */
    private fun movo(m: YtNewsHaptic) = when (m) {
        YtNewsHaptic.CLAIM -> listOf(s(YtPrimitive.QUICK_RISE, 0.45f))
        YtNewsHaptic.OPEN -> listOf(s(YtPrimitive.CLICK, 0.7f), s(YtPrimitive.CLICK, 0.4f, 70))
        YtNewsHaptic.PAGE -> listOf(s(YtPrimitive.CLICK, 0.35f))
        YtNewsHaptic.QUEUE -> listOf(s(YtPrimitive.CLICK, 0.7f), s(YtPrimitive.TICK, 0.4f, 50))
        YtNewsHaptic.CLOSE -> listOf(s(YtPrimitive.QUICK_FALL, 0.35f), s(YtPrimitive.CLICK, 0.4f, 40))
        YtNewsHaptic.REJECT -> listOf(s(YtPrimitive.CLICK, 0.5f), s(YtPrimitive.CLICK, 0.5f, 60))
    }

    /** After, a projector: the claw rattles the film, the gate locks with a thud. */
    private fun after(m: YtNewsHaptic) = when (m) {
        YtNewsHaptic.CLAIM -> listOf(s(YtPrimitive.TICK, 0.3f), s(YtPrimitive.TICK, 0.3f, 35), s(YtPrimitive.TICK, 0.3f, 35))
        YtNewsHaptic.OPEN -> listOf(s(YtPrimitive.TICK, 0.35f), s(YtPrimitive.TICK, 0.35f, 35), s(YtPrimitive.THUD, 0.6f, 40))
        YtNewsHaptic.PAGE -> listOf(s(YtPrimitive.TICK, 0.4f), s(YtPrimitive.TICK, 0.25f, 35))
        YtNewsHaptic.QUEUE -> listOf(s(YtPrimitive.TICK, 0.3f), s(YtPrimitive.TICK, 0.3f, 35), s(YtPrimitive.CLICK, 0.6f, 40))
        YtNewsHaptic.CLOSE -> listOf(s(YtPrimitive.THUD, 0.45f))
        YtNewsHaptic.REJECT -> listOf(s(YtPrimitive.CLICK, 0.6f), s(YtPrimitive.THUD, 0.4f, 30))
    }

    /** Side, the speech bubble: gentle; where something arrives, a heartbeat. */
    private fun side(m: YtNewsHaptic) = when (m) {
        YtNewsHaptic.CLAIM -> listOf(s(YtPrimitive.SLOW_RISE, 0.35f))
        YtNewsHaptic.OPEN -> listOf(s(YtPrimitive.TICK, 0.35f), s(YtPrimitive.CLICK, 0.55f, 120))
        YtNewsHaptic.PAGE -> listOf(s(YtPrimitive.TICK, 0.35f))
        // a lighter heartbeat: the card arrives in the queue, not in front of you
        YtNewsHaptic.QUEUE -> listOf(s(YtPrimitive.TICK, 0.3f), s(YtPrimitive.TICK, 0.5f, 120))
        YtNewsHaptic.CLOSE -> listOf(s(YtPrimitive.QUICK_FALL, 0.3f))
        YtNewsHaptic.REJECT -> listOf(s(YtPrimitive.TICK, 0.4f), s(YtPrimitive.TICK, 0.25f, 70))
    }

    /** Clay, a ball of dough: soft and heavy, low ticks as it is kneaded, deep thuds. */
    private fun clay(m: YtNewsHaptic) = when (m) {
        YtNewsHaptic.CLAIM -> listOf(s(YtPrimitive.LOW_TICK, 0.5f), s(YtPrimitive.LOW_TICK, 0.4f, 80))
        YtNewsHaptic.OPEN -> listOf(s(YtPrimitive.THUD, 0.7f), s(YtPrimitive.TICK, 0.25f, 60))
        YtNewsHaptic.PAGE -> listOf(s(YtPrimitive.LOW_TICK, 0.55f))
        YtNewsHaptic.QUEUE -> listOf(s(YtPrimitive.THUD, 0.6f), s(YtPrimitive.TICK, 0.3f, 70))
        YtNewsHaptic.CLOSE -> listOf(s(YtPrimitive.THUD, 0.45f))
        YtNewsHaptic.REJECT -> listOf(s(YtPrimitive.THUD, 0.4f), s(YtPrimitive.THUD, 0.4f, 70))
    }

    /** Editorial, a typewriter: short hard strikes, no rises, no falls. */
    private fun editorial(m: YtNewsHaptic) = when (m) {
        YtNewsHaptic.CLAIM -> listOf(s(YtPrimitive.TICK, 0.5f))
        YtNewsHaptic.OPEN -> listOf(s(YtPrimitive.CLICK, 0.8f))
        YtNewsHaptic.PAGE -> listOf(s(YtPrimitive.TICK, 0.55f), s(YtPrimitive.TICK, 0.3f, 28))
        YtNewsHaptic.QUEUE -> listOf(s(YtPrimitive.CLICK, 0.8f), s(YtPrimitive.TICK, 0.35f, 28))
        YtNewsHaptic.CLOSE -> listOf(s(YtPrimitive.CLICK, 0.5f))
        YtNewsHaptic.REJECT -> listOf(s(YtPrimitive.CLICK, 0.6f), s(YtPrimitive.CLICK, 0.6f, 28), s(YtPrimitive.CLICK, 0.6f, 28))
    }

    /** Glas, liquid: low ticks and after-rings, a drop that merges with a click. */
    private fun glass(m: YtNewsHaptic) = when (m) {
        YtNewsHaptic.CLAIM -> listOf(s(YtPrimitive.LOW_TICK, 0.45f))
        YtNewsHaptic.OPEN -> listOf(s(YtPrimitive.SLOW_RISE, 0.35f), s(YtPrimitive.CLICK, 0.5f, 80))
        YtNewsHaptic.PAGE -> listOf(s(YtPrimitive.TICK, 0.35f), s(YtPrimitive.LOW_TICK, 0.25f, 40))
        YtNewsHaptic.QUEUE -> listOf(s(YtPrimitive.QUICK_RISE, 0.3f), s(YtPrimitive.CLICK, 0.5f, 70))
        YtNewsHaptic.CLOSE -> listOf(s(YtPrimitive.QUICK_FALL, 0.35f))
        YtNewsHaptic.REJECT -> listOf(s(YtPrimitive.CLICK, 0.45f), s(YtPrimitive.LOW_TICK, 0.35f, 50))
    }

    /** Pop, rubbery: the balloon inflates, the sticker slaps on, a jelly landing. */
    private fun pop(m: YtNewsHaptic) = when (m) {
        YtNewsHaptic.CLAIM -> listOf(s(YtPrimitive.QUICK_RISE, 0.6f))
        YtNewsHaptic.OPEN -> listOf(s(YtPrimitive.CLICK, 0.7f), s(YtPrimitive.TICK, 0.4f, 50), s(YtPrimitive.TICK, 0.3f, 60))
        YtNewsHaptic.PAGE -> listOf(s(YtPrimitive.TICK, 0.5f), s(YtPrimitive.TICK, 0.25f, 45))
        YtNewsHaptic.QUEUE -> listOf(s(YtPrimitive.CLICK, 0.7f), s(YtPrimitive.SPIN, 0.4f, 40))
        YtNewsHaptic.CLOSE -> listOf(s(YtPrimitive.QUICK_FALL, 0.45f), s(YtPrimitive.TICK, 0.3f, 50))
        YtNewsHaptic.REJECT -> listOf(s(YtPrimitive.CLICK, 0.6f), s(YtPrimitive.QUICK_FALL, 0.4f, 40), s(YtPrimitive.CLICK, 0.35f, 50))
    }

    /** Formen, a shape sorter: spins and detents, a click and a knock. */
    private fun shapes(m: YtNewsHaptic) = when (m) {
        YtNewsHaptic.CLAIM -> listOf(s(YtPrimitive.SPIN, 0.35f))
        YtNewsHaptic.OPEN -> listOf(s(YtPrimitive.CLICK, 0.6f), s(YtPrimitive.THUD, 0.35f, 50))
        YtNewsHaptic.PAGE -> listOf(s(YtPrimitive.TICK, 0.5f))
        YtNewsHaptic.QUEUE -> listOf(s(YtPrimitive.SPIN, 0.35f), s(YtPrimitive.TICK, 0.45f, 30), s(YtPrimitive.TICK, 0.45f, 30))
        YtNewsHaptic.CLOSE -> listOf(s(YtPrimitive.CLICK, 0.45f), s(YtPrimitive.TICK, 0.3f, 40))
        YtNewsHaptic.REJECT -> listOf(s(YtPrimitive.CLICK, 0.6f), s(YtPrimitive.CLICK, 0.6f, 60))
    }

    /** Frost, ice: crackles of low ticks fading out, a slow thaw, a click with an after-tick. */
    private fun frost(m: YtNewsHaptic) = when (m) {
        YtNewsHaptic.CLAIM -> listOf(s(YtPrimitive.SLOW_RISE, 0.3f))
        YtNewsHaptic.OPEN -> listOf(s(YtPrimitive.LOW_TICK, 0.6f), s(YtPrimitive.LOW_TICK, 0.45f, 40), s(YtPrimitive.LOW_TICK, 0.3f, 40))
        YtNewsHaptic.PAGE -> listOf(s(YtPrimitive.CLICK, 0.35f), s(YtPrimitive.LOW_TICK, 0.25f, 40))
        YtNewsHaptic.QUEUE -> listOf(s(YtPrimitive.CLICK, 0.55f), s(YtPrimitive.LOW_TICK, 0.35f, 40))
        YtNewsHaptic.CLOSE -> listOf(s(YtPrimitive.LOW_TICK, 0.5f), s(YtPrimitive.LOW_TICK, 0.3f, 40))
        YtNewsHaptic.REJECT -> listOf(s(YtPrimitive.CLICK, 0.5f), s(YtPrimitive.CLICK, 0.5f, 60))
    }

    /** Kiesel, a heavy smooth stone: a squeeze, a thud with bounce ticks fading out. */
    private fun kiesel(m: YtNewsHaptic) = when (m) {
        YtNewsHaptic.CLAIM -> listOf(s(YtPrimitive.LOW_TICK, 0.6f))
        YtNewsHaptic.OPEN -> listOf(s(YtPrimitive.THUD, 0.7f), s(YtPrimitive.TICK, 0.35f, 140), s(YtPrimitive.TICK, 0.2f, 100))
        YtNewsHaptic.PAGE -> listOf(s(YtPrimitive.LOW_TICK, 0.5f))
        YtNewsHaptic.QUEUE -> listOf(s(YtPrimitive.THUD, 0.6f), s(YtPrimitive.TICK, 0.3f, 120))
        YtNewsHaptic.CLOSE -> listOf(s(YtPrimitive.THUD, 0.5f))
        YtNewsHaptic.REJECT -> listOf(s(YtPrimitive.THUD, 0.45f), s(YtPrimitive.THUD, 0.3f, 80))
    }

    /** Frequency, a beat: kicks with a hi-hat after them, a swell, a fill. */
    private fun frequency(m: YtNewsHaptic) = when (m) {
        YtNewsHaptic.CLAIM -> listOf(s(YtPrimitive.SLOW_RISE, 0.4f))
        YtNewsHaptic.OPEN -> listOf(s(YtPrimitive.THUD, 0.7f), s(YtPrimitive.TICK, 0.35f, 60))
        YtNewsHaptic.PAGE -> listOf(s(YtPrimitive.TICK, 0.45f))
        YtNewsHaptic.QUEUE -> listOf(s(YtPrimitive.THUD, 0.6f), s(YtPrimitive.THUD, 0.6f, 90))
        YtNewsHaptic.CLOSE -> listOf(s(YtPrimitive.THUD, 0.45f), s(YtPrimitive.TICK, 0.25f, 60))
        YtNewsHaptic.REJECT -> listOf(s(YtPrimitive.CLICK, 0.55f), s(YtPrimitive.CLICK, 0.55f, 60))
    }

    /** Synthwave, a synth: a filter sweep, a kick with a swell, an arpeggio of rising ticks. */
    private fun synthwave(m: YtNewsHaptic) = when (m) {
        YtNewsHaptic.CLAIM -> listOf(s(YtPrimitive.QUICK_RISE, 0.35f))
        YtNewsHaptic.OPEN -> listOf(s(YtPrimitive.THUD, 0.6f), s(YtPrimitive.SLOW_RISE, 0.3f, 40))
        YtNewsHaptic.PAGE -> listOf(s(YtPrimitive.TICK, 0.4f))
        YtNewsHaptic.QUEUE -> listOf(s(YtPrimitive.TICK, 0.3f), s(YtPrimitive.TICK, 0.45f, 50), s(YtPrimitive.TICK, 0.6f, 50))
        YtNewsHaptic.CLOSE -> listOf(s(YtPrimitive.QUICK_FALL, 0.45f))
        YtNewsHaptic.REJECT -> listOf(s(YtPrimitive.CLICK, 0.55f), s(YtPrimitive.QUICK_FALL, 0.35f, 40))
    }
}
