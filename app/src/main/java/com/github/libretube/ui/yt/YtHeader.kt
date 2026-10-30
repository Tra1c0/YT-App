package com.github.libretube.ui.yt

import android.view.Gravity
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.annotation.DrawableRes
import androidx.appcompat.widget.PopupMenu
import androidx.appcompat.widget.Toolbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.navigation.NavController
import com.github.libretube.R
import com.github.libretube.helpers.YtDesign
import com.google.android.material.appbar.MaterialToolbar

/**
 * YT: the header row of each design as in the references: logo and wordmark on the left,
 * search and a profile button on the right, drawn in Compose at the reference sizes. Search
 * opens the app's search field in the toolbar; the profile button opens the app menu.
 */
object YtHeader {
    /** Ids of the app menu that the profile button shows instead of the toolbar overflow. */
    private val MENU_IDS = listOf(R.id.action_settings, R.id.action_help, R.id.action_donate, R.id.action_about)

    /** True while a design draws its own header. */
    val active: Boolean get() = YtDesign.current() != YtDesign.STANDARD

    fun apply(toolbar: MaterialToolbar, onSearch: () -> Unit, onMenuItem: (MenuItem) -> Boolean) {
        val design = YtDesign.current()
        val palette = design.palette ?: return

        YtChrome.openSearch = onSearch
        toolbar.title = ""
        toolbar.setContentInsetsRelative(0, 0)
        toolbar.contentInsetStartWithNavigation = 0
        val header = ComposeView(toolbar.context)
        header.setContent {
            YtTheme(palette) {
                YtHeaderBar(
                    design = design,
                    palette = palette,
                    onSearch = onSearch,
                    onProfile = {
                        PopupMenu(header.context, header, Gravity.END).apply {
                            menuInflater.inflate(R.menu.action_bar, menu)
                            menu.removeItem(R.id.action_search)
                            setOnMenuItemClickListener { onMenuItem(it) }
                        }.show()
                    },
                )
            }
        }
        toolbar.addView(
            header,
            Toolbar.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
    }

    /** The search item stays hidden until the header's search button opens it. */
    fun onMenuCreated(searchItem: MenuItem, menu: android.view.Menu) {
        if (!active) return
        searchItem.isVisible = searchItem.isActionViewExpanded
        MENU_IDS.forEach { menu.findItem(it)?.isVisible = false }
    }

    fun onSearchCollapsed(toolbar: View, searchItem: MenuItem) {
        if (!active) return
        toolbar.post { if (!searchItem.isActionViewExpanded) searchItem.isVisible = false }
    }

    /**
     * "After": the home poster runs behind the header, as in the reference. The fragment area
     * starts at the top of the screen; every other destination keeps the room for the header
     * as top padding. Must run in onCreate, before MotionLayout reads the layout.
     */
    fun overlayAfterHome(appBar: View, fragment: View, navController: NavController) {
        if (YtDesign.current() != YtDesign.AFTER) return
        fragment.updateLayoutParams<ConstraintLayout.LayoutParams> {
            topToBottom = ConstraintLayout.LayoutParams.UNSET
            topToTop = ConstraintLayout.LayoutParams.PARENT_ID
        }
        appBar.translationZ = appBar.resources.displayMetrics.density

        var overlay = false
        fun update() {
            val header = appBar.height
            fragment.updatePadding(top = if (overlay) 0 else header)
            YtChrome.headerOverlapPx = if (overlay) header else 0
        }
        navController.addOnDestinationChangedListener { _, destination, _ ->
            overlay = destination.id == R.id.homeFragment
            update()
        }
        appBar.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top != oldBottom - oldTop) update()
        }
    }

    /** Search icon of the toolbar, or null for the classic one. */
    @DrawableRes
    fun searchIcon(): Int? = when (YtDesign.current()) {
        YtDesign.STANDARD -> null
        else -> R.drawable.ic_yt_search
    }
}

/** YT: state of the activity chrome that design screens lay out around. */
object YtChrome {
    /** Height in px of the header drawn over the current screen (After home), else 0. */
    var headerOverlapPx by mutableIntStateOf(0)

    /** Height in px of the floating navigation bar over the bottom of the screens, else 0. */
    var navOverlapPx by mutableIntStateOf(0)

    /** Opens the search field of the toolbar (e.g. from a search field on a home screen). */
    var openSearch: (() -> Unit)? = null
}

private enum class HeaderButton { SEARCH, PROFILE }

/** How a header button looks: size, shape, fill, outline, shadow, icon size and tint. */
private class Look(
    val size: Dp,
    val shape: Shape,
    val fill: Brush?,
    val outline: Color?,
    val outlineWidth: Dp,
    val shadow: Dp,
    val iconSize: Dp,
    val tint: Color,
    val filled: Boolean = false,
)

/** Layout of one design's header, measured in the references (docs/DESIGN-MASSE.md). */
private class HeaderStyle(
    val start: Dp,
    val end: Dp,
    val gap: Dp,
    @DrawableRes val logo: Int?,
    val logoWidth: Dp,
    val logoHeight: Dp,
    val wordmark: String,
    val wordmarkFont: FontFamily,
    val wordmarkWeight: FontWeight,
    val wordmarkSize: TextUnit,
    val wordmarkColor: Color,
)

private fun headerStyle(design: YtDesign, palette: YtPalette): HeaderStyle = when (design) {
    YtDesign.MOVO -> HeaderStyle(22.dp, 12.dp, 12.dp, R.drawable.ic_yt_logo_movo, 30.dp, 30.dp,
        "movo", YtFonts.geist, FontWeight.ExtraBold, 30.sp, palette.text)
    YtDesign.AFTER -> HeaderStyle(27.dp, 16.dp, 18.dp, null, 0.dp, 0.dp,
        "after", YtFonts.fraunces, FontWeight.ExtraBold, 25.sp, palette.text)
    YtDesign.SIDE -> HeaderStyle(30.dp, 16.dp, 18.dp, R.drawable.ic_yt_logo_side, 34.dp, 26.dp,
        "side", YtFonts.figtree, FontWeight.SemiBold, 28.sp, palette.text)
    YtDesign.CLAY -> HeaderStyle(18.dp, 12.dp, 11.dp, R.drawable.ic_yt_logo_youtube, 48.dp, 33.dp,
        "YouTube", YtFonts.robotoCondensed, FontWeight.Bold, 26.sp, palette.text)
    YtDesign.EDITORIAL -> HeaderStyle(17.dp, 16.dp, 16.dp, R.drawable.ic_yt_logo_youtube, 34.dp, 24.dp,
        "YouTube", YtFonts.robotoCondensed, FontWeight.Bold, 18.sp, palette.text)
    YtDesign.GLASS -> HeaderStyle(20.dp, 18.dp, 9.dp, R.drawable.ic_yt_logo_youtube, 36.dp, 25.dp,
        "YouTube", YtFonts.robotoCondensed, FontWeight.Bold, 21.sp, palette.text)
    YtDesign.POP -> HeaderStyle(20.dp, 13.dp, 11.dp, R.drawable.ic_yt_logo_youtube, 38.dp, 26.dp,
        "YouTube", YtFonts.robotoCondensed, FontWeight.Bold, 22.5.sp, palette.text)
    else -> HeaderStyle(15.dp, 14.dp, 14.dp, R.drawable.ic_yt_logo_youtube, 34.dp, 24.dp,
        "YouTube", YtFonts.robotoCondensed, FontWeight.Bold, 19.sp, palette.text)
}

@Composable
private fun YtHeaderBar(
    design: YtDesign,
    palette: YtPalette,
    onSearch: () -> Unit,
    onProfile: () -> Unit,
) {
    if (design == YtDesign.KIESEL) {
        KieselHeaderBar(palette, onSearch, onProfile)
        return
    }
    val style = headerStyle(design, palette)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxSize()
            .padding(start = style.start, end = style.end),
    ) {
        style.logo?.let {
            Icon(
                painterResource(it),
                contentDescription = null,
                tint = Color.Unspecified,
                modifier = Modifier.size(style.logoWidth, style.logoHeight),
            )
            Spacer(Modifier.width(if (design == YtDesign.MOVO || design == YtDesign.SIDE) 10.dp else 5.dp))
        }
        Text(
            text = buildAnnotatedString {
                append(style.wordmark)
                if (design == YtDesign.SIDE) withStyle(SpanStyle(color = palette.accent)) { append(".") }
            },
            color = style.wordmarkColor,
            maxLines = 1,
            style = TextStyle(
                fontFamily = style.wordmarkFont,
                fontWeight = style.wordmarkWeight,
                fontSize = style.wordmarkSize,
                letterSpacing = (style.wordmarkSize.value * -0.015f).sp,
            ),
        )
        Spacer(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(style.gap), verticalAlignment = Alignment.CenterVertically) {
            HeaderButtonView(design, palette, HeaderButton.SEARCH, onSearch)
            HeaderButtonView(design, palette, HeaderButton.PROFILE, onProfile)
        }
    }
}

@Composable
private fun HeaderButtonView(design: YtDesign, palette: YtPalette, kind: HeaderButton, onClick: () -> Unit) {
    val icon = if (kind == HeaderButton.SEARCH) R.drawable.ic_yt_search else R.drawable.ic_yt_person
    val activeIcon = if (kind == HeaderButton.PROFILE) R.drawable.ic_yt_nav_person_fill else icon
    val look = when (design) {
        YtDesign.CLAY -> if (kind == HeaderButton.SEARCH) {
            Look(48.dp, CircleShape, Brush.verticalGradient(listOf(Color(0xFFFFF3E9), Color(0xFFF4D8C4))),
                Color(0x99FFFFFF), 1.dp, 6.dp, 24.dp, palette.text)
        } else {
            Look(48.dp, CircleShape, Brush.verticalGradient(listOf(Color(0xFFCADBF0), Color(0xFFA9BFDD))),
                Color(0xFFF7EDE4), 3.dp, 6.dp, 26.dp, palette.text, filled = true)
        }
        YtDesign.EDITORIAL -> Look(27.dp, RectangleShape, null, palette.text, 1.3.dp, 0.dp, 16.dp, palette.text)
        YtDesign.GLASS -> Look(38.dp, CircleShape, Brush.verticalGradient(listOf(Color(0x99FFFFFF), Color(0x4DFFFFFF))),
            Color(0xCCFFFFFF), 1.dp, 4.dp, 20.dp, palette.text)
        YtDesign.POP -> if (kind == HeaderButton.SEARCH) {
            Look(38.dp, CircleShape, Brush.linearGradient(listOf(Color(0xFFDCD6F5), Color(0xFFDCD6F5))),
                null, 0.dp, 0.dp, 20.dp, palette.text)
        } else {
            Look(38.dp, CircleShape, Brush.linearGradient(listOf(Color(0xFFC9B3F4), Color(0xFFC9B3F4))),
                null, 0.dp, 0.dp, 24.dp, palette.text, filled = true)
        }
        YtDesign.SHAPES -> if (kind == HeaderButton.SEARCH) {
            Look(40.dp, CookieShape(9), Brush.linearGradient(listOf(palette.lime, palette.lime)),
                null, 0.dp, 0.dp, 20.dp, palette.ink)
        } else {
            Look(40.dp, RoundedCornerShape(13.dp), Brush.linearGradient(listOf(palette.pink, palette.pink)),
                null, 0.dp, 0.dp, 22.dp, palette.ink, filled = true)
        }
        YtDesign.FROST -> Look(40.dp, CircleShape, Brush.verticalGradient(listOf(Color(0x47FFFFFF), Color(0x1FFFFFFF))),
            Color(0x73FFFFFF), 1.dp, 0.dp, 20.dp, palette.text)
        YtDesign.MOVO -> if (kind == HeaderButton.SEARCH) {
            Look(40.dp, CircleShape, Brush.linearGradient(listOf(Color(0xFF1E1E22), Color(0xFF1E1E22))),
                null, 0.dp, 0.dp, 20.dp, palette.text)
        } else {
            Look(44.dp, CircleShape, Brush.linearGradient(listOf(Color(0xFF26262B), Color(0xFF26262B))),
                null, 0.dp, 0.dp, 22.dp, palette.text)
        }
        YtDesign.AFTER -> if (kind == HeaderButton.SEARCH) {
            Look(36.dp, CircleShape, null, null, 0.dp, 0.dp, 22.dp, palette.text)
        } else {
            Look(36.dp, CircleShape, Brush.linearGradient(listOf(palette.raised, palette.raised)),
                palette.text, 2.dp, 0.dp, 20.dp, palette.text)
        }
        YtDesign.SIDE -> if (kind == HeaderButton.SEARCH) {
            Look(34.dp, CircleShape, null, null, 0.dp, 0.dp, 22.dp, palette.text)
        } else {
            Look(34.dp, CircleShape, Brush.linearGradient(listOf(palette.raised, palette.raised)),
                null, 0.dp, 0.dp, 20.dp, palette.text)
        }
        else -> if (kind == HeaderButton.SEARCH) {
            Look(34.dp, CircleShape, null, null, 0.dp, 0.dp, 24.dp, palette.text)
        } else {
            Look(32.dp, CircleShape, Brush.linearGradient(listOf(palette.raised, palette.raised)),
                null, 0.dp, 0.dp, 18.dp, palette.text)
        }
    }
    val interaction = remember { MutableInteractionSource() }
    // Formen: the scalloped search button turns slowly
    val spin = if (design == YtDesign.SHAPES && kind == HeaderButton.SEARCH) rememberSpin(24000) else null
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(look.size)
            .ytPressScale(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
    ) {
        Box(
            Modifier
                .matchParentSize()
                .graphicsLayer { rotationZ = spin?.value ?: 0f }
                .then(if (look.shadow > 0.dp) Modifier.shadow(look.shadow, look.shape, clip = false) else Modifier)
                .clip(look.shape)
                .then(if (look.fill != null) Modifier.background(look.fill) else Modifier)
                .then(if (look.outline != null) Modifier.border(look.outlineWidth, look.outline, look.shape) else Modifier),
        )
        Icon(
            painterResource(if (look.filled) activeIcon else icon),
            contentDescription = null,
            tint = look.tint,
            modifier = Modifier.size(look.iconSize),
        )
    }
}

/** Kiesel: avatar with a greeting on the left, as in the inspiration image, search on the right. */
@Composable
private fun KieselHeaderBar(palette: YtPalette, onSearch: () -> Unit, onProfile: () -> Unit) {
    val avatar = remember { MutableInteractionSource() }
    val search = remember { MutableInteractionSource() }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxSize()
            .padding(start = 18.dp, end = 12.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(42.dp)
                .ytPressScale(avatar)
                .clip(CircleShape)
                .background(palette.lavender)
                .clickable(interactionSource = avatar, indication = null, onClick = onProfile),
        ) {
            Icon(painterResource(R.drawable.ic_yt_nav_person_fill), contentDescription = null, tint = palette.ink, modifier = Modifier.size(22.dp))
        }
        androidx.compose.foundation.layout.Column(Modifier.padding(start = 12.dp)) {
            Text(
                text = androidx.compose.ui.res.stringResource(R.string.yt_kiesel_welcome),
                color = palette.textSecondary,
                style = TextStyle(fontFamily = YtFonts.jakarta, fontSize = 12.sp),
            )
            Text(
                text = androidx.compose.ui.res.stringResource(R.string.yt_kiesel_you),
                color = palette.text,
                style = TextStyle(fontFamily = YtFonts.jakarta, fontWeight = FontWeight.ExtraBold, fontSize = 17.sp),
            )
        }
        Spacer(Modifier.weight(1f))
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(42.dp)
                .ytPressScale(search)
                .clip(CircleShape)
                .clickable(interactionSource = search, indication = null, onClick = onSearch),
        ) {
            Icon(painterResource(R.drawable.ic_yt_search), contentDescription = null, tint = palette.text, modifier = Modifier.size(23.dp))
        }
    }
}
