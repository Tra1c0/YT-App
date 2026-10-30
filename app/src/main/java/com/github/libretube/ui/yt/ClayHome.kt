package com.github.libretube.ui.yt

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import coil3.ImageLoader
import coil3.compose.AsyncImage
import com.github.libretube.R
import com.github.libretube.api.obj.StreamItem
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.absoluteValue
import kotlin.math.sin

private val ClayMauve = Color(0xFF9D6A6C)
private val ClayCream = Color(0xFFF7E8DA)
private val ClayDot = Color(0xFFDDB9A6)
private val ClayLabel = Color(0xFF8C6563)
private val ClayCardShadow = Color(0x80B07A66)

/**
 * Design "Clay", home: "Heute für dich" with a carousel of large soft cards (the neighbours
 * lean behind, arrows on both sides, a big cream play button), page dots and "Als Nächstes"
 * as a puffy card. Measured in the reference, see docs/DESIGN-MASSE.md.
 */
@Composable
fun ClayHomeScreen(
    palette: YtPalette,
    imageLoader: ImageLoader,
    data: YtHomeData,
    actions: YtHomeActions,
) {
    val videos = ytPlayable(data.forYou).orEmpty().take(8)
    val pager = rememberPagerState { videos.size }
    val scope = rememberCoroutineScope()
    val motion = LocalYtMotion.current

    Column(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFFF6DACA), Color(0xFFEFCDB9))))
            .verticalScroll(rememberScrollState())
            .padding(bottom = ytNavPadding()),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 19.dp, top = 10.dp),
        ) {
            Canvas(Modifier.size(12.dp, 15.dp)) {
                rotate(-35f) {
                    drawRoundRect(Color(0xFFF2A386), Offset(size.width * 0.15f, 0f), Size(size.width * 0.28f, size.height * 0.55f), CornerRadius(8f))
                }
                rotate(-60f) {
                    drawRoundRect(Color(0xFFF7C1A6), Offset(size.width * 0.05f, size.height * 0.55f), Size(size.width * 0.26f, size.height * 0.42f), CornerRadius(8f))
                }
            }
            Text(
                text = stringResource(R.string.yt_clay_today),
                color = Color(0xFF7A5560),
                style = TextStyle(fontFamily = YtFonts.nunito, fontWeight = FontWeight.ExtraBold, fontSize = 17.sp),
                modifier = Modifier.padding(start = 10.dp),
            )
        }

        Box(
            Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
                .height(326.dp),
        ) {
            if (videos.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    if (data.isLoading) CircularProgressIndicator(color = ClayMauve)
                    else Text(stringResource(R.string.yt_home_nothing), color = palette.textSecondary, modifier = Modifier.padding(32.dp))
                }
            } else {
                HorizontalPager(
                    state = pager,
                    contentPadding = PaddingValues(horizontal = 59.dp),
                    beyondViewportPageCount = 1,
                    modifier = Modifier.fillMaxSize(),
                ) { page ->
                    val offset = (pager.currentPage - page) + pager.currentPageOffsetFraction
                    val distance = offset.absoluteValue.coerceAtMost(1f)
                    ClayCard(
                        palette = palette,
                        imageLoader = imageLoader,
                        video = videos[page],
                        onOpen = actions.onOpenVideo,
                        modifier = Modifier
                            .zIndex(1f - distance)
                            // the card that lands in front wobbles like soft clay
                            .ytJelly(pager.settledPage == page, amount = 0.05f)
                            .graphicsLayer {
                                val scale = 1f - 0.12f * distance
                                // pulled between two places, the card stretches like dough
                                val pull = if (motion) sin(PI.toFloat() * distance) else 0f
                                scaleX = scale * (1f + 0.05f * pull)
                                scaleY = scale * (1f - 0.04f * pull)
                                rotationZ = offset.coerceIn(-1f, 1f) * 3f
                                translationX = offset.coerceIn(-1f, 1f) * 30.dp.toPx()
                            },
                    )
                }
                ClayRoundButton(
                    icon = R.drawable.ic_yt_chevron_left,
                    description = R.string.yt_previous,
                    size = 44,
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .offset(x = 21.dp)
                        .zIndex(3f),
                ) { scope.launch { pager.ytScrollTo((pager.currentPage - 1).coerceAtLeast(0), motion) } }
                ClayRoundButton(
                    icon = R.drawable.ic_yt_chevron_right,
                    description = R.string.yt_next,
                    size = 44,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .offset(x = (-22).dp)
                        .zIndex(3f),
                ) { scope.launch { pager.ytScrollTo((pager.currentPage + 1).coerceAtMost(videos.lastIndex), motion) } }
            }
        }

        if (videos.size > 1) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(top = 14.dp),
            ) {
                repeat(videos.size.coerceAtMost(6)) { i ->
                    val active = pager.currentPage == i
                    val color by animateColorAsState(if (active) ClayMauve else ClayDot, ytStateSpec(), label = "clayDot")
                    val width by animateDpAsState(if (active) 12.dp else 10.dp, ytStateSpec(), label = "clayDotSize")
                    Box(
                        Modifier
                            .size(width)
                            .shadow(2.dp, CircleShape)
                            .clip(CircleShape)
                            .background(color),
                    )
                }
            }
        }

        videos.getOrNull(pager.currentPage + 1)?.let { next ->
            val fadeInSpec = ytFinite(tween<Float>(260))
            val slideSpec = ytFinite(spring<IntOffset>(dampingRatio = 0.7f))
            val fadeOutSpec = ytFinite(tween<Float>(160))
            AnimatedContent(
                targetState = next,
                transitionSpec = {
                    (fadeIn(fadeInSpec) + slideInHorizontally(slideSpec) { it / 6 }) togetherWith fadeOut(fadeOutSpec)
                },
                label = "clayNext",
            ) { video -> ClayNextCard(palette, imageLoader, video, actions) }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun ClayCard(
    palette: YtPalette,
    imageLoader: ImageLoader,
    video: StreamItem,
    onOpen: (StreamItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(28.dp)
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier
            .padding(vertical = 5.dp)
            .fillMaxSize()
            .ytSquish(interaction)
            .shadow(14.dp, shape, ambientColor = ClayCardShadow, spotColor = ClayCardShadow)
            .clip(shape)
            .background(Color(0xFF7A5A4C))
            .clickable(interactionSource = interaction, indication = LocalIndication.current) { onOpen(video) },
    ) {
        YtLargeThumbnail(video, imageLoader, Modifier.fillMaxSize())
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(0.55f to Color.Transparent, 1f to Color(0xE65A4034))),
        )
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .padding(start = 18.dp, end = 14.dp, bottom = 10.dp),
        ) {
            // the play button sits right above the title, as in the reference
            ClayPlayButton(
                Modifier
                    .align(Alignment.End)
                    .padding(bottom = 4.dp),
            ) { onOpen(video) }
            Text(
                text = video.title.orEmpty(),
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(fontFamily = YtFonts.nunito, fontWeight = FontWeight.Black, fontSize = 20.5.sp, lineHeight = 24.sp),
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 8.dp),
            ) {
                AsyncImage(
                    model = video.uploaderAvatar,
                    contentDescription = null,
                    imageLoader = imageLoader,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .border(2.dp, Color.White, CircleShape)
                        .background(palette.lime),
                )
                Column(
                    Modifier
                        .weight(1f)
                        .padding(start = 9.dp),
                ) {
                    Text(
                        text = video.uploaderName.orEmpty(),
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = TextStyle(fontFamily = YtFonts.nunito, fontWeight = FontWeight.ExtraBold, fontSize = 12.sp),
                    )
                    Text(
                        text = ytViews(video).orEmpty(),
                        color = Color.White.copy(alpha = 0.82f),
                        maxLines = 1,
                        style = TextStyle(fontFamily = YtFonts.nunito, fontWeight = FontWeight.SemiBold, fontSize = 12.sp),
                    )
                }
                (if (video.isLive) stringResource(R.string.yt_live_badge) else ytDuration(video))?.let {
                    Text(
                        text = it,
                        color = Color.White,
                        style = TextStyle(fontFamily = YtFonts.nunito, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp),
                        modifier = Modifier
                            .clip(RoundedCornerShape(11.dp))
                            .background(Color(0xB33A2A2A))
                            .padding(horizontal = 9.dp, vertical = 3.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ClayPlayButton(modifier: Modifier = Modifier, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val breath = rememberBreath()
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(72.dp)
            .graphicsLayer {
                scaleX = breath
                scaleY = breath
            }
            .ytSquish(interaction)
            .shadow(10.dp, CircleShape, ambientColor = ClayCardShadow, spotColor = ClayCardShadow)
            .clip(CircleShape)
            .background(Brush.verticalGradient(listOf(Color(0xFFFFF5EC), Color(0xFFF1D9C6))))
            .border(1.dp, Color(0x99FFFFFF), CircleShape)
            .clickable(interactionSource = interaction, indication = LocalIndication.current, onClick = onClick),
    ) {
        Icon(
            painterResource(R.drawable.ic_yt_play_round),
            contentDescription = null,
            tint = Color(0xFF93636A),
            modifier = Modifier
                .padding(start = 4.dp)
                .size(30.dp),
        )
    }
}

@Composable
private fun ClayRoundButton(
    icon: Int,
    description: Int,
    size: Int,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(size.dp)
            .ytSquish(interaction)
            .shadow(8.dp, CircleShape, ambientColor = ClayCardShadow, spotColor = ClayCardShadow)
            .clip(CircleShape)
            .background(Brush.verticalGradient(listOf(Color(0xFFFFF3E9), Color(0xFFF2D9C6))))
            .border(1.dp, Color(0x99FFFFFF), CircleShape)
            .clickable(interactionSource = interaction, indication = LocalIndication.current, onClick = onClick),
    ) {
        Icon(
            painterResource(icon),
            contentDescription = stringResource(description),
            tint = Color(0xFF7A5560),
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun ClayNextCard(
    palette: YtPalette,
    imageLoader: ImageLoader,
    video: StreamItem,
    actions: YtHomeActions,
) {
    val shape = RoundedCornerShape(22.dp)
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier
            .padding(start = 15.dp, end = 18.dp, top = 15.dp)
            .fillMaxWidth()
            .height(111.dp)
            .ytSquish(interaction)
            .shadow(10.dp, shape, ambientColor = ClayCardShadow, spotColor = ClayCardShadow)
            .clip(shape)
            .background(Brush.verticalGradient(listOf(Color(0xFFFAEEE3), ClayCream)))
            .border(1.dp, Color(0x80FFFFFF), shape)
            .clickable(interactionSource = interaction, indication = LocalIndication.current) { actions.onOpenVideo(video) }
            .padding(start = 15.dp, top = 11.dp, bottom = 11.dp, end = 8.dp),
    ) {
        Box(
            Modifier
                .width(152.dp)
                .height(89.dp),
        ) {
            YtLargeThumbnail(
                video,
                imageLoader,
                Modifier
                    .width(152.dp)
                    .height(89.dp)
                    .clip(RoundedCornerShape(14.dp)),
            )
            ytDuration(video)?.let {
                Text(
                    text = it,
                    color = Color.White,
                    style = TextStyle(fontFamily = YtFonts.nunito, fontWeight = FontWeight.ExtraBold, fontSize = 12.sp),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .background(Color(0xB33A2A2A))
                        .padding(horizontal = 7.dp, vertical = 2.dp),
                )
            }
        }
        Column(
            Modifier
                .weight(1f)
                .padding(start = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    text = stringResource(R.string.yt_up_next),
                    color = ClayLabel,
                    style = TextStyle(fontFamily = YtFonts.nunito, fontWeight = FontWeight.ExtraBold, fontSize = 11.sp),
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    painterResource(R.drawable.ic_yt_dots_vertical),
                    contentDescription = null,
                    tint = palette.text,
                    modifier = Modifier
                        .size(18.dp)
                        .clickable { actions.onSave(video) },
                )
            }
            Text(
                text = video.title.orEmpty(),
                color = palette.text,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(fontFamily = YtFonts.nunito, fontWeight = FontWeight.Black, fontSize = 13.sp, lineHeight = 16.sp),
                modifier = Modifier.padding(top = 2.dp, end = 4.dp),
            )
            Spacer(Modifier.weight(1f))
            Row(verticalAlignment = Alignment.CenterVertically) {
                AsyncImage(
                    model = video.uploaderAvatar,
                    contentDescription = null,
                    imageLoader = imageLoader,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(palette.lime)
                        .clickable { actions.onOpenChannel(video) },
                )
                Column(Modifier.padding(start = 7.dp)) {
                    Text(
                        text = video.uploaderName.orEmpty(),
                        color = palette.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = TextStyle(fontFamily = YtFonts.nunito, fontWeight = FontWeight.ExtraBold, fontSize = 11.5.sp),
                    )
                    Text(
                        text = ytViews(video).orEmpty(),
                        color = palette.textSecondary,
                        maxLines = 1,
                        style = TextStyle(fontFamily = YtFonts.nunito, fontWeight = FontWeight.SemiBold, fontSize = 11.sp),
                    )
                }
            }
        }
    }
}

/** Scrolls the pager with its spring, or jumps when motion is off. */
private suspend fun PagerState.ytScrollTo(page: Int, motion: Boolean) {
    if (motion) animateScrollToPage(page, animationSpec = spring(dampingRatio = 0.7f, stiffness = 300f)) else scrollToPage(page)
}
