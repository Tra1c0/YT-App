package com.github.libretube.ui.yt

import android.view.ViewGroup
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.children
import androidx.core.view.isGone
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import coil3.ImageLoader
import coil3.compose.AsyncImage
import com.github.libretube.R
import com.github.libretube.api.SubscriptionHelper
import com.github.libretube.api.obj.StreamItem
import com.github.libretube.extensions.formatShort
import com.github.libretube.extensions.toID
import com.github.libretube.helpers.ImageHelper
import com.github.libretube.helpers.NavigationHelper
import com.github.libretube.helpers.YtDesign
import com.github.libretube.parcelable.PlayerData
import com.github.libretube.repo.FeedRefresh
import com.github.libretube.ui.models.SubscriptionsViewModel
import com.github.libretube.ui.yt.shorts.YtShortsView
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * YT: the Shorts tab of the designs that have one (it takes the place of the trends tab).
 * YT has no Shorts feed of its own, so the tab shows the Shorts from your subscriptions.
 *
 * Each design lays the grid out in its own material: Clay puffy frames that squish; Editorial
 * three columns of black-and-white stills that take color under the finger, numbered in mono;
 * Glas cards with a caption of glass that bends the still below; Pop colored frames, each a
 * little askew, that wobble; Formen alternating widget shapes on the night gradient; Frost
 * frosted cards on sage that thaw.
 */
object YtShorts {
    /** Shorts of a feed, newest first. */
    fun of(feed: List<StreamItem>?): List<StreamItem>? =
        feed?.filter { it.isShort && it.url != null }?.distinctBy { it.url }

    /** Makes sure the subscription feed is loaded; Home and Shorts read it. */
    fun ensureFeed(fragment: Fragment, viewModel: SubscriptionsViewModel) {
        if (viewModel.videoFeed.value != null) return
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            val feed = withContext(Dispatchers.IO) {
                runCatching { SubscriptionHelper.getFeed(FeedRefresh.Automatically) }.getOrNull()
            }
            if (feed != null) viewModel.videoFeed.value = feed
        }
    }

    /** @return true if the Shorts screen replaces the classic trends layout. */
    fun attach(fragment: Fragment, root: ViewGroup): Boolean {
        val design = YtDesign.current()
        if (!design.hasShortsTab) return false
        val palette = design.palette ?: return false
        val viewModel = ViewModelProvider(fragment.requireActivity())[SubscriptionsViewModel::class.java]
        ensureFeed(fragment, viewModel)

        root.children.forEach { it.isGone = true }
        val composeView = ComposeView(fragment.requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                // hidden videos and paused channels stay off the Shorts too (rememberYtVisible)
                val feed = rememberYtVisible(viewModel.videoFeed.observeAsState().value)
                val shorts = of(feed)
                YtTheme(palette) {
                    // a long press keeps the old way: the short in the video player
                    CompositionLocalProvider(
                        LocalShortLongPress provides { video -> YtShortsView.openInPlayer(context, video.url!!.toID()) },
                    ) {
                        YtShortsScreen(palette, ImageHelper.loader(), shorts) { video ->
                            // the Shorts view, from the tapped short on through the whole tab
                            if (!YtShortsView.open(context, shorts.orEmpty(), video)) {
                                NavigationHelper.navigateVideo(context, PlayerData(video.url!!.toID()))
                            }
                        }
                    }
                }
            }
        }
        root.addView(
            composeView,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        return true
    }
}

/** What a long press on a short does (open it in the video player). */
private val LocalShortLongPress = staticCompositionLocalOf<(StreamItem) -> Unit> { {} }

private val ShapesNight = listOf(Color(0xFF12122A), Color(0xFF1C1A45), Color(0xFF3F3489), Color(0xFF6656C4))
private val ShapesTags = listOf(Color(0xFFF6D365), Color(0xFFBFE26B), Color(0xFFD9D4EE), Color(0xFFF28B82))
private val PopFrames = listOf(Color(0xFFFDE682), Color(0xFFF7B3CE), Color(0xFFDFD6F7), Color(0xFFBFE8D2))
private val PopInk = Color(0xFF3B2054)
private val ClayShadow: Color get() = ytDayNight(Color(0x80B07A66), Color(0x80000000))
private val ClayInk: Color get() = ytDayNight(Color(0xFF7A5560), Color(0xFFE8C6C2))
private val ClayLabel: Color get() = ytDayNight(Color(0xFF8C6563), Color(0xFFC9AEA2))
private val ClayLight: Color get() = ytDayNight(Color(0xFFFFF3E9), Color(0xFF4A3830))

@Composable
private fun YtShortsScreen(
    palette: YtPalette,
    imageLoader: ImageLoader,
    shorts: List<StreamItem>?,
    onOpen: (StreamItem) -> Unit,
) {
    val enter = rememberYtEnterState()
    val signature = palette.signature
    val glass = signature == YtSignature.GLASS || signature == YtSignature.FROST
    val backdrop = if (glass) rememberLayerBackdrop() else null
    val columns = if (signature == YtSignature.EDITORIAL) 3 else 2
    if (signature == YtSignature.FREQUENCY) FrequencyPulseHost()
    if (signature == YtSignature.SYNTHWAVE) SynthwaveSceneHost()
    val gap = when (signature) {
        YtSignature.EDITORIAL -> 8.dp
        YtSignature.POP -> 14.dp
        YtSignature.SYNTHWAVE -> 12.dp
        else -> 10.dp
    }
    Box(
        Modifier
            .fillMaxSize()
            .then(
                when (signature) {
                    YtSignature.SHAPES -> Modifier.background(Brush.verticalGradient(ShapesNight))
                    // Frequency: the aura that drifts with the sound
                    YtSignature.FREQUENCY -> Modifier.background(palette.background)
                    // Synthwave: the scene, dimmed under the night (SynthwaveBackdrop below)
                    YtSignature.SYNTHWAVE -> Modifier.background(palette.background)
                    else -> Modifier.background(palette.background)
                },
            ),
    ) {
        // Frequency: the aura, blobs and wave in their own layer behind the grid
        if (signature == YtSignature.FREQUENCY) FrequencyBackdrop(palette, Modifier.matchParentSize())
        // Synthwave: the scene behind the grid, under the night veil (the titles stand on it)
        if (signature == YtSignature.SYNTHWAVE) SynthwaveBackdrop(Modifier.matchParentSize(), horizon = 0.66f, veil = SynthwaveColors.TAB_VEIL)
        if (backdrop != null) {
            Box(
                Modifier
                    .fillMaxSize()
                    .layerBackdrop(backdrop)
                    .background(Brush.verticalGradient(ytGlassScreenColors(palette).dropLast(1) + ytGlassScreenColors(palette).first())),
            ) {
                LiquidLight(ytGlassLightColors(palette), Modifier.fillMaxSize())
            }
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp + ytNavPadding()),
            horizontalArrangement = Arrangement.spacedBy(gap),
            verticalArrangement = Arrangement.spacedBy(if (signature == YtSignature.POP) 18.dp else 12.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(span = { GridItemSpan(columns) }) { ShortsHeader(palette, shorts?.size, backdrop) }
            when {
                shorts == null -> item(span = { GridItemSpan(columns) }) {
                    Box(Modifier.fillMaxWidth().height(240.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = if (glass || signature == YtSignature.SHAPES) Color.White else palette.accent)
                    }
                }
                shorts.isEmpty() -> item(span = { GridItemSpan(columns) }) {
                    Text(
                        text = stringResource(R.string.yt_shorts_empty),
                        color = palette.textSecondary,
                        style = TextStyle(fontFamily = palette.font, fontSize = 15.sp, lineHeight = 21.sp),
                        modifier = Modifier.padding(top = 24.dp),
                    )
                }
                else -> itemsIndexed(shorts, key = { _, s -> s.url!! }) { index, short ->
                    Box(Modifier.ytEnter(enter, short.url!!, index)) {
                        when (signature) {
                            YtSignature.CLAY -> ClayShort(palette, imageLoader, short, onOpen)
                            YtSignature.EDITORIAL -> EditorialShort(palette, imageLoader, short, index, onOpen)
                            YtSignature.GLASS -> GlassShort(palette, imageLoader, short, onOpen)
                            YtSignature.POP -> PopShort(palette, imageLoader, short, index, onOpen)
                            YtSignature.SHAPES -> ShapesShort(palette, imageLoader, short, index, onOpen)
                            // Frequency: Formen's shaped frames in its own colours
                            YtSignature.FREQUENCY -> ShapesShort(
                                palette, imageLoader, short, index, onOpen,
                                tag = frequencyTag(palette, index), ink = palette.ink,
                            )
                            YtSignature.FROST -> FrostShort(palette, imageLoader, short, backdrop, onOpen)
                            YtSignature.SYNTHWAVE -> SynthwaveShort(palette, imageLoader, short, index, onOpen)
                            else -> PlainShort(palette, imageLoader, short, onOpen)
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Headers

@Composable
private fun ShortsHeader(palette: YtPalette, count: Int?, backdrop: Backdrop?) {
    val title = stringResource(R.string.yt_nav_shorts)
    when (palette.signature) {
        YtSignature.CLAY -> Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 10.dp, bottom = 6.dp)) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(46.dp)
                    .shadow(8.dp, CircleShape, ambientColor = ClayShadow, spotColor = ClayShadow)
                    .clip(CircleShape)
                    .background(Brush.verticalGradient(listOf(ClayLight, ytDayNight(Color(0xFFF2D9C6), Color(0xFF3A2C26)))))
                    .border(1.dp, ytDayNight(Color(0x99FFFFFF), Color(0x26FFF0E4)), CircleShape),
            ) {
                YtShortsGlyph(ClayInk, filled = true, modifier = Modifier.size(24.dp))
            }
            Text(
                text = title,
                color = palette.text,
                style = TextStyle(fontFamily = YtFonts.fraunces, fontWeight = FontWeight.ExtraBold, fontSize = 30.sp),
                modifier = Modifier.padding(start = 12.dp),
            )
        }
        YtSignature.EDITORIAL -> Column(Modifier.padding(top = 10.dp, bottom = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title.uppercase(),
                    color = palette.textSecondary,
                    style = TextStyle(fontFamily = YtFonts.plexMono, fontWeight = FontWeight.Medium, fontSize = 11.sp, letterSpacing = 2.sp),
                    modifier = Modifier.weight(1f),
                )
                if (count != null) {
                    Text(
                        text = "%02d".format(count),
                        color = palette.text,
                        style = TextStyle(fontFamily = YtFonts.plexMono, fontWeight = FontWeight.Medium, fontSize = 11.sp, letterSpacing = 2.sp),
                    )
                }
            }
            Text(
                text = title,
                color = palette.text,
                style = TextStyle(fontFamily = palette.font, fontWeight = FontWeight.Bold, fontSize = 36.sp, letterSpacing = (-1).sp),
                modifier = Modifier.padding(top = 2.dp),
            )
            Box(
                Modifier
                    .padding(top = 8.dp)
                    .fillMaxWidth()
                    .height(2.dp)
                    .background(palette.text),
            )
        }
        YtSignature.GLASS -> Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .padding(top = 10.dp, bottom = 6.dp)
                .height(48.dp)
                .then(
                    if (backdrop != null) Modifier.liquidGlass(backdrop, RoundedCornerShape(50), ytDayNight(Color(0x8CFFFFFF), Color(0x33A8E0D6)), ytDayNight(Color(0x2E5C8C85), Color(0x66000000)))
                    else Modifier.ytGlass(RoundedCornerShape(50)),
                )
                .padding(start = 14.dp, end = 20.dp),
        ) {
            YtShortsGlyph(palette.live, filled = true, modifier = Modifier.size(24.dp))
            Text(
                text = title,
                color = palette.text,
                style = TextStyle(fontFamily = YtFonts.googleSans, fontWeight = FontWeight.Bold, fontSize = 22.sp),
                modifier = Modifier.padding(start = 10.dp),
            )
        }
        YtSignature.POP -> Box(Modifier.padding(top = 10.dp, bottom = 2.dp)) {
            Text(
                text = title,
                color = PopInk,
                style = TextStyle(fontFamily = YtFonts.nunito, fontWeight = FontWeight.Black, fontSize = 30.sp),
                modifier = Modifier
                    .ytJelly(true, amount = 0.06f)
                    .clip(BlobShape(0.6f, 0.5f, 0.56f, 0.64f))
                    .background(PopFrames[0])
                    .padding(horizontal = 22.dp, vertical = 8.dp),
            )
        }
        YtSignature.SHAPES -> Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 10.dp, bottom = 6.dp)) {
            // a lime cookie that turns slowly
            val spin = rememberSpin(16000)
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(44.dp)
                    .graphicsLayer { rotationZ = spin.value }
                    .clip(CookieShape(8))
                    .background(palette.lime),
            ) {
                YtShortsGlyph(Color(0xFF12122A), filled = true, modifier = Modifier.size(22.dp).graphicsLayer { rotationZ = -spin.value })
            }
            Text(
                text = title,
                color = Color.White,
                style = TextStyle(fontFamily = YtFonts.googleSans, fontWeight = FontWeight.Bold, fontSize = 28.sp),
                modifier = Modifier.padding(start = 12.dp),
            )
        }
        YtSignature.FREQUENCY -> Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 10.dp, bottom = 6.dp)) {
            // a coral cookie that breathes and turns with the sound, YouTube neu's heavy title
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(44.dp)
                    .frequencyCookie(palette.accent, lobes = 8, rest = 0.08f, index = 2),
            ) {
                YtShortsGlyph(palette.ink, filled = true, modifier = Modifier.size(22.dp))
            }
            Text(
                text = title,
                color = palette.text,
                style = TextStyle(fontFamily = YtFonts.geist, fontWeight = FontWeight.ExtraBold, fontSize = 28.sp, letterSpacing = (-0.5).sp),
                modifier = Modifier.padding(start = 12.dp),
            )
        }
        YtSignature.SYNTHWAVE -> Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 10.dp, bottom = 6.dp)) {
            // a magenta disc with the Shorts mark and a soft glow, the title in the retro face
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(40.dp)
                    .synthwaveGlow(CircleShape, palette.accent, 6.dp)
                    .clip(CircleShape)
                    .background(palette.accent),
            ) {
                YtShortsGlyph(palette.onAccent, filled = true, modifier = Modifier.size(20.dp))
            }
            Text(
                text = title.uppercase(),
                style = synthwaveDisplay(24.sp, palette.text, spacing = 1.5.sp),
                modifier = Modifier.padding(start = 12.dp),
            )
        }
        YtSignature.FROST -> Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 10.dp, bottom = 6.dp)) {
            Text(
                text = title,
                color = Color.White,
                style = TextStyle(fontFamily = YtFonts.jakarta, fontWeight = FontWeight.ExtraBold, fontSize = 26.sp),
                modifier = Modifier.weight(1f),
            )
            if (count != null) {
                Text(
                    text = count.toString(),
                    color = Color.White,
                    style = TextStyle(fontFamily = YtFonts.jakarta, fontWeight = FontWeight.Bold, fontSize = 13.sp),
                    modifier = Modifier
                        .frostGlass(backdrop, RoundedCornerShape(50))
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                )
            }
        }
        else -> Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)) {
            YtShortsGlyph(palette.live, filled = true, modifier = Modifier.size(26.dp))
            Text(
                text = title,
                color = palette.text,
                style = TextStyle(fontFamily = palette.font, fontWeight = FontWeight.Bold, fontSize = 24.sp),
                modifier = Modifier.padding(start = 10.dp),
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Cards

@Composable
private fun ShortTitle(palette: YtPalette, short: StreamItem, color: Color = palette.text, dim: Color = palette.textSecondary, font: androidx.compose.ui.text.font.FontFamily = palette.font, weight: FontWeight = FontWeight.SemiBold) {
    Text(
        text = short.ytTitle,
        color = color,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        style = TextStyle(fontFamily = font, fontWeight = weight, fontSize = 13.sp, lineHeight = 17.sp),
        modifier = Modifier.padding(top = 6.dp, start = 2.dp, end = 2.dp),
    )
    Text(
        text = short.uploaderName.orEmpty(),
        color = dim,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        style = TextStyle(fontFamily = font, fontSize = 12.sp),
        modifier = Modifier.padding(start = 2.dp, end = 2.dp),
    )
}

@Composable
private fun ViewsBadge(short: StreamItem, color: Color, font: androidx.compose.ui.text.font.FontFamily, modifier: Modifier) {
    short.views?.takeIf { it > 0 }?.let {
        Text(
            text = stringResource(R.string.yt_after_views, it.formatShort()),
            color = color,
            style = TextStyle(fontFamily = font, fontWeight = FontWeight.Bold, fontSize = 12.sp),
            modifier = modifier,
        )
    }
}

@Composable
private fun PlainShort(palette: YtPalette, imageLoader: ImageLoader, short: StreamItem, onOpen: (StreamItem) -> Unit) {
    val longPress = LocalShortLongPress.current
    Column(Modifier.ytPressable(ytCardShape(palette, 14), onLongClick = { longPress(short) }) { onOpen(short) }) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(9f / 16f)
                .clip(ytCardShape(palette, 14)),
        ) {
            YtLargeThumbnail(short, imageLoader, Modifier.fillMaxSize())
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Brush.verticalGradient(0.6f to Color.Transparent, 1f to Color(0xA6000000))),
            )
            ViewsBadge(short, Color.White, palette.font, Modifier.align(Alignment.BottomStart).padding(8.dp))
        }
        ShortTitle(palette, short)
    }
}

/**
 * Synthwave: a Short in a card of night glass with a neon edge (in turn magenta and cyan) and a
 * soft glow, the title on the glass under the still.
 */
@Composable
private fun SynthwaveShort(palette: YtPalette, imageLoader: ImageLoader, short: StreamItem, index: Int, onOpen: (StreamItem) -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    val neon = if (index % 2 == 0) palette.accent else palette.lime
    val longPress = LocalShortLongPress.current
    Column(
        Modifier
            .synthwaveGlow(shape, neon, 5.dp, 0.7f)
            .ytPressable(shape, onLongClick = { longPress(short) }) { onOpen(short) }
            .synthwaveGlass(shape, edge = 0f)
            .border(1.dp, neon.copy(alpha = 0.75f), shape)
            .padding(5.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(9f / 16f)
                .clip(RoundedCornerShape(8.dp)),
        ) {
            YtLargeThumbnail(short, imageLoader, Modifier.fillMaxSize())
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Brush.verticalGradient(0.6f to Color.Transparent, 1f to Color(0xB30D0620))),
            )
            ViewsBadge(short, Color.White, palette.font, Modifier.align(Alignment.BottomStart).padding(8.dp))
        }
        Column(Modifier.padding(start = 2.dp, end = 2.dp, bottom = 4.dp)) { ShortTitle(palette, short) }
    }
}

/** Clay: a puffy frame around the still, lit from above; it squishes under the finger. */
@Composable
private fun ClayShort(palette: YtPalette, imageLoader: ImageLoader, short: StreamItem, onOpen: (StreamItem) -> Unit) {
    val shape = RoundedCornerShape(24.dp)
    val longPress = LocalShortLongPress.current
    Column(Modifier.ytPressable(shape, squish = true, onLongClick = { longPress(short) }) { onOpen(short) }) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(9f / 16f)
                .shadow(8.dp, shape, ambientColor = ClayShadow, spotColor = ClayShadow)
                .clip(shape)
                .background(Brush.verticalGradient(listOf(ClayLight, palette.surface)))
                .border(1.dp, ytDayNight(Color(0x99FFFFFF), Color(0x26FFF0E4)), shape)
                .padding(7.dp),
        ) {
            YtLargeThumbnail(short, imageLoader, Modifier.fillMaxSize().clip(RoundedCornerShape(18.dp)))
            ViewsBadge(
                short, ClayInk, palette.font,
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(14.dp)
                    .clip(RoundedCornerShape(50))
                    .background(ClayLight)
                    .padding(horizontal = 9.dp, vertical = 3.dp),
            )
        }
        ShortTitle(palette, short, color = ClayInk, dim = ClayLabel)
    }
}

/** Editorial: a numbered still in black and white that takes color under the finger; a hairline below. */
@Composable
private fun EditorialShort(palette: YtPalette, imageLoader: ImageLoader, short: StreamItem, index: Int, onOpen: (StreamItem) -> Unit) {
    val press = remember { MutableInteractionSource() }
    val pressed by press.collectIsPressedAsState()
    val color by animateFloatAsState(if (pressed) 1f else 0f, ytSpec(tween(260, easing = YtEaseOut)), label = "editorialColor")
    val longPress = LocalShortLongPress.current
    Column(
        Modifier
            .combinedClickable(interactionSource = press, indication = null, onLongClick = { longPress(short) }) { onOpen(short) },
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(9f / 16f)
                .background(palette.raised),
        ) {
            AsyncImage(
                model = short.thumbnail,
                contentDescription = null,
                imageLoader = imageLoader,
                contentScale = ContentScale.Crop,
                colorFilter = if (color == 0f) ytGrayscale else ytSaturation(color),
                modifier = Modifier.fillMaxSize(),
            )
            Text(
                text = "%02d".format(index + 1),
                color = palette.background,
                style = TextStyle(fontFamily = YtFonts.plexMono, fontWeight = FontWeight.Medium, fontSize = 10.sp, letterSpacing = 1.sp),
                modifier = Modifier
                    .padding(6.dp)
                    .background(palette.text)
                    .padding(horizontal = 5.dp, vertical = 2.dp),
            )
        }
        Text(
            text = short.ytTitle,
            color = palette.text,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(fontFamily = YtFonts.inter, fontWeight = FontWeight.SemiBold, fontSize = 11.5.sp, lineHeight = 14.sp),
            modifier = Modifier.padding(top = 6.dp),
        )
        Text(
            text = short.uploaderName.orEmpty().uppercase(),
            color = palette.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(fontFamily = YtFonts.plexMono, fontWeight = FontWeight.Medium, fontSize = 9.sp, letterSpacing = 1.sp),
            modifier = Modifier.padding(top = 3.dp),
        )
        Box(
            Modifier
                .padding(top = 8.dp)
                .fillMaxWidth()
                .height(1.dp)
                .background(palette.text.copy(alpha = 0.3f)),
        )
    }
}

/** Glas: the still with a caption of glass that bends it, swelling under the finger. */
@Composable
private fun GlassShort(palette: YtPalette, imageLoader: ImageLoader, short: StreamItem, onOpen: (StreamItem) -> Unit) {
    val still = rememberLayerBackdrop()
    val press = remember { MutableInteractionSource() }
    val swell by rememberSwell(press)
    val longPress = LocalShortLongPress.current
    val shape = RoundedCornerShape(16.dp)
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(9f / 16f)
            .ytPressScale(press)
            .shadow(6.dp, shape, clip = false, ambientColor = ytDayNight(Color(0x405C8C85), Color(0x66000000)), spotColor = ytDayNight(Color(0x405C8C85), Color(0x66000000)))
            .clip(shape)
            .background(palette.lime)
            .combinedClickable(interactionSource = press, indication = LocalIndication.current, onLongClick = { longPress(short) }) { onOpen(short) },
    ) {
        YtLargeThumbnail(short, imageLoader, Modifier.fillMaxSize().layerBackdrop(still))
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(6.dp)
                .fillMaxWidth()
                .liquidGlass(still, RoundedCornerShape(12.dp), ytDayNight(Color(0x73FFFFFF), Color(0xE6142826)), null, blur = 8.dp, refraction = 8.dp, swell = { swell })
                .padding(horizontal = 9.dp, vertical = 7.dp),
        ) {
            Text(
                text = short.ytTitle,
                color = palette.text,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(fontFamily = YtFonts.googleSans, fontWeight = FontWeight.Medium, fontSize = 11.5.sp, lineHeight = 14.sp),
            )
            Text(
                text = short.uploaderName.orEmpty(),
                color = palette.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(fontFamily = YtFonts.googleSans, fontSize = 10.5.sp),
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/** Pop: a colored frame, each card a little askew; a tap wobbles it and the frame straightens while pressed. */
@Composable
private fun PopShort(palette: YtPalette, imageLoader: ImageLoader, short: StreamItem, index: Int, onOpen: (StreamItem) -> Unit) {
    var taps by remember { mutableIntStateOf(0) }
    val press = remember { MutableInteractionSource() }
    val pressed by press.collectIsPressedAsState()
    val longPress = LocalShortLongPress.current
    val askew = if (index % 2 == 0) -2.5f else 2.5f
    val tilt by animateFloatAsState(if (pressed) 0f else askew, ytSpec(spring(dampingRatio = 0.5f, stiffness = 260f)), label = "popTilt")
    val shape = RoundedCornerShape(26.dp)
    Column(
        Modifier
            .graphicsLayer { rotationZ = tilt }
            .ytJelly(taps % 2 == 0, amount = 0.08f)
            .ytSquish(press)
            .clip(shape)
            .combinedClickable(interactionSource = press, indication = null, onLongClick = { longPress(short) }) {
                taps++
                onOpen(short)
            },
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(9f / 16f)
                .clip(shape)
                .background(PopFrames[index % PopFrames.size])
                .padding(7.dp),
        ) {
            YtLargeThumbnail(short, imageLoader, Modifier.fillMaxSize().clip(RoundedCornerShape(20.dp)))
            ViewsBadge(
                short, PopInk, YtFonts.nunito,
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(14.dp)
                    .clip(RoundedCornerShape(50))
                    .background(PopFrames[0])
                    .padding(horizontal = 9.dp, vertical = 3.dp),
            )
        }
        ShortTitle(palette, short, font = YtFonts.nunito, weight = FontWeight.ExtraBold)
    }
}

/** Formen: alternating widget shapes in the night, each with its own tag color. */
@Composable
private fun ShapesShort(
    palette: YtPalette,
    imageLoader: ImageLoader,
    short: StreamItem,
    index: Int,
    onOpen: (StreamItem) -> Unit,
    tag: Color = ShapesTags[index % ShapesTags.size],
    ink: Color = Color(0xFF12122A),
) {
    val shape: Shape = if (index % 2 == 0) {
        RoundedCornerShape(topStart = 56.dp, topEnd = 16.dp, bottomEnd = 56.dp, bottomStart = 16.dp)
    } else {
        RoundedCornerShape(topStart = 16.dp, topEnd = 56.dp, bottomEnd = 16.dp, bottomStart = 56.dp)
    }
    val longPress = LocalShortLongPress.current
    Column(Modifier.ytPressable(RoundedCornerShape(16.dp), onLongClick = { longPress(short) }) { onOpen(short) }) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(9f / 16f)
                .clip(shape)
                .background(tag)
                .padding(4.dp),
        ) {
            YtLargeThumbnail(short, imageLoader, Modifier.fillMaxSize().clip(shape))
            ViewsBadge(
                short, ink, palette.font,
                Modifier
                    .align(if (index % 2 == 0) Alignment.BottomEnd else Alignment.BottomStart)
                    .padding(12.dp)
                    .clip(RoundedCornerShape(50))
                    .background(tag)
                    .padding(horizontal = 9.dp, vertical = 3.dp),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp, start = 4.dp, end = 4.dp)) {
            Box(
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(tag),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = short.uploaderName.orEmpty(),
                color = palette.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(fontFamily = palette.font, fontSize = 11.5.sp),
            )
        }
        Text(
            text = short.ytTitle,
            color = palette.text,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(fontFamily = palette.font, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 17.sp),
            modifier = Modifier.padding(top = 2.dp, start = 4.dp, end = 4.dp),
        )
    }
}

/** Frost: a frosted card on sage that thaws under a warm finger. */
@Composable
private fun FrostShort(palette: YtPalette, imageLoader: ImageLoader, short: StreamItem, backdrop: Backdrop?, onOpen: (StreamItem) -> Unit) {
    val press = remember { MutableInteractionSource() }
    val thaw by rememberThaw(press)
    val longPress = LocalShortLongPress.current
    Column(
        Modifier
            .ytPressScale(press)
            .frostGlass(backdrop, RoundedCornerShape(20.dp)) { thaw }
            .combinedClickable(interactionSource = press, indication = LocalIndication.current, onLongClick = { longPress(short) }) { onOpen(short) }
            .padding(6.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(9f / 16f)
                .clip(RoundedCornerShape(14.dp)),
        ) {
            YtLargeThumbnail(short, imageLoader, Modifier.fillMaxSize())
            ViewsBadge(
                short, Color.White, YtFonts.jakarta,
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(8.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color(0x40FFFFFF))
                    .padding(horizontal = 9.dp, vertical = 3.dp),
            )
        }
        ShortTitle(palette, short, color = Color.White, font = YtFonts.jakarta)
    }
}
