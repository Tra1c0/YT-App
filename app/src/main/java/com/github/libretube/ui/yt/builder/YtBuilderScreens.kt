package com.github.libretube.ui.yt.builder

import androidx.compose.foundation.background
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import com.github.libretube.R
import com.github.libretube.api.TrendingCategory
import com.github.libretube.api.obj.StreamItem
import com.github.libretube.extensions.toID
import com.github.libretube.helpers.YtDesign
import com.github.libretube.helpers.YtDesignDark
import com.github.libretube.ui.yt.Badge
import com.github.libretube.ui.yt.LocalYtMotion
import com.github.libretube.ui.yt.YT_FILTER_ALL
import com.github.libretube.ui.yt.YtHomeActions
import com.github.libretube.ui.yt.YtHomeData
import com.github.libretube.ui.yt.YtMiniPlayer
import com.github.libretube.ui.yt.YtNavItem
import com.github.libretube.ui.yt.YtNavState
import com.github.libretube.ui.yt.YtPalette
import com.github.libretube.ui.yt.YtRefreshBox
import com.github.libretube.ui.yt.YtSeenStore
import com.github.libretube.ui.yt.YtTuneCircle
import com.github.libretube.ui.yt.mini.ytDockedBar
import com.github.libretube.ui.yt.ytCardSwipe
import com.github.libretube.ui.yt.ytHomeCard
import com.github.libretube.ui.yt.ytNavHoldTarget
import com.github.libretube.ui.yt.ytNavPadding
import com.github.libretube.ui.yt.ytPlayable

/*
 * YT: the screens of the design "Eigenes", drawn from the active theme document: the home feed
 * (cards as list or grid by width class, the category chips), the header and the bar. The cards
 * keep every gesture of the other designs (swipe, hold and drag onto the mini player, quick
 * actions on release).
 */

/** The theme source of the app: the active theme in the current window and look. */
@Composable
fun rememberYtAppThemeSource(): YtThemeSource {
    val configuration = LocalConfiguration.current
    val env = rememberUpdatedState(YtEnv(configuration.screenWidthDp.toFloat(), configuration.screenHeightDp.toFloat(), configuration.fontScale, YtDesignDark.active))
    return remember {
        // loaded before any screen composes (the activity's palette); the source only reads
        YtBuilderActive.current
        YtThemeSource(
            docOf = { YtBuilderActive.doc },
            envOf = { env.value },
            nightOf = { env.value.dark },
            assetsOf = { YtBuilderActive.assets },
        )
    }
}

/**
 * A press that gives a little in the theme's motion feel (a spring), read in the layer: to
 * [pressScale] (the root's pressed patch, else the motion token; YtThemeSource.pressScale).
 */
@Composable
fun Modifier.ytThemePress(interaction: MutableInteractionSource, motion: YtMotionToken, pressScale: Float = motion.pressScale): Modifier {
    if (!LocalYtMotion.current) return this
    val scale = remember { Animatable(1f) }
    LaunchedEffect(interaction, motion, pressScale) {
        val spec = spring<Float>(dampingRatio = motion.damping.coerceIn(0.2f, 1.5f), stiffness = motion.stiffness.coerceIn(50f, 5000f))
        interaction.interactions.collect { i ->
            when (i) {
                is PressInteraction.Press -> scale.animateTo(pressScale, spec)
                is PressInteraction.Release, is PressInteraction.Cancel -> scale.animateTo(1f, spec)
            }
        }
    }
    return graphicsLayer {
        scaleX = scale.value
        scaleY = scale.value
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun YtCustomHomeScreen(
    palette: YtPalette,
    imageLoader: ImageLoader,
    data: YtHomeData,
    categories: List<TrendingCategory>,
    category: String,
    onSelectCategory: (String) -> Unit,
    categoryVideos: List<StreamItem>?,
    actions: YtHomeActions,
) {
    val src = rememberYtAppThemeSource()
    val doc = YtBuilderActive.doc
    val feed = doc?.feed ?: YtFeedLayout()
    val columns = feed.columns(src.env.widthClass).coerceIn(1, YtThemeValidate.MAX_COLUMNS)
    val all = category == YT_FILTER_ALL
    val videos = ytPlayable(if (all) data.forYou else categoryVideos).orEmpty()
    val loading = if (all) data.isLoading else categoryVideos == null
    val positions by YtSeenStore.positions.collectAsState()
    val cardShape = remember(doc?.components?.card?.style?.corners) { YtCornerShape(doc?.components?.card?.style?.corners ?: YtCorners()) }
    val motion = doc?.tokens?.motion ?: YtMotionToken()
    val grid = rememberLazyGridState()
    val feedScroll = rememberYtAnimFeedScroll(grid, loading)
    // the cards' appear/disappear: half of the card in the visible part of the feed
    val needVisibility = remember(doc?.animations) {
        doc?.animations?.of(YtComponentKind.CARD)?.any { it.trigger.kind == YtTriggerKind.APPEAR || it.trigger.kind == YtTriggerKind.DISAPPEAR } == true
    }
    val shown = remember { mutableStateOf<Set<Any>>(emptySet()) }
    if (needVisibility) {
        LaunchedEffect(grid) {
            snapshotFlow { ytHalfVisible(grid) }.distinctUntilChanged().collect { shown.value = it }
        }
    }
    // the animator may own the press (then no plain feel), else the pressed patch's scale or the token
    val pressScale = src.pressScale(YtComponentKind.CARD)

    YtRefreshBox(
        palette = palette,
        isRefreshing = data.isLoading && data.forYou != null,
        onRefresh = actions.onRefresh,
        // opaque like every YT home: the classic screen stays underneath (Pitfall P14)
        modifier = Modifier.fillMaxSize().background(palette.background),
    ) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            state = grid,
            modifier = Modifier.fillMaxSize().nestedScroll(feedScroll),
            contentPadding = PaddingValues(
                start = feed.padding.start.dp, end = feed.padding.end.dp,
                top = feed.padding.top.dp, bottom = feed.padding.bottom.dp + ytNavPadding(),
            ),
            horizontalArrangement = Arrangement.spacedBy(feed.gap.dp),
            verticalArrangement = Arrangement.spacedBy(feed.rowGap.dp),
        ) {
            if (feed.chips) {
                item(key = "chips", span = { GridItemSpan(maxLineSpan) }, contentType = "chips") {
                    YtCustomChips(src, palette, categories, category, onSelectCategory)
                }
            }
            if (videos.isEmpty()) {
                item(key = "empty", span = { GridItemSpan(maxLineSpan) }, contentType = "empty") {
                    Box(Modifier.fillMaxWidth().height(180.dp), contentAlignment = Alignment.Center) {
                        if (loading) {
                            CircularProgressIndicator(color = palette.accent)
                        } else {
                            BasicText(stringResource(R.string.yt_home_nothing), style = TextStyle(color = palette.textSecondary))
                        }
                    }
                }
            }
            items(videos, key = { "v-" + it.url }, contentType = { "card" }) { video ->
                val press = remember { MutableInteractionSource() }
                val id = remember(video.url) { video.url?.toID() }
                val slots = remember(video, imageLoader, palette) {
                    YtSlotData(
                        video = video,
                        progress = {
                            val pos = id?.let { positions[it] } ?: 0L
                            val duration = (video.duration ?: 0L) * 1000L
                            if (duration > 0) pos.toFloat() / duration else 0f
                        },
                        imageLoader = imageLoader,
                        palette = palette,
                    )
                }
                val itemKey = "v-" + video.url
                val input = remember(itemKey, needVisibility) { if (needVisibility) YtAnimInput(visible = { itemKey in shown.value }) else YtAnimInput.NONE }
                YtThemeComponent(
                    src, YtComponentKind.CARD, slots,
                    modifier = Modifier
                        .ytCardSwipe(palette, video, cardShape)
                        // held: lifts and drags onto the mini player; let go unmoved: quick actions
                        .ytHomeCard(palette, video, cardShape, onRelease = { actions.onQuick(video) })
                        // an animated press (theme animator) replaces the plain press feel
                        .then(if (pressScale == null) Modifier else Modifier.ytThemePress(press, motion, pressScale))
                        .clickable(interactionSource = press, indication = null) { actions.onOpenVideo(video) },
                    input = input,
                )
            }
        }
    }
}

@Composable
private fun YtCustomChips(src: YtThemeSource, palette: YtPalette, categories: List<TrendingCategory>, selected: String, onSelect: (String) -> Unit) {
    val filters = listOf(YT_FILTER_ALL to stringResource(R.string.all)) + categories.map { it.name to stringResource(it.titleRes) }
    val motion = YtBuilderActive.doc?.tokens?.motion ?: YtMotionToken()
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        item(key = "tune") {
            YtTuneCircle(fill = palette.raised, ink = palette.text, activeFill = palette.accent, activeInk = palette.onAccent)
        }
        items(filters, key = { it.first }) { (key, label) ->
            val press = remember { MutableInteractionSource() }
            val pressScale = src.pressScale(YtComponentKind.CHIP)
            YtThemeComponent(
                src, YtComponentKind.CHIP, remember(label) { YtSlotData(label = label) },
                modifier = Modifier
                    .then(if (pressScale == null) Modifier else Modifier.ytThemePress(press, motion, pressScale))
                    .clickable(interactionSource = press, indication = null) { onSelect(key) },
                states = if (key == selected) YtRenderStates.SELECTED else 0,
            )
        }
    }
}

/** The header row of "Eigenes" in the toolbar. */
@Composable
fun YtCustomHeader(onSearch: () -> Unit, onProfile: () -> Unit) {
    val src = rememberYtAppThemeSource()
    val wordmark = YtBuilderActive.doc?.meta?.wordmark.orEmpty()
    YtThemeComponent(
        src, YtComponentKind.HEADER,
        remember(wordmark, onSearch, onProfile) { YtSlotData(wordmark = wordmark, onSearch = onSearch, onProfile = onProfile) },
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * The bar of "Eigenes": the bar component's surface (its first fill, outline and shadow) shaped
 * so that a docked mini player sits on it as one surface (ytDockedBar), the bar component's own
 * nodes on it, and one tab component per destination, the selected one in its "selected" state.
 * The bar's animations run here: the root's move, scale, turn and opacity move the whole bar,
 * its fill and outline colour tint the surface, the nodes inside animate as anywhere else.
 */
@Composable
fun YtCustomNav(
    palette: YtPalette,
    items: List<YtNavItem>,
    state: YtNavState,
    onSelect: (Int) -> Unit,
    src: YtThemeSource = rememberYtAppThemeSource(),
    /** The editor: the source of tab [index] (only the first reports its bounds) and the bar's bounds. */
    tabSource: (Int) -> YtThemeSource = { src },
    onBarPlaced: ((androidx.compose.ui.layout.LayoutCoordinates) -> Unit)? = null,
    /** The editor: the state look shown on tab [index] (the state being edited), as render bits. */
    tabStates: (Int) -> Int = { 0 },
) {
    val doc = src.doc
    val nav = doc?.nav ?: YtNavConfig()
    val stacks = remember { YtMiniPlayer.stacks(YtDesign.CUSTOM) }
    val bar = doc?.components?.navBar ?: YtThemeSeeds.navBar()
    val t = src.resolver
    val fill = remember(bar.style, t) {
        bar.style.fills.firstOrNull { it.enabled }?.let { p -> t?.let { YtBuilderPaint.color(it.resolve(p)) } } ?: palette.surface
    }
    val stroke = bar.style.strokes.firstOrNull { it.enabled }
    val border = remember(stroke, t) { stroke?.let { s -> t?.let { YtBuilderPaint.color(it.resolve(s.paint)) } } }
    val shadow = bar.style.shadows.firstOrNull { it.enabled && !it.inner }
    val motion = doc?.tokens?.motion ?: YtMotionToken()
    val floating = nav.floating
    // the bar's own animations: one instance, its root drawn by the surface here
    val barAnim = rememberYtAnimInstance(src, YtComponentKind.NAV_BAR, 0, YtAnimInput.NONE)
    val rootAnim = remember(barAnim, bar.id, barAnim?.indexVersion) { barAnim?.nodeAnim(bar.id) }
    val fillOf = rootAnim?.takeIf { YtAnimProp.FILL_COLOR in it.props }?.let { a -> { a.inst.frame.intValue; Color(a.node.argb(YtAnimProp.FILL_COLOR, fill.toArgb())) } }
    val borderOf = if (border != null) rootAnim?.takeIf { YtAnimProp.STROKE_COLOR in it.props }?.let { a -> { a.inst.frame.intValue; Color(a.node.argb(YtAnimProp.STROKE_COLOR, border.toArgb())) } } else null
    val barTouch = barAnim != null && src.animMode == YtAnimMode.APP && src.animIndex(YtComponentKind.NAV_BAR)?.touch == true
    val tabPress = src.pressScale(YtComponentKind.NAV_TAB)
    Box(
        Modifier
            .fillMaxWidth()
            .padding(
                start = if (floating) nav.side.dp else 0.dp, end = if (floating) nav.side.dp else 0.dp,
                top = if (floating) 6.dp else 0.dp, bottom = if (floating) nav.bottom.dp else 0.dp,
            ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(nav.height.dp)
                .then(if (rootAnim != null && rootAnim.layer) Modifier.ytAnimHostLayer(rootAnim) else Modifier)
                .then(if (barTouch && barAnim != null) Modifier.ytAnimTouch(barAnim) else Modifier)
                .then(if (barAnim != null && src.animMode == YtAnimMode.APP) Modifier.ytAnimShown(barAnim) else Modifier)
                .ytDockedBar(
                    if (floating) nav.radius.dp else 0.dp, stacks, SolidColor(fill), border, (stroke?.width ?: 1f).dp,
                    shadow = ((shadow?.blur ?: 0f) / 2f).coerceIn(0f, 24f).dp, shadowColor = shadow?.let { Color(it.color) } ?: Color.Black,
                    fillOf = fillOf, borderOf = borderOf,
                )
                .then(if (onBarPlaced != null) Modifier.onPlaced(onBarPlaced) else Modifier),
        ) {
            // the bar component's nodes on the surface (the root's style is the surface itself)
            if (bar.children.isNotEmpty()) {
                YtThemeComponent(src, YtComponentKind.NAV_BAR, YtSlotData(), Modifier.matchParentSize(), hostAnim = barAnim, bareRoot = true)
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = bar.padding.start.dp, end = bar.padding.end.dp, top = bar.padding.top.dp, bottom = bar.padding.bottom.dp),
            ) {
                items.forEachIndexed { index, item ->
                    val press = remember { MutableInteractionSource() }
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .then(if (tabPress == null) Modifier else Modifier.ytThemePress(press, motion, tabPress))
                            .clickable(interactionSource = press, indication = null) { onSelect(item.id) }
                            .ytNavHoldTarget(item.id),
                    ) {
                        YtThemeComponent(
                            tabSource(index), YtComponentKind.NAV_TAB, remember(item) { YtSlotData(navItem = item) },
                            modifier = Modifier.fillMaxSize(),
                            states = (if (item.id == state.selected) YtRenderStates.SELECTED else 0) or tabStates(index),
                        )
                        Badge(palette, (state.badges[item.id] ?: 0) > 0, Modifier.align(Alignment.TopCenter).offset(12.dp, 8.dp))
                    }
                }
            }
        }
    }
}

/** Reports the feed's scroll (as its nested scroll parent) and loading to the animator (YtAnimSignals). */
@Composable
private fun rememberYtAnimFeedScroll(grid: LazyGridState, loading: Boolean): NestedScrollConnection {
    val density = LocalDensity.current.density
    LaunchedEffect(loading) { YtAnimSignals.feedLoading = loading }
    return remember(grid, density) {
        object : NestedScrollConnection {
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (consumed.y != 0f) YtAnimSignals.scrolled(-consumed.y / density, !grid.canScrollBackward)
                return Offset.Zero
            }
        }
    }
}

/** The keys of the feed items of which at least half is in the visible part. */
private fun ytHalfVisible(grid: LazyGridState): Set<Any> {
    val info = grid.layoutInfo
    val top = info.viewportStartOffset
    val bottom = info.viewportEndOffset
    return info.visibleItemsInfo.filter {
        val h = it.size.height
        val seen = minOf(bottom, it.offset.y + h) - maxOf(top, it.offset.y)
        h > 0 && seen * 2 >= h
    }.mapTo(HashSet()) { it.key }
}
