package com.kyant.backdrop.internal

/*
 * Vendored from Kyant0/AndroidLiquidGlass (backdrop 2.0.1, commit 65ab177), Apache License 2.0,
 * see third_party/backdrop/LICENSE. YT: flattened to Android (no expect/actual),
 * without the kyant shapes and JetBrains annotations dependencies.
 */
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.requireDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.toIntSize

internal fun DrawScope.recordLayer(
    node: DelegatableNode,
    layer: GraphicsLayer,
    size: IntSize = this.size.toIntSize(),
    block: DrawScope.() -> Unit
) {
    val density = node.requireDensity()
    layer.record(size) {
        val prevDensity = drawContext.density
        drawContext.density = density
        try {
            this.block()
        } finally {
            drawContext.density = prevDensity
        }
    }
}
