package com.github.libretube.ui.yt

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.findViewTreeLifecycleOwner

/** Decorative motion stops with the screen and respects Android's Remove animations switch. */
@Composable
internal fun rememberYtMotionAllowed(): Boolean {
    val view = LocalView.current
    val lifecycle = view.findViewTreeLifecycleOwner()?.lifecycle
    val resolver = view.context.contentResolver
    var started by remember(lifecycle) {
        mutableStateOf(lifecycle?.currentState?.isAtLeast(Lifecycle.State.STARTED) ?: true)
    }
    var systemMotion by remember(resolver) {
        mutableStateOf(Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f)
    }
    DisposableEffect(lifecycle, resolver) {
        val observer = LifecycleEventObserver { _, _ ->
            started = lifecycle?.currentState?.isAtLeast(Lifecycle.State.STARTED) ?: true
        }
        val settingsObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                systemMotion = Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
            }
        }
        lifecycle?.addObserver(observer)
        resolver.registerContentObserver(
            Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, settingsObserver,
        )
        onDispose {
            lifecycle?.removeObserver(observer)
            resolver.unregisterContentObserver(settingsObserver)
        }
    }
    return started && systemMotion && YtMotionPrefs.enabled
}
