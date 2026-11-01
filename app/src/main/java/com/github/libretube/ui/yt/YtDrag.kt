package com.github.libretube.ui.yt

import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.github.libretube.api.obj.StreamItem
import com.github.libretube.helpers.YtDesign
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** What is being dragged across the screens. */
enum class YtDragKind {
    /** A video card, on its way to the mini player (payload: the StreamItem). */
    VIDEO,

    /** The search icon, on its way to a card. */
    SEARCH,

    /** The share action of the video page, on its way to the timeline. */
    SHARE,
}

/**
 * One drag across the screens. The finger stays with the composable that started it, so the
 * source moves [pointer] and every target only reads it.
 */
@Stable
class YtDragSession(
    val kind: YtDragKind,
    val payload: Any?,
    /** The lifted element, where the drag started, in window px. */
    val origin: Rect,
    pointer: Offset,
) {
    /** The finger, in window px. */
    var pointer by mutableStateOf(pointer)
}

/** A card that takes drops (the search icon), with its bounds in window px. */
data class YtDropCard(val bounds: Rect, val item: StreamItem)

/**
 * YT: drags from one screen to another (a card onto the mini player, the search icon onto a
 * card). The screens are separate compositions in separate views, so the drag lives here as
 * shared state: the source writes [session] and its pointer, the targets publish where they
 * are ([miniBounds], [cards]) and [hoverKey] says which of them the finger is over. The ghost
 * that follows the finger is drawn by [YtDragLayer], above everything.
 */
object YtDrag {
    /** The [hoverKey] of the docked mini player. */
    const val MINI = "mini"

    /** The drag in progress, or null. */
    var session by mutableStateOf<YtDragSession?>(null)

    /**
     * The window rect of the docked mini player while it sits on the bar, else null (set by
     * YtMiniPlayer). A new Rect for every change: never change the one that is there.
     */
    var miniBounds by mutableStateOf<android.graphics.Rect?>(null)

    /** The cards on screen that take drops, by a key of the card's screen. */
    val cards: SnapshotStateMap<String, YtDropCard> = mutableStateMapOf()

    /** The target under the finger: [MINI], a key of [cards], or null. */
    var hoverKey by mutableStateOf<String?>(null)

    /**
     * How the dragged thing looks, drawn centered at the finger. Set by the source before it
     * starts the session. The layer gives it no theme: a ghost that needs the design's type and
     * colors wraps itself in YtTheme.
     */
    var ghost by mutableStateOf<(@Composable (YtDragSession) -> Unit)?>(null)
}

/**
 * YT: the layer for the drag ghost, above everything in the window (also above the player
 * container). It never takes a touch and draws nothing while no drag runs, so the screens look
 * and behave exactly as without it.
 */
object YtDragLayer {
    private const val TAG = "yt_drag_layer"

    /** Adds the layer to [activity]'s content; once per activity, only for the designs. */
    fun attach(activity: ComponentActivity) {
        // the classic layout has no drag sources
        if (YtDesign.current().palette == null) return
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        if (content.findViewWithTag<View>(TAG) != null) return

        val host = Host(activity).apply {
            tag = TAG
            // above the player container (20dp) even if more views come into the content later;
            // without an outline it casts no shadow
            elevation = 24f * resources.displayMetrics.density
            outlineProvider = null
            isClickable = false
            isLongClickable = false
            isFocusable = false
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            setViewTreeLifecycleOwner(activity)
            setViewTreeSavedStateRegistryOwner(activity)
            setViewTreeViewModelStoreOwner(activity)
        }
        val layer = ComposeView(activity).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent { Layer() }
        }
        host.addView(layer, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        // a drag cannot outlive its screens
        host.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit

            override fun onViewDetachedFromWindow(v: View) {
                YtDrag.session = null
                YtDrag.hoverKey = null
            }
        })
        content.addView(host, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    /** Lets every touch through to the views below, as if it were not there. */
    private class Host(context: Context) : FrameLayout(context) {
        override fun dispatchTouchEvent(ev: MotionEvent): Boolean = false

        override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean = false
    }

    @Composable
    private fun Layer() {
        var root by remember { mutableStateOf(Offset.Zero) }
        Box(Modifier.fillMaxSize().onGloballyPositioned { root = it.positionInWindow() }) {
            val session = YtDrag.session
            val ghost = YtDrag.ghost
            if (session != null && ghost != null) {
                key(session) { Ghost(session, ghost) { root } }
            }
        }
    }

    /** The ghost follows the finger on a spring, so it trails a little and settles on it. */
    @Composable
    private fun Ghost(session: YtDragSession, ghost: @Composable (YtDragSession) -> Unit, root: () -> Offset) {
        val follow = remember { Animatable(session.pointer, Offset.VectorConverter) }
        val motion = rememberYtMotionAllowed()
        LaunchedEffect(Unit) {
            snapshotFlow { session.pointer }.collect { target ->
                if (motion) {
                    launch { follow.animateTo(target, spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMedium)) }
                } else {
                    follow.snapTo(target)
                }
            }
        }
        Box(
            Modifier.layout { measurable, constraints ->
                val placeable = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
                layout(placeable.width, placeable.height) {
                    // read here, so the ghost moves without recomposing
                    val at = follow.value - root()
                    placeable.place((at.x - placeable.width / 2f).roundToInt(), (at.y - placeable.height / 2f).roundToInt())
                }
            },
        ) {
            ghost(session)
        }
    }
}
