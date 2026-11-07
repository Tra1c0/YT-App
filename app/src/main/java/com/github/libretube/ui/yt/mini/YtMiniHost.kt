package com.github.libretube.ui.yt.mini

import android.animation.Animator
import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Outline
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import androidx.activity.OnBackPressedCallback
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.constraintlayout.motion.widget.MotionLayout
import androidx.constraintlayout.motion.widget.TransitionAdapter
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.ViewCompat
import androidx.dynamicanimation.animation.FloatValueHolder
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.github.libretube.R
import com.github.libretube.databinding.FragmentPlayerBinding
import com.github.libretube.extensions.toID
import com.github.libretube.helpers.NavBarHelper
import com.github.libretube.helpers.PreferenceHelper
import com.github.libretube.ui.views.SingleViewTouchableMotionLayout
import com.github.libretube.ui.yt.YtDock
import com.github.libretube.ui.yt.YtDrag
import com.github.libretube.ui.yt.YtHaptics
import com.github.libretube.ui.yt.YtMiniPlayer
import com.github.libretube.ui.yt.YtMotionPrefs
import com.github.libretube.ui.yt.YtPalette
import com.github.libretube.ui.yt.YtTestHooks
import com.github.libretube.ui.yt.YtTheme
import com.github.libretube.ui.yt.liquid.YtViewBackdrop
import com.github.libretube.ui.yt.player.YtPlayerHost
import com.github.libretube.util.PlayingQueue
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.WeakHashMap
import kotlin.math.roundToInt

/** YT: the settings of the mini player (package A's section of "Gesten & Interaktion"). */
object YtMiniPrefs {
    const val PEEK = "yt_mini_peek"

    /** Whether a long press on the docked player's picture shows the preview. */
    val peek: Boolean get() = PreferenceHelper.getBoolean(PEEK, true)
}

/**
 * YT: the docked mini player of the player page, for every design: puts the design's composables
 * under the picture (inside the box, as the player's surface) and over it (in the PlayerView's
 * overlay), follows the minimize transition, lands, publishes the drop target's bounds, runs the
 * long-press preview on a transition of its own and plays the design's haptics. One per page view.
 */
class YtMiniHost private constructor(
    private val fragment: Fragment,
    private val binding: FragmentPlayerBinding,
    val design: YtMiniDesign,
    private val palette: YtPalette?,
) {
    private val context: Context = binding.root.context
    private val motion: SingleViewTouchableMotionLayout = binding.playerMotionLayout
    private val density = design.density(context)
    private val haptics = YtHaptics.of(binding.root)
    private val glass = palette != null && !design.classic && design.usesBackdrop(palette)
    private val backdrop = if (glass) {
        fragment.activity?.findViewById<View>(R.id.fragment)?.let { YtViewBackdrop(it, palette!!.background) }
    } else {
        null
    }

    val scene = YtMiniScene(design, palette, backdrop, density)

    /** The composable behind the picture, inside the box; the composable over the picture. */
    private val under = ComposeView(context)
    private var over: ComposeView? = null

    private var landing: Animator? = null
    private var reaction: Animator? = null
    private var resizeBefore: Int? = null
    private var page: YtPlayerHost? = null
    private var bound: Job? = null
    private val actions = mutableListOf<Int>()

    // the preview: a spring drives the progress of its own transition (yt_peek)
    private val peekValue = FloatValueHolder(0f)
    private val peekSpring = SpringAnimation(peekValue)
    private var peekActive = false
    private var peekHeld = false
    private var peekFull = false
    private var peekLayout: YtMiniPeekGeometry? = null
    private var peekExtra = 0f

    // a part of the preview acts only once the finger moved onto it: the part that grew under
    // the resting finger does nothing
    private var peekFrom = YtMiniTarget.NONE
    private var peekArmed = false
    private var peekBack: OnBackPressedCallback? = null

    /** How far the docked player reaches above the bar's view, in px (the slab behind it). */
    private val extentPx = if (design.classic) 0f else (design.spec.height - design.spec.navTop).coerceAtLeast(0) * density

    private fun views() = YtMiniViews(
        under = under,
        over = over,
        box = binding.mainContainer,
        player = binding.player,
        title = binding.titleTextView,
        play = binding.playImageView,
        close = binding.closeImageView,
        density = density,
    )

    private fun play(moment: YtMoment) = haptics.play(design.haptic(moment))

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun attach() {
        hosts[motion] = this
        YtMiniPlayer.keepDock()
        scene.surface = { binding.player.videoSurfaceView }
        if (!design.classic) shape()

        // behind the picture: inside the box, which draws before the player view
        under.alpha = 0f
        under.setContent { Content(poll = true) { design.Under(scene) } }
        binding.mainContainer.addView(
            under,
            0,
            ConstraintLayout.LayoutParams(0, 0).apply {
                startToStart = ConstraintLayout.LayoutParams.PARENT_ID
                endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
                topToTop = ConstraintLayout.LayoutParams.PARENT_ID
                bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
            },
        )
        // the backdrop follows the screens below only while the surface is in the window; the
        // listener would otherwise stay with the activity after this player is gone
        backdrop?.let { b ->
            under.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {
                    v.viewTreeObserver.addOnPreDrawListener(b.preDrawListener)
                }

                override fun onViewDetachedFromWindow(v: View) {
                    v.viewTreeObserver.removeOnPreDrawListener(b.preDrawListener)
                }
            })
        }

        // over the picture: the PlayerView's overlay, clipped by its outline. The view moves with
        // the player into the fullscreen dialog, which has no owners, so it carries its own and
        // keeps its composition until the page's view goes
        binding.player.overlayFrameLayout?.let { frame ->
            val owner = fragment.viewLifecycleOwner
            val view = ComposeView(context).apply {
                alpha = 0f
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                setViewTreeLifecycleOwner(owner)
                setViewTreeSavedStateRegistryOwner(owner as? SavedStateRegistryOwner ?: fragment)
                setViewTreeViewModelStoreOwner(owner as? ViewModelStoreOwner ?: fragment)
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnLifecycleDestroyed(owner))
                setContent { Content(poll = false) { design.Over(scene) } }
            }
            frame.addView(view, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            over = view
        }

        publishBoundsOnLayout()
        listen()
        if (design.followsFinger) followFinger()

        fragment.viewLifecycleOwner.lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_DESTROY) destroy()
            },
        )
    }

    /** The design's theme around its composables; one of them ([poll]) polls what they show. */
    @Composable
    private fun Content(poll: Boolean, content: @Composable () -> Unit) {
        if (poll) {
            LaunchedEffect(Unit) {
                while (true) {
                    scene.info = info()
                    delay(if (YtDock.docked) 350L else 1500L)
                }
            }
        }
        if (palette != null) YtTheme(palette) { content() } else content()
    }

    private fun info(): YtMiniInfo {
        val player = binding.player.player
        val duration = player?.duration ?: 0L
        return YtMiniInfo(
            title = binding.titleTextView.text?.toString().orEmpty(),
            channel = binding.playerChannelName.text?.toString().orEmpty(),
            fraction = if (player != null && duration > 0) (player.currentPosition.toFloat() / duration).coerceIn(0f, 1f) else 0f,
            playing = player?.isPlaying == true,
        )
    }

    /** The design's form: the end set, the minimize transition, the views of the docked player. */
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun shape() {
        // the constraints read only the margins, which do not depend on the width
        val g = design.geometry(0f, density)
        val end = motion.getConstraintSet(R.id.end) ?: return
        design.constrain(end, g, null, YtMiniState.REST)
        // landscape: the list beside the video would stay as a strip next to the docked player
        if (motion.findViewById<View>(R.id.related_container) != null) {
            end.setVisibility(R.id.related_container, View.GONE)
        }
        motion.updateState(R.id.end, end)
        motion.rebuildScene()

        // minimizing in the motion character of the design
        motion.getTransition(R.id.yt_transition)?.let { transition ->
            transition.duration = design.motion.duration
            transition.setInterpolatorInfo(design.motion.interpolator, null, 0)
        }

        // the player's surface, title and marks: a composable behind the picture and the buttons
        binding.mainContainer.background = null
        binding.mainContainer.clipChildren = false
        binding.mainContainer.clipToPadding = false
        // the joint reaches into the bar and dips with it: nothing above may cut it off
        (binding.mainContainer.parent as? ViewGroup)?.clipChildren = false
        (binding.root.parent as? ViewGroup)?.let { container ->
            container.clipChildren = false
            (container.parent as? ViewGroup)?.clipChildren = false
        }

        // the composable draws title and channel in the type of the design; the view stays for
        // accessibility and for the layout of the buttons
        binding.titleTextView.setTextColor(android.graphics.Color.TRANSPARENT)
        palette?.let { design.buttonTints(it) }?.let { (play, close) ->
            binding.playImageView.imageTintList = ColorStateList.valueOf(play.toArgb())
            binding.closeImageView.imageTintList = ColorStateList.valueOf(close.toArgb())
        }
        val pad = (10 * density).roundToInt()
        binding.closeImageView.setPadding(pad, pad, pad, pad)

        // the picture rounds its corners as the player shrinks (where the video surface allows
        // it); minimize progress, not the transition's, which the preview reuses
        val thumbRadius = design.spec.thumbRadius * density
        if (thumbRadius > 0f) {
            binding.player.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    // only on the page: moved into the fullscreen or picture-in-picture window,
                    // the video keeps square corners
                    val radius = if (view.parent === motion) thumbRadius * YtDock.progress else 0f
                    // top corners only: the rect reaches past the foot, so the foot stays square
                    val below = if (design.roundsTopOnly) radius.roundToInt() + 1 else 0
                    outline.setRoundRect(0, 0, view.width, view.height + below, radius)
                }
            }
            binding.player.clipToOutline = true
        }
    }

    /** The picture fills its shape (cropped) while minimized and fits again when it grows. */
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun crop(mini: Boolean) {
        if (design.classic) return
        val view = binding.player
        if (mini && resizeBefore == null) {
            resizeBefore = view.resizeMode
            view.setResizeModeTransient(AspectRatioFrameLayout.RESIZE_MODE_ZOOM)
            relayout(view)
        } else if (!mini && resizeBefore != null) {
            resizeBefore?.let { view.setResizeModeTransient(it) }
            resizeBefore = null
            relayout(view)
        }
    }

    /**
     * A MotionLayout at rest lays its children out only when a transition moves them, so the
     * picture would keep the frame of the old resize mode (Glas: the 16:9 picture letterboxed in
     * the wide strip, black at its sides) until the next one: the player view is measured and
     * laid out again at the size it has.
     */
    private fun relayout(view: View) {
        view.post {
            val width = view.width
            val height = view.height
            if (width <= 0 || height <= 0) return@post
            view.forceLayout()
            view.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
            )
            view.layout(view.left, view.top, view.right, view.bottom)
        }
    }

    // the drop target for videos dragged onto the docked player (YtDrag): the window rect of
    // main_container while the player sits on the bar, else null. Checked before every frame
    // too, since the activity moves the player container a frame after the transition ends
    // and the landing moves the player
    private val windowAt = IntArray(2)

    private fun publishBounds() {
        val box = binding.mainContainer
        if (!YtDock.docked || peekActive || motion.currentState != R.id.end || !box.isAttachedToWindow || box.width == 0) {
            if (YtDrag.miniBounds != null) YtDrag.miniBounds = null
            return
        }
        box.getLocationInWindow(windowAt)
        val old = YtDrag.miniBounds
        if (old != null && old.left == windowAt[0] && old.top == windowAt[1] &&
            old.width() == box.width && old.height() == box.height
        ) {
            return
        }
        val bounds = android.graphics.Rect(windowAt[0], windowAt[1], windowAt[0] + box.width, windowAt[1] + box.height)
        YtDrag.miniBounds = bounds
        YtTestHooks.log("YtMini") { "bounds ${bounds.left} ${bounds.top} ${bounds.right} ${bounds.bottom}" }
        logDockCheck(bounds.bottom)
        logSideCheck(bounds)
    }

    /** For the same check, turned: whether the box keeps out of the system's side bars and cutout. */
    private fun logSideCheck(bounds: android.graphics.Rect) {
        if (!YtTestHooks.gestureLog) return
        val root = binding.root.rootView ?: return
        val insets = ViewCompat.getRootWindowInsets(root)?.getInsets(
            androidx.core.view.WindowInsetsCompat.Type.systemBars() or androidx.core.view.WindowInsetsCompat.Type.displayCutout(),
        ) ?: return
        YtTestHooks.log("YtMini") { YtDockMath.sideLine(bounds.left, bounds.right, root.width, insets.left, insets.right) }
    }

    /**
     * For the emulator check (scripts/gestures/b.sh): how far the docked box's foot is from its
     * place on the bar, each time the box moves (the last line is where it rests).
     */
    private fun logDockCheck(boxBottom: Int) {
        if (!YtTestHooks.gestureLog) return
        val bar = fragment.activity?.findViewById<View>(R.id.bottomNav) ?: return
        if (!bar.isShown || bar.height == 0 || !NavBarHelper.hasTabs()) return
        val at = IntArray(2)
        bar.getLocationInWindow(at)
        YtTestHooks.log("YtMini") { YtDockMath.checkLine(boxBottom, at[1], YtMiniPlayer.containerDockPx) }
    }

    private val boundsCheck = ViewTreeObserver.OnPreDrawListener {
        publishBounds()
        clipToBar()
        true
    }

    private val barClip = android.graphics.Rect()

    /**
     * The docked player dragged down (towards closing) moves the whole page by its translationY:
     * it would slide over the bar and cover its active tab. While it does, the page is cut at the
     * line where the box's foot rests on the bar, so the player sinks behind the bar's top edge
     * and only the joint the design means (its overlap into the bar) stays. The clip is in the
     * page's own coordinates, which move with the translation, so it is moved back up by it.
     */
    private fun clipToBar() {
        if (design.classic) return
        val shift = motion.translationY
        val box = binding.mainContainer
        if (shift > 0.5f && YtDock.progress >= 0.999f && box.height > 0 && motion.width > 0) {
            val line = (box.bottom - shift).roundToInt().coerceAtLeast(0)
            if (motion.clipBounds?.let { it.bottom == line && it.right == motion.width } != true) {
                barClip.set(0, 0, motion.width, line)
                motion.clipBounds = barClip
            }
        } else if (motion.clipBounds != null) {
            motion.clipBounds = null
        }
    }

    private fun publishBoundsOnLayout() {
        binding.mainContainer.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                v.viewTreeObserver.addOnPreDrawListener(boundsCheck)
            }

            override fun onViewDetachedFromWindow(v: View) {
                v.viewTreeObserver.removeOnPreDrawListener(boundsCheck)
                YtDrag.miniBounds = null
            }
        })
        if (binding.mainContainer.isAttachedToWindow) {
            binding.mainContainer.viewTreeObserver.addOnPreDrawListener(boundsCheck)
        }
        binding.mainContainer.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> publishBounds() }
    }

    /**
     * The finger on the docked box, for designs that answer it (Movo's tiles tilt under it). The
     * listener only watches: it returns false, so the layout's gestures go on as before. It
     * hears a gesture only where the layout itself handles it (not on the buttons).
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun followFinger() {
        motion.setOnTouchListener { _, event ->
            val box = binding.mainContainer
            val x = event.x - box.left - box.translationX
            val y = event.y - box.top - box.translationY
            scene.finger = when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    val docked = YtDock.docked && motion.currentState == R.id.end
                    val inside = x >= 0f && y >= 0f && x <= box.width && y <= box.height
                    // a gesture that starts outside the box goes on without it
                    if (docked && (inside || (event.actionMasked == MotionEvent.ACTION_MOVE && scene.finger != null))) Offset(x, y) else null
                }
                else -> null
            }
            false
        }
    }

    /** Follows the minimize transition (the page's listeners hear nothing of the preview's). */
    private fun listen() {
        motion.addTransitionListener(object : TransitionAdapter() {
            override fun onTransitionChange(motionLayout: MotionLayout?, startId: Int, endId: Int, progress: Float) {
                // opened again while it was still landing: the landing stops (and resets) first
                if (progress < 1f) landing?.cancel()
                YtMiniPlayer.keepDock()
                val p = progress.coerceIn(0f, 1f)
                under.alpha = design.underAlpha(p)
                over?.alpha = design.overAlpha(p)
                YtDock.docked = progress > 0.02f
                YtDock.progress = p
                YtDock.slabPx = extentPx * p
                binding.player.invalidateOutline()
                if (progress < 0.98f) crop(false)
            }

            override fun onTransitionCompleted(motionLayout: MotionLayout?, currentId: Int) {
                val mini = currentId == R.id.end
                YtMiniPlayer.keepDock()
                if (YtDockMath.clearsOffsets(YtMinimizeModule.dragging, YtMinimizeModule.closing)) clearOffsets()
                under.alpha = if (mini) 1f else 0f
                over?.alpha = if (mini) 1f else 0f
                YtDock.docked = mini
                YtDock.progress = if (mini) 1f else 0f
                YtDock.slabPx = if (mini) extentPx else 0f
                binding.player.invalidateOutline()
                publishBounds()
                crop(mini)
                if (mini) {
                    play(YtMoment.DOCK)
                    YtTestHooks.log("YtMini") { "dock ${design.design.key}" }
                    if (YtMotionPrefs.enabled) {
                        landing?.cancel()
                        landing = design.land(views())
                        scene.landings++
                    }
                }
            }
        })
    }

    /**
     * The views at rest where the transition lays them out. MotionLayout never resets a
     * translation it does not animate itself, so whatever a landing or a drop reaction was
     * cut off in, or a drag's sideways offset that no release took back, would stay on the box
     * and the picture (the mini player off its place beside or under the bar). The landing that
     * follows starts from here.
     */
    private fun clearOffsets() {
        landing?.cancel()
        reaction?.cancel()
        for (view in views().all) {
            view.translationX = 0f
            view.translationY = 0f
        }
    }

    /** The page's host is there: gestures, the next video, the drop target's feedback. */
    fun bind(host: YtPlayerHost) {
        page = host
        motion.miniTouch = YtMiniTouch(this)
        scene.onPlayPause = { togglePlay() }
        scene.onPrev = {
            play(YtMoment.BUTTON)
            host.playPrev()
        }
        scene.onNext = {
            play(YtMoment.BUTTON)
            host.playNext()
        }
        scene.onOpen = { host.maximize() }
        scene.onClose = { host.close() }
        refreshNext()

        bound?.cancel()
        val scope = fragment.viewLifecycleOwner.lifecycleScope
        bound = scope.launch {
            launch { PlayingQueue.revision.collect { refreshNext() } }
            launch { watchDrops(scope) }
        }
    }

    /** The page's view goes: nothing may hold on to it. */
    fun unbind() {
        bound?.cancel()
        bound = null
        if (motion.miniTouch is YtMiniTouch) motion.miniTouch = null
        clearActions()
        page = null
    }

    /** What plays next (the queue, else autoplay's pick) and whether something played before. */
    fun refreshNext() {
        val host = page ?: return
        val item = host.nextCandidate()
        val id = item?.url?.toID()
        scene.next = if (item != null && id != null) {
            YtMiniNext(id, item.title.orEmpty(), item.uploaderName.orEmpty(), item.thumbnail)
        } else {
            null
        }
        scene.hasPrev = PlayingQueue.getPrev() != null
        updateActions()
    }

    /**
     * Without gestures: the docked player's title view offers play and pause and the next video
     * to accessibility services.
     */
    private fun updateActions() {
        clearActions()
        val target = binding.titleTextView
        actions += ViewCompat.addAccessibilityAction(target, context.getString(R.string.yt_mini_a11y_play_pause)) { _, _ ->
            togglePlay()
            true
        }
        if (scene.next != null) {
            actions += ViewCompat.addAccessibilityAction(target, context.getString(R.string.yt_mini_a11y_play_next)) { _, _ ->
                playNext()
            }
        }
        // designs without the buttons (Glas): the previous video and closing, without a gesture
        if (scene.hasPrev && !design.classic && !design.spec.play) {
            actions += ViewCompat.addAccessibilityAction(target, context.getString(R.string.yt_mini_a11y_prev)) { _, _ ->
                scene.onPrev()
                true
            }
        }
        if (!design.classic && !design.spec.close) {
            actions += ViewCompat.addAccessibilityAction(target, context.getString(R.string.tooltip_close)) { _, _ ->
                scene.onClose()
                true
            }
        }
    }

    private fun clearActions() {
        actions.forEach { ViewCompat.removeAccessibilityAction(binding.titleTextView, it) }
        actions.clear()
    }

    /** Hover, accept and reject of videos dragged onto the docked player (package D drops them). */
    private suspend fun watchDrops(scope: kotlinx.coroutines.CoroutineScope) {
        var accepted = YtDock.accepted
        var hovering = false
        var dragging = false
        var acceptedAtHover = accepted
        snapshotFlow { Triple(YtDrag.session != null, YtDock.hover || YtDrag.hoverKey == YtDrag.MINI, YtDock.accepted) }
            .collect { (session, hover, count) ->
                if (count != accepted) {
                    accepted = count
                    play(YtMoment.ACCEPT)
                    YtTestHooks.log("YtMini") { "drop accepted ${YtDock.lastDrop}" }
                    if (YtMotionPrefs.enabled) {
                        reaction?.cancel()
                        reaction = design.accept(views())
                    }
                }
                if (hover && !hovering) {
                    acceptedAtHover = accepted
                    play(YtMoment.HOVER)
                }
                // the drag ended over the player and nothing was taken: a drop the queue refused
                if (dragging && !session && hovering) {
                    val before = acceptedAtHover
                    scope.launch {
                        delay(160)
                        if (YtDock.accepted == before) reject()
                    }
                }
                hovering = hover && session
                dragging = session
            }
    }

    private fun reject() {
        play(YtMoment.REJECT)
        scene.rejects++
        YtTestHooks.log("YtMini") { "drop rejected" }
        if (YtMotionPrefs.enabled) {
            reaction?.cancel()
            reaction = design.reject(views())
        }
    }

    fun togglePlay() {
        val controller = page?.controller() ?: return
        val playing = controller.isPlaying
        if (playing) controller.pause() else controller.play()
        scene.flashPlaying = !playing
        scene.flashes++
        play(YtMoment.PLAY_TOGGLE)
        YtTestHooks.log("YtMini") { "play toggled playing=${!playing}" }
    }

    private fun playNext(): Boolean {
        val host = page ?: return false
        play(YtMoment.PLAY_NEXT)
        val played = host.playNext()
        YtTestHooks.log("YtQueue") { "play next from mini ${scene.next?.id} ok=$played" }
        return played
    }

    // ---------------------------------------------------------------------------------------
    // the preview

    /** Whether ([x], [y] in the page's coordinates) lies on the docked picture. */
    private fun onPicture(x: Float, y: Float): Boolean {
        val p = binding.player
        val slop = 12f * density
        return x >= p.left + p.translationX - slop && x <= p.right + p.translationX + slop &&
            y >= p.top + p.translationY - slop && y <= p.bottom + p.translationY + slop
    }

    /** [x], [y] (the page's coordinates) as shares of the picture's width and height. */
    private fun pressAt(x: Float, y: Float): androidx.compose.ui.geometry.Offset {
        val p = binding.player
        if (p.width <= 0 || p.height <= 0) return androidx.compose.ui.geometry.Offset.Unspecified
        return androidx.compose.ui.geometry.Offset(
            ((x - p.left - p.translationX) / p.width).coerceIn(0f, 1f),
            ((y - p.top - p.translationY) / p.height).coerceIn(0f, 1f),
        )
    }

    /** A long press on the docked picture: the preview grows. False if it does not start. */
    fun peekStart(x: Float, y: Float): Boolean {
        if (!design.peeks || !YtMiniPrefs.peek) return false
        if (peekActive) {
            // taken again while it shrank back: it grows again from where it is
            if (peekHeld) return false
            peekHeld = true
            peekFull = false
            peekFrom = partAt(x, y)
            peekArmed = false
            scene.press = pressAt(x, y)
            holdBack()
            play(YtMoment.PEEK_START)
            animatePeek(1f, design.motion.peekStiffness, design.motion.peekDamping)
            return true
        }
        if (motion.currentState != R.id.end || YtDock.progress < 1f || !onPicture(x, y)) return false
        val box = binding.mainContainer
        if (box.width == 0) return false
        val end = motion.getConstraintSet(R.id.end) ?: return false
        val set = motion.getConstraintSet(R.id.yt_peek_set) ?: return false
        landing?.cancel()
        reaction?.cancel()

        val width = box.width.toFloat()
        val rest = design.geometry(width, density)
        val layout = design.peekGeometry(width, density, scene.next != null)
        set.clone(end)
        design.constrain(set, rest, layout, YtMiniState.PEEK)
        motion.updateState(R.id.yt_peek_set, set)

        peekLayout = layout
        peekExtra = (layout.boxHeight - rest.boxHeight).coerceAtLeast(0f)
        peekFrom = partAt(x, y)
        peekArmed = false
        scene.peekLayout = layout
        scene.target = YtMiniTarget.NONE
        scene.press = pressAt(x, y)
        peekActive = true
        peekHeld = true
        peekFull = false
        YtDrag.miniBounds = null
        // the page's listeners must not take the preview for minimizing
        motion.peeking = true
        motion.setTransition(R.id.yt_peek)
        peekValue.value = 0f

        holdBack()
        play(YtMoment.PEEK_START)
        YtTestHooks.log("YtMini") { "peek start next=${scene.next?.id}" }
        animatePeek(1f, design.motion.peekStiffness, design.motion.peekDamping)
        return true
    }

    /** Back ends the preview while it is held (the page's own back is off while minimized). */
    private fun holdBack() {
        peekBack?.remove()
        val callback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = peekEnd(0f, 0f, true)
        }
        fragment.requireActivity().onBackPressedDispatcher.addCallback(fragment.viewLifecycleOwner, callback)
        peekBack = callback
    }

    /** The finger moves while the preview is held: the part under it lights up. */
    fun peekMove(x: Float, y: Float) {
        if (!peekActive || !peekHeld) return
        val part = partAt(x, y)
        if (part != peekFrom) peekArmed = true
        val target = targetAt(x, y)
        if (target != scene.target) {
            scene.target = target
            if (target != YtMiniTarget.NONE) play(YtMoment.PEEK_TARGET)
        }
    }

    /** The finger lets go (or back, [cancelled]): the part under it acts, the preview shrinks. */
    fun peekEnd(x: Float, y: Float, cancelled: Boolean) {
        if (!peekActive || !peekHeld) return
        peekHeld = false
        val target = if (cancelled) YtMiniTarget.NONE else targetAt(x, y)
        scene.target = YtMiniTarget.NONE
        peekBack?.remove()
        peekBack = null
        YtTestHooks.log("YtMini") { "peek=0 target=${target.name.lowercase()}" }
        when (target) {
            YtMiniTarget.PILL -> togglePlay()
            YtMiniTarget.NEXT -> playNext()
            YtMiniTarget.NONE -> play(YtMoment.PEEK_RELEASE)
        }
        animatePeek(0f, design.motion.releaseStiffness, design.motion.releaseDamping)
    }

    /**
     * The part of the preview at [x], [y] (page coordinates) that acts: none until the preview has
     * grown enough and the finger has moved onto another part than the one it started on.
     */
    private fun targetAt(x: Float, y: Float): YtMiniTarget {
        if (!peekArmed || YtDock.peek < 0.5f) return YtMiniTarget.NONE
        return partAt(x, y)
    }

    /** The part of the grown preview at [x], [y] (page coordinates). */
    private fun partAt(x: Float, y: Float): YtMiniTarget {
        val layout = peekLayout ?: return YtMiniTarget.NONE
        val box = binding.mainContainer
        // the geometry is left to right from the grown box's top; the box's foot stays on the bar
        val fromLeft = x - (box.left + box.translationX)
        val local = if (box.layoutDirection == View.LAYOUT_DIRECTION_RTL) box.width - fromLeft else fromLeft
        val fromBottom = (box.bottom + box.translationY) - y
        return YtMiniLayout.target(layout, local, layout.boxHeight - fromBottom, 8f * density)
    }

    private fun animatePeek(target: Float, stiffness: Float, damping: Float) {
        if (!YtMotionPrefs.enabled) {
            // no motion: the preview is there or gone at once
            peekSpring.cancel()
            peekValue.value = target
            applyPeek(target)
            if (target == 0f) finishPeek()
            return
        }
        val spring = peekSpring.getSpring() ?: SpringForce(target).also { peekSpring.setSpring(it) }
        spring.setStiffness(stiffness).setDampingRatio(damping)
        peekSpring.animateToFinalPosition(target)
    }

    private fun applyPeek(value: Float) {
        if (!peekActive) return
        val p = value.coerceIn(0f, 1f)
        motion.progress = p
        // a spring may swing a little past full size: the composables may show it, the layout not
        YtDock.peek = value.coerceIn(0f, 1.15f)
        YtDock.slabPx = extentPx + peekExtra * p
        if (peekHeld && !peekFull && value >= 0.98f) {
            peekFull = true
            play(YtMoment.PEEK_FULL)
            YtTestHooks.log("YtMini") { "peek=1" }
        }
    }

    /** The preview is gone: back on the minimize transition, docked as before. */
    private fun finishPeek() {
        if (!peekActive || peekHeld) return
        peekActive = false
        peekLayout = null
        scene.peekLayout = null
        scene.target = YtMiniTarget.NONE
        scene.press = androidx.compose.ui.geometry.Offset.Unspecified
        YtDock.peek = 0f
        YtDock.slabPx = extentPx
        if (motion.isAttachedToWindow) {
            // at the start of the preview's transition the page is docked (its current state
            // the end set), so switching back keeps it docked
            motion.progress = 0f
            motion.setTransition(R.id.yt_transition)
            // a progress the switch did not take for docked (the spring stopped a little before
            // 0, the state not yet updated) would leave the box between the two sets
            if (motion.currentState != R.id.end) motion.progress = 1f
        } else {
            // a detached MotionLayout keeps both for when it is attached again: the minimize
            // transition, docked; it would otherwise come back on the preview's transition
            motion.setTransition(R.id.yt_transition)
            motion.progress = 1f
        }
        // the switch is applied in the next frames; until then the page's listeners stay muted
        motion.postOnAnimation {
            motion.postOnAnimation { if (!peekActive) motion.peeking = false }
        }
        publishBounds()
    }

    private fun destroy() {
        unbind()
        if (design.followsFinger) motion.setOnTouchListener(null)
        scene.finger = null
        landing?.cancel()
        reaction?.cancel()
        peekSpring.cancel()
        peekBack?.remove()
        peekBack = null
        peekActive = false
        peekHeld = false
        motion.peeking = false
        hosts.remove(motion)
        YtDock.docked = false
        YtDock.dip = 0f
        YtDock.peek = 0f
        YtDock.slabPx = 0f
        YtDrag.miniBounds = null
        // the bar rounds its corners again, smoothly
        YtMiniPlayer.releaseDock()
    }

    init {
        peekSpring.setMinimumVisibleChange(0.002f)
        peekSpring.addUpdateListener { _, value, _ -> applyPeek(value) }
        peekSpring.addEndListener { _, canceled, value, _ ->
            if (!canceled && value <= 0.01f) finishPeek()
        }
    }

    companion object {
        private val hosts = WeakHashMap<View, YtMiniHost>()

        /** Gives the mini player of [binding] the form of the current design. */
        fun attach(fragment: Fragment, binding: FragmentPlayerBinding, design: YtMiniDesign, palette: YtPalette?) {
            YtMiniHost(fragment, binding, design, palette).attach()
        }

        /** The mini player of [host]'s page, if it has one. */
        fun of(host: YtPlayerHost): YtMiniHost? = host.pageBinding()?.playerMotionLayout?.let { hosts[it] }
    }
}
