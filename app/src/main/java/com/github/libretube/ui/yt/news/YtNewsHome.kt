package com.github.libretube.ui.yt.news

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.github.libretube.R
import com.github.libretube.ui.yt.PillAction
import com.github.libretube.ui.yt.PillLook
import com.github.libretube.ui.yt.PillRow
import com.github.libretube.ui.yt.SwipeAway
import com.github.libretube.ui.yt.YtCardDrag
import com.github.libretube.ui.yt.YtDrag
import com.github.libretube.ui.yt.YtFeedUi
import com.github.libretube.ui.yt.YtOverlays
import com.github.libretube.ui.yt.YtEaseOut
import com.github.libretube.ui.yt.rememberYtHaptics
import com.github.libretube.ui.yt.ytEnterTransition
import com.github.libretube.ui.yt.ytExitTransition
import kotlinx.coroutines.delay

/*
 * YT: the News overlay's parts on the home: the pinch that opens it, and the one-time hint pill.
 */

/**
 * The home's root: two fingers spread apart open the News overlay from their centre
 * (YtNewsGestures); one finger and everything else go on to the home untouched.
 */
@Composable
fun Modifier.ytNewsHome(): Modifier {
    val coords = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val haptics = rememberYtHaptics()
    return this
        .onGloballyPositioned { coords[0] = it }
        .ytNewsPinch(
            enabled = true,
            inward = false,
            toWindow = { local -> coords[0]?.takeIf { it.isAttached }?.localToWindow(local) ?: local },
            // not over a sheet of the home, nor while a card is held or dragged
            allowed = {
                !YtNews.shown && !YtOverlays.any && !YtFeedUi.sheet && !YtFeedUi.chooser && YtFeedUi.why == null &&
                    YtDrag.session == null && YtCardDrag.held.value.isEmpty()
            },
            onProgress = { progress, at -> YtNews.pinching(progress, at) },
            onCommit = { at ->
                haptics.confirm()
                YtNews.open(at)
            },
            onCancel = { YtNews.pinchEnded() },
        )
}

/**
 * "Neu: Mit zwei Fingern … für die News": once after the update, a moment after the home shows,
 * for a few seconds; "Zeigen" opens the News, a sideways swipe puts it away. Seen once, it never
 * comes again (also not once the News were opened by the gesture).
 */
@Composable
internal fun YtNewsHintPill(look: PillLook) {
    val due = remember { YtNews.hintDue() }
    var showing by remember { mutableStateOf(false) }
    val accessibility = LocalAccessibilityManager.current
    LaunchedEffect(due) {
        if (!due) return@LaunchedEffect
        delay(1800)
        if (!YtNews.hintDue()) return@LaunchedEffect
        showing = true
        YtNews.hintDone()
        delay(accessibility?.calculateRecommendedTimeoutMillis(9000L, containsIcons = true, containsText = true, containsControls = true) ?: 9000L)
        showing = false
    }
    LaunchedEffect(YtNews.shown) { if (YtNews.shown) showing = false }
    AnimatedVisibility(
        visible = showing,
        enter = ytEnterTransition(slideInVertically(spring(dampingRatio = 0.75f, stiffness = 420f)) { it } + fadeIn(tween(160))),
        exit = ytExitTransition(slideOutVertically(tween(200, easing = YtEaseOut)) { it / 2 } + fadeOut(tween(160))),
    ) {
        SwipeAway(onGone = { showing = false }) {
            PillRow(look, Modifier.testTag("yt_news_hint")) {
                Icon(
                    painterResource(R.drawable.ic_yt_news),
                    contentDescription = null,
                    tint = look.text,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = stringResource(R.string.yt_r27_news_hint),
                    color = look.text,
                    maxLines = 2,
                    style = look.style(FontWeight.Medium),
                    modifier = Modifier
                        .padding(start = 10.dp, end = 4.dp)
                        .widthIn(max = 220.dp)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                )
                PillAction(look, stringResource(R.string.yt_r27_news_hint_open), "yt_news_hint_open") {
                    showing = false
                    YtNews.open(null)
                }
                Icon(
                    painterResource(R.drawable.ic_close),
                    contentDescription = stringResource(R.string.yt_r27_news_hint_dismiss),
                    tint = look.text.copy(alpha = 0.7f),
                    modifier = Modifier
                        .testTag("yt_news_hint_close")
                        .clip(CircleShape)
                        .clickable { showing = false }
                        .padding(10.dp)
                        .size(16.dp),
                )
            }
        }
    }
}
