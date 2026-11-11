package com.github.libretube.ui.yt

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * YT: the "neon chrome" of the design Synthwave: the dark glass the words sit on, the neon edge
 * from magenta to cyan, the soft glow around buttons, the chrome gradient of the wordmark and
 * the logo (a striped sun over a neon floor). All of it is static: the scene behind
 * (SynthwaveScene) moves, the chrome and the words do not.
 */

/** The dark glass of Synthwave's cards and pills over the scene. */
object SynthwaveGlass {
    /**
     * The glass, top and bottom: the night surface at 86 / 90 % alpha. Over the brightest spot of
     * the scene (the sun's top) every text role still reads 4.5:1 (YtSynthwavePaletteTest).
     */
    val top = Color(0xDB1A0F36)
    val bottom = Color(0xE6120A28)

    /** A denser glass for small pills (chips, badges) whose words are smaller. */
    val pill = Color(0xE61A0F36)

    /** The neon edge: magenta to cyan. */
    val edgeStart = Color(0xFFFF3FD8)
    val edgeEnd = Color(0xFF36E4FF)

    /** The edge's alpha on cards (pills and buttons take it at full strength). */
    const val EDGE_ALPHA = 0.55f

    val fill: Brush get() = Brush.verticalGradient(listOf(top, bottom))

    fun edge(alpha: Float = EDGE_ALPHA): Brush =
        Brush.linearGradient(listOf(edgeStart.copy(alpha = alpha), edgeEnd.copy(alpha = alpha)))

    /** The chrome of the wordmark: sky white, a hard horizon, then warm sunset metal. */
    val chrome: Brush
        get() = Brush.verticalGradient(
            0f to Color(0xFFFFFFFF),
            0.42f to Color(0xFFBFE6FF),
            0.5f to Color(0xFF5B4BB0),
            0.53f to Color(0xFFFF8AE8),
            1f to Color(0xFFFFD27A),
        )
}

/** Dark glass with a neon edge from magenta to cyan, clipped to [shape]. */
fun Modifier.synthwaveGlass(shape: Shape, edge: Float = SynthwaveGlass.EDGE_ALPHA, width: Dp = 1.dp): Modifier = this
    .clip(shape)
    .background(SynthwaveGlass.fill)
    .then(if (edge > 0f) Modifier.border(width, SynthwaveGlass.edge(edge), shape) else Modifier)

/**
 * A soft neon glow of [color] around [shape] (drawn behind, outside the element): three wide
 * strokes of falling light along its outline, [reach] wide. Static, made once per size.
 */
fun Modifier.synthwaveGlow(shape: Shape, color: Color, reach: Dp = 6.dp, strength: Float = 1f): Modifier = drawWithCache {
    val path = Path().apply { addOutline(shape.createOutline(size, layoutDirection, this@drawWithCache)) }
    val r = reach.toPx()
    val strokes = arrayOf(Stroke(r * 2f), Stroke(r * 1.3f), Stroke(r * 0.6f))
    val alphas = floatArrayOf(0.06f, 0.09f, 0.14f)
    onDrawBehind {
        for (i in strokes.indices) drawPath(path, color, alpha = alphas[i] * strength, style = strokes[i])
    }
}

/** The retro display face with a neon glow of [glow] (Audiowide; words to be read stay Geist). */
fun synthwaveDisplay(size: TextUnit, color: Color, glow: Color = SynthwaveGlass.edgeStart, spacing: TextUnit = 0.6.sp): TextStyle = TextStyle(
    fontFamily = YtFonts.audiowide,
    fontSize = size,
    letterSpacing = spacing,
    color = color,
    shadow = Shadow(glow.copy(alpha = 0.75f), Offset.Zero, 14f),
)

/** The wordmark "SYNTHWAVE" in chrome with a magenta glow. */
@Composable
fun SynthwaveWordmark(size: TextUnit = 19.sp, modifier: Modifier = Modifier) {
    val chrome = remember { SynthwaveGlass.chrome }
    Text(
        text = "SYNTHWAVE",
        maxLines = 1,
        style = TextStyle(
            brush = chrome,
            fontFamily = YtFonts.audiowide,
            fontSize = size,
            letterSpacing = (size.value * 0.06f).sp,
            shadow = Shadow(Color(0xCCFF3FD8), Offset(0f, 1f), 16f),
        ),
        modifier = modifier,
    )
}

/**
 * The logo of Synthwave: a striped sun (yellow, orange, pink) setting on a neon floor, in a
 * chrome ring. Drawn once per size; it does not move.
 */
@Composable
fun SynthwaveLogo(modifier: Modifier = Modifier) {
    Canvas(modifier.clearAndSetSemantics {}) {
        val r = size.minDimension / 2f
        val c = center
        val ring = Stroke(r * 0.12f)
        val inner = r * 0.86f
        val disc = Path().apply { addOval(Rect(c, inner)) }
        drawCircle(Color(0xFF0D0620), inner, c)
        clipPath(disc) {
            // the sky, the sun, its slits
            drawRect(Brush.verticalGradient(listOf(Color(0xFF240A45), Color(0xFF5A1258)), startY = c.y - inner, endY = c.y + inner * 0.2f), Offset(c.x - inner, c.y - inner), Size(inner * 2f, inner * 1.2f))
            val sunR = inner * 0.62f
            val sunY = c.y + inner * 0.05f
            drawCircle(
                Brush.verticalGradient(
                    listOf(SynthwaveColors.sunTop, SynthwaveColors.sunMid, SynthwaveColors.sunBottom),
                    startY = sunY - sunR, endY = sunY + sunR,
                ),
                sunR,
                Offset(c.x, sunY),
            )
            for (i in 0 until 3) {
                val top = sunY + sunR * (0.05f + 0.22f * i)
                drawRect(Color(0xFF3A0D50), Offset(c.x - sunR, top), Size(sunR * 2f, sunR * (0.05f + 0.035f * i)))
            }
            // the floor and its neon grid
            val floorY = c.y + inner * 0.2f
            drawRect(Color(0xFF12052A), Offset(c.x - inner, floorY), Size(inner * 2f, inner))
            drawLine(SynthwaveColors.horizon, Offset(c.x - inner, floorY), Offset(c.x + inner, floorY), strokeWidth = r * 0.06f)
            val gridW = r * 0.045f
            for (j in -3..3) {
                drawLine(SynthwaveColors.grid, Offset(c.x + j * inner * 0.08f, floorY), Offset(c.x + j * inner * 0.42f, c.y + inner), strokeWidth = gridW)
            }
            for (k in 1..3) {
                val y = floorY + (inner * 0.8f) * (k * k) / 10f
                drawLine(SynthwaveColors.grid, Offset(c.x - inner, y), Offset(c.x + inner, y), strokeWidth = gridW)
            }
        }
        drawCircle(SynthwaveGlass.chrome, r - ring.width / 2f, c, style = ring)
    }
}
