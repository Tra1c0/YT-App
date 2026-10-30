package com.kyant.backdrop.shadow

/*
 * Vendored from Kyant0/AndroidLiquidGlass (backdrop 2.0.1, commit 65ab177), Apache License 2.0,
 * see third_party/backdrop/LICENSE. YT: flattened to Android (no expect/actual),
 * without the kyant shapes and JetBrains annotations dependencies.
 */
import androidx.annotation.FloatRange
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp

@Immutable
data class Shadow(
    val radius: Dp = 24f.dp,
    val offset: DpOffset = DpOffset(0f.dp, radius / 6f),
    val color: Color = Color.Black.copy(alpha = 0.1f),
    @param:FloatRange(from = 0.0, to = 1.0) val alpha: Float = 1f,
    val blendMode: BlendMode = DrawScope.DefaultBlendMode
) {

    companion object {

        @Stable
        val Default: Shadow = Shadow()
    }
}
