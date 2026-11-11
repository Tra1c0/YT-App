package com.github.libretube.ui.yt.mini

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
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
import com.github.libretube.ui.yt.SynthwaveColors
import com.github.libretube.ui.yt.SynthwaveGlass
import com.github.libretube.ui.yt.SynthwaveMath
import com.github.libretube.ui.yt.YtDock
import com.github.libretube.ui.yt.YtFonts
import com.github.libretube.ui.yt.YtHapticTexture
import com.github.libretube.ui.yt.YtMotionPrefs
import com.github.libretube.ui.yt.YtPalette

/**
 * YT: the mini player of Synthwave, "Neonkante": a stacked card of night glass on the bar (one
 * surface with it), edged in neon from magenta to cyan with a soft glow; the progress is a
 * magenta seam at the joint, and above its played part the sun glows like a sunset on the
 * horizon (its light lifts gently with the level, guarded: ≤ 3 swings a second), with a small
 * striped sun setting at the head. A cyan disc sits behind play (dark ink on it).
 * - Landing: the card takes the bar like a car's suspension takes a bump (down, a little past,
 *   settling; SynthwaveMath.bump), and a light runs once along its neon edge.
 * - The haptics are a synth's (YtMiniSynthwaveTextures): a kick with a swell on docking.
 * - The video is a SurfaceView: nothing is drawn on it, only around it.
 */
object YtMiniSynthwave : YtMiniLookDesign(YtDesign.SYNTHWAVE) {
    override val spec: YtMiniSpec = YtMiniSpecs.SYNTHWAVE
    override val motion: YtMiniMotion = YtMiniMotion(
        duration = 320,
        interpolator = MotionScene.Transition.INTERPOLATE_EASE_IN_OUT,
        // a calm glide, a little give
        peekStiffness = 300f,
        peekDamping = 0.82f,
        releaseStiffness = 480f,
        releaseDamping = 0.88f,
    )

    // the landing is its own (land below); the shared surface draws no landing of its own
    override val landing: YtMiniLanding get() = YtMiniLanding.NONE

    /** The colours of the card: night glass, the light words of the palette. */
    val card = Color(0xFF140B2C)
    val cardTop = Color(0xFF1E1240)
    val title = Color(0xFFFBF4FF)
    val secondary = Color(0xFFCDBDEB)

    override fun look(palette: YtPalette): YtMiniLook = YtMiniLook(
        spec = spec,
        fill = card, fillTop = cardTop,
        border = palette.accent.copy(alpha = 0.35f),
        title = TextStyle(color = title, fontFamily = YtFonts.geist, fontWeight = FontWeight.Medium, fontSize = 13.5.sp),
        subtitle = TextStyle(color = secondary, fontFamily = YtFonts.geist, fontWeight = FontWeight.Normal, fontSize = 11.5.sp),
        icon = title, playIcon = palette.ink,
        mark = YtMiniMark.DISC, markColor = palette.lime, markSize = 36,
        bar = YtMiniBar.SEAM, barColor = palette.accent, track = Color(0x33FFFFFF), barWidth = 2.5f, trackWidth = 1f,
    )

    @Composable
    override fun Under(scene: YtMiniScene) {
        // the sun's light follows the sound: the mini player holds the pulse on every screen
        FrequencyPulseHost()
        Box(Modifier.fillMaxSize()) {
            super.Under(scene)
            SunSeam(scene)
            NeonEdge(scene)
        }
    }

    /**
     * The neon edge around the card: a soft glow and a crisp line from magenta to cyan along its
     * top and sides; after a landing a light runs along it once. The outline is made once per
     * size while docked (only the preview, which grows the card, makes it again).
     */
    @Composable
    private fun NeonEdge(scene: YtMiniScene) {
        val palette = scene.palette ?: return
        val density = LocalDensity.current.density
        val look = remember(palette) { look(palette) }
        val sweep = remember { Animatable(1f) }
        val landings = scene.landings
        val motionOn = YtMotionPrefs.enabled
        LaunchedEffect(landings) {
            if (landings > 0 && motionOn) {
                sweep.snapTo(0f)
                sweep.animateTo(1f, tween(SWEEP_MS, easing = LinearEasing))
            }
        }
        Box(
            Modifier
                .fillMaxSize()
                .clearAndSetSemantics {}
                .drawWithCache {
                    val restBox = (look.height + look.spec.overlap) * density
                    val rest = bodyPath(look, size.width, size.height, density, open = true, grow = 0f)
                    val edge = Brush.horizontalGradient(listOf(SynthwaveGlass.edgeStart, SynthwaveGlass.edgeEnd))
                    val line = Stroke(1.2f * density)
                    val glowWide = Stroke(7f * density)
                    val glowNear = Stroke(3.5f * density)
                    onDrawBehind {
                        val shown = YtDock.progress.coerceIn(0f, 1f)
                        if (shown <= 0.01f) return@onDrawBehind
                        val peek = YtDock.peek
                        val grown = if (peek > 0f) (size.height - restBox).coerceAtLeast(0f) else 0f
                        val path = if (grown > 0f) bodyPath(look, size.width, size.height, density, open = true, grow = grown) else rest
                        drawPath(path, edge, alpha = 0.1f * shown, style = glowWide)
                        drawPath(path, edge, alpha = 0.18f * shown, style = glowNear)
                        drawPath(path, edge, alpha = 0.9f * shown, style = line)
                        val at = SynthwaveMath.edgeSweep(sweep.value)
                        if (at >= 0f) {
                            // a short band of light runs along the edge once (only during the sweep)
                            val reach = 60f * density
                            val x = -reach + (size.width + reach) * at
                            val light = Brush.horizontalGradient(
                                listOf(Color.White.copy(alpha = 0f), Color.White, Color.White.copy(alpha = 0f)),
                                startX = x, endX = x + reach,
                            )
                            drawPath(path, light, alpha = 0.7f * (1f - at) * shown, style = glowNear)
                        }
                    }
                },
        )
    }

    /**
     * The sunset on the seam: above the played part a glow in the sun's colours rises into the
     * card (its light lifts a little with the level, guarded), and at the head a small striped
     * sun sets on the line. In the preview it gives way.
     */
    @Composable
    private fun SunSeam(scene: YtMiniScene) {
        val palette = scene.palette ?: return
        val density = LocalDensity.current.density
        val mirror = LocalLayoutDirection.current == LayoutDirection.Rtl
        Box(
            Modifier
                .fillMaxSize()
                .clearAndSetSemantics {}
                .graphicsLayer { if (mirror) scaleX = -1f }
                .drawWithCache {
                    val content = look(palette).height * density
                    val y = content - 1.25f * density
                    val glowH = GLOW_DP * density
                    val glow = Brush.verticalGradient(
                        listOf(Color.Transparent, SynthwaveColors.sunLow.copy(alpha = 0.55f), SynthwaveColors.sunMid),
                        startY = y - glowH, endY = y,
                    )
                    val sunR = 5f * density
                    val sun = Brush.verticalGradient(
                        listOf(SynthwaveColors.sunTop, SynthwaveColors.sunMid, SynthwaveColors.sunBottom),
                        startY = -sunR, endY = sunR,
                    )
                    val half = Path().apply {
                        // the upper half of the disc: the sun sets on the seam
                        moveTo(-sunR, 0f)
                        arcTo(Rect(Offset.Zero, sunR), 180f, 180f, false)
                        close()
                    }
                    onDrawBehind {
                        val shown = (1f - YtDock.peek.coerceIn(0f, 1f)) * ((YtDock.progress - 0.9f) / 0.1f).coerceIn(0f, 1f)
                        if (shown <= 0.01f) return@onDrawBehind
                        val left = 1f * density
                        val played = (size.width - 2f * left) * scene.info.fraction.coerceIn(0f, 1f)
                        if (played <= 0f) return@onDrawBehind
                        val light = FrequencyPulse.glow.floatValue
                        drawRect(glow, Offset(left, y - glowH), Size(played, glowH), alpha = (0.2f + 0.16f * light) * shown)
                        val headX = left + played
                        drawContext.canvas.save()
                        drawContext.transform.translate(headX, y)
                        drawPath(half, sun, alpha = shown)
                        // two slits through the little sun
                        drawRect(card, Offset(-sunR, -sunR * 0.42f), Size(sunR * 2f, sunR * 0.14f), alpha = shown)
                        drawRect(card, Offset(-sunR, -sunR * 0.2f), Size(sunR * 2f, sunR * 0.2f), alpha = shown)
                        drawContext.canvas.restore()
                    }
                },
        )
    }

    override fun haptic(moment: YtMoment): YtHapticTexture = YtMiniSynthwaveTextures.of(moment)

    /** The landing: player and bar take it like a car's suspension takes a bump. */
    override fun land(views: YtMiniViews): Animator {
        val surface = views.under
        surface.pivotX = surface.width / 2f
        surface.pivotY = surface.height.toFloat()
        return ValueAnimator.ofFloat(0f, 1f).apply {
            duration = SynthwaveMath.LAND_MS
            interpolator = null
            addUpdateListener { animator ->
                val b = SynthwaveMath.bump(animator.animatedValue as Float)
                // the bar takes most of it (both dip together), the player a little more
                val shared = BUMP_DP * views.density * b * 0.6f
                val own = BUMP_DP * views.density * b * 0.4f
                views.all.forEach { it.translationY = own + shared }
                // the springs give a little: the card widens as it sinks
                surface.scaleX = 1f + 0.012f * b
                YtDock.dip = shared
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    views.all.forEach { it.translationY = 0f }
                    surface.scaleX = 1f
                    YtDock.dip = 0f
                }
            })
            start()
        }
    }

    private const val BUMP_DP = 5f
    private const val GLOW_DP = 16f
    private const val SWEEP_MS = 700
}
