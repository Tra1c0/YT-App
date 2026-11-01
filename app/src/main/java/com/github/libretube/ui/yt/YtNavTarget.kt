package com.github.libretube.ui.yt

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.selection.selectable
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.testTag

/** A single named, selectable tab even when a design draws several decorative copies. */
@Composable
internal fun Modifier.ytNavTarget(
    item: YtNavItem,
    active: Boolean,
    interaction: MutableInteractionSource? = null,
    select: () -> Unit,
): Modifier {
    val label = stringResource(item.label)
    val tag = "yt_nav_${LocalContext.current.resources.getResourceEntryName(item.id)}"
    return selectable(active, interaction, indication = null, role = Role.Tab, onClick = select)
        .clearAndSetSemantics {
            contentDescription = label
            role = Role.Tab
            selected = active
            testTag = tag
            onClick { select(); true }
        }
}
