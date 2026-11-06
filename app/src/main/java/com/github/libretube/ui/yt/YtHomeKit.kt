package com.github.libretube.ui.yt

import android.text.format.DateUtils
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import com.github.libretube.R
import com.github.libretube.api.obj.StreamItem
import com.github.libretube.extensions.formatShort
import com.github.libretube.extensions.toID
import com.github.libretube.helpers.YtChannelPause
import com.github.libretube.helpers.YtHidden
import com.github.libretube.ui.yt.feed.YtPausedChannel
import com.github.libretube.ui.yt.feed.YtVisibility

/*
 * YT: shared pieces of the home screens of the second round of designs (Clay, Editorial, Glas,
 * Pop) and the inspiration designs (Formen, Frost).
 */

const val YT_TAB_FOR_YOU = "FOR_YOU"
const val YT_TAB_SUBS = "SUBS"
const val YT_TAB_LIVE = "LIVE"

/** What a home screen shows. Lists are null while they load. */
class YtHomeData(
    /** Subscriptions, charts, topics and history as the user's rules rank them (YtFeedEngine). */
    val forYou: List<StreamItem>?,
    /** The subscription feed without Shorts, newest first. */
    val subscriptions: List<StreamItem>?,
    /** Live streams of the trends. */
    val live: List<StreamItem>?,
    /** Shorts of the subscription feed. */
    val shorts: List<StreamItem>?,
    /** Videos the watch history has a position for. */
    val continueWatching: List<StreamItem>?,
    val isLoading: Boolean,
) {
    fun forTab(tab: String): List<StreamItem>? = when (tab) {
        YT_TAB_SUBS -> subscriptions
        YT_TAB_LIVE -> live
        else -> forYou
    }
}

/** What a home screen can do; the screens only show. */
class YtHomeActions(
    val onOpenVideo: (StreamItem) -> Unit,
    val onOpenChannel: (StreamItem) -> Unit,
    val onSave: (StreamItem) -> Unit,
    val onShowShorts: () -> Unit,
    val onShowSubscriptions: () -> Unit,
    val onSearch: () -> Unit,
    val onRefresh: () -> Unit,
    /** Opens a video at a position, in seconds. */
    val onOpenVideoAt: (StreamItem, Long) -> Unit = { video, _ -> onOpenVideo(video) },
    /** Shares the link of a video (the quick sheet of the designs that have one). */
    val onShare: (StreamItem) -> Unit = {},
    /**
     * Opens the quick actions of a video (save, share, channel, queue, hide, pause): the way to
     * everything the card swipes do without swiping.
     */
    val onQuick: (StreamItem) -> Unit = {},
)

/** Videos that can be played as a regular video: no Shorts, nothing upcoming, with a URL. */
fun ytPlayable(videos: List<StreamItem>?): List<StreamItem>? =
    videos?.filter { !it.isShort && !it.isUpcoming && it.url != null }?.distinctBy { it.url }

/**
 * The subscription feed as the "Abos" lists show it: playable videos without live streams.
 * Live and upcoming streams sort to the top of a feed ordered by date (their date is now or in
 * the future), and they have the Live tab of their own, so the Abos lists leave them out.
 */
fun ytSubscriptionList(videos: List<StreamItem>?): List<StreamItem>? =
    ytPlayable(videos)?.filter { !it.isLive }

/**
 * [videos] without what the home screens leave out: videos swiped away ([hidden], video id to
 * day) and channels [paused] at [now] (YtVisibility). The same list when nothing goes.
 */
fun ytVisible(videos: List<StreamItem>?, hidden: Map<String, Int>, paused: List<YtPausedChannel>, now: Long): List<StreamItem>? =
    YtVisibility.visible(
        videos, hidden, paused, now,
        id = { it.url?.toID() },
        channelId = { YtChannelPause.channelId(it.uploaderUrl) },
        channelName = { it.uploaderName },
    )

/** [videos] as the home screens show them; follows hiding and pausing as they happen. */
@Composable
fun rememberYtVisible(videos: List<StreamItem>?): List<StreamItem>? {
    val hidden by YtHidden.hidden.collectAsState()
    val paused by YtChannelPause.paused.collectAsState()
    return remember(videos, hidden, paused) { ytVisible(videos, hidden, paused, System.currentTimeMillis()) }
}

/**
 * The motion of a home list item that comes or goes (a card swiped away, one brought back):
 * the others close the gap on a spring; without motion they jump. New items keep their own
 * entrance (ytEnter), so nothing fades in twice.
 */
@Composable
fun LazyItemScope.ytItemMotion(): Modifier =
    if (LocalYtMotion.current) {
        Modifier.animateItem(
            fadeInSpec = null,
            placementSpec = spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow, visibilityThreshold = IntOffset.VisibilityThreshold),
            fadeOutSpec = tween(160),
        )
    } else {
        Modifier.animateItem(fadeInSpec = null, placementSpec = null, fadeOutSpec = null)
    }

/** Grayscale for the Editorial thumbnails. */
val ytGrayscale: ColorFilter = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })

/** A picture with [saturation] of its colors (0 black and white, 1 full color). */
fun ytSaturation(saturation: Float): ColorFilter =
    ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(saturation) })

fun ytDuration(video: StreamItem): String? =
    video.duration?.takeIf { it > 0 }?.let { DateUtils.formatElapsedTime(it) }

/** "320.000 Aufrufe" or, for live streams, "3.200 Zuschauer". */
@Composable
fun ytViews(video: StreamItem): String? = video.views?.takeIf { it > 0 }?.let {
    if (video.isLive) stringResource(R.string.yt_home_viewers, it.formatShort())
    else stringResource(R.string.yt_after_views, it.formatShort())
}

/** Views and age, or for live streams viewers and "Jetzt live". */
@Composable
fun ytMeta(video: StreamItem, separator: String = " • "): String = listOfNotNull(
    ytViews(video),
    if (video.isLive) stringResource(R.string.yt_live_now) else video.uploadedDate,
).joinToString(separator)
