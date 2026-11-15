package com.github.libretube.ui.yt.builder

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.libretube.R
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/*
 * YT: the animator's pro mode: a timeline of the selected node's animations (a group row per
 * animation with its property tracks under it, keyframes, a ruler with zoom and scroll, the
 * playhead with scrubbing and the transport) and a curve editor for one track (value and speed
 * curve, Bézier handles of a segment, numbers, copy, save as preset). Springs show their settle
 * time but have no keyframe times: physics is not a timeline.
 */

private val RulerColor = Color(0xFF2A2C33)
private val LaneColor = Color(0xFF1A1B20)
private val KeyColor = Color(0xFFE6E7EB)
private val SelColor = Color(0xFF8AB4FF)
private val HeadColor = Color(0xFFFF5A5F)
private val BarColor = Color(0x553E7BFF)
private val SpringColor = Color(0x5534C38F)

private sealed interface TlRow {
    val a: YtAnim
    val off: Int

    data class Anim(override val a: YtAnim, override val off: Int) : TlRow
    data class Track(override val a: YtAnim, override val off: Int, val ti: Int) : TlRow
}

/** The pro mode for [node]: transport, timeline, key tools and the curve editor. */
@Composable
internal fun YtAnimTimelinePane(state: YtEditorState, node: YtNode, kind: YtComponentKind, anims: List<YtAnim>, current: YtAnim) {
    val doc = state.doc ?: return
    val ids = anims.map { it.id }
    LaunchedEffect(ids) { state.showOnTimeline(anims) }
    DisposableEffect(Unit) {
        state.timelineOpen = true
        onDispose {
            state.timelineOpen = false
            state.stopPreview()
        }
    }
    var view by remember { mutableStateOf(YtAnimTimeline.View(pxPerMs = 0.35f)) }
    var multi by remember { mutableStateOf(false) }
    var curves by remember { mutableStateOf(false) }
    var collapsed by remember { mutableStateOf<Set<String>>(emptySet()) }
    val schedule = remember(doc.animations, ids) { YtAnimCombine.schedule(anims.filter { it.enabled }, doc.animations.groups).associate { it.first.id to it.second } }

    // transport
    Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { state.playing = false; state.playMs = 0.0 }) { Text("⏮") }
        TextButton(onClick = { state.stepFrame(-1) }) { Text("◀|") }
        TextButton(onClick = {
            if (state.animPreview == null) state.showOnTimeline(anims)
            if (!state.playing && state.playMs >= state.previewEndMs) state.playMs = 0.0
            state.playing = !state.playing
        }) { Text(if (state.playing) "⏸" else "▶") }
        TextButton(onClick = { state.stepFrame(1) }) { Text("|▶") }
        Text(
            "${state.playMs.roundToInt()} ms · F ${YtAnimTimeline.frameOf(state.playMs, state.fps)}",
            color = AnimText, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 6.dp),
        )
        AnimChoice(
            listOf(YtLoopMode.ONCE to stringResource(R.string.yt_anim_loop_once), YtLoopMode.LOOP to stringResource(R.string.yt_anim_loop_loop), YtLoopMode.PING_PONG to stringResource(R.string.yt_anim_loop_pingpong)),
            state.loop,
        ) { state.loop = it }
    }
    Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
        AnimChoice(listOf(0.25f, 0.5f, 1f, 2f).map { it to "${if (it < 1f) it.toString() else it.roundToInt().toString()}×" }, state.speed) { state.speed = it }
        Text(" fps", color = AnimDim, fontSize = 12.sp)
        AnimChoice(YtAnimTimeline.FPS.map { it to it.toString() }, state.fps) { state.fps = it }
        FilterChip(selected = state.snapKeys, onClick = { state.snapKeys = !state.snapKeys }, label = { Text(stringResource(R.string.yt_anim_snap), fontSize = 12.sp) })
        TextButton(onClick = { view = view.zoomed(1f / 1.5f, 0f) }) { Text("−") }
        TextButton(onClick = { view = view.zoomed(1.5f, 0f) }) { Text("+") }
    }

    // the rows: one per animation, its tracks under it
    val rows = buildList<TlRow> {
        for (a in anims) {
            val off = schedule[a.id] ?: 0
            add(TlRow.Anim(a, off))
            if (a.id !in collapsed) a.tracks.indices.forEach { add(TlRow.Track(a, off, it)) }
        }
    }
    val rowDp = 30.dp
    val rulerDp = 26.dp
    val density = LocalDensity.current
    val rowPx = with(density) { rowDp.toPx() }
    val rulerPx = with(density) { rulerDp.toPx() }
    val measurer = rememberTextMeasurer()
    val rowsNow by rememberUpdatedState(rows)
    val animsNow by rememberUpdatedState(anims)
    val viewNow by rememberUpdatedState(view)

    Row(Modifier.fillMaxWidth().height(rulerDp + rowDp * min(rows.size, 7).coerceAtLeast(1)).verticalScroll(rememberScrollState())) {
        // headers
        Column(Modifier.width(118.dp)) {
            Box(Modifier.height(rulerDp))
            for (r in rows) {
                Row(
                    Modifier.height(rowDp).fillMaxWidth().background(if (r.a.id == current.id) Color(0xFF23262E) else Color.Transparent).clickable {
                        if (r is TlRow.Anim) {
                            if (state.animId == r.a.id) collapsed = if (r.a.id in collapsed) collapsed - r.a.id else collapsed + r.a.id
                            state.animId = r.a.id
                            state.keySel = emptySet()
                        }
                    },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    when (r) {
                        is TlRow.Anim -> Text((if (r.a.id in collapsed) "▸ " else "▾ ") + animTitle(r.a), color = AnimText, fontSize = 12.sp, maxLines = 1, modifier = Modifier.padding(start = 4.dp))
                        is TlRow.Track -> {
                            val t = r.a.tracks.getOrNull(r.ti) ?: return@Row
                            Text(propLabel(t.prop), color = if (t.muted) AnimDim else AnimText, fontSize = 11.sp, maxLines = 1, modifier = Modifier.weight(1f).padding(start = 14.dp))
                            fun track(change: (YtTrack) -> YtTrack) = state.editAnim(r.a.id, "track") { an -> an.copy(tracks = an.tracks.mapIndexed { i, x -> if (i == r.ti) change(x) else x }) }
                            Text(if (t.muted) "M●" else "M", color = if (t.muted) AnimWarn else AnimDim, fontSize = 11.sp, modifier = Modifier.clickable { track { it.copy(muted = !it.muted) } }.padding(4.dp))
                            Text(if (t.solo) "S●" else "S", color = if (t.solo) SelColor else AnimDim, fontSize = 11.sp, modifier = Modifier.clickable { track { it.copy(solo = !it.solo) } }.padding(4.dp))
                        }
                    }
                }
            }
        }
        // lanes
        Canvas(
            Modifier
                .weight(1f)
                .height(rulerDp + rowDp * rows.size.coerceAtLeast(1))
                .pointerInput(state) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val v0 = viewNow
                        val rowsAt = rowsNow
                        val p = down.position
                        val slop = viewConfiguration.touchSlop
                        // which key is under the finger
                        var hitAnim: YtAnim? = null
                        var hitRef: YtKeyRef? = null
                        val rowIndex = ((p.y - rulerPx) / rowPx).toInt()
                        val row = if (p.y >= rulerPx) rowsAt.getOrNull(rowIndex) else null
                        if (row is TlRow.Track && row.a.kind == YtAnimKind.TIME) {
                            val t = row.a.tracks.getOrNull(row.ti)
                            if (t != null) {
                                var best = 18f * density.density
                                t.keys.forEachIndexed { ki, k ->
                                    val x = v0.x((row.off + row.a.delayMs + k.at * row.a.durationMs).toDouble())
                                    val d = abs(x - p.x)
                                    if (d < best) {
                                        best = d
                                        hitAnim = row.a
                                        hitRef = YtKeyRef(row.ti, ki)
                                    }
                                }
                            }
                        }
                        val onRuler = p.y < rulerPx
                        val anim0 = hitAnim
                        val ref0 = hitRef
                        var sel0 = state.keySel
                        if (anim0 != null && ref0 != null) {
                            if (state.animId != anim0.id) {
                                state.animId = anim0.id
                                sel0 = emptySet()
                            }
                            sel0 = when {
                                multi -> if (ref0 in sel0) sel0 - ref0 else sel0 + ref0
                                ref0 in sel0 -> sel0
                                else -> setOf(ref0)
                            }
                            state.keySel = sel0
                        } else if (onRuler) {
                            state.playing = false
                            if (state.animPreview == null) state.showOnTimeline(animsNow)
                            state.playMs = v0.ms(p.x).coerceIn(0.0, state.previewEndMs)
                        }
                        var moved = false
                        var pinch0 = 0f
                        var zoom0 = v0
                        while (true) {
                            val ev = awaitPointerEvent()
                            val pressed = ev.changes.filter { it.pressed }
                            if (pressed.isEmpty()) break
                            if (pressed.size >= 2) {
                                // two fingers: zoom around their middle
                                val a = pressed[0].position
                                val b = pressed[1].position
                                val dist = hypot(a.x - b.x, a.y - b.y)
                                if (pinch0 == 0f) {
                                    pinch0 = dist
                                    zoom0 = view
                                } else if (pinch0 > 0f) {
                                    view = zoom0.zoomed(dist / pinch0, (a.x + b.x) / 2f)
                                }
                                moved = true
                                ev.changes.forEach { it.consume() }
                                continue
                            }
                            val c = pressed.first()
                            val dx = c.position.x - p.x
                            if (!moved && abs(dx) < slop && abs(c.position.y - p.y) < slop) continue
                            moved = true
                            when {
                                anim0 != null && ref0 != null && anim0.kind == YtAnimKind.TIME -> {
                                    // drag the selection; computed from the state at the start, so snapping does not drift
                                    val deltaMs = dx / v0.scale.toDouble()
                                    val (next, sel) = YtAnimTimeline.move(anim0, sel0, ref0, deltaMs, state.fps, state.snapKeys)
                                    state.editAnim(anim0.id, "keys", "drag") { next }
                                    state.keySel = sel
                                }
                                onRuler -> state.playMs = v0.ms(c.position.x).coerceIn(0.0, state.previewEndMs)
                                else -> view = v0.copy(scrollPx = (v0.scrollPx - dx).coerceAtLeast(0f))
                            }
                            c.consume()
                        }
                        state.seal()
                        if (!moved && anim0 == null && !onRuler) state.keySel = emptySet()
                    }
                },
        ) {
            drawTimeline(rows, view, state, current, rowPx, rulerPx, measurer)
        }
    }

    // key tools
    Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
        FilterChip(selected = multi, onClick = { multi = !multi }, label = { Text(stringResource(R.string.yt_anim_multi), fontSize = 12.sp) })
        val a = current
        val sel = state.keySel
        val time = a.kind == YtAnimKind.TIME
        val localMs = (state.playMs - (schedule[a.id] ?: 0) - a.delayMs).coerceIn(0.0, a.durationMs.toDouble())
        TextButton(enabled = sel.isNotEmpty(), onClick = { state.keyClipboard = YtAnimTimeline.copy(a, sel) }) { Text(stringResource(R.string.yt_anim_copy)) }
        TextButton(enabled = time && state.keyClipboard.isNotEmpty(), onClick = {
            val (next, s) = YtAnimTimeline.paste(a, state.keyClipboard, localMs)
            state.editAnim(a.id, "paste") { next }
            state.keySel = s
        }) { Text(stringResource(R.string.yt_anim_paste)) }
        TextButton(enabled = time && sel.isNotEmpty(), onClick = {
            val (next, s) = YtAnimTimeline.duplicate(a, sel, state.fps)
            state.editAnim(a.id, "duplicate") { next }
            state.keySel = s
        }) { Text(stringResource(R.string.yt_builder_duplicate)) }
        TextButton(enabled = sel.isNotEmpty(), onClick = {
            state.editAnim(a.id, "delete keys") { YtAnimTimeline.delete(it, sel) }
            state.keySel = emptySet()
        }) { Text(stringResource(R.string.yt_builder_delete)) }
        TextButton(enabled = time && sel.size >= 3, onClick = {
            val (next, s) = YtAnimTimeline.distribute(a, sel)
            state.editAnim(a.id, "distribute") { next }
            state.keySel = s
        }) { Text(stringResource(R.string.yt_anim_distribute)) }
        TextButton(enabled = time && sel.isNotEmpty(), onClick = {
            val (next, s) = YtAnimTimeline.align(a, sel, localMs)
            state.editAnim(a.id, "align") { next }
            state.keySel = s
        }) { Text(stringResource(R.string.yt_anim_align)) }
        val track = sel.firstOrNull()?.track ?: 0
        TextButton(enabled = time && a.tracks.isNotEmpty(), onClick = {
            val (next, ref) = YtAnimTimeline.addKey(a, track, localMs)
            state.editAnim(a.id, "key") { next }
            state.keySel = setOfNotNull(ref)
        }) { Text("+ " + stringResource(R.string.yt_anim_key)) }
        FilterChip(selected = curves, onClick = { curves = !curves }, label = { Text(stringResource(R.string.yt_anim_curves), fontSize = 12.sp) })
    }
    if (current.kind == YtAnimKind.SPRING) Text(stringResource(R.string.yt_anim_spring_timeline), color = AnimDim, fontSize = 11.sp)
    // the selected key as numbers
    val ref = state.keySel.singleOrNull()
    val key = ref?.let { current.tracks.getOrNull(it.track)?.keys?.getOrNull(it.key) }
    if (ref != null && key != null) {
        val t = current.tracks[ref.track]
        fun setKey(change: (YtKeyframe) -> YtKeyframe) = state.editAnim(current.id, "key", "key:${ref.track}:${ref.key}") { an ->
            an.copy(tracks = an.tracks.mapIndexed { i, x -> if (i == ref.track) x.copy(keys = x.keys.mapIndexed { j, k -> if (j == ref.key) change(k) else k }) else x })
        }
        if (current.kind == YtAnimKind.TIME && current.durationMs > 0) {
            YtNumberRow(stringResource(R.string.yt_anim_key_time), key.at * current.durationMs, YtAnimTimeline.frameMs(state.fps).toFloat(), { ms -> setKey { it.copy(at = (ms / current.durationMs).coerceIn(0f, 1f)) } }, 0f, current.durationMs.toFloat())
        }
        if (t.prop.colour) YtKeyColor(state, key.color) { c -> setKey { it.copy(color = c) } }
        else YtNumberRow(propLabel(t.prop), key.value, step(t.prop), { v -> setKey { it.copy(value = v.coerceIn(t.prop.min, t.prop.max)) } }, t.prop.min, t.prop.max)
    }
    if (curves) YtAnimGraph(state, current, ref?.track ?: 0, ref?.key ?: 0)
}

private fun DrawScope.drawTimeline(
    rows: List<TlRow>,
    view: YtAnimTimeline.View,
    state: YtEditorState,
    current: YtAnim,
    rowPx: Float,
    rulerPx: Float,
    measurer: androidx.compose.ui.text.TextMeasurer,
) {
    val w = size.width
    drawRect(RulerColor, size = Size(w, rulerPx))
    val tick = view.tickMs()
    val firstMs = (view.ms(0f) / tick).toInt() * tick
    var ms = firstMs
    val style = TextStyle(color = AnimDim, fontSize = 9.sp)
    while (view.x(ms.toDouble()) < w) {
        val x = view.x(ms.toDouble())
        drawLine(AnimDim, Offset(x, rulerPx * 0.55f), Offset(x, rulerPx), 1f)
        drawLine(Color(0x22FFFFFF), Offset(x, rulerPx), Offset(x, size.height), 1f)
        drawText(measurer, if (ms >= 1000 && ms % 1000 == 0) "${ms / 1000}s" else "$ms", Offset(x + 2f, 1f), style)
        ms += tick
    }
    // the frame grid where frames are far enough apart to see
    val frame = YtAnimTimeline.frameMs(state.fps)
    if (frame * view.scale >= 6f) {
        var f = (view.ms(0f) / frame).toInt() * frame
        while (view.x(f) < w) {
            drawLine(Color(0x11FFFFFF), Offset(view.x(f), rulerPx), Offset(view.x(f), size.height), 1f)
            f += frame
        }
    }
    rows.forEachIndexed { i, r ->
        val top = rulerPx + i * rowPx
        drawRect(if (i % 2 == 0) LaneColor else Color(0xFF16171B), Offset(0f, top), Size(w, rowPx))
        val a = r.a
        val start = (r.off + a.delayMs).toDouble()
        val play = YtAnimEval.playMs(a).toDouble()
        when (r) {
            is TlRow.Anim -> {
                val plays = if (a.infinite) 3 else a.repeat.coerceAtLeast(0) + 1
                for (p in 0 until plays) {
                    val x0 = view.x(start + p * play)
                    val x1 = view.x(start + (p + 1) * play)
                    drawRect(
                        (if (a.kind == YtAnimKind.SPRING) SpringColor else BarColor).copy(alpha = if (p == 0) 0.55f else 0.25f),
                        Offset(x0, top + rowPx * 0.25f), Size(max(2f, x1 - x0), rowPx * 0.5f),
                    )
                }
                if (a.delayMs > 0) drawLine(AnimDim, Offset(view.x(r.off.toDouble()), top + rowPx / 2), Offset(view.x(start), top + rowPx / 2), 1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f)))
            }
            is TlRow.Track -> {
                val t = a.tracks.getOrNull(r.ti) ?: return@forEachIndexed
                val cy = top + rowPx / 2f
                val keysAt = if (a.kind == YtAnimKind.SPRING) listOf(0.0, play) else t.keys.map { it.at.toDouble() * a.durationMs }
                for (k in 1 until keysAt.size) drawLine(Color(0x55FFFFFF), Offset(view.x(start + keysAt[k - 1]), cy), Offset(view.x(start + keysAt[k]), cy), 2f)
                keysAt.forEachIndexed { ki, kms ->
                    val x = view.x(start + kms)
                    val selected = a.id == current.id && YtKeyRef(r.ti, ki) in state.keySel
                    val s = rowPx * (if (selected) 0.3f else 0.24f)
                    val diamond = Path().apply {
                        moveTo(x, cy - s)
                        lineTo(x + s, cy)
                        lineTo(x, cy + s)
                        lineTo(x - s, cy)
                        close()
                    }
                    drawPath(diamond, if (selected) SelColor else if (t.muted) AnimDim else KeyColor)
                    if (a.kind == YtAnimKind.SPRING) drawPath(diamond, SpringColor, style = Stroke(2f))
                }
            }
        }
    }
    val hx = view.x(state.playMs)
    drawLine(HeadColor, Offset(hx, 0f), Offset(hx, size.height), 2f)
}

/**
 * The curve editor of track [ti] of [a]: the value over the play (colour tracks: the eased
 * progress), the speed (dashed), the Bézier handles of the segment from key [ki], and the
 * numbers. Springs: their response, read only.
 */
@Composable
private fun YtAnimGraph(state: YtEditorState, a: YtAnim, ti: Int, ki: Int) {
    val t = a.tracks.getOrNull(ti) ?: return
    val seg = ki.coerceIn(0, (t.keys.size - 2).coerceAtLeast(0))
    val aNow by rememberUpdatedState(a)
    AnimLabel(stringResource(R.string.yt_anim_graph, propLabel(t.prop)))
    // the curve's samples: value 0..1 of the box
    val samples = remember(a, ti) { graphSamples(a, t) }
    val lo = samples.minOrNull() ?: 0f
    val hi = samples.maxOrNull() ?: 1f
    val span = (hi - lo).takeIf { it > 1e-6f } ?: 1f
    Canvas(
        Modifier.fillMaxWidth().height(170.dp).background(Color(0xFF15161A)).pointerInput(state, ti, seg) {
            awaitEachGesture {
                val down = awaitFirstDown()
                val cur = aNow
                val track = cur.tracks.getOrNull(ti) ?: return@awaitEachGesture
                if (cur.kind != YtAnimKind.TIME || track.keys.size < 2) return@awaitEachGesture
                val pad = 16f * density
                val gw = size.width - 2 * pad
                val gh = size.height - 2 * pad
                val k0 = track.keys[seg]
                val k1 = track.keys[seg + 1]
                val x0 = pad + k0.at * gw
                val x1 = pad + k1.at * gw
                fun yOf(v: Float) = pad + gh - (v - lo) / span * gh
                val v0 = if (t.prop.colour) progressOf(seg, track.keys.size) else k0.value
                val v1 = if (t.prop.colour) progressOf(seg + 1, track.keys.size) else k1.value
                val c = k0.curve
                val h1 = Offset(x0 + YtCurves.x1(c) * (x1 - x0), yOf(v0 + YtCurves.y1(c) * (v1 - v0)))
                val h2 = Offset(x0 + YtCurves.x2(c) * (x1 - x0), yOf(v0 + YtCurves.y2(c) * (v1 - v0)))
                val which = when {
                    (down.position - h1).getDistance() < 28f * density -> 1
                    (down.position - h2).getDistance() < 28f * density -> 2
                    else -> 0
                }
                if (which == 0) return@awaitEachGesture
                down.consume()
                while (true) {
                    val ev = awaitPointerEvent()
                    val ch = ev.changes.firstOrNull() ?: break
                    if (!ch.pressed) break
                    val fx = ((ch.position.x - x0) / (x1 - x0).coerceAtLeast(1f)).coerceIn(0f, 1f)
                    val dv = (v1 - v0).takeIf { abs(it) > 1e-6f } ?: 1f
                    val value = lo + (pad + gh - ch.position.y) / gh * span
                    val fy = ((value - v0) / dv).coerceIn(-5f, 5f)
                    val base = YtCurve.bezier(YtCurves.x1(c), YtCurves.y1(c), YtCurves.x2(c), YtCurves.y2(c))
                    val next = if (which == 1) base.copy(x1 = fx, y1 = fy) else base.copy(x2 = fx, y2 = fy)
                    state.editAnim(cur.id, "curve", "curve:$ti:$seg") { an -> an.copy(tracks = an.tracks.mapIndexed { i, x -> if (i == ti) x.copy(keys = x.keys.mapIndexed { j, k -> if (j == seg) k.copy(curve = next) else k }) else x }) }
                    ch.consume()
                }
                state.seal()
            }
        },
    ) {
        val pad = 16f * density
        val gw = size.width - 2 * pad
        val gh = size.height - 2 * pad
        fun yOf(v: Float) = pad + gh - (v - lo) / span * gh
        drawRect(Color(0x11FFFFFF), Offset(pad, pad), Size(gw, gh))
        // value curve
        val path = Path()
        samples.forEachIndexed { i, v ->
            val x = pad + gw * i / (samples.size - 1)
            if (i == 0) path.moveTo(x, yOf(v)) else path.lineTo(x, yOf(v))
        }
        drawPath(path, SelColor, style = Stroke(2.5f))
        // speed curve: the slope, scaled to the box
        val speeds = samples.zipWithNext { p, q -> q - p }
        val top = speeds.maxOfOrNull { abs(it) }?.takeIf { it > 1e-7f } ?: 1f
        val sp = Path()
        speeds.forEachIndexed { i, s ->
            val x = pad + gw * (i + 0.5f) / speeds.size
            val y = pad + gh / 2f - s / top * gh / 2f
            if (i == 0) sp.moveTo(x, y) else sp.lineTo(x, y)
        }
        drawPath(sp, AnimWarn.copy(alpha = 0.7f), style = Stroke(1.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 5f))))
        if (a.kind == YtAnimKind.TIME) {
            t.keys.forEachIndexed { i, k ->
                val v = if (t.prop.colour) progressOf(i, t.keys.size) else k.value
                drawCircle(KeyColor, 5f * density / 2f + 2f, Offset(pad + k.at * gw, yOf(v)))
            }
            if (t.keys.size >= 2) {
                val k0 = t.keys[seg]
                val k1 = t.keys[seg + 1]
                val x0 = pad + k0.at * gw
                val x1 = pad + k1.at * gw
                val v0 = if (t.prop.colour) progressOf(seg, t.keys.size) else k0.value
                val v1 = if (t.prop.colour) progressOf(seg + 1, t.keys.size) else k1.value
                val c = k0.curve
                val p0 = Offset(x0, yOf(v0))
                val p3 = Offset(x1, yOf(v1))
                val h1 = Offset(x0 + YtCurves.x1(c) * (x1 - x0), yOf(v0 + YtCurves.y1(c) * (v1 - v0)))
                val h2 = Offset(x0 + YtCurves.x2(c) * (x1 - x0), yOf(v0 + YtCurves.y2(c) * (v1 - v0)))
                drawLine(AnimDim, p0, h1, 1.5f)
                drawLine(AnimDim, p3, h2, 1.5f)
                drawCircle(SelColor, 7f * density / 2f + 3f, h1)
                drawCircle(SelColor, 7f * density / 2f + 3f, h2)
            }
        }
        val hx = pad + gw * (((state.playMs) / YtAnimEval.playMs(a).coerceAtLeast(1)).toFloat().coerceIn(0f, 1f))
        drawLine(HeadColor, Offset(hx, pad), Offset(hx, pad + gh), 1.5f)
    }
    Text(stringResource(R.string.yt_anim_graph_note), color = AnimDim, fontSize = 11.sp)
    if (a.kind == YtAnimKind.TIME && t.keys.size >= 2) {
        val k0 = t.keys[seg]
        fun setCurve(c: YtCurve) = state.editAnim(a.id, "curve", "curve:$ti:$seg") { an -> an.copy(tracks = an.tracks.mapIndexed { i, x -> if (i == ti) x.copy(keys = x.keys.mapIndexed { j, k -> if (j == seg) k.copy(curve = c) else k }) else x }) }
        Text(stringResource(R.string.yt_anim_segment, seg + 1, t.keys.size - 1), color = AnimText, fontSize = 12.sp)
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            TextButton(onClick = { state.curveClipboard = k0.curve }) { Text(stringResource(R.string.yt_anim_curve_copy)) }
            TextButton(enabled = state.curveClipboard != null, onClick = { state.curveClipboard?.let(::setCurve) }) { Text(stringResource(R.string.yt_anim_curve_paste)) }
            TextButton(enabled = state.curveClipboard != null, onClick = {
                val c = state.curveClipboard ?: return@TextButton
                state.editAnim(a.id, "curve") { an -> an.copy(tracks = an.tracks.mapIndexed { i, x -> if (i == ti) x.copy(keys = x.keys.map { it.copy(curve = c) }) else x }) }
            }) { Text(stringResource(R.string.yt_anim_curve_paste_all)) }
        }
        YtEasingPicker(state, k0.curve) { setCurve(it) }
    }
}

/** Where key [i] of [n] sits on a colour track's progress (0 first, 1 last). */
private fun progressOf(i: Int, n: Int): Float = if (n < 2) 0f else i.toFloat() / (n - 1)

/** 121 samples of [t] over one play of [a]: values (colours: eased progress), springs by their response. */
private fun graphSamples(a: YtAnim, t: YtTrack): List<Float> {
    val n = 120
    if (a.kind == YtAnimKind.SPRING) {
        val play = YtAnimEval.playMs(a).coerceAtLeast(1)
        val e = YtAnimEngine()
        val solo = a.copy(id = "graph", node = "g", tracks = listOf(t.copy(muted = false, solo = false)), delayMs = 0, repeat = 0)
        return (0..n).map { i ->
            e.preview(listOf(solo to 0), play.toDouble() * i / n)
            if (t.prop.colour) 0f else e.node("g").float(t.prop)
        }
    }
    if (t.prop.colour) {
        // progress 0 → 1 across the keys: each segment's eased share
        val keys = t.keys
        return (0..n).map { i ->
            val p = i.toFloat() / n
            if (keys.size < 2) return@map 0f
            val j = keys.indexOfLast { it.at <= p }.coerceIn(0, keys.size - 2)
            val a0 = keys[j]
            val a1 = keys[j + 1]
            val f = if (a1.at > a0.at) ((p - a0.at) / (a1.at - a0.at)).coerceIn(0f, 1f) else 1f
            (j + YtCurves.eval(a0.curve, f)) / (keys.size - 1)
        }
    }
    return (0..n).map { YtAnimEval.sample(t, it.toFloat() / n) }
}
