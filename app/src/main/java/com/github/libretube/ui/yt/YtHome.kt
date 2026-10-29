package com.github.libretube.ui.yt

import android.view.ViewGroup
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import com.github.libretube.R
import com.github.libretube.api.MediaServiceRepository
import com.github.libretube.api.TrendingCategory
import com.github.libretube.api.obj.StreamItem
import com.github.libretube.constants.PreferenceKeys
import com.github.libretube.extensions.toID
import com.github.libretube.helpers.ImageHelper
import com.github.libretube.helpers.NavigationHelper
import com.github.libretube.helpers.PreferenceHelper
import com.github.libretube.helpers.YtDesign
import com.github.libretube.parcelable.PlayerData
import com.github.libretube.ui.models.HomeViewModel
import com.google.android.material.bottomnavigation.BottomNavigationView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * YT: puts the home screen of the selected design on top of the classic home layout.
 * The classic layout keeps loading the data, the design screen only shows it.
 */
object YtHome {
    /** @return true if a design screen was attached. */
    fun attach(
        fragment: Fragment,
        root: ViewGroup,
        viewModel: HomeViewModel,
        refresh: () -> Unit,
    ): Boolean {
        val design = YtDesign.current()
        if (design != YtDesign.YOUTUBE && design != YtDesign.MOVO && design != YtDesign.AFTER) return false

        val composeView = ComposeView(fragment.requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val feed by viewModel.feed.observeAsState()
                val trending by viewModel.trending.observeAsState()
                val isLoading by viewModel.isLoading.observeAsState(true)
                var filter by remember { mutableStateOf(YT_FILTER_ALL) }
                var movoTab by remember { mutableStateOf(MOVO_TAB_DISCOVER) }

                if (design == YtDesign.AFTER) {
                    val continueWatching by viewModel.continueWatching.observeAsState()
                    var afterTab by remember { mutableStateOf(AFTER_TAB_SELECTION) }
                    var reload by remember { mutableIntStateOf(0) }
                    // "Filme" and "Live" load their trending category directly, so the
                    // trending category chosen elsewhere in the app stays untouched.
                    val tabVideos by produceState<List<StreamItem>?>(null, afterTab, reload) {
                        value = null
                        if (afterTab == AFTER_TAB_SELECTION) return@produceState
                        val region = PreferenceHelper.getTrendingRegion(context)
                        value = withContext(Dispatchers.IO) {
                            runCatching {
                                MediaServiceRepository.instance
                                    .getTrending(region, TrendingCategory.valueOf(afterTab))
                            }.getOrDefault(emptyList())
                        }
                    }
                    val selection = afterTab == AFTER_TAB_SELECTION
                    YtTheme(YtPalettes.After) {
                        AfterHomeScreen(
                            palette = YtPalettes.After,
                            imageLoader = ImageHelper.loader(),
                            videos = if (selection) {
                                feed?.takeIf { it.isNotEmpty() } ?: trending?.second?.streams
                            } else tabVideos,
                            continueWatching = continueWatching,
                            tab = afterTab,
                            isLoading = if (selection) isLoading else tabVideos == null,
                            onSelectTab = { afterTab = it },
                            onRefresh = { if (selection) refresh() else reload++ },
                            onOpenVideo = { video ->
                                NavigationHelper.navigateVideo(context, PlayerData(video.url!!.toID()))
                            },
                            onShowAll = {
                                fragment.activity?.findViewById<BottomNavigationView>(R.id.bottomNav)
                                    ?.selectedItemId = R.id.libraryFragment
                            },
                        )
                    }
                } else if (design == YtDesign.MOVO) {
                    YtTheme(YtPalettes.Movo) {
                        MovoHomeScreen(
                            palette = YtPalettes.Movo,
                            imageLoader = ImageHelper.loader(),
                            discover = trending?.second?.streams,
                            following = feed,
                            tab = movoTab,
                            isLoading = isLoading,
                            onSelectTab = { movoTab = it },
                            onRefresh = refresh,
                            onOpenVideo = { video ->
                                NavigationHelper.navigateVideo(context, PlayerData(video.url!!.toID()))
                            },
                        )
                    }
                } else YtTheme(YtPalettes.YouTube) {
                    YouTubeHomeScreen(
                        palette = YtPalettes.YouTube,
                        imageLoader = ImageHelper.loader(),
                        feed = feed,
                        trending = trending?.second?.streams,
                        categories = MediaServiceRepository.instance.getTrendingCategories(),
                        selectedFilter = filter,
                        isLoading = isLoading,
                        onSelectFilter = { key ->
                            filter = key
                            if (key != YT_FILTER_ALL) {
                                PreferenceHelper.putString(PreferenceKeys.TRENDING_CATEGORY, key)
                                refresh()
                            }
                        },
                        onRefresh = refresh,
                        onOpenVideo = { video ->
                            NavigationHelper.navigateVideo(context, PlayerData(video.url!!.toID()))
                        },
                        onOpenChannel = { video ->
                            NavigationHelper.navigateChannel(context, video.uploaderUrl)
                        },
                    )
                }
            }
        }
        root.addView(
            composeView,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        return true
    }
}
