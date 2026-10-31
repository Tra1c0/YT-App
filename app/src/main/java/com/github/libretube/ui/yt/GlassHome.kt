package com.github.libretube.ui.yt

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.remember
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.highlight.HighlightStyle
import com.kyant.backdrop.shadow.Shadow
import kotlin.math.roundToInt
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.Composable
import coil3.ImageLoader
import coil3.compose.AsyncImage
import com.github.libretube.R
import com.github.libretube.api.obj.StreamItem
import com.github.libretube.extensions.formatShort

private val GlassShadow = Color(0x405C8C85)

/** Frosted glass: translucent white with a bright edge and a soft shadow. */
fun Modifier.ytGlass(shape: Shape, strong: Boolean = false, shadow: Boolean = true): Modifier = this
    .then(if (shadow) Modifier.shadow(if (strong) 8.dp else 5.dp, shape, clip = false, ambientColor = GlassShadow, spotColor = GlassShadow) else Modifier)
    .clip(shape)
    .background(
        if (strong) Brush.verticalGradient(listOf(Color(0xF7FFFFFF), Color(0xE6FFFFFF)))
        else Brush.verticalGradient(listOf(Color(0x9EFFFFFF), Color(0x5CFFFFFF)))
    )
    .border(1.dp, Color(0xD9FFFFFF), shape)

/**
 * Design "Glas", home: search field, segmented tabs Für dich / Abos / Live, large videos with
 * a glass play button and a Shorts row. Measured in the reference, see docs/DESIGN-MASSE.md.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlassHomeScreen(
    palette: YtPalette,
    imageLoader: ImageLoader,
    data: YtHomeData,
    tab: String,
    onSelectTab: (String) -> Unit,
    actions: YtHomeActions,
) {
    val source = data.forTab(tab)
    val videos = (if (tab == YT_TAB_LIVE) source?.filter { it.url != null }?.distinctBy { it.url } else ytPlayable(source)).orEmpty()
    val shorts = data.shorts.orEmpty()
    val enter = rememberYtEnterState()
    // the screen below the floating glass: drifting light, mint and the list
    val screen = rememberLayerBackdrop()
    val chrome = 89.dp
    val searchPress = remember { MutableInteractionSource() }
    val searchSwell by rememberSwell(searchPress)

    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .layerBackdrop(screen)
                .background(Brush.verticalGradient(listOf(Color(0xFFCFE9E2), Color(0xFFDDF2EE), Color(0xFFD3ECE6)))),
        ) {
            LiquidLight(
                listOf(Color(0x99FFFFFF), Color(0x5C8FD8CB), Color(0x66FFFFFF)),
                Modifier.fillMaxSize(),
            )
            YtRefreshBox(
                palette = palette,
                isRefreshing = data.isLoading && source != null,
                onRefresh = actions.onRefresh,
                modifier = Modifier.fillMaxSize(),
                indicatorTop = chrome,
            ) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = chrome, bottom = 16.dp + ytNavPadding()),
                ) {
                    if (source == null || videos.isEmpty()) {
                        item {
                            Box(Modifier.fillMaxWidth().height(220.dp), contentAlignment = Alignment.Center) {
                                if (source == null || data.isLoading) CircularProgressIndicator(color = palette.text)
                                else Text(stringResource(R.string.yt_home_nothing), color = palette.textSecondary, modifier = Modifier.padding(32.dp))
                            }
                        }
                    }
                    itemsIndexed(videos.take(2), key = { _, video -> "top-" + video.url }) { index, video ->
                        GlassVideo(palette, imageLoader, video, index, actions, ytItemMotion().ytEnter(enter, tab + video.url, index))
                    }
                    if (shorts.isNotEmpty() && tab != YT_TAB_LIVE) {
                        item { GlassShorts(palette, imageLoader, shorts, actions) }
                    }
                    itemsIndexed(videos.drop(2), key = { _, video -> "more-" + video.url }) { index, video ->
                        GlassVideo(palette, imageLoader, video, index + 2, actions, ytItemMotion().ytEnter(enter, tab + video.url, index + 2))
                    }
                }
            }
        }

        // search and tabs float as glass over the list, which bends below them
        Column {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .padding(start = 18.dp, end = 18.dp, top = 4.dp)
                    .fillMaxWidth()
                    .height(38.dp)
                    .liquidGlass(screen, RoundedCornerShape(19.dp), Color(0x80FFFFFF), GlassShadow, swell = { searchSwell })
                    .clickable(interactionSource = searchPress, indication = null, onClick = actions.onSearch)
                    .padding(horizontal = 16.dp),
            ) {
                Icon(
                    painterResource(R.drawable.ic_yt_search),
                    contentDescription = null,
                    tint = palette.text,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = stringResource(R.string.yt_search_youtube),
                    color = palette.textSecondary,
                    style = TextStyle(fontFamily = YtFonts.googleSans, fontSize = 13.sp),
                    modifier = Modifier.padding(start = 15.dp),
                )
            }
            LiquidSegments(
                backdrop = screen,
                look = LiquidSegmentsLook(
                    height = 31.dp,
                    gap = 5.dp,
                    tile = Color(0x73FFFFFF),
                    pill = Color(0xF7FFFFFF),
                    shadow = GlassShadow,
                    text = palette.text,
                    lensText = Color(0xFF0A8F7F),
                    font = YtFonts.googleSans,
                    size = 13.5.sp,
                    activeSize = 14.sp,
                ),
                keys = listOf(YT_TAB_FOR_YOU, YT_TAB_SUBS, YT_TAB_LIVE),
                labels = listOf(
                    stringResource(R.string.yt_tab_for_you),
                    stringResource(R.string.yt_tab_subscriptions),
                    stringResource(R.string.yt_tab_live),
                ),
                // segment widths of the reference: 124 / 101 / 117 dp
                weights = listOf(1.23f, 1f, 1.16f),
                selected = tab,
                onSelect = onSelectTab,
                modifier = Modifier
                    .padding(start = 21.dp, end = 20.dp, top = 6.dp, bottom = 10.dp)
                    .fillMaxWidth()
                    .height(31.dp),
            )
        }
    }
}

@Composable
private fun GlassVideo(
    palette: YtPalette,
    imageLoader: ImageLoader,
    video: StreamItem,
    index: Int,
    actions: YtHomeActions,
    modifier: Modifier = Modifier,
) {
    val thumb = rememberLayerBackdrop()
    val press = remember { MutableInteractionSource() }
    val swell by rememberSwell(press)
    // a long press brings up a glass magnifier over the picture that follows the finger
    var magnifier by remember { mutableStateOf<Offset?>(null) }
    val haptics = rememberYtHaptics()
    val motion = LocalYtMotion.current
    val lensSize = 104.dp
    // left hides, right queues: a glass drop swells in the gap the video leaves
    Column(
        modifier
            .ytCardSwipe(
                palette, video, RoundedCornerShape(12.dp),
                inset = PaddingValues(start = 20.dp, end = 18.dp, bottom = 11.dp),
                testTag = "yt_card_$index",
            )
            .padding(start = 20.dp, end = 18.dp, bottom = 11.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(355f / 175f)
                .shadow(6.dp, RoundedCornerShape(12.dp), clip = false, ambientColor = GlassShadow, spotColor = GlassShadow)
                .clip(RoundedCornerShape(12.dp))
                .background(palette.lime)
                .clickable(interactionSource = press, indication = LocalIndication.current) { actions.onOpenVideo(video) }
                .pointerInput(motion) {
                    if (!motion) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val hold = awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
                        hold.consume()
                        haptics.tick()
                        magnifier = hold.position
                        drag(hold.id) { change ->
                            change.consume()
                            magnifier = change.position
                        }
                        magnifier = null
                    }
                },
        ) {
            YtLargeThumbnail(video, imageLoader, Modifier.fillMaxSize().layerBackdrop(thumb))
            // a glass drop on the picture: bends it at the rim and swells under the finger
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(56.dp)
                    .liquidGlass(thumb, CircleShape, Color(0x4DFFFFFF), GlassShadow, blur = 6.dp, refraction = 14.dp, swell = { swell }),
            ) {
                Icon(
                    painterResource(R.drawable.ic_yt_play_round),
                    contentDescription = null,
                    tint = palette.text,
                    modifier = Modifier
                        .padding(start = 3.dp)
                        .size(24.dp),
                )
            }
            (if (video.isLive) stringResource(R.string.yt_live_badge) else ytDuration(video))?.let {
                Box(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(8.dp)
                        .liquidGlass(
                            thumb, RoundedCornerShape(8.dp),
                            if (video.isLive) palette.live.copy(alpha = 0.85f) else Color(0x8C101C1E),
                            null, blur = 8.dp, refraction = 5.dp,
                        ),
                ) {
                    Text(
                        text = it,
                        color = Color.White,
                        style = TextStyle(fontFamily = YtFonts.googleSans, fontWeight = FontWeight.Medium, fontSize = 13.5.sp),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
            }
            magnifier?.let { at ->
                val half = with(LocalDensity.current) { lensSize.toPx() / 2f }
                Box(
                    Modifier
                        .offset { IntOffset((at.x - half).roundToInt(), (at.y - half).roundToInt()) }
                        .size(lensSize)
                        .drawBackdrop(
                            backdrop = thumb,
                            shape = { CircleShape },
                            effects = { lens(14.dp.toPx(), 26.dp.toPx(), chromaticAberration = true) },
                            highlight = { Highlight(style = HighlightStyle.Default(angle = 45f)) },
                            shadow = { Shadow(radius = 14.dp, color = GlassShadow) },
                            // the picture below is drawn at 1.6 x inside the circle
                            layerBlock = {
                                scaleX = 1.6f
                                scaleY = 1.6f
                            },
                            onDrawSurface = { drawRect(Color(0x12FFFFFF)) },
                        ),
                )
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = 6.dp),
        ) {
            AsyncImage(
                model = video.uploaderAvatar ?: video.thumbnail,
                contentDescription = null,
                imageLoader = imageLoader,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(palette.lime)
                    .clickable { actions.onOpenChannel(video) },
            )
            Column(
                Modifier
                    .weight(1f)
                    .padding(start = 16.dp)
                    .clickable { actions.onOpenVideo(video) },
            ) {
                Text(
                    text = video.title.orEmpty(),
                    color = palette.text,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(fontFamily = YtFonts.googleSans, fontWeight = FontWeight.Medium, fontSize = 15.5.sp, lineHeight = 19.sp),
                )
                Text(
                    text = listOf(video.uploaderName.orEmpty(), ytMeta(video, " · ")).filter { it.isNotBlank() }.joinToString("  ·  "),
                    color = palette.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(fontFamily = YtFonts.googleSans, fontSize = 12.sp),
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Icon(
                painterResource(R.drawable.ic_yt_dots_vertical),
                contentDescription = stringResource(R.string.yt_home_more_actions),
                tint = palette.text,
                modifier = Modifier
                    .size(22.dp)
                    .clickable { actions.onQuick(video) },
            )
        }
    }
}

@Composable
private fun GlassShorts(palette: YtPalette, imageLoader: ImageLoader, shorts: List<StreamItem>, actions: YtHomeActions) {
    Column(Modifier.padding(top = 4.dp, bottom = 12.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 21.dp, end = 18.dp, bottom = 10.dp),
        ) {
            YtShortsGlyph(palette.live, filled = true, modifier = Modifier.size(24.dp))
            Text(
                text = stringResource(R.string.yt_nav_shorts),
                color = palette.text,
                style = TextStyle(fontFamily = YtFonts.googleSans, fontWeight = FontWeight.Medium, fontSize = 18.sp),
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 12.dp),
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .height(26.dp)
                    .ytGlass(RoundedCornerShape(13.dp), shadow = false)
                    .clickable(onClick = actions.onShowShorts)
                    .padding(horizontal = 12.dp),
            ) {
                Text(
                    text = stringResource(R.string.yt_after_show_all),
                    color = palette.text,
                    style = TextStyle(fontFamily = YtFonts.googleSans, fontSize = 12.5.sp),
                )
                Icon(
                    painterResource(R.drawable.ic_yt_chevron_right),
                    contentDescription = null,
                    tint = palette.text,
                    modifier = Modifier
                        .padding(start = 6.dp)
                        .size(12.dp),
                )
            }
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            items(shorts, key = { "short-" + it.url }) { short ->
                val thumb = rememberLayerBackdrop()
                Box(
                    Modifier
                        .width(173.dp)
                        .aspectRatio(173f / 132f)
                        .shadow(5.dp, RoundedCornerShape(12.dp), clip = false, ambientColor = GlassShadow, spotColor = GlassShadow)
                        .clip(RoundedCornerShape(12.dp))
                        .background(palette.lime)
                        .clickable { actions.onOpenVideo(short) },
                ) {
                    YtLargeThumbnail(short, imageLoader, Modifier.fillMaxSize().layerBackdrop(thumb))
                    Icon(
                        painterResource(R.drawable.ic_yt_dots_vertical),
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp)
                            .size(18.dp),
                    )
                    short.views?.takeIf { it > 0 }?.let { views ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .padding(6.dp)
                                .liquidGlass(thumb, RoundedCornerShape(14.dp), Color(0x73101C1E), null, blur = 8.dp, refraction = 6.dp)
                                .padding(horizontal = 9.dp, vertical = 4.dp),
                        ) {
                            Icon(
                                painterResource(R.drawable.ic_yt_eye),
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(15.dp),
                            )
                            Text(
                                text = views.formatShort(),
                                color = Color.White,
                                style = TextStyle(fontFamily = YtFonts.googleSans, fontWeight = FontWeight.Medium, fontSize = 12.5.sp),
                                modifier = Modifier.padding(start = 6.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
