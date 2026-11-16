package com.github.libretube.ui.yt.builder

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.sp
import com.github.libretube.ui.yt.YtHaptics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/*
 * YT: the pen tool and the motion recording on the editor's canvas (phase 3). Touches are in
 * screen px; the pen session's frame maps them to path units. Handles and hit areas keep their
 * size on screen at any zoom (the hit slop grows as the view zooms out), small points get a 22 dp
 * hit area. A point drag shows live (the renderer reads the live path in its draw cache) and goes
 * into the document once, on release: one undo step. Two fingers always move the view.
 */

private val PenAccent = Color(0xFF4C8DFF)
private val PenSnap = Color(0xFFFF3FD8)

/** The live trail of a recording (frame px), drawn while the finger moves. */
internal val YtRecordTrail = mutableStateListOf<Offset>()

/**
 * The canvas's touches while the pen or a recording is active: one finger draws, edits or records,
 * two fingers zoom and pan. [density] is the screen's px per dp.
 */
internal fun Modifier.ytPenInput(state: YtEditorState, hooks: YtEditorHooks, density: Float, haptics: YtHaptics, scope: CoroutineScope): Modifier = pointerInput(state, hooks) {
    val v = state.vector
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        down.consume()
        val rec = v.record
        if (rec != null && rec.armed) {
            record(state, hooks, rec, down, scope)
            return@awaitEachGesture
        }
        val sess = v.pen
        val frame = sess?.let(v::frameOf)
        if (sess == null || frame == null) {
            // nothing to work on here: let the view move with two fingers, ignore the rest
            transformOnly(state, down)
            return@awaitEachGesture
        }
        val path0 = v.path(sess) ?: YtPath()
        fun units(screen: Offset) = frame.fromFrame((screen - state.pan) / state.zoom)
        val hitUnits = frame.units(22f * density / state.zoom)
        val slop = viewConfiguration.touchSlop
        val longMs = viewConfiguration.longPressTimeoutMillis
        val start = down.position
        val u0 = units(start)
        var moved = false
        var cancelled = false
        // snapping: frame px of grid, guides and the other points
        fun snap(f: Offset, skip: Set<YtPathGeom.Ref>): Offset {
            val td = hooks.themeDensity
            val others = ArrayList<Offset>()
            path0.subpaths.forEachIndexed { si, s -> s.points.forEachIndexed { pi, p -> if (YtPathGeom.Ref(si, pi) !in skip) others += frame.toFrame(p.x, p.y) } }
            val r = YtPointSnap.snap(
                f.x, f.y, if (state.showGrid) state.gridDp * td else 0f,
                state.guidesX.map { it * td }, state.guidesY.map { it * td },
                others.map { it.x }, others.map { it.y },
                6f * density / state.zoom,
            )
            sess.snapX = r.lineX
            sess.snapY = r.lineY
            return Offset(r.x, r.y)
        }
        fun snapUnits(screen: Offset, skip: Set<YtPathGeom.Ref> = emptySet()) = frame.fromFrame(snap((screen - state.pan) / state.zoom, skip))

        try {
            if (sess.drawing) {
                val at = snapUnits(start)
                val step = YtPenModel.touch(path0, sess.sub, at.x, at.y, hitUnits)
                sess.sub = step.sub
                var working = step.path
                sess.live = working
                if (state.haptics) haptics.tick()
                while (true) {
                    val ev = awaitPointerEvent()
                    if (ev.changes.count { it.pressed } >= 2) {
                        cancelled = true
                        sess.live = null
                        transform(state, ev.changes)
                        transformOnly(state, null)
                        return@awaitEachGesture
                    }
                    val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                    if (!c.pressed) {
                        c.consume()
                        break
                    }
                    if (!moved && (c.position - start).getDistance() > slop) moved = true
                    if (moved) {
                        val u = units(c.position)
                        working = YtPenModel.pull(step.path, step.sub, step.did == YtPenModel.Did.CLOSED, u.x, u.y)
                        sess.live = working
                    }
                    c.consume()
                }
                sess.live = null
                v.commit(sess, working, "pen")
                if (step.did == YtPenModel.Did.CLOSED && sess.kind == YtPenKind.NEW) v.finish(keepEditing = true)
                return@awaitEachGesture
            }

            // editing: a handle of a selected point, else a point, else a segment, else empty space
            val handle = YtPathGeom.hitHandle(path0, sess.selected, u0.x.toDouble(), u0.y.toDouble(), hitUnits.toDouble(), 1.0, 1.0)
            val point = if (handle == null) YtPathGeom.hitPoint(path0, u0.x.toDouble(), u0.y.toDouble(), hitUnits.toDouble()) else null
            // wait for a move or the long press
            val first = withTimeoutOrNull(longMs) {
                var result: PointerInputChange? = null
                while (result == null) {
                    val ev = awaitPointerEvent()
                    if (ev.changes.count { it.pressed } >= 2) {
                        cancelled = true
                        transform(state, ev.changes)
                        return@withTimeoutOrNull null
                    }
                    val c = ev.changes.firstOrNull { it.id == down.id }
                    if (c == null) {
                        cancelled = true
                        return@withTimeoutOrNull null
                    }
                    if (!c.pressed || (c.position - start).getDistance() > slop) result = c
                    c.consume()
                }
                result
            }
            if (cancelled) {
                transformOnly(state, null)
                return@awaitEachGesture
            }
            if (first == null) {
                // held: a point joins or leaves the selection
                if (point != null) {
                    sess.selected = if (point in sess.selected) sess.selected - point else sess.selected + point
                    if (state.haptics) haptics.tick()
                }
                waitUp(down)
                return@awaitEachGesture
            }
            if (!first.pressed) {
                // a tap
                when {
                    point != null -> sess.selected = setOf(point)
                    handle != null -> Unit
                    else -> {
                        val seg = YtPathGeom.hitSegment(path0, u0.x.toDouble(), u0.y.toDouble(), hitUnits.toDouble())
                        if (seg != null) {
                            v.commit(sess, YtPathOps.insert(path0, seg.sub, seg.segment, seg.t), "add point")
                            sess.selected = setOf(YtPathGeom.Ref(seg.sub, seg.segment + 1))
                            if (state.haptics) haptics.tick()
                        } else {
                            sess.selected = emptySet()
                        }
                    }
                }
                return@awaitEachGesture
            }
            when {
                handle != null -> {
                    val p = path0.subpaths[handle.sub].points[handle.index]
                    var working = path0
                    var change: PointerInputChange? = first
                    while (change != null && change.pressed) {
                        val u = snapUnits(change.position, setOf(YtPathGeom.Ref(handle.sub, handle.index)))
                        working = YtPathOps.setHandle(path0, YtPathGeom.Ref(handle.sub, handle.index), handle.side, u.x - p.x, u.y - p.y)
                        sess.live = working
                        change = nextChange(down)
                    }
                    sess.live = null
                    v.commit(sess, working, "handle")
                }
                point != null -> {
                    if (point !in sess.selected) sess.selected = setOf(point)
                    val grabbed = path0.subpaths[point.sub].points[point.index]
                    val sel = sess.selected
                    var working = path0
                    var change: PointerInputChange? = first
                    while (change != null && change.pressed) {
                        // the grabbed point follows the finger (snapped), the others keep their distance to it
                        val target = snapUnits(change.position - (start - frame.toFrame(grabbed.x, grabbed.y).let { it * state.zoom + state.pan }), sel)
                        working = YtPathOps.move(path0, sel, target.x - grabbed.x, target.y - grabbed.y)
                        sess.live = working
                        change = nextChange(down)
                    }
                    sess.live = null
                    v.commit(sess, working, "move points")
                }
                else -> {
                    // a marquee selects the points inside it
                    var change: PointerInputChange? = first
                    var rect = Rect(start, start)
                    while (change != null && change.pressed) {
                        rect = Rect(Offset(min(start.x, change.position.x), min(start.y, change.position.y)), Offset(max(start.x, change.position.x), max(start.y, change.position.y)))
                        YtPenMarquee.rect = rect
                        change = nextChange(down)
                    }
                    YtPenMarquee.rect = null
                    val inside = HashSet<YtPathGeom.Ref>()
                    path0.subpaths.forEachIndexed { si, s ->
                        s.points.forEachIndexed { pi, p ->
                            val sp = frame.toFrame(p.x, p.y) * state.zoom + state.pan
                            if (rect.contains(sp)) inside += YtPathGeom.Ref(si, pi)
                        }
                    }
                    sess.selected = inside
                }
            }
        } finally {
            // cancelled from outside: nothing half done stays
            sess.live = null
            sess.snapX = null
            sess.snapY = null
            YtPenMarquee.rect = null
        }
    }
}

/** The marquee of the pen's edit mode (screen px), drawn by the overlay. */
internal object YtPenMarquee {
    var rect by mutableStateOf<Rect?>(null)
}

private suspend fun androidx.compose.ui.input.pointer.AwaitPointerEventScope.nextChange(down: PointerInputChange): PointerInputChange? {
    val ev = awaitPointerEvent()
    val c = ev.changes.firstOrNull { it.id == down.id } ?: return null
    c.consume()
    return c
}

private suspend fun androidx.compose.ui.input.pointer.AwaitPointerEventScope.waitUp(down: PointerInputChange) {
    while (true) {
        val c = nextChange(down) ?: return
        if (!c.pressed) return
    }
}

/** Two fingers: zoom around the point under them and pan with them (as the canvas does). */
private var lastSpan = 0f
private var lastCentroid = Offset.Zero

private fun transform(state: YtEditorState, changes: List<PointerInputChange>) {
    val pressed = changes.filter { it.pressed }
    if (pressed.size < 2) return
    val a = pressed[0].position
    val b = pressed[1].position
    val centroid = (a + b) / 2f
    val span = hypot(a.x - b.x, a.y - b.y)
    if (lastSpan > 0f) {
        val z = (state.zoom * span / lastSpan).coerceIn(0.1f, 8f)
        val world = (lastCentroid - state.pan) / state.zoom
        state.zoom = z
        state.pan = centroid - world * z
    }
    lastSpan = span
    lastCentroid = centroid
    changes.forEach(PointerInputChange::consume)
}

/** Follows a two-finger transform until the fingers lift. */
private suspend fun androidx.compose.ui.input.pointer.AwaitPointerEventScope.transformOnly(state: YtEditorState, down: PointerInputChange?) {
    lastSpan = 0f
    while (true) {
        val ev = awaitPointerEvent()
        if (ev.changes.none { it.pressed }) break
        if (ev.changes.count { it.pressed } >= 2) transform(state, ev.changes) else lastSpan = 0f
        ev.changes.forEach(PointerInputChange::consume)
    }
    lastSpan = 0f
}

/** A recording: the node follows the finger, the samples are its moves in dp; on release it springs back and the path is built. */
private suspend fun androidx.compose.ui.input.pointer.AwaitPointerEventScope.record(state: YtEditorState, hooks: YtEditorHooks, rec: YtRecordSession, down: PointerInputChange, scope: CoroutineScope) {
    val start = down.position
    val t0 = down.uptimeMillis
    val samples = ArrayList<YtMotionRecord.Sample>()
    samples += YtMotionRecord.Sample(0, 0f, 0f)
    val origin = hooks.nodeBox(rec.nodeId)?.let { Offset(it[0], it[1]) } ?: ((start - state.pan) / state.zoom)
    YtRecordTrail.clear()
    YtRecordTrail += origin
    hooks.dx = 0f
    hooks.dy = 0f
    hooks.dragging = setOf(rec.nodeId)
    try {
        while (true) {
            val ev = awaitPointerEvent()
            val c = ev.changes.firstOrNull { it.id == down.id } ?: break
            c.consume()
            val d = (c.position - start) / state.zoom
            hooks.dx = d.x
            hooks.dy = d.y
            val td = hooks.themeDensity.coerceAtLeast(0.01f)
            samples += YtMotionRecord.Sample(c.uptimeMillis - t0, d.x / td, d.y / td)
            YtRecordTrail += origin + d
            if (!c.pressed) break
        }
    } finally {
        val fx = hooks.dx
        val fy = hooks.dy
        scope.launch {
            animate(1f, 0f, animationSpec = spring(dampingRatio = 0.8f, stiffness = 600f)) { k, _ ->
                hooks.dx = fx * k
                hooks.dy = fy * k
            }
            hooks.dragging = emptySet()
        }
    }
    if (samples.size >= 3) state.vector.recorded(samples) else YtRecordTrail.clear()
}

/**
 * The pen's overlay: the path (a fill preview for a new one), its points (squares are corners,
 * circles smooth), the handles of the selected points, the snap lines, the marquee; or, without a
 * session, the motion path of the selected animation as a dashed line.
 */
internal fun DrawScope.drawPenOverlay(state: YtEditorState, measurer: TextMeasurer, reducedLabel: String, density: Float) {
    val v = state.vector
    val toScreen = { f: Offset -> f * state.zoom + state.pan }
    YtRecordTrail.takeIf { it.size > 1 && v.record != null }?.let { trail ->
        val p = Path()
        trail.forEachIndexed { i, f -> val s = toScreen(f); if (i == 0) p.moveTo(s.x, s.y) else p.lineTo(s.x, s.y) }
        drawPath(p, PenSnap, style = Stroke(2f * density, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f * density, 4f * density))))
    }
    val sess = v.pen
    if (sess == null) {
        val hint = v.motionHint(state.animId) ?: return
        if (state.selection.firstOrNull() != state.anim(state.animId)?.node) return
        drawPathLine(hint.second, hint.first, toScreen, PenAccent.copy(alpha = 0.8f), density, dashed = true)
        return
    }
    val frame = v.frameOf(sess) ?: return
    val path = v.path(sess) ?: return
    if (sess.kind == YtPenKind.NEW) {
        // the new shape's fill as it will be (closed parts)
        val fill = Path()
        for (s in path.subpaths) {
            if (s.points.size < 2) continue
            val poly = YtPathGeom.flatten(s, 0.5 / (frame.sx * state.zoom)) ?: continue
            for (i in 0 until poly.n) {
                val sp = toScreen(frame.toFrame(poly.xs[i].toFloat(), poly.ys[i].toFloat()))
                if (i == 0) fill.moveTo(sp.x, sp.y) else fill.lineTo(sp.x, sp.y)
            }
            fill.close()
        }
        drawPath(fill, PenAccent.copy(alpha = 0.18f))
    }
    drawPathLine(path, frame, toScreen, PenAccent, density, dashed = sess.kind == YtPenKind.MOTION)
    // snap lines
    sess.snapX?.let { x -> val sx = x * state.zoom + state.pan.x; drawLine(PenSnap, Offset(sx, 0f), Offset(sx, size.height), 1f * density) }
    sess.snapY?.let { y -> val sy = y * state.zoom + state.pan.y; drawLine(PenSnap, Offset(0f, sy), Offset(size.width, sy), 1f * density) }
    val r = 5f * density
    val current = path.subpaths.getOrNull(sess.sub)
    path.subpaths.forEachIndexed { si, s ->
        s.points.forEachIndexed { pi, p ->
            val ref = YtPathGeom.Ref(si, pi)
            val a = toScreen(frame.toFrame(p.x, p.y))
            val selected = ref in sess.selected
            val lastDrawn = sess.drawing && si == sess.sub && pi == s.points.lastIndex
            if (selected || lastDrawn) {
                if (p.hasIn) handle(a, toScreen(frame.toFrame(p.x + p.ix, p.y + p.iy)), density)
                if (p.hasOut) handle(a, toScreen(frame.toFrame(p.x + p.ox, p.y + p.oy)), density)
            }
            // the first point of an open subpath being drawn closes it
            if (sess.drawing && current != null && si == sess.sub && pi == 0 && !s.closed && s.points.size >= 2) {
                drawCircle(PenSnap, r * 2.2f, a, style = Stroke(1.5f * density))
            }
            val fillColor = if (selected) PenAccent else Color.White
            if (p.kind == YtPointKind.CORNER) {
                drawRect(fillColor, a - Offset(r, r), Size(2 * r, 2 * r))
                drawRect(PenAccent, a - Offset(r, r), Size(2 * r, 2 * r), style = Stroke(1.5f * density))
            } else {
                drawCircle(fillColor, r, a)
                drawCircle(PenAccent, r, a, style = Stroke(1.5f * density))
            }
        }
    }
    YtPenMarquee.rect?.let {
        drawRect(PenAccent.copy(alpha = 0.12f), it.topLeft, it.size)
        drawRect(PenAccent, it.topLeft, it.size, style = Stroke(1f * density))
    }
    if (v.reduced()) {
        drawText(measurer, reducedLabel, Offset(12f * density, 12f * density), TextStyle(color = Color.White, fontSize = 12.sp, background = Color(0xCC8A5A00)))
    }
}

private fun DrawScope.handle(anchor: Offset, end: Offset, density: Float) {
    drawLine(PenAccent, anchor, end, 1f * density)
    drawCircle(Color.White, 4f * density, end)
    drawCircle(PenAccent, 4f * density, end, style = Stroke(1.5f * density))
}

private fun DrawScope.drawPathLine(path: YtPath, frame: YtPenFrame, toScreen: (Offset) -> Offset, color: Color, density: Float, dashed: Boolean) {
    val zoom = max(abs(frame.sx), 1e-3f)
    val line = Path()
    for (s in path.subpaths) {
        val poly = YtPathGeom.flatten(s, 0.4 / zoom) ?: continue
        for (i in 0 until poly.n) {
            val sp = toScreen(frame.toFrame(poly.xs[i].toFloat(), poly.ys[i].toFloat()))
            if (i == 0) line.moveTo(sp.x, sp.y) else line.lineTo(sp.x, sp.y)
        }
        if (poly.closed) line.close()
    }
    drawPath(line, color, style = Stroke(1.5f * density, pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(8f * density, 5f * density)) else null))
}
