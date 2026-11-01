package com.github.libretube.ui.yt

import android.app.Activity
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.github.libretube.R
import com.github.libretube.helpers.YtDesign
import com.github.libretube.ui.yt.feed.YtSession
import com.github.libretube.ui.yt.feed.YtSessionState

/**
 * YT: the end card of the time box, "Zeit ist um · Noch ein Video?", at the bottom of every
 * screen (above the player and the bar) once the time is up. "Noch ein Video" plays one more
 * (the next queued one, else the best spare one of the plan), "Weiterschauen" lets a video that
 * stopped at its end point play on, the close button or a sideways swipe ends the time box.
 * Only for the designs (the time box starts from a design home).
 */
object YtTimeUpLayer {
    private const val TAG = "yt_timeup_layer"

    /** Adds the card's layer to [activity]'s content, once per activity. */
    fun attach(activity: ComponentActivity) {
        val palette = YtDesign.current().palette ?: return
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        if (content.findViewWithTag<View>(TAG) != null) return

        // a plain frame that is not clickable: touches outside the card go on to what is below
        val host = FrameLayout(activity).apply {
            tag = TAG
            // above the player container (20dp) and the flights (22dp), below the drag layer (24dp)
            elevation = 23f * resources.displayMetrics.density
            outlineProvider = null
            isClickable = false
            isFocusable = false
            setViewTreeLifecycleOwner(activity)
            setViewTreeSavedStateRegistryOwner(activity)
            setViewTreeViewModelStoreOwner(activity)
        }
        val card = ComposeView(activity).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent { Layer(palette) }
        }
        host.addView(
            card,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
            ),
        )
        content.addView(host, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    @Composable
    private fun Layer(palette: YtPalette) {
        val density = ytDensity(LocalDensity.current, LocalConfiguration.current)
        val motion = rememberYtMotionAllowed()
        CompositionLocalProvider(
            LocalDensity provides density,
            LocalYtMotion provides motion,
            LocalYtMotionStyle provides palette.motion,
        ) {
            val look = remember(palette) { pillLook(palette) }
            val state by YtSession.state.collectAsState()
            val configuration = LocalConfiguration.current
            val activity = LocalContext.current as? Activity
            // not in the small picture-in-picture window
            val pip = remember(configuration) { activity?.isInPictureInPictureMode == true }
            val done = state?.done == true && !pip
            // the last state stays while the card slides away
            val shown = remember { arrayOfNulls<YtSessionState>(1) }
            state?.takeIf { it.done }?.let { shown[0] = it }
            val haptics = rememberYtHaptics()
            LaunchedEffect(done) { if (done) haptics.land(0.4f) }
            AnimatedVisibility(
                visible = done,
                enter = ytEnterTransition(slideInVertically(spring(dampingRatio = 0.78f, stiffness = 380f)) { it } + fadeIn(tween(160))),
                exit = ytExitTransition(slideOutVertically(tween(220, easing = YtEaseOut)) { it / 2 } + fadeOut(tween(160))),
            ) {
                shown[0]?.let { Card(look, it) }
            }
        }
    }

    @Composable
    private fun Card(look: PillLook, state: YtSessionState) {
        val context = LocalContext.current
        val more = remember(state) { YtTimeBoxStarter.nextFor(state) != null }
        Box(Modifier.padding(start = 16.dp, end = 16.dp, bottom = ytNavPadding() + 12.dp)) {
            SwipeAway(onGone = { YtTimeBoxStarter.end() }) {
                Column(
                    Modifier
                        .testTag("yt_timeup_card")
                        .widthIn(max = 380.dp)
                        .then(look.card(Modifier))
                        .padding(start = 18.dp, end = 4.dp, top = 4.dp, bottom = 8.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.padding(top = 10.dp, end = 8.dp).widthIn(max = 280.dp)) {
                            val title = stringResource(R.string.yt_timebox_up)
                            Text(
                                text = if (look.uppercase) title.uppercase() else title,
                                color = look.text,
                                style = look.style(FontWeight.Bold).copy(fontSize = look.size * 1.15f),
                                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                            )
                            Text(
                                text = stringResource(R.string.yt_timebox_up_detail, state.minutes),
                                color = look.text.copy(alpha = 0.72f),
                                style = look.style(FontWeight.Normal),
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                        Icon(
                            painterResource(R.drawable.ic_close),
                            contentDescription = stringResource(R.string.yt_timebox_done),
                            tint = look.text.copy(alpha = 0.7f),
                            modifier = Modifier
                                .align(Alignment.Top)
                                .testTag("yt_timeup_done")
                                .ytPressable(CircleShape) { YtTimeBoxStarter.end() }
                                .padding(12.dp)
                                .size(16.dp),
                        )
                    }
                    Row(Modifier.padding(top = 4.dp)) {
                        if (more) {
                            PillAction(look, stringResource(R.string.yt_timebox_one_more), "yt_timeup_more", first = true) {
                                YtTimeBoxStarter.oneMore(context)
                            }
                        }
                        if (state.stoppedAt != null) {
                            PillAction(look, stringResource(R.string.yt_timebox_watch_on), "yt_timeup_on", first = !more) {
                                YtTimeBoxStarter.watchToEnd()
                            }
                        }
                        if (!more && state.stoppedAt == null) {
                            PillAction(look, stringResource(R.string.yt_timebox_done), "yt_timeup_finish", first = true) {
                                YtTimeBoxStarter.end()
                            }
                        }
                    }
                }
            }
        }
    }
}
