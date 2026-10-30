package com.github.libretube.ui.yt

import android.text.format.DateUtils
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.res.stringResource
import com.github.libretube.R
import com.github.libretube.api.obj.StreamItem
import com.github.libretube.extensions.formatShort

/*
 * YT: shared pieces of the home screens of the second round of designs (Clay, Editorial, Glas,
 * Pop) and the inspiration designs (Formen, Frost).
 */

const val YT_TAB_FOR_YOU = "FOR_YOU"
const val YT_TAB_SUBS = "SUBS"
const val YT_TAB_LIVE = "LIVE"

/** What a home screen shows. Lists are null while they load. */
class YtHomeData(
    /** Your subscriptions and the trends, interleaved: the closest YT has to recommendations. */
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
)

/** Videos that can be played as a regular video: no Shorts, nothing upcoming, with a URL. */
fun ytPlayable(videos: List<StreamItem>?): List<StreamItem>? =
    videos?.filter { !it.isShort && !it.isUpcoming && it.url != null }?.distinctBy { it.url }

/** Alternates the items of [a] and [b], without duplicates. */
fun ytInterleave(a: List<StreamItem>, b: List<StreamItem>): List<StreamItem> = buildList {
    for (i in 0 until maxOf(a.size, b.size)) {
        a.getOrNull(i)?.let(::add)
        b.getOrNull(i)?.let(::add)
    }
}.distinctBy { it.url }

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
