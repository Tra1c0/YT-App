package com.github.libretube.ui.yt.builder

import android.animation.ValueAnimator
import android.graphics.BlurMaskFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.Build
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.CacheDrawScope
import androidx.compose.ui.draw.DrawResult
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onVisibilityChanged
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.github.libretube.ui.yt.LocalYtMotion
import com.github.libretube.ui.yt.YtPlaybackClock
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/*
 * YT: the animator in the real "Eigenes" design. Each drawn component instance (a card, a chip,
 * the header, a tab) gets a YtAnimInstance when its component has animations: an engine
 * (YtAnimEngine), the controller with the state machine, and a frame counter the drawing reads.
 *
 * Frames: a loop runs withFrameNanos only while a run is active, and only while the component can
 * be seen: the screen at least STARTED, its view shown (not in a hidden fragment), the component in
 * the window (onVisibilityChanged) and, where the host says so, shown by the host (the mini player
 * while docked). The time away is not counted: a loop that repeats forever stands still while hidden
 * and goes on from there. Values are read in the draw phase: transforms,
 * opacity and blur in graphicsLayer, colours/radii/strokes/shadows/glow/shimmer in drawWithCache's
 * draw (paints, shaders and paths are built once per size and reused), text colour in BasicText's
 * colour producer. Only a size change (WIDTH/HEIGHT) is read in layout, as it moves the neighbours.
 * Nothing composes per frame and nothing is allocated per frame.
 *
 * Motion off (Darstellung → "Animationen der Designs") or Android's "remove animations": every
 * start jumps to its end state, nothing runs, scroll-linked animations rest.
 */

/** Signals of the app the animations follow, written by the screens of "Eigenes". */
object YtAnimSignals {
    /** How far the home feed is scrolled, dp from the top (0 at the top). */
    var feedScrollDp by mutableFloatStateOf(0f)
        private set

    /** The last direction: 1 down the list, -1 back up, 0 none yet. */
    var feedScrollDir by mutableIntStateOf(0)
        private set

    var feedLoading by mutableStateOf(false)

    fun scrolled(deltaDp: Float, atTop: Boolean) {
        feedScrollDp = if (atTop) 0f else (feedScrollDp + deltaDp).coerceAtLeast(0f)
        if (abs(deltaDp) > 0.5f) {
            val dir = if (deltaDp > 0f) 1 else -1
            if (dir != feedScrollDir) feedScrollDir = dir
        }
    }
}

/**
 * What the animator measured in the app, for Diagnose ("Animator"): the frame step the loop
 * sees (16.7 ms at 60 Hz, 8.3 ms at 120 Hz) and, for the last finished set of runs, the length
 * it was planned to take and the time it really ran from its first to its last frame. Main thread.
 */
object YtAnimStats {
    var instances = 0
    var starts = 0L
    private var frames = 0L
    private var stepSum = 0.0
    private var steps = 0
    private var lastPlanned = -1L
    private var lastRan = -1.0
    private var lastStep = 0.0

    internal fun frame(stepMs: Double) {
        frames++
        if (stepMs > 0.0 && stepMs < 100.0) {
            stepSum += stepMs
            steps++
        }
    }

    internal fun finished(plannedMs: Long, ranMs: Double) {
        lastPlanned = plannedMs
        lastRan = ranMs
        lastStep = if (steps > 0) stepSum / steps else 0.0
        stepSum = 0.0
        steps = 0
    }

    fun diagnosis(): String {
        val doc = YtBuilderActive.doc
        val count = doc?.animations?.items?.size ?: 0
        val motion = if (com.github.libretube.ui.yt.YtMotionPrefs.enabled && ValueAnimator.areAnimatorsEnabled()) "motion on" else "motion off (end states)"
        val last = if (lastPlanned < 0) {
            "nothing finished yet"
        } else {
            val hz = if (lastStep > 0.0) (1000.0 / lastStep).roundToInt() else 0
            "last: planned ${lastPlanned} ms, ran ${lastRan.roundToInt()} ms, frame step ${"%.1f".format(java.util.Locale.ROOT, lastStep)} ms (≈$hz Hz)"
        }
        return "$count in the theme, $motion, $instances live, $starts starts, $frames frames; $last"
    }
}

/** How a theme source plays animations: the app's (live, with triggers), the editor's (playhead/test), none. */
enum class YtAnimMode { APP, EDITOR, NONE }

/** The editor's side of an instance: the playhead and the test events. */
interface YtAnimLink {
    /** What the playhead shows for [kind] (animations with schedule offsets), or null for live. Snapshot state. */
    fun preview(kind: YtComponentKind): List<Pair<YtAnim, Int>>?

    /** The playhead, ms. Snapshot state. */
    val previewMs: Double

    fun attach(inst: YtAnimInstance)
    fun detach(inst: YtAnimInstance)
}

/**
 * What the host knows about the instance besides its states: whether it is on screen for the
 * appear trigger ([visible]: cards half in the feed, the mini player docked), the touches and
 * events it sends itself ([events]: the mini player, whose touches the player page takes; TOUCH_DOWN
 * and TOUCH_UP also set the pressed state), and whether it is shown at all ([shown], snapshot
 * reads; false pauses the frame loop).
 */
@Stable
class YtAnimInput(
    val visible: (() -> Boolean)? = null,
    val events: kotlinx.coroutines.flow.Flow<YtTriggerKind>? = null,
    val shown: (() -> Boolean)? = null,
) {
    companion object {
        val NONE = YtAnimInput()
    }
}

/** Where watched touches go: an instance, or the editor's test mode (all edited instances). */
interface YtAnimTouchSink {
    /** A touch went down (pressed on) or up/cancelled (pressed off). */
    fun touch(down: Boolean)
    fun event(t: YtTriggerKind)

    /** A double tap starts something here: a tap waits for the double-tap window (YtTapArbiter). */
    val waitsForDoubleTap: Boolean get() = false
}

/** The animations of one drawn component instance. */
@Stable
class YtAnimInstance internal constructor(val kind: YtComponentKind, private val src: YtThemeSource, index: YtAnimIndex) : YtAnimRest, YtAnimTouchSink {
    val engine = YtAnimEngine(this)
    val controller = YtAnimController(index, engine)

    /** Counts frames and changes; the drawing reads it, so it draws again (no composition). */
    val frame = mutableIntStateOf(0)

    /** A run is active: the frame loop is needed. */
    var running by mutableStateOf(false)
        private set

    /** The machine's states as render bits (state patches); composition reads it. */
    var stateBits by mutableIntStateOf(0)
        private set

    /** Bumped when the animations were edited, so the node views fetch their channels again. */
    var indexVersion by mutableIntStateOf(0)
        private set

    private val nodes = HashMap<String, YtNodeAnim>()

    /** In the window (onVisibilityChanged), its view shown, and what the host says ([hostShown]). Snapshot state. */
    var onScreen by mutableStateOf(true)
    var viewShown by mutableStateOf(true)
    var hostShown: (() -> Boolean)? = null

    /** The component can be seen: the frame loop may run. */
    val shown: Boolean get() = onScreen && viewShown && hostShown?.invoke() != false

    override val waitsForDoubleTap: Boolean get() = controller.index.doubleTap

    /** The loop stopped with a run still going (hidden): its measurement no longer means anything. */
    internal fun paused() {
        loopStart = -1L
        lastFrame = -1L
        planned = 0L
    }

    // measuring: the first and last frame of the current loop, and how long the started runs should take
    private var loopStart = -1L
    private var lastFrame = -1L
    private var planned = 0L

    internal val onFrame: (Long) -> Unit = { t ->
        if (loopStart < 0L) loopStart = t
        if (lastFrame >= 0L) YtAnimStats.frame((t - lastFrame) / 1_000_000.0)
        lastFrame = t
        engine.tick(t)
        changed()
        if (!engine.running) {
            if (planned > 0L && src.animMode == YtAnimMode.APP) YtAnimStats.finished(planned, (t - loopStart) / 1_000_000.0)
            loopStart = -1L
            lastFrame = -1L
            planned = 0L
        }
    }

    init {
        controller.onStart = { a, offset ->
            YtAnimStats.starts++
            // the planned end, from the loop's first frame: what is left of the runs going on and this one
            val end = YtAnimEval.totalMs(a)?.plus(offset)
            val sinceStart = if (loopStart >= 0L && lastFrame >= 0L) (lastFrame - loopStart) / 1_000_000 else 0L
            if (end != null) planned = maxOf(planned, sinceStart + end)
        }
    }

    /** The editor's last playhead picture, shown again after an edit rebuilt the index. */
    private var lastPreview: List<Pair<YtAnim, Int>>? = null
    private var lastPreviewMs = 0.0

    fun update(index: YtAnimIndex) {
        if (index === controller.index) return
        controller.update(index)
        nodes.clear()
        indexVersion++
        lastPreview?.let { engine.preview(it, lastPreviewMs) }
        changed()
    }

    /** The animation of node [id], or null when nothing animates it. */
    internal fun nodeAnim(id: String): YtNodeAnim? {
        val idx = controller.index
        if (id !in idx.targets) return null
        return nodes.getOrPut(id) { YtNodeAnim(this, engine.node(id), idx.props(id)) }
    }

    override fun event(t: YtTriggerKind) {
        controller.event(t)
        changed()
    }

    fun state(s: String, on: Boolean) {
        controller.state(s, on)
        changed()
    }

    override fun touch(down: Boolean) {
        controller.state(YtStateNames.PRESSED, down)
        controller.event(if (down) YtTriggerKind.TOUCH_DOWN else YtTriggerKind.TOUCH_UP)
        changed()
    }

    fun scroll(dp: Float) {
        controller.scroll(dp)
        changed()
    }

    fun preview(list: List<Pair<YtAnim, Int>>, ms: Double) {
        lastPreview = list
        lastPreviewMs = ms
        engine.preview(list, ms)
        changed()
    }

    fun rest() {
        lastPreview = null
        engine.clear()
        changed()
    }

    private fun changed() {
        frame.intValue++
        running = engine.running
        stateBits = controller.machine.bits()
    }

    /** The node's own colour of a colour property, resolved (for "own colour" keyframes). */
    override fun color(node: String, prop: YtAnimProp): Int? {
        val n = src.resolve(kind, node, 0)?.node ?: return null
        val t = src.resolver ?: return null
        return runCatching {
            when (prop) {
                YtAnimProp.FILL_COLOR -> n.style.fills.firstOrNull { it.enabled }?.let { YtBuilderPaint.color(t.resolve(it)).toArgbInt() }
                YtAnimProp.STROKE_COLOR -> n.style.strokes.firstOrNull { it.enabled }?.let { YtBuilderPaint.color(t.resolve(it.paint)).toArgbInt() }
                YtAnimProp.TEXT_COLOR -> YtBuilderPaint.color(t.resolve(n.text?.color ?: YtPaint.token(YtRole.TEXT))).toArgbInt()
                else -> null
            }
        }.getOrNull()
    }

    private fun androidx.compose.ui.graphics.Color.toArgbInt(): Int = toArgb()
}

/** One node's channels and which drawing phases it needs. */
@Stable
internal class YtNodeAnim(val inst: YtAnimInstance, val node: YtAnimNode, val props: Set<YtAnimProp>) {
    val layer = props.any { it.phase == YtPropPhase.LAYER }
    val size = props.any { it.phase == YtPropPhase.LAYOUT }
    val style = props.any { it.phase == YtPropPhase.STYLE || it.phase == YtPropPhase.OVERLAY }
    val text = YtAnimProp.TEXT_COLOR in props
    val blur = YtAnimProp.BLUR in props && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    private var blurs: Array<RenderEffect?>? = null

    /** A blur of [px], made once per whole pixel and kept: a running blur allocates only the first time. */
    fun blurOf(px: Float): RenderEffect? {
        if (px < 0.5f) return null
        val key = px.roundToInt().coerceIn(1, MAX_BLUR_PX)
        val cache = blurs ?: arrayOfNulls<RenderEffect>(MAX_BLUR_PX + 1).also { blurs = it }
        return cache[key] ?: BlurEffect(key.toFloat(), key.toFloat(), TileMode.Decal).also { cache[key] = it }
    }

    companion object {
        const val MAX_BLUR_PX = 400
    }
}

val LocalYtAnim = staticCompositionLocalOf<YtAnimInstance?> { null }

/** Motion allowed: the design motion switch and Android's animator scale. */
@Composable
private fun ytAnimMotion(): Boolean = LocalYtMotion.current && ValueAnimator.areAnimatorsEnabled()

/**
 * The instance of component [kind] drawn from [src], or null when it has no animations (then
 * nothing is added: no loop, no touch observer). [hostStates]: the render bits the host sets
 * (selected chip or tab).
 */
@Composable
internal fun rememberYtAnimInstance(src: YtThemeSource, kind: YtComponentKind, hostStates: Int, input: YtAnimInput): YtAnimInstance? {
    if (src.animMode == YtAnimMode.NONE) return null
    val index = src.animIndex(kind) ?: return null
    val inst = remember(src, kind) {
        YtAnimInstance(kind, src, index).also { i ->
            i.controller.initial(YtStateNames.SELECTED, hostStates and YtRenderStates.SELECTED != 0)
            if (src.animMode == YtAnimMode.APP) {
                i.controller.initial(YtStateNames.LOADING, YtAnimSignals.feedLoading)
                i.controller.initial(YtStateNames.ACTIVE, YtPlaybackClock.clock.value.playing)
            }
        }
    }
    androidx.compose.runtime.SideEffect { inst.update(index) }
    if (src.animMode == YtAnimMode.APP) {
        androidx.compose.runtime.DisposableEffect(inst) {
            YtAnimStats.instances++
            onDispose { YtAnimStats.instances-- }
        }
    }
    val motion = ytAnimMotion()
    inst.engine.snap = src.animMode == YtAnimMode.APP && !motion
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    // the frame loop: only while something runs, only while the screen is started and the component shown
    LaunchedEffect(inst, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            inst.engine.resetClock()
            while (true) {
                snapshotFlow { inst.running && inst.shown }.first { it }
                // the time the loop was idle or hidden does not count: the first frame takes none
                inst.engine.resetClock()
                while (inst.engine.running && inst.shown) withFrameNanos(inst.onFrame)
                if (inst.engine.running) inst.paused()
            }
        }
    }
    if (src.animMode == YtAnimMode.APP) {
        inst.hostShown = input.shown
        // a hidden fragment keeps its screen STARTED: its view is not shown then
        val view = androidx.compose.ui.platform.LocalView.current
        androidx.compose.runtime.DisposableEffect(inst, view) {
            val check = android.view.ViewTreeObserver.OnGlobalLayoutListener { inst.viewShown = view.isShown }
            val attach = object : android.view.View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: android.view.View) {
                    inst.viewShown = v.isShown
                    v.viewTreeObserver.addOnGlobalLayoutListener(check)
                }

                override fun onViewDetachedFromWindow(v: android.view.View) {
                    v.viewTreeObserver.removeOnGlobalLayoutListener(check)
                }
            }
            view.addOnAttachStateChangeListener(attach)
            if (view.isAttachedToWindow) attach.onViewAttachedToWindow(view)
            onDispose {
                view.removeOnAttachStateChangeListener(attach)
                view.viewTreeObserver.removeOnGlobalLayoutListener(check)
            }
        }
    }

    if (src.animMode == YtAnimMode.EDITOR) {
        val link = src.hooks?.anim
        if (link != null) {
            androidx.compose.runtime.DisposableEffect(inst, link) {
                link.attach(inst)
                onDispose { link.detach(inst) }
            }
            LaunchedEffect(inst, link) {
                var wasPreview = false
                snapshotFlow { link.preview(kind) to link.previewMs }.collect { (list, ms) ->
                    if (list != null) {
                        inst.preview(list, ms)
                        wasPreview = true
                    } else if (wasPreview) {
                        inst.rest()
                        wasPreview = false
                    }
                }
            }
        }
        return inst
    }

    // what the host sends itself (the mini player's touches)
    val events = input.events
    if (events != null) {
        LaunchedEffect(inst, events) {
            events.collect { e ->
                when (e) {
                    YtTriggerKind.TOUCH_DOWN -> inst.touch(true)
                    YtTriggerKind.TOUCH_UP -> inst.touch(false)
                    else -> inst.event(e)
                }
            }
        }
    }
    // appear: on screen (cards: half visible in the feed), and again when the app comes back
    val visible = input.visible
    LaunchedEffect(inst, visible, lifecycle) {
        if (visible == null) {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { inst.event(YtTriggerKind.APPEAR) }
        } else {
            var seen = false
            snapshotFlow { visible() }.distinctUntilChanged().collect { on ->
                if (on) {
                    seen = true
                    inst.event(YtTriggerKind.APPEAR)
                } else if (seen) {
                    inst.event(YtTriggerKind.DISAPPEAR)
                }
            }
        }
    }
    // selection from the host (chips, tabs): a change is a state change and an event
    LaunchedEffect(inst, hostStates) {
        val on = hostStates and YtRenderStates.SELECTED != 0
        if (inst.controller.machine.isOn(YtStateNames.SELECTED) != on) {
            inst.state(YtStateNames.SELECTED, on)
            inst.event(if (on) YtTriggerKind.SELECTED else YtTriggerKind.DESELECTED)
        }
    }
    if (index.scroll) {
        LaunchedEffect(inst) {
            launch { snapshotFlow { YtAnimSignals.feedScrollDp }.collect { inst.scroll(it) } }
            snapshotFlow { YtAnimSignals.feedScrollDir }.drop(1).collect { d ->
                if (d > 0) inst.event(YtTriggerKind.SCROLL_DOWN) else if (d < 0) inst.event(YtTriggerKind.SCROLL_UP)
            }
        }
    }
    if (index.loading) {
        LaunchedEffect(inst) {
            snapshotFlow { YtAnimSignals.feedLoading }.drop(1).collect { on ->
                inst.state(YtStateNames.LOADING, on)
                inst.event(if (on) YtTriggerKind.LOADING_START else YtTriggerKind.LOADING_END)
            }
        }
    }
    if (index.player) {
        LaunchedEffect(inst) {
            YtPlaybackClock.clock.map { it.playing }.distinctUntilChanged().drop(1).collect { on ->
                inst.state(YtStateNames.ACTIVE, on)
                inst.event(if (on) YtTriggerKind.PLAYING else YtTriggerKind.PAUSED)
            }
        }
    }
    return inst
}

/**
 * Watches the touches on the component without taking them (the cards keep their swipe, hold
 * and click): down/up (pressed state), tap, double tap, long press. Where a double tap starts
 * something, a tap waits for the double-tap window and two quick taps are one double tap only
 * (YtTapArbiter); elsewhere taps play at once. Every end of a gesture, a cancel included, releases.
 */
internal fun Modifier.ytAnimTouch(inst: YtAnimTouchSink): Modifier = pointerInput(inst) {
    val slop = viewConfiguration.touchSlop
    val longMs = viewConfiguration.longPressTimeoutMillis
    val taps = YtTapArbiter(inst.waitsForDoubleTap, viewConfiguration.doubleTapTimeoutMillis, slop)
    awaitEachGesture {
        taps.waitForDouble = inst.waitsForDoubleTap
        // a tap held back for its double: let go once the window ends without a second touch
        var early: androidx.compose.ui.input.pointer.PointerInputChange? = null
        val wait = taps.waitMs(android.os.SystemClock.uptimeMillis())
        if (wait != null) {
            early = withTimeoutOrNull(wait.coerceAtLeast(1L)) { awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial) }
            if (early == null) taps.expire(android.os.SystemClock.uptimeMillis())?.let(inst::event)
        }
        val down = early ?: awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        taps.down(down.uptimeMillis, down.position.x, down.position.y)?.let(inst::event)
        var upAt = down.uptimeMillis
        var pressed = true
        var moved = false
        var released = false
        var long = false
        inst.touch(true)
        try {
            val up: Boolean? = withTimeoutOrNull(longMs) {
                var result: Boolean? = null
                while (result == null) {
                    val ev = awaitPointerEvent(PointerEventPass.Initial)
                    val c = ev.changes.firstOrNull { it.id == down.id }
                    if (c == null) {
                        result = false
                    } else {
                        if (!moved && (c.position - down.position).getDistance() > slop) {
                            moved = true
                            // a scroll or drag began: no longer pressed
                            pressed = false
                            inst.touch(false)
                        }
                        if (!c.pressed) {
                            upAt = c.uptimeMillis
                            result = true
                        }
                    }
                }
                result
            }
            if (up == null) {
                if (!moved) {
                    long = true
                    inst.event(YtTriggerKind.LONG_PRESS)
                }
                while (true) {
                    val ev = awaitPointerEvent(PointerEventPass.Initial)
                    val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                    if (!c.pressed) break
                }
                released = true
            } else {
                released = up
            }
        } finally {
            if (pressed) inst.touch(false)
        }
        val decided = if (released && !moved && !long) taps.tap(upAt, down.position.x, down.position.y) else taps.other()
        decided?.let(inst::event)
    }
}

/**
 * Watches for a press without taking the touch, for a pressed look without animations: on at the
 * touch, off when it lifts, is cancelled or moves past the slop (a scroll is no press).
 */
internal fun Modifier.ytPressWatch(onPress: (Boolean) -> Unit): Modifier = pointerInput(Unit) {
    val slop = viewConfiguration.touchSlop
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        onPress(true)
        try {
            while (true) {
                val ev = awaitPointerEvent(PointerEventPass.Initial)
                val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                if (!c.pressed || (c.position - down.position).getDistance() > slop) break
            }
        } finally {
            onPress(false)
        }
    }
}

/** Tells [inst] whether the component is in the window, for the frame loop. */
internal fun Modifier.ytAnimShown(inst: YtAnimInstance): Modifier = onVisibilityChanged(0L, 0f) { inst.onScreen = it }

/**
 * The states YT itself knows, for a component without animations that has a look for them:
 * loading (the home feed loads) and active (the player plays). With animations the state machine
 * sets them instead.
 */
@Composable
internal fun rememberYtHostStateBits(mask: Int): Int {
    var bits = 0
    val loading = YtRenderStates.bitOf(YtStateNames.LOADING)
    val active = YtRenderStates.bitOf(YtStateNames.ACTIVE)
    if (mask and loading != 0 && YtAnimSignals.feedLoading) bits = bits or loading
    if (mask and active != 0) {
        val flow = remember { YtPlaybackClock.clock.map { it.playing }.distinctUntilChanged() }
        val playing by flow.collectAsState(YtPlaybackClock.clock.value.playing)
        if (playing) bits = bits or active
    }
    return bits
}

/**
 * The root's move, scale, turn and opacity on a surface the host draws itself (the bar of
 * "Eigenes": its shadow and clip move with it). Read in the layer only.
 */
internal fun Modifier.ytAnimHostLayer(a: YtNodeAnim): Modifier = this.then(Modifier.graphicsLayer {
    a.inst.frame.intValue
    val n = a.node
    alpha = n.float(YtAnimProp.OPACITY).coerceIn(0f, 1f)
    rotationZ = n.float(YtAnimProp.ROTATION)
    translationX = n.float(YtAnimProp.X) * density
    translationY = n.float(YtAnimProp.Y) * density
    scaleX = n.float(YtAnimProp.SCALE_X)
    scaleY = n.float(YtAnimProp.SCALE_Y)
    transformOrigin = androidx.compose.ui.graphics.TransformOrigin(n.float(YtAnimProp.PIVOT_X), n.float(YtAnimProp.PIVOT_Y))
})

/** WIDTH/HEIGHT: the node's box grows or shrinks by the animated dp (as far as its parent allows). */
internal fun Modifier.ytAnimSize(a: YtNodeAnim): Modifier = layout { m, c ->
    a.inst.frame.intValue
    val dw = (a.node.float(YtAnimProp.WIDTH) * density).roundToInt()
    val dh = (a.node.float(YtAnimProp.HEIGHT) * density).roundToInt()
    val p = m.measure(c)
    val w = (p.width + dw).coerceIn(c.minWidth, c.maxWidth)
    val h = (p.height + dh).coerceIn(c.minHeight, c.maxHeight)
    layout(w, h) { p.placeRelative(0, 0) }
}

// ---- the animated style ----

/** Blur filters by whole pixel radius, shared (a running blur creates each radius once). */
private object YtBlurCache {
    // an array, not a map: an Int key would be boxed on every lookup
    private val filters = arrayOfNulls<BlurMaskFilter>(401)

    fun of(px: Float): BlurMaskFilter? {
        if (px < 0.5f) return null
        val key = px.roundToInt().coerceIn(1, 400)
        return filters[key] ?: BlurMaskFilter(key.toFloat(), BlurMaskFilter.Blur.NORMAL).also { filters[key] = it }
    }
}

/**
 * The fills, strokes, drop shadows, glow and shimmer of a node whose style animates, drawn on
 * the native canvas with paints, shaders, matrices and paths made once per size; a frame only
 * changes their numbers.
 */
internal fun Modifier.ytAnimStyle(style: YtStyle, src: YtThemeSource, a: YtNodeAnim): Modifier =
    drawWithCache {
        try {
            buildAnimStyle(style, src, a)
        } catch (e: Exception) {
            // a style the native canvas cannot build: the node draws its content only
            onDrawWithContent { drawContent() }
        }
    }

private fun CacheDrawScope.buildAnimStyle(style: YtStyle, src: YtThemeSource, a: YtNodeAnim): DrawResult {
    val t = src.resolver
    val d = density
    val w = size.width
    val h = size.height
    val rtl = layoutDirection == LayoutDirection.Rtl
    val c = style.corners
    val baseTl = (if (rtl) c.topEnd else c.topStart)
    val baseTr = (if (rtl) c.topStart else c.topEnd)
    val baseBr = (if (rtl) c.bottomStart else c.bottomEnd)
    val baseBl = (if (rtl) c.bottomEnd else c.bottomStart)
    val smooth = c.smoothing.coerceIn(0f, 1f)

    class Fill(val paint: android.graphics.Paint, val solid: Boolean, val own: Int, val shader: Shader?, val matrix: Matrix, val radial: Boolean, val angle: Float, val base: Matrix? = null)
    val fills = style.fills.filter { it.enabled }.mapNotNull { p0 ->
        val p = runCatching { t?.resolve(p0) ?: p0 }.getOrNull() ?: return@mapNotNull null
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        when (p.type) {
            YtPaintType.SOLID -> {
                paint.color = p.color
                Fill(paint, true, p.color, null, Matrix(), false, 0f)
            }
            YtPaintType.IMAGE -> {
                // the image covering the box; its placeholder colour until it is loaded (the read redraws)
                YtBuilderImages.version
                val name = p.image
                val dir = src.assets
                val bitmap = if (name != null && dir != null && YtThemeZip.safeAssetName(name)) YtBuilderImages.get(java.io.File(dir, name)) else null
                if (bitmap == null) {
                    paint.color = p.color
                    Fill(paint, false, p.color, null, Matrix(), false, 0f)
                } else {
                    val sh = android.graphics.BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
                    val base = YtBuilderImages.coverMatrix(bitmap.width, bitmap.height, w, h)
                    sh.setLocalMatrix(base)
                    paint.shader = sh
                    Fill(paint, false, 0, sh, Matrix(), false, 0f, base)
                }
            }
            YtPaintType.LINEAR -> {
                val stops = YtColor.normalizeStops(p.stops, p.color)
                val rad = p.angle * Math.PI.toFloat() / 180f
                val dx = cos(rad)
                val dy = sin(rad)
                val half = (abs(w * dx) + abs(h * dy)) / 2f
                val sh = LinearGradient(w / 2f - dx * half, h / 2f - dy * half, w / 2f + dx * half, h / 2f + dy * half, stops.map { it.color }.toIntArray(), stops.map { it.at }.toFloatArray(), Shader.TileMode.CLAMP)
                paint.shader = sh
                Fill(paint, false, 0, sh, Matrix(), false, p.angle)
            }
            YtPaintType.RADIAL -> {
                val stops = YtColor.normalizeStops(p.stops, p.color)
                val sh = RadialGradient(w * p.centerX, h * p.centerY, max(1f, p.radius * max(w, h)), stops.map { it.color }.toIntArray(), stops.map { it.at }.toFloatArray(), Shader.TileMode.CLAMP)
                paint.shader = sh
                Fill(paint, false, 0, sh, Matrix(), true, 0f)
            }
        }
    }
    class StrokeDraw(val paint: android.graphics.Paint, val width: Float, val own: Int, val solid: Boolean)
    val strokes = style.strokes.filter { it.enabled && it.width > 0f }.mapNotNull { s ->
        val p = runCatching { t?.resolve(s.paint) ?: s.paint }.getOrNull() ?: return@mapNotNull null
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { this.style = android.graphics.Paint.Style.STROKE }
        val solid = p.type == YtPaintType.SOLID
        paint.color = YtBuilderPaint.color(p).toArgb()
        StrokeDraw(paint, s.width, paint.color, solid)
    }
    class ShadowDraw(val s: YtShadow, val paint: android.graphics.Paint)
    val drops = style.shadows.filter { it.enabled && !it.inner }.map { s -> ShadowDraw(s, android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = s.color }) }
    // inner shadows keep their own values (the SHADOW properties move the drop shadows)
    class InnerDraw(val s: YtShadow, val paint: android.graphics.Paint, val frame: android.graphics.Path)
    val inners = style.shadows.filter { it.enabled && it.inner }.map { s ->
        InnerDraw(
            s,
            android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = s.color
                maskFilter = YtBlurCache.of(s.blur * d / 2f)
            },
            android.graphics.Path().apply { fillType = android.graphics.Path.FillType.EVEN_ODD },
        )
    }
    val innerTemp = android.graphics.Path()
    val glowColor = style.shadows.firstOrNull { it.enabled && it.x == 0f && it.y == 0f }?.color?.let { it or 0xFF000000.toInt() }
        ?: runCatching { t?.argb(YtRole.ACCENT) }.getOrNull() ?: 0xFFFFFFFF.toInt()
    val glowPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = glowColor }
    val band = max(1f, 0.45f * w)
    val shimmerShader = LinearGradient(0f, 0f, band, 0f, intArrayOf(0x00FFFFFF, 0x55FFFFFF, 0x00FFFFFF), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
    val shimmerMatrix = Matrix()
    val shimmerPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { shader = shimmerShader }

    // paths reused: rebuilt only when the animated radius or stroke changes
    val shape = android.graphics.Path()
    val grown = android.graphics.Path()
    val inset = android.graphics.Path()
    val rect = RectF()
    val radii = FloatArray(8)
    var lastCorner = Float.NaN
    var lastSpread = Float.NaN
    var lastInset = Float.NaN
    var lastInsetCorner = Float.NaN

    fun build(path: android.graphics.Path, grow: Float, extra: Float) {
        // grow > 0: outward by grow px (shadow spread), < 0: inward (stroke inset)
        val x0 = -grow
        val y0 = -grow
        val x1 = w + grow
        val y1 = h + grow
        ytCornerPath(path, rect, radii, x0, y0, x1, y1, (baseTl + extra) * d + grow, (baseTr + extra) * d + grow, (baseBr + extra) * d + grow, (baseBl + extra) * d + grow, smooth)
    }

    fun DrawScope.behind() {
        val n = a.node
        val extra = n.float(YtAnimProp.CORNERS)
        if (extra != lastCorner) {
            build(shape, 0f, extra)
            for (i in inners) {
                // the frame around the moved, shrunk outline; drawn clipped to the element
                build(innerTemp, -i.s.spread * d, extra)
                i.frame.rewind()
                i.frame.addRect(-w - 400f * d, -h - 400f * d, 2f * w + 400f * d, 2f * h + 400f * d, android.graphics.Path.Direction.CW)
                i.frame.addPath(innerTemp, i.s.x * d, i.s.y * d)
            }
            lastCorner = extra
            lastSpread = Float.NaN
            lastInset = Float.NaN
        }
        drawIntoCanvas { canvas ->
            val nc = canvas.nativeCanvas
            // drop shadows and glow: outside the element only
            val glow = n.float(YtAnimProp.GLOW)
            if (drops.isNotEmpty() || glow > 0.05f) {
                val save = nc.save()
                nc.clipOutPath(shape)
                for (sd in drops) {
                    val s = sd.s
                    val spread = s.spread
                    if (spread != lastSpread) {
                        build(grown, spread * d, extra)
                        lastSpread = spread
                    }
                    sd.paint.color = s.color
                    sd.paint.alpha = (YtColor.alpha(s.color) * n.float(YtAnimProp.SHADOW_ALPHA)).roundToInt().coerceIn(0, 255)
                    sd.paint.maskFilter = YtBlurCache.of(((s.blur + n.float(YtAnimProp.SHADOW_BLUR)).coerceAtLeast(0f)) * d / 2f)
                    val ox = (s.x + n.float(YtAnimProp.SHADOW_X)) * d
                    val oy = (s.y + n.float(YtAnimProp.SHADOW_Y)) * d
                    nc.translate(ox, oy)
                    nc.drawPath(if (spread != 0f) grown else shape, sd.paint)
                    nc.translate(-ox, -oy)
                }
                if (glow > 0.05f) {
                    glowPaint.maskFilter = YtBlurCache.of(glow * d)
                    nc.drawPath(shape, glowPaint)
                }
                nc.restoreToCount(save)
            }
            val angle = n.float(YtAnimProp.GRADIENT_ANGLE)
            val shift = n.float(YtAnimProp.GRADIENT_SHIFT)
            var firstSolid = true
            for (f in fills) {
                if (f.solid) {
                    f.paint.color = if (firstSolid) n.argb(YtAnimProp.FILL_COLOR, f.own) else f.own
                    firstSolid = false
                } else if (f.shader != null) {
                    if (f.base != null) f.matrix.set(f.base) else f.matrix.reset()
                    if (angle != 0f) f.matrix.postRotate(angle, w / 2f, h / 2f)
                    if (shift != 0f) {
                        val rad = (f.angle + angle) * Math.PI.toFloat() / 180f
                        val len = if (f.radial) w else (abs(w * cos(rad)) + abs(h * sin(rad)))
                        f.matrix.postTranslate(cos(rad) * shift * len, sin(rad) * shift * len)
                    }
                    f.shader.setLocalMatrix(f.matrix)
                }
                nc.drawPath(shape, f.paint)
            }
            if (inners.isNotEmpty()) {
                val save = nc.save()
                nc.clipPath(shape)
                for (i in inners) nc.drawPath(i.frame, i.paint)
                nc.restoreToCount(save)
            }
        }
    }

    fun DrawScope.front() {
        val n = a.node
        drawIntoCanvas { canvas ->
            val nc = canvas.nativeCanvas
            var firstSolid = true
            for (s in strokes) {
                val width = ((s.width + n.float(YtAnimProp.STROKE_WIDTH)).coerceAtLeast(0f)) * d
                if (width <= 0f) continue
                val extra = n.float(YtAnimProp.CORNERS)
                if (width != lastInset || extra != lastInsetCorner) {
                    // inside the edge: the outline inset by half the width
                    build(inset, -width / 2f, extra)
                    lastInset = width
                    lastInsetCorner = extra
                }
                s.paint.strokeWidth = width
                s.paint.color = if (s.solid && firstSolid) n.argb(YtAnimProp.STROKE_COLOR, s.own) else s.own
                if (s.solid) firstSolid = false
                nc.drawPath(inset, s.paint)
            }
            val v = n.float(YtAnimProp.SHIMMER)
            if (v > 0f && v < 1f && w > 0f) {
                val save = nc.save()
                nc.clipPath(shape)
                shimmerMatrix.setTranslate(-band + v * (w + band), 0f)
                shimmerShader.setLocalMatrix(shimmerMatrix)
                nc.drawRect(0f, 0f, w, h, shimmerPaint)
                nc.restoreToCount(save)
            }
        }
    }

    return onDrawWithContent {
        // the frame counter: this draw runs again on every animation frame, nothing else does
        a.inst.frame.intValue
        try {
            behind()
        } catch (e: Exception) {
            // a paint the canvas cannot take: the node stays without it
        }
        drawContent()
        try {
            front()
        } catch (e: Exception) {
            // see above
        }
    }
}

/**
 * A rounded rectangle into [path] (rewound, no allocation): per-corner radii in px scaled down
 * together where they overlap (as YtCornerShape), smoothed corners as cubic curves.
 */
internal fun ytCornerPath(
    path: android.graphics.Path, rect: RectF, radii: FloatArray,
    x0: Float, y0: Float, x1: Float, y1: Float,
    tl0: Float, tr0: Float, br0: Float, bl0: Float, s: Float,
) {
    path.rewind()
    val w = x1 - x0
    val h = y1 - y0
    if (w <= 0f || h <= 0f) return
    var tl = tl0.coerceAtLeast(0f)
    var tr = tr0.coerceAtLeast(0f)
    var br = br0.coerceAtLeast(0f)
    var bl = bl0.coerceAtLeast(0f)
    val f = min(1f, min(min(w / max(tl + tr, 1e-3f), w / max(bl + br, 1e-3f)), min(h / max(tl + bl, 1e-3f), h / max(tr + br, 1e-3f))))
    tl *= f
    tr *= f
    br *= f
    bl *= f
    if (s <= 0.001f) {
        rect.set(x0, y0, x1, y1)
        radii[0] = tl; radii[1] = tl; radii[2] = tr; radii[3] = tr
        radii[4] = br; radii[5] = br; radii[6] = bl; radii[7] = bl
        path.addRoundRect(rect, radii, android.graphics.Path.Direction.CW)
        return
    }
    val side = min(w, h)
    fun reach(r: Float) = min(r * (1f + s), side / 2f)
    val k = 0.55f + 0.3f * s
    val a = reach(tl)
    val b = reach(tr)
    val c = reach(br)
    val e = reach(bl)
    path.moveTo(x0 + a, y0)
    path.lineTo(x1 - b, y0)
    path.cubicTo(x1 - b + b * k, y0, x1, y0 + b - b * k, x1, y0 + b)
    path.lineTo(x1, y1 - c)
    path.cubicTo(x1, y1 - c + c * k, x1 - c + c * k, y1, x1 - c, y1)
    path.lineTo(x0 + e, y1)
    path.cubicTo(x0 + e - e * k, y1, x0, y1 - e + e * k, x0, y1 - e)
    path.lineTo(x0, y0 + a)
    path.cubicTo(x0, y0 + a - a * k, x0 + a - a * k, y0, x0 + a, y0)
    path.close()
}
