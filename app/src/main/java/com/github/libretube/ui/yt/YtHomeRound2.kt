package com.github.libretube.ui.yt

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
import com.github.libretube.ui.dialogs.AddToPlaylistDialog
import com.github.libretube.ui.models.HomeViewModel
import com.github.libretube.ui.yt.feed.YtFeed
import com.github.libretube.ui.yt.feed.YtIntent
import com.github.libretube.ui.yt.shorts.YtShortsView
import com.google.android.material.bottomnavigation.BottomNavigationView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * YT: home of the second round of designs (Clay, Editorial, Glas, Pop), of the inspiration
 * designs (Formen, Frost, Kiesel), of Frequency and of Synthwave. Collects the data the screens show and
 * dispatches to the design.
 */
@Composable
internal fun YtRound2Home(
    fragment: Fragment,
    design: YtDesign,
    viewModel: HomeViewModel,
    refresh: () -> Unit,
    /** The feed as the current intent's rules rank it (YtFeed), null while nothing loaded. */
    ranked: List<StreamItem>?,
) {
    val palette = design.palette ?: return
    val context = LocalContext.current
    // hidden videos and paused channels stay off the home (rememberYtVisible)
    val feed = rememberYtVisible(viewModel.feed.observeAsState().value)
    val trending by viewModel.trending.observeAsState()
    val continueWatching = rememberYtVisible(viewModel.continueWatching.observeAsState().value)
    val isLoading by viewModel.isLoading.observeAsState(true)
    var tab by rememberSaveable { mutableStateOf(YT_TAB_FOR_YOU) }
    var category by rememberSaveable { mutableStateOf(YT_FILTER_ALL) }
    var reload by remember { mutableIntStateOf(0) }

    val liveLoaded by produceState<List<StreamItem>?>(null, reload) {
        value = loadTrending(context, TrendingCategory.LIVE)
    }
    val live = rememberYtVisible(liveLoaded)
    val categoryLoaded by produceState<List<StreamItem>?>(null, category, reload) {
        value = null
        if (category != YT_FILTER_ALL) value = loadTrending(context, TrendingCategory.valueOf(category))
    }
    val categoryVideos = rememberYtVisible(categoryLoaded)
    val positions by produceState(emptyMap<String, Long>(), continueWatching) {
        value = withContext(Dispatchers.IO) {
            continueWatching.orEmpty().mapNotNull { video ->
                val id = video.url?.toID() ?: return@mapNotNull null
                runCatching { Database.watchPositionDao().findById(id)?.position }.getOrNull()?.let { id to it }
            }.toMap()
        }
    }

    // the Abos tab without live streams: they are in the Live tab
    val subscriptions = ytSubscriptionList(feed)
    val trendingVisible = rememberYtVisible(trending?.second?.streams)
    // the ranked feed (YtFeedEngine), else what has loaded so far
    val forYou = ytPlayable(ranked ?: subscriptions ?: trendingVisible)
    // "Meine Abos sehen" opens the designs with tabs on the subscriptions
    val intent by YtFeed.intent.collectAsState()
    LaunchedEffect(intent) { if (intent == YtIntent.SUBSCRIPTIONS) tab = YT_TAB_SUBS }
    val data = YtHomeData(
        forYou = forYou,
        subscriptions = subscriptions,
        live = live,
        shorts = YtShorts.of(feed),
        continueWatching = continueWatching,
        isLoading = isLoading,
    )

    val haptics = rememberYtHaptics()
    var quick by remember { mutableStateOf<StreamItem?>(null) }

    fun selectTab(id: Int) {
        fragment.activity?.findViewById<BottomNavigationView>(R.id.bottomNav)?.selectedItemId = id
    }
    val actions = YtHomeActions(
        // a tap keeps what the user queued (YtQueue)
        onOpenVideo = { video ->
            // a short of the Shorts rows opens the Shorts view with the feed's shorts
            if (!video.isShort || !YtShortsView.open(context, YtShorts.of(feed).orEmpty(), video)) {
                YtQueue.playKeepingQueue(context, video, audio = YtFeed.audioNow())
            }
        },
        onOpenVideoAt = { video, seconds -> YtQueue.playKeepingQueue(context, video, seconds, audio = YtFeed.audioNow()) },
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
            // pulled far enough: a tick, as the refresh starts
            haptics.tick()
            refresh()
            reload++
        },
        onShare = { video -> YtHome.shareVideo(fragment, video) },
        onQuick = { quick = it },
    )
    val imageLoader = ImageHelper.loader()

    YtTheme(palette) {
        Box(Modifier.fillMaxSize()) {
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
            YtDesign.FREQUENCY -> FrequencyHomeScreen(
                palette = palette,
                imageLoader = imageLoader,
                data = data,
                categories = MediaServiceRepository.instance.getTrendingCategories(),
                category = category,
                onSelectCategory = { category = it },
                categoryVideos = categoryVideos,
                progress = { video ->
                    val position = video.url?.toID()?.let { positions[it] } ?: 0L
                    val duration = (video.duration ?: 0L) * 1000
                    if (duration > 0) position.toFloat() / duration else 0f
                },
                actions = actions,
            )
            YtDesign.SYNTHWAVE -> SynthwaveHomeScreen(
                palette = palette,
                imageLoader = imageLoader,
                data = data,
                categories = MediaServiceRepository.instance.getTrendingCategories(),
                category = category,
                onSelectCategory = { category = it },
                categoryVideos = categoryVideos,
                progress = { video ->
                    val position = video.url?.toID()?.let { positions[it] } ?: 0L
                    val duration = (video.duration ?: 0L) * 1000
                    if (duration > 0) position.toFloat() / duration else 0f
                },
                actions = actions,
            )
            YtDesign.KIESEL -> KieselHomeScreen(
                palette = palette,
                imageLoader = imageLoader,
                data = data,
                remaining = { video ->
                    val position = video.url?.toID()?.let { positions[it] } ?: 0L
                    ((video.duration ?: 0L) * 1000 - position).coerceAtLeast(0L)
                },
                actions = actions,
            )
            else -> Unit
        }
        YtQuickSheet(
            palette = palette,
            imageLoader = imageLoader,
            video = quick,
            onDismiss = { quick = null },
            onSave = actions.onSave,
            onShare = actions.onShare,
            onChannel = actions.onOpenChannel,
        )
        }
    }
}

private suspend fun loadTrending(context: android.content.Context, category: TrendingCategory): List<StreamItem> =
    withContext(Dispatchers.IO) {
        runCatching {
            MediaServiceRepository.instance.getTrending(PreferenceHelper.getTrendingRegion(context), category)
        }.getOrDefault(emptyList())
    }
