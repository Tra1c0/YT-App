package com.github.libretube.ui.yt

import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.children
import androidx.core.view.isGone
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import coil3.ImageLoader
import coil3.compose.AsyncImage
import com.github.libretube.R
import com.github.libretube.api.MediaServiceRepository
import com.github.libretube.api.TrendingCategory
import com.github.libretube.api.obj.StreamItem
import com.github.libretube.extensions.formatShort
import com.github.libretube.helpers.ImageHelper
import com.github.libretube.helpers.NavigationHelper
import com.github.libretube.helpers.PreferenceHelper
import com.github.libretube.helpers.YtDesign
import com.github.libretube.ui.fragments.TrendsFragment
import com.github.libretube.ui.models.SubscriptionsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * YT design "YouTube, neu gedacht": the tab "Live" as its own page instead of the classic trends:
 * what is live from your channels first, then everything live in your region, with a pulsing
 * LIVE badge and the viewers, pull to refresh, quick actions on a long press and the region to
 * change.
 */
object YtLive {
    /** @return true if the live page replaces the classic trends layout. */
    fun attach(fragment: Fragment, root: ViewGroup): Boolean {
        val design = YtDesign.current()
        if (design != YtDesign.YOUTUBE) return false
        val palette = design.palette ?: return false
        val viewModel = ViewModelProvider(fragment.requireActivity())[SubscriptionsViewModel::class.java]
        YtShorts.ensureFeed(fragment, viewModel)

        root.children.forEach { it.isGone = true }
        val composeView = ComposeView(fragment.requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                var reload by remember { mutableIntStateOf(0) }
                var loading by remember { mutableStateOf(true) }
                val loaded by produceState<List<StreamItem>?>(null, reload) {
                    loading = true
                    val region = PreferenceHelper.getTrendingRegion(context)
                    value = withContext(Dispatchers.IO) {
                        runCatching { MediaServiceRepository.instance.getTrending(region, TrendingCategory.LIVE) }
                            .getOrDefault(emptyList())
                    }
                    loading = false
                }
                // hidden videos and paused channels stay off here too (rememberYtVisible)
                val live = rememberYtVisible(loaded)
                val feed = rememberYtVisible(viewModel.videoFeed.observeAsState().value)
                YtTheme(palette) {
                    YouTubeLiveScreen(
                        palette = palette,
                        imageLoader = ImageHelper.loader(),
                        live = live,
                        yours = feed?.filter { it.isLive && it.url != null },
                        isLoading = loading,
                        onRefresh = { reload++ },
                        onChangeRegion = { TrendsFragment.showChangeRegionDialog(context) { reload++ } },
                        onOpen = { video -> YtQueue.playKeepingQueue(context, video) },
                        onOpenChannel = { url -> NavigationHelper.navigateChannel(context, url) },
                        onSave = { ytSaveVideo(fragment, it) },
                        onShare = { ytShareVideo(fragment, it) },
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

@Composable
private fun YouTubeLiveScreen(
    palette: YtPalette,
    imageLoader: ImageLoader,
    live: List<StreamItem>?,
    yours: List<StreamItem>?,
    isLoading: Boolean,
    onRefresh: () -> Unit,
    onChangeRegion: () -> Unit,
    onOpen: (StreamItem) -> Unit,
    onOpenChannel: (String) -> Unit,
    onSave: (StreamItem) -> Unit,
    onShare: (StreamItem) -> Unit,
) {
    val enter = rememberYtEnterState()
    val pulse = rememberPulse(0.35f, 900)
    var quick by remember { mutableStateOf<StreamItem?>(null) }
    val streams = live.orEmpty().filter { it.url != null }

    Box(Modifier.fillMaxSize().background(palette.background)) {
        YtRefreshBox(
            palette = palette,
            isRefreshing = isLoading && live != null,
            onRefresh = onRefresh,
            modifier = Modifier.fillMaxSize(),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 24.dp + ytNavPadding()),
            ) {
                item {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(start = 15.dp, end = 12.dp, top = 4.dp, bottom = 12.dp),
                    ) {
                        // the red dot of a live signal, breathing
                        Box(
                            Modifier
                                .size(14.dp)
                                .drawBehind {
                                    val p = pulse.value
                                    drawCircle(palette.accent.copy(alpha = 0.35f * p), radius = size.minDimension / 2f * (1f + 0.6f * p))
                                    drawCircle(palette.accent, radius = size.minDimension / 3.2f)
                                },
                        )
                        Text(
                            text = stringResource(R.string.yt_nav_live),
                            color = palette.text,
                            style = TextStyle(fontFamily = palette.font, fontWeight = FontWeight.ExtraBold, fontSize = 36.sp, letterSpacing = (-0.8).sp),
                            modifier = Modifier
                                .padding(start = 12.dp)
                                .weight(1f),
                        )
                        Text(
                            text = stringResource(R.string.region),
                            color = palette.text,
                            style = TextStyle(fontFamily = palette.font, fontWeight = FontWeight.Medium, fontSize = 12.5.sp),
                            modifier = Modifier
                                .ytPressable(RoundedCornerShape(50), onClick = onChangeRegion)
                                .background(palette.raised)
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                        )
                    }
                }
                if (!yours.isNullOrEmpty()) {
                    item { LiveSection(palette, stringResource(R.string.yt_live_yours)) }
                    item {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.padding(bottom = 8.dp),
                        ) {
                            itemsIndexed(yours, key = { _, v -> "yours-" + v.url }) { i, video ->
                                Box(Modifier.width(236.dp).ytEnter(enter, "yours" + video.url, i)) {
                                    LiveCard(palette, imageLoader, video, pulse, onOpen = { onOpen(video) }, onQuick = { quick = video })
                                }
                            }
                        }
                    }
                }
                item { LiveSection(palette, stringResource(R.string.yt_live_now)) }
                when {
                    live == null -> item {
                        Box(Modifier.fillMaxWidth().height(220.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = palette.accent)
                        }
                    }
                    streams.isEmpty() -> item {
                        Text(
                            text = stringResource(R.string.yt_live_empty),
                            color = palette.textSecondary,
                            style = TextStyle(fontFamily = palette.font, fontSize = 15.sp, lineHeight = 21.sp),
                            modifier = Modifier.padding(horizontal = 15.dp, vertical = 16.dp),
                        )
                    }
                    else -> itemsIndexed(streams, key = { _, v -> v.url!! }) { i, video ->
                        Box(Modifier.padding(horizontal = 12.dp, vertical = 6.dp).ytEnter(enter, video.url!!, i)) {
                            LiveCard(palette, imageLoader, video, pulse, onOpen = { onOpen(video) }, onQuick = { quick = video })
                        }
                    }
                }
            }
        }
        YtQuickSheet(
            palette, imageLoader, quick,
            onDismiss = { quick = null },
            onSave = onSave,
            onShare = onShare,
            onChannel = { it.uploaderUrl?.let(onOpenChannel) },
        )
        // the undo pill of a stream hidden or a channel paused from the quick sheet
        YtHomePills(palette)
    }
}

@Composable
private fun LiveSection(palette: YtPalette, title: String) {
    Text(
        text = title,
        color = palette.text,
        style = TextStyle(fontFamily = palette.font, fontWeight = FontWeight.Bold, fontSize = 17.5.sp),
        modifier = Modifier.padding(start = 15.dp, end = 15.dp, top = 10.dp, bottom = 8.dp),
    )
}

/** A live stream: the still with the LIVE badge and the viewers, then the channel with the title. */
@Composable
private fun LiveCard(
    palette: YtPalette,
    imageLoader: ImageLoader,
    video: StreamItem,
    pulse: State<Float>,
    onOpen: () -> Unit,
    onQuick: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .ytPressable(RoundedCornerShape(14.dp), onLongClick = onQuick, onClick = onOpen),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(14.dp)),
        ) {
            YtLargeThumbnail(video, imageLoader, Modifier.fillMaxSize())
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Brush.verticalGradient(0.55f to Color.Transparent, 1f to Color(0x99000000))),
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(palette.accent)
                    .padding(horizontal = 7.dp, vertical = 3.dp),
            ) {
                Box(
                    Modifier
                        .size(6.dp)
                        .drawBehind { drawCircle(Color.White.copy(alpha = 0.55f + 0.45f * pulse.value)) },
                )
                Text(
                    text = stringResource(R.string.yt_live_badge),
                    color = Color.White,
                    style = TextStyle(fontFamily = palette.font, fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = 0.5.sp),
                    modifier = Modifier.padding(start = 5.dp),
                )
            }
            video.views?.takeIf { it > 0 }?.let {
                Text(
                    text = stringResource(R.string.yt_live_viewers, it.formatShort()),
                    color = Color.White,
                    style = TextStyle(fontFamily = palette.font, fontWeight = FontWeight.Medium, fontSize = 12.sp),
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(10.dp),
                )
            }
        }
        Row(verticalAlignment = Alignment.Top, modifier = Modifier.padding(top = 10.dp, start = 2.dp, end = 2.dp)) {
            AsyncImage(
                model = video.uploaderAvatar,
                contentDescription = null,
                imageLoader = imageLoader,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(palette.surface),
            )
            Column(Modifier.padding(start = 10.dp)) {
                Text(
                    text = video.title.orEmpty(),
                    color = palette.text,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(fontFamily = palette.font, fontWeight = FontWeight.SemiBold, fontSize = 14.5.sp, lineHeight = 18.sp),
                )
                Text(
                    text = video.uploaderName.orEmpty(),
                    color = palette.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(fontFamily = palette.font, fontSize = 12.5.sp),
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        Spacer(Modifier.height(4.dp))
    }
}
