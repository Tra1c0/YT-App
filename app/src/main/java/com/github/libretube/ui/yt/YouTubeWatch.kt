package com.github.libretube.ui.yt

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Brush
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.ImageLoader
import com.github.libretube.R
import com.github.libretube.api.SubscriptionHelper
import com.github.libretube.api.obj.Streams
import com.github.libretube.extensions.formatShort
import com.github.libretube.extensions.toID
import com.github.libretube.util.TextUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** State of the video page that the player fragment updates. */
class YtWatchController {
    var videoId by mutableStateOf<String?>(null)
    var streams by mutableStateOf<Streams?>(null)
    var chapterIndex by mutableIntStateOf(-1)
}

/** Actions of the video page; most reuse the existing buttons of the classic layout. */
class YtWatchActions(
    val onSeek: (Long) -> Unit,
    val onSave: () -> Unit,
    val onShare: () -> Unit,
    val onDownload: () -> Unit,
    val onAudioOnly: () -> Unit,
    val onComments: () -> Unit,
    val onOpenChannel: () -> Unit,
    val onShowDescription: (Boolean) -> Unit,
    val currentPosition: () -> Long = { 0L },
    /** Opens a link or jumps to a timestamp of the description. */
    val onLink: (String) -> Unit = {},
)

private enum class WatchTab { CHAPTERS, COMMENTS, DESCRIPTION }

/**
 * YT design "YouTube, neu gedacht", view B (Videoseite) below the player: title and metadata,
 * creator row with "Abonnieren", compact actions, tabs Kapitel / Kommentare / Beschreibung and the
 * chapter list with the current chapter marked. "Als Nächstes" stays the classic list below.
 */
@Composable
fun YouTubeWatchDetails(
    palette: YtPalette,
    imageLoader: ImageLoader,
    controller: YtWatchController,
    actions: YtWatchActions,
) {
    val streams = controller.streams ?: return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val channelId = streams.uploaderUrl?.toID()

    var subscribed by androidx.compose.runtime.remember(channelId) { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(channelId) {
        subscribed = channelId?.let {
            withContext(Dispatchers.IO) { runCatching { SubscriptionHelper.isSubscribed(it) }.getOrNull() }
        }
    }
    val hasChapters = streams.chapters.isNotEmpty()
    var tab by androidx.compose.runtime.remember(streams.title) {
        mutableStateOf(if (hasChapters) WatchTab.CHAPTERS else WatchTab.DESCRIPTION)
    }
    LaunchedEffect(tab, streams.title) { actions.onShowDescription(tab == WatchTab.DESCRIPTION) }

    // the glass designs see a gradient with drifting light through their glass pieces
    val glass = palette.signature == YtSignature.GLASS || palette.signature == YtSignature.FROST
    val backdrop = if (glass) rememberLayerBackdrop() else null
    Box(Modifier.fillMaxWidth()) {
        if (backdrop != null) {
            Box(
                Modifier
                    .matchParentSize()
                    .layerBackdrop(backdrop)
                    .background(Brush.verticalGradient(ytGlassScreenColors(palette))),
            ) {
                LiquidLight(ytGlassLightColors(palette), Modifier.fillMaxSize())
            }
        }
        CompositionLocalProvider(LocalWatchBackdrop provides backdrop) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            text = streams.title,
            color = palette.text,
            style = TextStyle(
                fontFamily = palette.font,
                fontWeight = FontWeight.Bold,
                fontSize = 18.5.sp,
                lineHeight = 23.sp,
                letterSpacing = (-0.2).sp,
            ),
            modifier = Modifier.padding(start = 13.dp, end = 13.dp, top = 8.dp),
        )
        Text(
            text = TextUtils.formatViewsString(context, streams.views, streams.uploaded ?: 0L),
            color = palette.textSecondary,
            style = TextStyle(fontFamily = palette.font, fontSize = 12.5.sp),
            modifier = Modifier.padding(start = 13.dp, end = 13.dp, top = 3.dp),
        )

        val toggle: () -> Unit = {
            val newState = subscribed != true
            subscribed = newState
            if (channelId != null) {
                scope.launch(Dispatchers.IO) {
                    runCatching {
                        if (newState) {
                            SubscriptionHelper.subscribe(
                                channelId,
                                streams.uploader,
                                streams.uploaderAvatar,
                                streams.uploaderVerified
                            )
                        } else {
                            SubscriptionHelper.unsubscribe(channelId)
                        }
                    }
                }
            }
        }
        WatchCreator(palette, imageLoader, streams, channelId != null, subscribed == true, toggle, actions.onOpenChannel)

        val watchActions = buildList {
            add(WatchAction(R.drawable.ic_like, if (streams.likes > 0) streams.likes.formatShort() else stringResource(R.string.yt_watch_like)) {})
            add(WatchAction(R.drawable.ic_playlist_add, stringResource(R.string.yt_watch_save), actions.onSave))
            add(WatchAction(R.drawable.ic_share, stringResource(R.string.share), actions.onShare))
            if (!streams.isLive) add(WatchAction(R.drawable.ic_download, stringResource(R.string.yt_watch_download), actions.onDownload))
            add(WatchAction(R.drawable.ic_headphones, stringResource(R.string.yt_watch_audio), actions.onAudioOnly))
        }
        WatchActionRow(palette, watchActions, Modifier.padding(top = 18.dp))

        val tabs = buildList {
            if (hasChapters) add(WatchTab.CHAPTERS)
            add(WatchTab.DESCRIPTION)
            add(WatchTab.COMMENTS)
        }
        val labels = tabs.map {
            stringResource(
                when (it) {
                    WatchTab.CHAPTERS -> R.string.yt_watch_chapters
                    WatchTab.DESCRIPTION -> R.string.yt_watch_description
                    WatchTab.COMMENTS -> R.string.yt_watch_comments
                },
            )
        }
        WatchTabs(
            palette, labels, tabs.indexOf(tab),
            onSelect = { i -> if (tabs[i] == WatchTab.COMMENTS) actions.onComments() else tab = tabs[i] },
            modifier = Modifier.padding(top = 16.dp),
        )

        if (tab == WatchTab.CHAPTERS && hasChapters) {
            WatchChapters(palette, imageLoader, streams, controller.chapterIndex, actions.onSeek)
        }
        if (tab == WatchTab.CHAPTERS || !hasChapters) WatchUpNextTitle(palette)
    }
        }
    }
}
