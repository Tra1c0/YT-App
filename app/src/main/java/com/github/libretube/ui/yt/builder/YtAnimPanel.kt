package com.github.libretube.ui.yt.builder

import android.os.Build
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.libretube.R
import com.github.libretube.ui.yt.YtMotionPrefs
import java.util.Locale
import kotlin.math.roundToInt

/*
 * YT: the animator's panel in the theme editor (tab "Animation" of a selected node), in three
 * levels that edit the same animations: simple (a preset with strength and duration, playing at
 * once: node → tab → preset), advanced (every parameter, tracks and keyframes as numbers,
 * states and rules) and pro (the timeline and the curve editor, YtAnimTimelineView).
 */

internal val AnimText = Color(0xFFE6E7EB)
internal val AnimDim = Color(0xFF9EA2AD)
internal val AnimWarn = Color(0xFFFFC46B)

@Composable
internal fun AnimLabel(text: String) = Text(text, color = AnimDim, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp, bottom = 2.dp))

@Composable
internal fun <T> AnimChoice(options: List<Pair<T, String>>, selected: T?, onSelect: (T) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for ((value, label) in options) FilterChip(selected = value == selected, onClick = { onSelect(value) }, label = { Text(label, fontSize = 12.sp) })
    }
}

// ---- labels (string arrays in enum order; YtAnimStringsTest checks their sizes) ----

@Composable
internal fun propLabel(p: YtAnimProp): String = stringArrayResource(R.array.yt_anim_props).getOrElse(p.ordinal) { p.name }

@Composable
internal fun presetLabel(p: YtPreset): String = stringArrayResource(R.array.yt_anim_presets).getOrElse(p.ordinal) { p.name }

@Composable
internal fun triggerLabel(t: YtTriggerKind): String = stringArrayResource(R.array.yt_anim_triggers).getOrElse(t.ordinal) { t.name }

@Composable
internal fun unsupportedLabel(u: YtAnimUnsupported): String = stringArrayResource(R.array.yt_anim_unsupported).getOrElse(u.ordinal) { u.name }

@Composable
internal fun curveLabel(c: YtCurveKind): String = stringArrayResource(R.array.yt_anim_curves).getOrElse(c.ordinal) { c.name }

@Composable
internal fun stateLabel(name: String): String {
    if (name == YtStateNames.ANY) return stringResource(R.string.yt_anim_state_any)
    val i = YtStateNames.builtIn.indexOf(name)
    return if (i >= 0) stringArrayResource(R.array.yt_anim_states).getOrElse(i) { name } else name
}

/** The preset's name while the user has not named the animation, else its name. */
@Composable
internal fun animTitle(a: YtAnim): String {
    val p = a.preset
    return if (p != null && (a.name.isBlank() || a.name == p.name.lowercase().replace('_', ' '))) presetLabel(p) else a.name.ifBlank { a.id }
}

/** Triggers offered in the simple mode (the advanced mode offers all). */
private val simpleTriggers = listOf(YtTriggerKind.APPEAR, YtTriggerKind.TAP, YtTriggerKind.DOUBLE_TAP, YtTriggerKind.LONG_PRESS, YtTriggerKind.TOUCH_DOWN, YtTriggerKind.SELECTED, YtTriggerKind.SCROLL)

/** The tab "Animation" of the selected [node]. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun YtAnimTab(state: YtEditorState, node: YtNode) {
    val doc = state.doc ?: return
    val kind = state.kindOf(node.id) ?: return
    val anims = YtAnimOps.of(doc, kind, node.id)
    var adding by remember(node.id) { mutableStateOf(false) }
    val current = state.anim(state.animId)?.takeIf { it.node == node.id && it.component == kind } ?: anims.firstOrNull()
    // the preview rests when the tab goes
    DisposableEffect(node.id) { onDispose { state.stopPreview() } }

    AnimChoice(
        listOf(YtAnimUi.SIMPLE to stringResource(R.string.yt_anim_ui_simple), YtAnimUi.ADVANCED to stringResource(R.string.yt_anim_ui_advanced), YtAnimUi.PRO to stringResource(R.string.yt_anim_ui_pro)),
        state.animUi,
    ) { state.animUi = it }
    if (!YtMotionPrefs.enabled) Text(stringResource(R.string.yt_anim_motion_off_note), color = AnimWarn, fontSize = 11.sp)

    AnimLabel(stringResource(R.string.yt_anim_of_node))
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        for (a in anims) {
            FilterChip(
                selected = a.id == current?.id && !adding,
                onClick = { state.animId = a.id; state.keySel = emptySet(); adding = false },
                label = { Text((if (!a.enabled) "⏸ " else "") + animTitle(a) + " · " + triggerLabel(a.trigger.kind), fontSize = 12.sp) },
            )
        }
        FilterChip(selected = adding || anims.isEmpty(), onClick = { adding = !adding }, label = { Text("+ " + stringResource(R.string.yt_anim_add), fontSize = 12.sp) })
    }
    if (adding || anims.isEmpty()) {
        AnimLabel(stringResource(R.string.yt_anim_pick_preset))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (p in YtPreset.entries) {
                FilterChip(selected = false, onClick = { state.addPreset(p); adding = false }, label = { Text(presetLabel(p), fontSize = 12.sp) })
            }
        }
        return
    }
    val a = current ?: return
    androidx.compose.runtime.SideEffect { if (state.animId != a.id) state.animId = a.id }

    when (state.animUi) {
        YtAnimUi.SIMPLE -> SimpleAnim(state, a)
        YtAnimUi.ADVANCED -> {
            SimpleAnim(state, a)
            AdvancedAnim(state, a, node)
            YtAnimStatesSection(state, kind)
        }
        YtAnimUi.PRO -> YtAnimTimelinePane(state, node, kind, anims, a)
    }
    YtAnimRuleNote(state, kind, node.id)
}

/** Preset, trigger, strength, duration, preview, delete. */
@Composable
private fun SimpleAnim(state: YtEditorState, a: YtAnim) {
    val accent = state.doc?.let { YtTokensResolver(it, state.night).argb(YtRole.ACCENT) } ?: 0
    AnimLabel(stringResource(R.string.yt_anim_trigger))
    AnimChoice((simpleTriggers + a.trigger.kind).distinct().map { it to triggerLabel(it) }, a.trigger.kind) { t ->
        state.editAnim(a.id, "trigger") { it.copy(trigger = it.trigger.copy(kind = t)) }
    }
    if (a.preset != null) {
        YtSliderRow(stringResource(R.string.yt_anim_strength), a.strength * 100f, YtAnimPresets.MIN_STRENGTH * 100f..YtAnimPresets.MAX_STRENGTH * 100f, { v ->
            state.editAnim(a.id, "strength", "strength") { YtAnimPresets.retune(it, v / 100f, it.durationMs, accent) }
        }, { state.seal(); state.playOnce(a.id) }, unit = " %")
    }
    if (a.kind == YtAnimKind.TIME) {
        YtSliderRow(stringResource(R.string.yt_anim_duration), a.durationMs.toFloat(), 50f..4000f, { v ->
            state.editAnim(a.id, "duration", "duration") { it.copy(durationMs = v.roundToInt()) }
        }, { state.seal(); state.playOnce(a.id) }, unit = " ms")
    } else {
        Text(stringResource(R.string.yt_anim_spring_length, YtAnimEval.playMs(a)), color = AnimDim, fontSize = 12.sp)
    }
    Row(Modifier.horizontalScroll(rememberScrollState())) {
        TextButton(onClick = { state.playOnce(a.id) }) { Text("▶ " + stringResource(R.string.yt_anim_preview)) }
        TextButton(onClick = { state.animTest = !state.animTest }) { Text((if (state.animTest) "● " else "") + stringResource(R.string.yt_anim_test)) }
        TextButton(onClick = { state.editAnim(a.id, "enabled") { it.copy(enabled = !it.enabled) } }) {
            Text(stringResource(if (a.enabled) R.string.yt_anim_disable else R.string.yt_anim_enable))
        }
        TextButton(onClick = { state.removeAnim(a.id) }) { Text(stringResource(R.string.yt_builder_delete)) }
    }
}

/** Every parameter: kind, delay, repeat, direction, easing, spring, group, trigger details, tracks and keys. */
@Composable
private fun AdvancedAnim(state: YtEditorState, a: YtAnim, node: YtNode) {
    val doc = state.doc ?: return
    fun edit(label: String, coalesce: String? = null, change: (YtAnim) -> YtAnim) = state.editAnim(a.id, label, coalesce, change)

    OutlinedTextField(value = a.name, onValueChange = { v -> edit("name", "name") { it.copy(name = v.take(60)) } }, singleLine = true, label = { Text(stringResource(R.string.yt_builder_name)) }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))

    AnimLabel(stringResource(R.string.yt_anim_trigger_all))
    AnimChoice(YtTriggerKind.entries.map { it to triggerLabel(it) }, a.trigger.kind) { t -> edit("trigger") { it.copy(trigger = it.trigger.copy(kind = t)) } }
    if (a.trigger.kind == YtTriggerKind.STATE) {
        val states = listOf(YtStateNames.ANY) + YtStateNames.builtIn + doc.animations.customStates
        AnimLabel(stringResource(R.string.yt_anim_state_from))
        AnimChoice(states.map { it to stateLabel(it) }, a.trigger.from) { s -> edit("trigger") { it.copy(trigger = it.trigger.copy(from = s)) } }
        AnimLabel(stringResource(R.string.yt_anim_state_to))
        AnimChoice(states.map { it to stateLabel(it) }, a.trigger.to) { s -> edit("trigger") { it.copy(trigger = it.trigger.copy(to = s)) } }
    }
    if (a.trigger.kind == YtTriggerKind.SCROLL) {
        YtSliderRow(stringResource(R.string.yt_anim_scroll_range), a.trigger.scrollRange, 50f..3000f, { v -> edit("scroll", "scroll") { it.copy(trigger = it.trigger.copy(scrollRange = v)) } }, state::seal, unit = " dp")
    }
    Text(stringResource(triggerNote(a.trigger.kind, a.component)), color = AnimDim, fontSize = 11.sp)

    AnimLabel(stringResource(R.string.yt_anim_kind))
    AnimChoice(listOf(YtAnimKind.TIME to stringResource(R.string.yt_anim_kind_time), YtAnimKind.SPRING to stringResource(R.string.yt_anim_kind_spring)), a.kind) { k -> edit("kind") { it.copy(kind = k) } }
    Text(stringResource(if (a.kind == YtAnimKind.SPRING) R.string.yt_anim_kind_spring_note else R.string.yt_anim_kind_time_note), color = AnimDim, fontSize = 11.sp)

    YtSliderRow(stringResource(R.string.yt_anim_delay), a.delayMs.toFloat(), 0f..3000f, { v -> edit("delay", "delay") { it.copy(delayMs = v.roundToInt()) } }, state::seal, unit = " ms")
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.yt_anim_forever), color = AnimText, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Switch(checked = a.infinite, onCheckedChange = { on -> edit("repeat") { it.copy(repeat = if (on) -1 else 0) } })
    }
    if (!a.infinite) YtNumberRow(stringResource(R.string.yt_anim_repeat), a.repeat.toFloat(), 1f, { v -> edit("repeat", "repeat") { it.copy(repeat = v.roundToInt().coerceIn(0, YtAnimValidate.MAX_REPEAT)) } }, 0f, YtAnimValidate.MAX_REPEAT.toFloat())
    AnimLabel(stringResource(R.string.yt_anim_direction))
    AnimChoice(
        listOf(YtAnimDirection.NORMAL to stringResource(R.string.yt_anim_dir_normal), YtAnimDirection.REVERSE to stringResource(R.string.yt_anim_dir_reverse), YtAnimDirection.ALTERNATE to stringResource(R.string.yt_anim_dir_alternate)),
        a.direction,
    ) { d -> edit("direction") { it.copy(direction = d) } }

    if (a.kind == YtAnimKind.SPRING) {
        AnimLabel(stringResource(R.string.yt_anim_spring))
        YtSliderRow(stringResource(R.string.yt_builder_stiffness), a.spring.stiffness, 10f..3000f, { v -> edit("spring", "stiffness") { it.copy(spring = it.spring.copy(stiffness = v)) } }, state::seal)
        YtSliderRow(stringResource(R.string.yt_builder_damping), a.spring.damping, 0.05f..2f, { v -> edit("spring", "damping") { it.copy(spring = it.spring.copy(damping = v)) } }, state::seal, 2)
        Text(stringResource(R.string.yt_anim_spring_feel, stringResource(springFeel(a.spring.damping)), YtAnimEval.playMs(a)), color = AnimDim, fontSize = 11.sp)
    } else {
        AnimLabel(stringResource(R.string.yt_anim_easing))
        YtEasingPicker(state, firstCurve(a)) { c -> edit("easing") { YtAnimTimeline.setCurve(it, c) } }
    }

    AnimLabel(stringResource(R.string.yt_anim_group))
    val groups = doc.animations.groups
    AnimChoice(listOf<String?>(null).map { it to stringResource(R.string.yt_anim_group_none) } + groups.map { it.id to it.name.ifBlank { it.id } }, a.group) { g -> edit("group") { it.copy(group = g) } }
    TextButton(onClick = {
        state.run("group") { d ->
            val id = YtAnimOps.freshId(d.animations, "g")
            val g = YtAnimGroup(id, a.name.ifBlank { id }, YtGroupMode.PARALLEL)
            YtAnimOps.update(d.copy(animations = d.animations.copy(groups = d.animations.groups + g)), a.id) { it.copy(group = id) }
        }
    }) { Text("+ " + stringResource(R.string.yt_anim_group_new)) }
    groups.firstOrNull { it.id == a.group }?.let { g ->
        fun group(change: (YtAnimGroup) -> YtAnimGroup) = state.editAnimations("group", "group:${g.id}") { an -> an.copy(groups = an.groups.map { if (it.id == g.id) change(it) else it }) }
        AnimChoice(
            listOf(YtGroupMode.PARALLEL to stringResource(R.string.yt_anim_group_parallel), YtGroupMode.SEQUENCE to stringResource(R.string.yt_anim_group_sequence), YtGroupMode.STAGGER to stringResource(R.string.yt_anim_group_stagger)),
            g.mode,
        ) { m -> group { it.copy(mode = m) } }
        if (g.mode == YtGroupMode.STAGGER) YtSliderRow(stringResource(R.string.yt_anim_stagger), g.staggerMs.toFloat(), 0f..500f, { v -> group { it.copy(staggerMs = v.roundToInt()) } }, state::seal, unit = " ms")
        Text(stringResource(R.string.yt_anim_group_note, doc.animations.items.count { it.group == g.id }), color = AnimDim, fontSize = 11.sp)
    }

    AnimLabel(stringResource(R.string.yt_anim_tracks))
    a.tracks.forEachIndexed { ti, t ->
        YtTrackEditor(state, a, ti, t, node)
    }
    var addTrack by remember(a.id) { mutableStateOf(false) }
    TextButton(onClick = { addTrack = !addTrack }) { Text("+ " + stringResource(R.string.yt_anim_track_add)) }
    if (addTrack) YtPropPicker(node, a.tracks.map { it.prop }.toSet()) { p ->
        addTrack = false
        edit("track") { it.copy(tracks = it.tracks + newTrack(p, node, state)) }
    }
}

private fun springFeel(damping: Float): Int = when {
    damping < 0.4f -> R.string.yt_anim_feel_bouncy
    damping < 0.9f -> R.string.yt_anim_feel_soft
    damping <= 1.1f -> R.string.yt_anim_feel_critical
    else -> R.string.yt_anim_feel_slow
}

/** The note under a trigger: where YT sends it from. */
private fun triggerNote(t: YtTriggerKind, kind: YtComponentKind): Int = when (t) {
    YtTriggerKind.APPEAR, YtTriggerKind.DISAPPEAR -> if (kind == YtComponentKind.CARD) R.string.yt_anim_note_visibility_card else R.string.yt_anim_note_visibility
    YtTriggerKind.TAP, YtTriggerKind.DOUBLE_TAP, YtTriggerKind.LONG_PRESS, YtTriggerKind.TOUCH_DOWN, YtTriggerKind.TOUCH_UP -> R.string.yt_anim_note_touch
    YtTriggerKind.SCROLL, YtTriggerKind.SCROLL_UP, YtTriggerKind.SCROLL_DOWN -> R.string.yt_anim_note_scroll
    YtTriggerKind.SELECTED, YtTriggerKind.DESELECTED -> R.string.yt_anim_note_selection
    YtTriggerKind.PLAYING, YtTriggerKind.PAUSED -> R.string.yt_anim_note_player
    YtTriggerKind.LOADING_START, YtTriggerKind.LOADING_END -> R.string.yt_anim_note_loading
    YtTriggerKind.STATE -> R.string.yt_anim_note_state
    YtTriggerKind.MANUAL -> R.string.yt_anim_note_manual
}.let { if ((kind == YtComponentKind.MINI || kind == YtComponentKind.NAV_BAR) && t != YtTriggerKind.MANUAL) R.string.yt_anim_note_mini else it }

internal fun firstCurve(a: YtAnim): YtCurve = a.tracks.firstOrNull()?.keys?.firstOrNull()?.curve ?: YtCurve.LINEAR

/** A new track for [p] on [node]: from rest to rest (colours: own to accent). */
internal fun newTrack(p: YtAnimProp, node: YtNode, state: YtEditorState): YtTrack {
    if (p.colour) {
        val accent = state.doc?.let { YtTokensResolver(it, state.night).argb(YtRole.ACCENT) } ?: 0xFF3EA6FF.toInt()
        return YtTrack(p, listOf(YtKeyframe(0f, color = null, curve = YtCurve.EASE_IN_OUT), YtKeyframe(1f, color = accent)))
    }
    val to = when (p) {
        YtAnimProp.X, YtAnimProp.Y -> 12f
        YtAnimProp.WIDTH, YtAnimProp.HEIGHT -> 16f
        YtAnimProp.SCALE_X, YtAnimProp.SCALE_Y -> 1.1f
        YtAnimProp.ROTATION -> 15f
        YtAnimProp.PIVOT_X, YtAnimProp.PIVOT_Y -> 0.5f
        YtAnimProp.OPACITY -> 0.5f
        YtAnimProp.SHADOW_ALPHA -> 2f
        YtAnimProp.SHIMMER -> 1f
        YtAnimProp.GRADIENT_SHIFT -> 0.5f
        YtAnimProp.GRADIENT_ANGLE -> 90f
        else -> 8f
    }
    return YtTrack(p, listOf(YtKeyframe(0f, p.rest, curve = YtCurve.EASE_IN_OUT), YtKeyframe(1f, to)))
}

/** The properties, each marked when it has no effect on [node]. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun YtPropPicker(node: YtNode, taken: Set<YtAnimProp>, onPick: (YtAnimProp) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (p in YtAnimProp.entries) {
            if (p in taken) continue
            val why = YtAnimSupport.check(p, node, Build.VERSION.SDK_INT)
            FilterChip(
                selected = false,
                enabled = why == null,
                onClick = { onPick(p) },
                label = { Text(propLabel(p) + if (why != null) " ✕" else "", fontSize = 12.sp) },
            )
        }
    }
    val marked = YtAnimProp.entries.filter { it !in taken }.mapNotNull { p -> YtAnimSupport.check(p, node, Build.VERSION.SDK_INT)?.let { p to it } }
    if (marked.isNotEmpty()) {
        val props = stringArrayResource(R.array.yt_anim_props)
        val whys = stringArrayResource(R.array.yt_anim_unsupported)
        Text(
            stringResource(R.string.yt_anim_unsupported_title) + "\n" + marked.joinToString("\n") { (p, why) -> "✕ " + props.getOrElse(p.ordinal) { p.name } + ": " + whys.getOrElse(why.ordinal) { why.name } },
            color = AnimDim, fontSize = 10.sp,
        )
    }
}

/** One track: property, mute/solo, delete, spring velocity, its keys as numbers. */
@Composable
private fun YtTrackEditor(state: YtEditorState, a: YtAnim, ti: Int, t: YtTrack, node: YtNode) {
    fun track(label: String, coalesce: String? = null, change: (YtTrack) -> YtTrack) =
        state.editAnim(a.id, label, coalesce?.let { "$it:$ti" }) { an -> an.copy(tracks = an.tracks.mapIndexed { i, x -> if (i == ti) change(x) else x }) }
    val why = YtAnimSupport.check(t.prop, node, Build.VERSION.SDK_INT)
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF1D1F24)).padding(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(propLabel(t.prop), color = AnimText, fontSize = 13.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = { track("mute") { it.copy(muted = !it.muted) } }) { Text(if (t.muted) "M●" else "M") }
            TextButton(onClick = { track("solo") { it.copy(solo = !it.solo) } }) { Text(if (t.solo) "S●" else "S") }
            TextButton(onClick = { state.editAnim(a.id, "track") { an -> an.copy(tracks = an.tracks.filterIndexed { i, _ -> i != ti }) } }) { Text("✕") }
        }
        if (why != null) Text(stringResource(R.string.yt_anim_track_no_effect, unsupportedLabel(why)), color = AnimWarn, fontSize = 11.sp)
        if (a.kind == YtAnimKind.SPRING && !t.prop.colour) {
            YtNumberRow(stringResource(R.string.yt_anim_velocity), t.velocity, 0.5f, { v -> track("velocity", "velocity") { it.copy(velocity = v.coerceIn(-YtAnimValidate.MAX_VELOCITY, YtAnimValidate.MAX_VELOCITY)) } }, -YtAnimValidate.MAX_VELOCITY, YtAnimValidate.MAX_VELOCITY)
            Text(stringResource(R.string.yt_anim_spring_keys), color = AnimDim, fontSize = 11.sp)
        }
        t.keys.forEachIndexed { ki, k ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("◆ " + (k.at * a.durationMs).roundToInt() + " ms", color = AnimDim, fontSize = 12.sp, modifier = Modifier.width(76.dp))
                if (t.prop.colour) {
                    YtKeyColor(state, k.color) { c -> track("key", "key$ki") { tr -> tr.copy(keys = tr.keys.mapIndexed { i, x -> if (i == ki) x.copy(color = c) else x }) } }
                } else {
                    Box(Modifier.weight(1f)) {
                        YtNumberRow("", k.value, step(t.prop), { v -> track("key", "key$ki") { tr -> tr.copy(keys = tr.keys.mapIndexed { i, x -> if (i == ki) x.copy(value = v.coerceIn(t.prop.min, t.prop.max)) else x }) } }, t.prop.min, t.prop.max)
                    }
                }
            }
        }
    }
}

internal fun step(p: YtAnimProp): Float = when (p) {
    YtAnimProp.SCALE_X, YtAnimProp.SCALE_Y, YtAnimProp.OPACITY, YtAnimProp.PIVOT_X, YtAnimProp.PIVOT_Y,
    YtAnimProp.SHADOW_ALPHA, YtAnimProp.GRADIENT_SHIFT, YtAnimProp.SHIMMER -> 0.05f
    YtAnimProp.ROTATION, YtAnimProp.GRADIENT_ANGLE -> 5f
    else -> 1f
}

/** A keyframe colour: the node's own (null) or a colour. */
@Composable
internal fun YtKeyColor(state: YtEditorState, color: Int?, onColor: (Int?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(26.dp).clip(RoundedCornerShape(6.dp)).background(Color(color ?: 0x00000000)).border(1.dp, Color(0x60FFFFFF), RoundedCornerShape(6.dp)).clickable { open = !open })
            Text(if (color == null) stringResource(R.string.yt_anim_own_colour) else YtColor.hex(color), color = AnimText, fontSize = 12.sp, modifier = Modifier.padding(start = 8.dp).clickable { open = !open })
            if (color != null) TextButton(onClick = { onColor(null) }) { Text(stringResource(R.string.yt_anim_own_colour)) }
        }
        if (open) YtColorPicker(state, color ?: 0xFF888888.toInt(), null, { onColor(it) }, null)
    }
}

/** Easing: the named curves, the saved presets, Bézier numbers, save as preset. */
@Composable
internal fun YtEasingPicker(state: YtEditorState, curve: YtCurve, onCurve: (YtCurve) -> Unit) {
    val doc = state.doc ?: return
    val named = listOf(YtCurve.LINEAR, YtCurve.EASE_IN, YtCurve.EASE_OUT, YtCurve.EASE_IN_OUT, YtCurve.STRONG_OUT.copy(name = "strong"), YtCurve.HOLD)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (c in named) {
            val label = if (c.kind == YtCurveKind.BEZIER) stringResource(R.string.yt_anim_curve_strong) else curveLabel(c.kind)
            FilterChip(selected = curve == c || (c.kind != YtCurveKind.BEZIER && curve.kind == c.kind), onClick = { onCurve(c) }, label = { Text(label, fontSize = 12.sp) })
        }
        for (e in doc.animations.easings) FilterChip(selected = curve.name == e.name, onClick = { onCurve(e.curve()) }, label = { Text("★ " + e.name, fontSize = 12.sp) })
    }
    val x1 = YtCurves.x1(curve)
    val y1 = YtCurves.y1(curve)
    val x2 = YtCurves.x2(curve)
    val y2 = YtCurves.y2(curve)
    fun set(a: Float = x1, b: Float = y1, c: Float = x2, d: Float = y2) = onCurve(YtCurve.bezier(a.coerceIn(0f, 1f), b.coerceIn(-5f, 5f), c.coerceIn(0f, 1f), d.coerceIn(-5f, 5f)))
    YtNumberRow("x1", x1, 0.05f, { set(a = it) }, 0f, 1f)
    YtNumberRow("y1", y1, 0.05f, { set(b = it) }, -5f, 5f)
    YtNumberRow("x2", x2, 0.05f, { set(c = it) }, 0f, 1f)
    YtNumberRow("y2", y2, 0.05f, { set(d = it) }, -5f, 5f)
    var name by remember { mutableStateOf("") }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(value = name, onValueChange = { name = it.take(40) }, singleLine = true, label = { Text(stringResource(R.string.yt_anim_easing_name)) }, modifier = Modifier.weight(1f))
        TextButton(enabled = name.isNotBlank(), onClick = {
            val n = name.trim()
            state.editAnimations("easing") { an -> an.copy(easings = an.easings.filter { it.name != n } + YtEasingPreset(n, x1, y1, x2, y2)) }
            name = ""
        }) { Text(stringResource(R.string.yt_anim_easing_save)) }
    }
}

/** The component's states and rules, and the test of a state. */
@Composable
internal fun YtAnimStatesSection(state: YtEditorState, kind: YtComponentKind) {
    val doc = state.doc ?: return
    val an = doc.animations
    AnimLabel(stringResource(R.string.yt_anim_states))
    Text(stringResource(R.string.yt_anim_states_note), color = AnimDim, fontSize = 11.sp)
    val all = YtStateNames.builtIn + an.customStates
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (s in all.drop(1)) {
            FilterChip(selected = false, onClick = { state.testState(kind, s) }, label = { Text(stateLabel(s), fontSize = 12.sp) })
        }
    }
    Text(stringResource(R.string.yt_anim_states_test), color = AnimDim, fontSize = 11.sp)
    var custom by remember { mutableStateOf("") }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(value = custom, onValueChange = { custom = it.take(24) }, singleLine = true, label = { Text(stringResource(R.string.yt_anim_state_custom)) }, modifier = Modifier.weight(1f))
        TextButton(enabled = YtStateNames.validCustom(custom.trim()) && custom.trim() !in an.customStates && an.customStates.size < YtAnimValidate.MAX_CUSTOM, onClick = {
            val n = custom.trim()
            state.editAnimations("state") { it.copy(customStates = it.customStates + n) }
            custom = ""
        }) { Text("+") }
    }
    for (s in an.customStates) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(s, color = AnimText, fontSize = 13.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = {
                state.editAnimations("state") { a ->
                    a.copy(
                        customStates = a.customStates - s,
                        rules = a.rules.filter { it.state != s },
                        items = a.items.map { x -> if (x.trigger.from == s || x.trigger.to == s) x.copy(trigger = x.trigger.copy(from = if (x.trigger.from == s) YtStateNames.ANY else x.trigger.from, to = if (x.trigger.to == s) YtStateNames.ANY else x.trigger.to)) else x },
                    )
                }
            }) { Text("✕") }
        }
    }
    AnimLabel(stringResource(R.string.yt_anim_rules))
    val rules = an.rules.withIndex().filter { it.value.component == kind }
    for ((i, r) in rules) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(triggerLabel(r.on) + " → " + stringArrayResource(R.array.yt_anim_actions)[r.action.ordinal] + " " + stateLabel(r.state), color = AnimText, fontSize = 13.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = { state.editAnimations("rule") { a -> a.copy(rules = a.rules.filterIndexed { j, _ -> j != i }) } }) { Text("✕") }
        }
    }
    var on by remember { mutableStateOf(YtTriggerKind.DOUBLE_TAP) }
    var action by remember { mutableStateOf(YtStateAction.TOGGLE) }
    var target by remember { mutableStateOf(YtStateNames.EXPANDED) }
    AnimChoice(YtTriggerKind.entries.filter { it != YtTriggerKind.STATE && it != YtTriggerKind.SCROLL }.map { it to triggerLabel(it) }, on) { on = it }
    AnimChoice(YtStateAction.entries.map { it to stringArrayResource(R.array.yt_anim_actions)[it.ordinal] }, action) { action = it }
    AnimChoice(all.drop(1).map { it to stateLabel(it) }, target) { target = it }
    TextButton(enabled = an.rules.size < YtAnimValidate.MAX_RULES, onClick = {
        state.editAnimations("rule") { it.copy(rules = it.rules + YtStateRule(kind, on, target, action)) }
    }) { Text("+ " + stringResource(R.string.yt_anim_rule_add)) }
}

/** The rule for several animations on one property, and this node's conflicts. */
@Composable
internal fun YtAnimRuleNote(state: YtEditorState, kind: YtComponentKind, node: String) {
    val doc = state.doc ?: return
    Spacer(Modifier.height(10.dp))
    Text(stringResource(R.string.yt_anim_rule_text), color = AnimDim, fontSize = 11.sp)
    val conflicts = remember(doc.animations) { YtAnimCombine.conflicts(doc.animations) }.filter { it.component == kind && it.node == node }
    val triggers = stringArrayResource(R.array.yt_anim_triggers)
    for (c in conflicts) {
        val names = c.anims.mapNotNull { id -> doc.animations.items.firstOrNull { it.id == id } }
        Text(
            stringResource(R.string.yt_anim_conflict, propLabel(c.prop), names.joinToString(", ") { it.name.ifBlank { it.id } + " (" + triggers.getOrElse(it.trigger.kind.ordinal) { "" } + ")" }),
            color = AnimWarn, fontSize = 11.sp,
        )
    }
}

/** The theme's animations when nothing is selected: the list (tap selects the node), presets of easing. */
@Composable
fun YtAnimThemeSection(state: YtEditorState) {
    val doc = state.doc ?: return
    val an = doc.animations
    AnimLabel(stringResource(R.string.yt_anim_all, an.items.size))
    if (an.items.isEmpty()) Text(stringResource(R.string.yt_anim_none_yet), color = AnimDim, fontSize = 12.sp)
    for (a in an.items) {
        Text(
            (if (!a.enabled) "⏸ " else "") + animTitle(a) + " · " + (state.node(a.node)?.name?.ifEmpty { null } ?: a.node) + " · " + triggerLabel(a.trigger.kind),
            color = AnimText, fontSize = 13.sp,
            modifier = Modifier.fillMaxWidth().clickable {
                state.selection = setOf(a.node)
                state.animId = a.id
                state.openAnimTab = true
            }.padding(vertical = 5.dp),
        )
    }
    if (an.easings.isNotEmpty()) {
        AnimLabel(stringResource(R.string.yt_anim_easings))
        for (e in an.easings) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("★ ${e.name}  (${fmt2(e.x1)}, ${fmt2(e.y1)}, ${fmt2(e.x2)}, ${fmt2(e.y2)})", color = AnimText, fontSize = 12.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = { state.editAnimations("easing") { x -> x.copy(easings = x.easings.filter { it.name != e.name }) } }) { Text("✕") }
            }
        }
    }
    Text(stringResource(R.string.yt_anim_rule_text), color = AnimDim, fontSize = 11.sp)
}

internal fun fmt2(v: Float) = "%.2f".format(Locale.ROOT, v)

/** Test mode over the preview: touches go to the edited instances' animations (not to the tools). */
@Composable
fun YtAnimTestOverlay(state: YtEditorState, modifier: Modifier = Modifier) {
    val sink = remember(state) {
        object : YtAnimTouchSink {
            override fun touch(down: Boolean) {
                state.stopPreview()
                for (i in state.animInstances) if (state.target.kind == null || i.kind == state.target.kind) i.touch(down)
            }

            override fun event(t: YtTriggerKind) = state.testEvent(state.target.kind, t)
        }
    }
    Box(modifier.ytAnimTouch(sink)) {
        Text(stringResource(R.string.yt_anim_test_on), color = Color.White, fontSize = 11.sp, modifier = Modifier.align(Alignment.TopCenter).padding(6.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xCC2B6DE0)).padding(horizontal = 10.dp, vertical = 4.dp))
    }
}

/** The test mode's bar: the triggers YT sends by itself (appear, scroll, player, loading), a scroll slider, the end. */
@Composable
fun YtAnimTestBar(state: YtEditorState) {
    var scroll by remember { mutableStateOf(0f) }
    Column(Modifier.fillMaxWidth().background(Color(0xFF1D1F24)).padding(horizontal = 8.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
            for (t in listOf(YtTriggerKind.APPEAR, YtTriggerKind.DISAPPEAR, YtTriggerKind.SCROLL_DOWN, YtTriggerKind.SCROLL_UP, YtTriggerKind.PLAYING, YtTriggerKind.PAUSED, YtTriggerKind.LOADING_START, YtTriggerKind.LOADING_END, YtTriggerKind.MANUAL)) {
                TextButton(onClick = { state.testEvent(state.target.kind, t) }) { Text(triggerLabel(t), fontSize = 12.sp) }
            }
            TextButton(onClick = { for (i in state.animInstances) i.rest() }) { Text(stringResource(R.string.yt_anim_test_reset), fontSize = 12.sp) }
            TextButton(onClick = { state.animTest = false }) { Text(stringResource(R.string.yt_builder_close), fontSize = 12.sp) }
        }
        YtSliderRow(stringResource(R.string.yt_anim_test_scroll), scroll, 0f..2000f, { v -> scroll = v; state.testScroll(state.target.kind, v) }, unit = " dp")
    }
}

/** Moves the playhead with the real frame time while the preview plays. */
@Composable
fun YtAnimPlayhead(state: YtEditorState) {
    LaunchedEffect(state.playing) {
        if (!state.playing) return@LaunchedEffect
        var last = -1L
        while (state.playing) {
            withFrameNanos { t ->
                if (last >= 0L) state.advancePlayhead(((t - last) / 1_000_000.0).coerceAtMost(250.0))
                last = t
            }
        }
    }
}
