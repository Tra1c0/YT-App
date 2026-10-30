package com.github.libretube.ui.yt

import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
import androidx.lifecycle.lifecycleScope
import coil3.ImageLoader
import com.github.libretube.R
import com.github.libretube.api.SubscriptionHelper
import com.github.libretube.api.obj.StreamItem
import com.github.libretube.extensions.formatShort
import com.github.libretube.extensions.toID
import com.github.libretube.helpers.ImageHelper
import com.github.libretube.helpers.NavigationHelper
import com.github.libretube.helpers.YtDesign
import com.github.libretube.parcelable.PlayerData
import com.github.libretube.repo.FeedRefresh
import com.github.libretube.ui.models.SubscriptionsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * YT: the Shorts tab of the designs that have one (it takes the place of the trends tab).
 * YT has no Shorts feed of its own, so the tab shows the Shorts from your subscriptions.
 */
object YtShorts {
    /** Shorts of a feed, newest first. */
    fun of(feed: List<StreamItem>?): List<StreamItem>? =
        feed?.filter { it.isShort && it.url != null }?.distinctBy { it.url }

    /** Makes sure the subscription feed is loaded; Home and Shorts read it. */
    fun ensureFeed(fragment: Fragment, viewModel: SubscriptionsViewModel) {
        if (viewModel.videoFeed.value != null) return
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            val feed = withContext(Dispatchers.IO) {
                runCatching { SubscriptionHelper.getFeed(FeedRefresh.Automatically) }.getOrNull()
            }
            if (feed != null) viewModel.videoFeed.value = feed
        }
    }

    /** @return true if the Shorts screen replaces the classic trends layout. */
    fun attach(fragment: Fragment, root: ViewGroup): Boolean {
        val design = YtDesign.current()
        if (!design.hasShortsTab) return false
        val palette = design.palette ?: return false
        val viewModel = ViewModelProvider(fragment.requireActivity())[SubscriptionsViewModel::class.java]
        ensureFeed(fragment, viewModel)

        root.children.forEach { it.isGone = true }
        val composeView = ComposeView(fragment.requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val feed by viewModel.videoFeed.observeAsState()
                YtTheme(palette) {
                    YtShortsScreen(palette, ImageHelper.loader(), of(feed)) { video ->
                        NavigationHelper.navigateVideo(context, PlayerData(video.url!!.toID()))
                    }
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
private fun YtShortsScreen(
    palette: YtPalette,
    imageLoader: ImageLoader,
    shorts: List<StreamItem>?,
    onOpen: (StreamItem) -> Unit,
) {
    val enter = rememberYtEnterState()
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp + ytNavPadding()),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxSize()
            .background(palette.background),
    ) {
        item(span = { GridItemSpan(2) }) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
            ) {
                YtShortsGlyph(palette.live, filled = true, modifier = Modifier.size(26.dp))
                Text(
                    text = stringResource(R.string.yt_nav_shorts),
                    color = palette.text,
                    style = TextStyle(fontFamily = palette.font, fontWeight = FontWeight.Bold, fontSize = 24.sp),
                    modifier = Modifier.padding(start = 10.dp),
                )
            }
        }
        when {
            shorts == null -> item(span = { GridItemSpan(2) }) {
                Box(Modifier.fillMaxWidth().height(240.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = palette.accent)
                }
            }
            shorts.isEmpty() -> item(span = { GridItemSpan(2) }) {
                Text(
                    text = stringResource(R.string.yt_shorts_empty),
                    color = palette.textSecondary,
                    style = TextStyle(fontFamily = palette.font, fontSize = 15.sp, lineHeight = 21.sp),
                    modifier = Modifier.padding(top = 24.dp),
                )
            }
            else -> items(shorts, key = { it.url!! }) { short ->
                Column(
                    Modifier
                        .ytEnter(enter, short.url!!, shorts.indexOf(short))
                        .ytPressable(ytCardShape(palette, 14), squish = palette.surfaceStyle == YtSurfaceStyle.CLAY) { onOpen(short) },
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .aspectRatio(9f / 16f)
                            .clip(ytCardShape(palette, 14)),
                    ) {
                        YtLargeThumbnail(short, imageLoader, Modifier.fillMaxSize())
                        Box(
                            Modifier
                                .fillMaxSize()
                                .background(Brush.verticalGradient(0.6f to Color.Transparent, 1f to Color(0xA6000000))),
                        )
                        short.views?.takeIf { it > 0 }?.let {
                            Text(
                                text = stringResource(R.string.yt_after_views, it.formatShort()),
                                color = Color.White,
                                style = TextStyle(fontFamily = palette.font, fontWeight = FontWeight.Bold, fontSize = 12.sp),
                                modifier = Modifier
                                    .align(Alignment.BottomStart)
                                    .padding(8.dp),
                            )
                        }
                    }
                    Text(
                        text = short.title.orEmpty(),
                        color = palette.text,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        style = TextStyle(fontFamily = palette.font, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, lineHeight = 17.sp),
                        modifier = Modifier.padding(top = 6.dp, start = 2.dp, end = 2.dp),
                    )
                    Text(
                        text = short.uploaderName.orEmpty(),
                        color = palette.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = TextStyle(fontFamily = palette.font, fontSize = 12.sp),
                        modifier = Modifier.padding(start = 2.dp, end = 2.dp),
                    )
                }
            }
        }
    }
}
