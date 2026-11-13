package com.github.libretube.ui.yt

import com.github.libretube.ui.yt.news.YtNewsChannels
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import com.github.libretube.helpers.ImageHelper
import com.github.libretube.helpers.YtDesign
import com.github.libretube.ui.models.CommentsViewModel

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
        val palette = design.palette ?: return null

        // the pages read the comments through the activity's comment state (Side, Movo), so the
        // classic sheet and the page share the pages fetched and what the scene lets them show
        val comments = ViewModelProvider(fragment.requireActivity())[CommentsViewModel::class.java]
        val controller = YtWatchController(comments)
        val composeView = ComposeView(fragment.requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                YtTheme(palette) {
                    // round 29: timestamps of the description's link overview seek the player
                    CompositionLocalProvider(LocalYtDescriptionSeek provides actions.onSeek) {
                    // YT: a keyword for the user's topic, offered while a topic video plays
                    val videoId = controller.videoId
                    val streams = controller.streams
                    LaunchedEffect(videoId, streams) {
                        if (videoId != null && streams != null) YtTopicSuggest.watch(controller, videoId, streams, actions.currentPosition)
                        // YT: a video of the category "News & Politics" marks its channel for the News
                        if (streams != null) YtNewsChannels.noteWatched(streams)
                    }
                    DisposableEffect(Unit) { onDispose { YtTopicSuggest.leave(controller) } }
                    val enter = rememberYtEnterState()
                    Column {
                    YtTopicSuggestPill(palette)
                    Box(Modifier.ytEnter(enter, controller.videoId ?: "", 0)) {
                    if (design == YtDesign.MOVO) {
                        MovoWatchDetails(
                            palette = palette,
                            imageLoader = ImageHelper.loader(),
                            controller = controller,
                            actions = actions,
                        )
                    } else if (design == YtDesign.SIDE) {
                        SideWatchDetails(
                            palette = palette,
                            imageLoader = ImageHelper.loader(),
                            controller = controller,
                            actions = actions,
                        )
                    } else {
                        // every other design has a page of its own (YtWatchPages.kt)
                        YtWatchPage(
                            design = design,
                            palette = palette,
                            imageLoader = ImageHelper.loader(),
                            controller = controller,
                            actions = actions,
                        )
                    }
                    }
                    }
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
