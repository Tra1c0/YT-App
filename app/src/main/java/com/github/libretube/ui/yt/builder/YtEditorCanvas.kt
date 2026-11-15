package com.github.libretube.ui.yt.builder

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.ImageLoader
import com.github.libretube.R
import com.github.libretube.api.obj.StreamItem
import com.github.libretube.ui.yt.YtHaptics
import com.github.libretube.ui.yt.YtNav
import com.github.libretube.ui.yt.YtNavState
import com.github.libretube.ui.yt.YtPalette
import com.github.libretube.ui.yt.YtTheme
import com.github.libretube.ui.yt.YT_REFERENCE_WIDTH_DP
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * YT: the editor's hooks into the renderer: where each node of the edited instance lies (its
 * LayoutCoordinates; boxes are computed when needed, relative to the preview frame), and the
 * drag offset and lift of the picked-up nodes (snapshot state read only in graphicsLayer, so a
 * drag moves the element without composing or measuring anything).
 */
class YtEditorHooks(private val state: YtEditorState) : YtRenderHooks {
    private val coords = HashMap<Pair<YtComponentKind, String>, LayoutCoordinates>()
    var frame: LayoutCoordinates? = null

    /** px of the preview per theme dp. */
    var themeDensity = 1f

    var dragging by mutableStateOf<Set<String>>(emptySet())

    /** The nodes the lift applies to: still set while a dropped node settles. */
    var lifted by mutableStateOf<Set<String>>(emptySet())
    var dx by mutableFloatStateOf(0f)
    var dy by mutableFloatStateOf(0f)
    val lift = Animatable(0f)

    /** Counts placements, so the overlay draws again after a layout. */
    var placements by mutableIntStateOf(0)
        private set

    override fun placed(kind: YtComponentKind, id: String, coordinates: LayoutCoordinates) {
        coords[kind to id] = coordinates
        placements++
    }

    override fun dragX(id: String) = if (id in dragging) dx else 0f
    override fun dragY(id: String) = if (id in dragging) dy else 0f
    override fun lift(id: String) = if (id in lifted) lift.value else 0f

    override fun conflicts(id: String, conflicts: List<YtLayoutEngine.Conflict>) {
        if (conflicts.isEmpty()) state.conflicts.remove(id) else state.conflicts[id] = conflicts
    }

    /** The box of [id] in frame px as drawn now (drag offset included), or null if not shown. */
    fun bounds(kind: YtComponentKind, id: String): Rect? {
        val f = frame ?: return null
        val c = coords[kind to id] ?: return null
        if (!f.isAttached || !c.isAttached) return null
        return runCatching { f.localBoundingBoxOf(c, clipBounds = false) }.getOrNull()
    }

    fun bounds(id: String): Rect? = YtComponentKind.entries.firstNotNullOfOrNull { bounds(it, id) }

    /** All shown nodes as (kind, id, box). */
    fun all(): List<Triple<YtComponentKind, String, Rect>> {
        coords.entries.removeAll { !it.value.isAttached }
        return coords.keys.mapNotNull { (k, id) -> bounds(k, id)?.let { Triple(k, id, it) } }
    }

    fun forgetAll() = coords.clear()
}

private fun Rect.snap() = YtSnap.Rect(left, top, right, bottom)

/** The preview's density: the design dp of the simulated window (as ytDensity on a real phone). */
private fun previewDensity(base: Density, size: YtPreviewSize, fontScale: Float): Density {
    val scale = (min(size.widthDp, size.heightDp) / YT_REFERENCE_WIDTH_DP).coerceIn(0.8f, 1.3f)
    return Density(base.density * scale, (fontScale / 1.15f).coerceAtLeast(1f))
}

/**
 * The canvas: the preview frame (the real renderer, real videos), zoomed and panned as a
 * picture (graphicsLayer: the theme's positions never change with the view), with the editing
 * layer over it. Touches never reach the preview's own controls.
 */
@Composable
fun YtEditorCanvas(
    state: YtEditorState,
    hooks: YtEditorHooks,
    palette: YtPalette,
    videos: List<StreamItem>,
    imageLoader: ImageLoader,
    stats: String?,
    modifier: Modifier = Modifier,
) {
    val doc = state.doc ?: return
    val base = LocalDensity.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val haptics = remember(view) { YtHaptics(view, true) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    var frameSize by remember { mutableStateOf(IntSize.Zero) }
    var guides by remember { mutableStateOf<List<YtSnap.Guide>>(emptyList()) }
    var marquee by remember { mutableStateOf<Rect?>(null) }
    val capture = rememberGraphicsLayer()

    val src = remember(state, hooks) {
        YtThemeSource({ state.doc }, { YtEnv(state.size.widthDp, state.size.heightDp, state.fontScale, state.night) }, { state.night }, { state.doc?.let { YtBuilderStore.assets(hooksContext(state), it.id) } }, hooks)
    }
    val plain = remember(state) {
        YtThemeSource({ state.doc }, { YtEnv(state.size.widthDp, state.size.heightDp, state.fontScale, state.night) }, { state.night }, { state.doc?.let { YtBuilderStore.assets(hooksContext(state), it.id) } })
    }

    fun toFrame(p: Offset) = (p - state.pan) / state.zoom
    fun toScreen(p: Offset) = p * state.zoom + state.pan
    fun toScreen(r: Rect) = Rect(toScreen(r.topLeft), toScreen(r.bottomRight))

    fun fit() {
        if (viewport.width == 0 || frameSize.width == 0) return
        val z = min(viewport.width / frameSize.width.toFloat(), viewport.height / frameSize.height.toFloat()) * 0.94f
        state.zoom = z.coerceIn(0.1f, 8f)
        state.pan = Offset((viewport.width - frameSize.width * state.zoom) / 2f, (viewport.height - frameSize.height * state.zoom) / 2f)
    }
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose {
            YtEditorViewActions.fit = {}
            YtEditorViewActions.actual = {}
        }
    }
    YtEditorViewActions.fit = ::fit
    YtEditorViewActions.actual = {
        state.zoom = 1f
        state.pan = Offset((viewport.width - frameSize.width) / 2f, 16f * base.density)
    }

    /**
     * The node under [p] (frame px): the smallest box (the deeper one where two are as big),
     * small ones grown to a 24 dp hit area.
     */
    fun hit(p: Offset): Pair<YtComponentKind, String>? {
        val current = state.doc ?: return null
        val slop = 12f * base.density / state.zoom
        var best: Triple<YtComponentKind, String, Float>? = null
        var bestDepth = 0
        for ((kind, id, r) in hooks.all()) {
            val node = YtDocOps.find(current.components[kind], id) ?: continue
            if (node.locked) continue
            if (state.target.kind != null && state.target.kind != kind) continue
            val grown = Rect(
                r.left - if (r.width < 2 * slop) slop else 0f, r.top - if (r.height < 2 * slop) slop else 0f,
                r.right + if (r.width < 2 * slop) slop else 0f, r.bottom + if (r.height < 2 * slop) slop else 0f,
            )
            if (!grown.contains(p)) continue
            val area = r.width * r.height
            val depth = YtDocOps.pathTo(current.components[kind], id).size
            if (best == null || area < best.third - 0.5f || (abs(area - best.third) <= 0.5f && depth > bestDepth)) {
                best = Triple(kind, id, area)
                bestDepth = depth
            }
        }
        return best?.let { it.first to it.second }
    }

    Box(
        modifier
            .clipToBounds()
            .background(Color(0xFF15161A))
            .onSizeChanged {
                val first = viewport == IntSize.Zero
                viewport = it
                if (first) scope.launch { fit() }
            },
    ) {
        // the world: the preview frame, zoomed and panned
        Box(
            Modifier
                .wrapContentSize(Alignment.TopStart, unbounded = true)
                .graphicsLayer {
                    transformOrigin = TransformOrigin(0f, 0f)
                    scaleX = state.zoom
                    scaleY = state.zoom
                    translationX = state.pan.x
                    translationY = state.pan.y
                },
        ) {
            val pd = previewDensity(base, state.size, state.fontScale)
            hooks.themeDensity = pd.density
            Box(
                Modifier
                    .then(if (state.target == YtEditorTarget.SCREEN) Modifier.requiredSize(state.size.widthDp.dp, state.size.heightDp.dp) else Modifier.requiredWidth(state.size.widthDp.dp))
                    .onPlaced { hooks.frame = it }
                    .onSizeChanged {
                        val first = frameSize == IntSize.Zero
                        frameSize = it
                        if (first) scope.launch { fit() }
                    }
                    .drawWithContent {
                        // kept as a picture for the eyedropper
                        capture.record { this@drawWithContent.drawContent() }
                        drawLayer(capture)
                    }
                    .background(palette.background),
            ) {
                CompositionLocalProvider(LocalDensity provides pd) {
                    YtTheme(palette) {
                        CompositionLocalProvider(LocalDensity provides pd) {
                            YtEditorPreview(state, src, plain, hooks, palette, videos, imageLoader)
                        }
                    }
                }
            }
        }

        // the editing layer: gestures and everything drawn over the preview
        val measurer = rememberTextMeasurer()
        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(state, hooks) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        val start = down.position
                        val longPressMs = viewConfiguration.longPressTimeoutMillis
                        val slop = viewConfiguration.touchSlop
                        val startHit = hit(toFrame(start))
                        val selected = state.selection
                        // a handle of the selection, in screen px
                        val primary = selected.firstOrNull()
                        val primaryBox = primary?.let { hooks.bounds(it) }?.let(::toScreen)
                        val handle = primaryBox?.let { YtEditorHandles.at(it, start, 24f * base.density, state.parentOf(primary)?.layout?.mode == YtLayoutMode.FREE, base.density) }
                        var mode = Mode.PENDING
                        var committed = false
                        var snapState = YtSnap.State()
                        var dragIds: Set<String> = emptySet()
                        var startRect: Rect? = null
                        var others: List<YtSnap.Rect> = emptyList()
                        var container = YtSnap.Rect(0f, 0f, frameSize.width.toFloat(), frameSize.height.toFloat())
                        var startNode: YtNode? = null
                        var lastSpan = 0f
                        var lastCentroid = Offset.Zero
                        val downTime = down.uptimeMillis
                        var lastTime = downTime
                        try {
                            while (true) {
                                // the long press counts from the first touch, not from the last move
                                val left = (longPressMs - (lastTime - downTime)).coerceAtLeast(1L)
                                val event = if (mode == Mode.PENDING) withTimeoutOrNull(left) { awaitPointerEvent() } else awaitPointerEvent()
                                event?.changes?.firstOrNull()?.let { lastTime = it.uptimeMillis }
                                if (event == null) {
                                    // held without moving: long press adds or removes the node
                                    if (mode == Mode.PENDING && startHit != null) {
                                        val id = startHit.second
                                        state.selection = if (id in state.selection) state.selection - id else state.selection + id
                                        if (state.haptics) haptics.tick()
                                        mode = Mode.DONE
                                    } else if (mode == Mode.PENDING) {
                                        mode = Mode.MARQUEE
                                    }
                                    continue
                                }
                                val pressed = event.changes.filter { it.pressed }
                                if (pressed.size >= 2) {
                                    // two fingers: the view, never the theme; a running drag goes back
                                    if (mode == Mode.DRAG) {
                                        cancelDrag(scope, hooks)
                                        guides = emptyList()
                                    }
                                    marquee = null
                                    val a = pressed[0].position
                                    val b = pressed[1].position
                                    val centroid = (a + b) / 2f
                                    val span = hypot(a.x - b.x, a.y - b.y)
                                    if (mode == Mode.TRANSFORM && lastSpan > 0f) {
                                        // the point under the fingers stays under them: zoom around it, pan with it
                                        val z = (state.zoom * span / lastSpan).coerceIn(0.1f, 8f)
                                        val world = (lastCentroid - state.pan) / state.zoom
                                        state.zoom = z
                                        state.pan = centroid - world * z
                                    }
                                    lastSpan = span
                                    lastCentroid = centroid
                                    mode = Mode.TRANSFORM
                                    event.changes.forEach(PointerInputChange::consume)
                                    continue
                                }
                                val change = event.changes.firstOrNull { it.id == down.id } ?: event.changes.first()
                                if (!change.pressed) {
                                    when (mode) {
                                        Mode.PENDING -> {
                                            // a tap: pick a colour, or select what is under the finger
                                            val pick = state.pick
                                            if (pick != null) {
                                                val p = toFrame(change.position)
                                                scope.launch {
                                                    YtEditorPicker.colorAt(capture, p, frameSize)?.let(pick)
                                                    state.pick = null
                                                }
                                            } else {
                                                state.selection = startHit?.let { setOf(it.second) } ?: emptySet()
                                            }
                                        }
                                        Mode.DRAG -> {
                                            committed = true
                                            commitDrag(state, hooks, dragIds, startRect, scope)
                                            if (state.haptics) haptics.release()
                                            scope.launch {
                                                // the drop: the element settles into the place it got, never late under the finger
                                                hooks.lift.animateTo(0f, spring(dampingRatio = 0.6f, stiffness = 500f))
                                                hooks.lifted = emptySet()
                                            }
                                            guides = emptyList()
                                        }
                                        Mode.MARQUEE -> {
                                            marquee?.let { m ->
                                                val f = Rect(toFrame(m.topLeft), toFrame(m.bottomRight))
                                                val inside = hooks.all().filter { (k, _, r) ->
                                                    (state.target.kind == null || state.target.kind == k) && f.contains(r.topLeft) && f.contains(r.bottomRight)
                                                }.map { it.second }
                                                state.selection = inside.toSet()
                                            }
                                            marquee = null
                                        }
                                        Mode.RESIZE, Mode.ROTATE -> state.seal()
                                        else -> Unit
                                    }
                                    change.consume()
                                    break
                                }
                                val delta = change.position - start
                                if (mode == Mode.PENDING && (abs(delta.x) > slop || abs(delta.y) > slop)) {
                                    mode = when {
                                        handle == YtEditorHandles.ROTATE -> Mode.ROTATE
                                        handle != null -> Mode.RESIZE
                                        startHit != null -> Mode.DRAG
                                        else -> Mode.MARQUEE
                                    }
                                    if (mode == Mode.DRAG) {
                                        val id = startHit!!.second
                                        if (id !in state.selection) state.selection = setOf(id)
                                        // only siblings move together, and never the component's root
                                        val parent = state.parentOf(id)
                                        dragIds = state.selection.filter { s -> state.parentOf(s)?.id == parent?.id && parent != null }.toSet()
                                        if (dragIds.isEmpty()) {
                                            mode = Mode.DONE
                                        } else {
                                            val boxes = dragIds.mapNotNull { hooks.bounds(it) }
                                            startRect = boxes.reduceOrNull { a, b -> Rect(min(a.left, b.left), min(a.top, b.top), max(a.right, b.right), max(a.bottom, b.bottom)) }
                                            others = parent?.children.orEmpty().filter { it.id !in dragIds && it.visible }.mapNotNull { hooks.bounds(it.id)?.snap() }
                                            parent?.let { p -> hooks.bounds(p.id)?.let { container = it.snap() } }
                                            hooks.dx = 0f
                                            hooks.dy = 0f
                                            hooks.dragging = dragIds
                                            hooks.lifted = dragIds
                                            if (state.haptics) haptics.grab()
                                            // the pick-up runs beside the finger, never in its way
                                            scope.launch { hooks.lift.animateTo(1f, spring(dampingRatio = 0.7f, stiffness = 700f)) }
                                        }
                                    }
                                    if (mode == Mode.RESIZE || mode == Mode.ROTATE) {
                                        startNode = primary?.let(state::node)
                                        startRect = primary?.let { hooks.bounds(it) }
                                    }
                                }
                                when (mode) {
                                    Mode.DRAG -> {
                                        val raw = delta / state.zoom
                                        val r0 = startRect
                                        val free = dragIds.firstOrNull()?.let { state.parentOf(it) }?.layout?.mode == YtLayoutMode.FREE
                                        if (r0 != null && free && state.snapKinds.isNotEmpty()) {
                                            val moving = r0.snap().offset(raw.x, raw.y)
                                            val snapInPx = 8f * base.density / state.zoom
                                            val settings = YtSnap.Settings(
                                                kinds = state.snapKinds.filter { it != YtSnap.Kind.GRID || state.showGrid }.toSet(),
                                                snapIn = snapInPx, snapOut = snapInPx * 2f,
                                                grid = if (state.showGrid) state.gridDp * hooks.themeDensity else 0f,
                                                guidesX = state.guidesX.map { it * hooks.themeDensity },
                                                guidesY = state.guidesY.map { it * hooks.themeDensity },
                                            )
                                            val r = YtSnap.snap(moving, others, container, settings, snapState)
                                            if (state.haptics && (r.state.x != snapState.x && r.state.x != null || r.state.y != snapState.y && r.state.y != null)) haptics.detent()
                                            snapState = r.state
                                            hooks.dx = raw.x + r.dx
                                            hooks.dy = raw.y + r.dy
                                            guides = if (state.showGuides) r.guides else emptyList()
                                        } else {
                                            hooks.dx = raw.x
                                            hooks.dy = raw.y
                                        }
                                    }
                                    Mode.MARQUEE -> marquee = Rect(
                                        Offset(min(start.x, change.position.x), min(start.y, change.position.y)),
                                        Offset(max(start.x, change.position.x), max(start.y, change.position.y)),
                                    )
                                    Mode.RESIZE -> {
                                        val n0 = startNode
                                        val r0 = startRect
                                        if (n0 != null && r0 != null && handle != null) {
                                            val free = state.parentOf(n0.id)?.layout?.mode == YtLayoutMode.FREE
                                            val lock = n0.aspect != null
                                            val d = delta / state.zoom
                                            val next = YtEditorDrag.resized(n0, handle.drag!!, d.x, d.y, r0.width, r0.height, hooks.themeDensity, lock, free)
                                            state.editNode(n0.id, "resize", "resize") { next }
                                        }
                                    }
                                    Mode.ROTATE -> {
                                        val r0 = startRect
                                        if (r0 != null) {
                                            val c = toScreen(r0.center)
                                            val deg = YtEditorDrag.rotation(c.x, c.y, change.position.x, change.position.y, snap = true)
                                            primary?.let { id -> state.editNode(id, "rotate", "rotate") { it.copy(rotation = if (deg > 180f) deg - 360f else deg) } }
                                        }
                                    }
                                    else -> Unit
                                }
                                change.consume()
                            }
                        } finally {
                            // cancelled (another gesture took over, the screen went away): nothing half done stays
                            if (mode == Mode.DRAG && !committed) {
                                cancelDrag(scope, hooks)
                            }
                            if (mode != Mode.DRAG) guides = emptyList()
                            if (marquee != null && mode != Mode.MARQUEE) marquee = null
                        }
                    }
                },
        ) {
            // read here so the layer draws again while a node moves (its box follows the finger)
            hooks.placements
            hooks.dx
            hooks.dy
            drawOverlay(state, hooks, ::toScreen, guides, marquee, base.density)
            if (state.showRulers) drawRulers(state, hooks.themeDensity, measurer)
            if (stats != null && state.showStats) {
                drawText(measurer, stats, Offset(12f, size.height - 34f * base.density), TextStyle(color = Color.White, fontSize = 11.sp, background = Color(0xB3000000)))
            }
        }
    }
}

private enum class Mode { PENDING, DRAG, MARQUEE, TRANSFORM, RESIZE, ROTATE, DONE }

/** For the editor's own zoom buttons. */
object YtEditorViewActions {
    var fit: () -> Unit = {}
    var actual: () -> Unit = {}
}

private fun hooksContext(state: YtEditorState) = state.getApplication<android.app.Application>()

/** The drag ends without a change: the element springs back to where it was. */
private fun cancelDrag(scope: kotlinx.coroutines.CoroutineScope, hooks: YtEditorHooks) {
    val fromX = hooks.dx
    val fromY = hooks.dy
    scope.launch {
        launch {
            hooks.lift.animateTo(0f)
            hooks.lifted = emptySet()
        }
        animate(1f, 0f, animationSpec = spring(dampingRatio = 0.8f, stiffness = 600f)) { v, _ ->
            hooks.dx = fromX * v
            hooks.dy = fromY * v
        }
        hooks.dragging = emptySet()
    }
}

/**
 * Turns the drag into the document: in a free container each node's x/y (one undo step); in a
 * flow the dragged node takes the place among its siblings where it was dropped. Dropped outside
 * its flow container it goes back (nothing changes).
 */
private fun commitDrag(state: YtEditorState, hooks: YtEditorHooks, ids: Set<String>, startRect: Rect?, scope: kotlinx.coroutines.CoroutineScope) {
    val dx = hooks.dx
    val dy = hooks.dy
    val first = ids.firstOrNull()
    val parent = first?.let(state::parentOf)
    if (parent == null || (abs(dx) < 0.5f && abs(dy) < 0.5f)) {
        hooks.dragging = emptySet()
        hooks.dx = 0f
        hooks.dy = 0f
        return
    }
    if (parent.layout.mode == YtLayoutMode.FREE) {
        // the new values and the end of the offset land in the same frame: nothing jumps
        state.edit("move") { n -> if (n.id in ids) YtEditorDrag.moved(n, dx, dy, hooks.themeDensity) else n }
        hooks.dragging = emptySet()
        hooks.dx = 0f
        hooks.dy = 0f
        return
    }
    val r0 = startRect
    val parentBox = hooks.bounds(parent.id)
    val center = r0?.center?.let { it + Offset(dx, dy) }
    if (center == null || parentBox == null || !parentBox.inflate(24f * hooks.themeDensity).contains(center)) {
        // no valid place: back where it was
        val fromX = dx
        val fromY = dy
        scope.launch {
            animate(1f, 0f, animationSpec = spring(dampingRatio = 0.75f, stiffness = 500f)) { v, _ ->
                hooks.dx = fromX * v
                hooks.dy = fromY * v
            }
            hooks.dragging = emptySet()
        }
        return
    }
    val horizontal = parent.layout.mode == YtLayoutMode.FLOW_H
    val siblings = parent.children.filter { it.visible }
    val centers = siblings.map { s -> hooks.bounds(s.id)?.let { if (horizontal) it.center.x - if (s.id == first) dx else 0f else it.center.y - if (s.id == first) dy else 0f } ?: 0f }
    val from = siblings.indexOfFirst { it.id == first }
    val at = YtEditorDrag.insertIndex(centers, from, if (horizontal) center.x else center.y)
    // the index among all children, hidden ones kept where they are
    val target = siblings.filterIndexed { i, _ -> i != from }.getOrNull(at)?.let { t -> parent.children.indexOfFirst { it.id == t.id } }
        ?: parent.children.size
    val index = if (target > parent.children.indexOfFirst { it.id == first }) target - 1 else target
    state.moveInFlow(first, index.coerceAtLeast(0))
    hooks.dragging = emptySet()
    hooks.dx = 0f
    hooks.dy = 0f
}

/** The handles around the selected box (screen px). */
enum class YtEditorHandles(val drag: YtEditorDrag.Handle?) {
    TOP_LEFT(YtEditorDrag.Handle.TOP_LEFT), TOP_RIGHT(YtEditorDrag.Handle.TOP_RIGHT),
    BOTTOM_LEFT(YtEditorDrag.Handle.BOTTOM_LEFT), BOTTOM_RIGHT(YtEditorDrag.Handle.BOTTOM_RIGHT),
    ROTATE(null);

    companion object {
        fun points(r: Rect, free: Boolean, density: Float): List<Pair<YtEditorHandles, Offset>> = buildList {
            add(BOTTOM_RIGHT to r.bottomRight)
            if (free) {
                add(TOP_LEFT to r.topLeft)
                add(TOP_RIGHT to r.topRight)
                add(BOTTOM_LEFT to r.bottomLeft)
            }
            add(ROTATE to Offset(r.center.x, r.top - 28f * density))
        }

        fun at(r: Rect, p: Offset, reach: Float, free: Boolean, density: Float): YtEditorHandles? =
            points(r, free, density).minByOrNull { (it.second - p).getDistance() }?.takeIf { (it.second - p).getDistance() <= reach }?.first
    }
}

private val Accent = Color(0xFF4C8DFF)
private val GuideColor = Color(0xFFFF3FD8)
private val WarnColor = Color(0xFFFFB020)

private fun DrawScope.drawOverlay(
    state: YtEditorState,
    hooks: YtEditorHooks,
    toScreen: (Rect) -> Rect,
    guides: List<YtSnap.Guide>,
    marquee: Rect?,
    density: Float,
) {
    val zoom = state.zoom
    if (state.showGrid) {
        val step = state.gridDp * hooks.themeDensity * zoom
        if (step >= 6f) {
            var x = state.pan.x % step
            while (x < size.width) {
                drawLine(Color(0x1FFFFFFF), Offset(x, 0f), Offset(x, size.height))
                x += step
            }
            var y = state.pan.y % step
            while (y < size.height) {
                drawLine(Color(0x1FFFFFFF), Offset(0f, y), Offset(size.width, y))
                y += step
            }
        }
    }
    if (state.showSafe && state.target == YtEditorTarget.SCREEN) {
        // the status bar and the gesture area of a typical phone, where controls do not belong
        val top = toScreen(Rect(0f, 0f, state.size.widthDp * hooks.themeDensity, 24f * hooks.themeDensity))
        val bottom = toScreen(Rect(0f, (state.size.heightDp - 24f) * hooks.themeDensity, state.size.widthDp * hooks.themeDensity, state.size.heightDp * hooks.themeDensity))
        drawRect(Color(0x40FF3B30), top.topLeft, top.size)
        drawRect(Color(0x40FF3B30), bottom.topLeft, bottom.size)
    }
    val all = hooks.all()
    if (state.showBounds) {
        for ((kind, _, r) in all) {
            if (state.target.kind != null && state.target.kind != kind) continue
            val s = toScreen(r)
            drawRect(Color(0x33FFFFFF), s.topLeft, s.size, style = Stroke(1f))
        }
    }
    for (w in state.warnings) {
        val r = hooks.bounds(w.component, w.nodeId) ?: continue
        val s = toScreen(r)
        drawRect(WarnColor, s.topLeft, s.size, style = Stroke(1.5f * density, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f * density, 4f * density))))
    }
    for (g in state.guidesX) {
        val x = g * hooks.themeDensity * zoom + state.pan.x
        drawLine(Color(0x9900E5FF), Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f * density)
    }
    for (g in state.guidesY) {
        val y = g * hooks.themeDensity * zoom + state.pan.y
        drawLine(Color(0x9900E5FF), Offset(0f, y), Offset(size.width, y), strokeWidth = 1f * density)
    }
    for (id in state.selection) {
        val r = hooks.bounds(id) ?: continue
        val s = toScreen(r)
        drawRect(Accent, s.topLeft, s.size, style = Stroke(1.5f * density))
    }
    state.selection.firstOrNull()?.let { id ->
        val r = hooks.bounds(id) ?: return@let
        if (id in hooks.dragging) return@let
        val s = toScreen(r)
        val free = state.parentOf(id)?.layout?.mode == YtLayoutMode.FREE
        for ((h, p) in YtEditorHandles.points(s, free, density)) {
            if (h == YtEditorHandles.ROTATE) {
                drawLine(Accent, Offset(s.center.x, s.top), p, strokeWidth = 1f * density)
                drawCircle(Color.White, 6f * density, p)
                drawCircle(Accent, 6f * density, p, style = Stroke(1.5f * density))
            } else {
                drawRect(Color.White, p - Offset(5f * density, 5f * density), Size(10f * density, 10f * density))
                drawRect(Accent, p - Offset(5f * density, 5f * density), Size(10f * density, 10f * density), style = Stroke(1.5f * density))
            }
        }
    }
    for (g in guides) {
        val a = if (g.vertical) Offset(g.at, g.from) else Offset(g.from, g.at)
        val b = if (g.vertical) Offset(g.at, g.to) else Offset(g.to, g.at)
        drawLine(GuideColor, a * zoom + state.pan, b * zoom + state.pan, strokeWidth = 1f * density)
    }
    marquee?.let {
        drawRect(Accent.copy(alpha = 0.12f), it.topLeft, it.size)
        drawRect(Accent, it.topLeft, it.size, style = Stroke(1f * density))
    }
}

private fun DrawScope.drawRulers(state: YtEditorState, themeDensity: Float, measurer: androidx.compose.ui.text.TextMeasurer) {
    val band = 18f * density
    drawRect(Color(0xE61E1F24), Offset.Zero, Size(size.width, band))
    drawRect(Color(0xE61E1F24), Offset.Zero, Size(band, size.height))
    val dpPx = themeDensity * state.zoom
    val step = listOf(4f, 8f, 16f, 40f, 80f, 160f).first { it * dpPx >= 6f * density }
    val label = step * 5f
    val style = TextStyle(color = Color(0xFFB0B3BA), fontSize = 8.sp)
    var dp = (-state.pan.x / dpPx / step).toInt() * step
    while (true) {
        val x = dp * dpPx + state.pan.x
        if (x > size.width) break
        if (x >= band) {
            val major = dp % label == 0f
            drawLine(Color(0xFF8A8D96), Offset(x, band - if (major) 9f * density else 4f * density), Offset(x, band))
            if (major) drawText(measurer, dp.toInt().toString(), Offset(x + 2f, 1f), style)
        }
        dp += step
    }
    dp = (-state.pan.y / dpPx / step).toInt() * step
    while (true) {
        val y = dp * dpPx + state.pan.y
        if (y > size.height) break
        if (y >= band) {
            val major = dp % label == 0f
            drawLine(Color(0xFF8A8D96), Offset(band - if (major) 9f * density else 4f * density, y), Offset(band, y))
            if (major) drawText(measurer, dp.toInt().toString(), Offset(1f, y + 1f), style)
        }
        dp += step
    }
}

/** The eyedropper: the colour of the preview at a point of the frame. */
object YtEditorPicker {
    suspend fun colorAt(layer: androidx.compose.ui.graphics.layer.GraphicsLayer, p: Offset, frame: IntSize): Int? = runCatching {
        val image = layer.toImageBitmap()
        val bitmap = image.asAndroidBitmap().let { if (it.config == android.graphics.Bitmap.Config.HARDWARE) it.copy(android.graphics.Bitmap.Config.ARGB_8888, false) else it }
        val x = (p.x / frame.width.coerceAtLeast(1) * bitmap.width).toInt().coerceIn(0, bitmap.width - 1)
        val y = (p.y / frame.height.coerceAtLeast(1) * bitmap.height).toInt().coerceIn(0, bitmap.height - 1)
        bitmap.getPixel(x, y)
    }.getOrNull()
}

/** What the canvas shows for the target: the component alone, or the whole home screen. */
@Composable
private fun YtEditorPreview(
    state: YtEditorState,
    src: YtThemeSource,
    plain: YtThemeSource,
    hooks: YtEditorHooks,
    palette: YtPalette,
    videos: List<StreamItem>,
    imageLoader: ImageLoader,
) {
    val doc = state.doc ?: return
    val target = state.target
    val sample = videos.ifEmpty { listOf(null, null, null, null) }
    // one slot data per card, kept, so an edit does not compose every card again
    val slotList = remember(videos, imageLoader) { List(8) { i -> YtSlotData(video = sample.getOrNull(i % sample.size.coerceAtLeast(1)), progress = { 0.4f }, imageLoader = imageLoader) } }
    fun slots(i: Int) = slotList[i % slotList.size]
    val selectedState = if (state.editState) YtRenderStates.SELECTED else 0
    val navItems = remember { YtNav.items(com.github.libretube.helpers.YtDesign.CUSTOM) }
    val navState = remember { YtNavState().apply { selected = navItems.firstOrNull()?.id ?: 0 } }
    val chipLabels = listOf(androidx.compose.ui.res.stringResource(R.string.all)) +
        com.github.libretube.api.TrendingCategory.entries.take(4).map { androidx.compose.ui.res.stringResource(it.titleRes) }

    @Composable
    fun Chips(modifier: Modifier = Modifier) {
        Row(modifier.padding(horizontal = doc.feed.padding.start.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            chipLabels.forEachIndexed { i, label ->
                YtThemeComponent(if (i == 0) src else plain, YtComponentKind.CHIP, remember(label) { YtSlotData(label = label) }, states = if (i == 0) YtRenderStates.SELECTED else 0)
            }
        }
    }

    @Composable
    fun Cards(count: Int) {
        val columns = doc.feed.columns(src.env.widthClass).coerceAtLeast(1)
        Column(
            Modifier.padding(start = doc.feed.padding.start.dp, end = doc.feed.padding.end.dp, top = doc.feed.padding.top.dp),
            verticalArrangement = Arrangement.spacedBy(doc.feed.rowGap.dp),
        ) {
            (0 until count).chunked(columns).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(doc.feed.gap.dp)) {
                    row.forEach { i ->
                        Box(Modifier.weight(1f)) { YtThemeComponent(if (i == 0) src else plain, YtComponentKind.CARD, slots(i)) }
                    }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }

    @Composable
    fun Nav() {
        YtCustomNav(
            palette, navItems, navState, onSelect = {}, src = src,
            tabSource = { if (it == 0) src else plain },
            onBarPlaced = { hooks.placed(YtComponentKind.NAV_BAR, doc.components.navBar.id, it) },
        )
    }

    @Composable
    fun Mini() {
        val m = doc.mini
        val nav = doc.nav
        Box(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = if (nav.floating) nav.side.dp else 0.dp)
                .height(m.height.dp)
                .clip(RoundedCornerShape(topStart = m.radius.dp, topEnd = m.radius.dp)),
        ) {
            YtThemeComponent(src, YtComponentKind.MINI, slots(0), modifier = Modifier.fillMaxSize())
            // the picture is the player's (a video surface); here a stand-in of its size
            Box(
                Modifier
                    .padding(start = 7.dp, top = ((m.height - m.thumbWidth * 9f / 16f) / 2f).coerceAtLeast(0f).dp)
                    .size(m.thumbWidth.dp, (m.thumbWidth * 9f / 16f).dp)
                    .clip(RoundedCornerShape(m.thumbRadius.dp))
                    .background(Color(0xFF3A3D45)),
            )
        }
    }

    when (target) {
        YtEditorTarget.SCREEN -> Column(Modifier.fillMaxSize()) {
            Spacer(Modifier.height(24.dp))
            YtThemeComponent(src, YtComponentKind.HEADER, remember(doc.meta.wordmark) { YtSlotData(wordmark = doc.meta.wordmark) }, modifier = Modifier.fillMaxWidth())
            if (doc.feed.chips) Chips()
            Box(Modifier.weight(1f).clipToBounds()) { Cards(6) }
            Mini()
            Nav()
            Spacer(Modifier.height(24.dp))
        }
        YtEditorTarget.CARD -> Box(Modifier.padding(bottom = 16.dp)) { Cards(2) }
        YtEditorTarget.HEADER -> Column(Modifier.padding(vertical = 16.dp)) {
            YtThemeComponent(src, YtComponentKind.HEADER, remember(doc.meta.wordmark) { YtSlotData(wordmark = doc.meta.wordmark) }, modifier = Modifier.fillMaxWidth())
        }
        YtEditorTarget.CHIP -> Column(Modifier.padding(vertical = 16.dp)) {
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                YtThemeComponent(src, YtComponentKind.CHIP, remember(chipLabels[1]) { YtSlotData(label = chipLabels[1]) }, states = selectedState)
                YtThemeComponent(plain, YtComponentKind.CHIP, remember(chipLabels[2]) { YtSlotData(label = chipLabels[2]) }, states = if (selectedState == 0) YtRenderStates.SELECTED else 0)
                YtThemeComponent(plain, YtComponentKind.CHIP, remember(chipLabels[3]) { YtSlotData(label = chipLabels[3]) })
            }
        }
        YtEditorTarget.NAV_BAR, YtEditorTarget.NAV_TAB -> Column(Modifier.padding(vertical = 24.dp)) {
            val wanted = if (target == YtEditorTarget.NAV_TAB && !state.editState) navItems.getOrNull(1)?.id ?: 0 else navItems.firstOrNull()?.id ?: 0
            androidx.compose.runtime.SideEffect { navState.selected = wanted }
            Nav()
        }
        YtEditorTarget.MINI -> Column(Modifier.padding(vertical = 24.dp)) {
            Mini()
            Nav()
        }
    }
}
