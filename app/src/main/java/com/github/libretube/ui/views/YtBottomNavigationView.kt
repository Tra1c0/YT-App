package com.github.libretube.ui.views

import android.annotation.SuppressLint
import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import com.google.android.material.bottomnavigation.BottomNavigationView

/**
 * YT: the bottom navigation, which a design can turn into a floating bar. The material view
 * consumes every touch on its area; while [floating] is set, touches beside the bar the design
 * draws reach the content that scrolls below it.
 */
class YtBottomNavigationView(
    context: Context,
    attrs: AttributeSet? = null,
) : BottomNavigationView(context, attrs) {
    var floating = false

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean =
        if (floating) false else super.onTouchEvent(event)
}
