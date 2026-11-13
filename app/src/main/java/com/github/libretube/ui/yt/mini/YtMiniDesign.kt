package com.github.libretube.ui.yt.mini

import android.animation.Animator
import android.animation.ValueAnimator
import android.content.Context
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.constraintlayout.motion.widget.MotionScene
import androidx.constraintlayout.widget.ConstraintSet
import com.github.libretube.R
import com.github.libretube.helpers.YtDesign
import com.github.libretube.ui.yt.YtDock
import com.github.libretube.ui.yt.YtDrag
import com.github.libretube.ui.yt.YtDragKind
import com.github.libretube.ui.yt.YtHapticTexture
import com.github.libretube.ui.yt.YtPalette
import com.github.libretube.ui.yt.ytScale
import com.kyant.backdrop.Backdrop
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin

/** Which ConstraintSet [YtMiniDesign.constrain] fills. */
enum class YtMiniState {
    /** Docked on the bar (the end set). */
    REST,

    /** The long-press preview (yt_peek_set, a copy of the end set). */
    PEEK,
}

/**
 * How a design's player moves: minimizing (duration and the curve of MotionScene's interpolators)
 * and the springs of the preview (stiffness and damping of SpringForce).
 */
data class YtMiniMotion(
    val duration: Int,
    val interpolator: Int = MotionScene.Transition.INTERPOLATE_EASE_OUT,
    val peekStiffness: Float = 420f,
    val peekDamping: Float = 0.78f,
    val releaseStiffness: Float = 520f,
    val releaseDamping: Float = 0.9f,
)

/** What the player shows of the video: title, channel, how far it played, whether it plays. */
data class YtMiniInfo(val title: String, val channel: String, val fraction: Float, val playing: Boolean) {
    companion object {
        val EMPTY = YtMiniInfo("", "", 0f, false)
    }
}

/** The video that plays next (the preview's row), from YtPlayerHost.nextCandidate. */
data class YtMiniNext(val id: String, val title: String, val channel: String, val thumbnail: String?)

/**
 * The views a design's View animations move (the landing, accept, reject): the composable under
 * the picture, the one over it, the box and the views in it.
 */
class YtMiniViews(
    val under: View,
    val over: View?,
    val box: View,
    val player: View,
    val title: View,
    val play: View,
    val close: View,
    /** px per dp of the player. */
    val density: Float,
) {
    /** Everything that moves together with the picture. */
    val all: List<View> get() = listOf(box, player, title, play, close)
}

/**
 * How near a dragged video is to the docked player, 0 (none, or far) to 1 (over it): its pointer
 * approaching within 80 dp ([density] px per dp) counts up to 0.6, the drop target taking it is 1.
 * Read it in draw and layout lambdas; the Glas bar reads it too (its slab is the player's glass).
 */
fun ytMiniHover(density: Float): Float {
    if (YtDock.hover || YtDrag.hoverKey == YtDrag.MINI) return 1f
    val session = YtDrag.session
    val bounds = YtDrag.miniBounds
    if (session == null || bounds == null || session.kind != YtDragKind.VIDEO) return 0f
    val p = session.pointer
    return 0.6f * YtMiniDockMath.nearness(
        p.x, p.y, bounds.left.toFloat(), bounds.top.toFloat(), bounds.right.toFloat(), bounds.bottom.toFloat(), 80f * density,
    )
}

/**
 * YT: what the composables of a design's mini player get. The per-frame values are lambdas, to be
 * read in draw and layout lambdas (graphicsLayer {}, drawBehind {}, Modifier.layout {}), never in
 * composition, so the player draws again but does not compose again while it moves. The other
 * values change rarely and are snapshot state, fine to read in composition.
 */
@Stable
class YtMiniScene(
    val design: YtMiniDesign,
    /** The design's colors; null for the classic layout. */
    val palette: YtPalette?,
    /** The screens below, for the glass designs; null for the solid ones. */
    val backdrop: Backdrop?,
    /** px per dp, the same as the density of the composition. */
    val density: Float,
) {
    /** Minimized, 0 (the page) to 1 (docked). */
    val progress: () -> Float = { YtDock.progress }

    /**
     * Minimized as the page's own transition has it, 0 to 1. Unlike [progress] it stays at 1
     * while a thrown player falls (the bar lets go of [progress] at once) and while the preview
     * runs; the seamless minimize ([YtMiniDesign.merge]) shapes the window by it. Read it in draw
     * and layout lambdas.
     */
    var pageProgress by mutableFloatStateOf(0f)

    /** The long-press preview, 0 to 1 (a spring may swing a little past 1). */
    val peek: () -> Float = { YtDock.peek }

    /** How far bar and player dip as the player lands, in px. */
    val dip: () -> Float = { YtDock.dip }

    /**
     * How near a dragged video is, 0 (none, or far) to 1 (over the player): its pointer
     * approaching within 80 dp counts up to 0.6, the drop target taking it is 1.
     */
    val hover: () -> Float = { ytMiniHover(density) }

    /** The video's SurfaceView (the Glas colour bleed copies its frames), or null. */
    var surface: () -> View? = { null }

    /** Title, channel and position, polled while the player is shown. */
    var info by mutableStateOf(YtMiniInfo.EMPTY)

    /** The next video, or null (the preview then shows no row). */
    var next by mutableStateOf<YtMiniNext?>(null)

    /** Whether the queue has a video before this one. */
    var hasPrev by mutableStateOf(false)

    /** The preview's geometry while one runs (set when it starts), else null. */
    var peekLayout by mutableStateOf<YtMiniPeekGeometry?>(null)

    /** The part of the preview under the finger. */
    var target by mutableStateOf(YtMiniTarget.NONE)

    /**
     * Where the finger took the preview, as shares (0 to 1) of the picture's width and height from
     * its top left corner (Frost thaws the frost from there); Unspecified outside a preview.
     */
    var press by mutableStateOf(androidx.compose.ui.geometry.Offset.Unspecified)

    /** Counts the landings; each new value replays a design's landing in its composables. */
    var landings by mutableIntStateOf(0)

    /** Counts play and pause toggles from the player itself; each shows the glyph flash. */
    var flashes by mutableIntStateOf(0)

    /** Whether the video plays after the last toggle (the glyph the flash shows). */
    var flashPlaying by mutableStateOf(false)

    /** Counts the drops the player did not take; each new value plays the reject look. */
    var rejects by mutableIntStateOf(0)

    /**
     * Where a finger rests on the docked box (px of the box as it is laid out, not mirrored), or
     * null. Kept only for designs that [YtMiniDesign.followsFinger]; read it in draw and layout
     * lambdas or in a snapshotFlow, it changes with every move.
     */
    var finger by mutableStateOf<Offset?>(null)

    var onPlayPause: () -> Unit = {}
    var onPrev: () -> Unit = {}
    var onNext: () -> Unit = {}
    var onOpen: () -> Unit = {}
    var onClose: () -> Unit = {}
}

/**
 * YT: one design's mini player. The host (YtMiniHost) owns the views, the transitions, the
 * preview and the drop target; a design says how they look and feel:
 * - [spec], [geometry], [peekGeometry]: the measures and where the parts sit (pure, one source
 *   for the constraints and the composables)
 * - [constrain]: the View constraints of the docked player and of its preview
 * - [Under]: drawn behind the picture, inside the box (the body, text, marks, the preview's parts)
 * - [Over]: drawn over the picture, clipped to it (glyphs, feathering, titles over the video);
 *   it moves with the video into fullscreen and picture in picture, where it is hidden
 * - [motion], [land], [accept], [reject]: how it moves
 * - [haptic]: its feel at each moment
 * Every open member has a neutral default, so a design only overrides what it does its own way.
 */
abstract class YtMiniDesign(val design: YtDesign) {
    /** The measures of the docked player. */
    abstract val spec: YtMiniSpec

    /** Minimizing and the preview's springs. */
    abstract val motion: YtMiniMotion

    /** The classic layout keeps player_scene.xml's end set and its views as they are. */
    open val classic: Boolean get() = false

    /** How much the preview enlarges the picture. */
    open val peekGrowth: Float get() = spec.peekGrowth

    /** True where the player has no play button: a double tap on the picture toggles play. */
    open val takesDoubleTap: Boolean get() = !spec.play

    /** True where the picture rounds only its top corners (Glas: its foot tucks under the bar). */
    open val roundsTopOnly: Boolean get() = false

    /**
     * The alpha of the composable behind the picture at minimize [progress] (0 to 1). By default
     * it fades in with the progress.
     */
    open fun underAlpha(progress: Float): Float = progress

    /**
     * The alpha of the composable over the picture at minimize [progress]. By default it fades in
     * with the progress; a design that paints a mask over the picture keeps it opaque and fades
     * the mask's shape itself, or the picture's rectangle would show through it.
     */
    open fun overAlpha(progress: Float): Float = progress

    /**
     * The seamless minimize (YtMiniMerge): while it minimizes, the window takes on the bar's
     * width, corners and colour and flows into it, the page below fades out, and a drag from the
     * open page is never cut at the bar's edge. Null (the default) keeps the minimize as it was.
     * A design that returns one draws its body by YtMiniMergeMath.window at [YtMiniScene.pageProgress].
     */
    open fun merge(palette: YtPalette): YtMiniMerge? = null

    /** Whether a long press on the picture opens the preview. */
    open val peeks: Boolean get() = true

    /** Whether the design answers the finger on the docked player ([YtMiniScene.finger]). */
    open val followsFinger: Boolean get() = false

    /** Whether the player's surface is glass over the screens below (it then gets a backdrop). */
    open fun usesBackdrop(palette: YtPalette): Boolean = false

    /** The tints of the play and close buttons, or null to keep theirs. */
    open fun buttonTints(palette: YtPalette): Pair<Color, Color>? = null

    /** px per dp of the player: the bar's dp, which scale with the screen width ([ytScale]). */
    open fun density(context: Context): Float =
        context.resources.displayMetrics.density * ytScale(context.resources.configuration)

    open fun geometry(boxWidth: Float, density: Float): YtMiniGeometry =
        YtMiniLayout.rest(spec.copy(peekGrowth = peekGrowth), boxWidth, density)

    open fun peekGeometry(boxWidth: Float, density: Float, hasNext: Boolean): YtMiniPeekGeometry =
        YtMiniLayout.peek(spec.copy(peekGrowth = peekGrowth), boxWidth, density, hasNext)

    /**
     * Puts the player of [state] into [set]: the docked player into the end set ([peek] null), or
     * the preview into yt_peek_set, which is a copy of the end set when this is called.
     */
    open fun constrain(set: ConstraintSet, g: YtMiniGeometry, peek: YtMiniPeekGeometry?, state: YtMiniState) {
        fun px(value: Float) = value.roundToInt()
        val m = g.margins
        when (state) {
            YtMiniState.REST -> {
                // on the bar, as wide as its surface (or the body), the picture inside
                if (m.boxWidth != null) {
                    set.constrainWidth(R.id.main_container, px(m.boxWidth))
                } else {
                    set.constrainWidth(R.id.main_container, ConstraintSet.MATCH_CONSTRAINT)
                }
                set.setMargin(R.id.main_container, ConstraintSet.START, px(m.boxStart))
                set.setMargin(R.id.main_container, ConstraintSet.END, px(m.boxEnd))
                set.constrainHeight(R.id.main_container, px(m.boxHeight))
                set.setMargin(R.id.main_container, ConstraintSet.BOTTOM, 0)
                set.constrainWidth(R.id.player, px(m.playerWidth))
                set.constrainHeight(R.id.player, px(m.playerHeight))
                set.setMargin(R.id.player, ConstraintSet.START, px(m.playerStart))
                set.setMargin(R.id.player, ConstraintSet.TOP, 0)
                set.setMargin(R.id.player, ConstraintSet.BOTTOM, px(m.playerBottom))
                for (button in listOf(R.id.close_imageView, R.id.play_imageView)) {
                    set.constrainWidth(button, px(m.button))
                    set.constrainHeight(button, px(m.button))
                }
                set.setMargin(R.id.close_imageView, ConstraintSet.END, px(m.closeEnd))
                set.setMargin(R.id.close_imageView, ConstraintSet.TOP, 0)
                set.setMargin(R.id.close_imageView, ConstraintSet.BOTTOM, px(m.buttonBottom))
                set.setMargin(R.id.play_imageView, ConstraintSet.END, px(m.playEnd))
            }

            YtMiniState.PEEK -> {
                val p = peek ?: return
                // the box grows upward from the bar (its vertical bias is 1), the picture at its
                // start; the buttons give way to the preview's pill
                set.constrainHeight(R.id.main_container, px(p.boxHeight))
                set.constrainWidth(R.id.player, px(p.player.width))
                set.constrainHeight(R.id.player, px(p.player.height))
                set.setMargin(R.id.player, ConstraintSet.START, px(p.player.left))
                set.setMargin(R.id.player, ConstraintSet.TOP, px(p.player.top))
                set.setMargin(R.id.player, ConstraintSet.BOTTOM, 0)
                set.setVerticalBias(R.id.player, 0f)
                for (view in listOf(R.id.close_imageView, R.id.play_imageView, R.id.title_textView)) {
                    set.setAlpha(view, 0f)
                }
            }
        }
    }

    /** Behind the picture, inside the box: the design's body and everything beside the picture. */
    @Composable
    abstract fun Under(scene: YtMiniScene)

    /** Over the picture, clipped to it. The neutral one shows the glyph of play and pause. */
    @Composable
    open fun Over(scene: YtMiniScene) {
        YtMiniParts.Flash(scene, YtMiniParts.flashColors(scene))
    }

    /** The feel of [moment]. */
    open fun haptic(moment: YtMoment): YtHapticTexture = YtMiniTextures.neutral(moment)

    /** The player lands on the bar (after minimizing, motion on); null for no landing. */
    open fun land(views: YtMiniViews): Animator? = null

    /** The player takes a dropped video: the box swells a little and settles. */
    open fun accept(views: YtMiniViews): Animator? = wobbleViews(views, 460L) { t ->
        val w = wobble(t, 1.2f, 5f)
        views.under.scaleY = 1f + 0.06f * w
        views.under.scaleX = 1f + 0.02f * w
    }

    /** The player does not take a dropped video: a short shake, side to side. */
    open fun reject(views: YtMiniViews): Animator? = wobbleViews(views, 380L) { t ->
        val x = 6f * views.density * wobble(t, 3f, 5f)
        views.all.forEach { it.translationX = x }
    }

    companion object {
        /** A damped swing from 0: first towards 1, then back past 0, settling at 0. */
        fun wobble(t: Float, frequency: Float, decay: Float): Float =
            exp(-decay * t) * sin(2f * PI.toFloat() * frequency * t)

        /**
         * A ValueAnimator from 0 to 1 over [duration] ms calling [frame], that puts every moved
         * property of [views] back at rest when it ends or is cancelled.
         */
        fun wobbleViews(views: YtMiniViews, duration: Long, frame: (Float) -> Unit): Animator =
            ValueAnimator.ofFloat(0f, 1f).apply {
                this.duration = duration
                interpolator = null
                views.under.pivotX = views.under.width / 2f
                views.under.pivotY = views.under.height.toFloat()
                addUpdateListener { frame(it.animatedValue as Float) }
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        views.all.forEach { it.translationX = 0f }
                        views.under.scaleX = 1f
                        views.under.scaleY = 1f
                    }
                })
                start()
            }
    }
}
