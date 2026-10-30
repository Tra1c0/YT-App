package com.github.libretube.ui.yt

import android.view.ViewGroup
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import android.content.Intent
import com.github.libretube.ui.activities.AboutActivity
import com.github.libretube.ui.activities.HelpActivity
import com.github.libretube.ui.activities.SettingsActivity
import com.github.libretube.R
import com.github.libretube.api.PlaylistsHelper
import com.github.libretube.constants.PreferenceKeys
import com.github.libretube.db.DatabaseHelper
import com.github.libretube.db.DatabaseHolder.Database
import com.github.libretube.enums.PlaylistType
import com.github.libretube.enums.WatchHistoryStatus
import com.github.libretube.extensions.toID
import com.github.libretube.helpers.ImageHelper
import com.github.libretube.helpers.NavigationHelper
import com.github.libretube.helpers.PlayerHelper
import com.github.libretube.helpers.PreferenceHelper
import com.github.libretube.helpers.YtDesign
import com.github.libretube.parcelable.PlayerData
import com.github.libretube.repo.UserDataRepositoryHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext

/**
 * YT: puts the personal area of the selected design on top of the classic library layout.
 */
object YtLibrary {
    private val reloadSignal = MutableStateFlow(0)

    /** Ask an attached design screen to load its data again, e.g. after a playlist was created. */
    fun requestReload() {
        reloadSignal.value = reloadSignal.value + 1
    }

    /** @return true if a design screen was attached. */
    fun attach(fragment: Fragment, root: ViewGroup, onNewCollection: () -> Unit): Boolean {
        val design = YtDesign.current()
        val palette = design.palette ?: return false
        val (headline, subline) = when (design) {
            YtDesign.MOVO -> R.string.yt_movo_library_headline to R.string.yt_movo_library_subline
            YtDesign.AFTER -> R.string.yt_after_library_headline to R.string.yt_after_library_subline
            YtDesign.SIDE -> R.string.yt_side_library_headline to R.string.yt_side_library_subline
            else -> R.string.yt_library_headline to R.string.yt_library_subline
        }

        val composeView = ComposeView(fragment.requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val signal by reloadSignal.collectAsState()
                val account by com.github.libretube.ui.yt.account.YtAccount.state.collectAsState()
                var continueWatching by remember { mutableStateOf(emptyList<YtContinueItem>()) }
                var collections by remember { mutableStateOf(emptyList<YtCollection>()) }

                LaunchedEffect(signal) {
                    continueWatching = loadContinueWatching()
                    collections = loadCollections()
                }

                YtTheme(palette) {
                    YouTubeLibraryScreen(
                        palette = palette,
                        imageLoader = ImageHelper.loader(),
                        continueWatching = continueWatching,
                        collections = collections,
                        showHistory = PreferenceHelper.getBoolean(PreferenceKeys.WATCH_HISTORY_TOGGLE, true),
                        onOpenHistory = {
                            fragment.findNavController()
                                .navigate(R.id.action_libraryFragment_to_watchHistoryFragment)
                        },
                        onOpenDownloads = {
                            fragment.findNavController()
                                .navigate(R.id.action_libraryFragment_to_downloadsFragment)
                        },
                        onOpenVideo = { item ->
                            item.video.url?.let {
                                NavigationHelper.navigateVideo(context, PlayerData(it.toID()))
                            }
                        },
                        onOpenCollection = { collection ->
                            NavigationHelper.navigatePlaylist(
                                context,
                                collection.id,
                                if (collection.isBookmark) PlaylistType.PUBLIC else PlaylistType.PRIVATE
                            )
                        },
                        onNewCollection = onNewCollection,
                        headline = stringResource(headline),
                        subline = stringResource(subline),
                        onOpenSettings = { context.startActivity(Intent(context, SettingsActivity::class.java)) },
                        onOpenHelp = { context.startActivity(Intent(context, HelpActivity::class.java)) },
                        onOpenAbout = { context.startActivity(Intent(context, AboutActivity::class.java)) },
                        account = account,
                        onAccount = {
                            fragment.activity?.let { com.github.libretube.ui.yt.account.YtAccountSheet.show(it) }
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

    @Suppress("DEPRECATION")
    private suspend fun loadContinueWatching(): List<YtContinueItem> = withContext(Dispatchers.IO) {
        if (!PlayerHelper.watchHistoryEnabled) return@withContext emptyList()
        val entries = runCatching {
            UserDataRepositoryHelper.userDataRepository
                .getWatchHistory(20, null, WatchHistoryStatus.ALL).first
        }.getOrDefault(emptyList())

        val unwatched = runCatching {
            DatabaseHelper.filterUnwatched(entries.map { it.video }).mapNotNull { it.url }.toSet()
        }.getOrDefault(emptySet())

        entries.mapNotNull { entry ->
            val url = entry.video.url ?: return@mapNotNull null
            if (url !in unwatched) return@mapNotNull null
            val position = entry.metadata.positionMillis
                ?: runCatching { Database.watchPositionDao().findById(url.toID())?.position }.getOrNull()
                ?: 0L
            if (position <= 0L) null else YtContinueItem(entry.video, position)
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun loadCollections(): List<YtCollection> = withContext(Dispatchers.IO) {
        val playlists = runCatching { PlaylistsHelper.getPlaylists() }.getOrDefault(emptyList())
        val own = playlists.mapNotNull { playlist ->
            val id = playlist.id ?: return@mapNotNull null
            val previews = runCatching {
                PlaylistsHelper.getPlaylist(id, PlaylistType.PRIVATE)
                    .relatedStreams.mapNotNull { it.thumbnail }.take(3)
            }.getOrElse { listOfNotNull(playlist.thumbnail) }
            YtCollection(id, playlist.name.orEmpty(), playlist.videos, previews, isBookmark = false)
        }
        val bookmarks = runCatching {
            UserDataRepositoryHelper.userDataRepository.getPlaylistBookmarks()
        }.getOrDefault(emptyList()).map { bookmark ->
            YtCollection(
                bookmark.playlistId,
                bookmark.playlistName.orEmpty(),
                bookmark.videos.toLong(),
                listOfNotNull(bookmark.thumbnailUrl),
                isBookmark = true,
            )
        }
        own + bookmarks
    }
}
