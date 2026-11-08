package com.github.libretube.ui.yt

import android.content.Context
import android.util.AttributeSet
import android.view.View
import androidx.core.widget.NestedScrollView

/**
 * YT: a NestedScrollView that is never taller than [maxHeightFraction] of the screen, so a long
 * text scrolls inside it and the views and dialog buttons below always stay visible.
 */
class YtMaxHeightScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : NestedScrollView(context, attrs) {
    var maxHeightFraction = 0.5f

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val cap = (resources.displayMetrics.heightPixels * maxHeightFraction).toInt()
        val mode = View.MeasureSpec.getMode(heightMeasureSpec)
        val size = View.MeasureSpec.getSize(heightMeasureSpec)
        val limit = if (mode == View.MeasureSpec.UNSPECIFIED) cap else minOf(size, cap)
        super.onMeasure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec(limit, View.MeasureSpec.AT_MOST))
    }
}
