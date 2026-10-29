package com.github.libretube.ui.yt

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.delay

/*
 * YT: motion from the design briefs. Compose scales every animation with the system animator
 * duration, so "Remove animations" in the Android accessibility settings turns them into
 * direct state changes.
 */

/** How long a just opened video stays marked, i.e. while the player slides in. */
private const val OPENING_MILLIS = 700L

/** URL of the video that is opening right now, or null. */
val LocalYtOpening = compositionLocalOf<String?> { null }

/** Holds the opening video for a moment and clears it again. */
@Composable
fun rememberYtOpening(): MutableState<String?> {
    val opening = remember { mutableStateOf<String?>(null) }
    LaunchedEffect(opening.value) {
        if (opening.value != null) {
            delay(OPENING_MILLIS)
            opening.value = null
        }
    }
    return opening
}

/**
 * Movo: the chosen cover grows a little towards the player while the other covers step back.
 */
@Composable
fun Modifier.ytRecede(url: String?): Modifier {
    val opening = LocalYtOpening.current
    val scale by animateFloatAsState(
        targetValue = when {
            opening == null -> 1f
            opening == url -> 1.04f
            else -> 0.94f
        },
        animationSpec = tween(220),
        label = "recedeScale",
    )
    val alpha by animateFloatAsState(
        targetValue = if (opening != null && opening != url) 0.5f else 1f,
        animationSpec = tween(220),
        label = "recedeAlpha",
    )
    return graphicsLayer {
        scaleX = scale
        scaleY = scale
        this.alpha = alpha
    }
}

/** A clickable surface in [shape] that gives way slightly while pressed. */
@Composable
fun Modifier.ytPressable(shape: Shape, onClick: () -> Unit): Modifier {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, label = "pressScale")
    return graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
        .clip(shape)
        .clickable(interactionSource = interaction, indication = LocalIndication.current, onClick = onClick)
}
