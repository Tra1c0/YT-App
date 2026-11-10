package com.github.libretube.ui.yt.shorts

import com.github.libretube.helpers.YtDesign
import com.github.libretube.ui.yt.YtFeedback
import com.github.libretube.ui.yt.YtHapticStep
import com.github.libretube.ui.yt.YtHapticTexture
import com.github.libretube.ui.yt.YtPrimitive

/** The type families the Shorts view can use (mapped to YtFonts in YtShortsStyle). */
enum class YtShortsFont { SYSTEM, GEIST, GEIST_MONO, FIGTREE, ARCHIVO, NUNITO, INTER, PLEX_MONO, GOOGLE_SANS, JAKARTA }

/** The shape of the timeline's handle, after each design's shape language. */
enum class YtShortsKnob { CIRCLE, CAPSULE, SQUARE, FRAME, BLOB, COOKIE, PEBBLE }

/** How the timeline grows in and the chips answer: the design's motion character. */
enum class YtShortsMotion { CALM, SPRINGY, CRISP, CINEMATIC, FLUID }

/**
 * YT: the look of the Shorts view per design, as plain values so a JVM test can check the
 * contrast of every pair. Over the video everything sits on an opaque or strongly scrimmed
 * [chip], never on the moving picture alone.
 *
 * [accent] the played part of the timeline and active marks; [track] the rest of it;
 * [chip] the pills (time label, buttons, countdown) and [chipInk] their text;
 * [radiusDp] the corner of the pills (-1: a full capsule, 0: square);
 * [barDp] the open timeline's thickness; [upper] labels in capitals (Editorial, After);
 * [page] the haptic texture when a new short lands, [second] the tick per second of scrubbing.
 */
data class YtShortsLook(
    val accent: Int,
    val track: Int,
    val chip: Int,
    val chipInk: Int,
    val radiusDp: Int,
    val barDp: Int,
    val knob: YtShortsKnob,
    val font: YtShortsFont,
    val timeFont: YtShortsFont,
    val upper: Boolean,
    val motion: YtShortsMotion,
    val heart: Int,
    val page: YtHapticTexture,
    val second: YtHapticTexture,
) {
    companion object {
        private const val WHITE_TRACK = 0x59FFFFFF

        private fun tick(name: String, scale: Float, primitive: Int = YtPrimitive.TICK) =
            YtHapticTexture.of("shorts_second_$name", YtFeedback.CLOCK_TICK, YtHapticStep(primitive, scale))

        /**
         * The look of [design]; [dark]: the dark mode of a light design (YtDesignDark), where the
         * light pills turn into the design's dark surfaces with light type.
         */
        fun of(design: YtDesign, dark: Boolean = false): YtShortsLook {
            val day = ofDay(design)
            if (!dark) return day
            return when (design) {
                YtDesign.CLAY -> day.copy(chip = 0xFF4A3830.toInt(), chipInk = 0xFFF7E8DA.toInt())
                YtDesign.EDITORIAL -> day.copy(chip = 0xFF0B0B0B.toInt(), chipInk = 0xFFF2F2EE.toInt())
                YtDesign.GLASS -> day.copy(chip = 0xE61F3A38.toInt(), chipInk = 0xFFE3F4F1.toInt())
                YtDesign.KIESEL -> day.copy(chip = 0xF23A3632.toInt(), chipInk = 0xFFEDE8E3.toInt())
                // Pop keeps its yellow pills with plum type
                else -> day
            }
        }

        private fun ofDay(design: YtDesign): YtShortsLook = when (design) {
            YtDesign.STANDARD -> YtShortsLook(
                accent = 0xFFFF4E45.toInt(), track = WHITE_TRACK,
                chip = 0xD9101014.toInt(), chipInk = 0xFFFFFFFF.toInt(),
                radiusDp = -1, barDp = 6, knob = YtShortsKnob.CIRCLE,
                font = YtShortsFont.SYSTEM, timeFont = YtShortsFont.SYSTEM, upper = false,
                motion = YtShortsMotion.CALM, heart = 0xFFFF4E45.toInt(),
                page = YtHapticTexture.of("shorts_page_standard", YtFeedback.SEGMENT_TICK, YtHapticStep(YtPrimitive.TICK, 0.5f)),
                second = tick("standard", 0.3f),
            )
            YtDesign.YOUTUBE -> YtShortsLook(
                accent = 0xFFFF334B.toInt(), track = WHITE_TRACK,
                chip = 0xE01C1C23.toInt(), chipInk = 0xFFF6F4F2.toInt(),
                radiusDp = -1, barDp = 6, knob = YtShortsKnob.CIRCLE,
                font = YtShortsFont.GEIST, timeFont = YtShortsFont.GEIST_MONO, upper = false,
                motion = YtShortsMotion.CALM, heart = 0xFFFF334B.toInt(),
                page = YtHapticTexture.of("shorts_page_youtube", YtFeedback.SEGMENT_TICK, YtHapticStep(YtPrimitive.TICK, 0.5f)),
                second = tick("youtube", 0.3f),
            )
            // cobalt and lime: a lime bar on a dark tile, the handle a short capsule
            YtDesign.MOVO -> YtShortsLook(
                accent = 0xFFC9F26F.toInt(), track = WHITE_TRACK,
                chip = 0xE61D1E21.toInt(), chipInk = 0xFFF7F6F2.toInt(),
                radiusDp = 14, barDp = 8, knob = YtShortsKnob.CAPSULE,
                font = YtShortsFont.GEIST, timeFont = YtShortsFont.GEIST_MONO, upper = false,
                motion = YtShortsMotion.SPRINGY, heart = 0xFFE557D6.toInt(),
                page = YtHapticTexture.of(
                    "shorts_page_movo", YtFeedback.SEGMENT_TICK,
                    YtHapticStep(YtPrimitive.QUICK_RISE, 0.35f), YtHapticStep(YtPrimitive.CLICK, 0.4f, 30),
                ),
                second = tick("movo", 0.35f),
            )
            // the film: black gate, condensed capitals, a square frame as the handle
            YtDesign.AFTER -> YtShortsLook(
                accent = 0xFFFF8B4B.toInt(), track = WHITE_TRACK,
                chip = 0xE6000000.toInt(), chipInk = 0xFFFFF4DF.toInt(),
                radiusDp = 2, barDp = 5, knob = YtShortsKnob.FRAME,
                font = YtShortsFont.ARCHIVO, timeFont = YtShortsFont.GEIST_MONO, upper = true,
                motion = YtShortsMotion.CINEMATIC, heart = 0xFFFF8B4B.toInt(),
                page = YtHapticTexture.of(
                    "shorts_page_after", YtFeedback.CONFIRM,
                    YtHapticStep(YtPrimitive.THUD, 0.5f), YtHapticStep(YtPrimitive.TICK, 0.2f, 60),
                ),
                second = tick("after", 0.25f, YtPrimitive.LOW_TICK),
            )
            YtDesign.SIDE -> YtShortsLook(
                accent = 0xFFFFC68D.toInt(), track = WHITE_TRACK,
                chip = 0xE6292A2C.toInt(), chipInk = 0xFFF5F1EB.toInt(),
                radiusDp = 18, barDp = 6, knob = YtShortsKnob.CIRCLE,
                font = YtShortsFont.FIGTREE, timeFont = YtShortsFont.FIGTREE, upper = false,
                motion = YtShortsMotion.CALM, heart = 0xFFFF7A8A.toInt(),
                // a soft double beat, as the heart of Side
                page = YtHapticTexture.of(
                    "shorts_page_side", YtFeedback.SEGMENT_TICK,
                    YtHapticStep(YtPrimitive.TICK, 0.4f), YtHapticStep(YtPrimitive.TICK, 0.25f, 110),
                ),
                second = tick("side", 0.3f),
            )
            // puffy cream pills with mauve ink, a round blob of clay as the handle
            YtDesign.CLAY -> YtShortsLook(
                accent = 0xFFE6A4A0.toInt(), track = WHITE_TRACK,
                chip = 0xFFFFF3E9.toInt(), chipInk = 0xFF6E4A55.toInt(),
                radiusDp = 22, barDp = 10, knob = YtShortsKnob.BLOB,
                font = YtShortsFont.NUNITO, timeFont = YtShortsFont.NUNITO, upper = false,
                motion = YtShortsMotion.SPRINGY, heart = 0xFFE6A4A0.toInt(),
                page = YtHapticTexture.of(
                    "shorts_page_clay", YtFeedback.GESTURE_END,
                    YtHapticStep(YtPrimitive.THUD, 0.35f), YtHapticStep(YtPrimitive.LOW_TICK, 0.25f, 70),
                ),
                second = tick("clay", 0.3f, YtPrimitive.LOW_TICK),
            )
            // printed: paper chips, black ink, square corners, mono time, a square handle
            YtDesign.EDITORIAL -> YtShortsLook(
                accent = 0xFF0F54F6.toInt(), track = WHITE_TRACK,
                chip = 0xFFFFFFFF.toInt(), chipInk = 0xFF0A0A0A.toInt(),
                radiusDp = 0, barDp = 4, knob = YtShortsKnob.SQUARE,
                font = YtShortsFont.INTER, timeFont = YtShortsFont.PLEX_MONO, upper = true,
                motion = YtShortsMotion.CRISP, heart = 0xFF0F54F6.toInt(),
                page = YtHapticTexture.of("shorts_page_editorial", YtFeedback.SEGMENT_TICK, YtHapticStep(YtPrimitive.TICK, 0.65f)),
                second = tick("editorial", 0.4f),
            )
            // glass: a bright, nearly opaque glass pill with deep teal ink
            YtDesign.GLASS -> YtShortsLook(
                accent = 0xFFF03A3A.toInt(), track = WHITE_TRACK,
                chip = 0xE6F4FBF9.toInt(), chipInk = 0xFF0F2B2E.toInt(),
                radiusDp = -1, barDp = 8, knob = YtShortsKnob.CIRCLE,
                font = YtShortsFont.GOOGLE_SANS, timeFont = YtShortsFont.GOOGLE_SANS, upper = false,
                motion = YtShortsMotion.FLUID, heart = 0xFFF03A3A.toInt(),
                page = YtHapticTexture.of(
                    "shorts_page_glass", YtFeedback.SEGMENT_TICK,
                    YtHapticStep(YtPrimitive.LOW_TICK, 0.4f), YtHapticStep(YtPrimitive.TICK, 0.2f, 40),
                ),
                second = tick("glass", 0.25f),
            )
            // yellow blobs with plum ink, the handle a blob that wobbles
            YtDesign.POP -> YtShortsLook(
                accent = 0xFFFDE682.toInt(), track = WHITE_TRACK,
                chip = 0xFFFDE682.toInt(), chipInk = 0xFF3B2054.toInt(),
                radiusDp = 20, barDp = 10, knob = YtShortsKnob.BLOB,
                font = YtShortsFont.NUNITO, timeFont = YtShortsFont.NUNITO, upper = false,
                motion = YtShortsMotion.SPRINGY, heart = 0xFFF7B3CE.toInt(),
                page = YtHapticTexture.of(
                    "shorts_page_pop", YtFeedback.SEGMENT_TICK,
                    YtHapticStep(YtPrimitive.CLICK, 0.5f), YtHapticStep(YtPrimitive.TICK, 0.3f, 80),
                ),
                second = tick("pop", 0.35f),
            )
            // night chips, lime bar, a turning cookie as the handle
            YtDesign.SHAPES -> YtShortsLook(
                accent = 0xFFC6F36B.toInt(), track = WHITE_TRACK,
                chip = 0xE612122A.toInt(), chipInk = 0xFFF5F3FF.toInt(),
                radiusDp = -1, barDp = 8, knob = YtShortsKnob.COOKIE,
                font = YtShortsFont.GOOGLE_SANS, timeFont = YtShortsFont.GOOGLE_SANS, upper = false,
                motion = YtShortsMotion.SPRINGY, heart = 0xFFF28B82.toInt(),
                page = YtHapticTexture.of(
                    "shorts_page_shapes", YtFeedback.SEGMENT_TICK,
                    YtHapticStep(YtPrimitive.SPIN, 0.3f), YtHapticStep(YtPrimitive.TICK, 0.3f, 50),
                ),
                second = tick("shapes", 0.3f),
            )
            // Frequency: night chips with a coral bar, Formen's turning cookie as the handle,
            // YouTube neu's Geist; a page lands like a kick with a hi-hat after it
            YtDesign.FREQUENCY -> YtShortsLook(
                accent = 0xFFFF4D6D.toInt(), track = WHITE_TRACK,
                chip = 0xEB0B0A18.toInt(), chipInk = 0xFFF4F2FF.toInt(),
                radiusDp = -1, barDp = 7, knob = YtShortsKnob.COOKIE,
                font = YtShortsFont.GEIST, timeFont = YtShortsFont.GEIST_MONO, upper = false,
                motion = YtShortsMotion.SPRINGY, heart = 0xFFFF4D6D.toInt(),
                page = YtHapticTexture.of(
                    "shorts_page_frequency", YtFeedback.SEGMENT_TICK,
                    YtHapticStep(YtPrimitive.THUD, 0.4f), YtHapticStep(YtPrimitive.TICK, 0.3f, 70),
                ),
                second = tick("frequency", 0.3f),
            )
            // dark frost on sage with an orange bar; the scroll lands with a crackle
            YtDesign.FROST -> YtShortsLook(
                accent = 0xFFF08A3C.toInt(), track = WHITE_TRACK,
                chip = 0xD9243530.toInt(), chipInk = 0xFFF3F6F4.toInt(),
                radiusDp = 20, barDp = 8, knob = YtShortsKnob.CIRCLE,
                font = YtShortsFont.JAKARTA, timeFont = YtShortsFont.JAKARTA, upper = false,
                motion = YtShortsMotion.FLUID, heart = 0xFFF08A3C.toInt(),
                page = YtHapticTexture.of(
                    "shorts_page_frost", YtFeedback.SEGMENT_TICK,
                    YtHapticStep(YtPrimitive.TICK, 0.35f), YtHapticStep(YtPrimitive.TICK, 0.22f, 30),
                    YtHapticStep(YtPrimitive.TICK, 0.12f, 30),
                ),
                second = tick("frost", 0.25f),
            )
            // the black panel with lavender: a pebble as the handle, landing with weight
            YtDesign.KIESEL -> YtShortsLook(
                accent = 0xFFB4A7F5.toInt(), track = WHITE_TRACK,
                chip = 0xF20E0E0E.toInt(), chipInk = 0xFFFFFFFF.toInt(),
                radiusDp = 20, barDp = 10, knob = YtShortsKnob.PEBBLE,
                font = YtShortsFont.JAKARTA, timeFont = YtShortsFont.JAKARTA, upper = false,
                motion = YtShortsMotion.SPRINGY, heart = 0xFFF5B79A.toInt(),
                page = YtHapticTexture.of(
                    "shorts_page_kiesel", YtFeedback.CONFIRM,
                    YtHapticStep(YtPrimitive.THUD, 0.55f), YtHapticStep(YtPrimitive.LOW_TICK, 0.2f, 60),
                ),
                second = tick("kiesel", 0.3f, YtPrimitive.LOW_TICK),
            )
        }
    }
}
