package com.github.libretube.ui.yt

import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import com.github.libretube.helpers.ImageHelper
import com.github.libretube.helpers.YtDesign

/**
 * YT: puts the video page details of the selected design at the top of the area below the
 * player. The classic views stay in place, hidden, so their behavior keeps working.
 */
object YtWatch {
    /** @return the controller to update, or null if the classic layout stays. */
    fun attach(
        fragment: Fragment,
        container: LinearLayout,
        actions: YtWatchActions,
    ): YtWatchController? {
        val design = YtDesign.current()
        if (design != YtDesign.YOUTUBE && design != YtDesign.MOVO) return null

        val controller = YtWatchController()
        val composeView = ComposeView(fragment.requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                if (design == YtDesign.MOVO) {
                    YtTheme(YtPalettes.Movo) {
                        MovoWatchDetails(
                            palette = YtPalettes.Movo,
                            imageLoader = ImageHelper.loader(),
                            controller = controller,
                            actions = actions,
                        )
                    }
                } else {
                    YtTheme(YtPalettes.YouTube) {
                        YouTubeWatchDetails(
                            palette = YtPalettes.YouTube,
                            imageLoader = ImageHelper.loader(),
                            controller = controller,
                            actions = actions,
                        )
                    }
                }
            }
        }
        container.addView(
            composeView,
            0,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        return controller
    }
}
