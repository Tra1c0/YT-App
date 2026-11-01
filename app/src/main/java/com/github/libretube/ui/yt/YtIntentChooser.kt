package com.github.libretube.ui.yt

import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.libretube.R
import com.github.libretube.helpers.ContextHelper
import com.github.libretube.ui.yt.feed.YtFeed
import com.github.libretube.ui.yt.feed.YtIntent
import kotlinx.coroutines.delay

/** The words and glyph of an intent. */
@StringRes
fun YtIntent.label(): Int = when (this) {
    YtIntent.LEARN -> R.string.yt_intent_learn
    YtIntent.MUSIC -> R.string.yt_intent_music
    YtIntent.SUBSCRIPTIONS -> R.string.yt_intent_subscriptions
    YtIntent.BROWSE -> R.string.yt_intent_browse
}

@StringRes
fun YtIntent.shortLabel(): Int = when (this) {
    YtIntent.LEARN -> R.string.yt_intent_learn_short
    YtIntent.MUSIC -> R.string.yt_intent_music_short
    YtIntent.SUBSCRIPTIONS -> R.string.yt_intent_subscriptions_short
    YtIntent.BROWSE -> R.string.yt_intent_browse_short
}

@StringRes
fun YtIntent.hint(): Int = when (this) {
    YtIntent.LEARN -> R.string.yt_intent_learn_hint
    YtIntent.MUSIC -> R.string.yt_intent_music_hint
    YtIntent.SUBSCRIPTIONS -> R.string.yt_intent_subscriptions_hint
    YtIntent.BROWSE -> R.string.yt_intent_browse_hint
}

@DrawableRes
fun YtIntent.icon(): Int = when (this) {
    YtIntent.LEARN -> R.drawable.ic_yt_intent_learn
    YtIntent.MUSIC -> R.drawable.ic_yt_intent_music
    YtIntent.SUBSCRIPTIONS -> R.drawable.ic_yt_nav_subs
    YtIntent.BROWSE -> R.drawable.ic_yt_intent_browse
}

/** Takes the back gesture while [enabled], for the overlays of the home (chooser, sheets). */
@Composable
internal fun YtBackHandler(enabled: Boolean, onBack: () -> Unit) {
    val context = LocalContext.current
    val current by rememberUpdatedState(onBack)
    val callback = remember {
        object : OnBackPressedCallback(enabled) {
            override fun handleOnBackPressed() = current()
        }
    }
    LaunchedEffect(enabled) { callback.isEnabled = enabled }
    DisposableEffect(context) {
        ContextHelper.tryUnwrapActivity<ComponentActivity>(context)?.onBackPressedDispatcher?.addCallback(callback)
        onDispose { callback.remove() }
    }
}

/** How one design draws the four choices. */
private class ChoiceLook(
    val shape: Shape,
    /** Fill per choice (LEARN, MUSIC, SUBSCRIPTIONS, BROWSE). */
    val fills: List<Color>,
    val inks: List<Color>,
    val font: FontFamily,
    val titleFont: FontFamily,
    val titleSize: TextUnit,
    val titleColor: Color,
    val scrim: Color,
    /** Two by two tiles instead of rows. */
    val grid: Boolean,
    val uppercase: Boolean = false,
    val letterSpacing: TextUnit = 0.sp,
    /** Editorial: numbered rows on black rules. */
    val numbered: Boolean = false,
    val border: Color? = null,
    /** A surface of the design's own (glass, clay, frost) instead of the flat fill. */
    val surface: ((Modifier, Shape) -> Modifier)? = null,
)

private fun choiceLook(palette: YtPalette): ChoiceLook {
    val four = { a: Color -> listOf(a, a, a, a) }
    return when (palette.signature) {
        YtSignature.YOUTUBE -> ChoiceLook(
            shape = RoundedCornerShape(16.dp), fills = four(palette.raised), inks = four(palette.text),
            font = YtFonts.geist, titleFont = YtFonts.geist, titleSize = 34.sp, titleColor = palette.text,
            scrim = palette.background.copy(alpha = 0.96f), grid = false,
        )
        YtSignature.MOVO -> ChoiceLook(
            shape = RoundedCornerShape(26.dp),
            fills = listOf(palette.accent, palette.pink, palette.lime, palette.lavender),
            inks = listOf(palette.onAccent, palette.ink, palette.ink, palette.ink),
            font = YtFonts.geist, titleFont = YtFonts.geist, titleSize = 42.sp, titleColor = palette.text,
            scrim = palette.background.copy(alpha = 0.97f), grid = true,
        )
        YtSignature.AFTER -> ChoiceLook(
            shape = RoundedCornerShape(6.dp), fills = four(palette.raised), inks = four(palette.text),
            font = YtFonts.archivoNarrow, titleFont = YtFonts.fraunces, titleSize = 34.sp, titleColor = palette.text,
            scrim = palette.background.copy(alpha = 0.96f), grid = false, uppercase = true, letterSpacing = 1.sp,
            border = palette.accent.copy(alpha = 0.6f),
        )
        YtSignature.SIDE -> ChoiceLook(
            shape = RoundedCornerShape(22.dp), fills = four(palette.raised), inks = four(palette.text),
            font = YtFonts.figtree, titleFont = YtFonts.figtree, titleSize = 32.sp, titleColor = palette.text,
            scrim = palette.background.copy(alpha = 0.97f), grid = false,
        )
        YtSignature.CLAY -> ChoiceLook(
            shape = RoundedCornerShape(30.dp), fills = four(palette.surface), inks = four(palette.text),
            font = YtFonts.nunito, titleFont = YtFonts.nunito, titleSize = 32.sp, titleColor = palette.text,
            scrim = palette.background.copy(alpha = 0.95f), grid = true,
            surface = { m, shape -> m.ytSurface(palette, shape) },
        )
        YtSignature.EDITORIAL -> ChoiceLook(
            shape = RectangleShape, fills = four(Color.Transparent), inks = four(palette.text),
            font = YtFonts.inter, titleFont = YtFonts.inter, titleSize = 36.sp, titleColor = palette.text,
            scrim = palette.background.copy(alpha = 0.98f), grid = false, numbered = true,
        )
        YtSignature.GLASS -> ChoiceLook(
            shape = RoundedCornerShape(26.dp), fills = four(Color.Transparent), inks = four(palette.text),
            font = YtFonts.googleSans, titleFont = YtFonts.googleSans, titleSize = 32.sp, titleColor = palette.text,
            scrim = palette.background.copy(alpha = 0.9f), grid = true,
            surface = { m, shape -> m.ytGlass(shape, strong = true) },
        )
        YtSignature.POP -> ChoiceLook(
            shape = BlobShape(0.56f, 0.5f, 0.5f, 0.6f),
            fills = listOf(palette.lime, palette.pink, palette.lavender, palette.accent),
            inks = listOf(palette.ink, Color.White, palette.ink, Color.White),
            font = YtFonts.nunito, titleFont = YtFonts.nunito, titleSize = 34.sp, titleColor = palette.text,
            scrim = palette.background.copy(alpha = 0.96f), grid = true,
        )
        YtSignature.SHAPES -> ChoiceLook(
            shape = CookieShape(9, 0.05f),
            fills = listOf(palette.lime, Color(0xFFF7C4D0), palette.lavender, palette.surface),
            inks = four(palette.ink),
            font = YtFonts.googleSans, titleFont = YtFonts.googleSans, titleSize = 32.sp, titleColor = palette.text,
            scrim = palette.background.copy(alpha = 0.96f), grid = true,
        )
        YtSignature.FROST -> ChoiceLook(
            shape = RoundedCornerShape(24.dp), fills = four(Color.Transparent), inks = four(palette.text),
            font = YtFonts.jakarta, titleFont = YtFonts.jakarta, titleSize = 32.sp, titleColor = palette.text,
            scrim = palette.background.copy(alpha = 0.93f), grid = false,
            surface = { m, shape -> m.ytSurface(palette, shape) },
        )
        YtSignature.KIESEL -> ChoiceLook(
            shape = RoundedCornerShape(50),
            fills = listOf(KieselInk, KieselLavender, palette.lime, KieselInk),
            inks = listOf(KieselCream, KieselInk, KieselInk, KieselCream),
            font = YtFonts.jakarta, titleFont = YtFonts.jakarta, titleSize = 32.sp, titleColor = KieselInk,
            scrim = palette.background.copy(alpha = 0.97f), grid = false,
        )
    }
}

/**
 * YT: "Worauf hast du Lust?" over the home, in the design's own language: four big choices
 * (learn, music, subscriptions, browse) and the minutes row of the time box. The choices come
 * in one after the other on a spring; picking one sets the home's rules and lets it go.
 * "Jetzt nicht" and back close it without a change.
 */
@Composable
internal fun YtIntentChooser(palette: YtPalette, visible: Boolean, timeBox: (() -> YtTimeBoxSource)?, onClose: () -> Unit) {
    YtBackHandler(visible, onClose)
    AnimatedVisibility(
        visible = visible,
        enter = ytEnterTransition(fadeIn(tween(200))),
        exit = ytExitTransition(fadeOut(tween(220)) + scaleOut(tween(220, easing = YtEaseOut), targetScale = 1.04f)),
    ) {
        val look = remember(palette) { choiceLook(palette) }
        val current by YtFeed.intent.collectAsState()
        val context = LocalContext.current
        val haptics = rememberYtHaptics()
        Box(
            Modifier
                .testTag("yt_intent_chooser")
                .fillMaxSize()
                .background(look.scrim)
                // the home below takes no touches while the chooser shows
                .clickable(interactionSource = null, indication = null) {},
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 24.dp + ytNavPadding()),
            ) {
                Text(
                    text = stringResource(R.string.yt_intent_title).let { if (look.uppercase) it.uppercase() else it },
                    color = look.titleColor,
                    style = TextStyle(
                        fontFamily = look.titleFont,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = look.titleSize,
                        lineHeight = look.titleSize * 1.02f,
                        letterSpacing = if (look.numbered) (-0.8).sp else (-0.5).sp,
                    ),
                    modifier = Modifier.semantics { heading() },
                )
                Text(
                    text = stringResource(R.string.yt_intent_subtitle),
                    color = palette.textSecondary,
                    style = TextStyle(fontFamily = look.font, fontSize = 14.sp, lineHeight = 19.sp),
                    modifier = Modifier.padding(top = 8.dp, bottom = 20.dp),
                )
                val pick: (YtIntent) -> Unit = { intent ->
                    haptics.confirm()
                    YtFeed.setIntent(intent)
                    onClose()
                }
                val intents = YtIntent.entries
                if (look.grid) {
                    intents.chunked(2).forEachIndexed { row, pair ->
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(bottom = 10.dp)) {
                            pair.forEachIndexed { col, intent ->
                                val i = row * 2 + col
                                ChoiceTile(look, intent, i, intent == current, grid = true, Modifier.weight(1f)) { pick(intent) }
                            }
                        }
                    }
                } else {
                    intents.forEachIndexed { i, intent ->
                        ChoiceTile(look, intent, i, intent == current, grid = false, Modifier.fillMaxWidth().padding(bottom = if (look.numbered) 0.dp else 10.dp)) { pick(intent) }
                    }
                }
                if (timeBox != null && YtTimeBoxPrefs.chip) {
                    Text(
                        text = stringResource(R.string.yt_intent_minutes).let { if (look.uppercase) it.uppercase() else it },
                        color = palette.text,
                        style = TextStyle(fontFamily = look.font, fontWeight = FontWeight.Bold, fontSize = 15.sp, letterSpacing = look.letterSpacing),
                        modifier = Modifier.padding(top = 18.dp, bottom = 10.dp),
                    )
                    YtMinutesRow(palette, onPick = { minutes ->
                        YtTimeBoxStarter.start(context, minutes, timeBox(), onEmpty = {})
                        onClose()
                    })
                }
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .padding(top = 18.dp)
                        .testTag("yt_intent_skip")
                        .heightIn(min = 48.dp)
                        .clip(RoundedCornerShape(50))
                        .clickable(onClick = onClose)
                        .padding(horizontal = 12.dp),
                ) {
                    Text(
                        text = stringResource(R.string.yt_intent_skip).let { if (look.uppercase) it.uppercase() else it },
                        color = palette.textSecondary,
                        style = TextStyle(
                            fontFamily = look.font,
                            fontWeight = FontWeight.Medium,
                            fontSize = 14.sp,
                            textDecoration = if (look.numbered) TextDecoration.Underline else null,
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun ChoiceTile(look: ChoiceLook, intent: YtIntent, index: Int, current: Boolean, grid: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val motion = LocalYtMotion.current
    // each choice rises in a little after the one before
    val rise = remember { Animatable(if (motion) 0f else 1f) }
    LaunchedEffect(Unit) {
        if (!motion) return@LaunchedEffect
        delay(50L * index)
        rise.animateTo(1f, spring(dampingRatio = 0.72f, stiffness = 380f))
    }
    val label = stringResource(intent.label())
    val hint = stringResource(intent.hint())
    val ink = look.inks[index]
    val interaction = remember { MutableInteractionSource() }
    val base = modifier
        .testTag("yt_intent_" + intent.name.lowercase())
        .graphicsLayer {
            val r = rise.value
            alpha = r.coerceIn(0f, 1f)
            translationY = (1f - r) * 36.dp.toPx()
            val s = 0.94f + 0.06f * r
            scaleX = s
            scaleY = s
        }
        .ytPressScale(interaction)
        .semantics(mergeDescendants = true) {
            contentDescription = "$label. $hint"
            role = Role.Button
        }
    if (look.numbered) {
        // Editorial: "01  Ich will etwas lernen" between black rules
        Column(base.clickable(interactionSource = interaction, indication = LocalIndication.current, onClick = onClick)) {
            Box(Modifier.fillMaxWidth().height(if (index == 0) 2.dp else 1.dp).background(look.inks[0]))
            Row(verticalAlignment = Alignment.Top, modifier = Modifier.padding(vertical = 16.dp)) {
                Text(
                    text = "%02d".format(index + 1),
                    color = if (current) YtPalettes.Editorial.accent else ink,
                    style = TextStyle(fontFamily = YtFonts.plexMono, fontSize = 12.sp, letterSpacing = 1.sp),
                    modifier = Modifier.padding(top = 5.dp).widthIn(min = 34.dp),
                )
                Column {
                    Text(
                        text = label,
                        color = ink,
                        style = TextStyle(fontFamily = look.font, fontWeight = FontWeight.Bold, fontSize = 22.sp, letterSpacing = (-0.4).sp),
                    )
                    Text(
                        text = hint.uppercase(),
                        color = ink.copy(alpha = 0.6f),
                        style = TextStyle(fontFamily = YtFonts.plexMono, fontSize = 10.5.sp, letterSpacing = 1.sp),
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
        return
    }
    val surface = look.surface
    val filled = (if (surface != null) surface(Modifier, look.shape) else Modifier.shadow(if (look.fills[index].alpha > 0f) 4.dp else 0.dp, look.shape).clip(look.shape).background(look.fills[index]))
        .then(if (look.border != null) Modifier.border(1.dp, look.border, look.shape) else Modifier)
        .then(if (current) Modifier.border(2.dp, ink.copy(alpha = 0.85f), look.shape) else Modifier)
    val text = if (look.uppercase) label.uppercase() else label
    if (grid) {
        Column(
            verticalArrangement = Arrangement.SpaceBetween,
            modifier = base
                .then(filled)
                .clickable(interactionSource = interaction, indication = LocalIndication.current, onClick = onClick)
                .heightIn(min = 148.dp)
                .padding(horizontal = 18.dp, vertical = 18.dp),
        ) {
            Icon(painterResource(intent.icon()), contentDescription = null, tint = ink, modifier = Modifier.size(28.dp))
            Column {
                Text(
                    text = text,
                    color = ink,
                    style = TextStyle(fontFamily = look.font, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp, lineHeight = 21.sp, letterSpacing = look.letterSpacing),
                    modifier = Modifier.padding(top = 18.dp),
                )
                Text(
                    text = hint,
                    color = ink.copy(alpha = 0.72f),
                    style = TextStyle(fontFamily = look.font, fontSize = 12.sp, lineHeight = 15.sp),
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    } else {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = base
                .then(filled)
                .clickable(interactionSource = interaction, indication = LocalIndication.current, onClick = onClick)
                .heightIn(min = 72.dp)
                .padding(horizontal = 18.dp, vertical = 14.dp),
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(ink.copy(alpha = 0.12f)),
            ) {
                Icon(painterResource(intent.icon()), contentDescription = null, tint = ink, modifier = Modifier.size(22.dp))
            }
            Column(Modifier.padding(start = 14.dp)) {
                Text(
                    text = text,
                    color = ink,
                    style = TextStyle(fontFamily = look.font, fontWeight = FontWeight.Bold, fontSize = 17.sp, letterSpacing = look.letterSpacing),
                )
                Text(
                    text = hint,
                    color = ink.copy(alpha = 0.7f),
                    style = TextStyle(fontFamily = look.font, fontSize = 12.5.sp),
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

/**
 * The intent on the home: a small chip in the pill look with the intent's glyph and short name;
 * tapped, the chooser opens again.
 */
@Composable
internal fun YtIntentChip(look: PillLook, modifier: Modifier = Modifier) {
    val intent by YtFeed.intent.collectAsState()
    val shown = intent ?: return
    val haptics = rememberYtHaptics()
    val name = stringResource(shown.shortLabel())
    val description = stringResource(R.string.yt_intent_chip, name)
    val interaction = remember { MutableInteractionSource() }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .testTag("yt_intent_chip")
            .ytPressScale(interaction)
            .then(look.surface(Modifier))
            .clickable(interactionSource = interaction, indication = LocalIndication.current) {
                haptics.tick()
                YtFeedUi.chooser = true
            }
            .semantics(mergeDescendants = true) {
                contentDescription = description
                role = Role.Button
            }
            .heightIn(min = 48.dp)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Icon(painterResource(shown.icon()), contentDescription = null, tint = look.text, modifier = Modifier.size(20.dp))
        Text(
            text = if (look.uppercase) name.uppercase() else name,
            color = look.text,
            maxLines = 1,
            style = look.style(FontWeight.Medium),
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

/** YT: what the overlays of the home show; the home's layer ([YtFeedLayer]) draws them. */
object YtFeedUi {
    /** "Worauf hast du Lust?" shows. */
    var chooser by mutableStateOf(false)

    /** The Feed-Regler shows. */
    var sheet by mutableStateOf(false)

    /** "Warum sehe ich das?" for this video (from a menu without a quick sheet). */
    var why by mutableStateOf<com.github.libretube.api.obj.StreamItem?>(null)

    fun openSheet() {
        sheet = true
        YtTestHooks.log("YtFeed") { "sheet open" }
    }
}

/**
 * The overlays of the home in one layer above the screen and its pills: the intent chooser, the
 * Feed-Regler and the "Warum sehe ich das?" card. [preview] is what the sheet's preview strip
 * ranks; [timeBox] what the chooser's minutes row plans from.
 */
@Composable
fun YtFeedLayer(palette: YtPalette, preview: () -> com.github.libretube.ui.yt.feed.YtFeedInput, timeBox: (() -> YtTimeBoxSource)?) {
    YtFeedSheet(palette, YtFeedUi.sheet, preview) { YtFeedUi.sheet = false }
    YtWhyCard(palette, YtFeedUi.why) { YtFeedUi.why = null }
    YtIntentChooser(palette, YtFeedUi.chooser, timeBox) { YtFeedUi.chooser = false }
}

/**
 * Content over a screen that has its theme further down (as YtPillLayer): the design's scaled
 * density and motion, without a second YtTheme.
 */
@Composable
fun YtOverlayLayer(palette: YtPalette, content: @Composable () -> Unit) {
    val density = ytDensity(LocalDensity.current, LocalConfiguration.current)
    val motion = remember { YtMotionPrefs.enabled }
    CompositionLocalProvider(
        LocalDensity provides density,
        LocalYtMotion provides motion,
        LocalYtMotionStyle provides palette.motion,
    ) { content() }
}
