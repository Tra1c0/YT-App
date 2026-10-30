package com.github.libretube.ui.yt

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.DrawableRes
import androidx.annotation.IdRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.children
import androidx.navigation.NavController
import androidx.navigation.NavDestination.Companion.hierarchy
import com.github.libretube.R
import com.github.libretube.helpers.YtDesign
import com.google.android.material.bottomnavigation.BottomNavigationView
import android.widget.ScrollView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.core.widget.NestedScrollView
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.recyclerview.widget.RecyclerView
import androidx.compose.ui.platform.AbstractComposeView
import com.github.libretube.ui.views.YtBottomNavigationView
import com.github.libretube.ui.yt.liquid.YtViewBackdrop
import com.kyant.backdrop.Backdrop
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.floatingactionbutton.FloatingActionButton
import java.util.WeakHashMap

/** One tab of a design's navigation bar. [icon] null draws the design's own mark or no icon. */
class YtNavItem(
    @IdRes val id: Int,
    @StringRes val label: Int,
    @DrawableRes val icon: Int?,
    @DrawableRes val activeIcon: Int? = icon,
)

/** What the navigation bar shows: the selected tab and the badges (e.g. new videos). */
class YtNavState {
    var selected by mutableIntStateOf(0)
    val badges = mutableStateMapOf<Int, Int>()
}

/** The look of a design's floating bar, measured in the references (docs/DESIGN-MASSE.md). */
private class YtNavStyle(
    val height: Dp,
    val radius: Dp,
    val container: Color,
    val border: Color,
    val active: Color,
    val inactive: Color,
    val font: FontFamily,
    val labelSize: TextUnit,
    val activeWeight: FontWeight,
    val weight: FontWeight,
    val iconSize: Dp,
    /** Underline below the active label; Color.Unspecified for none. */
    val underline: Color,
    val underlineHeight: Dp,
)

object YtNav {
    /** The tabs of each design, as in the references. Shorts has no destination in YT. */
    fun items(design: YtDesign): List<YtNavItem> = when (design) {
        YtDesign.YOUTUBE -> listOf(
            YtNavItem(R.id.homeFragment, R.string.yt_nav_home, R.drawable.ic_yt_nav_home, R.drawable.ic_yt_nav_home_fill),
            YtNavItem(R.id.subscriptionsFragment, R.string.yt_nav_subscriptions, R.drawable.ic_yt_nav_subs),
            YtNavItem(R.id.trendsFragment, R.string.yt_nav_live, R.drawable.ic_yt_nav_live),
            YtNavItem(R.id.libraryFragment, R.string.yt_nav_you, R.drawable.ic_yt_person, R.drawable.ic_yt_nav_person_fill),
        )
        YtDesign.MOVO -> listOf(
            YtNavItem(R.id.homeFragment, R.string.yt_nav_discover, null),
            YtNavItem(R.id.subscriptionsFragment, R.string.yt_nav_channels, R.drawable.ic_yt_nav_bars),
            YtNavItem(R.id.libraryFragment, R.string.yt_nav_watchlist, R.drawable.ic_yt_nav_bookmark, R.drawable.ic_yt_nav_bookmark_fill),
        )
        YtDesign.AFTER -> listOf(
            YtNavItem(R.id.homeFragment, R.string.yt_nav_today, null),
            YtNavItem(R.id.trendsFragment, R.string.yt_nav_rooms, null),
            YtNavItem(R.id.libraryFragment, R.string.yt_nav_collection, null),
        )
        YtDesign.SIDE -> listOf(
            YtNavItem(R.id.homeFragment, R.string.yt_nav_start, R.drawable.ic_yt_nav_home, R.drawable.ic_yt_nav_home_fill),
            YtNavItem(R.id.subscriptionsFragment, R.string.yt_nav_inbox, R.drawable.ic_yt_nav_chat, R.drawable.ic_yt_nav_chat_fill),
            YtNavItem(R.id.libraryFragment, R.string.yt_nav_mine, R.drawable.ic_yt_person, R.drawable.ic_yt_nav_person_fill),
        )
        YtDesign.CLAY, YtDesign.EDITORIAL, YtDesign.GLASS, YtDesign.POP, YtDesign.SHAPES, YtDesign.FROST -> listOf(
            YtNavItem(R.id.homeFragment, R.string.yt_nav_home, R.drawable.ic_yt_nav_home, R.drawable.ic_yt_nav_home_fill),
            // Shorts draws its own glyph (YtShortsGlyph)
            YtNavItem(R.id.trendsFragment, R.string.yt_nav_shorts, null),
            YtNavItem(R.id.subscriptionsFragment, R.string.yt_nav_subscriptions, R.drawable.ic_yt_nav_subs, R.drawable.ic_yt_nav_subs_fill),
            YtNavItem(R.id.libraryFragment, R.string.yt_nav_you, R.drawable.ic_yt_person, R.drawable.ic_yt_nav_person_fill),
        )
        YtDesign.STANDARD -> emptyList()
    }

    /**
     * Replaces the look of the classic bottom navigation with the floating bar of the selected
     * design. The bar lives inside the BottomNavigationView, so its height, the mini player
     * transition and the navigation logic stay as they are; the classic menu view is hidden.
     *
     * @return the state to update badges with, or null if the classic bar stays.
     */
    fun attach(
        activity: AppCompatActivity,
        bottomNav: BottomNavigationView,
        navController: NavController,
    ): YtNavState? {
        val design = YtDesign.current()
        val palette = design.palette ?: return null
        val items = items(design)

        for (item in bottomNav.menu.children) {
            item.isVisible = items.any { it.id == item.itemId }
        }
        bottomNav.children.forEach { it.visibility = View.GONE }
        bottomNav.background = null
        bottomNav.minimumHeight = 0
        float(activity, bottomNav)

        val state = YtNavState()
        state.selected = bottomNav.selectedItemId
        navController.addOnDestinationChangedListener { _, destination, _ ->
            items.firstOrNull { item -> destination.hierarchy.any { it.id == item.id } }
                ?.let { state.selected = it.id }
        }

        // the screens below, seen through the glass of the liquid designs
        val backdrop = activity.findViewById<View>(R.id.fragment)?.let { YtViewBackdrop(it, palette.background) }
        val bar = ComposeView(activity).apply {
            setContent {
                YtTheme(palette) {
                    YtNavBar(design, palette, items, state, backdrop) { id -> bottomNav.selectedItemId = id }
                }
            }
        }
        backdrop?.let { bar.viewTreeObserver.addOnPreDrawListener(it.preDrawListener) }
        bottomNav.addView(
            bar,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        return state
    }

    /**
     * Lets the bar float freely: the screens run to the bottom edge behind it, touches beside it
     * reach the screen, and lists get room at their end to scroll out from under it.
     */
    private fun float(activity: AppCompatActivity, bottomNav: BottomNavigationView) {
        (bottomNav as? YtBottomNavigationView)?.floating = true
        // above the screens (no shadow without a background), below the player
        bottomNav.elevation = 4 * activity.resources.displayMetrics.density
        activity.findViewById<View>(R.id.fragment)?.updateLayoutParams<ConstraintLayout.LayoutParams> {
            bottomToTop = ConstraintLayout.LayoutParams.UNSET
            bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
        }

        val screens = activity.supportFragmentManager.findFragmentById(R.id.fragment)?.childFragmentManager ?: return
        fun insetAll(manager: FragmentManager) {
            for (fragment in manager.fragments) {
                if (fragment is DialogFragment || !fragment.isAdded) continue
                fragment.view?.let { YtNavInsets.apply(it, YtChrome.navOverlapPx) }
                insetAll(fragment.childFragmentManager)
            }
        }
        bottomNav.addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
            val overlap = if (view.isVisible) view.height else 0
            if (overlap != YtChrome.navOverlapPx) {
                YtChrome.navOverlapPx = overlap
                insetAll(screens)
            }
        }
        screens.registerFragmentLifecycleCallbacks(
            object : FragmentManager.FragmentLifecycleCallbacks() {
                override fun onFragmentViewCreated(manager: FragmentManager, fragment: Fragment, view: View, savedInstanceState: Bundle?) {
                    if (fragment is DialogFragment) return
                    // after the fragment has set up its lists
                    view.post { YtNavInsets.apply(view, YtChrome.navOverlapPx) }
                }
            },
            true,
        )
    }
}

/**
 * YT: makes room for the floating navigation bar in the classic screens: vertical lists get
 * padding at their end (and draw into it), floating action buttons move above the bar. Design
 * screens drawn in Compose make room themselves with [ytNavPadding].
 */
private object YtNavInsets {
    private val basePadding = WeakHashMap<View, Int>()
    private val baseMargin = WeakHashMap<View, Int>()

    fun apply(view: View, overlap: Int) {
        if (view.visibility == View.GONE) return
        when (view) {
            is AbstractComposeView -> Unit
            is RecyclerView -> if (view.layoutManager?.canScrollVertically() == true) padEnd(view, overlap)
            is NestedScrollView, is ScrollView -> padEnd(view as ViewGroup, overlap)
            is FloatingActionButton, is ExtendedFloatingActionButton -> lift(view, overlap)
            is ViewGroup -> view.children.forEach { apply(it, overlap) }
        }
    }

    private fun padEnd(view: ViewGroup, overlap: Int) {
        val base = basePadding.getOrPut(view) { view.paddingBottom }
        view.clipToPadding = false
        view.updatePadding(bottom = base + overlap)
    }

    private fun lift(view: View, overlap: Int) {
        val params = view.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        val base = baseMargin.getOrPut(view) { params.bottomMargin }
        view.updateLayoutParams<ViewGroup.MarginLayoutParams> { bottomMargin = base + overlap }
    }
}

/** Room for the floating navigation bar at the end of a design screen. */
@Composable
fun ytNavPadding(): Dp = with(LocalDensity.current) { YtChrome.navOverlapPx.toDp() }

private fun style(design: YtDesign, palette: YtPalette): YtNavStyle = when (design) {
    YtDesign.MOVO -> YtNavStyle(
        height = 68.dp, radius = 26.dp,
        container = Color(0xFF17171A), border = Color(0xFF2B2B31),
        active = Color.White, inactive = Color(0xFF8E8E97),
        font = YtFonts.geist, labelSize = 12.5.sp, activeWeight = FontWeight.SemiBold, weight = FontWeight.Medium,
        iconSize = 26.dp, underline = palette.accent, underlineHeight = 4.dp,
    )
    YtDesign.AFTER -> YtNavStyle(
        height = 54.dp, radius = 22.dp,
        container = Color(0xF2240E15), border = palette.text.copy(alpha = 0.08f),
        active = palette.text, inactive = Color(0xFFB9A79E),
        font = YtFonts.geist, labelSize = 13.sp, activeWeight = FontWeight.SemiBold, weight = FontWeight.Normal,
        iconSize = 0.dp, underline = palette.text, underlineHeight = 2.5.dp,
    )
    YtDesign.SIDE -> YtNavStyle(
        height = 66.dp, radius = 26.dp,
        container = palette.surface, border = Color(0xFF3F4043),
        active = palette.accent, inactive = palette.textSecondary,
        font = YtFonts.figtree, labelSize = 12.sp, activeWeight = FontWeight.Medium, weight = FontWeight.Medium,
        iconSize = 24.dp, underline = Color.Unspecified, underlineHeight = 0.dp,
    )
    else -> YtNavStyle(
        height = 64.dp, radius = 24.dp,
        container = Color(0xFF17171D), border = Color(0xFF2A2A33),
        active = palette.accent, inactive = Color(0xFFE6E6EC),
        font = YtFonts.geist, labelSize = 12.sp, activeWeight = FontWeight.Medium, weight = FontWeight.Medium,
        iconSize = 24.dp, underline = Color.Unspecified, underlineHeight = 0.dp,
    )
}

/**
 * The floating navigation bar: a rounded surface with even tabs, set off from the screen edges.
 * The active state animates (color, underline width), so further motion can build on it.
 */
@Composable
fun YtNavBar(
    design: YtDesign,
    palette: YtPalette,
    items: List<YtNavItem>,
    state: YtNavState,
    backdrop: Backdrop?,
    onSelect: (Int) -> Unit,
) {
    when (design) {
        YtDesign.CLAY -> ClayNav(palette, items, state, onSelect)
        YtDesign.EDITORIAL -> EditorialNav(palette, items, state, onSelect)
        YtDesign.GLASS -> if (backdrop != null) LiquidNav(design, palette, items, state, backdrop, onSelect) else GlassNav(palette, items, state, onSelect)
        YtDesign.POP -> PopNav(palette, items, state, onSelect)
        YtDesign.SHAPES -> ShapesNav(palette, items, state, onSelect)
        YtDesign.FROST -> if (backdrop != null) LiquidNav(design, palette, items, state, backdrop, onSelect) else FrostNav(palette, items, state, onSelect)
        else -> FloatingNav(design, palette, items, state, onSelect)
    }
}

@Composable
private fun FloatingNav(
    design: YtDesign,
    palette: YtPalette,
    items: List<YtNavItem>,
    state: YtNavState,
    onSelect: (Int) -> Unit,
) {
    val style = remember(design) { style(design, palette) }
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 10.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .height(style.height)
                .clip(RoundedCornerShape(style.radius))
                .background(style.container)
                .border(1.dp, style.border, RoundedCornerShape(style.radius)),
        ) {
            items.forEach { item ->
                NavTab(
                    design = design,
                    palette = palette,
                    style = style,
                    item = item,
                    active = state.selected == item.id,
                    badge = state.badges[item.id],
                    onClick = { onSelect(item.id) },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                )
            }
        }
    }
}

@Composable
private fun NavTab(
    design: YtDesign,
    palette: YtPalette,
    style: YtNavStyle,
    item: YtNavItem,
    active: Boolean,
    badge: Int?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val color by animateColorAsState(if (active) style.active else style.inactive, ytStateSpec(), label = "navColor")
    var labelWidth by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val underlineTarget = when {
        style.underline == Color.Unspecified || !active -> 0.dp
        design == YtDesign.MOVO -> 58.dp
        else -> with(density) { labelWidth.toDp() } + 10.dp
    }
    val underlineWidth by animateDpAsState(
        underlineTarget,
        animationSpec = ytSpec(spring(dampingRatio = if (design == YtDesign.MOVO) 0.5f else 0.75f, stiffness = 500f)),
        label = "navUnderline",
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = modifier.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = onClick,
        ),
    ) {
        if (style.iconSize > 0.dp) {
            Box(contentAlignment = Alignment.Center) {
                if (item.icon == null && design == YtDesign.MOVO) {
                    MovoMark(active, palette, Modifier.size(style.iconSize).ytSelectBounce(active))
                } else if (item.icon != null) {
                    Icon(
                        painterResource(if (active) item.activeIcon ?: item.icon else item.icon),
                        contentDescription = null,
                        tint = color,
                        modifier = Modifier
                            .size(style.iconSize)
                            .ytSelectBounce(active),
                    )
                }
                if (badge != null && badge > 0) {
                    Box(
                        Modifier
                            .align(Alignment.TopEnd)
                            .offset(x = 6.dp, y = (-2).dp)
                            .size(9.dp)
                            .clip(CircleShape)
                            .background(palette.accent),
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
        }
        Text(
            text = stringResource(item.label),
            color = color,
            maxLines = 1,
            style = TextStyle(
                fontFamily = style.font,
                fontWeight = if (active) style.activeWeight else style.weight,
                fontSize = style.labelSize,
            ),
            onTextLayout = { labelWidth = it.size.width },
        )
        if (style.underline != Color.Unspecified) {
            Spacer(Modifier.height(if (design == YtDesign.MOVO) 6.dp else 5.dp))
            Box(
                Modifier
                    .width(underlineWidth)
                    .height(style.underlineHeight)
                    .clip(RoundedCornerShape(50))
                    .background(style.underline),
            )
        }
        if (style.iconSize == 0.dp && badge != null && badge > 0) {
            Box(
                Modifier
                    .padding(top = 3.dp)
                    .size(5.dp)
                    .clip(CircleShape)
                    .background(palette.accent),
            )
        }
    }
}

/** Movo's mark: two overlapping rounded squares, cobalt and pink when active. */
@Composable
fun MovoMark(active: Boolean, palette: YtPalette, modifier: Modifier = Modifier) {
    val back by animateColorAsState(if (active) palette.accent else Color(0xFF6E6E78), ytStateSpec(), label = "markBack")
    val front by animateColorAsState(
        if (active) Color(0xFFB4AEFF) else Color(0xFF8E8E97),
        ytStateSpec(),
        label = "markFront",
    )
    Canvas(modifier) {
        val side = size.minDimension * 0.66f
        val corner = CornerRadius(side * 0.26f)
        drawRoundRect(back, Offset.Zero, Size(side, side), corner)
        drawRoundRect(front.copy(alpha = 0.92f), Offset(size.width - side, size.height - side), Size(side, side), corner)
    }
}
