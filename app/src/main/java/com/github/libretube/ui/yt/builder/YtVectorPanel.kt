package com.github.libretube.ui.yt.builder

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.libretube.R
import kotlin.math.roundToInt

/*
 * YT: the vector tools' panels (phase 3): the pen bar over the canvas, the selected point's
 * numbers, the recording's sliders, the "Form" tab of a node (shape kind and parameters, convert to
 * path, path operations, booleans, compound paths, masks) and the motion path and morph section of
 * the animation tab.
 */

private val VText = Color(0xFFE6E7EB)
private val VDim = Color(0xFF9EA2AD)
private val VWarn = Color(0xFFFFC46B)

@Composable
private fun VLabel(text: String) = Text(text, color = VDim, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp, bottom = 2.dp))

@Composable
private fun <T> VChoice(options: List<Pair<T, String>>, selected: T?, onSelect: (T) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for ((value, label) in options) FilterChip(selected = value == selected, onClick = { onSelect(value) }, label = { Text(label, fontSize = 12.sp) })
    }
}

/** Over the canvas while the pen or a recording runs: mode, point actions, done. */
@Composable
fun YtPenBar(state: YtEditorState) {
    val v = state.vector
    val sess = v.pen
    val rec = v.record
    Column(Modifier.fillMaxWidth().background(Color(0xFF1D1F24))) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            if (sess != null) {
                Text(
                    stringResource(when (sess.kind) { YtPenKind.NEW -> R.string.yt_vec_pen_new; YtPenKind.NODE -> R.string.yt_vec_pen_node; YtPenKind.MOTION -> R.string.yt_vec_pen_motion }),
                    color = VText, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 6.dp),
                )
                FilterChip(selected = sess.drawing, onClick = { sess.drawing = true; sess.sub = v.path(sess)?.subpaths?.size ?: 0 }, label = { Text(stringResource(R.string.yt_vec_draw)) })
                if (sess.kind != YtPenKind.NEW) FilterChip(selected = !sess.drawing, onClick = { sess.drawing = false }, label = { Text(stringResource(R.string.yt_vec_edit)) })
                if (sess.selected.isNotEmpty()) {
                    TextButton(onClick = v::deletePoints) { Text(stringResource(R.string.yt_builder_delete)) }
                    TextButton(onClick = { v.setKind(YtPointKind.CORNER) }) { Text(stringResource(R.string.yt_vec_corner)) }
                    TextButton(onClick = { v.setKind(YtPointKind.SMOOTH) }) { Text(stringResource(R.string.yt_vec_smooth_point)) }
                    TextButton(onClick = { v.setKind(YtPointKind.SYMMETRIC) }) { Text(stringResource(R.string.yt_vec_symmetric)) }
                    TextButton(onClick = v::splitAt) { Text(stringResource(R.string.yt_vec_split)) }
                    TextButton(onClick = v::joinOrClose) { Text(stringResource(if (sess.selected.size == 2) R.string.yt_vec_join else R.string.yt_vec_close)) }
                }
                if (sess.kind != YtPenKind.NEW) TextButton(onClick = v::reversePen) { Text(stringResource(R.string.yt_vec_reverse)) }
                TextButton(onClick = v::finish) { Text("✓ " + stringResource(R.string.yt_vec_done)) }
                if (sess.kind == YtPenKind.NEW) TextButton(onClick = v::cancel) { Text(stringResource(R.string.yt_vec_discard)) }
            } else if (rec != null) {
                Text(stringResource(if (rec.armed) R.string.yt_vec_record_go else R.string.yt_vec_record_done), color = VText, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 6.dp))
                if (!rec.armed) TextButton(onClick = { rec.armed = true; YtRecordTrail.clear() }) { Text(stringResource(R.string.yt_vec_record_again)) }
                TextButton(onClick = { v.record = null; YtRecordTrail.clear() }) { Text("✓ " + stringResource(R.string.yt_vec_done)) }
            }
        }
        val help = when {
            sess?.drawing == true -> R.string.yt_vec_help_draw
            sess != null -> R.string.yt_vec_help_edit
            else -> null
        }
        if (help != null) Text(stringResource(help), color = VDim, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp))
        v.hint?.let { Text(stringResource(it), color = VWarn, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp)) }
    }
}

/** The tools panel while the pen runs: the selected point's place and handles as numbers. */
@Composable
fun YtPenPanel(state: YtEditorState) {
    val v = state.vector
    val sess = v.pen ?: return
    // the document's path: a point drag shows on the canvas, the numbers follow on release
    val path = v.committed(sess) ?: return
    Text(stringResource(R.string.yt_vec_points_count, path.pointCount, path.subpaths.size), color = VText, fontSize = 14.sp)
    val ref = sess.selected.firstOrNull { path.subpaths.getOrNull(it.sub)?.points?.getOrNull(it.index) != null }
    if (ref == null) {
        Text(stringResource(R.string.yt_vec_no_point), color = VDim, fontSize = 12.sp)
        return
    }
    val p = path.subpaths[ref.sub].points[ref.index]
    VLabel(stringResource(R.string.yt_vec_point_n, ref.index + 1, ref.sub + 1) + " · " + stringResource(kindLabel(p.kind)))
    YtNumberRow("X", p.x, 1f, { v.setNumber(ref, 0, it) })
    YtNumberRow("Y", p.y, 1f, { v.setNumber(ref, 1, it) })
    VLabel(stringResource(R.string.yt_vec_handle_in))
    YtNumberRow("X", p.ix, 1f, { v.setNumber(ref, 2, it) })
    YtNumberRow("Y", p.iy, 1f, { v.setNumber(ref, 3, it) })
    VLabel(stringResource(R.string.yt_vec_handle_out))
    YtNumberRow("X", p.ox, 1f, { v.setNumber(ref, 4, it) })
    YtNumberRow("Y", p.oy, 1f, { v.setNumber(ref, 5, it) })
    if (sess.kind == YtPenKind.MOTION) Text(stringResource(R.string.yt_vec_motion_units), color = VDim, fontSize = 11.sp)
}

private fun kindLabel(k: YtPointKind) = when (k) {
    YtPointKind.CORNER -> R.string.yt_vec_corner
    YtPointKind.SMOOTH -> R.string.yt_vec_smooth_point
    YtPointKind.SYMMETRIC -> R.string.yt_vec_symmetric
}

/** The tools panel after a recording: simplify, smooth, duration and speed. */
@Composable
fun YtRecordPanel(state: YtEditorState) {
    val v = state.vector
    val rec = v.record ?: return
    val a = state.anim(rec.animId) ?: return
    if (rec.samples.isEmpty()) {
        Text(stringResource(R.string.yt_vec_record_explain), color = VDim, fontSize = 12.sp)
        return
    }
    YtSliderRow(stringResource(R.string.yt_vec_simplify), rec.simplify, 0f..12f, { rec.simplify = it; v.applyRecording(rec) }, { state.seal(); state.playOnce(rec.animId) }, 1, " dp")
    YtSliderRow(stringResource(R.string.yt_vec_smooth), rec.smooth * 100f, 0f..100f, { rec.smooth = it / 100f; v.applyRecording(rec) }, { state.seal(); state.playOnce(rec.animId) }, unit = " %")
    Text(stringResource(R.string.yt_vec_points_count, a.motion?.path?.pointCount ?: 0, 1), color = VDim, fontSize = 11.sp)
    YtMotionTiming(state, a)
}

/** Duration, speed and even speed of a motion path animation. */
@Composable
private fun YtMotionTiming(state: YtEditorState, a: YtAnim) {
    YtSliderRow(stringResource(R.string.yt_anim_duration), a.durationMs.toFloat(), 100f..10_000f, { ms ->
        state.editAnim(a.id, "duration", "duration") { it.copy(durationMs = ms.roundToInt()) }
    }, { state.seal(); state.playOnce(a.id) }, unit = " ms")
    // speed: the same motion faster or slower (the duration scaled), measured from the value at the start of the drag
    var base by remember(a.id) { mutableFloatStateOf(a.durationMs.toFloat()) }
    var speed by remember(a.id) { mutableFloatStateOf(1f) }
    YtSliderRow(stringResource(R.string.yt_vec_speed), speed * 100f, 25f..400f, { pct ->
        speed = pct / 100f
        state.editAnim(a.id, "speed", "speed") { it.copy(durationMs = (base / speed).roundToInt().coerceIn(50, YtAnimValidate.MAX_MS)) }
    }, {
        state.seal()
        base = (base / speed)
        speed = 1f
        state.playOnce(a.id)
    }, unit = " %")
    TextButton(onClick = {
        state.editAnim(a.id, "even speed") { it.copy(tracks = it.tracks.filter { t -> t.prop != YtAnimProp.PATH } + YtMotionRecord.evenSpeed()) }
        state.playOnce(a.id)
    }) { Text(stringResource(R.string.yt_vec_even_speed)) }
}

/** The "Form" tab of a node. */
@Composable
fun YtFormTab(state: YtEditorState, node: YtNode) {
    val v = state.vector
    // a refusal belongs to the selection it was about
    androidx.compose.runtime.LaunchedEffect(state.selection) { v.hint = null }
    v.hint?.let { Text(stringResource(it), color = VWarn, fontSize = 12.sp) }
    // several siblings: booleans, compound path, mask
    val sibs = v.siblings()
    if (sibs != null) {
        VLabel(stringResource(R.string.yt_vec_boolean, sibs.size))
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            TextButton(onClick = { v.boolean(YtPathBoolean.Op.UNION) }) { Text(stringResource(R.string.yt_vec_union)) }
            TextButton(onClick = { v.boolean(YtPathBoolean.Op.SUBTRACT) }) { Text(stringResource(R.string.yt_vec_subtract)) }
            TextButton(onClick = { v.boolean(YtPathBoolean.Op.INTERSECT) }) { Text(stringResource(R.string.yt_vec_intersect)) }
            TextButton(onClick = { v.boolean(YtPathBoolean.Op.EXCLUDE) }) { Text(stringResource(R.string.yt_vec_exclude)) }
        }
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            TextButton(onClick = { v.compound(false) }) { Text(stringResource(R.string.yt_vec_compound)) }
            if (sibs.size == 2 && sibs.any { it.type == YtNodeType.SHAPE }) TextButton(onClick = v::mask) { Text(stringResource(R.string.yt_vec_mask)) }
        }
        Text(stringResource(R.string.yt_vec_boolean_note), color = VDim, fontSize = 11.sp)
    } else if (state.selection.size > 1) {
        Text(stringResource(R.string.yt_vec_need_siblings), color = VDim, fontSize = 12.sp)
    } else if (state.selection.size == 1) {
        Text(stringResource(R.string.yt_vec_multi_hint), color = VDim, fontSize = 11.sp)
    }

    // a free container: new shapes and the pen
    if (node.type == YtNodeType.CONTAINER && node.layout.mode == YtLayoutMode.FREE) {
        VLabel(stringResource(R.string.yt_vec_add_shape))
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            for ((kind, label) in newShapes()) {
                TextButton(onClick = {
                    state.addChild(YtNode("shape", type = YtNodeType.SHAPE, name = label, shape = kind, shapeSpec = YtShapeSpec(sides = if (kind == YtShapeKind.STAR) 5 else 6), width = YtSize.fixed(64f), height = YtSize.fixed(64f), x = 16f, y = 16f, style = YtStyle(fills = listOf(YtPaint.token(YtRole.ACCENT)), corners = if (kind == YtShapeKind.ELLIPSE) YtCorners.all(500f) else YtCorners())))
                }) { Text(label) }
            }
            TextButton(onClick = { v.startDraw() }) { Text("✎ " + stringResource(R.string.yt_vec_pen)) }
        }
    }

    node.mask?.let { m ->
        VLabel(stringResource(R.string.yt_vec_masked))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.yt_vec_mask_invert), color = VText, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Switch(checked = m.invert, onCheckedChange = { on -> state.editNode(node.id, "mask") { it.copy(mask = m.copy(invert = on)) } })
        }
        TextButton(onClick = { state.editNode(node.id, "mask") { it.copy(mask = null) } }) { Text(stringResource(R.string.yt_vec_mask_remove)) }
    }

    if (node.type != YtNodeType.SHAPE) {
        if (node.type != YtNodeType.CONTAINER && sibs == null) Text(stringResource(R.string.yt_vec_mask_hint), color = VDim, fontSize = 11.sp)
        return
    }
    VLabel(stringResource(R.string.yt_builder_shape))
    VChoice(
        listOf(
            YtShapeKind.RECT to "▭", YtShapeKind.PILL to "⬭", YtShapeKind.ELLIPSE to "◯", YtShapeKind.TRIANGLE to "△",
            YtShapeKind.POLYGON to "⬡", YtShapeKind.STAR to "★", YtShapeKind.SQUIRCLE to "▢",
        ) + if (node.path != null) listOf(YtShapeKind.PATH to "✎") else emptyList(),
        node.shape,
    ) { k ->
        state.editNode(node.id, "shape") { n ->
            when (k) {
                YtShapeKind.PILL, YtShapeKind.ELLIPSE -> n.copy(shape = k, style = n.style.copy(corners = YtCorners.all(500f, n.style.corners.smoothing)))
                else -> n.copy(shape = k)
            }
        }
    }
    val spec = node.shapeSpec
    fun setSpec(change: (YtShapeSpec) -> YtShapeSpec) = state.editNode(node.id, "shape", "shapeSpec") { it.copy(shapeSpec = YtVectorValidate.spec(change(it.shapeSpec))) }
    when (node.shape) {
        YtShapeKind.POLYGON, YtShapeKind.STAR -> {
            YtSliderRow(stringResource(if (node.shape == YtShapeKind.STAR) R.string.yt_vec_points else R.string.yt_vec_sides), spec.sides.toFloat(), 3f..16f, { n -> setSpec { it.copy(sides = n.roundToInt()) } }, state::seal)
            if (node.shape == YtShapeKind.STAR) YtSliderRow(stringResource(R.string.yt_vec_inner), spec.inner * 100f, 10f..95f, { p -> setSpec { it.copy(inner = p / 100f) } }, state::seal, unit = " %")
        }
        else -> Unit
    }
    if (node.shape == YtShapeKind.TRIANGLE || node.shape == YtShapeKind.POLYGON || node.shape == YtShapeKind.STAR || node.shape == YtShapeKind.SQUIRCLE) {
        YtSliderRow(stringResource(R.string.yt_vec_rounding), spec.rounding, 0f..60f, { r -> setSpec { it.copy(rounding = r) } }, state::seal, unit = " dp")
        YtSliderRow(stringResource(R.string.yt_builder_smoothing), spec.smoothing * 100f, 0f..100f, { s -> setSpec { it.copy(smoothing = s / 100f) } }, state::seal, unit = " %")
    }
    if (node.shape != YtShapeKind.PATH) {
        TextButton(onClick = { v.toPath(node.id) }) { Text(stringResource(R.string.yt_vec_to_path)) }
        Text(stringResource(R.string.yt_vec_parametric_note), color = VDim, fontSize = 11.sp)
    } else {
        val path = node.path ?: return
        Text(stringResource(R.string.yt_vec_points_count, path.pointCount, path.subpaths.size), color = VDim, fontSize = 12.sp)
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            TextButton(onClick = { v.startEdit(node.id) }) { Text(stringResource(R.string.yt_vec_edit_points)) }
            TextButton(onClick = { v.startEdit(node.id, drawMore = true) }) { Text(stringResource(R.string.yt_vec_draw_more)) }
            TextButton(onClick = { state.editNode(node.id, "reverse") { it.copy(path = YtPathOps.reverse(path)) } }) { Text(stringResource(R.string.yt_vec_reverse)) }
            TextButton(onClick = { v.smooth(node.id) }) { Text(stringResource(R.string.yt_vec_smooth)) }
        }
        VLabel(stringResource(R.string.yt_vec_fill_rule))
        VChoice(listOf(YtFillRule.NON_ZERO to stringResource(R.string.yt_vec_rule_nonzero), YtFillRule.EVEN_ODD to stringResource(R.string.yt_vec_rule_evenodd)), path.fillRule) { r ->
            state.editNode(node.id, "fill rule") { it.copy(path = path.copy(fillRule = r)) }
        }
        var tol by remember(node.id) { mutableFloatStateOf(1f) }
        YtSliderRow(stringResource(R.string.yt_vec_simplify), tol, 0.2f..10f, { tol = it }, {}, 1, " dp")
        TextButton(onClick = { v.simplify(node.id, tol) }) { Text(stringResource(R.string.yt_vec_simplify_apply)) }
    }
    if (node.style.strokes.any { it.enabled && it.width > 0f }) {
        TextButton(onClick = { v.outlineStroke(node.id) }) { Text(stringResource(R.string.yt_vec_outline)) }
    }
}

@Composable
private fun newShapes(): List<Pair<YtShapeKind, String>> = listOf(
    YtShapeKind.ELLIPSE to stringResource(R.string.yt_vec_shape_circle),
    YtShapeKind.TRIANGLE to stringResource(R.string.yt_vec_shape_triangle),
    YtShapeKind.POLYGON to stringResource(R.string.yt_vec_shape_polygon),
    YtShapeKind.STAR to stringResource(R.string.yt_vec_shape_star),
    YtShapeKind.SQUIRCLE to stringResource(R.string.yt_vec_shape_squircle),
)

/** In the animation tab: add a motion path, record one, add a morph; edit those of the current animation. */
@Composable
fun YtAnimVectorSection(state: YtEditorState, node: YtNode, current: YtAnim?) {
    val v = state.vector
    VLabel(stringResource(R.string.yt_vec_anim_section))
    Row(Modifier.horizontalScroll(rememberScrollState())) {
        TextButton(onClick = { v.addMotion() }) { Text("+ " + stringResource(R.string.yt_vec_motion_name)) }
        TextButton(onClick = { v.startRecording() }) { Text("● " + stringResource(R.string.yt_vec_record)) }
        if (node.type == YtNodeType.SHAPE) TextButton(onClick = { v.addMorph() }) { Text("+ " + stringResource(R.string.yt_vec_morph_name)) }
    }
    val a = current ?: return
    if (a.tracks.any { it.prop == YtAnimProp.PATH }) {
        val m = a.motion ?: YtMotionPath()
        VLabel(stringResource(R.string.yt_vec_motion_name) + " · " + stringResource(R.string.yt_vec_points_count, m.path.pointCount, m.path.subpaths.size))
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            TextButton(onClick = { v.startMotion(a.id) }) { Text("✎ " + stringResource(R.string.yt_vec_edit_on_canvas)) }
            TextButton(onClick = { state.editAnim(a.id, "reverse") { it.copy(motion = m.copy(path = YtPathOps.reverse(m.path))) } }) { Text(stringResource(R.string.yt_vec_reverse)) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.yt_vec_orient), color = VText, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Switch(checked = m.orient, onCheckedChange = { on -> state.editAnim(a.id, "orient") { it.copy(motion = m.copy(orient = on)) } })
        }
        YtSliderRow(stringResource(if (m.orient) R.string.yt_vec_angle_extra else R.string.yt_vec_angle_fixed), m.angle, -180f..180f, { d -> state.editAnim(a.id, "angle", "angle") { it.copy(motion = m.copy(angle = d)) } }, state::seal, unit = "°")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.yt_anim_forever), color = VText, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Switch(checked = a.infinite, onCheckedChange = { on -> state.editAnim(a.id, "repeat") { it.copy(repeat = if (on) -1 else 0) } })
        }
        VChoice(
            listOf(YtAnimDirection.NORMAL to stringResource(R.string.yt_anim_dir_normal), YtAnimDirection.REVERSE to stringResource(R.string.yt_anim_dir_reverse), YtAnimDirection.ALTERNATE to stringResource(R.string.yt_anim_dir_alternate)),
            a.direction,
        ) { d -> state.editAnim(a.id, "direction") { it.copy(direction = d) } }
        YtMotionTiming(state, a)
        // the waypoints as numbers (dp from the start)
        VLabel(stringResource(R.string.yt_vec_waypoints))
        m.path.subpaths.firstOrNull()?.points?.forEachIndexed { i, p ->
            Text(stringResource(R.string.yt_vec_point_n, i + 1, 1), color = VDim, fontSize = 11.sp)
            YtNumberRow("X", p.x, 4f, { x -> state.editAnim(a.id, "waypoint", "wp:$i:x") { it.copy(motion = m.copy(path = YtPathOps.moveTo(m.path, YtPathGeom.Ref(0, i), x, p.y))) } })
            YtNumberRow("Y", p.y, 4f, { y -> state.editAnim(a.id, "waypoint", "wp:$i:y") { it.copy(motion = m.copy(path = YtPathOps.moveTo(m.path, YtPathGeom.Ref(0, i), p.x, y))) } })
        }
    }
    if (a.tracks.any { it.prop == YtAnimProp.MORPH } && node.type == YtNodeType.SHAPE) {
        val t = a.morph ?: YtMorphTarget()
        VLabel(stringResource(R.string.yt_vec_morph_target))
        VChoice(
            listOf(
                YtShapeKind.ELLIPSE to "◯", YtShapeKind.RECT to "▭", YtShapeKind.TRIANGLE to "△", YtShapeKind.POLYGON to "⬡",
                YtShapeKind.STAR to "★", YtShapeKind.SQUIRCLE to "▢",
            ) + if (t.shape == YtShapeKind.PATH) listOf(YtShapeKind.PATH to "✎") else emptyList(),
            t.shape,
        ) { k ->
            state.editAnim(a.id, "morph") {
                it.copy(morph = YtMorphTarget(k, YtShapeSpec(sides = if (k == YtShapeKind.STAR) 5 else 6, inner = 0.5f), if (k == YtShapeKind.ELLIPSE) YtCorners.all(500f) else YtCorners()))
            }
            state.playOnce(a.id)
        }
        if (t.shape == YtShapeKind.POLYGON || t.shape == YtShapeKind.STAR) {
            YtSliderRow(stringResource(if (t.shape == YtShapeKind.STAR) R.string.yt_vec_points else R.string.yt_vec_sides), t.spec.sides.toFloat(), 3f..16f, { n ->
                state.editAnim(a.id, "morph", "morphSides") { it.copy(morph = t.copy(spec = t.spec.copy(sides = n.roundToInt()))) }
            }, { state.seal(); state.playOnce(a.id) })
        }
        // another shape of the component as the target
        val kind = state.kindOf(node.id)
        val others = kind?.let { k -> state.doc?.components?.get(k)?.let { YtDocOps.all(it) } }.orEmpty().filter { it.type == YtNodeType.SHAPE && it.id != node.id }
        if (others.isNotEmpty()) {
            VLabel(stringResource(R.string.yt_vec_morph_from_node))
            VChoice(others.map { it.id to it.name.ifEmpty { it.id } }, null) { id -> v.morphFromNode(a.id, id); state.playOnce(a.id) }
        }
        if (v.approximated(a)) {
            Text(stringResource(R.string.yt_vec_approx), color = VWarn, fontSize = 12.sp)
        } else {
            Text(stringResource(R.string.yt_vec_exact), color = VDim, fontSize = 11.sp)
        }
    }
}
