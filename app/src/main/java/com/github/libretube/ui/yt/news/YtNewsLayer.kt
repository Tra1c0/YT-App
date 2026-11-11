package com.github.libretube.ui.yt.news

import android.content.Context
import android.content.Intent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.github.libretube.ui.yt.YtCardFrame
import kotlinx.coroutines.flow.drop
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.os.bundleOf
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.github.libretube.R
import com.github.libretube.api.obj.StreamItem
import com.github.libretube.constants.IntentData
import com.github.libretube.extensions.toID
import com.github.libretube.helpers.ContextHelper
import com.github.libretube.helpers.ImageHelper
import com.github.libretube.helpers.NavigationHelper
import com.github.libretube.helpers.YtDesign
import com.github.libretube.ui.dialogs.AddToPlaylistDialog
import com.github.libretube.ui.yt.PillLook
import com.github.libretube.ui.yt.PillRow
import com.github.libretube.ui.yt.YtBackHandler
import com.github.libretube.ui.yt.YtContrast
import com.github.libretube.ui.yt.YtDropResult
import com.github.libretube.ui.yt.YtEaseOut
import com.github.libretube.ui.yt.YtFeedUi
import com.github.libretube.ui.yt.YtFonts
import com.github.libretube.ui.yt.YtHaptics
import com.github.libretube.ui.yt.YtInk
import com.github.libretube.ui.yt.YtLargeThumbnail
import com.github.libretube.ui.yt.YtMotionPrefs
import com.github.libretube.ui.yt.YtPalette
import com.github.libretube.ui.yt.YtQueue
import com.github.libretube.ui.yt.YtQuickSheet
import com.github.libretube.ui.yt.YtSignature
import com.github.libretube.ui.yt.YtSurfaceStyle
import com.github.libretube.ui.yt.YtTestHooks
import com.github.libretube.ui.yt.YtTheme
import com.github.libretube.ui.yt.palette
import com.github.libretube.ui.yt.pillLook
import com.github.libretube.ui.yt.readableOn
import com.github.libretube.ui.yt.rememberYtHaptics
import com.github.libretube.ui.yt.ytCardShape
import com.github.libretube.ui.yt.ytDuration
import com.github.libretube.ui.yt.ytPressable
import com.github.libretube.ui.yt.ytRubberBand
import com.github.libretube.ui.yt.ytSurface
import com.github.libretube.ui.yt.ytSurfaceTones
import com.github.libretube.ui.yt.ytTitle
import com.github.libretube.ui.yt.ytVideoAge
import com.github.libretube.ui.yt.feed.YtFeed
import com.github.libretube.ui.yt.feed.YtQueueMove
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.sign

/**
 * YT: the News overlay, a full-screen window above the home, the player and the bar, in the
 * current design's palette, type and surfaces. A two-finger spread on the home opens it
 * (YtNewsGestures on the home's root): the card under the fingers becomes the window (its
 * picture, recorded by YtCardFrames, fades into the window that grows out of its bounds and
 * follows the fingers), over a gap the window grows from the fingers' centre; closing reverses
 * into the card while it still shows. The geometry is YtNewsMotion's, read in the layers only.
 * The pages recede in depth with a parallax thumbnail, a card dragged up or down tilts, and the
 * moments play the design's feel (YtNewsTextures). The switch "Animationen der Designs" off: it
 * just appears, no depth. Inside, a horizontal pager of cards (YtNewsCard) with stories-like segments at
 * the top: tap a card to watch it, swipe it up for the end of the queue, hold it for the quick
 * sheet; pinch, swipe down, back or the cross close it.
 *
 * A layer of its own in the activity's content (as YtTimeUpLayer), above the player container
 * and the flights; it takes touches only while the overlay is up.
 */
object YtNewsLayer {
    private const val TAG = "yt_news_layer"

    /** The design whose feel the moments play (YtNewsTextures); set per activity, as the palette. */
    private var design = YtDesign.STANDARD

    /** Adds the layer to [activity]'s content, once per activity, only for the designs. */
    fun attach(activity: ComponentActivity) {
        val palette = YtDesign.current().palette ?: return
        design = YtDesign.current()
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        if (content.findViewWithTag<View>(TAG) != null) return
        // a new window (a recreated activity): an overlay left open in the old one starts closed
        YtNews.reset()
        val host = Host(activity).apply {
            tag = TAG
            // above the player container (20dp), the flights (22dp) and the time box card (23dp),
            // below the drag layer (24dp, never takes touches)
            elevation = 23.5f * resources.displayMetrics.density
            outlineProvider = null
            setViewTreeLifecycleOwner(activity)
            setViewTreeSavedStateRegistryOwner(activity)
            setViewTreeViewModelStoreOwner(activity)
        }
        val layer = ComposeView(activity).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent { Layer(palette) }
        }
        host.addView(layer, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        content.addView(host, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    /** Takes every touch while the overlay is up (nothing reaches the screens below), none otherwise. */
    private class Host(context: Context) : FrameLayout(context) {
        override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
            if (!YtNews.layerUp) return false
            super.dispatchTouchEvent(ev)
            return true
        }

        override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean =
            if (YtNews.layerUp) super.dispatchGenericMotionEvent(event) else false
    }

    /** Plays the design's feel of [moment]. */
    private fun YtHaptics.news(moment: YtNewsHaptic) = play(YtNewsTextures.of(design, moment))

    @Composable
    private fun Layer(palette: YtPalette) {
        val reveal = remember { Animatable(0f) }
        // how far the window follows the fingers' travel since the claim (window px)
        val follow = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
        // whether the window goes back into its card: decided when it starts to close
        val intoCard = remember { mutableStateOf(true) }
        DisposableEffect(reveal) {
            YtNews.reveal = reveal.asState()
            onDispose { YtNews.reveal = null }
        }
        LaunchedEffect(Unit) {
            snapshotFlow { Triple(YtNews.shown, YtNews.preview, YtNews.origin) }.collectLatest { (open, preview, origin) ->
                val motion = YtMotionPrefs.enabled
                when {
                    open -> {
                        intoCard.value = true
                        launch { if (motion) follow.animateTo(Offset.Zero, spring(dampingRatio = 0.85f, stiffness = 380f)) else follow.snapTo(Offset.Zero) }
                        if (motion) reveal.animateTo(1f, spring(dampingRatio = 0.8f, stiffness = 320f)) else reveal.snapTo(1f)
                    }
                    // the card grows with the spread and follows the fingers until the pinch is far enough
                    preview > 0f -> if (motion) {
                        intoCard.value = true
                        val anchor = YtNews.anchor
                        if (anchor != null && origin != null) follow.snapTo(origin - anchor)
                        reveal.animateTo(YtNewsMotion.PREVIEW_REACH * preview, spring(dampingRatio = 1f, stiffness = 1400f))
                    }
                    else -> {
                        val source = YtNews.source
                        // back into the card if it still shows, else to the centre (the card shows again at
                        // once); a pinch that shrinks back always goes into its card, which cannot have moved
                        val bounds = source?.bounds()
                        val back = bounds != null && (!YtNews.layerUp || YtNewsMotion.stillThere(bounds, source.visible()))
                        if (!back) source?.hidden = false
                        intoCard.value = back
                        launch { if (motion) follow.animateTo(Offset.Zero, spring(dampingRatio = 1f, stiffness = 700f)) else follow.snapTo(Offset.Zero) }
                        if (motion && reveal.value > 0f) {
                            reveal.animateTo(
                                0f,
                                if (YtNews.layerUp) spring(dampingRatio = 0.9f, stiffness = 420f) else spring(dampingRatio = 1f, stiffness = 700f),
                            )
                        } else {
                            reveal.snapTo(0f)
                        }
                        follow.snapTo(Offset.Zero)
                        YtNews.layerUp = false
                        YtNews.windowGone()
                    }
                }
            }
        }
        val present by remember { derivedStateOf { YtNews.shown || reveal.value > 0.001f } }
        if (!present) return
        YtTheme(palette) {
            Window(palette, Reveal(reveal, follow, intoCard))
        }
    }

    /** The window's motion state, read in the draw phase only. */
    private class Reveal(
        val value: Animatable<Float, *>,
        val follow: Animatable<Offset, *>,
        val intoCard: androidx.compose.runtime.State<Boolean>,
    ) {
        /** The card the window grows out of, while it is to go back into it. */
        fun card(): YtCardFrame? = if (intoCard.value) YtNews.source?.takeIf { it.bounds() != null } else null

        /** Where the window starts: the card's visible rectangle, or a small window about the fingers' centre. */
        fun start(full: YtNewsRect): YtNewsRect {
            card()?.bounds()?.let { return it }
            val o = YtNews.origin
            return YtNewsMotion.centreStart(full, o?.x ?: full.centerX, o?.y ?: full.centerY)
        }

        /** The window's rectangle now (window px), dragged down [dragPx] of [full]'s height. */
        fun rect(full: YtNewsRect, dragPx: Float): YtNewsRect {
            val f = follow.value
            val r = YtNewsMotion.window(start(full), full, value.value, f.x, f.y)
            return YtNewsMotion.dragged(r, dragFraction(dragPx, full.height), dragPx)
        }
    }

    @Composable
    private fun Window(palette: YtPalette, reveal: Reveal) {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val haptics = rememberYtHaptics()
        val look = remember(palette) { newsLook(palette) }
        val pill = remember(palette) { pillLook(palette) }
        val motion = remember(YtNews.opens) { YtMotionPrefs.enabled }
        // parallax, depth and shadows: with the motion switch on a phone that is not weak
        val depth = remember(YtNews.opens) { motion && YtNews.rich }
        // the window dragged down to close; back at the top for every opening
        val dragY = remember { Animatable(0f) }
        LaunchedEffect(YtNews.opens) { dragY.snapTo(0f) }
        var layerOrigin by remember { mutableStateOf(Offset.Zero) }
        var quick by remember { mutableStateOf<StreamItem?>(null) }
        var note by remember { mutableStateOf<Note?>(null) }
        val cornerFallback = with(LocalDensity.current) { 40.dp.toPx() }
        YtBackHandler(enabled = YtNews.shown) {
            haptics.news(YtNewsHaptic.CLOSE)
            YtNews.close()
        }
        // the card hides on the home once its picture shows here, so it is never seen twice
        val source = YtNews.source
        LaunchedEffect(source) {
            source ?: return@LaunchedEffect
            repeat(30) {
                withFrameNanos { }
                if (source.recorded) {
                    withFrameNanos { }
                    if (YtNews.source === source && reveal.intoCard.value) source.hidden = true
                    return@LaunchedEffect
                }
            }
        }

        // the deck as it was when the overlay opened (cards do not move under the finger); taken
        // again only while it is still empty (the feed or a topic search still loading)
        val live = YtNews.deck
        val held = remember(YtNews.opens) { arrayOfNulls<YtNewsDeck>(1) }
        val before = held[0]
        if (live.ready && (before == null || (before.cards.isEmpty() && live.cards.isNotEmpty()))) held[0] = live
        val deck = held[0] ?: live
        androidx.compose.runtime.SideEffect { YtNewsStories.showing = deck }

        val actions = remember(context, haptics) {
            NewsActions(
                open = { video ->
                    YtNews.close()
                    YtQueue.playKeepingQueue(context, video, audio = YtFeed.audioNow())
                },
                queue = { video -> queueVideo(video, haptics) },
                quick = { quick = it },
            )
        }

        fun full(width: Float, height: Float) = YtNewsRect(layerOrigin.x, layerOrigin.y, layerOrigin.x + width, layerOrigin.y + height)

        Box(
            Modifier
                .fillMaxSize()
                .onGloballyPositioned { layerOrigin = it.positionInWindow() },
        ) {
            // the screen under the window dims as it opens
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = reveal.value.value.coerceIn(0f, 1f) * (1f - dragFraction(dragY.value, size.height)) }
                    .background(Color(0x99000000)),
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .testTag("yt_news_window")
                    .graphicsLayer {
                        // read here, in the layer: the window moves and grows without composing again
                        val r = reveal.value.value
                        val full = full(size.width, size.height)
                        val now = reveal.rect(full, dragY.value)
                        val s = if (full.width > 0f) (now.width / full.width).coerceAtLeast(0.01f) else 1f
                        transformOrigin = TransformOrigin(0f, 0f)
                        scaleX = s
                        scaleY = s
                        translationX = now.left - full.left
                        translationY = now.top - full.top
                        val card = reveal.card()
                        val radius = YtNewsMotion.corner(card?.cornerPx() ?: cornerFallback, r)
                        alpha = YtNewsMotion.windowAlpha(r, card?.picture != null) * (1f - 0.5f * dragFraction(dragY.value, full.height))
                        // a card that grows into the screen: its corners while small, square when full
                        shape = WindowShape(now.height / s, radius / s)
                        clip = true
                    }
                    .background(look.ground)
                    // pinched together: closed
                    .ytNewsPinch(
                        enabled = true,
                        inward = true,
                        toWindow = { it },
                        onProgress = { _, _ -> },
                        onCommit = {
                            haptics.news(YtNewsHaptic.CLOSE)
                            YtNews.close()
                        },
                        onCancel = {},
                    ),
            ) {
                Content(palette, look, deck, motion, depth, actions, dragY, haptics, scope)
                // the confirmation of a card handed to the queue
                NotePill(pill, note, Modifier.align(Alignment.BottomCenter)) { note = null }
            }
            // the card itself over the growing window, fading into it (and back out of it)
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        alpha = YtNewsMotion.pictureAlpha(reveal.value.value) * (1f - 0.5f * dragFraction(dragY.value, size.height))
                        compositingStrategy = CompositingStrategy.ModulateAlpha
                    }
                    .drawBehind {
                        val card = reveal.card() ?: return@drawBehind
                        val picture = card.picture?.takeIf { !it.isReleased && it.size.width > 0 } ?: return@drawBehind
                        val outer = card.outer() ?: return@drawBehind
                        val inner = card.bounds() ?: return@drawBehind
                        val now = reveal.rect(full(size.width, size.height), dragY.value)
                        // the card's rectangle in the picture's own px (the home may be scaled back)
                        val k = outer.width / picture.size.width
                        if (k <= 0f || inner.width <= 0f) return@drawBehind
                        val left = (inner.left - outer.left) / k
                        val top = (inner.top - outer.top) / k
                        val s = now.width / (inner.width / k)
                        withTransform({
                            translate(now.left - layerOrigin.x - left * s, now.top - layerOrigin.y - top * s)
                            scale(s, s, Offset.Zero)
                        }) {
                            drawLayer(picture)
                        }
                    },
            )
            YtQuickSheet(
                palette = palette,
                imageLoader = ImageHelper.loader(),
                video = quick,
                onDismiss = { quick = null },
                onSave = { video -> saveVideo(context, video) },
                onShare = { video -> shareVideo(context, video) },
                onChannel = { video ->
                    YtNews.close()
                    NavigationHelper.navigateChannel(context, video.uploaderUrl)
                },
            )
        }
        // what the queue did, as a pill for a moment
        LaunchedEffect(Unit) {
            queueNotes.collect { note = it }
        }
    }

    /** The window's clip: [height] px of it from the top (the rest is still below the card), [radius] px corners. */
    private class WindowShape(private val height: Float, private val radius: Float) : Shape {
        override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
            val h = height.coerceIn(0f, size.height)
            val r = radius.coerceAtLeast(0f)
            return Outline.Rounded(RoundRect(0f, 0f, size.width, h, CornerRadius(r, r)))
        }
    }

    private fun dragFraction(dragY: Float, height: Float): Float =
        if (height <= 0f) 0f else (dragY / height).coerceIn(0f, 1f)

    @Composable
    private fun Content(
        palette: YtPalette,
        look: NewsLook,
        deck: YtNewsDeck,
        motion: Boolean,
        depth: Boolean,
        actions: NewsActions,
        dragY: Animatable<Float, *>,
        haptics: YtHaptics,
        scope: kotlinx.coroutines.CoroutineScope,
    ) {
        val cards = deck.cards
        val pager = key(YtNews.opens) { rememberPagerState { cards.size } }
        // a tick of the design's feel as the pager passes into another card
        LaunchedEffect(pager) {
            snapshotFlow { pager.currentPage }.drop(1).collect { haptics.news(YtNewsHaptic.PAGE) }
        }
        val ground = look.groundInk
        // down on the header or the empty state: the window follows and closes
        @Composable
        fun closeSwipe(name: String): Modifier = Modifier.newsSwipe(
            key = name,
            allowUp = false,
            onDrag = { dy -> scope.launch { dragY.snapTo(dy.coerceAtLeast(0f)) } },
            onRelease = { release, velocity -> settleWindow(release, velocity, dragY, motion, haptics, scope) },
        )
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            Column(closeSwipe("header")) {
                if (cards.size > 1) Segments(pager, cards.size, look, Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp))
                Header(look, pager, cards.size, haptics)
            }
            if (cards.isEmpty()) {
                Empty(look, deck.ready, closeSwipe("empty").weight(1f))
                return@Column
            }
            HorizontalPager(
                state = pager,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 20.dp),
                pageSpacing = 12.dp,
                key = { cards.getOrNull(it)?.key ?: it },
                flingBehavior = PagerDefaults.flingBehavior(
                    state = pager,
                    snapAnimationSpec = if (motion) spring(dampingRatio = 0.82f, stiffness = Spring.StiffnessMediumLow) else snap(),
                ),
            ) { page ->
                val card = cards.getOrNull(page) ?: return@HorizontalPager
                val video = deck.video(card) ?: return@HorizontalPager
                // how far this page is from the middle, signed; read in the draw phase only
                val offset = { (pager.currentPage - page) + pager.currentPageOffsetFraction }
                NewsCardPage(
                    palette = palette,
                    look = look,
                    card = card,
                    video = video,
                    actions = actions,
                    motion = motion,
                    depth = depth,
                    offset = offset,
                    haptics = haptics,
                    dragY = dragY,
                    modifier = Modifier.graphicsLayer {
                        // the neighbours recede: smaller and dimmer (with depth also lower, below)
                        val p = YtNewsMotion.page(offset(), depth)
                        scaleX = p.scale
                        scaleY = p.scale
                        alpha = p.alpha
                    },
                )
            }
            Text(
                text = stringResource(R.string.yt_r27_news_gestures),
                color = ground.textSecondary,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(fontFamily = palette.font, fontSize = 12.sp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 12.dp),
            )
        }
    }

    /** Stories-like segments: one per card, filled up to the card shown, following the swipe. */
    @Composable
    private fun Segments(pager: PagerState, count: Int, look: NewsLook, modifier: Modifier) {
        val track = look.groundInk.textSecondary.copy(alpha = 0.3f)
        val fill = look.segment
        Canvas(modifier.fillMaxWidth().height(3.dp)) {
            val gap = (if (count > 20) 2.dp else 4.dp).toPx()
            val w = ((size.width - gap * (count - 1)) / count).coerceAtLeast(1f)
            val h = size.height
            val corner = CornerRadius(h / 2f, h / 2f)
            // read here, in the draw phase: the swipe redraws, it does not recompose
            val position = pager.currentPage + pager.currentPageOffsetFraction
            for (i in 0 until count) {
                val x = i * (w + gap)
                drawRoundRect(track, Offset(x, 0f), Size(w, h), corner)
                val filled = (position - i + 1f).coerceIn(0f, 1f)
                if (filled > 0f) drawRoundRect(fill, Offset(x, 0f), Size(w * filled, h), corner)
            }
        }
    }

    @Composable
    private fun Header(look: NewsLook, pager: PagerState, count: Int, haptics: YtHaptics) {
        val ink = look.groundInk
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        ) {
            Text(
                text = stringResource(R.string.yt_r27_news_title).uppercase(),
                color = look.label,
                maxLines = 1,
                style = TextStyle(fontFamily = look.headline, fontWeight = look.headlineWeight, fontSize = 18.sp, letterSpacing = 3.sp),
            )
            if (count > 0) {
                val position by remember(pager) { derivedStateOf { pager.currentPage + 1 } }
                Text(
                    text = stringResource(R.string.yt_r27_news_position, position.coerceAtMost(count), count),
                    color = ink.textSecondary,
                    maxLines = 1,
                    style = TextStyle(fontFamily = look.body, fontSize = 13.sp),
                    modifier = Modifier.padding(start = 12.dp),
                )
            }
            Spacer(Modifier.weight(1f))
            YtNewsStoryMenu(pager, ink)
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .testTag("yt_news_close")
                    .size(48.dp)
                    .clip(CircleShape)
                    .clickable {
                        haptics.news(YtNewsHaptic.CLOSE)
                        YtNews.close()
                    },
            ) {
                Icon(
                    painterResource(R.drawable.ic_close),
                    contentDescription = stringResource(R.string.yt_r27_news_close),
                    tint = ink.text,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
    }

    /** No news yet: how to get some; while the feed still loads, nothing for a moment. */
    @Composable
    private fun Empty(look: NewsLook, ready: Boolean, modifier: Modifier) {
        val waited by produceState(ready) {
            delay(1500)
            value = true
        }
        val ink = look.groundInk
        Box(modifier.fillMaxWidth().padding(horizontal = 28.dp), contentAlignment = Alignment.Center) {
            if (!ready && !waited) return@Box
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(painterResource(R.drawable.ic_yt_news), contentDescription = null, tint = look.label, modifier = Modifier.size(44.dp))
                Text(
                    text = stringResource(R.string.yt_r27_news_empty_title),
                    color = ink.text,
                    textAlign = TextAlign.Center,
                    style = TextStyle(fontFamily = look.headline, fontWeight = look.headlineWeight, fontSize = 22.sp, lineHeight = 28.sp),
                    modifier = Modifier.padding(top = 16.dp),
                )
                Text(
                    text = stringResource(R.string.yt_r27_news_empty_body),
                    color = ink.textSecondary,
                    textAlign = TextAlign.Center,
                    style = TextStyle(fontFamily = look.body, fontSize = 14.5.sp, lineHeight = 21.sp),
                    modifier = Modifier.padding(top = 10.dp),
                )
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .padding(top = 22.dp)
                        .testTag("yt_news_feed")
                        .heightIn(min = 48.dp)
                        .clip(RoundedCornerShape(50))
                        .background(look.buttonFill)
                        .clickable {
                            YtNews.close()
                            YtFeedUi.openSheet()
                        }
                        .padding(horizontal = 22.dp, vertical = 12.dp),
                ) {
                    Text(
                        text = stringResource(R.string.yt_r27_news_empty_feed),
                        color = look.buttonInk,
                        style = TextStyle(fontFamily = look.body, fontWeight = FontWeight.SemiBold, fontSize = 14.sp),
                    )
                }
            }
        }
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun NewsCardPage(
        palette: YtPalette,
        look: NewsLook,
        card: YtNewsCard,
        video: StreamItem,
        actions: NewsActions,
        motion: Boolean,
        depth: Boolean,
        offset: () -> Float,
        haptics: YtHaptics,
        dragY: Animatable<Float, *>,
        modifier: Modifier,
    ) {
        val scope = rememberCoroutineScope()
        // a shadow under the card in the middle, where the card is opaque (glass shows it through)
        val shadow = depth && palette.surfaceStyle !in setOf(YtSurfaceStyle.GLASS, YtSurfaceStyle.FROST, YtSurfaceStyle.CLAY)
        val density = LocalDensity.current
        val shadowPx = with(density) { 10.dp.toPx() }
        val camera = 14f * density.density
        val lead = card.lead
        val ink = look.cardInk
        val shape = look.cardShape
        // the card lifted by a swipe up (to the queue)
        val lift = remember { Animatable(0f) }
        var height by remember { mutableStateOf(1f) }
        val queuePx = with(LocalDensity.current) { QUEUE_DP.dp.toPx() }
        val text = remember(lead.description) { YtNewsText.clean(lead.description) }
        val queueLabel = stringResource(R.string.yt_r27_news_queue)
        val watchLabel = stringResource(R.string.yt_r27_news_watch)

        fun queue() {
            actions.queue(video)
        }

        Column(
            modifier
                .fillMaxSize()
                .padding(vertical = 8.dp)
                .onSizeChanged { height = it.height.toFloat() }
                .graphicsLayer {
                    translationY = lift.value
                    if (!depth) return@graphicsLayer
                    // dragged up (to the queue) or down (the window): the card leans and lifts a little
                    val dy = if (lift.value < 0f) lift.value else dragY.value
                    val tilt = YtNewsMotion.tilt(dy, queuePx * 1.5f)
                    rotationX = tilt.rotationX
                    scaleX = tilt.scale
                    scaleY = tilt.scale
                    cameraDistance = camera
                    if (shadow) {
                        val raised = (tilt.scale - 1f) / 0.03f
                        shadowElevation = shadowPx * (0.6f * YtNewsMotion.page(offset(), true).elevation + 0.4f * raised)
                        this.shape = look.cardShape
                        clip = false
                    }
                }
                .testTag("yt_news_card")
                .semantics {
                    customActions = listOf(
                        CustomAccessibilityAction(queueLabel) {
                            queue()
                            true
                        },
                    )
                }
                .newsSwipe(
                    key = card.key,
                    allowUp = true,
                    onDrag = { dy ->
                        scope.launch {
                            if (dy < 0f) {
                                // up: the card follows, with a rubber band past the threshold
                                val over = -dy - queuePx
                                val shown = if (over <= 0f) dy else -(queuePx + ytRubberBand(over, height.coerceAtLeast(1f)))
                                lift.snapTo(shown)
                                dragY.snapTo(0f)
                            } else {
                                // down: the whole window follows
                                lift.snapTo(0f)
                                dragY.snapTo(dy)
                            }
                        }
                    },
                    onRelease = { release, velocity ->
                        when (release) {
                            YtNewsGestureRules.Release.QUEUE -> {
                                queue()
                                scope.launch {
                                    if (motion) lift.animateTo(0f, spring(dampingRatio = 0.6f, stiffness = 380f), initialVelocity = velocity) else lift.snapTo(0f)
                                }
                            }
                            else -> {
                                scope.launch { if (motion) lift.animateTo(0f, spring(dampingRatio = 0.8f, stiffness = 420f)) else lift.snapTo(0f) }
                                settleWindow(release, velocity, dragY, motion, haptics, scope)
                            }
                        }
                    },
                )
                .then(look.card(Modifier, shape))
                .combinedClickable(
                    onClickLabel = watchLabel,
                    onClick = { actions.open(video) },
                    onLongClick = {
                        haptics.grab()
                        actions.quick(video)
                    },
                )
                .padding(16.dp),
        ) {
            Text(
                text = video.ytTitle,
                color = ink.text,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(
                    fontFamily = look.headline,
                    fontWeight = look.headlineWeight,
                    fontSize = look.headlineSize,
                    lineHeight = look.headlineSize * 1.25f,
                    letterSpacing = look.headlineSpacing,
                ),
            )
            Box(
                Modifier
                    .padding(top = 14.dp)
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(ytCardShape(palette, 12))
                    .background(look.imageBack),
            ) {
                // the picture lags behind its card as the pages move (parallax), within its frame
                YtLargeThumbnail(
                    video,
                    ImageHelper.loader(),
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            if (!depth) return@graphicsLayer
                            scaleX = YtNewsMotion.PARALLAX_SCALE
                            scaleY = YtNewsMotion.PARALLAX_SCALE
                            translationX = YtNewsMotion.parallax(offset(), size.width)
                        },
                )
                ytDuration(video)?.let { duration ->
                    Text(
                        text = duration,
                        color = Color.White,
                        style = TextStyle(fontFamily = look.body, fontWeight = FontWeight.SemiBold, fontSize = 12.sp),
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(8.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0xCC000000))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
            val age = ytVideoAge(video)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 12.dp)) {
                Text(
                    text = listOfNotNull(video.uploaderName?.takeIf { it.isNotBlank() }, age).joinToString(" · "),
                    color = ink.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(fontFamily = look.body, fontWeight = FontWeight.Medium, fontSize = 13.sp),
                    modifier = Modifier.weight(1f, fill = false),
                )
                val tag = if (lead.origin == YtNewsOrigin.TOPIC && lead.topics.isNotEmpty()) {
                    stringResource(R.string.yt_r27_news_topic, lead.topics.first())
                } else if (lead.origin == YtNewsOrigin.CHANNEL) {
                    stringResource(R.string.yt_r27_news_channel_tag)
                } else {
                    null
                }
                if (tag != null) {
                    Text(
                        text = tag,
                        color = look.tagInk,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = TextStyle(fontFamily = look.body, fontWeight = FontWeight.SemiBold, fontSize = 11.5.sp),
                        modifier = Modifier
                            .padding(start = 8.dp)
                            .widthIn(max = 160.dp)
                            .clip(RoundedCornerShape(50))
                            .background(look.tagFill)
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
            }
            // the story's first lines; cut off where the card ends on a small screen
            // (stage 4: with the user's key, the cited summary can take their place)
            YtNewsSummaryBlock(
                lead, video, YtNewsSummaryStyle(ink.text, ink.textSecondary, look.tagInk, look.tagFill, look.body),
                Modifier.weight(1f).fillMaxWidth().padding(top = 8.dp),
            ) {
                if (text.isNotEmpty()) {
                    Text(
                        text = text,
                        color = ink.text,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                        style = TextStyle(fontFamily = look.body, fontSize = 14.5.sp, lineHeight = 20.sp),
                    )
                }
            }
            YtNewsStoryBlock(palette, card, actions.open, actions.queue)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 10.dp)) {
                CardAction(palette, look, R.drawable.ic_play, watchLabel, "yt_news_watch") { actions.open(video) }
                CardAction(palette, look, R.drawable.ic_queue, queueLabel, "yt_news_queue") { queue() }
            }
        }
    }

    @Composable
    private fun CardAction(palette: YtPalette, look: NewsLook, icon: Int, label: String, tag: String, onClick: () -> Unit) {
        val shape = RoundedCornerShape(50)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .testTag(tag)
                .heightIn(min = 44.dp)
                .ytPressable(shape, squish = palette.surfaceStyle == YtSurfaceStyle.CLAY, onClick = onClick)
                .clip(shape)
                .background(look.buttonFill)
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            Icon(painterResource(icon), contentDescription = null, tint = look.buttonInk, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                text = label,
                color = look.buttonInk,
                maxLines = 1,
                style = TextStyle(fontFamily = look.body, fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp),
            )
        }
    }

    @Composable
    private fun NotePill(look: PillLook, note: Note?, modifier: Modifier, onGone: () -> Unit) {
        val accessibility = LocalAccessibilityManager.current
        val shown = remember { arrayOfNulls<Note>(1) }
        note?.let { shown[0] = it }
        LaunchedEffect(note) {
            note ?: return@LaunchedEffect
            val timeout = accessibility?.calculateRecommendedTimeoutMillis(1800L, containsIcons = true, containsText = true) ?: 1800L
            delay(timeout)
            onGone()
        }
        AnimatedVisibility(
            visible = note != null,
            modifier = modifier.windowInsetsPadding(WindowInsets.safeDrawing).padding(bottom = 44.dp),
            enter = com.github.libretube.ui.yt.ytEnterTransition(slideInVertically(spring(dampingRatio = 0.75f, stiffness = 420f)) { it } + fadeIn(tween(140))),
            exit = com.github.libretube.ui.yt.ytExitTransition(slideOutVertically(tween(200, easing = YtEaseOut)) { it / 2 } + fadeOut(tween(160))),
        ) {
            val current = shown[0] ?: return@AnimatedVisibility
            PillRow(look, Modifier.testTag("yt_news_note").padding(end = 10.dp)) {
                Icon(painterResource(R.drawable.ic_queue), contentDescription = null, tint = look.text, modifier = Modifier.size(18.dp))
                Text(
                    text = current.text(),
                    color = look.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = look.style(FontWeight.Medium),
                    modifier = Modifier
                        .padding(start = 10.dp)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        }
    }

    private class NewsActions(
        val open: (StreamItem) -> Unit,
        val queue: (StreamItem) -> Unit,
        val quick: (StreamItem) -> Unit,
    )

    /** What handing a card to the queue did, for the pill. */
    private class Note(val move: YtQueueMove, val count: Int) {
        @Composable
        fun text(): String = when (move) {
            YtQueueMove.APPEND -> stringResource(R.string.yt_r27_news_queued)
            YtQueueMove.DRAFT -> stringResource(R.string.yt_r27_news_drafted, count)
            else -> stringResource(R.string.yt_r27_news_rejected)
        }
    }

    private val queueNotes = kotlinx.coroutines.flow.MutableSharedFlow<Note>(extraBufferCapacity = 4)

    /** To the end of the queue (or the draft while nothing plays), as a card swiped right on the home. */
    private fun queueVideo(video: StreamItem, haptics: YtHaptics) {
        val result = YtQueue.appendToEnd(video)
        when (result.move) {
            YtQueueMove.APPEND -> YtQueue.announce(YtDropResult(next = false, position = result.position, title = video.title.orEmpty()))
            YtQueueMove.DRAFT -> YtQueue.draftLanded++
            else -> Unit
        }
        haptics.news(if (result.move == YtQueueMove.REJECT) YtNewsHaptic.REJECT else YtNewsHaptic.QUEUE)
        queueNotes.tryEmit(Note(result.move, result.position))
        YtTestHooks.log("YtNews") { "queue move=${result.move} id=${video.url?.toID()}" }
    }

    /** After a vertical swipe: closed, or the window springs back. */
    private fun settleWindow(
        release: YtNewsGestureRules.Release,
        velocity: Float,
        dragY: Animatable<Float, *>,
        motion: Boolean,
        haptics: YtHaptics,
        scope: kotlinx.coroutines.CoroutineScope,
    ) {
        if (release == YtNewsGestureRules.Release.CLOSE) {
            haptics.news(YtNewsHaptic.CLOSE)
            YtNews.close()
            return
        }
        scope.launch {
            if (motion) dragY.animateTo(0f, spring(dampingRatio = 0.8f, stiffness = 420f), initialVelocity = velocity) else dragY.snapTo(0f)
        }
    }

    private fun saveVideo(context: Context, video: StreamItem) {
        val activity = ContextHelper.tryUnwrapActivity<FragmentActivity>(context) ?: return
        AddToPlaylistDialog().apply {
            arguments = bundleOf(IntentData.videoInfo to video)
        }.show(activity.supportFragmentManager, AddToPlaylistDialog::class.java.name)
    }

    private fun shareVideo(context: Context, video: StreamItem) {
        val link = "https://youtu.be/" + (video.url ?: return).toID()
        val intent = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, video.title)
            .putExtra(Intent.EXTRA_TEXT, link)
        context.startActivity(Intent.createChooser(intent, video.title))
    }
}

/** Swipe up past this many dp queues the card. */
private const val QUEUE_DP = 96f

/** Swipe down past this many dp closes the overlay. */
private const val CLOSE_DP = 140f

/**
 * The vertical swipes of the overlay: the finger is taken only for a clearly vertical move
 * (YtNewsGestureRules.claim), so the pager keeps the sideways ones and taps and holds stay with
 * the card. [onDrag] gets the distance from the start (negative: up; with [allowUp] false only
 * down counts), [onRelease] the decision and the vertical velocity. A second finger (the pinch)
 * ends it without a decision.
 */
@Composable
private fun Modifier.newsSwipe(
    key: Any,
    allowUp: Boolean,
    onDrag: (Float) -> Unit,
    onRelease: (YtNewsGestureRules.Release, Float) -> Unit,
): Modifier {
    // the finger is followed in the layer's root space: what it moves (the card, the window)
    // moves this node too, and its own coordinates would follow the finger along
    val coords = remember { arrayOfNulls<LayoutCoordinates>(1) }
    return onGloballyPositioned { coords[0] = it }.pointerInput(key, allowUp) {
    val slop = viewConfiguration.touchSlop
    val queuePx = QUEUE_DP.dp.toPx()
    val closePx = CLOSE_DP.dp.toPx()
    fun root(change: PointerInputChange): Offset =
        coords[0]?.takeIf { it.isAttached }?.localToRoot(change.position) ?: change.position
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val tracker = VelocityTracker()
        val start = root(down)
        tracker.addPosition(down.uptimeMillis, start)
        var origin = start.y
        // 1. who takes the finger
        while (true) {
            val event = awaitPointerEvent()
            if (event.changes.count { it.pressed } > 1) return@awaitEachGesture
            val change = event.changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
            if (!change.pressed || change.isConsumed) return@awaitEachGesture
            val at = root(change)
            tracker.addPosition(change.uptimeMillis, at)
            val moved = at - start
            when (YtNewsGestureRules.claim(moved.x, moved.y, slop)) {
                YtNewsGestureRules.Claim.HORIZONTAL -> return@awaitEachGesture
                YtNewsGestureRules.Claim.WAIT -> continue
                YtNewsGestureRules.Claim.VERTICAL -> {
                    if (!allowUp && moved.y < 0f) return@awaitEachGesture
                    // past the slop the move starts from here, so the card does not jump
                    origin += sign(moved.y) * slop
                    change.consume()
                    break
                }
            }
        }
        // 2. follow until the finger lifts
        var dy = 0f
        var lifted = false
        try {
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                // a second finger or the pinch took over: no decision
                if (event.changes.count { it.pressed } > 1 || change.isConsumed) break
                val at = root(change)
                tracker.addPosition(change.uptimeMillis, at)
                dy = at.y - origin
                if (!allowUp) dy = dy.coerceAtLeast(0f)
                onDrag(dy)
                if (!change.pressed) {
                    lifted = true
                    break
                }
                change.consume()
            }
        } finally {
            val velocity = if (lifted) tracker.calculateVelocity().y else 0f
            val release = if (lifted) YtNewsGestureRules.release(dy, velocity, queuePx, closePx) else YtNewsGestureRules.Release.STAY
            onRelease(if (!allowUp && release == YtNewsGestureRules.Release.QUEUE) YtNewsGestureRules.Release.STAY else release, velocity)
        }
    }
    }
}

/** How the overlay looks in a design. */
private class NewsLook(
    /** The window's ground, opaque. */
    val ground: Color,
    val headline: FontFamily,
    val headlineWeight: FontWeight,
    val headlineSize: TextUnit,
    val headlineSpacing: TextUnit,
    val body: FontFamily,
    val cardShape: Shape,
    val card: (Modifier, Shape) -> Modifier,
    /** Words directly on the ground. */
    val groundInk: YtInk,
    /** Words on the card (checked against both ends of its surface). */
    val cardInk: YtInk,
    /** "NEWS" and the empty state's icon. */
    val label: Color,
    /** The filled part of the segments (a UI part: 3:1 on the ground). */
    val segment: Color,
    val tagFill: Color,
    val tagInk: Color,
    val buttonFill: Color,
    val buttonInk: Color,
    val imageBack: Color,
)

private fun newsLook(palette: YtPalette): NewsLook {
    val ground = palette.background.copy(alpha = 1f)
    val groundArgb = ground.toArgb()
    val tones = ytSurfaceTones(palette, groundArgb)
    val cardInk = YtInk.onAll(palette, tones)
    val groundInk = YtInk.on(palette, groundArgb)
    // the topic or channel tag: the accent on its tinted capsule over the card
    val tagFill = palette.accent.copy(alpha = 0.18f)
    val tagBacks = IntArray(tones.size) { YtContrast.over(tagFill.toArgb(), tones[it]) }
    val tagInk = Color(YtContrast.readableOnAll(palette.accent.toArgb(), tagBacks))
    val (headline, weight) = when (palette.signature) {
        YtSignature.SYNTHWAVE -> YtFonts.audiowide to FontWeight.Normal
        YtSignature.AFTER -> YtFonts.fraunces to FontWeight.ExtraBold
        else -> palette.font to FontWeight.Bold
    }
    val synthwave = palette.signature == YtSignature.SYNTHWAVE
    // the buttons: the accent filled, its own ink made to read on it
    val buttonFill = palette.accent.copy(alpha = 1f)
    return NewsLook(
        ground = ground,
        headline = headline,
        headlineWeight = weight,
        headlineSize = if (synthwave) 19.sp else 22.sp,
        headlineSpacing = if (synthwave) 0.6.sp else 0.sp,
        body = palette.font,
        cardShape = ytCardShape(palette, 22),
        card = { m, shape -> m.ytSurface(palette, shape) },
        groundInk = groundInk,
        cardInk = cardInk,
        label = palette.accent.readableOn(ground),
        segment = palette.accent.readableOn(ground, 3.0),
        tagFill = tagFill,
        tagInk = tagInk,
        buttonFill = buttonFill,
        buttonInk = palette.onAccent.readableOn(buttonFill),
        imageBack = palette.raised,
    )
}
