package com.github.libretube.ui.yt

import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import com.github.libretube.R
import com.github.libretube.helpers.PreferenceHelper
import com.github.libretube.helpers.YtHearts

/** Keep the home and video page in sync, including preference imports and restoration. */
@Composable
internal fun rememberYtHearts(): State<Set<String>> {
    val state = remember { mutableStateOf(YtHearts.all()) }
    DisposableEffect(Unit) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key == YtHearts.KEY) state.value = YtHearts.all()
        }
        PreferenceHelper.settings.registerOnSharedPreferenceChangeListener(listener)
        state.value = YtHearts.all()
        onDispose { PreferenceHelper.settings.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return state
}

/** Local hearts are not YouTube ratings; the action names their local scope. */
@Composable
internal fun rememberLocalHeartAction(videoId: String?): WatchAction {
    val hearts by rememberYtHearts()
    val marked = videoId != null && videoId in hearts
    return WatchAction(
        if (marked) R.drawable.ic_yt_heart_fill else R.drawable.ic_yt_heart,
        stringResource(if (marked) R.string.yt_watch_local_liked else R.string.yt_watch_local_like),
    ) {
        if (!videoId.isNullOrBlank()) YtHearts.toggle(videoId)
    }
}
