package com.github.libretube.ui.yt.builder

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.github.libretube.helpers.YtBuilderMode
import com.github.libretube.helpers.YtDesign
import com.github.libretube.ui.yt.Clay
import com.github.libretube.ui.yt.ClayDark
import com.github.libretube.ui.yt.Editorial
import com.github.libretube.ui.yt.EditorialDark
import com.github.libretube.ui.yt.Glass
import com.github.libretube.ui.yt.GlassDark
import com.github.libretube.ui.yt.Kiesel
import com.github.libretube.ui.yt.KieselDark
import com.github.libretube.ui.yt.Pop
import com.github.libretube.ui.yt.PopDark
import com.github.libretube.ui.yt.YtMotionStyle
import com.github.libretube.ui.yt.YtPalette
import com.github.libretube.ui.yt.YtPalettes
import com.github.libretube.ui.yt.YtSignature
import com.github.libretube.ui.yt.palette
import java.io.File

/**
 * YT: the theme "Eigenes" draws right now. Snapshot state, so the screens that draw from it (the
 * home, header, bar, mini player) follow a change at once; the editor sets it while it edits the
 * active theme, so its preview and the app are the same. Without a document (none made yet, or it
 * could not be read) the design falls back to the colours of YouTube neu.
 */
object YtBuilderActive {
    /** The active document, already clamped (YtThemeValidate). */
    var doc by mutableStateOf<YtThemeDoc?>(null)
        private set

    /** The folder of the active theme's fonts and images. */
    var assets: File? = null
        private set

    fun set(doc: YtThemeDoc?, assets: File?) {
        this.assets = assets
        this.doc = doc
        if (doc != null) {
            runCatching {
                YtBuilderMode.write(
                    light = doc.mode == YtThemeMode.BOTH,
                    wordmark = doc.meta.wordmark,
                    timeline = YtTokensResolver(doc, night = false).argb(YtRole.ACCENT),
                    base = doc.base,
                )
            }
        }
    }

    private val fallback = YtPalettes.YouTube.copy(signature = YtSignature.CUSTOM)
    private var cacheKey: Pair<YtThemeDoc?, Boolean>? = null
    private var cached: YtPalette = fallback

    /** The design palette of the active theme ([night]: the dark look of a BOTH theme). */
    fun palette(night: Boolean): YtPalette {
        val d = doc
        val key = d to night
        if (key == cacheKey) return cached
        val p = d?.let { runCatching { paletteOf(it, night, assets) }.getOrNull() } ?: fallback
        cacheKey = key
        cached = p
        return p
    }

    fun paletteOf(doc: YtThemeDoc, night: Boolean, assets: File?): YtPalette {
        val t = YtTokensResolver(doc, night)
        fun c(role: String) = Color(t.argb(role))
        val background = t.argb(YtRole.BACKGROUND)
        return YtPalette(
            background = c(YtRole.BACKGROUND),
            surface = c(YtRole.SURFACE),
            raised = c(YtRole.RAISED),
            text = c(YtRole.TEXT),
            textSecondary = c(YtRole.TEXT_SECONDARY),
            accent = c(YtRole.ACCENT),
            onAccent = c(YtRole.ON_ACCENT),
            live = c(YtRole.LIVE),
            lime = c(YtRole.LIME),
            lavender = c(YtRole.LAVENDER),
            ink = c(YtRole.INK),
            pink = c(YtRole.PINK),
            font = YtBuilderFonts.family(doc.tokens.type["body"]?.font ?: "geist", assets),
            // dark type on a light background
            light = YtColor.luminance(background) > 0.4,
            motion = if (doc.tokens.motion.damping < 0.6f) YtMotionStyle.SPRINGY else YtMotionStyle.CALM,
            signature = YtSignature.CUSTOM,
        )
    }

    /** The seed of a new theme from [design]: its colours (day and, for light designs, night), font, corners and motion. */
    fun seedOf(design: YtDesign): Pair<YtThemeSeeds.YtSeed, Map<String, Int>?> {
        val (day, night) = when (design) {
            YtDesign.CLAY -> YtPalettes.Clay to YtPalettes.ClayDark
            YtDesign.EDITORIAL -> YtPalettes.Editorial to YtPalettes.EditorialDark
            YtDesign.GLASS -> YtPalettes.Glass to YtPalettes.GlassDark
            YtDesign.POP -> YtPalettes.Pop to YtPalettes.PopDark
            YtDesign.KIESEL -> YtPalettes.Kiesel to YtPalettes.KieselDark
            YtDesign.STANDARD, YtDesign.CUSTOM -> YtPalettes.YouTube to null
            else -> (design.palette ?: YtPalettes.YouTube) to null
        }
        val radius = when (design) {
            YtDesign.EDITORIAL -> 0f
            YtDesign.SYNTHWAVE -> 8f
            YtDesign.CLAY, YtDesign.POP, YtDesign.KIESEL -> 22f
            YtDesign.SHAPES, YtDesign.GLASS, YtDesign.FROST -> 20f
            else -> 14f
        }
        val springy = day.motion == YtMotionStyle.SPRINGY
        val seed = YtThemeSeeds.YtSeed(
            design = if (design == YtDesign.STANDARD || design == YtDesign.CUSTOM) YtDesign.YOUTUBE.key else design.key,
            roles = roles(day),
            font = YtBuilderFonts.keyOf(day.font),
            light = night != null,
            radius = radius,
            smoothing = if (design == YtDesign.SHAPES || design == YtDesign.KIESEL) 0.6f else 0f,
            stiffness = if (springy) 420f else 600f,
            damping = if (springy) 0.55f else 0.85f,
        )
        return seed to night?.let(::roles)
    }

    private fun roles(p: YtPalette): Map<String, Int> = mapOf(
        YtRole.BACKGROUND to p.background.toArgb(),
        YtRole.SURFACE to p.surface.toArgb(),
        YtRole.RAISED to p.raised.toArgb(),
        YtRole.TEXT to p.text.toArgb(),
        YtRole.TEXT_SECONDARY to p.textSecondary.toArgb(),
        YtRole.ACCENT to p.accent.toArgb(),
        YtRole.ON_ACCENT to p.onAccent.toArgb(),
        YtRole.LIVE to p.live.toArgb(),
        YtRole.LIME to p.lime.toArgb(),
        YtRole.LAVENDER to p.lavender.toArgb(),
        YtRole.INK to p.ink.toArgb(),
        YtRole.PINK to p.pink.toArgb(),
    )

    /** A new theme document that starts from [design]. */
    fun create(design: YtDesign, name: String, now: Long = System.currentTimeMillis()): YtThemeDoc {
        val (seed, night) = seedOf(design)
        return YtThemeSeeds.create(seed, YtThemeIds.fresh(now), name, now, night)
    }
}
