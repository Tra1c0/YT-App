package com.github.libretube.ui.yt.account

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.github.libretube.R
import com.github.libretube.helpers.ImageHelper
import com.github.libretube.helpers.YtDesign
import com.github.libretube.ui.yt.palette
import com.github.libretube.ui.activities.YtSignInActivity
import com.github.libretube.ui.yt.YtEaseOut
import com.github.libretube.ui.yt.YtPalette
import com.github.libretube.ui.yt.YtPalettes
import com.github.libretube.ui.yt.YtSurfaceStyle
import com.github.libretube.ui.yt.YtTheme
import com.github.libretube.ui.yt.rememberYtHaptics
import com.github.libretube.ui.yt.rememberYtSwipe
import com.github.libretube.ui.yt.ProvideYtInk
import com.github.libretube.ui.yt.ytCardShape
import com.github.libretube.ui.yt.ytInk
import com.github.libretube.ui.yt.ytScrimSheetBack
import com.github.libretube.ui.yt.ytSurfaceInk
import com.github.libretube.ui.yt.ytEnterTransition
import com.github.libretube.ui.yt.ytExitTransition
import com.github.libretube.ui.yt.ytPressable
import com.github.libretube.ui.yt.ytSurface
import com.github.libretube.ui.yt.ytSwipe
import kotlinx.coroutines.delay

/**
 * YT: the account sheet behind the profile button of every design. Signed out it offers the
 * sign-in in Google's own page and the way over Google Takeout; signed in it shows the account,
 * brings data over on request and signs out. Below come the app's menu entries (settings,
 * help, about). It springs up in the surfaces and shapes of the design and can be dragged away.
 */
object YtAccountSheet {
    private const val TAG = "yt_account_sheet"

    /** An entry of the app menu shown below the account. */
    class Entry(@DrawableRes val icon: Int, val label: String, val onClick: () -> Unit)

    /** What the sheet opens with. */
    enum class Focus { NONE, WELCOME, IMPORT, TAKEOUT }

    fun show(activity: ComponentActivity, entries: List<Entry> = emptyList(), focus: Focus = Focus.NONE) {
        val root = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        if (root.findViewWithTag<View>(TAG) != null) return
        val palette = YtDesign.current().palette ?: YtPalettes.YouTube
        val open = mutableStateOf(false)
        val back = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                open.value = false
            }
        }
        activity.onBackPressedDispatcher.addCallback(activity, back)
        val view = ComposeView(activity)
        view.tag = TAG
        view.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        view.setContent {
            var appeared by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) { open.value = true }
            LaunchedEffect(open.value) {
                if (open.value) {
                    appeared = true
                } else if (appeared) {
                    back.remove()
                    delay(300)
                    view.post { root.removeView(view) }
                }
            }
            YtTheme(palette) {
                AccountSheet(palette, open.value, entries, focus, activity) { open.value = false }
            }
        }
        root.addView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }
}

@Composable
private fun AccountSheet(
    palette: YtPalette,
    open: Boolean,
    entries: List<YtAccountSheet.Entry>,
    focus: YtAccountSheet.Focus,
    activity: ComponentActivity,
    onDismiss: () -> Unit,
) {
    val account by YtAccount.state.collectAsState()
    val import by YtImport.state.collectAsState()
    var showOptions by remember { mutableStateOf(focus == YtAccountSheet.Focus.IMPORT) }
    var showTakeout by remember { mutableStateOf(focus == YtAccountSheet.Focus.TAKEOUT) }
    val haptics = rememberYtHaptics()
    val swipe = rememberYtSwipe()
    var height by remember { mutableStateOf(IntSize.Zero) }
    val square = palette.surfaceStyle == YtSurfaceStyle.EDITORIAL
    val shape = if (square) RectangleShape else RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp)
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.88f).dp
    // the words on the sheet are checked against the sheet itself (Formen: a light sheet in a
    // dark design), the rows against their own surfaces over it
    val sheetBack = remember(palette, square) { ytScrimSheetBack(palette, square) }

    fun signIn() {
        activity.startActivity(Intent(activity, YtSignInActivity::class.java))
        onDismiss()
    }

    AnimatedVisibility(
        visible = open,
        enter = ytEnterTransition(fadeIn(tween(180))),
        exit = ytExitTransition(fadeOut(tween(180))),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color(0x80000000))
                .clickable(interactionSource = null, indication = null, onClick = onDismiss),
        )
    }
    Box(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars),
        contentAlignment = Alignment.BottomCenter,
    ) {
        AnimatedVisibility(
            visible = open,
            enter = ytEnterTransition(slideInVertically(spring(dampingRatio = 0.8f, stiffness = 300f)) { it }),
            exit = ytExitTransition(slideOutVertically(tween(220, easing = YtEaseOut)) { it }),
        ) {
            ProvideYtInk(palette, sheetBack) {
            val ink = ytInk(palette)
            Column(
                Modifier
                    .onSizeChanged { height = it }
                    .offset { IntOffset(0, swipe.offset.value.toInt().coerceAtLeast(-40)) }
                    .fillMaxWidth()
                    .heightIn(max = maxHeight)
                    .clip(shape)
                    .background(palette.surface)
                    .then(if (square) Modifier.background(palette.background) else Modifier)
                    .windowInsetsPadding(WindowInsets.navigationBars),
            ) {
                // the handle area drags the sheet away; the rest scrolls
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(28.dp)
                        .ytSwipe(swipe, thresholdPx = (height.height * 0.3f).coerceAtLeast(120f), direction = 1, haptics = haptics, vertical = true) {
                            onDismiss()
                        },
                ) {
                    Box(
                        Modifier
                            .size(36.dp, 4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(ink.textSecondary.copy(alpha = 0.5f)),
                    )
                }
                Column(
                    Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(start = 16.dp, end = 16.dp, bottom = 18.dp),
                ) {
                    val signedIn = account
                    if (signedIn == null) {
                        Text(
                            text = stringResource(if (focus == YtAccountSheet.Focus.WELCOME) R.string.yt_account_welcome_title else R.string.yt_account_title),
                            color = ink.text,
                            style = TextStyle(fontFamily = palette.font, fontWeight = FontWeight.Bold, fontSize = 22.sp),
                        )
                        Text(
                            text = stringResource(R.string.yt_account_pitch),
                            color = ink.textSecondary,
                            style = TextStyle(fontFamily = palette.font, fontSize = 14.sp, lineHeight = 19.sp),
                            modifier = Modifier.padding(top = 6.dp, bottom = 14.dp),
                        )
                        YtPrimaryButton(palette, stringResource(R.string.yt_account_sign_in), R.drawable.ic_login_filled) { signIn() }
                        YtFootnote(palette, R.drawable.ic_locked, stringResource(R.string.yt_account_sign_in_note))
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 12.dp)) {
                            YtAccountAvatar(palette, signedIn, 52)
                            Column(Modifier.padding(start = 14.dp)) {
                                Text(
                                    text = signedIn.name.ifBlank { stringResource(R.string.yt_account_row_title) },
                                    color = ink.text,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = TextStyle(fontFamily = palette.font, fontWeight = FontWeight.Bold, fontSize = 19.sp),
                                )
                                if (signedIn.handle.isNotBlank()) {
                                    Text(
                                        text = signedIn.handle,
                                        color = ink.textSecondary,
                                        maxLines = 1,
                                        style = TextStyle(fontFamily = palette.font, fontSize = 13.sp),
                                    )
                                }
                            }
                        }
                        if (showOptions && !import.running) {
                            YtImportOptionsPanel(palette, onStart = {
                                YtImport.fromAccount(it)
                                showOptions = false
                            })
                        } else {
                            YtSheetRow(palette, R.drawable.ic_restore, stringResource(R.string.yt_account_import), stringResource(R.string.yt_account_import_summary)) {
                                showOptions = true
                            }
                        }
                    }

                    YtImportStatus(palette, import)

                    YtSheetRow(palette, R.drawable.ic_backup, stringResource(R.string.yt_account_takeout), stringResource(R.string.yt_account_takeout_note)) {
                        showTakeout = !showTakeout
                    }
                    if (showTakeout) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                            YtSecondaryButton(palette, stringResource(R.string.yt_account_takeout_open), Modifier.weight(1f)) {
                                YtTakeout.openInBrowser(activity)
                            }
                            YtSecondaryButton(palette, stringResource(R.string.yt_account_takeout_pick), Modifier.weight(1f)) {
                                YtTakeout.pick(activity)
                            }
                        }
                    }

                    if (account != null) {
                        YtSheetRow(palette, R.drawable.ic_login_filled, stringResource(R.string.yt_account_switch), null) {
                            YtAccount.signOut()
                            signIn()
                        }
                        YtSheetRow(palette, R.drawable.ic_close, stringResource(R.string.yt_account_sign_out), null) {
                            YtAccount.signOut()
                        }
                    } else if (focus == YtAccountSheet.Focus.WELCOME) {
                        Text(
                            text = stringResource(R.string.yt_account_later),
                            color = ink.textSecondary,
                            style = TextStyle(fontFamily = palette.font, fontWeight = FontWeight.SemiBold, fontSize = 15.sp),
                            modifier = Modifier
                                .align(Alignment.CenterHorizontally)
                                .padding(top = 14.dp)
                                .clickable(onClick = onDismiss)
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }

                    if (entries.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(1.dp)
                                .background(ink.textSecondary.copy(alpha = 0.2f)),
                        )
                        entries.forEach { entry ->
                            YtSheetRow(palette, entry.icon, entry.label, null) {
                                onDismiss()
                                entry.onClick()
                            }
                        }
                    }
                }
            }
            }
        }
    }
}

// --- pieces shared with the sign-in window

@Composable
internal fun YtAccountAvatar(palette: YtPalette, account: YtAccountInfo?, size: Int) {
    val photo = account?.photo
    if (photo != null) {
        AsyncImage(
            model = photo,
            contentDescription = null,
            imageLoader = ImageHelper.loader(),
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(size.dp)
                .clip(CircleShape)
                .background(palette.raised),
        )
    } else {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(size.dp)
                .clip(CircleShape)
                .background(palette.accent),
        ) {
            val initial = account?.name?.firstOrNull()?.uppercase()
            if (initial != null) {
                Text(
                    text = initial,
                    color = palette.onAccent,
                    style = TextStyle(fontFamily = palette.font, fontWeight = FontWeight.Bold, fontSize = (size * 0.42f).sp),
                )
            } else {
                Icon(
                    painterResource(R.drawable.ic_login_filled),
                    contentDescription = null,
                    tint = palette.onAccent,
                    modifier = Modifier.size((size * 0.5f).dp),
                )
            }
        }
    }
}

private fun pillShape(palette: YtPalette): Shape =
    if (palette.surfaceStyle == YtSurfaceStyle.EDITORIAL) RectangleShape else RoundedCornerShape(50)

@Composable
internal fun YtPrimaryButton(palette: YtPalette, label: String, @DrawableRes icon: Int?, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = pillShape(palette)
    Row(
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .ytPressable(shape, squish = palette.surfaceStyle == YtSurfaceStyle.CLAY, onClick = onClick)
            .clip(shape)
            .background(palette.accent)
            .padding(horizontal = 18.dp, vertical = 15.dp),
    ) {
        if (icon != null) {
            Icon(painterResource(icon), contentDescription = null, tint = palette.onAccent, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
        }
        Text(
            text = label,
            color = palette.onAccent,
            style = TextStyle(fontFamily = palette.font, fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
        )
    }
}

@Composable
internal fun YtSecondaryButton(palette: YtPalette, label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {    val ink = ytSurfaceInk(palette)

    val shape = ytCardShape(palette, 14)
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .ytPressable(shape, squish = palette.surfaceStyle == YtSurfaceStyle.CLAY, onClick = onClick)
            .ytSurface(palette, shape)
            .padding(horizontal = 12.dp, vertical = 12.dp),
    ) {
        Text(
            text = label,
            color = ink.text,
            maxLines = 2,
            style = TextStyle(fontFamily = palette.font, fontWeight = FontWeight.SemiBold, fontSize = 14.sp),
        )
    }
}

@Composable
internal fun YtFootnote(palette: YtPalette, @DrawableRes icon: Int, text: String) {    val ink = ytInk(palette)

    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 10.dp, bottom = 6.dp)) {
        Icon(painterResource(icon), contentDescription = null, tint = ink.textSecondary, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(6.dp))
        Text(
            text = text,
            color = ink.textSecondary,
            style = TextStyle(fontFamily = palette.font, fontSize = 12.sp, lineHeight = 16.sp),
        )
    }
}

@Composable
internal fun YtSheetRow(palette: YtPalette, @DrawableRes icon: Int, label: String, summary: String?, onClick: () -> Unit) {    val ink = ytSurfaceInk(palette)

    val shape = ytCardShape(palette, 14)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .padding(top = 8.dp)
            .fillMaxWidth()
            .ytPressable(shape, squish = palette.surfaceStyle == YtSurfaceStyle.CLAY, onClick = onClick)
            .ytSurface(palette, shape)
            .padding(horizontal = 16.dp, vertical = 13.dp),
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = ink.text, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        Column {
            Text(
                text = label,
                color = ink.text,
                style = TextStyle(fontFamily = palette.font, fontWeight = FontWeight.Medium, fontSize = 15.sp),
            )
            if (summary != null) {
                Text(
                    text = summary,
                    color = ink.textSecondary,
                    style = TextStyle(fontFamily = palette.font, fontSize = 12.sp, lineHeight = 16.sp),
                )
            }
        }
    }
}

/** The question what to bring over, with one switch per list. */
@Composable
internal fun YtImportOptionsPanel(palette: YtPalette, onStart: (YtImportOptions) -> Unit) {    val ink = ytInk(palette)

    var subscriptions by remember { mutableStateOf(true) }
    var playlists by remember { mutableStateOf(true) }
    var watchLater by remember { mutableStateOf(true) }
    var liked by remember { mutableStateOf(true) }
    var history by remember { mutableStateOf(true) }
    Column(Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.yt_account_import_question),
            color = ink.text,
            style = TextStyle(fontFamily = palette.font, fontWeight = FontWeight.SemiBold, fontSize = 15.sp),
            modifier = Modifier.padding(bottom = 4.dp),
        )
        OptionRow(palette, stringResource(R.string.yt_account_opt_subscriptions), subscriptions) { subscriptions = it }
        OptionRow(palette, stringResource(R.string.yt_account_opt_playlists), playlists) { playlists = it }
        OptionRow(palette, stringResource(R.string.yt_account_opt_watch_later), watchLater) { watchLater = it }
        OptionRow(palette, stringResource(R.string.yt_account_opt_liked), liked) { liked = it }
        OptionRow(palette, stringResource(R.string.yt_account_opt_history), history) { history = it }
        Text(
            text = stringResource(R.string.yt_account_local_note),
            color = ink.textSecondary,
            style = TextStyle(fontFamily = palette.font, fontSize = 12.sp),
            modifier = Modifier.padding(top = 2.dp, bottom = 10.dp),
        )
        YtPrimaryButton(palette, stringResource(R.string.yt_account_import_start), R.drawable.ic_restore) {
            onStart(YtImportOptions(subscriptions, playlists, watchLater, liked, history))
        }
    }
}

@Composable
private fun OptionRow(palette: YtPalette, label: String, checked: Boolean, onChange: (Boolean) -> Unit) {    val ink = ytInk(palette)

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) },
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onChange,
            colors = CheckboxDefaults.colors(
                checkedColor = palette.accent,
                uncheckedColor = ink.textSecondary,
                checkmarkColor = palette.onAccent,
            ),
        )
        Text(
            text = label,
            color = ink.text,
            style = TextStyle(fontFamily = palette.font, fontSize = 15.sp),
        )
    }
}

/** Progress of a running import or the result of the last one. */
@Composable
internal fun YtImportStatus(palette: YtPalette, state: YtImportState) {    val ink = ytSurfaceInk(palette)

    if (!state.running && state.result == null) return
    val shape = ytCardShape(palette, 14)
    Column(
        Modifier
            .padding(top = 10.dp)
            .fillMaxWidth()
            .ytSurface(palette, shape)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(
            text = if (state.running) state.progress else state.result.orEmpty(),
            color = if (state.failed) palette.live else ink.text,
            style = TextStyle(fontFamily = palette.font, fontSize = 14.sp, lineHeight = 18.sp),
        )
        if (state.running) {
            LinearProgressIndicator(
                color = palette.accent,
                trackColor = palette.raised,
                modifier = Modifier
                    .padding(top = 10.dp)
                    .fillMaxWidth(),
            )
        }
    }
}
