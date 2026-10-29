package com.github.libretube.ui.yt

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import com.github.libretube.api.MediaServiceRepository
import com.github.libretube.api.TrendingCategory
import com.github.libretube.api.obj.StreamItem
import com.github.libretube.helpers.PreferenceHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * YT: videos to discover when the subscriptions don't fill the home screen. YouTube no longer
 * has a general trending list; the remaining charts (music, podcasts, trailers, gaming) are
 * mixed instead, so a fresh install without subscriptions still shows regular videos and not
 * only the live streams of the gaming chart.
 */
object YtDiscovery {
    private val categories = listOf(
        TrendingCategory.MUSIC,
        TrendingCategory.PODCASTS,
        TrendingCategory.TRAILERS,
        TrendingCategory.GAMING,
    )
    private val videos = MutableStateFlow<List<StreamItem>?>(null)
    private val scope = MainScope()
    private var loading = false

    /** The mixed charts, loaded on first use and kept for the session. */
    @Composable
    fun rememberVideos(): List<StreamItem>? {
        val context = LocalContext.current
        val current by videos.collectAsState()
        LaunchedEffect(Unit) { if (videos.value == null) load(context) }
        return current
    }

    /** Loads the charts again, e.g. on pull to refresh. */
    fun refresh(context: Context) {
        scope.launch { load(context.applicationContext) }
    }

    private suspend fun load(context: Context) {
        if (loading) return
        loading = true
        try {
            val region = PreferenceHelper.getTrendingRegion(context)
            val lists = withContext(Dispatchers.IO) {
                coroutineScope {
                    categories.map { category ->
                        async {
                            runCatching { MediaServiceRepository.instance.getTrending(region, category) }
                                .getOrDefault(emptyList())
                        }
                    }.awaitAll()
                }
            }
            // one from each chart in turn
            videos.value = buildList {
                for (i in 0 until (lists.maxOfOrNull { it.size } ?: 0)) {
                    lists.forEach { list -> list.getOrNull(i)?.let(::add) }
                }
            }.filter { it.url != null && !it.isUpcoming }.distinctBy { it.url }
        } finally {
            loading = false
        }
    }
}
