package com.github.libretube.ui.yt.mini

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.constraintlayout.motion.widget.MotionScene
import com.github.libretube.helpers.YtBuilderMode
import com.github.libretube.helpers.YtDesign
import com.github.libretube.ui.yt.YtHapticTexture
import com.github.libretube.ui.yt.YtPalette
import com.github.libretube.ui.yt.builder.YtBuilderActive
import com.github.libretube.ui.yt.builder.YtBuilderPaint
import com.github.libretube.ui.yt.builder.YtDocOps
import com.github.libretube.ui.yt.builder.YtMiniConfig
import com.github.libretube.ui.yt.builder.YtNavConfig
import com.github.libretube.ui.yt.builder.YtPaintType
import com.github.libretube.ui.yt.builder.YtRole
import com.github.libretube.ui.yt.builder.YtSlot
import com.github.libretube.ui.yt.builder.YtThemeDoc
import com.github.libretube.ui.yt.builder.YtTokensResolver
import com.github.libretube.ui.yt.builder.YtTextSpec
import com.github.libretube.helpers.YtDesignDark

/**
 * YT: the mini player of "Eigenes", drawn from the active theme document: its measures from the
 * theme's mini player and bar settings (a card stacked on the bar), the body's fill (a vertical
 * gradient of two stops at most), outline and shadow from the mini player component's root, title,
 * channel and progress from its slots. The shared surface of the round 10 players draws it, so
 * docking, the preview and the drop target work as in every design. Nothing is drawn on the video
 * (a SurfaceView, Pitfall P5): the picture is only clipped by its outline.
 */
object YtMiniCustom : YtMiniLookDesign(YtDesign.CUSTOM) {
    private var specKey: Pair<YtMiniConfig, YtNavConfig>? = null
    private var specCache: YtMiniSpec = YtMiniSpecs.SYNTHWAVE

    override val spec: YtMiniSpec
        get() {
            val doc = YtBuilderActive.current ?: return specCache
            val key = doc.mini to doc.nav
            if (key != specKey) {
                specCache = specOf(doc.mini, doc.nav)
                specKey = key
            }
            return specCache
        }

    /** The theme's measures, rounded to the whole dp the spec takes. */
    fun specOf(mini: YtMiniConfig, nav: YtNavConfig): YtMiniSpec {
        val thumbW = mini.thumbWidth.toInt().coerceIn(48, 140)
        return YtMiniSpec(
            joint = YtMiniJoint.STACK,
            start = if (nav.floating) nav.side.toInt() else 0,
            navTop = if (nav.floating) 6 else 0,
            height = mini.height.toInt().coerceIn(48, 96),
            radius = mini.radius.toInt().coerceIn(0, 48),
            thumbWidth = thumbW,
            thumbHeight = (thumbW * 9 / 16).coerceAtMost(mini.height.toInt() - 8),
            thumbStart = 7,
            thumbRadius = mini.thumbRadius.toInt().coerceIn(0, 40),
            peekGrowth = 2.0f,
        )
    }

    override val motion: YtMiniMotion
        get() {
            val m = YtBuilderActive.doc?.tokens?.motion
            val springy = (m?.damping ?: 0.8f) < 0.6f
            return YtMiniMotion(
                duration = (m?.durationMs ?: 280).coerceIn(160, 600),
                interpolator = if (springy) MotionScene.Transition.INTERPOLATE_OVERSHOOT else MotionScene.Transition.INTERPOLATE_EASE_OUT,
                peekStiffness = (m?.stiffness ?: 420f).coerceIn(150f, 1500f),
                peekDamping = (m?.damping ?: 0.8f).coerceIn(0.3f, 1f),
            )
        }

    override val landing: YtMiniLanding get() = YtMiniLanding.SETTLE

    private var lookKey: Pair<YtThemeDoc?, Boolean>? = null
    private var lookCache: YtMiniLook? = null

    override fun look(palette: YtPalette): YtMiniLook {
        val doc = YtBuilderActive.doc
        val night = YtDesignDark.active
        val key = doc to night
        lookCache?.takeIf { key == lookKey && it.spec == spec }?.let { return it }
        val look = runCatching { doc?.let { lookOf(it, night, palette) } }.getOrNull() ?: plain(palette)
        lookKey = key
        lookCache = look
        return look
    }

    private fun plain(palette: YtPalette) = YtMiniLook(
        spec = spec,
        fill = palette.surface,
        title = TextStyle(color = palette.text, fontFamily = palette.font, fontWeight = FontWeight.Medium, fontSize = 13.5.sp),
        subtitle = TextStyle(color = palette.textSecondary, fontFamily = palette.font, fontSize = 11.5.sp),
        icon = palette.text,
        bar = YtMiniBar.SEAM, barColor = palette.accent, track = palette.text.copy(alpha = 0.2f),
    )

    private fun lookOf(doc: YtThemeDoc, night: Boolean, palette: YtPalette): YtMiniLook {
        val t = YtTokensResolver(doc, night)
        val root = doc.components.mini
        val nodes = YtDocOps.all(root)
        val fills = root.style.fills.filter { it.enabled }.map { t.resolve(it) }
        val first = fills.firstOrNull()
        val fill = first?.let { if (it.type == YtPaintType.SOLID) Color(it.color) else Color(it.stops.first().color) } ?: palette.surface
        // a gradient runs from its last stop at the top to its first at the bottom
        val fillTop = first?.takeIf { it.type != YtPaintType.SOLID }?.let { Color(it.stops.last().color) }
        val stroke = root.style.strokes.firstOrNull { it.enabled }
        val shadow = root.style.shadows.firstOrNull { it.enabled && !it.inner }
        fun text(slot: YtSlot, fallback: YtTextSpec): TextStyle {
            val spec = nodes.firstOrNull { it.slot == slot }?.text ?: fallback
            return YtBuilderPaint.textStyle(spec, t, YtBuilderActive.assets)
        }
        val title = text(YtSlot.TITLE, YtTextSpec(styleRef = "label"))
        val subtitle = text(YtSlot.CHANNEL, YtTextSpec(styleRef = "caption", color = com.github.libretube.ui.yt.builder.YtPaint.token(YtRole.TEXT_SECONDARY)))
        val progress = nodes.firstOrNull { it.slot == YtSlot.PROGRESS }
        val barColor = progress?.style?.fills?.firstOrNull()?.let { YtBuilderPaint.color(t.resolve(it)) } ?: palette.accent
        return YtMiniLook(
            spec = spec,
            fill = fill,
            fillTop = fillTop,
            border = stroke?.let { YtBuilderPaint.color(t.resolve(it.paint)) },
            borderWidth = stroke?.width?.coerceIn(0.5f, 4f) ?: 1f,
            shadow = shadow?.let { Color(it.color) },
            title = title,
            subtitle = subtitle,
            icon = title.color,
            playIcon = title.color,
            bar = YtMiniBar.SEAM,
            barColor = barColor,
            track = title.color.copy(alpha = 0.18f),
            barWidth = (progress?.height?.value ?: 2f).coerceIn(1f, 6f),
            trackWidth = 1f,
        )
    }

    /** Feels like the design the theme started from. */
    override fun haptic(moment: YtMoment): YtHapticTexture {
        val base = YtBuilderMode.base
        return if (base == YtDesign.CUSTOM) super.haptic(moment) else YtMiniDesigns.of(base).haptic(moment)
    }
}
