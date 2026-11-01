package com.github.libretube.ui.yt

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.github.libretube.R

/*
 * YT: the button that opens the Feed-Regler, in each design's own form (YouTube's sits in its
 * filter row, Pop's and Formen's are a tab and a tile of their own screens). Each one fills
 * with the design's selection colour while the sheet is open, so the way back reads the same.
 */

/** Opens the Feed-Regler with a tick. */
@Composable
internal fun rememberOpenFeedSheet(): () -> Unit {
    val haptics = rememberYtHaptics()
    return remember(haptics) {
        {
            haptics.tick()
            YtFeedUi.openSheet()
        }
    }
}

/**
 * A round button with the slider glyph: [fill] and [ink] at rest, [activeFill] and [activeInk]
 * while the sheet is open. [surface] draws the design's own material behind it (glass, frost).
 */
@Composable
internal fun YtTuneCircle(
    fill: Color,
    ink: Color,
    activeFill: Color,
    activeInk: Color,
    modifier: Modifier = Modifier,
    size: Dp = 34.dp,
    glyph: Dp = 18.dp,
    shape: Shape = CircleShape,
    surface: ((Modifier) -> Modifier)? = null,
) {
    val open = rememberOpenFeedSheet()
    val active = YtFeedUi.sheet
    val background by animateColorAsState(if (active) activeFill else fill, ytStateSpec(), label = "tuneFill")
    val tint by animateColorAsState(if (active) activeInk else ink, ytStateSpec(), label = "tuneInk")
    val description = stringResource(R.string.yt_feed_open)
    val interaction = remember { MutableInteractionSource() }
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .testTag("yt_feed_button")
            .ytPressScale(interaction)
            .size(size)
            .then(surface?.invoke(Modifier) ?: Modifier.clip(shape))
            .background(background)
            .clickable(interactionSource = interaction, indication = LocalIndication.current, onClick = open)
            .semantics {
                contentDescription = description
                role = Role.Button
            },
    ) {
        Icon(painterResource(R.drawable.ic_yt_tune), contentDescription = null, tint = tint, modifier = Modifier.size(glyph))
    }
}

/**
 * A word that opens the Feed-Regler (After "Programm", Side "Feed anpassen", Editorial
 * "§ Einstellen", Kiesel "Anpassen"), in [style]; [active] colours it while the sheet is open.
 */
@Composable
internal fun YtTuneWord(
    text: String,
    style: TextStyle,
    color: Color,
    active: Color,
    modifier: Modifier = Modifier,
    glyph: Boolean = false,
) {
    val open = rememberOpenFeedSheet()
    val ink by animateColorAsState(if (YtFeedUi.sheet) active else color, ytStateSpec(), label = "tuneWord")
    val description = stringResource(R.string.yt_feed_open)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .testTag("yt_feed_button")
            .heightIn(min = 40.dp)
            .clickable(onClick = open)
            .semantics(mergeDescendants = true) {
                contentDescription = description
                role = Role.Button
            }
            .padding(horizontal = 4.dp),
    ) {
        if (glyph) Icon(painterResource(R.drawable.ic_yt_tune), contentDescription = null, tint = ink, modifier = Modifier.padding(end = 5.dp).size(15.dp))
        Text(text = text, color = ink, maxLines = 1, style = style)
    }
}
