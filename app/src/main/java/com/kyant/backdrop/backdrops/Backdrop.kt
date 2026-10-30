package com.kyant.backdrop.backdrops

/*
 * Vendored from Kyant0/AndroidLiquidGlass (backdrop 2.0.1, commit 65ab177), Apache License 2.0,
 * see third_party/backdrop/LICENSE. YT: flattened to Android (no expect/actual),
 * without the kyant shapes and JetBrains annotations dependencies.
 */
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.unit.Density
import com.kyant.backdrop.Backdrop

@Composable
fun rememberBackdrop(
    backdrop: Backdrop,
    onDraw: DrawScope.(drawBackdrop: DrawScope.() -> Unit) -> Unit
): Backdrop {
    return remember(backdrop, onDraw) {
        Backdrop(backdrop, onDraw)
    }
}

@Immutable
private class Backdrop(
    val backdrop: Backdrop,
    val onDraw: DrawScope.(drawBackdrop: DrawScope.() -> Unit) -> Unit
) : Backdrop {

    override val isCoordinatesDependent: Boolean = backdrop.isCoordinatesDependent

    override fun DrawScope.drawBackdrop(
        density: Density,
        coordinates: LayoutCoordinates?,
        layerBlock: (GraphicsLayerScope.() -> Unit)?
    ) {
        onDraw { with(backdrop) { drawBackdrop(density, coordinates, layerBlock) } }
    }
}
