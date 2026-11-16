package com.github.libretube.ui.yt.builder

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import com.github.libretube.R
import com.github.libretube.ui.yt.builder.YtDocOps.withComponent
import kotlin.math.cos
import kotlin.math.sin

/*
 * YT: the theme editor's vector tools (phase 3): the pen session (draw a new path, add to or edit
 * an existing one, edit a motion path), the motion recording, and the document operations behind
 * the "Form" tab (convert to path, booleans, compound paths, masks, outline stroke). Geometry of
 * what is shown comes from the editor hooks (frame px); the document is changed through
 * YtEditorState, so every step is one undo step (point drags commit once, on release).
 */

/** What the pen works on: a new path in a free container, a node's path, or an animation's motion path. */
enum class YtPenKind { NEW, NODE, MOTION }

class YtPenSession(val kind: YtPenKind, val nodeId: String, val animId: String? = null) {
    /** Drawing (taps add points) or editing (select and drag points and handles). */
    var drawing by mutableStateOf(kind == YtPenKind.NEW)

    /** NEW: the path drawn so far, in the container's dp. */
    var draft by mutableStateOf(YtPath())

    /** The subpath taps add to (past the end: a new one). */
    var sub by mutableIntStateOf(0)
    var selected by mutableStateOf<Set<YtPathGeom.Ref>>(emptySet())

    /** The path while a finger drags points (drawn live, committed on release). */
    var live by mutableStateOf<YtPath?>(null)

    /** Snap lines of the drag (frame px), or null. */
    var snapX by mutableStateOf<Float?>(null)
    var snapY by mutableStateOf<Float?>(null)
}

/** A recording in progress or just made: [samples] in dp from where the finger started. */
class YtRecordSession(val nodeId: String, val animId: String) {
    var armed by mutableStateOf(true)
    var samples: List<YtMotionRecord.Sample> = emptyList()
    var simplify by mutableStateOf(1.5f)
    var smooth by mutableStateOf(0.4f)
}

/**
 * Path units of a target ↔ frame px of the editor: frame = origin + R·(x·sx, y·sy). For a node's
 * path the box of the node (rotated about its centre); for a motion path the node's centre, 1 dp
 * per unit, the path's start on the centre.
 */
class YtPenFrame(private val ox: Float, private val oy: Float, val sx: Float, val sy: Float, deg: Float) {
    private val c = cos(Math.toRadians(deg.toDouble())).toFloat()
    private val s = sin(Math.toRadians(deg.toDouble())).toFloat()

    fun toFrame(x: Float, y: Float): Offset {
        val px = x * sx
        val py = y * sy
        return Offset(ox + c * px - s * py, oy + s * px + c * py)
    }

    fun fromFrame(p: Offset): Offset {
        val dx = p.x - ox
        val dy = p.y - oy
        return Offset((c * dx + s * dy) / sx, (-s * dx + c * dy) / sy)
    }

    /** A length in frame px as path units. */
    fun units(px: Float): Float = px / ((sx + sy) / 2f).coerceAtLeast(1e-4f)

    companion object {
        /** For a box centred at ([cx], [cy]) of [w] × [h] px turned by [deg], holding a path box of [vw] × [vh]. */
        fun box(cx: Float, cy: Float, w: Float, h: Float, vw: Float, vh: Float, deg: Float): YtPenFrame {
            val r = Math.toRadians(deg.toDouble())
            val c = cos(r).toFloat()
            val s = sin(r).toFloat()
            // the top left corner after turning about the centre
            val ox = cx + c * (-w / 2f) - s * (-h / 2f)
            val oy = cy + s * (-w / 2f) + c * (-h / 2f)
            return YtPenFrame(ox, oy, w / vw.coerceAtLeast(1e-4f), h / vh.coerceAtLeast(1e-4f), deg)
        }
    }
}

class YtVectorEditor(private val state: YtEditorState) {
    var pen by mutableStateOf<YtPenSession?>(null)
    var record by mutableStateOf<YtRecordSession?>(null)

    /** Frames over budget while dragging points: the dragged path draws reduced (outline only). */
    var overBudget by mutableStateOf(false)

    /** The canvas's hooks (set by the editor screen). */
    var hooks: YtEditorHooks? = null

    /** A note shown under the pen bar (refusals), or null. */
    var hint by mutableStateOf<Int?>(null)

    /** The editor's frame figures now (set by the editor screen), kept for Diagnose when a pen session ends. */
    var frameLine: () -> String? = { null }

    // ---- what the renderer reads ----

    fun livePath(id: String): YtPath? {
        val p = pen ?: return null
        return if (p.kind == YtPenKind.NODE && p.nodeId == id) p.live else null
    }

    fun reduced(): Boolean = overBudget && pen?.live != null

    // ---- geometry ----

    private val td: Float get() = hooks?.themeDensity ?: 1f

    /** The path the pen shows now (live while dragging). */
    fun path(p: YtPenSession): YtPath? = p.live ?: committed(p)

    /** The path as it is in the document (or the draft): what panels show, so a drag does not recompose them. */
    fun committed(p: YtPenSession): YtPath? = when (p.kind) {
        YtPenKind.NEW -> p.draft
        YtPenKind.NODE -> state.node(p.nodeId)?.path
        YtPenKind.MOTION -> state.anim(p.animId)?.motion?.path ?: YtPath(w = 1f, h = 1f)
    }

    /** The mapping of [p]'s path units to frame px, or null when its node is not shown. */
    fun frameOf(p: YtPenSession): YtPenFrame? {
        val h = hooks ?: return null
        return when (p.kind) {
            YtPenKind.NEW -> {
                val b = h.nodeBox(p.nodeId) ?: return null
                val rot = state.node(p.nodeId)?.rotation ?: 0f
                YtPenFrame.box(b[0], b[1], b[2], b[3], b[2] / td, b[3] / td, rot)
            }
            YtPenKind.NODE -> {
                val n = state.node(p.nodeId) ?: return null
                val path = n.path ?: return null
                val b = h.nodeBox(p.nodeId) ?: return null
                YtPenFrame.box(b[0], b[1], b[2], b[3], path.w, path.h, n.rotation)
            }
            YtPenKind.MOTION -> {
                val b = h.nodeBox(p.nodeId) ?: return null
                val first = path(p)?.subpaths?.firstOrNull()?.points?.firstOrNull()
                YtPenFrame(b[0] - (first?.x ?: 0f) * td, b[1] - (first?.y ?: 0f) * td, td, td, 0f)
            }
        }
    }

    /** The motion path of animation [animId] for the canvas hint (not edited): its frame and path. */
    fun motionHint(animId: String?): Pair<YtPenFrame, YtPath>? {
        val a = state.anim(animId) ?: return null
        val m = a.motion ?: return null
        if (a.tracks.none { it.prop == YtAnimProp.PATH }) return null
        val b = hooks?.nodeBox(a.node) ?: return null
        val first = m.path.subpaths.firstOrNull()?.points?.firstOrNull()
        return YtPenFrame(b[0] - (first?.x ?: 0f) * td, b[1] - (first?.y ?: 0f) * td, td, td, 0f) to m.path
    }

    // ---- pen sessions ----

    /** The free container new shapes go into: the selected one, or the parent of the selection. */
    fun drawContainer(): YtNode? {
        val n = state.primary ?: return null
        val c = if (n.type == YtNodeType.CONTAINER) n else state.parentOf(n.id) ?: return null
        return c.takeIf { it.layout.mode == YtLayoutMode.FREE }
    }

    fun startDraw(): Boolean {
        val c = drawContainer()
        if (c == null) {
            hint = R.string.yt_vec_need_free
            return false
        }
        record = null
        pen = YtPenSession(YtPenKind.NEW, c.id)
        hint = null
        return true
    }

    fun startEdit(nodeId: String, drawMore: Boolean = false) {
        val n = state.node(nodeId) ?: return
        if (n.path == null) return
        record = null
        pen = YtPenSession(YtPenKind.NODE, nodeId).also {
            it.drawing = drawMore
            it.sub = n.path.subpaths.size
        }
        hint = null
    }

    fun startMotion(animId: String) {
        val a = state.anim(animId) ?: return
        record = null
        pen = YtPenSession(YtPenKind.MOTION, a.node, animId).also { it.sub = a.motion?.path?.subpaths?.size ?: 0 }
        hint = null
    }

    /** Writes [path] for the session's target: one undo step per call ([coalesce] merges quick steps of one control). */
    fun commit(p: YtPenSession, path: YtPath, label: String = "path", coalesce: String? = null) {
        when (p.kind) {
            YtPenKind.NEW -> p.draft = path
            YtPenKind.NODE -> state.editNode(p.nodeId, label, coalesce) { it.copy(shape = YtShapeKind.PATH, path = path) }
            YtPenKind.MOTION -> p.animId?.let { id -> state.editAnim(id, label, coalesce) { a -> a.copy(motion = (a.motion ?: YtMotionPath()).copy(path = path)) } }
        }
    }

    /**
     * Ends the session: a new path becomes a node of its container; with [keepEditing] (the path was
     * just closed by a tap on its first point) its points stay editable at once.
     */
    fun finish(keepEditing: Boolean = false) {
        val p = pen ?: return
        pen = null
        state.seal()
        YtVectorStats.pen(p.kind, committed(p)?.pointCount ?: 0, frameLine())
        if (p.kind != YtPenKind.NEW) return
        val draft = p.draft.copy(subpaths = p.draft.subpaths.filter { it.points.size >= 2 })
        if (draft.isEmpty) return
        val container = state.node(p.nodeId) ?: return
        val kind = state.kindOf(container.id) ?: return
        val (path, b) = YtPathOps.normalized(draft) ?: return
        val open = draft.subpaths.all { !it.closed }
        val node = YtNode(
            "path", type = YtNodeType.SHAPE, name = state.getApplication<android.app.Application>().getString(R.string.yt_vec_path_name),
            shape = YtShapeKind.PATH, path = path,
            width = YtSize.fixed(b[2]), height = YtSize.fixed(b[3]),
            x = b[0] - container.padding.start, y = b[1] - container.padding.top,
            style = if (open) YtStyle(strokes = listOf(YtStroke(YtPaint.token(YtRole.ACCENT), 2f))) else YtStyle(fills = listOf(YtPaint.token(YtRole.ACCENT))),
        )
        val taken = state.takenIds()
        val fresh = YtDocOps.withFreshIds(node, taken)
        state.run("draw") { d -> d.withComponent(kind) { YtDocOps.add(it, container.id, fresh, taken) } }
        state.selection = setOf(fresh.id)
        if (keepEditing) startEdit(fresh.id)
    }

    fun cancel() {
        pen?.live = null
        pen = null
        record = null
    }

    // ---- point actions (the pen bar) ----

    private fun valid(p: YtPenSession, path: YtPath) = p.selected.filter { path.subpaths.getOrNull(it.sub)?.points?.getOrNull(it.index) != null }.toSet()

    fun deletePoints() {
        val p = pen ?: return
        val path = path(p) ?: return
        commit(p, YtPathOps.remove(path, valid(p, path)), "points")
        p.selected = emptySet()
    }

    fun setKind(kind: YtPointKind) {
        val p = pen ?: return
        var path = path(p) ?: return
        // a corner drops its handles (a sharp point); smooth and symmetric ones get aligned handles
        for (r in valid(p, path)) path = if (kind == YtPointKind.CORNER) YtPathOps.sharpen(path, r) else YtPathOps.setKind(path, r, kind)
        commit(p, path, "point kind")
    }

    fun splitAt() {
        val p = pen ?: return
        val path = path(p) ?: return
        val r = valid(p, path).firstOrNull() ?: return
        commit(p, YtPathOps.split(path, r), "split")
        p.selected = emptySet()
    }

    /** Joins the two selected end points, or closes the subpath of the one selected point. */
    fun joinOrClose() {
        val p = pen ?: return
        val path = path(p) ?: return
        val sel = valid(p, path).toList()
        val next = when (sel.size) {
            1 -> YtPathOps.setClosed(path, sel[0].sub, true)
            2 -> YtPathOps.join(path, sel[0], sel[1])
            else -> return
        }
        commit(p, next, "join")
        p.selected = emptySet()
    }

    fun reversePen() {
        val p = pen ?: return
        val path = path(p) ?: return
        commit(p, YtPathOps.reverse(path), "reverse")
    }

    /** Sets the selected point's number [what] (x, y, in/out handle x/y) to [v], merged while typed. */
    fun setNumber(ref: YtPathGeom.Ref, what: Int, v: Float) {
        val p = pen ?: return
        val path = path(p) ?: return
        val pt = path.subpaths.getOrNull(ref.sub)?.points?.getOrNull(ref.index) ?: return
        val next = when (what) {
            0 -> YtPathOps.moveTo(path, ref, v, pt.y)
            1 -> YtPathOps.moveTo(path, ref, pt.x, v)
            2 -> YtPathOps.setHandle(path, ref, YtPathGeom.HandleSide.IN, v, pt.iy)
            3 -> YtPathOps.setHandle(path, ref, YtPathGeom.HandleSide.IN, pt.ix, v)
            4 -> YtPathOps.setHandle(path, ref, YtPathGeom.HandleSide.OUT, v, pt.oy)
            else -> YtPathOps.setHandle(path, ref, YtPathGeom.HandleSide.OUT, pt.ox, v)
        }
        commit(p, next, "point", "pt:${p.nodeId}:${p.animId}:${ref.sub}:${ref.index}:$what")
    }

    // ---- document operations of the Form tab ----

    /** The node's box in its parent (centre x, y, width, height in dp). */
    private fun box(id: String): FloatArray? = hooks?.boxInParent(id, state)

    /** [node]'s outline placed in its parent (dp), or null when it is not shown. */
    private fun placed(node: YtNode): YtPath? {
        val b = box(node.id) ?: return null
        val local = YtShapeGeom.pathOf(node, b[2], b[3])
        return YtPathOps.place(local, b[0], b[1], b[2], b[3], node.rotation)
    }

    /** The node's size in dp: shown on the canvas, else its fixed rules. */
    private fun sizeOf(node: YtNode): Pair<Float, Float> {
        val b = box(node.id)
        if (b != null) return b[2] to b[3]
        val w = if (node.width.kind == YtSizeKind.FIXED) node.width.value else 48f
        val h = if (node.height.kind == YtSizeKind.FIXED) node.height.value else 48f
        return w to h
    }

    fun toPath(id: String) {
        val n = state.node(id) ?: return
        if (n.type != YtNodeType.SHAPE || n.shape == YtShapeKind.PATH) return
        val (w, h) = sizeOf(n)
        val path = YtShapeGeom.pathOf(n, w, h)
        state.editNode(id, "to path") { it.copy(shape = YtShapeKind.PATH, path = path) }
    }

    fun outlineStroke(id: String) {
        val n = state.node(id) ?: return
        val stroke = n.style.strokes.firstOrNull { it.enabled && it.width > 0f } ?: return
        val (w, h) = sizeOf(n)
        val local = YtShapeGeom.pathOf(n, w, h)
        val shape = YtPathBoolean.outline(local, stroke.width).copy(w = w, h = h)
        if (shape.isEmpty) return
        state.editNode(id, "outline") {
            it.copy(shape = YtShapeKind.PATH, path = shape, style = it.style.copy(fills = listOf(stroke.paint), strokes = it.style.strokes - stroke))
        }
    }

    fun simplify(id: String, tolerance: Float) {
        val path = state.node(id)?.path ?: return
        state.editNode(id, "simplify", "simplify") { it.copy(path = YtPathOps.simplify(path, tolerance)) }
    }

    fun smooth(id: String) {
        val path = state.node(id)?.path ?: return
        state.editNode(id, "smooth") { it.copy(path = YtPathOps.smooth(path, 1f)) }
    }

    /** The selection as siblings in one free container, in their drawing order (bottom first), or null. */
    fun siblings(): List<YtNode>? {
        val ids = state.selection
        if (ids.size < 2) return null
        val parent = state.parentOf(ids.first()) ?: return null
        if (parent.layout.mode != YtLayoutMode.FREE) return null
        if (ids.any { state.parentOf(it)?.id != parent.id }) return null
        return parent.children.filter { it.id in ids }
    }

    /** The boolean [op] of the selected shapes: one path node in place of the bottom one, in its look. */
    fun boolean(op: YtPathBoolean.Op) {
        val nodes = siblings() ?: run { hint = R.string.yt_vec_need_siblings; return }
        if (nodes.any { it.type != YtNodeType.SHAPE }) {
            hint = R.string.yt_vec_only_shapes
            return
        }
        val parent = state.parentOf(nodes.first().id) ?: return
        val paths = nodes.map { placed(it) ?: return }
        val began = System.nanoTime()
        val result = YtPathBoolean.op(op, paths)
        YtVectorStats.boolean(op, nodes.size, (System.nanoTime() - began) / 1_000_000.0, result.pointCount)
        place(nodes, parent, result, opName(op))
    }

    /** The selected shapes as one compound path (no area merged). */
    fun compound(evenOdd: Boolean) {
        val nodes = siblings() ?: run { hint = R.string.yt_vec_need_siblings; return }
        if (nodes.any { it.type != YtNodeType.SHAPE }) {
            hint = R.string.yt_vec_only_shapes
            return
        }
        val parent = state.parentOf(nodes.first().id) ?: return
        val paths = nodes.map { placed(it) ?: return }
        place(nodes, parent, YtPathOps.combine(paths, if (evenOdd) YtFillRule.EVEN_ODD else YtFillRule.NON_ZERO), state.getApplication<android.app.Application>().getString(R.string.yt_vec_compound))
    }

    private fun opName(op: YtPathBoolean.Op): String = state.getApplication<android.app.Application>().getString(
        when (op) {
            YtPathBoolean.Op.UNION -> R.string.yt_vec_union
            YtPathBoolean.Op.SUBTRACT -> R.string.yt_vec_subtract
            YtPathBoolean.Op.INTERSECT -> R.string.yt_vec_intersect
            YtPathBoolean.Op.EXCLUDE -> R.string.yt_vec_exclude
        },
    )

    private fun place(nodes: List<YtNode>, parent: YtNode, result: YtPath, name: String) {
        val (path, b) = YtPathOps.normalized(result) ?: run {
            // nothing left (e.g. shapes that do not overlap, intersected)
            hint = R.string.yt_vec_empty_result
            return
        }
        val base = nodes.first()
        val kind = state.kindOf(base.id) ?: return
        val made = base.copy(
            name = name, type = YtNodeType.SHAPE, shape = YtShapeKind.PATH, path = path.copy(fillRule = result.fillRule),
            width = YtSize.fixed(b[2]), height = YtSize.fixed(b[3]), aspect = null,
            x = b[0] - parent.padding.start, y = b[1] - parent.padding.top, anchorH = YtAnchor.START, anchorV = YtAnchor.START,
            rotation = 0f, mask = null, children = emptyList(), text = null, slot = null, icon = null,
            style = if (base.style.fills.isEmpty() && base.style.strokes.isEmpty()) base.style.copy(fills = listOf(YtPaint.token(YtRole.ACCENT))) else base.style,
        )
        val drop = nodes.drop(1).map { it.id }.toSet()
        state.run("boolean") { d ->
            val out = d.withComponent(kind) { root ->
                val cleaned = drop.fold(root) { r, id -> YtDocOps.remove(r, id) }
                YtDocOps.update(cleaned, base.id) { made }
            }
            YtAnimOps.withoutNodes(out)
        }
        state.selection = setOf(base.id)
        hint = null
    }

    /**
     * Masks with the selection: of two siblings the shape on top masks the other one (an image too);
     * the mask shape is taken into the masked node.
     */
    fun mask() {
        val nodes = siblings()?.takeIf { it.size == 2 } ?: run { hint = R.string.yt_vec_need_two; return }
        val maskNode = nodes.lastOrNull { it.type == YtNodeType.SHAPE } ?: run { hint = R.string.yt_vec_need_shape; return }
        val target = nodes.first { it.id != maskNode.id }
        val mp = placed(maskNode) ?: return
        val tb = box(target.id) ?: return
        val local = YtPathOps.unplace(mp, tb[0], tb[1], tb[2], tb[3], target.rotation)
        val kind = state.kindOf(target.id) ?: return
        state.run("mask") { d ->
            val out = d.withComponent(kind) { root ->
                YtDocOps.update(YtDocOps.remove(root, maskNode.id), target.id) { it.copy(mask = YtMask(local)) }
            }
            YtAnimOps.withoutNodes(out)
        }
        state.selection = setOf(target.id)
        hint = null
    }

    // ---- motion paths and morphs ----

    /** A new animation of the selected node: [build] fills it in; it is selected and played. */
    private fun newAnim(build: (YtAnim) -> YtAnim): String? {
        val node = state.primary ?: return null
        val kind = state.kindOf(node.id) ?: return null
        val d = state.doc ?: return null
        val id = YtAnimOps.freshId(d.animations)
        val a = build(YtAnim(id, component = kind, node = node.id, trigger = YtTrigger(YtTriggerKind.TAP)))
        state.run("animation") { it.copy(animations = it.animations.copy(items = it.animations.items + a)) }
        state.animId = id
        state.keySel = emptySet()
        return id
    }

    fun addMotion(): String? = newAnim {
        it.copy(
            name = state.getApplication<android.app.Application>().getString(R.string.yt_vec_motion_name),
            durationMs = 900,
            motion = YtMotionPath(YtPath(listOf(YtSubpath(listOf(YtPoint(0f, 0f, ox = 40f, oy = -50f), YtPoint(140f, 0f, ix = -40f, iy = -50f)), closed = false)), w = 1f, h = 1f)),
            tracks = listOf(YtMotionRecord.evenSpeed()),
        )
    }?.also { state.playOnce(it) }

    fun addMorph(): String? {
        val node = state.primary ?: return null
        if (node.type != YtNodeType.SHAPE) return null
        val target = if (node.shape == YtShapeKind.ELLIPSE) YtMorphTarget(YtShapeKind.STAR, YtShapeSpec(sides = 5, inner = 0.5f)) else YtMorphTarget(YtShapeKind.ELLIPSE, corners = YtCorners.all(500f))
        return newAnim {
            it.copy(
                name = state.getApplication<android.app.Application>().getString(R.string.yt_vec_morph_name),
                durationMs = 600, direction = YtAnimDirection.ALTERNATE, repeat = 1, morph = target,
                tracks = listOf(YtTrack(YtAnimProp.MORPH, listOf(YtKeyframe(0f, 0f, curve = YtCurve.EASE_IN_OUT), YtKeyframe(1f, 1f)))),
            )
        }?.also { state.playOnce(it) }
    }

    /** Whether the morph of animation [a] on its node is only approximated (no exact match). */
    fun approximated(a: YtAnim): Boolean {
        val node = state.node(a.node) ?: return false
        val t = a.morph ?: return false
        val (w, h) = sizeOf(node)
        return runCatching { YtMorph.plan(YtShapeGeom.targetOf(node), t, w, h).approximated }.getOrDefault(false)
    }

    /** The morph target from another shape node's outline (in this node's box). */
    fun morphFromNode(animId: String, sourceId: String) {
        val a = state.anim(animId) ?: return
        val node = state.node(a.node) ?: return
        val src = state.node(sourceId) ?: return
        val (w, h) = sizeOf(node)
        val (sw, sh) = sizeOf(src)
        val path = YtPathOps.scale(YtShapeGeom.pathOf(src, sw, sh), w / sw.coerceAtLeast(0.01f), h / sh.coerceAtLeast(0.01f)).copy(w = w, h = h)
        state.editAnim(animId, "morph") { it.copy(morph = YtMorphTarget(YtShapeKind.PATH, path = path)) }
    }

    // ---- recording ----

    fun startRecording() {
        val node = state.primary ?: return
        val current = state.anim(state.animId)?.takeIf { it.node == node.id && it.tracks.any { t -> t.prop == YtAnimProp.PATH } }
        val id = current?.id ?: newAnim { it.copy(name = state.getApplication<android.app.Application>().getString(R.string.yt_vec_recorded_name), motion = YtMotionPath(), tracks = listOf(YtMotionRecord.evenSpeed(YtCurve.LINEAR))) } ?: return
        pen = null
        record = YtRecordSession(node.id, id)
    }

    /** The finger was let go: the recording becomes the animation's path and timing. */
    fun recorded(samples: List<YtMotionRecord.Sample>) {
        val r = record ?: return
        r.samples = samples
        r.armed = false
        applyRecording(r, seal = true)
        state.playOnce(r.animId)
    }

    /** The sliders changed: built again from the raw samples (merged into one undo step while dragged). */
    fun applyRecording(r: YtRecordSession, seal: Boolean = false) {
        val a = state.anim(r.animId) ?: return
        val result = YtMotionRecord.build(r.samples, r.simplify, r.smooth, a.motion?.orient ?: false, a.motion?.angle ?: 0f) ?: return
        state.editAnim(r.animId, "record", if (seal) null else "record") {
            it.copy(kind = YtAnimKind.TIME, durationMs = result.durationMs, motion = result.motion, tracks = it.tracks.filter { t -> t.prop != YtAnimProp.PATH } + result.track)
        }
        if (seal) state.seal()
    }
}

/**
 * YT: what the vector tools did, for Diagnose ("Vektor"): the active theme's vector content, the
 * last pen session (points, frame times while it ran, kept across restarts) and the last boolean.
 */
object YtVectorStats {
    private const val PREF = "yt_builder_vector"
    private var lastBoolean: String? = null

    fun pen(kind: YtPenKind, points: Int, frames: String?) {
        runCatching {
            com.github.libretube.helpers.PreferenceHelper.putString(
                PREF,
                "${kind.name.lowercase()} with $points points; frames ${frames ?: "not measured"} · ${com.github.libretube.util.YtCrashLog.formatTime(System.currentTimeMillis())}",
            )
        }
    }

    fun boolean(op: YtPathBoolean.Op, shapes: Int, ms: Double, points: Int) {
        lastBoolean = "${op.name.lowercase()} of $shapes in %.1f ms → $points points".format(java.util.Locale.ROOT, ms)
    }

    fun diagnosis(): String {
        val doc = YtBuilderActive.doc
        val content = if (doc == null) {
            "no theme loaded"
        } else {
            val nodes = YtComponentKind.entries.flatMap { YtDocOps.all(doc.components[it]) }
            val paths = nodes.filter { it.shape == YtShapeKind.PATH && it.path != null }
            val parametric = nodes.count { it.type == YtNodeType.SHAPE && !it.shape.cornered && it.shape != YtShapeKind.PATH }
            val motions = doc.animations.items.count { a -> a.motion != null && a.tracks.any { it.prop == YtAnimProp.PATH } }
            val morphs = doc.animations.items.count { a -> a.morph != null && a.tracks.any { it.prop == YtAnimProp.MORPH } }
            "${paths.size} paths (${paths.sumOf { it.path!!.pointCount }} points), $parametric parametric, ${nodes.count { it.mask != null }} masks, $motions motion paths, $morphs morphs"
        }
        val pen = runCatching { com.github.libretube.helpers.PreferenceHelper.getString(PREF, "") }.getOrNull().orEmpty().ifEmpty { "no pen session yet" }
        return "$content; last pen: $pen; last boolean: ${lastBoolean ?: "none"}"
    }
}
