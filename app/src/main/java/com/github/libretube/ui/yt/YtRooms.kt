package com.github.libretube.ui.yt

import android.view.ViewGroup
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.view.children
import androidx.core.view.isGone
import androidx.fragment.app.Fragment
import com.github.libretube.api.MediaServiceRepository
import com.github.libretube.api.TrendingCategory
import com.github.libretube.api.obj.StreamItem
import com.github.libretube.extensions.toID
import com.github.libretube.helpers.ImageHelper
import com.github.libretube.helpers.NavigationHelper
import com.github.libretube.helpers.PreferenceHelper
import com.github.libretube.helpers.YtDesign
import com.github.libretube.parcelable.PlayerData
import com.github.libretube.ui.fragments.TrendsFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * YT: with the design "After", the trends tab becomes "Räume" and shows the current live
 * streams as rooms instead of the classic trending tabs.
 */
object YtRooms {
    /** @return true if the rooms replace the classic trends layout. */
    fun attach(fragment: Fragment, root: ViewGroup): Boolean {
        if (YtDesign.current() != YtDesign.AFTER) return false

        root.children.forEach { it.isGone = true }
        val composeView = ComposeView(fragment.requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                var reload by remember { mutableIntStateOf(0) }
                var loading by remember { mutableStateOf(true) }
                val rooms by produceState<List<StreamItem>?>(null, reload) {
                    loading = true
                    val region = PreferenceHelper.getTrendingRegion(context)
                    value = withContext(Dispatchers.IO) {
                        runCatching {
                            MediaServiceRepository.instance.getTrending(region, TrendingCategory.LIVE)
                        }.getOrDefault(emptyList())
                    }
                    loading = false
                }

                YtTheme(YtPalettes.After) {
                    AfterRoomsScreen(
                        palette = YtPalettes.After,
                        imageLoader = ImageHelper.loader(),
                        rooms = rooms,
                        isLoading = loading,
                        onRefresh = { reload++ },
                        onChangeRegion = {
                            TrendsFragment.showChangeRegionDialog(context) { reload++ }
                        },
                        onEnterRoom = { room ->
                            NavigationHelper.navigateVideo(context, PlayerData(room.url!!.toID()))
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
