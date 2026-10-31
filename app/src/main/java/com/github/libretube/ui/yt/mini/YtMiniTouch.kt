package com.github.libretube.ui.yt.mini

import com.github.libretube.ui.views.SingleViewTouchableMotionLayout

/**
 * YT: the touches of the docked mini player that the page's MotionLayout hands over: a long press
 * on the picture shows the preview, the finger then picks a part of it (letting go on the next
 * video plays it, on the pill toggles play, elsewhere just closes it), and a double tap toggles
 * play where the design shows no play button.
 */
class YtMiniTouch(private val host: YtMiniHost) : SingleViewTouchableMotionLayout.MiniTouch {
    override val takesDoubleTap: Boolean get() = host.design.takesDoubleTap

    override fun onLongPress(x: Float, y: Float): Boolean = host.peekStart(x, y)

    override fun onPeekMove(x: Float, y: Float) = host.peekMove(x, y)

    override fun onPeekEnd(x: Float, y: Float, cancelled: Boolean) = host.peekEnd(x, y, cancelled)

    override fun onDoubleTap(x: Float, y: Float): Boolean {
        if (!host.design.takesDoubleTap) return false
        host.togglePlay()
        return true
    }
}
