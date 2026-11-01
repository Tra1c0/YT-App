package com.github.libretube.ui.yt.mini

/** MotionLayout publishes endpoint progress before all child rectangles reach that endpoint. */
internal object YtRestGeometry {
    fun valid(
        player: YtBox,
        box: YtBox,
        pageHeight: Int,
        docked: Boolean,
        oppositePlayer: YtBox?,
        oppositeBox: YtBox?,
    ): Boolean {
        if (pageHeight <= 0 || player.width <= 0f || player.height <= 0f || box.width <= 0f || box.height <= 0f) return false
        if (oppositePlayer != null && oppositeBox != null) {
            return if (docked) player.height < oppositePlayer.height && box.top > oppositeBox.top
            else player.height > oppositePlayer.height && box.top < oppositeBox.top
        }
        // Before both endpoints have been seen, never call a full-size rectangle docked.
        return if (docked) box.top >= pageHeight / 2f else box.top < pageHeight / 2f
    }
}
