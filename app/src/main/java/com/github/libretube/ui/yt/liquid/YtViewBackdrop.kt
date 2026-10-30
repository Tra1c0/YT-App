package com.github.libretube.ui.yt.liquid

import android.view.View
import android.view.ViewTreeObserver
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.Density
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.internal.InverseLayerScope

/**
 * YT: the screens below the floating navigation as a backdrop for its glass. The navigation and
 * the screens are separate views, so the glass draws the screen container itself: on a
 * hardware canvas that only references the display lists the screens already recorded. When
 * the screens change (scrolling, images loading), the glass is redrawn in the same frame.
 */
class YtViewBackdrop(
    private val source: View,
    private val background: Color,
) : Backdrop {
    override val isCoordinatesDependent: Boolean = true

    private var generation by mutableIntStateOf(0)
    private val location = IntArray(2)
    private var inverseLayerScope: InverseLayerScope? = null

    /** Redraws the glass whenever the screens below are about to be drawn anew. */
    val preDrawListener = ViewTreeObserver.OnPreDrawListener {
        if (source.isDirty) {
            generation++
            Snapshot.sendApplyNotifications()
        }
        true
    }

    override fun DrawScope.drawBackdrop(
        density: Density,
        coordinates: LayoutCoordinates?,
        layerBlock: (GraphicsLayerScope.() -> Unit)?,
    ) {
        // read, so that the glass is drawn again for a new generation of the screens
        generation
        val coordinates = coordinates ?: return
        if (!source.isAttachedToWindow || source.width == 0 || source.height == 0) return
        val glass = coordinates.positionInWindow()
        source.getLocationInWindow(location)
        withTransform({
            if (layerBlock != null) {
                val scope = inverseLayerScope?.apply { reset() } ?: InverseLayerScope().also { inverseLayerScope = it }
                with(scope) { inverseTransform(density, layerBlock) }
            }
            translate(location[0] - glass.x, location[1] - glass.y)
        }) {
            drawRect(background, size = Size(source.width.toFloat(), source.height.toFloat()))
            drawIntoCanvas { source.draw(it.nativeCanvas) }
        }
    }
}
