package com.github.libretube.ui.yt

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import com.github.libretube.R
import com.github.libretube.api.MediaServiceRepository
import com.github.libretube.api.TrendingCategory
import com.github.libretube.api.obj.StreamItem
import com.github.libretube.constants.IntentData
import com.github.libretube.db.DatabaseHolder.Database
import com.github.libretube.extensions.toID
import com.github.libretube.helpers.ImageHelper
import com.github.libretube.helpers.NavigationHelper
import com.github.libretube.helpers.PreferenceHelper
import com.github.libretube.helpers.YtDesign
import com.github.libretube.parcelable.PlayerData
import com.github.libretube.ui.dialogs.AddToPlaylistDialog
import com.github.libretube.ui.models.HomeViewModel
import com.google.android.material.bottomnavigation.BottomNavigationView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * YT: home of the second round of designs (Clay, Editorial, Glas, Pop) and of the inspiration
 * designs (Formen, Frost). Collects the data the screens show and dispatches to the design.
 */
@Composable
internal fun YtRound2Home(
    fragment: Fragment,
    design: YtDesign,
    viewModel: HomeViewModel,
    refresh: () -> Unit,
    charts: List<StreamItem>?,
) {
    val palette = design.palette ?: return
    val context = LocalContext.current
    val feed by viewModel.feed.observeAsState()
    val trending by viewModel.trending.observeAsState()
    val continueWatching by viewModel.continueWatching.observeAsState()
    val isLoading by viewModel.isLoading.observeAsState(true)
    var tab by rememberSaveable { mutableStateOf(YT_TAB_FOR_YOU) }
    var category by rememberSaveable { mutableStateOf(YT_FILTER_ALL) }
    var reload by remember { mutableIntStateOf(0) }

    val live by produceState<List<StreamItem>?>(null, reload) {
        value = loadTrending(context, TrendingCategory.LIVE)
    }
    val categoryVideos by produceState<List<StreamItem>?>(null, category, reload) {
        value = null
        if (category != YT_FILTER_ALL) value = loadTrending(context, TrendingCategory.valueOf(category))
    }
    val positions by produceState(emptyMap<String, Long>(), continueWatching) {
        value = withContext(Dispatchers.IO) {
            continueWatching.orEmpty().mapNotNull { video ->
                val id = video.url?.toID() ?: return@mapNotNull null
                runCatching { Database.watchPositionDao().findById(id)?.position }.getOrNull()?.let { id to it }
            }.toMap()
        }
    }

    val subscriptions = ytPlayable(feed)
    val trends = ytPlayable(charts ?: trending?.second?.streams)
    val forYou = if (subscriptions == null && trends == null) null
    else ytInterleave(subscriptions.orEmpty(), trends.orEmpty())
    val data = YtHomeData(
        forYou = forYou,
        subscriptions = subscriptions,
        live = live,
        shorts = YtShorts.of(feed),
        continueWatching = continueWatching,
        isLoading = isLoading,
    )

    fun selectTab(id: Int) {
        fragment.activity?.findViewById<BottomNavigationView>(R.id.bottomNav)?.selectedItemId = id
    }
    val actions = YtHomeActions(
        onOpenVideo = { video -> video.url?.let { NavigationHelper.navigateVideo(context, PlayerData(it.toID())) } },
        onOpenChannel = { video -> NavigationHelper.navigateChannel(context, video.uploaderUrl) },
        onSave = { video ->
            AddToPlaylistDialog().apply {
                arguments = bundleOf(IntentData.videoInfo to video)
            }.show(fragment.childFragmentManager, AddToPlaylistDialog::class.java.name)
        },
        onShowShorts = { selectTab(R.id.trendsFragment) },
        onShowSubscriptions = { selectTab(R.id.subscriptionsFragment) },
        onSearch = { YtChrome.openSearch?.invoke() },
        onRefresh = {
            refresh()
            reload++
        },
    )
    val imageLoader = ImageHelper.loader()

    YtTheme(palette) {
        when (design) {
            YtDesign.CLAY -> ClayHomeScreen(palette, imageLoader, data, actions)
            YtDesign.EDITORIAL -> EditorialHomeScreen(
                palette = palette,
                imageLoader = imageLoader,
                data = data,
                tab = tab,
                onSelectTab = { tab = it },
                categories = MediaServiceRepository.instance.getTrendingCategories()
                    .filter { it != TrendingCategory.LIVE },
                category = category,
                onSelectCategory = { category = it },
                categoryVideos = categoryVideos,
                actions = actions,
            )
            YtDesign.GLASS -> GlassHomeScreen(palette, imageLoader, data, tab, { tab = it }, actions)
            YtDesign.POP -> PopHomeScreen(palette, imageLoader, data, tab, { tab = it }, actions)
            YtDesign.SHAPES -> ShapesHomeScreen(
                palette = palette,
                imageLoader = imageLoader,
                data = data,
                progress = { video ->
                    val position = video.url?.toID()?.let { positions[it] } ?: 0L
                    val duration = (video.duration ?: 0L) * 1000
                    if (duration > 0) position.toFloat() / duration else 0.35f
                },
                actions = actions,
            )
            YtDesign.FROST -> FrostHomeScreen(palette, imageLoader, data, actions)
            else -> Unit
        }
    }
}

private suspend fun loadTrending(context: android.content.Context, category: TrendingCategory): List<StreamItem> =
    withContext(Dispatchers.IO) {
        runCatching {
            MediaServiceRepository.instance.getTrending(PreferenceHelper.getTrendingRegion(context), category)
        }.getOrDefault(emptyList())
    }
