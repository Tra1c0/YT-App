package com.github.libretube.ui.yt

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.ImageLoader
import coil3.compose.AsyncImage
import com.github.libretube.R
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop

/*
 * YT: the pieces of the personal area ("Du") that differ per design beyond colors: the
 * headline, the big continue-watching card and the collection cards. The shared screen keeps
 * its order (headline, quick access, continue watching, collections, bookmarks) and hands these
 * pieces to the design: Clay knead, Editorial print, Glas glass, Pop blobs, Formen widget
 * shapes, Frost frost, Kiesel capsules; After a poster wall, Movo a mosaic, Side quiet notes.
 */

/** A backdrop behind the shared screens of the glass designs, for their glass pieces. */
internal val LocalScreenBackdrop = androidx.compose.runtime.staticCompositionLocalOf<Backdrop?> { null }

/**
 * Wraps a shared screen: for the glass designs a gradient with drifting light lies behind it,
 * recorded as a backdrop the glass pieces look through; for Formen the night gradient; else the
 * background color.
 */
@Composable
fun YtScreenSurface(palette: YtPalette, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val glass = palette.signature == YtSignature.GLASS || palette.signature == YtSignature.FROST
    val backdrop = if (glass) rememberLayerBackdrop() else null
    // Frequency: the screen holds the pulse, and its aura moves with the sound
    if (palette.isFrequency) FrequencyPulseHost()
    Box(
        modifier.then(
            when (palette.signature) {
                YtSignature.SHAPES -> Modifier.background(Brush.verticalGradient(listOf(Color(0xFF12122A), Color(0xFF1C1A45), Color(0xFF3F3489), Color(0xFF6656C4))))
                YtSignature.FREQUENCY -> Modifier.frequencyAura(palette)
                else -> Modifier.background(palette.background)
            },
        ),
    ) {
        if (backdrop != null) {
            val colors = ytGlassScreenColors(palette)
            Box(
                Modifier
                    .fillMaxSize()
                    .layerBackdrop(backdrop)
                    .background(Brush.verticalGradient(colors.dropLast(1) + colors.first())),
            ) {
                LiquidLight(ytGlassLightColors(palette), Modifier.fillMaxSize())
            }
        }
        androidx.compose.runtime.CompositionLocalProvider(LocalScreenBackdrop provides backdrop) { content() }
    }
}

private val ClayShadow: Color get() = ytDayNight(Color(0x80B07A66), Color(0x80000000))
private val ClayInk: Color get() = ytDayNight(Color(0xFF7A5560), Color(0xFFE8C6C2))
private val ClayLabel: Color get() = ytDayNight(Color(0xFF8C6563), Color(0xFFC9AEA2))
private val ClayLight: Color get() = ytDayNight(Color(0xFFFFF3E9), Color(0xFF4A3830))
private val ClayDeep: Color get() = ytDayNight(Color(0xFFF2D9C6), Color(0xFF3A2C26))
private val ClayMauve: Color get() = ytDayNight(Color(0xFF9D6A6C), Color(0xFFD9A5A6))
private val PopInk = Color(0xFF3B2054)
private val PopFrames = listOf(Color(0xFFFDE682), Color(0xFFF7B3CE), Color(0xFFDFD6F7), Color(0xFFBFE8D2))
private val ShapesTags = listOf(Color(0xFFF6D365), Color(0xFFBFE26B), Color(0xFFD9D4EE), Color(0xFFF28B82))
private val ShapesInk = Color(0xFF12122A)
private val GlassShadow: Color get() = ytDayNight(Color(0x2E5C8C85), Color(0x66000000))
private val KieselBeige: Color get() = ytDayNight(Color(0xFFE2DCD5), Color(0xFF4A4541))
private val KieselCard: Color get() = ytDayNight(Color(0xFFF7F4F1), Color(0xFF2B2826))
private val KieselGrey: Color get() = ytDayNight(Color(0xFF7A7672), Color(0xFFABA59F))

/** The text and the secondary text of the design on its screen background. */
private fun inkOf(palette: YtPalette): Color = when (palette.signature) {
    YtSignature.SHAPES, YtSignature.FROST -> Color.White
    YtSignature.KIESEL -> KieselInk
    else -> palette.text
}

// ---------------------------------------------------------------------------------------------
// Headline

/** The headline of the personal area in the type of the design, with a small living detail. */
@Composable
internal fun LibraryHeadline(palette: YtPalette, headline: String, subline: String) {
    val padding = Modifier.padding(start = 15.dp, end = 15.dp, top = 4.dp, bottom = 16.dp)
    when (palette.signature) {
        YtSignature.AFTER -> Column(padding) {
            Text(
                text = headline.uppercase(),
                color = palette.text,
                style = TextStyle(fontFamily = YtFonts.anton, fontSize = 46.sp, lineHeight = 46.sp, letterSpacing = 1.sp),
            )
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                Box(
                    Modifier
                        .width(28.dp)
                        .height(2.dp)
                        .background(palette.accent),
                )
                Text(
                    text = subline.uppercase(),
                    color = palette.textSecondary,
                    style = TextStyle(fontFamily = YtFonts.robotoCondensed, fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 2.sp),
                    modifier = Modifier.padding(start = 10.dp),
                )
            }
        }
        YtSignature.CLAY -> Box(padding) {
            // a blob of clay behind the headline, breathing slowly
            val breath by rememberBreath(0.03f, 3200)
            Box(
                Modifier
                    .padding(top = 6.dp)
                    .size(96.dp, 64.dp)
                    .graphicsLayer {
                        scaleX = breath
                        scaleY = breath
                        rotationZ = -8f
                    }
                    .shadow(10.dp, RoundedCornerShape(32.dp), ambientColor = ClayShadow, spotColor = ClayShadow)
                    .clip(RoundedCornerShape(32.dp))
                    .background(Brush.verticalGradient(listOf(ClayLight, ClayDeep))),
            )
            Column(Modifier.padding(start = 24.dp, top = 14.dp)) {
                Text(
                    text = headline,
                    color = palette.text,
                    style = TextStyle(fontFamily = YtFonts.fraunces, fontWeight = FontWeight.ExtraBold, fontSize = 40.sp, lineHeight = 42.sp),
                )
                Text(text = subline, color = ClayLabel, style = TextStyle(fontFamily = palette.font, fontSize = 17.sp))
            }
        }
        YtSignature.EDITORIAL -> Column(padding) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = subline.uppercase(),
                    color = palette.textSecondary,
                    style = TextStyle(fontFamily = YtFonts.plexMono, fontWeight = FontWeight.Medium, fontSize = 10.sp, letterSpacing = 2.sp),
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "№ 1",
                    color = palette.text,
                    style = TextStyle(fontFamily = YtFonts.plexMono, fontWeight = FontWeight.Medium, fontSize = 10.sp, letterSpacing = 2.sp),
                )
            }
            Text(
                text = headline,
                color = palette.text,
                style = TextStyle(fontFamily = palette.font, fontWeight = FontWeight.Bold, fontSize = 44.sp, lineHeight = 44.sp, letterSpacing = (-1.5).sp),
                modifier = Modifier.padding(top = 4.dp),
            )
            Box(
                Modifier
                    .padding(top = 8.dp)
                    .fillMaxWidth()
                    .height(2.dp)
                    .background(palette.text),
            )
            Box(
                Modifier
                    .padding(top = 2.dp)
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(palette.text),
            )
        }
        YtSignature.GLASS -> Column(padding) {
            Text(
                text = headline,
                color = palette.text,
                style = TextStyle(fontFamily = YtFonts.googleSans, fontWeight = FontWeight.Bold, fontSize = 38.sp, lineHeight = 40.sp, letterSpacing = (-0.5).sp),
            )
            val backdrop = LocalScreenBackdrop.current
            Text(
                text = subline,
                color = palette.text,
                style = TextStyle(fontFamily = YtFonts.googleSans, fontWeight = FontWeight.Medium, fontSize = 13.sp),
                modifier = Modifier
                    .padding(top = 8.dp)
                    .then(
                        if (backdrop != null) Modifier.liquidGlass(backdrop, RoundedCornerShape(50), ytDayNight(Color(0x80FFFFFF), Color(0x33A8E0D6)), GlassShadow)
                        else Modifier.ytGlass(RoundedCornerShape(50)),
                    )
                    .padding(horizontal = 14.dp, vertical = 7.dp),
            )
        }
        YtSignature.POP -> Column(padding) {
            // the first word sits on a yellow blob that wobbles once when the page opens
            val first = headline.substringBefore(' ')
            val rest = headline.removePrefix(first).trim()
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = first,
                    color = PopInk,
                    style = TextStyle(fontFamily = YtFonts.nunito, fontWeight = FontWeight.Black, fontSize = 38.sp),
                    modifier = Modifier
                        .ytJelly(true, amount = 0.08f)
                        .clip(BlobShape(0.6f, 0.5f, 0.56f, 0.64f))
                        .background(PopFrames[0])
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                )
                if (rest.isNotEmpty()) {
                    Text(
                        text = rest,
                        color = palette.text,
                        style = TextStyle(fontFamily = YtFonts.nunito, fontWeight = FontWeight.Black, fontSize = 38.sp),
                        modifier = Modifier.padding(start = 8.dp, bottom = 4.dp),
                    )
                }
            }
            Text(text = subline, color = palette.textSecondary, style = TextStyle(fontFamily = YtFonts.nunito, fontWeight = FontWeight.Bold, fontSize = 17.sp), modifier = Modifier.padding(top = 6.dp))
        }
        YtSignature.SHAPES -> Column(padding) {
            // three small shapes turn slowly beside the headline
            val spin = rememberSpin(18000)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 10.dp)) {
                listOf<Shape>(CookieShape(8), CircleShape, RoundedCornerShape(topStartPercent = 50, topEndPercent = 20, bottomEndPercent = 50, bottomStartPercent = 20)).forEachIndexed { i, shape ->
                    Box(
                        Modifier
                            .size(22.dp)
                            .graphicsLayer { rotationZ = spin.value * (if (i % 2 == 0) 1f else -1f) }
                            .clip(shape)
                            .background(ShapesTags[i]),
                    )
                }
            }
            Text(
                text = headline,
                color = Color.White,
                style = TextStyle(fontFamily = YtFonts.googleSans, fontWeight = FontWeight.Bold, fontSize = 38.sp, lineHeight = 40.sp),
            )
            Text(text = subline, color = palette.textSecondary, style = TextStyle(fontFamily = YtFonts.googleSans, fontSize = 17.sp))
        }
        YtSignature.FREQUENCY -> Column(padding) {
            // YouTube neu's big headline; three of Formen's shapes above it breathe with the sound
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 10.dp)) {
                listOf<Shape>(CookieShape(8), CircleShape, RoundedCornerShape(topStartPercent = 50, topEndPercent = 20, bottomEndPercent = 50, bottomStartPercent = 20)).forEachIndexed { i, shape ->
                    Box(
                        Modifier
                            .size(20.dp)
                            .frequencyBreath()
                            .frequencyTurn(if (i % 2 == 0) 1f else -1f)
                            .clip(shape)
                            .background(frequencyTag(palette, i)),
                    )
                }
            }
            Text(
                text = headline,
                color = palette.text,
                style = TextStyle(fontFamily = YtFonts.geist, fontWeight = FontWeight.ExtraBold, fontSize = 41.sp, lineHeight = 42.sp, letterSpacing = (-0.8).sp),
            )
            Text(text = subline, color = palette.textSecondary, style = TextStyle(fontFamily = YtFonts.geist, fontSize = 20.sp))
        }
        YtSignature.FROST -> Column(padding) {
            Text(
                text = headline,
                color = Color.White,
                style = TextStyle(fontFamily = YtFonts.jakarta, fontWeight = FontWeight.ExtraBold, fontSize = 36.sp, lineHeight = 40.sp),
            )
            Text(text = subline, color = palette.textSecondary, style = TextStyle(fontFamily = YtFonts.jakarta, fontSize = 16.sp))
        }
        YtSignature.KIESEL -> Column(padding) {
            KieselTypedHeadline(headline, color = KieselInk, size = 38f)
            Text(text = subline, color = KieselGrey, style = TextStyle(fontFamily = YtFonts.jakarta, fontWeight = FontWeight.Medium, fontSize = 16.sp), modifier = Modifier.padding(top = 4.dp))
        }
        else -> Column(padding) {
            Text(
                text = headline,
                color = palette.text,
                style = TextStyle(fontFamily = palette.font, fontWeight = FontWeight.ExtraBold, fontSize = 41.sp, lineHeight = 42.sp, letterSpacing = (-0.8).sp),
            )
            Text(text = subline, color = palette.textSecondary, style = TextStyle(fontFamily = palette.font, fontSize = 20.sp))
        }
    }
}

/** A section title in the type of the design. */
@Composable
internal fun LibraryTitle(palette: YtPalette, title: String, modifier: Modifier = Modifier) {
    when (palette.signature) {
        YtSignature.EDITORIAL -> Text(
            text = title.uppercase(),
            color = palette.text,
            style = TextStyle(fontFamily = YtFonts.plexMono, fontWeight = FontWeight.Medium, fontSize = 11.sp, letterSpacing = 2.sp),
            modifier = modifier,
        )
        YtSignature.AFTER -> Text(
            text = title.uppercase(),
            color = palette.text,
            style = TextStyle(fontFamily = YtFonts.robotoCondensed, fontWeight = FontWeight.Bold, fontSize = 14.sp, letterSpacing = 2.sp),
            modifier = modifier,
        )
        YtSignature.KIESEL -> Text(
            text = title,
            color = KieselInk,
            style = TextStyle(fontFamily = YtFonts.jakarta, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp),
            modifier = modifier,
        )
        else -> Text(
            text = title,
            color = inkOf(palette),
            style = TextStyle(fontFamily = palette.font, fontWeight = FontWeight.Bold, fontSize = 17.5.sp),
            modifier = modifier,
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Continue watching

/** The watched part fills up when the card appears, so the eye finds where you left off. */
@Composable
private fun rememberFill(fraction: Float): Animatable<Float, *> {
    val motion = LocalYtMotion.current
    val fill = remember { Animatable(if (motion) 0f else fraction) }
    LaunchedEffect(fraction) {
        if (motion) fill.animateTo(fraction, tween(700, delayMillis = 150, easing = YtEaseOut)) else fill.snapTo(fraction)
    }
    return fill
}

/**
 * The big continue-watching card of the design. [progress] 0 to 1, [remaining] the label of
 * the time left.
 */
@Composable
internal fun LibraryContinue(
    palette: YtPalette,
    imageLoader: ImageLoader,
    item: YtContinueItem,
    progress: Float,
    remaining: String?,
    onOpen: (YtContinueItem) -> Unit,
) {
    val fill = rememberFill(progress)
    val title = item.video.ytTitle
    val channel = item.video.uploaderName.orEmpty()
    when (palette.signature) {
        YtSignature.CLAY -> {
            // a frame of clay; the progress is a groove with a round knob
            val press = remember { MutableInteractionSource() }
            Column(
                Modifier
                    .padding(horizontal = 12.dp)
                    .ytSquish(press)
                    .shadow(10.dp, RoundedCornerShape(28.dp), ambientColor = ClayShadow, spotColor = ClayShadow)
                    .clip(RoundedCornerShape(28.dp))
                    .background(Brush.verticalGradient(listOf(ClayLight, palette.surface)))
                    .border(1.dp, ytDayNight(Color(0x99FFFFFF), Color(0x26FFF0E4)), RoundedCornerShape(28.dp))
                    .clickable(interactionSource = press, indication = LocalIndication.current) { onOpen(item) }
                    .padding(8.dp),
            ) {
                AsyncImage(
                    model = item.video.thumbnail, contentDescription = null, imageLoader = imageLoader, contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(2.4f)
                        .clip(RoundedCornerShape(22.dp)),
                )
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 10.dp, start = 8.dp, end = 8.dp)) {
                    Box(
                        Modifier
                            .weight(1f)
                            .height(12.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(ClayDeep)
                            .drawBehind {
                                val w = size.width * fill.value
                                drawRoundRect(ClayMauve, size = size.copy(width = w), cornerRadius = androidx.compose.ui.geometry.CornerRadius(6.dp.toPx()))
                                drawCircle(ClayLight, 7.dp.toPx(), Offset(w.coerceIn(7.dp.toPx(), size.width - 7.dp.toPx()), size.height / 2))
                            },
                    )
                    remaining?.let {
                        Text(stringResource(R.string.yt_library_remaining, it), color = ClayLabel, style = TextStyle(fontFamily = palette.font, fontWeight = FontWeight.Medium, fontSize = 12.5.sp), modifier = Modifier.padding(start = 12.dp))
                    }
                }
                Text(title, color = ClayInk, maxLines = 2, overflow = TextOverflow.Ellipsis, style = TextStyle(fontFamily = palette.font, fontWeight = FontWeight.SemiBold, fontSize = 14.5.sp, lineHeight = 19.sp), modifier = Modifier.padding(start = 8.dp, top = 8.dp, end = 8.dp))
                Text(channel, color = ClayLabel, style = TextStyle(fontFamily = palette.font, fontSize = 12.5.sp), modifier = Modifier.padding(start = 8.dp, top = 1.dp, bottom = 6.dp))
            }
        }
        YtSignature.EDITORIAL -> {
            // a still in black and white that takes color under the finger; the progress a rule with the percent in mono
            val press = remember { MutableInteractionSource() }
            val pressed by press.collectIsPressedAsState()
            val color by animateFloatAsState(if (pressed) 1f else 0f, ytSpec(tween(260, easing = YtEaseOut)), label = "editorialColor")
            Column(
                Modifier
                    .padding(horizontal = 15.dp)
                    .clickable(interactionSource = press, indication = null) { onOpen(item) },
            ) {
                AsyncImage(
                    model = item.video.thumbnail, contentDescription = null, imageLoader = imageLoader, contentScale = ContentScale.Crop,
                    colorFilter = if (color == 0f) ytGrayscale else ytSaturation(color),
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(2.6f),
                )
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                    Box(
                        Modifier
                            .weight(1f)
                            .height(3.dp)
                            .background(palette.text.copy(alpha = 0.2f))
                            .drawBehind { drawRect(palette.text, size = size.copy(width = size.width * fill.value)) },
                    )
                    Text(
                        "%d %%".format((progress * 100).toInt()),
                        color = palette.text,
                        style = TextStyle(fontFamily = YtFonts.plexMono, fontWeight = FontWeight.Medium, fontSize = 11.sp, letterSpacing = 1.sp),
                        modifier = Modifier.padding(start = 10.dp),
                    )
                }
                Text(title, color = palette.text, maxLines = 2, overflow = TextOverflow.Ellipsis, style = TextStyle(fontFamily = YtFonts.inter, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 19.sp), modifier = Modifier.padding(top = 8.dp))
                Text(channel.uppercase(), color = palette.textSecondary, style = TextStyle(fontFamily = YtFonts.plexMono, fontWeight = FontWeight.Medium, fontSize = 10.sp, letterSpacing = 1.5.sp), modifier = Modifier.padding(top = 3.dp))
            }
        }
        YtSignature.GLASS -> {
            // the still with a caption of glass over it that holds the progress and swells under the finger
            val still = rememberLayerBackdrop()
            val press = remember { MutableInteractionSource() }
            val swell by rememberSwell(press)
            Box(
                Modifier
                    .padding(horizontal = 12.dp)
                    .fillMaxWidth()
                    .aspectRatio(2.1f)
                    .ytPressScale(press)
                    .shadow(8.dp, RoundedCornerShape(18.dp), clip = false, ambientColor = ytDayNight(Color(0x405C8C85), Color(0x66000000)), spotColor = ytDayNight(Color(0x405C8C85), Color(0x66000000)))
                    .clip(RoundedCornerShape(18.dp))
                    .background(palette.lime)
                    .clickable(interactionSource = press, indication = LocalIndication.current) { onOpen(item) },
            ) {
                AsyncImage(
                    model = item.video.thumbnail, contentDescription = null, imageLoader = imageLoader, contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().layerBackdrop(still),
                )
                Column(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(8.dp)
                        .fillMaxWidth()
                        .liquidGlass(still, RoundedCornerShape(12.dp), ytDayNight(Color(0x80FFFFFF), Color(0xE6142826)), null, blur = 8.dp, refraction = 8.dp, swell = { swell })
                        .padding(horizontal = 12.dp, vertical = 9.dp),
                ) {
                    Text(title, color = palette.text, maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(fontFamily = YtFonts.googleSans, fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp))
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                        Box(
                            Modifier
                                .weight(1f)
                                .height(5.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(Color(0x66FFFFFF))
                                .drawBehind { drawRect(palette.text, size = size.copy(width = size.width * fill.value)) },
                        )
                        remaining?.let {
                            Text(stringResource(R.string.yt_library_remaining, it), color = palette.text, style = TextStyle(fontFamily = YtFonts.googleSans, fontSize = 11.5.sp), modifier = Modifier.padding(start = 10.dp))
                        }
                    }
                }
            }
        }
        YtSignature.POP -> {
            // a blob with the still; the progress a yellow pill that fills, the card wobbles on a tap
            val press = remember { MutableInteractionSource() }
            val blob = BlobShape(0.6f, 0.5f, 0.56f, 0.64f)
            Column(
                Modifier
                    .padding(horizontal = 12.dp)
                    .ytSquish(press)
                    .clip(RoundedCornerShape(26.dp))
                    .clickable(interactionSource = press, indication = null) { onOpen(item) },
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(2.3f)
                        .clip(blob)
                        .background(PopFrames[1])
                        .padding(6.dp),
                ) {
                    AsyncImage(
                        model = item.video.thumbnail, contentDescription = null, imageLoader = imageLoader, contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().clip(blob),
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(start = 22.dp, end = 22.dp, bottom = 12.dp)
                            .fillMaxWidth()
                            .height(18.dp)
                            .clip(RoundedCornerShape(50))
                            .background(Color(0xB3FFFFFF))
                            .drawBehind { drawRect(PopFrames[0], size = size.copy(width = size.width * fill.value)) }
                            .padding(horizontal = 8.dp),
                    ) {
                        remaining?.let {
                            Text(stringResource(R.string.yt_library_remaining, it), color = PopInk, maxLines = 1, style = TextStyle(fontFamily = YtFonts.nunito, fontWeight = FontWeight.ExtraBold, fontSize = 10.5.sp))
                        }
                    }
                }
                Text(title, color = palette.text, maxLines = 2, overflow = TextOverflow.Ellipsis, style = TextStyle(fontFamily = YtFonts.nunito, fontWeight = FontWeight.ExtraBold, fontSize = 14.5.sp, lineHeight = 19.sp), modifier = Modifier.padding(start = 6.dp, top = 8.dp, end = 6.dp))
                Text(channel, color = palette.textSecondary, style = TextStyle(fontFamily = YtFonts.nunito, fontWeight = FontWeight.Bold, fontSize = 12.5.sp), modifier = Modifier.padding(start = 6.dp, top = 1.dp))
            }
        }
        YtSignature.SHAPES -> {
            // the still in a widget shape; the progress is the lime wave of the design
            val shape = RoundedCornerShape(topStart = 28.dp, topEnd = 90.dp, bottomEnd = 28.dp, bottomStart = 28.dp)
            val phase = rememberWavePhase()
            Column(
                Modifier
                    .padding(horizontal = 12.dp)
                    .ytPressable(shape) { onOpen(item) }
                    .background(palette.surface)
                    .padding(10.dp),
            ) {
                AsyncImage(
                    model = item.video.thumbnail, contentDescription = null, imageLoader = imageLoader, contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(2.4f)
                        .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 80.dp, bottomEnd = 20.dp, bottomStart = 20.dp)),
                )
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 10.dp, start = 6.dp, end = 6.dp)) {
                    WavyProgress(
                        progress = progress,
                        played = palette.accent,
                        rest = Color(0xFFD9D4EE),
                        modifier = Modifier
                            .weight(1f)
                            .height(18.dp),
                        phase = { phase.value },
                    )
                    remaining?.let {
                        Text(stringResource(R.string.yt_library_remaining, it), color = palette.ink.copy(alpha = 0.7f), style = TextStyle(fontFamily = YtFonts.googleSans, fontSize = 12.sp), modifier = Modifier.padding(start = 12.dp))
                    }
                }
                Text(title, color = palette.ink, maxLines = 2, overflow = TextOverflow.Ellipsis, style = TextStyle(fontFamily = YtFonts.googleSans, fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 19.sp), modifier = Modifier.padding(start = 6.dp, top = 8.dp, end = 6.dp))
                Text(channel, color = palette.ink.copy(alpha = 0.6f), style = TextStyle(fontFamily = YtFonts.googleSans, fontSize = 12.sp), modifier = Modifier.padding(start = 6.dp, top = 1.dp, bottom = 2.dp))
            }
        }
        YtSignature.FREQUENCY -> {
            // YouTube neu's still in a Formen widget corner; a coral cookie as the play button
            // breathes with the bass, the coral progress shimmers with the highs
            val shape = RoundedCornerShape(topStart = 22.dp, topEnd = 60.dp, bottomEnd = 22.dp, bottomStart = 22.dp)
            Column(
                Modifier
                    .padding(horizontal = 12.dp)
                    .ytPressable(shape) { onOpen(item) },
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(2.6f)
                        .clip(shape),
                ) {
                    AsyncImage(
                        model = item.video.thumbnail, contentDescription = null, imageLoader = imageLoader, contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(end = 14.dp, bottom = 22.dp)
                            .size(44.dp)
                            .frequencyCookie(palette.accent, lobes = 9, rest = 0.08f),
                    ) {
                        Icon(painterResource(R.drawable.ic_play), contentDescription = null, tint = palette.onAccent, modifier = Modifier.size(18.dp))
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth()
                            .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xB30B0A18))))
                            .padding(start = 12.dp, end = 12.dp, bottom = 6.dp, top = 10.dp),
                    ) {
                        Box(
                            Modifier
                                .weight(1f)
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(Color(0x66FFFFFF))
                                .drawBehind { drawRect(palette.accent, size = size.copy(width = size.width * fill.value.coerceIn(0f, 1f))) }
                                .frequencyShimmer(),
                        )
                        remaining?.let {
                            Text(
                                text = stringResource(R.string.yt_library_remaining, it),
                                color = Color.White,
                                style = TextStyle(fontFamily = YtFonts.geist, fontWeight = FontWeight.Medium, fontSize = 13.sp),
                                modifier = Modifier.padding(start = 66.dp),
                            )
                        }
                    }
                }
                Text(title, color = palette.text, maxLines = 2, overflow = TextOverflow.Ellipsis, style = TextStyle(fontFamily = YtFonts.geist, fontWeight = FontWeight.SemiBold, fontSize = 14.5.sp, lineHeight = 19.sp), modifier = Modifier.padding(start = 3.dp, top = 8.dp))
                Text(channel, color = palette.textSecondary, style = TextStyle(fontFamily = YtFonts.geist, fontSize = 12.5.sp), modifier = Modifier.padding(start = 3.dp, top = 1.dp))
            }
        }
        YtSignature.FROST -> {
            // a frosted frame that thaws under the finger; the progress a white line
            val backdrop = LocalScreenBackdrop.current
            val press = remember { MutableInteractionSource() }
            val thaw by rememberThaw(press)
            Column(
                Modifier
                    .padding(horizontal = 12.dp)
                    .fillMaxWidth()
                    .frostGlass(backdrop, RoundedCornerShape(24.dp)) { thaw }
                    .clickable(interactionSource = press, indication = LocalIndication.current) { onOpen(item) }
                    .padding(8.dp),
            ) {
                AsyncImage(
                    model = item.video.thumbnail, contentDescription = null, imageLoader = imageLoader, contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(2.4f)
                        .clip(RoundedCornerShape(18.dp)),
                )
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 10.dp, start = 6.dp, end = 6.dp)) {
                    Box(
                        Modifier
                            .weight(1f)
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(Color(0x40FFFFFF))
                            .drawBehind { drawRect(Color.White, size = size.copy(width = size.width * fill.value)) },
                    )
                    remaining?.let {
                        Text(stringResource(R.string.yt_library_remaining, it), color = Color.White, style = TextStyle(fontFamily = YtFonts.jakarta, fontWeight = FontWeight.Medium, fontSize = 12.sp), modifier = Modifier.padding(start = 12.dp))
                    }
                }
                Text(title, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis, style = TextStyle(fontFamily = YtFonts.jakarta, fontWeight = FontWeight.SemiBold, fontSize = 14.5.sp, lineHeight = 19.sp), modifier = Modifier.padding(start = 6.dp, top = 8.dp, end = 6.dp))
                Text(channel, color = palette.textSecondary, style = TextStyle(fontFamily = YtFonts.jakarta, fontSize = 12.5.sp), modifier = Modifier.padding(start = 6.dp, top = 1.dp, bottom = 2.dp))
            }
        }
        YtSignature.KIESEL -> {
            // a peach card; the progress a lavender capsule that fills, the time left in a black capsule
            Column(
                Modifier
                    .padding(horizontal = 12.dp)
                    .ytPressable(RoundedCornerShape(22.dp), squish = true) { onOpen(item) }
                    .background(KieselPeach)
                    .padding(10.dp),
            ) {
                AsyncImage(
                    model = item.video.thumbnail, contentDescription = null, imageLoader = imageLoader, contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(2.4f)
                        .clip(RoundedCornerShape(16.dp)),
                )
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 10.dp, start = 4.dp, end = 4.dp)) {
                    Box(
                        Modifier
                            .weight(1f)
                            .height(14.dp)
                            .clip(RoundedCornerShape(7.dp))
                            .background(Color(0x59FFFFFF))
                            .drawBehind { drawRoundRect(KieselLavender, size = size.copy(width = (size.width * fill.value).coerceAtLeast(size.height)), cornerRadius = androidx.compose.ui.geometry.CornerRadius(7.dp.toPx())) },
                    )
                    remaining?.let {
                        Text(
                            stringResource(R.string.yt_library_remaining, it),
                            color = KieselCream,
                            style = TextStyle(fontFamily = YtFonts.jakarta, fontWeight = FontWeight.Bold, fontSize = 11.sp),
                            modifier = Modifier
                                .padding(start = 10.dp)
                                .clip(RoundedCornerShape(50))
                                .background(KieselBlack)
                                .padding(horizontal = 10.dp, vertical = 4.dp),
                        )
                    }
                }
                Text(title, color = KieselBlack, maxLines = 2, overflow = TextOverflow.Ellipsis, style = TextStyle(fontFamily = YtFonts.jakarta, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp, lineHeight = 19.sp), modifier = Modifier.padding(start = 4.dp, top = 10.dp, end = 4.dp))
                Text(channel, color = KieselBlack.copy(alpha = 0.7f), style = TextStyle(fontFamily = YtFonts.jakarta, fontSize = 12.5.sp), modifier = Modifier.padding(start = 4.dp, top = 1.dp, bottom = 2.dp))
            }
        }
        YtSignature.AFTER -> {
            // a poster: the still letterboxed, the title in Anton over it, the progress a thin accent line at the bottom edge
            Column(
                Modifier
                    .padding(horizontal = 12.dp)
                    .ytPressable(RoundedCornerShape(4.dp)) { onOpen(item) },
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(2.2f)
                        .background(Color.Black),
                ) {
                    AsyncImage(
                        model = item.video.thumbnail, contentDescription = null, imageLoader = imageLoader, contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxWidth()
                            .fillMaxHeight(0.82f)
                            .align(Alignment.Center),
                    )
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(Brush.verticalGradient(0.4f to Color.Transparent, 1f to Color(0xCC000000))),
                    )
                    Text(
                        title.uppercase(),
                        color = Color.White,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        style = TextStyle(fontFamily = YtFonts.anton, fontSize = 22.sp, lineHeight = 24.sp, letterSpacing = 0.5.sp),
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(start = 14.dp, end = 14.dp, bottom = 16.dp),
                    )
                    Box(
                        Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth()
                            .height(3.dp)
                            .background(Color(0x40FFFFFF))
                            .drawBehind { drawRect(palette.accent, size = size.copy(width = size.width * fill.value)) },
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                    Text(channel.uppercase(), color = palette.textSecondary, maxLines = 1, style = TextStyle(fontFamily = YtFonts.robotoCondensed, fontWeight = FontWeight.Bold, fontSize = 11.5.sp, letterSpacing = 1.5.sp), modifier = Modifier.weight(1f))
                    remaining?.let {
                        Text(stringResource(R.string.yt_library_remaining, it), color = palette.text, style = TextStyle(fontFamily = YtFonts.robotoCondensed, fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 1.sp))
                    }
                }
            }
        }
        else -> {
            // YouTube neu, Movo, Side: the still with a play button and the progress bar in the accent
            Column(
                Modifier
                    .padding(horizontal = 12.dp)
                    .ytPressable(RoundedCornerShape(12.dp)) { onOpen(item) },
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(3f)
                        .clip(RoundedCornerShape(12.dp)),
                ) {
                    AsyncImage(
                        model = item.video.thumbnail, contentDescription = null, imageLoader = imageLoader, contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(end = 12.dp, bottom = 24.dp)
                            .size(37.dp)
                            .clip(CircleShape)
                            .background(Color(0xCC111114)),
                    ) {
                        Icon(painterResource(R.drawable.ic_play), contentDescription = null, tint = Color.White, modifier = Modifier.size(17.dp))
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(start = 12.dp, end = 10.dp, bottom = 5.dp),
                    ) {
                        Box(
                            Modifier
                                .weight(1f)
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(Color(0x66FFFFFF))
                                .drawBehind { drawRect(palette.accent, size = size.copy(width = size.width * fill.value.coerceIn(0f, 1f))) },
                        )
                        remaining?.let {
                            Text(
                                text = stringResource(R.string.yt_library_remaining, it),
                                color = Color.White,
                                style = TextStyle(fontFamily = palette.font, fontWeight = FontWeight.Medium, fontSize = 13.sp),
                                modifier = Modifier.padding(start = 30.dp),
                            )
                        }
                    }
                }
                Text(title, color = palette.text, maxLines = 2, overflow = TextOverflow.Ellipsis, style = TextStyle(fontFamily = palette.font, fontWeight = FontWeight.SemiBold, fontSize = 14.5.sp, lineHeight = 19.sp), modifier = Modifier.padding(start = 3.dp, top = 8.dp))
                Text(channel, color = palette.textSecondary, style = TextStyle(fontFamily = palette.font, fontSize = 12.5.sp), modifier = Modifier.padding(start = 3.dp, top = 1.dp))
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Collections

/** Up to three previews of a collection in a row, [shape] each. */
@Composable
private fun Previews(imageLoader: ImageLoader, collection: YtCollection, shape: Shape, empty: Color, modifier: Modifier = Modifier) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = modifier) {
        collection.previews.take(3).forEach { url ->
            AsyncImage(
                model = url,
                contentDescription = null,
                imageLoader = imageLoader,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .weight(1f)
                    .aspectRatio(1f)
                    .clip(shape),
            )
        }
        repeat((3 - collection.previews.size).coerceAtLeast(0)) {
            Box(
                Modifier
                    .weight(1f)
                    .aspectRatio(1f)
                    .clip(shape)
                    .background(empty),
            )
        }
    }
}

@Composable
private fun countOf(collection: YtCollection): String =
    pluralStringResource(R.plurals.yt_videos_count, collection.videoCount.toInt(), collection.videoCount.toInt())

/**
 * A collection card of the design; [index] is its place in the grid (for alternating colors
 * and tilts). Editorial lays its collections out as numbered rows instead, see [LibraryCollectionRow].
 */
@Composable
internal fun LibraryCollection(
    palette: YtPalette,
    imageLoader: ImageLoader,
    collection: YtCollection,
    index: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val name = collection.name
    when (palette.signature) {
        YtSignature.CLAY -> {
            val press = remember { MutableInteractionSource() }
            val shape = RoundedCornerShape(26.dp)
            Column(
                modifier
                    .ytSquish(press)
                    .shadow(8.dp, shape, ambientColor = ClayShadow, spotColor = ClayShadow)
                    .clip(shape)
                    .background(Brush.verticalGradient(if (index % 2 == 0) listOf(ClayLight, ClayDeep) else listOf(ytDayNight(Color(0xFFB07A7C), Color(0xFFE0B0B1)), ClayMauve)))
                    .border(1.dp, ytDayNight(Color(0x99FFFFFF), Color(0x26FFF0E4)), shape)
                    .clickable(interactionSource = press, indication = LocalIndication.current, onClick = onClick)
                    .padding(12.dp),
            ) {
                val ink = if (index % 2 == 0) ClayInk else ClayLight
                Text(name, color = ink, maxLines = 2, overflow = TextOverflow.Ellipsis, style = TextStyle(fontFamily = YtFonts.fraunces, fontWeight = FontWeight.ExtraBold, fontSize = 22.sp, lineHeight = 24.sp))
                Text(countOf(collection), color = ink.copy(alpha = 0.75f), style = TextStyle(fontFamily = palette.font, fontSize = 12.5.sp), modifier = Modifier.padding(top = 2.dp))
                Spacer(Modifier.weight(1f))
                Previews(imageLoader, collection, CircleShape, Color(0x33FFFFFF))
            }
        }
        YtSignature.GLASS -> {
            val backdrop = LocalScreenBackdrop.current
            val press = remember { MutableInteractionSource() }
            val swell by rememberSwell(press)
            val shape = RoundedCornerShape(18.dp)
            Column(
                modifier
                    .then(
                        if (backdrop != null) Modifier.liquidGlass(backdrop, shape, ytDayNight(Color(0x80FFFFFF), Color(0x33A8E0D6)), GlassShadow, swell = { swell })
                        else Modifier.ytGlass(shape),
                    )
                    .clickable(interactionSource = press, indication = LocalIndication.current, onClick = onClick)
                    .padding(12.dp),
            ) {
                Text(name, color = palette.text, maxLines = 2, overflow = TextOverflow.Ellipsis, style = TextStyle(fontFamily = YtFonts.googleSans, fontWeight = FontWeight.Bold, fontSize = 21.sp, lineHeight = 24.sp))
                Text(countOf(collection), color = palette.textSecondary, style = TextStyle(fontFamily = YtFonts.googleSans, fontSize = 12.5.sp), modifier = Modifier.padding(top = 2.dp))
                Spacer(Modifier.weight(1f))
                Previews(imageLoader, collection, RoundedCornerShape(8.dp), Color(0x40FFFFFF))
            }
        }
        YtSignature.POP -> {
            // a colored card a little askew; it straightens while pressed and wobbles on a tap
            val press = remember { MutableInteractionSource() }
            val pressed by press.collectIsPressedAsState()
            val askew = if (index % 2 == 0) -2f else 2f
            val tilt by animateFloatAsState(if (pressed) 0f else askew, ytSpec(spring(dampingRatio = 0.5f, stiffness = 260f)), label = "popTilt")
            Column(
                modifier
                    .graphicsLayer { rotationZ = tilt }
                    .ytSquish(press)
                    .clip(RoundedCornerShape(26.dp))
                    .background(PopFrames[index % PopFrames.size])
                    .clickable(interactionSource = press, indication = null, onClick = onClick)
                    .padding(12.dp),
            ) {
                Text(name, color = PopInk, maxLines = 2, overflow = TextOverflow.Ellipsis, style = TextStyle(fontFamily = YtFonts.nunito, fontWeight = FontWeight.Black, fontSize = 24.sp, lineHeight = 26.sp))
                Text(countOf(collection), color = PopInk.copy(alpha = 0.7f), style = TextStyle(fontFamily = YtFonts.nunito, fontWeight = FontWeight.Bold, fontSize = 12.5.sp), modifier = Modifier.padding(top = 2.dp))
                Spacer(Modifier.weight(1f))
                Previews(imageLoader, collection, BlobShape(0.6f, 0.5f, 0.56f, 0.64f), Color(0x33000000))
            }
        }
        YtSignature.SHAPES -> {
            val shape: Shape = if (index % 2 == 0) {
                RoundedCornerShape(topStart = 44.dp, topEnd = 16.dp, bottomEnd = 44.dp, bottomStart = 16.dp)
            } else {
                RoundedCornerShape(topStart = 16.dp, topEnd = 44.dp, bottomEnd = 16.dp, bottomStart = 44.dp)
            }
            val tag = ShapesTags[index % ShapesTags.size]
            Column(
                modifier
                    .ytPressable(shape, onClick = onClick)
                    .background(tag)
                    .padding(14.dp),
            ) {
                Text(name, color = ShapesInk, maxLines = 2, overflow = TextOverflow.Ellipsis, style = TextStyle(fontFamily = YtFonts.googleSans, fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 24.sp))
                Text(countOf(collection), color = ShapesInk.copy(alpha = 0.7f), style = TextStyle(fontFamily = YtFonts.googleSans, fontSize = 12.5.sp), modifier = Modifier.padding(top = 2.dp))
                Spacer(Modifier.weight(1f))
                Previews(imageLoader, collection, CookieShape(8, 0.06f), Color(0x33000000))
            }
        }
        YtSignature.FREQUENCY -> {
            // Formen's alternating widget corners in aqua and violet, YouTube neu's heavy type
            val shape: Shape = if (index % 2 == 0) {
                RoundedCornerShape(topStart = 36.dp, topEnd = 14.dp, bottomEnd = 36.dp, bottomStart = 14.dp)
            } else {
                RoundedCornerShape(topStart = 14.dp, topEnd = 36.dp, bottomEnd = 14.dp, bottomStart = 36.dp)
            }
            Column(
                modifier
                    .ytPressable(shape, onClick = onClick)
                    .background(if (index % 2 == 0) palette.lime else palette.lavender)
                    .padding(14.dp),
            ) {
                Text(name, color = palette.ink, maxLines = 2, overflow = TextOverflow.Ellipsis, style = TextStyle(fontFamily = YtFonts.geist, fontWeight = FontWeight.Black, fontSize = 24.sp, lineHeight = 26.sp, letterSpacing = (-0.4).sp))
                Text(countOf(collection), color = palette.ink, style = TextStyle(fontFamily = YtFonts.geist, fontSize = 12.5.sp), modifier = Modifier.padding(top = 2.dp))
                Spacer(Modifier.weight(1f))
                Previews(imageLoader, collection, CookieShape(8, 0.06f), Color(0x33000000))
            }
        }
        YtSignature.FROST -> {
            val backdrop = LocalScreenBackdrop.current
            val press = remember { MutableInteractionSource() }
            val thaw by rememberThaw(press)
            Column(
                modifier
                    .frostGlass(backdrop, RoundedCornerShape(22.dp)) { thaw }
                    .clickable(interactionSource = press, indication = LocalIndication.current, onClick = onClick)
                    .padding(12.dp),
            ) {
                Text(name, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis, style = TextStyle(fontFamily = YtFonts.jakarta, fontWeight = FontWeight.ExtraBold, fontSize = 21.sp, lineHeight = 24.sp))
                Text(countOf(collection), color = palette.textSecondary, style = TextStyle(fontFamily = YtFonts.jakarta, fontSize = 12.5.sp), modifier = Modifier.padding(top = 2.dp))
                Spacer(Modifier.weight(1f))
                Previews(imageLoader, collection, RoundedCornerShape(10.dp), Color(0x33FFFFFF))
            }
        }
        YtSignature.KIESEL -> {
            val colors = listOf(KieselLavender, KieselPeach, KieselBeige)
            Column(
                modifier
                    .ytPressable(RoundedCornerShape(20.dp), squish = true, onClick = onClick)
                    .background(colors[index % colors.size])
                    .padding(12.dp),
            ) {
                Text(name, color = kieselInkOn(colors[index % colors.size]), maxLines = 2, overflow = TextOverflow.Ellipsis, style = TextStyle(fontFamily = YtFonts.jakarta, fontWeight = FontWeight.ExtraBold, fontSize = 21.sp, lineHeight = 24.sp))
                Text(
                    countOf(collection),
                    color = KieselCream,
                    style = TextStyle(fontFamily = YtFonts.jakarta, fontWeight = FontWeight.Bold, fontSize = 11.sp),
                    modifier = Modifier
                        .padding(top = 6.dp)
                        .clip(RoundedCornerShape(50))
                        .background(KieselPanel)
                        .padding(horizontal = 9.dp, vertical = 3.dp),
                )
                Spacer(Modifier.weight(1f))
                Previews(imageLoader, collection, RoundedCornerShape(50), Color(0x33000000))
            }
        }
        YtSignature.AFTER -> {
            // a poster: the first preview fills the card, the name in Anton over it
            Box(
                modifier
                    .ytPressable(RoundedCornerShape(4.dp), onClick = onClick)
                    .background(palette.surface),
            ) {
                collection.previews.firstOrNull()?.let {
                    AsyncImage(model = it, contentDescription = null, imageLoader = imageLoader, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().graphicsLayer { alpha = 0.75f })
                }
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Brush.verticalGradient(0.3f to Color.Transparent, 1f to Color(0xD9000000))),
                )
                Column(
                    Modifier
                        .align(Alignment.BottomStart)
                        .padding(12.dp),
                ) {
                    Text(name.uppercase(), color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis, style = TextStyle(fontFamily = YtFonts.anton, fontSize = 22.sp, lineHeight = 24.sp, letterSpacing = 0.5.sp))
                    Text(countOf(collection).uppercase(), color = palette.accent, style = TextStyle(fontFamily = YtFonts.robotoCondensed, fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = 1.5.sp), modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
        else -> {
            // YouTube neu, Movo, Side: lime and lavender cards with the name in heavy type
            val color = if (index % 2 == 0) palette.lime else palette.lavender
            Column(
                modifier
                    .ytPressable(RoundedCornerShape(14.dp), onClick = onClick)
                    .background(color)
                    .padding(start = 9.dp, end = 9.dp, top = 12.dp, bottom = 9.dp),
            ) {
                Row(verticalAlignment = Alignment.Top, modifier = Modifier.padding(start = 5.dp)) {
                    Text(
                        text = name,
                        color = palette.ink,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        style = TextStyle(fontFamily = palette.font, fontWeight = FontWeight.Black, fontSize = 28.sp, lineHeight = 30.sp, letterSpacing = (-0.5).sp),
                        modifier = Modifier.weight(1f),
                    )
                    Icon(painterResource(R.drawable.ic_arrow_right), contentDescription = null, tint = palette.ink, modifier = Modifier.size(20.dp))
                }
                Text(countOf(collection), color = palette.ink, style = TextStyle(fontFamily = palette.font, fontSize = 13.5.sp), modifier = Modifier.padding(start = 5.dp, top = 2.dp))
                Spacer(Modifier.weight(1f))
                Previews(imageLoader, collection, RoundedCornerShape(6.dp), Color(0x1F000000))
            }
        }
    }
}

/** Editorial: a collection as a numbered row of a table, with a strip of previews in black and white. */
@Composable
internal fun LibraryCollectionRow(palette: YtPalette, imageLoader: ImageLoader, collection: YtCollection, index: Int, onClick: () -> Unit) {
    val press = remember { MutableInteractionSource() }
    val pressed by press.collectIsPressedAsState()
    val fill by animateColorAsState(if (pressed) palette.text else Color.Transparent, ytSpec(tween(120)), label = "rowFill")
    val ink by animateColorAsState(if (pressed) palette.background else palette.text, ytSpec(tween(120)), label = "rowInk")
    Column(Modifier.padding(horizontal = 15.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .drawBehind { drawRect(fill) }
                .clickable(interactionSource = press, indication = null, onClick = onClick)
                .padding(vertical = 10.dp, horizontal = 4.dp),
        ) {
            Text(
                "%02d".format(index + 1),
                color = ink,
                style = TextStyle(fontFamily = YtFonts.plexMono, fontWeight = FontWeight.Medium, fontSize = 11.sp, letterSpacing = 1.sp),
                modifier = Modifier.width(30.dp),
            )
            Column(Modifier.weight(1f)) {
                Text(collection.name, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(fontFamily = YtFonts.inter, fontWeight = FontWeight.SemiBold, fontSize = 16.sp))
                Text(countOf(collection).uppercase(), color = ink.copy(alpha = 0.7f), style = TextStyle(fontFamily = YtFonts.plexMono, fontWeight = FontWeight.Medium, fontSize = 9.5.sp, letterSpacing = 1.5.sp), modifier = Modifier.padding(top = 2.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.padding(start = 10.dp)) {
                collection.previews.take(3).forEach { url ->
                    AsyncImage(
                        model = url,
                        contentDescription = null,
                        imageLoader = imageLoader,
                        contentScale = ContentScale.Crop,
                        colorFilter = if (pressed) null else ytGrayscale,
                        modifier = Modifier.size(34.dp),
                    )
                }
            }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(palette.text.copy(alpha = 0.3f)),
        )
    }
}

/** The small "new collection" button of the design. */
@Composable
internal fun LibraryAdd(palette: YtPalette, onClick: () -> Unit) {
    val haptics = rememberYtHaptics()
    val label = stringResource(R.string.createPlaylist)
    when (palette.signature) {
        YtSignature.EDITORIAL -> Text(
            text = "+ " + label.uppercase(),
            color = palette.text,
            style = TextStyle(fontFamily = YtFonts.plexMono, fontWeight = FontWeight.Medium, fontSize = 10.sp, letterSpacing = 1.5.sp),
            modifier = Modifier
                .clickable {
                    haptics.tick()
                    onClick()
                }
                .drawBehind { drawLine(palette.text, Offset(0f, size.height), Offset(size.width, size.height), 1.5.dp.toPx(), StrokeCap.Square) }
                .padding(vertical = 4.dp),
        )
        else -> {
            val fill = when (palette.signature) {
                YtSignature.POP -> PopFrames[0]
                YtSignature.SHAPES -> palette.lime
                YtSignature.FREQUENCY -> palette.accent
                YtSignature.KIESEL -> KieselPanel
                YtSignature.CLAY -> ClayLight
                else -> palette.raised
            }
            val ink = when (palette.signature) {
                YtSignature.POP -> PopInk
                YtSignature.SHAPES -> ShapesInk
                YtSignature.FREQUENCY -> palette.onAccent
                YtSignature.KIESEL -> KieselCream
                YtSignature.CLAY -> ClayInk
                YtSignature.FROST -> Color.White
                else -> palette.text
            }
            val backdrop = LocalScreenBackdrop.current
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(if (palette.signature == YtSignature.CLAY || palette.signature == YtSignature.POP) 40.dp else 32.dp)
                    .ytPressable(if (palette.signature == YtSignature.POP) BlobShape(0.6f, 0.5f, 0.56f, 0.64f) else CircleShape, squish = palette.signature == YtSignature.CLAY || palette.signature == YtSignature.KIESEL) {
                        haptics.tick()
                        onClick()
                    }
                    .then(
                        when (palette.signature) {
                            YtSignature.GLASS, YtSignature.FROST -> if (palette.signature == YtSignature.FROST) Modifier.frostGlass(backdrop, CircleShape) else if (backdrop != null) Modifier.liquidGlass(backdrop, CircleShape, ytDayNight(Color(0x99FFFFFF), Color(0x336FB5AA)), GlassShadow) else Modifier.ytGlass(CircleShape)
                            YtSignature.CLAY -> Modifier.shadow(6.dp, CircleShape, ambientColor = ClayShadow, spotColor = ClayShadow).background(Brush.verticalGradient(listOf(ClayLight, ClayDeep))).border(1.dp, ytDayNight(Color(0x99FFFFFF), Color(0x26FFF0E4)), CircleShape)
                            else -> Modifier.background(fill)
                        },
                    ),
            ) {
                Icon(painterResource(R.drawable.ic_add), contentDescription = label, tint = ink, modifier = Modifier.size(20.dp))
            }
        }
    }
}
