package com.github.libretube.ui.yt.mini

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import androidx.constraintlayout.motion.widget.MotionScene
import com.github.libretube.helpers.YtDesign
import com.github.libretube.ui.yt.FrequencyPulse
import com.github.libretube.ui.yt.FrequencyPulseHost
import com.github.libretube.ui.yt.FrequencyPulseMath
import com.github.libretube.ui.yt.YtDock
import com.github.libretube.ui.yt.YtFonts
import com.github.libretube.ui.yt.YtHapticTexture
import com.github.libretube.ui.yt.YtPalette
import com.github.libretube.ui.yt.FrequencyMotionMath
import com.github.libretube.ui.yt.frequencyPhase
import com.github.libretube.ui.yt.frequencyShapePath
import com.github.libretube.ui.yt.frequencyWavePath
import androidx.compose.ui.graphics.StrokeCap
import kotlin.math.cos
import kotlin.math.sin

/**
 * YT: the mini player of Frequency, "Pulsnaht": YouTube neu's stacked card ("Rote Naht": card and
 * bar are one surface, the seam between them, the needle sews it in when the player lands, the
 * same preview with "Als Nächstes"), drawn in Frequency's night with its coral, and alive with
 * the sound: the spectrum runs as a wave along the seam, a glow above the played part lights
 * with the level, its head swells on hits, a coral rim inside the picture breathes (drawn over
 * the picture, so it follows its live bounds while dragged; the video itself cannot be changed),
 * and a morphing aqua ring with spectrum rays sits around the play button. In silence all of it
 * rests.
 */
object YtMiniFrequency : YtMiniLookDesign(YtDesign.FREQUENCY) {
    override val spec: YtMiniSpec = YtMiniSpecs.FREQUENCY
    override val motion: YtMiniMotion = YtMiniMotion(
        duration = 300,
        interpolator = MotionScene.Transition.INTERPOLATE_OVERSHOOT,
        // YouTube neu's calm card with a little of Formen's spring
        peekStiffness = 320f,
        peekDamping = 0.8f,
        releaseStiffness = 520f,
        releaseDamping = 0.85f,
    )
    override val landing: YtMiniLanding get() = YtMiniLanding.NONE
    override val peekGrowth: Float get() = YtMiniYoutubeLayout.GROWTH

    /** The night of the card and the bar (YtNavBar's Frequency bar has the same). */
    val card = Color(0xFF17152B)

    internal val colors = YtMiniYoutubeColors(
        card = card,
        cardTop = Color(0xFF1D1A35),
        border = Color(0xFF2C2850),
        title = Color(0xFFF4F2FF),
        secondary = Color(0xFFB7B2D6),
        pill = Color(0xFF231F3F),
        row = Color(0xFF1D1A35),
        rowActive = Color(0xFF241F45),
        thumbGround = Color(0xFF231F3F),
        dull = Color(0xFF5A5670),
        hot = Color(0xFFFFB3C1),
        onAccent = Color(0xFF170812),
    )

    override fun look(palette: YtPalette): YtMiniLook = YtMiniLook(
        spec = spec,
        fill = colors.card, border = colors.border,
        title = TextStyle(color = colors.title, fontFamily = YtFonts.geist, fontWeight = FontWeight.Medium, fontSize = 13.5.sp),
        subtitle = TextStyle(color = colors.secondary, fontFamily = YtFonts.geist, fontWeight = FontWeight.Normal, fontSize = 11.5.sp),
        icon = colors.title, playIcon = Color.White,
        bar = YtMiniBar.SEAM, barColor = palette.accent, track = Color(0x26FFFFFF), barWidth = YtMiniYoutubeLayout.SEAM,
    )

    override fun peekGeometry(boxWidth: Float, density: Float, hasNext: Boolean): YtMiniPeekGeometry =
        YtMiniYoutubeLayout.peek(spec, boxWidth, density, hasNext)

    @Composable
    override fun Under(scene: YtMiniScene) {
        // the mini player holds the pulse too: it is shown over every screen of the design
        FrequencyPulseHost()
        Box(Modifier.fillMaxSize()) {
            YoutubeUnder(scene, this@YtMiniFrequency, colors)
            SeamPulse(scene)
            PlayRing(scene)
        }
    }

    @Composable
    override fun Over(scene: YtMiniScene) {
        YoutubeOver(scene, spec.thumbRadius.toFloat(), pulse = true)
    }

    /**
     * Over the seam: the spectrum as a wave running along the whole seam (bass left, highs
     * right, up to 8 dp), a coral glow above the played part that rises in light with the level
     * (guarded), and a soft light at its head that swells on hits. The brushes and the path are
     * made once per size and only moved while drawing, so a frame allocates nothing.
     */
    @Composable
    private fun SeamPulse(scene: YtMiniScene) {
        val palette = scene.palette ?: return
        val density = LocalDensity.current.density
        val mirror = LocalLayoutDirection.current == LayoutDirection.Rtl
        val coral = palette.accent
        Box(
            Modifier
                .fillMaxSize()
                .clearAndSetSemantics {}
                .drawWithCache {
                    val overlap = spec.overlap * density
                    val y = size.height - overlap / 2f
                    val glowHeight = YtMiniYoutubeLayout.GLOW_HOVER * density
                    val glow = Brush.verticalGradient(listOf(Color.Transparent, coral), startY = y - glowHeight, endY = y)
                    val headRadius = 12f * density
                    val head = Brush.radialGradient(listOf(coral, coral.copy(alpha = 0f)), Offset.Zero, headRadius)
                    val wave = Path()
                    val waveTop = y - WAVE_DP * density
                    val waveFill = Brush.verticalGradient(listOf(palette.lavender.copy(alpha = 0f), coral.copy(alpha = 0.35f)), startY = waveTop, endY = y)
                    val waveLine = Stroke(1.3f * density)
                    onDrawBehind {
                        val shown = YtMiniYoutubeMath.seamShown(YtDock.progress) * (1f - YtDock.peek.coerceIn(0f, 1f))
                        if (shown <= 0.01f) return@onDrawBehind
                        FrequencyPulse.tick.intValue
                        val left = 1f * density
                        val length = size.width - 2f * left
                        // the wave sits on the seam over the whole width, whatever was played
                        val amp = WAVE_DP * density * FrequencyPulse.tier.amplitude
                        frequencyWavePath(wave, left, size.width - left, y, amp, FrequencyPulse.tier.bars, mirror = false, closeY = y)
                        drawPath(wave, waveFill, alpha = shown)
                        frequencyWavePath(wave, left, size.width - left, y, amp, FrequencyPulse.tier.bars, mirror = false)
                        drawPath(wave, palette.lime.copy(alpha = 0.7f * shown), style = waveLine)
                        val played = length * scene.info.fraction.coerceIn(0f, 1f)
                        if (played <= 0f) return@onDrawBehind
                        val light = FrequencyPulse.glow.floatValue
                        val hit = FrequencyPulse.punch.floatValue
                        val start = if (mirror) size.width - left - played else left
                        if (light > 0.01f) {
                            drawRect(glow, Offset(start, y - glowHeight), Size(played, glowHeight), alpha = 0.32f * light * shown)
                        }
                        if (hit > 0.01f) {
                            val x = if (mirror) start else left + played
                            translate(x, y) {
                                scale(0.6f + 0.6f * hit, Offset.Zero) {
                                    drawCircle(head, headRadius, Offset.Zero, alpha = 0.55f * hit * shown)
                                }
                            }
                        }
                    }
                },
        )
    }

    /**
     * Around the play button: an aqua ring that grows with the beat, bounces, turns and morphs
     * (cookie, blob, squircle, circle), and the spectrum as short rays around it.
     */
    @Composable
    private fun PlayRing(scene: YtMiniScene) {
        val palette = scene.palette ?: return
        val density = LocalDensity.current.density
        val mirror = LocalLayoutDirection.current == LayoutDirection.Rtl
        val path = remember { Path() }
        val stroke = remember(density) { Stroke(1.6f * density) }
        val ring = palette.lime
        // the docked geometry, computed again only when the width changes (not every frame)
        val cache = remember { GeometryCache(this@YtMiniFrequency) }
        Canvas(Modifier.fillMaxSize().clearAndSetSemantics {}) {
            val shown = YtMiniYoutubeMath.restText(YtDock.progress, YtDock.peek)
            if (shown <= 0.01f) return@Canvas
            val g = cache.of(size.width, density)
            val play = g.play ?: return@Canvas
            val restTop = size.height - (spec.height + spec.overlap) * density
            val cx = if (mirror) size.width - play.centerX else play.centerX
            val center = Offset(cx, restTop + play.centerY)
            FrequencyPulse.tick.intValue
            val e = FrequencyPulse.energy.floatValue
            val amp = FrequencyPulse.tier.amplitude
            val radius = 17f * density * FrequencyMotionMath.scale(e, FrequencyPulse.bounce(2), 0.14f, amp)
            val turn = Math.toRadians(FrequencyPulse.turn.floatValue.toDouble()).toFloat()
            frequencyShapePath(path, center, radius, 8, FrequencyPulseMath.cookieDepth(0.06f, e), frequencyPhase(2), turn)
            drawPath(path, ring.copy(alpha = (0.55f + 0.35f * FrequencyPulse.glow.floatValue) * shown), style = stroke)
            // the spectrum as rays: bass at the top, around clockwise
            val count = FrequencyPulse.tier.bars
            val inner = radius + 3f * density
            val reach = 7f * density * amp
            val rayColor = palette.lime.copy(alpha = 0.75f * shown)
            for (i in 0 until count) {
                val v = FrequencyMotionMath.bar(FrequencyPulse.bands, i, count)
                val angle = turn + FrequencyPulseMath.TAU * i / count
                val dx = sin(angle)
                val dy = -cos(angle)
                val outer = inner + 1.5f * density + reach * v
                drawLine(
                    rayColor,
                    Offset(center.x + dx * inner, center.y + dy * inner),
                    Offset(center.x + dx * outer, center.y + dy * outer),
                    strokeWidth = 1.6f * density,
                    cap = StrokeCap.Round,
                )
            }
        }
    }

    override fun haptic(moment: YtMoment): YtHapticTexture = YtMiniFrequencyTextures.of(moment)

    /** How high the seam's spectrum wave reaches. */
    private const val WAVE_DP = 8f

    // the needle sews the seam in (YoutubeUnder): the card stays on the bar
    override fun land(views: YtMiniViews): Animator? = null

    // the "+1" pill comes out of the seam (YoutubeUnder)
    override fun accept(views: YtMiniViews): Animator? = null

    /** A short shake of the picture and its buttons; the card stays on the bar, the seam dulls. */
    override fun reject(views: YtMiniViews): Animator? {
        val moved = listOf(views.player, views.play, views.close)
        return ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 380L
            interpolator = null
            addUpdateListener { animator ->
                val x = 5f * views.density * wobble(animator.animatedValue as Float, 3f, 5f)
                moved.forEach { it.translationX = x }
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    moved.forEach { it.translationX = 0f }
                }
            })
            start()
        }
    }
}

/** One docked geometry per box width and density. */
private class GeometryCache(private val design: YtMiniDesign) {
    private var width = -1f
    private var density = 0f
    private var cached: YtMiniGeometry? = null

    fun of(width: Float, density: Float): YtMiniGeometry {
        val c = cached
        if (c != null && width == this.width && density == this.density) return c
        this.width = width
        this.density = density
        return design.geometry(width, density).also { cached = it }
    }
}
