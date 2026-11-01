package com.github.libretube.ui.yt.mini

import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.Density

/**
 * YT: the top corners of the Glas slab, from the height of the shape it rounds: the bar's own
 * radius while the slab is low, the slab's once it has risen. A corner size rather than a shape
 * of its own, so the slab stays a RoundedCornerShape (the glass's lens takes only those) and its
 * outline follows the slab's height without composing again.
 */
private data class GlassSlabCorner(val bar: Float, val barRadius: Float, val slabRadius: Float) : CornerSize {
    override fun toPx(shapeSize: Size, density: Density): Float =
        YtGlassMath.slabTop(shapeSize.height - bar, barRadius, slabRadius)
}

/** The Glas bar grown into the slab: [bar] px of the bar at its foot, round at the bottom. */
fun ytGlassSlabShape(bar: Float, slabRadius: Float): RoundedCornerShape {
    val top = GlassSlabCorner(bar, bar / 2f, slabRadius)
    val bottom = CornerSize(bar / 2f)
    return RoundedCornerShape(topStart = top, topEnd = top, bottomEnd = bottom, bottomStart = bottom)
}
