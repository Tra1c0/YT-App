package com.github.libretube.ui.yt

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue

/**
 * YT: how many sheets and overlays of the home are open right now (the quick sheet, the
 * Feed-Regler, the intent chooser, "Warum sehe ich das?"). The floating chips and pills of the
 * home ([YtHomePills]) live in a layer of their own above the screens, so a sheet drawn by a
 * screen would otherwise stay under them; they fade out while this is above 0.
 */
object YtOverlays {
    var open by mutableIntStateOf(0)
        private set

    /** Whether any overlay is open (snapshot state, read it in composition). */
    val any: Boolean get() = open > 0

    /** Counts this overlay as open while [shown]. */
    @Composable
    fun Open(shown: Boolean) {
        DisposableEffect(shown) {
            if (shown) open++
            onDispose { if (shown) open = (open - 1).coerceAtLeast(0) }
        }
    }
}
