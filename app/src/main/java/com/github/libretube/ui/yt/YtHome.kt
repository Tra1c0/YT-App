package com.github.libretube.ui.yt

import android.content.Intent
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.net.toUri
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import com.github.libretube.R
import com.github.libretube.api.MediaServiceRepository
import com.github.libretube.api.TrendingCategory
import com.github.libretube.api.obj.StreamItem
import com.github.libretube.constants.IntentData
import com.github.libretube.constants.PreferenceKeys
import com.github.libretube.extensions.toID
import com.github.libretube.helpers.ImageHelper
import com.github.libretube.helpers.NavigationHelper
import com.github.libretube.helpers.PreferenceHelper
import com.github.libretube.helpers.YtDesign
import com.github.libretube.helpers.YtHearts
import com.github.libretube.parcelable.PlayerData
import com.github.libretube.ui.dialogs.AddToPlaylistDialog
import com.github.libretube.ui.models.HomeViewModel
import com.github.libretube.util.TextUtils
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
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
        if (design == YtDesign.STANDARD) return false

        val composeView = ComposeView(fragment.requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val feed by viewModel.feed.observeAsState()
                val trending by viewModel.trending.observeAsState()
                val isLoading by viewModel.isLoading.observeAsState(true)
                var filter by remember { mutableStateOf(YT_FILTER_ALL) }
                var movoTab by remember { mutableStateOf(MOVO_TAB_DISCOVER) }
                // charts to discover: without subscriptions the home would otherwise be nearly empty
                val discovery = YtDiscovery.rememberVideos()
                val charts = discovery?.takeIf { it.isNotEmpty() } ?: trending?.second?.streams
                val refreshAll = {
                    refresh()
                    YtDiscovery.refresh(context)
                }

                if (design.hasShortsTab || design == YtDesign.KIESEL) {
                    YtRound2Home(fragment, design, viewModel, refreshAll, charts)
                } else if (design == YtDesign.SIDE) {
                    var sideTab by remember { mutableStateOf(SIDE_TAB_FOR_YOU) }
                    var hearts by remember { mutableStateOf(YtHearts.all()) }
                    YtTheme(YtPalettes.Side) {
                        SideHomeScreen(
                            palette = YtPalettes.Side,
                            imageLoader = ImageHelper.loader(),
                            forYou = charts ?: feed,
                            people = feed,
                            tab = sideTab,
                            hearts = hearts,
                            isLoading = isLoading,
                            onSelectTab = { sideTab = it },
                            onRefresh = refreshAll,
                            actions = SideFeedActions(
                                onOpenVideo = { video ->
                                    NavigationHelper.navigateVideo(context, PlayerData(video.url!!.toID()))
                                },
                                onOpenChannel = { video ->
                                    NavigationHelper.navigateChannel(context, video.uploaderUrl)
                                },
                                onBookmark = { video ->
                                    AddToPlaylistDialog().apply {
                                        arguments = bundleOf(IntentData.videoInfo to video)
                                    }.show(fragment.childFragmentManager, AddToPlaylistDialog::class.java.name)
                                },
                                onShare = { video -> shareVideo(fragment, video) },
                                onToggleHeart = { video -> hearts = YtHearts.toggle(video.url!!.toID()) },
                                onPost = { showPostDialog(fragment) },
                            ),
                        )
                    }
                } else if (design == YtDesign.AFTER) {
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
                                feed?.takeIf { videos -> videos.any { !it.isShort } } ?: charts
                            } else tabVideos,
                            continueWatching = continueWatching,
                            tab = afterTab,
                            isLoading = if (selection) isLoading else tabVideos == null,
                            onSelectTab = { afterTab = it },
                            onRefresh = { if (selection) refreshAll() else reload++ },
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
                            discover = charts,
                            following = feed,
                            tab = movoTab,
                            isLoading = isLoading,
                            onSelectTab = { movoTab = it },
                            onRefresh = refreshAll,
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
                        // "Alle": subscriptions, or the mixed charts; a topic: its chart
                        trending = if (filter == YT_FILTER_ALL) charts else trending?.second?.streams,
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
                        onRefresh = refreshAll,
                        onOpenVideo = { video ->
                            NavigationHelper.navigateVideo(context, PlayerData(video.url!!.toID()))
                        },
                        onOpenChannel = { video ->
                            NavigationHelper.navigateChannel(context, video.uploaderUrl)
                        },
                        onShowAllLive = {
                            fragment.activity?.findViewById<BottomNavigationView>(R.id.bottomNav)
                                ?.selectedItemId = R.id.trendsFragment
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

    private fun shareVideo(fragment: Fragment, video: StreamItem) {
        val link = "https://youtu.be/" + (video.url ?: return).toID()
        val intent = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, video.title)
            .putExtra(Intent.EXTRA_TEXT, link)
        fragment.startActivity(Intent.createChooser(intent, video.title))
    }

    /** "Beitrag" in the design Side: open a pasted YouTube link with its conversation. */
    private fun showPostDialog(fragment: Fragment) {
        val context = fragment.requireContext()
        val input = EditText(context).apply {
            setHint(R.string.yt_side_post_hint)
            setSingleLine()
        }
        val container = FrameLayout(context).apply {
            val gutter = (20 * resources.displayMetrics.density).toInt()
            setPadding(gutter, gutter / 2, gutter, 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.yt_side_post)
            .setMessage(R.string.yt_side_post_message)
            .setView(container)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.yt_side_post_open) { _, _ ->
                val videoId = videoIdFromLink(input.text.toString().trim())
                if (videoId == null) {
                    Toast.makeText(context, R.string.yt_side_post_invalid, Toast.LENGTH_SHORT).show()
                } else {
                    NavigationHelper.navigateVideo(context, PlayerData(videoId))
                }
            }
            .show()
    }

    private fun videoIdFromLink(text: String): String? {
        if (VIDEO_ID.matches(text)) return text
        val uri = runCatching { text.toUri() }.getOrNull() ?: return null
        TextUtils.getVideoIdFromUri(uri)?.let { return it }
        val segments = uri.pathSegments
        val shorts = segments.indexOf("shorts")
        return segments.getOrNull(shorts + 1)?.takeIf { shorts >= 0 && VIDEO_ID.matches(it) }
    }

    private val VIDEO_ID = Regex("[A-Za-z0-9_-]{11}")
}
