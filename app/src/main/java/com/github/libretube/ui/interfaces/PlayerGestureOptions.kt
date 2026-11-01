package com.github.libretube.ui.interfaces

interface PlayerGestureOptions {

    fun onSingleTap(areControlsLocked: Boolean)

    fun onDoubleTapCenterScreen()

    fun onDoubleTapLeftScreen()

    fun onDoubleTapRightScreen()

    fun onSwipeLeftScreen(distanceY: Float, positionY: Float)

    fun onSwipeRightScreen(distanceY: Float, positionY: Float)

    fun onSwipeCenterScreen(distanceY: Float, positionY: Float)

    fun onSwipeEnd()

    fun onZoom()

    fun onMinimize()

    fun onFullscreenChange(isFullscreen: Boolean)

    /** A long press began at [x], [y] (view px). */
    fun onLongPress(x: Float, y: Float)

    /** YT: the finger moved by [dx], [dy] (px) from where the long press began. */
    fun onLongPressDrag(dx: Float, dy: Float)

    /**
     * The long press ended: lifted at [dx], [dy] from where it began, or [cancelled] (the gesture
     * was taken away, or a second finger came).
     */
    fun onLongPressEnd(dx: Float, dy: Float, cancelled: Boolean)

    /**
     *  Returns a pair of the width and height of the view this listener is used for
     *  These measures change when the screen orientation changes or fullscreen is entered, thus
     *  needs to be refreshed manually all the time when needed.
     */
    fun getViewMeasures(): Pair<Int, Int>
}
