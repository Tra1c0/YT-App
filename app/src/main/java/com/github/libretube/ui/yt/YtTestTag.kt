package com.github.libretube.ui.yt

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId

/**
 * YT: for the root of a composition the emulator checks look into: its test tags show as
 * resource-id, so uiautomator (crash-check.sh) finds them. Only in the emulator; on a phone
 * the semantics stay as they are.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun Modifier.ytTestRoot(): Modifier =
    if (com.github.libretube.helpers.YtGuard.isEmulator) semantics { testTagsAsResourceId = true } else this
