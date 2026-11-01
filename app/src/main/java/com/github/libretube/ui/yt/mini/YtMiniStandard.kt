package com.github.libretube.ui.yt.mini

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.constraintlayout.widget.ConstraintSet
import com.github.libretube.helpers.ThemeHelper
import com.github.libretube.helpers.YtDesign
import com.github.libretube.ui.yt.YtHapticTexture

/**
 * YT: the classic LibreTube mini player (no design). It keeps its look and layout (the end set of
 * player_scene.xml, its views) and only gets what every player has: the long-press preview with the
 * next video in Material 3 colors of the app's theme, the drop target, and the system's touch
 * feedback for each moment.
 */
object YtMiniStandard : YtMiniDesign(YtDesign.STANDARD) {
    override val spec: YtMiniSpec = YtMiniSpecs.STANDARD
    override val motion: YtMiniMotion = YtMiniMotion(duration = 250)
    override val classic: Boolean get() = true

    /** The classic layout measures in plain dp. */
    override fun density(context: Context): Float = context.resources.displayMetrics.density

    override fun constrain(set: ConstraintSet, g: YtMiniGeometry, peek: YtMiniPeekGeometry?, state: YtMiniState) {
        // docked, the end set stays as player_scene.xml has it
        if (state == YtMiniState.PEEK) super.constrain(set, g, peek, state)
    }

    override fun haptic(moment: YtMoment): YtHapticTexture = YtMiniTextures.system(moment)

    @Composable
    private fun style(): YtMiniParts.Style {
        val context = LocalContext.current
        return remember(context) {
            fun color(attr: Int) = Color(ThemeHelper.getThemeColor(context, attr))
            val onSurface = color(com.google.android.material.R.attr.colorOnSurface)
            val secondary = color(com.google.android.material.R.attr.colorOnSurfaceVariant)
            YtMiniParts.Style(
                surface = color(com.google.android.material.R.attr.colorSurfaceContainerHigh),
                onSurface = onSurface,
                secondary = secondary,
                pill = color(androidx.appcompat.R.attr.colorPrimary),
                onPill = color(com.google.android.material.R.attr.colorOnPrimary),
                accent = color(androidx.appcompat.R.attr.colorPrimary),
                title = TextStyle(color = onSurface, fontWeight = FontWeight.Medium, fontSize = 14.sp),
                label = TextStyle(color = secondary, fontWeight = FontWeight.Medium, fontSize = 12.sp),
                thumbRadius = 8.dp,
                rowRadius = 16.dp,
            )
        }
    }

    @Composable
    override fun Under(scene: YtMiniScene) {
        val style = style()
        Box(Modifier.fillMaxSize()) {
            YtMiniParts.Peek(scene, style)
            // the drop target over the docked strip; it gives way to the preview
            BoxWithConstraints(
                Modifier
                    .fillMaxWidth()
                    .requiredHeight((spec.height + spec.overlap).dp)
                    .align(Alignment.TopStart)
                    .graphicsLayer { alpha = YtMiniParts.restFade(scene.peek()) },
            ) {
                val density = LocalDensity.current.density
                val g = remember(constraints.maxWidth, density) { geometry(constraints.maxWidth.toFloat(), density) }
                val outline: (Size) -> Path = remember(density) { YtMiniParts.roundedOutline(0f, topOnly = false) }
                YtMiniParts.Drop(scene, style, g.text, outline)
            }
        }
    }

    @Composable
    override fun Over(scene: YtMiniScene) {
        YtMiniParts.Flash(scene, YtMiniParts.FlashColors(Color(0x8A000000), Color.White))
    }
}
