package com.github.libretube.ui.yt

import android.view.View
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * YT: what the system covers at the left and right edge of the window. Upright that is nothing;
 * turned, the three-button navigation moves to a side and a camera cutout may sit at the other.
 * The activity's root takes all insets for itself (only top and bottom were used), so the bar and
 * the docked mini player ran under them: their ends, with the mini player's buttons, could not be
 * seen or tapped.
 */
object YtSideInsets {
    /** Left and right in px: the system bars ([bars]) or the display cutout, whichever is wider. */
    fun of(root: View, bars: Insets): Pair<Int, Int> {
        val cutout = ViewCompat.getRootWindowInsets(root)
            ?.getInsets(WindowInsetsCompat.Type.displayCutout())
            ?: Insets.NONE
        return maxOf(bars.left, cutout.left) to maxOf(bars.right, cutout.right)
    }
}
