package com.github.libretube.ui.yt.builder

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.libretube.R
import java.util.Locale
import kotlin.math.roundToInt

/*
 * YT: the theme editor's tools for what is selected (pure Compose UI over YtEditorState): colour
 * (HEX/RGB/HSL, alpha, recent colours, tokens, eyedropper), gradients with stops, corners per corner
 * with smoothing, shadows, type, layout and size rules, numbers with step nudges, and the theme's
 * own values when nothing is selected. Simple mode shows the first tabs, advanced all of them;
 * both edit the same document.
 */

private val PanelText = Color(0xFFE6E7EB)
private val PanelDim = Color(0xFF9EA2AD)

@Composable
private fun Label(text: String) = Text(text, color = PanelDim, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp, bottom = 2.dp))

/** A value slider with its number; [coalesce] merges one drag into one undo step. */
@Composable
fun YtSliderRow(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit, onDone: () -> Unit = {}, decimals: Int = 0, unit: String = "") {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, color = PanelText, fontSize = 13.sp, modifier = Modifier.width(96.dp))
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = { onChange(if (decimals == 0) it.roundToInt().toFloat() else it) },
            onValueChangeFinished = onDone,
            valueRange = range,
            modifier = Modifier.weight(1f),
        )
        Text(
            (if (decimals == 0) value.roundToInt().toString() else "%.${decimals}f".format(Locale.ROOT, value)) + unit,
            color = PanelText, fontSize = 12.sp, modifier = Modifier.width(52.dp).padding(start = 6.dp),
        )
    }
}

/** A number with − and + by [step], and typed input. */
@Composable
fun YtNumberRow(label: String, value: Float, step: Float, onChange: (Float) -> Unit, min: Float = -4000f, max: Float = 4000f) {
    var text by remember { mutableStateOf(fmt(value)) }
    // a new value from elsewhere replaces the text, the text being typed is left alone
    LaunchedEffect(value) { if (text.replace(',', '.').toFloatOrNull() != value) text = fmt(value) }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, color = PanelText, fontSize = 13.sp, modifier = Modifier.width(96.dp))
        TextButton(onClick = { onChange((value - step).coerceIn(min, max)) }) { Text("−") }
        OutlinedTextField(
            value = text,
            onValueChange = { t ->
                text = t
                t.replace(',', '.').toFloatOrNull()?.let { onChange(it.coerceIn(min, max)) }
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.width(96.dp),
            textStyle = MaterialTheme.typography.bodyMedium,
        )
        TextButton(onClick = { onChange((value + step).coerceIn(min, max)) }) { Text("+") }
    }
}

private fun fmt(v: Float) = if (v == v.roundToInt().toFloat()) v.roundToInt().toString() else "%.1f".format(Locale.ROOT, v)

@Composable
private fun <T> Choice(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for ((value, label) in options) FilterChip(selected = value == selected, onClick = { onSelect(value) }, label = { Text(label, fontSize = 12.sp) })
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 2.dp)) {
        Text(label, color = PanelText, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

// ---- colour ----

/**
 * The colour picker: a swatch, HEX, RGB or HSL sliders, alpha, the recent colours, the theme's
 * colour tokens (a link: the colour follows the token) and the eyedropper on the preview.
 */
@Composable
fun YtColorPicker(state: YtEditorState, argb: Int, token: String?, onColor: (Int) -> Unit, onToken: ((String) -> Unit)?) {
    var hsl by remember { mutableStateOf(false) }
    var hex by remember { mutableStateOf(YtColor.hex(argb)) }
    LaunchedEffect(argb) { if (YtColor.parse(hex) != argb) hex = YtColor.hex(argb) }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(40.dp).clip(RoundedCornerShape(10.dp))
                    .background(Brush.linearGradient(listOf(Color(0xFFCCCCCC), Color(0xFF888888))))
                    .background(Color(argb)).border(1.dp, Color(0x40FFFFFF), RoundedCornerShape(10.dp)),
            )
            OutlinedTextField(
                value = hex,
                onValueChange = { t ->
                    hex = t
                    YtColor.parse(t)?.let(onColor)
                },
                singleLine = true,
                label = { Text("HEX") },
                modifier = Modifier.padding(start = 10.dp).width(150.dp),
            )
            TextButton(onClick = { state.pick = { picked -> onColor(picked or 0xFF000000.toInt()); state.remember(picked) } }) {
                Text(stringResource(if (state.pick != null) R.string.yt_builder_pick_waiting else R.string.yt_builder_pick))
            }
        }
        if (token != null) Text(stringResource(R.string.yt_builder_linked_token, token), color = PanelDim, fontSize = 12.sp)
        Choice(listOf(false to "RGB", true to "HSL"), hsl) { hsl = it }
        val done = { state.seal(); state.remember(argb) }
        if (!hsl) {
            YtSliderRow("R", YtColor.red(argb).toFloat(), 0f..255f, { onColor(YtColor.argb(YtColor.alpha(argb), it.toInt(), YtColor.green(argb), YtColor.blue(argb))) }, done)
            YtSliderRow("G", YtColor.green(argb).toFloat(), 0f..255f, { onColor(YtColor.argb(YtColor.alpha(argb), YtColor.red(argb), it.toInt(), YtColor.blue(argb))) }, done)
            YtSliderRow("B", YtColor.blue(argb).toFloat(), 0f..255f, { onColor(YtColor.argb(YtColor.alpha(argb), YtColor.red(argb), YtColor.green(argb), it.toInt())) }, done)
        } else {
            val h = YtColor.toHsl(argb)
            YtSliderRow("H", h.h, 0f..360f, { onColor(YtColor.fromHsl(h.copy(h = it), YtColor.alpha(argb))) }, done)
            YtSliderRow("S", h.s * 100f, 0f..100f, { onColor(YtColor.fromHsl(h.copy(s = it / 100f), YtColor.alpha(argb))) }, done)
            YtSliderRow("L", h.l * 100f, 0f..100f, { onColor(YtColor.fromHsl(h.copy(l = it / 100f), YtColor.alpha(argb))) }, done)
        }
        YtSliderRow(stringResource(R.string.yt_builder_alpha), YtColor.alpha(argb) / 2.55f, 0f..100f, { onColor(YtColor.withAlpha(argb, it / 100f)) }, done, unit = "%")
        if (state.recent.isNotEmpty()) {
            Label(stringResource(R.string.yt_builder_recent))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (c in state.recent) Box(Modifier.size(28.dp).clip(CircleShape).background(Color(c)).border(1.dp, Color(0x40FFFFFF), CircleShape).clickable { onColor(c) })
            }
        }
        if (onToken != null) {
            val doc = state.doc
            val t = doc?.let { YtTokensResolver(it, state.night) }
            Label(stringResource(R.string.yt_builder_tokens))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (name in doc?.tokens?.colors?.keys.orEmpty()) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { onToken(name) }) {
                        Box(Modifier.size(28.dp).clip(CircleShape).background(Color(t?.argb(name) ?: 0)).border(if (name == token) 2.dp else 1.dp, if (name == token) Color.White else Color(0x40FFFFFF), CircleShape))
                        Text(name, color = PanelDim, fontSize = 9.sp, maxLines = 1)
                    }
                }
            }
        }
    }
}

/**
 * A paint: flat, linear or radial; gradients with their stops (each stop its own colour). With
 * [onImportImage] (fills) also an image of the theme, covering the element.
 */
@Composable
fun YtPaintEditor(state: YtEditorState, paint: YtPaint, key: String, onImportImage: (((String) -> Unit) -> Unit)? = null, onPaint: (YtPaint) -> Unit) {
    val t = state.doc?.let { YtTokensResolver(it, state.night) }
    var stop by remember(key) { mutableIntStateOf(0) }
    Column {
        Choice(
            buildList {
                add(YtPaintType.SOLID to stringResource(R.string.yt_builder_paint_solid))
                add(YtPaintType.LINEAR to stringResource(R.string.yt_builder_paint_linear))
                add(YtPaintType.RADIAL to stringResource(R.string.yt_builder_paint_radial))
                if (onImportImage != null || paint.type == YtPaintType.IMAGE) add(YtPaintType.IMAGE to stringResource(R.string.yt_builder_paint_image))
            },
            paint.type,
        ) { type ->
            val base = t?.resolve(paint)?.let { if (it.type == YtPaintType.SOLID || it.type == YtPaintType.IMAGE) it.color else it.stops.firstOrNull()?.color ?: it.color } ?: paint.color
            when (type) {
                YtPaintType.SOLID -> onPaint(YtPaint.solid(base))
                YtPaintType.IMAGE -> {
                    val first = state.doc?.assets?.firstOrNull { it.kind == YtAssetKind.IMAGE }?.file
                    if (first != null) onPaint(YtPaint.image(first, base)) else onImportImage?.invoke { file -> onPaint(YtPaint.image(file, base)) }
                }
                else -> onPaint(paint.copy(type = type, ref = null, image = null, stops = paint.stops.takeIf { it.size >= 2 } ?: listOf(YtStop(0f, base), YtStop(1f, YtColor.withAlpha(base, 0.2f)))))
            }
        }
        if (paint.type == YtPaintType.IMAGE) {
            YtImagePicker(state, paint.image, onImportImage) { file -> onPaint(paint.copy(image = file)) }
        } else if (paint.type == YtPaintType.SOLID) {
            val argb = t?.resolve(paint)?.color ?: paint.color
            YtColorPicker(state, argb, paint.ref, { onPaint(YtPaint.solid(it)) }, { onPaint(YtPaint.token(it)) })
        } else {
            // the gradient as it will look, then its geometry and the stops
            Box(Modifier.fillMaxWidth().height(28.dp).padding(vertical = 4.dp).clip(RoundedCornerShape(8.dp)).background(Brush.horizontalGradient(paint.stops.sortedBy { it.at }.map { Color(it.color) }.ifEmpty { listOf(Color.Black, Color.Black) }.let { if (it.size == 1) it + it else it })))
            if (paint.type == YtPaintType.LINEAR) {
                YtSliderRow(stringResource(R.string.yt_builder_angle), paint.angle, 0f..359f, { onPaint(paint.copy(angle = it)) }, state::seal, unit = "°")
            } else {
                YtSliderRow("X", paint.centerX * 100f, 0f..100f, { onPaint(paint.copy(centerX = it / 100f)) }, state::seal, unit = "%")
                YtSliderRow("Y", paint.centerY * 100f, 0f..100f, { onPaint(paint.copy(centerY = it / 100f)) }, state::seal, unit = "%")
                YtSliderRow(stringResource(R.string.yt_builder_radius), paint.radius * 100f, 5f..200f, { onPaint(paint.copy(radius = it / 100f)) }, state::seal, unit = "%")
            }
            Label(stringResource(R.string.yt_builder_stops))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                paint.stops.forEachIndexed { i, s ->
                    Box(Modifier.size(30.dp).clip(CircleShape).background(Color(s.color)).border(if (i == stop) 2.dp else 1.dp, if (i == stop) Color.White else Color(0x40FFFFFF), CircleShape).clickable { stop = i })
                }
                TextButton(onClick = {
                    val last = paint.stops.lastOrNull()
                    onPaint(paint.copy(stops = paint.stops + YtStop(1f, last?.color ?: 0xFFFFFFFF.toInt())))
                    stop = paint.stops.size
                }) { Text("+") }
                if (paint.stops.size > 2) TextButton(onClick = {
                    onPaint(paint.copy(stops = paint.stops.filterIndexed { i, _ -> i != stop }))
                    stop = 0
                }) { Text("−") }
            }
            paint.stops.getOrNull(stop)?.let { s ->
                YtSliderRow(stringResource(R.string.yt_builder_position), s.at * 100f, 0f..100f, { v -> onPaint(paint.copy(stops = paint.stops.mapIndexed { i, x -> if (i == stop) x.copy(at = v / 100f) else x })) }, state::seal, unit = "%")
                YtColorPicker(state, s.color, null, { c -> onPaint(paint.copy(stops = paint.stops.mapIndexed { i, x -> if (i == stop) x.copy(color = c, ref = null) else x })) }, null)
            }
            TextButton(onClick = { YtEditorClipboard.paint = paint }) { Text(stringResource(R.string.yt_builder_copy)) }
        }
        YtEditorClipboard.paint?.let { copied ->
            TextButton(onClick = { onPaint(copied) }) { Text(stringResource(R.string.yt_builder_paste)) }
        }
    }
}

/** The theme's images to pick from, and the import (a picked file is checked and scaled, YtImageImport). */
@Composable
fun YtImagePicker(state: YtEditorState, selected: String?, onImportImage: (((String) -> Unit) -> Unit)?, onPick: (String) -> Unit) {
    val images = state.doc?.assets?.filter { it.kind == YtAssetKind.IMAGE }.orEmpty()
    Label(stringResource(R.string.yt_builder_image))
    if (images.isEmpty()) {
        Text(stringResource(R.string.yt_builder_image_none), color = PanelDim, fontSize = 12.sp)
    } else {
        Choice(images.map { it.file to it.file }, selected ?: "") { onPick(it) }
    }
    if (onImportImage != null) {
        TextButton(onClick = { onImportImage(onPick) }) { Text(stringResource(R.string.yt_builder_import_image)) }
    }
}

/** Copied effect values, to put on another element. */
object YtEditorClipboard {
    var paint: YtPaint? by mutableStateOf(null)
    var shadows: List<YtShadow>? by mutableStateOf(null)
}

// ---- the panel ----

private enum class Tab(val label: Int, val simple: Boolean) {
    COLOR(R.string.yt_builder_tab_color, true),
    SIZE(R.string.yt_builder_tab_size, true),
    CORNERS(R.string.yt_builder_tab_corners, true),
    FORM(R.string.yt_vec_tab, true),
    TEXT(R.string.yt_builder_tab_text, true),
    ANIM(R.string.yt_anim_tab, true),
    SHADOW(R.string.yt_builder_tab_shadow, false),
    LAYOUT(R.string.yt_builder_tab_layout, false),
    RULES(R.string.yt_builder_tab_rules, false),
}

/** The context panel: the selected node's tools, or the theme's values when nothing is selected. */
@Composable
fun YtEditorPanel(state: YtEditorState, onImportFont: ((String) -> Unit) -> Unit, modifier: Modifier = Modifier, onImportImage: ((String) -> Unit) -> Unit = {}) {
    val node = state.primary
    Column(modifier.verticalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 6.dp)) {
        // the pen and the recording have their own panels (phase 3)
        if (state.vector.pen != null) {
            YtPenPanel(state)
            return@Column
        }
        if (state.vector.record != null) {
            YtRecordPanel(state)
            return@Column
        }
        if (node == null) {
            YtThemePanel(state)
            return@Column
        }
        val textual = node.type == YtNodeType.TEXT || (node.type == YtNodeType.SLOT && node.slot?.text == true) ||
            node.type == YtNodeType.ICON || node.slot == YtSlot.SEARCH || node.slot == YtSlot.PROFILE || node.slot == YtSlot.NAV_ICON
        val tabs = Tab.entries.filter { (state.advanced || it.simple) && (it != Tab.TEXT || textual) && (it != Tab.LAYOUT || node.type == YtNodeType.CONTAINER) }
        var tab by remember { mutableStateOf(Tab.COLOR) }
        LaunchedEffect(state.openAnimTab) {
            if (state.openAnimTab) {
                tab = Tab.ANIM
                state.openAnimTab = false
            }
        }
        if (tab !in tabs) tab = tabs.first()
        Text(node.name.ifEmpty { node.id } + if (state.selection.size > 1) " (+${state.selection.size - 1})" else "", color = PanelText, fontSize = 15.sp)
        ScrollableTabRow(selectedTabIndex = tabs.indexOf(tab), edgePadding = 0.dp, containerColor = Color.Transparent) {
            tabs.forEach { t -> Tab(selected = t == tab, onClick = { tab = t }, text = { Text(stringResource(t.label), fontSize = 12.sp) }) }
        }
        when (tab) {
            Tab.COLOR -> ColorTab(state, node, onImportImage)
            Tab.SIZE -> SizeTab(state, node)
            Tab.CORNERS -> CornersTab(state, node)
            Tab.FORM -> YtFormTab(state, node)
            Tab.TEXT -> TextTab(state, node, onImportFont)
            Tab.ANIM -> YtAnimTab(state, node)
            Tab.SHADOW -> ShadowTab(state, node)
            Tab.LAYOUT -> LayoutTab(state, node, onImportImage)
            Tab.RULES -> RulesTab(state, node)
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** The states whose look the colour tab edits besides the normal one (drawn in the app: YtStatePatchRule). */
private val editableStates = listOf(YtStateNames.SELECTED, YtStateNames.PRESSED, YtStateNames.ACTIVE, YtStateNames.LOADING)

@Composable
private fun ColorTab(state: YtEditorState, node: YtNode, onImportImage: ((String) -> Unit) -> Unit) {
    val textual = node.text != null
    var which by remember(node.id) { mutableIntStateOf(if (node.style.fills.isEmpty() && textual) 2 else 0) }
    if (node.type == YtNodeType.IMAGE) {
        // an image element shows one of the theme's images (its file name is kept in `icon`)
        YtImagePicker(state, node.icon, onImportImage) { file -> state.edit("image") { it.copy(icon = file) } }
    }
    // the look of a state: normal, or the patch of selected, pressed, active (player plays), loading
    Label(stringResource(R.string.yt_builder_state_look))
    Choice(
        listOf<Pair<String?, String>>(null to stringResource(R.string.yt_builder_state_normal)) + editableStates.map { it to stateLabel(it) },
        if (state.editState) state.editStateName else null,
    ) { s ->
        state.editState = s != null
        if (s != null) state.editStateName = s
    }
    val sKey = state.editStateName
    val inState = state.editState
    if (inState && sKey == YtStateNames.PRESSED) {
        // how far the element gives while pressed (the plain press feel; the animator's own press replaces it)
        val scale = node.states[sKey]?.scale ?: state.doc?.tokens?.motion?.pressScale ?: 0.97f
        YtSliderRow(stringResource(R.string.yt_builder_press_scale), scale * 100f, 80f..110f, { v ->
            state.edit("press scale", "pressScale") { n -> n.copy(states = n.states + (sKey to (n.states[sKey] ?: YtPatch()).copy(scale = v / 100f))) }
        }, state::seal, unit = "%")
    }
    Choice(
        buildList {
            add(0 to stringResource(R.string.yt_builder_fill))
            add(1 to stringResource(R.string.yt_builder_stroke))
            if (textual) add(2 to stringResource(R.string.yt_builder_text_color))
        },
        which,
    ) { which = it }
    when (which) {
        0 -> {
            val fills = if (inState) node.states[sKey]?.fills ?: node.style.fills else node.style.fills
            val fill = fills.firstOrNull()
            if (fill == null) {
                TextButton(onClick = { state.edit("fill") { setFills(it, listOf(YtPaint.token(YtRole.RAISED)), inState, sKey) } }) { Text(stringResource(R.string.yt_builder_add_fill)) }
            } else {
                YtPaintEditor(state, fill, node.id + "fill", onImportImage) { p -> state.edit("fill", "fill") { setFills(it, listOf(p) + fills.drop(1), inState, sKey) } }
                TextButton(onClick = { state.edit("fill") { setFills(it, emptyList(), inState, sKey) } }) { Text(stringResource(R.string.yt_builder_remove)) }
            }
        }
        1 -> {
            val stroke = node.style.strokes.firstOrNull()
            if (stroke == null) {
                TextButton(onClick = { state.edit("stroke") { it.copy(style = it.style.copy(strokes = listOf(YtStroke(YtPaint.token(YtRole.TEXT_SECONDARY), 1f)))) } }) { Text(stringResource(R.string.yt_builder_add_stroke)) }
            } else {
                YtSliderRow(stringResource(R.string.yt_builder_width), stroke.width, 0f..12f, { w -> state.edit("stroke", "strokeWidth") { it.copy(style = it.style.copy(strokes = listOf(stroke.copy(width = w)) + it.style.strokes.drop(1))) } }, state::seal, 1, " dp")
                YtPaintEditor(state, stroke.paint, node.id + "stroke") { p -> state.edit("stroke", "stroke") { it.copy(style = it.style.copy(strokes = listOf(stroke.copy(paint = p)) + it.style.strokes.drop(1))) } }
                TextButton(onClick = { state.edit("stroke") { it.copy(style = it.style.copy(strokes = emptyList())) } }) { Text(stringResource(R.string.yt_builder_remove)) }
            }
        }
        2 -> {
            val spec = node.text ?: YtTextSpec()
            val color = if (inState) node.states[sKey]?.textColor ?: spec.color else spec.color
            YtPaintEditor(state, color, node.id + "text") { p ->
                state.edit("text color", "textColor") { n ->
                    if (inState) n.copy(states = n.states + (sKey to (n.states[sKey] ?: YtPatch()).copy(textColor = p)))
                    else n.copy(text = (n.text ?: YtTextSpec()).copy(color = p))
                }
            }
        }
    }
    YtSliderRow(stringResource(R.string.yt_builder_opacity), node.style.opacity * 100f, 0f..100f, { v -> state.edit("opacity", "opacity") { it.copy(style = it.style.copy(opacity = v / 100f)) } }, state::seal, unit = "%")
}

private fun setFills(n: YtNode, fills: List<YtPaint>, inState: Boolean, state: String = YtStates.SELECTED): YtNode =
    if (inState) n.copy(states = n.states + (state to (n.states[state] ?: YtPatch()).copy(fills = fills)))
    else n.copy(style = n.style.copy(fills = fills))

@Composable
private fun SizeTab(state: YtEditorState, node: YtNode) {
    val parent = state.parentOf(node.id)
    val free = parent?.layout?.mode == YtLayoutMode.FREE
    var step by remember { mutableStateOf(1f) }
    Choice(listOf(1f to "1 dp", 4f to "4 dp", 8f to "8 dp"), step) { step = it }
    if (free) {
        YtNumberRow("X", node.x, step, { v -> state.edit("x", "x") { it.copy(x = v) } })
        YtNumberRow("Y", node.y, step, { v -> state.edit("y", "y") { it.copy(y = v) } })
    }
    SizeRule(stringResource(R.string.yt_builder_w), node.width, step) { r -> state.edit("width", "width") { it.copy(width = r) } }
    SizeRule(stringResource(R.string.yt_builder_h), node.height, step) { r -> state.edit("height", "height") { it.copy(height = r) } }
    SwitchRow(stringResource(R.string.yt_builder_aspect_lock), node.aspect != null) { on ->
        val box = state.boundsOf(node.id)
        state.edit("aspect") { it.copy(aspect = if (on) box?.let { b -> (b.width / b.height.coerceAtLeast(1f)) } ?: (16f / 9f) else null) }
    }
    YtNumberRow(stringResource(R.string.yt_builder_rotation), node.rotation, if (step == 1f) 1f else 15f, { v -> state.edit("rotation", "rotation") { it.copy(rotation = v) } }, -360f, 360f)
}

@Composable
private fun SizeRule(label: String, rule: YtSize, step: Float, onRule: (YtSize) -> Unit) {
    Label(label)
    Choice(
        listOf(
            YtSizeKind.FIXED to stringResource(R.string.yt_builder_size_fixed), YtSizeKind.HUG to stringResource(R.string.yt_builder_size_hug),
            YtSizeKind.FILL to stringResource(R.string.yt_builder_size_fill), YtSizeKind.PERCENT to "%",
        ),
        rule.kind,
    ) { k -> onRule(YtSize(k, if (k == YtSizeKind.FIXED) rule.value.takeIf { rule.kind == YtSizeKind.FIXED } ?: 100f else if (k == YtSizeKind.PERCENT) 50f else if (k == YtSizeKind.FILL) 1f else 0f)) }
    when (rule.kind) {
        YtSizeKind.FIXED -> YtNumberRow("dp", rule.value, step, { onRule(rule.copy(value = it)) }, 0f, YtThemeValidate.MAX_SIZE)
        YtSizeKind.PERCENT -> YtNumberRow("%", rule.value, step, { onRule(rule.copy(value = it)) }, 0f, 100f)
        YtSizeKind.FILL -> YtNumberRow(stringResource(R.string.yt_builder_weight), rule.value.takeIf { it > 0f } ?: 1f, 1f, { onRule(rule.copy(value = it)) }, 1f, 20f)
        YtSizeKind.HUG -> Unit
    }
}

@Composable
private fun CornersTab(state: YtEditorState, node: YtNode) {
    val c = node.style.corners
    var linked by remember(node.id) { mutableStateOf(c.topStart == c.topEnd && c.topEnd == c.bottomEnd && c.bottomEnd == c.bottomStart) }
    SwitchRow(stringResource(R.string.yt_builder_corners_linked), linked) { linked = it }
    fun set(change: (YtCorners) -> YtCorners) = state.edit("corners", "corners") { it.copy(style = it.style.copy(corners = change(it.style.corners))) }
    if (linked) {
        YtSliderRow(stringResource(R.string.yt_builder_radius), c.topStart, 0f..100f, { v -> set { it.copy(topStart = v, topEnd = v, bottomEnd = v, bottomStart = v) } }, state::seal, unit = " dp")
    } else {
        YtSliderRow(stringResource(R.string.yt_builder_corner_ts), c.topStart, 0f..100f, { v -> set { it.copy(topStart = v) } }, state::seal, unit = " dp")
        YtSliderRow(stringResource(R.string.yt_builder_corner_te), c.topEnd, 0f..100f, { v -> set { it.copy(topEnd = v) } }, state::seal, unit = " dp")
        YtSliderRow(stringResource(R.string.yt_builder_corner_be), c.bottomEnd, 0f..100f, { v -> set { it.copy(bottomEnd = v) } }, state::seal, unit = " dp")
        YtSliderRow(stringResource(R.string.yt_builder_corner_bs), c.bottomStart, 0f..100f, { v -> set { it.copy(bottomStart = v) } }, state::seal, unit = " dp")
    }
    YtSliderRow(stringResource(R.string.yt_builder_smoothing), c.smoothing * 100f, 0f..100f, { v -> set { it.copy(smoothing = v / 100f) } }, state::seal, unit = "%")
    if (node.type == YtNodeType.SHAPE) {
        Label(stringResource(R.string.yt_builder_shape))
        Choice(listOf(YtShapeKind.RECT to "▭", YtShapeKind.PILL to "⬭", YtShapeKind.ELLIPSE to "◯", YtShapeKind.LINE to "—"), node.shape) { k ->
            state.edit("shape") { n ->
                when (k) {
                    YtShapeKind.PILL, YtShapeKind.ELLIPSE -> n.copy(shape = k, style = n.style.copy(corners = YtCorners.all(500f, n.style.corners.smoothing)))
                    else -> n.copy(shape = k)
                }
            }
        }
    }
}

@Composable
private fun TextTab(state: YtEditorState, node: YtNode, onImportFont: ((String) -> Unit) -> Unit) {
    val spec = node.text ?: YtTextSpec()
    val doc = state.doc ?: return
    fun set(change: (YtTextSpec) -> YtTextSpec) = state.edit("text", "text") { it.copy(text = change(it.text ?: YtTextSpec())) }
    if (node.type == YtNodeType.TEXT) {
        OutlinedTextField(value = spec.text, onValueChange = { v -> set { it.copy(text = v.take(YtThemeValidate.MAX_TEXT)) } }, label = { Text(stringResource(R.string.yt_builder_text)) }, modifier = Modifier.fillMaxWidth())
    }
    if (node.type == YtNodeType.ICON) {
        Label(stringResource(R.string.yt_builder_icon))
        Choice(YtIcons.names.map { it to it }, node.icon ?: "star") { name -> state.edit("icon") { it.copy(icon = name) } }
        return
    }
    if (node.text == null && node.type == YtNodeType.SLOT && node.slot?.text != true) return
    Label(stringResource(R.string.yt_builder_type_token))
    Choice(doc.tokens.type.keys.map { it to it }, spec.styleRef) { name -> set { it.copy(styleRef = name) } }
    val token = YtTokensResolver(doc, state.night).type(spec.styleRef)
    YtSliderRow(stringResource(R.string.yt_builder_size), if (spec.size > 0f) spec.size else token.size, 6f..64f, { v -> set { it.copy(size = v) } }, state::seal, 1, " sp")
    Label(stringResource(R.string.yt_builder_weight_font))
    Choice(listOf(300, 400, 500, 600, 700, 800, 900).map { it to it.toString() }, if (spec.weight > 0) spec.weight else token.weight) { w -> set { it.copy(weight = w) } }
    YtNumberRow(stringResource(R.string.yt_builder_lines), spec.maxLines.toFloat(), 1f, { v -> set { it.copy(maxLines = v.roundToInt().coerceIn(1, 20)) } }, 1f, 20f)
    Label(stringResource(R.string.yt_builder_align))
    Choice(listOf(YtTextAlign.START to "⟸", YtTextAlign.CENTER to "≡", YtTextAlign.END to "⟹"), spec.align) { a -> set { it.copy(align = a) } }
    Label(stringResource(R.string.yt_builder_font))
    val fonts = YtFontKeys.bundled + doc.assets.filter { it.kind == YtAssetKind.FONT }.map { YtFontKeys.ASSET + it.file }
    // the font belongs to the type token: every text of that token changes with it
    Choice(fonts.map { it to it.removePrefix(YtFontKeys.ASSET) }, token.font) { f -> state.run("font") { d -> d.copy(tokens = d.tokens.copy(type = d.tokens.type + (spec.styleRef to token.copy(font = f)))) } }
    TextButton(onClick = { onImportFont { key -> state.run("font") { d -> d.copy(tokens = d.tokens.copy(type = d.tokens.type + (spec.styleRef to token.copy(font = key)))) } } }) {
        Text(stringResource(R.string.yt_builder_import_font))
    }
    if (state.advanced) {
        Label(stringResource(R.string.yt_builder_token_values, spec.styleRef))
        fun setToken(change: (YtTypeToken) -> YtTypeToken) = state.run("type token", "token:" + spec.styleRef) { d -> d.copy(tokens = d.tokens.copy(type = d.tokens.type + (spec.styleRef to change(token)))) }
        YtSliderRow(stringResource(R.string.yt_builder_size), token.size, 6f..64f, { v -> setToken { it.copy(size = v) } }, state::seal, 1, " sp")
        YtSliderRow(stringResource(R.string.yt_builder_line_height), token.lineHeight, 0f..80f, { v -> setToken { it.copy(lineHeight = v) } }, state::seal, 0, " sp")
        YtSliderRow(stringResource(R.string.yt_builder_tracking), token.letterSpacing, -1f..2f, { v -> setToken { it.copy(letterSpacing = v) } }, state::seal, 2, " sp")
        SwitchRow(stringResource(R.string.yt_builder_caps), token.caps) { on -> setToken { it.copy(caps = on) } }
    }
}

@Composable
private fun ShadowTab(state: YtEditorState, node: YtNode) {
    val shadows = node.style.shadows
    fun set(list: List<YtShadow>) = state.edit("shadow", "shadow") { it.copy(style = it.style.copy(shadows = list.take(YtThemeValidate.MAX_SHADOWS))) }
    Row {
        TextButton(onClick = { set(shadows + YtShadow()) }) { Text(stringResource(R.string.yt_builder_add_shadow)) }
        TextButton(onClick = { set(shadows + YtShadow(0f, 0f, 18f, 2f, YtTokensResolver(state.doc!!, state.night).argb(YtRole.ACCENT) and 0x00FFFFFF or 0x99000000.toInt())) }) { Text(stringResource(R.string.yt_builder_add_glow)) }
        TextButton(onClick = { YtEditorClipboard.shadows = shadows }) { Text(stringResource(R.string.yt_builder_copy)) }
        YtEditorClipboard.shadows?.let { copied -> TextButton(onClick = { set(copied) }) { Text(stringResource(R.string.yt_builder_paste)) } }
    }
    shadows.forEachIndexed { i, s ->
        fun put(n: YtShadow) = set(shadows.mapIndexed { j, x -> if (j == i) n else x })
        Label(stringResource(R.string.yt_builder_shadow_n, i + 1))
        SwitchRow(stringResource(R.string.yt_builder_on), s.enabled) { put(s.copy(enabled = it)) }
        SwitchRow(stringResource(R.string.yt_builder_inner), s.inner) { put(s.copy(inner = it)) }
        YtSliderRow("X", s.x, -40f..40f, { put(s.copy(x = it)) }, state::seal)
        YtSliderRow("Y", s.y, -40f..40f, { put(s.copy(y = it)) }, state::seal)
        YtSliderRow(stringResource(R.string.yt_builder_blur), s.blur, 0f..80f, { put(s.copy(blur = it)) }, state::seal)
        YtSliderRow(stringResource(R.string.yt_builder_spread), s.spread, -20f..40f, { put(s.copy(spread = it)) }, state::seal)
        YtColorPicker(state, s.color, null, { put(s.copy(color = it)) }, null)
        TextButton(onClick = { set(shadows.filterIndexed { j, _ -> j != i }) }) { Text(stringResource(R.string.yt_builder_remove)) }
    }
}

@Composable
private fun LayoutTab(state: YtEditorState, node: YtNode, onImportImage: ((String) -> Unit) -> Unit) {
    val l = node.layout
    fun set(change: (YtLayout) -> YtLayout) = state.edit("layout", "layout") { it.copy(layout = change(it.layout)) }
    Label(stringResource(R.string.yt_builder_layout_mode))
    Choice(
        listOf(
            YtLayoutMode.FLOW_V to stringResource(R.string.yt_builder_mode_column), YtLayoutMode.FLOW_H to stringResource(R.string.yt_builder_mode_row),
            YtLayoutMode.GRID to stringResource(R.string.yt_builder_mode_grid), YtLayoutMode.FREE to stringResource(R.string.yt_builder_mode_free),
        ),
        l.mode,
    ) { m -> set { it.copy(mode = m) } }
    YtSliderRow(stringResource(R.string.yt_builder_gap), l.gap, 0f..64f, { v -> set { it.copy(gap = v) } }, state::seal, unit = " dp")
    if (l.mode == YtLayoutMode.GRID) {
        YtSliderRow(stringResource(R.string.yt_builder_row_gap), l.crossGap, 0f..64f, { v -> set { it.copy(crossGap = v) } }, state::seal, unit = " dp")
        YtNumberRow(stringResource(R.string.yt_builder_columns), l.columns.toFloat(), 1f, { v -> set { it.copy(columns = v.roundToInt().coerceIn(1, YtThemeValidate.MAX_COLUMNS)) } }, 1f, YtThemeValidate.MAX_COLUMNS.toFloat())
    }
    if (l.mode == YtLayoutMode.FLOW_H || l.mode == YtLayoutMode.FLOW_V) {
        Label(stringResource(R.string.yt_builder_align))
        Choice(listOf(YtAlign.START to "⇤", YtAlign.CENTER to "↔", YtAlign.END to "⇥", YtAlign.STRETCH to "⇔"), l.align) { a -> set { it.copy(align = a) } }
        Label(stringResource(R.string.yt_builder_justify))
        Choice(listOf(YtJustify.START to "|··", YtJustify.CENTER to "·|·", YtJustify.END to "··|", YtJustify.SPACE_BETWEEN to "|·|"), l.justify) { j -> set { it.copy(justify = j) } }
    }
    SwitchRow(stringResource(R.string.yt_builder_clip), l.clip) { on -> set { it.copy(clip = on) } }
    Label(stringResource(R.string.yt_builder_padding))
    val p = node.padding
    fun pad(change: (YtInsets) -> YtInsets) = state.edit("padding", "padding") { it.copy(padding = change(it.padding)) }
    YtSliderRow(stringResource(R.string.yt_builder_side_start), p.start, 0f..64f, { v -> pad { it.copy(start = v) } }, state::seal)
    YtSliderRow(stringResource(R.string.yt_builder_side_top), p.top, 0f..64f, { v -> pad { it.copy(top = v) } }, state::seal)
    YtSliderRow(stringResource(R.string.yt_builder_side_end), p.end, 0f..64f, { v -> pad { it.copy(end = v) } }, state::seal)
    YtSliderRow(stringResource(R.string.yt_builder_side_bottom), p.bottom, 0f..64f, { v -> pad { it.copy(bottom = v) } }, state::seal)
    if (state.advanced) {
        Label(stringResource(R.string.yt_builder_add))
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            TextButton(onClick = { state.addChild(YtNode("text", type = YtNodeType.TEXT, name = "Text", text = YtTextSpec(text = "Text"), x = 12f, y = 12f)) }) { Text(stringResource(R.string.yt_builder_add_text)) }
            TextButton(onClick = { state.addChild(YtNode("shape", type = YtNodeType.SHAPE, name = "Shape", width = YtSize.fixed(48f), height = YtSize.fixed(48f), x = 12f, y = 12f, style = YtStyle(fills = listOf(YtPaint.token(YtRole.ACCENT)), corners = YtCorners.all(12f)))) }) { Text(stringResource(R.string.yt_builder_add_shape)) }
            TextButton(onClick = { state.addChild(YtNode("icon", type = YtNodeType.ICON, name = "Icon", icon = "star", width = YtSize.fixed(24f), height = YtSize.fixed(24f), x = 12f, y = 12f, text = YtTextSpec())) }) { Text(stringResource(R.string.yt_builder_add_icon)) }
            TextButton(onClick = {
                // an image element: the theme's first image (another one is picked in its colour tab), or a new one
                val add = { file: String ->
                    state.addChild(YtNode("image", type = YtNodeType.IMAGE, name = "Image", icon = file, width = YtSize.fixed(96f), height = YtSize.fixed(64f), x = 12f, y = 12f, style = YtStyle(corners = YtCorners.all(8f)), layout = YtLayout(clip = true)))
                }
                val first = state.doc?.assets?.firstOrNull { it.kind == YtAssetKind.IMAGE }?.file
                if (first != null) add(first) else onImportImage(add)
            }) { Text(stringResource(R.string.yt_builder_image)) }
            TextButton(onClick = { state.addChild(YtNode("box", name = "Box", layout = YtLayout(YtLayoutMode.FREE), width = YtSize.fixed(80f), height = YtSize.fixed(60f), x = 12f, y = 12f, style = YtStyle(fills = listOf(YtPaint.token(YtRole.RAISED)), corners = YtCorners.all(12f)))) }) { Text(stringResource(R.string.yt_builder_add_box)) }
        }
    }
}

@Composable
private fun RulesTab(state: YtEditorState, node: YtNode) {
    val parent = state.parentOf(node.id)
    if (parent?.layout?.mode == YtLayoutMode.FREE) {
        Label(stringResource(R.string.yt_builder_anchor))
        val anchors = listOf(YtAnchor.START to "⇤", YtAnchor.CENTER to "•", YtAnchor.END to "⇥")
        Choice(anchors, node.anchorH) { a -> state.edit("anchor") { it.copy(anchorH = a) } }
        Choice(anchors.map { (a, _) -> a to when (a) { YtAnchor.START -> "⤒"; YtAnchor.CENTER -> "•"; YtAnchor.END -> "⤓" } }, node.anchorV) { a -> state.edit("anchor") { it.copy(anchorV = a) } }
    }
    Label(stringResource(R.string.yt_builder_limits))
    LimitRow(stringResource(R.string.yt_builder_min_w), node.minW) { v -> state.edit("min", "minW") { it.copy(minW = v) } }
    LimitRow(stringResource(R.string.yt_builder_max_w), node.maxW) { v -> state.edit("max", "maxW") { it.copy(maxW = v) } }
    LimitRow(stringResource(R.string.yt_builder_min_h), node.minH) { v -> state.edit("min", "minH") { it.copy(minH = v) } }
    LimitRow(stringResource(R.string.yt_builder_max_h), node.maxH) { v -> state.edit("max", "maxH") { it.copy(maxH = v) } }
    SwitchRow(stringResource(R.string.yt_builder_visible), node.visible) { on -> state.edit("visible") { it.copy(visible = on) } }
    SwitchRow(stringResource(R.string.yt_builder_locked), node.locked) { on -> state.edit("lock") { it.copy(locked = on) } }
    YtNumberRow(stringResource(R.string.yt_builder_z), node.z.toFloat(), 1f, { v -> state.edit("z", "z") { it.copy(z = v.roundToInt()) } }, -100f, 100f)
    // the window the preview simulates: what changes there goes into an override of that size
    val env = YtEnv(state.size.widthDp, state.size.heightDp, state.fontScale)
    val cond = YtOverride(width = env.widthClass.takeIf { it != YtWidthClass.COMPACT }, orientation = env.orientation.takeIf { it == YtOrientation.LANDSCAPE }, minFontScale = state.fontScale.takeIf { it > 1f })
    if (cond.width != null || cond.orientation != null || cond.minFontScale != null) {
        Label(stringResource(R.string.yt_builder_override_for, state.size.name.lowercase() + if (state.fontScale > 1f) " · ${state.fontScale}×" else ""))
        val current = node.overrides.firstOrNull { it.width == cond.width && it.orientation == cond.orientation && it.minFontScale == cond.minFontScale }?.patch ?: YtPatch()
        SwitchRow(stringResource(R.string.yt_builder_override_hide), current.visible == false) { on ->
            state.edit("override") { YtResponsive.withOverride(it, cond) { p -> p.copy(visible = if (on) false else null) } }
        }
        if (node.text != null) {
            YtSliderRow(stringResource(R.string.yt_builder_override_text), current.textSize ?: 0f, 0f..48f, { v -> state.edit("override", "overrideText") { YtResponsive.withOverride(it, cond) { p -> p.copy(textSize = v.takeIf { it >= 6f }) } } }, state::seal, 0, " sp")
        }
        if (node.type == YtNodeType.CONTAINER && (node.layout.mode == YtLayoutMode.GRID)) {
            YtNumberRow(stringResource(R.string.yt_builder_columns), (current.columns ?: node.layout.columns).toFloat(), 1f, { v -> state.edit("override", "overrideColumns") { YtResponsive.withOverride(it, cond) { p -> p.copy(columns = v.roundToInt().coerceIn(1, 8)) } } }, 1f, 8f)
        }
        if (node.overrides.isNotEmpty()) TextButton(onClick = { state.edit("override") { it.copy(overrides = emptyList()) } }) { Text(stringResource(R.string.yt_builder_override_clear)) }
    }
}

@Composable
private fun LimitRow(label: String, value: Float?, onChange: (Float?) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(checked = value != null, onCheckedChange = { onChange(if (it) 48f else null) }, modifier = Modifier.padding(end = 8.dp))
        if (value != null) YtNumberRow(label, value, 4f, { onChange(it.coerceAtLeast(0f)) }, 0f, YtThemeValidate.MAX_SIZE) else Text(label, color = PanelDim, fontSize = 13.sp)
    }
}

/** The theme's own values: colour roles, motion feel, feed, bar, mini player, wordmark, look. */
@Composable
private fun YtThemePanel(state: YtEditorState) {
    val doc = state.doc ?: return
    var role by remember { mutableStateOf<String?>(null) }
    Text(stringResource(R.string.yt_builder_theme_values), color = PanelText, fontSize = 15.sp)
    Label(stringResource(R.string.yt_builder_mode))
    Choice(listOf(YtThemeMode.DARK to stringResource(R.string.yt_builder_mode_dark), YtThemeMode.BOTH to stringResource(R.string.yt_builder_mode_both)), doc.mode) { m ->
        state.run("mode") { d -> d.copy(mode = m) }
    }
    if (doc.mode == YtThemeMode.BOTH) SwitchRow(stringResource(R.string.yt_builder_edit_night), state.night) { state.night = it }
    Label(stringResource(R.string.yt_builder_colors))
    val t = YtTokensResolver(doc, state.night)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (name in doc.tokens.colors.keys) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { role = if (role == name) null else name }) {
                Box(Modifier.size(34.dp).clip(CircleShape).background(Color(t.argb(name))).border(if (role == name) 2.dp else 1.dp, if (role == name) Color.White else Color(0x40FFFFFF), CircleShape))
                Text(name, color = PanelDim, fontSize = 9.sp, maxLines = 1)
            }
        }
    }
    role?.let { name ->
        val token = doc.tokens.colors[name] ?: return@let
        val night = state.night && doc.mode == YtThemeMode.BOTH
        val paint = (if (night) token.night else null) ?: token.day
        YtPaintEditor(state, paint, "role:$name") { p ->
            state.run("token $name", "token:$name:$night") { d ->
                val tk = d.tokens.colors[name] ?: token
                d.copy(tokens = d.tokens.copy(colors = d.tokens.colors + (name to if (night) tk.copy(night = p) else tk.copy(day = p))))
            }
        }
    }
    Label(stringResource(R.string.yt_builder_motion))
    val m = doc.tokens.motion
    fun motion(change: (YtMotionToken) -> YtMotionToken) = state.run("motion", "motion") { d -> d.copy(tokens = d.tokens.copy(motion = change(d.tokens.motion))) }
    YtSliderRow(stringResource(R.string.yt_builder_stiffness), m.stiffness, 50f..1500f, { v -> motion { it.copy(stiffness = v) } }, state::seal)
    YtSliderRow(stringResource(R.string.yt_builder_damping), m.damping, 0.2f..1.2f, { v -> motion { it.copy(damping = v) } }, state::seal, 2)
    YtSliderRow(stringResource(R.string.yt_builder_press), m.pressScale * 100f, 85f..100f, { v -> motion { it.copy(pressScale = v / 100f) } }, state::seal, unit = "%")
    Label(stringResource(R.string.yt_builder_feed))
    val f = doc.feed
    fun feed(change: (YtFeedLayout) -> YtFeedLayout) = state.run("feed", "feed") { d -> d.copy(feed = change(d.feed)) }
    YtNumberRow(stringResource(R.string.yt_builder_cols_compact), f.compact.toFloat(), 1f, { v -> feed { it.copy(compact = v.roundToInt().coerceIn(1, 8)) } }, 1f, 8f)
    YtNumberRow(stringResource(R.string.yt_builder_cols_medium), f.medium.toFloat(), 1f, { v -> feed { it.copy(medium = v.roundToInt().coerceIn(1, 8)) } }, 1f, 8f)
    YtNumberRow(stringResource(R.string.yt_builder_cols_expanded), f.expanded.toFloat(), 1f, { v -> feed { it.copy(expanded = v.roundToInt().coerceIn(1, 8)) } }, 1f, 8f)
    YtSliderRow(stringResource(R.string.yt_builder_gap), f.gap, 0f..48f, { v -> feed { it.copy(gap = v) } }, state::seal, unit = " dp")
    YtSliderRow(stringResource(R.string.yt_builder_row_gap), f.rowGap, 0f..48f, { v -> feed { it.copy(rowGap = v) } }, state::seal, unit = " dp")
    YtSliderRow(stringResource(R.string.yt_builder_side_padding), f.padding.start, 0f..48f, { v -> feed { it.copy(padding = it.padding.copy(start = v, end = v)) } }, state::seal, unit = " dp")
    SwitchRow(stringResource(R.string.yt_builder_chips), f.chips) { on -> feed { it.copy(chips = on) } }
    Label(stringResource(R.string.yt_builder_bar))
    val n = doc.nav
    fun nav(change: (YtNavConfig) -> YtNavConfig) = state.run("bar", "bar") { d -> d.copy(nav = change(d.nav)) }
    SwitchRow(stringResource(R.string.yt_builder_floating), n.floating) { on -> nav { it.copy(floating = on) } }
    YtSliderRow(stringResource(R.string.yt_builder_bar_height), n.height, 48f..96f, { v -> nav { it.copy(height = v) } }, state::seal, unit = " dp")
    if (n.floating) {
        YtSliderRow(stringResource(R.string.yt_builder_bar_side), n.side, 0f..48f, { v -> nav { it.copy(side = v) } }, state::seal, unit = " dp")
        YtSliderRow(stringResource(R.string.yt_builder_radius), n.radius, 0f..48f, { v -> nav { it.copy(radius = v) } }, state::seal, unit = " dp")
        YtSliderRow(stringResource(R.string.yt_builder_bar_bottom), n.bottom, 0f..32f, { v -> nav { it.copy(bottom = v) } }, state::seal, unit = " dp")
    }
    Label(stringResource(R.string.yt_builder_mini))
    val mini = doc.mini
    fun mini(change: (YtMiniConfig) -> YtMiniConfig) = state.run("mini", "mini") { d -> d.copy(mini = change(d.mini)) }
    YtSliderRow(stringResource(R.string.yt_builder_mini_height), mini.height, 48f..96f, { v -> mini { it.copy(height = v) } }, state::seal, unit = " dp")
    YtSliderRow(stringResource(R.string.yt_builder_radius), mini.radius, 0f..40f, { v -> mini { it.copy(radius = v) } }, state::seal, unit = " dp")
    YtSliderRow(stringResource(R.string.yt_builder_mini_picture), mini.thumbWidth, 48f..140f, { v -> mini { it.copy(thumbWidth = v) } }, state::seal, unit = " dp")
    YtSliderRow(stringResource(R.string.yt_builder_mini_picture_radius), mini.thumbRadius, 0f..32f, { v -> mini { it.copy(thumbRadius = v) } }, state::seal, unit = " dp")
    Text(stringResource(R.string.yt_builder_mini_note), color = PanelDim, fontSize = 11.sp)
    Label(stringResource(R.string.yt_builder_wordmark))
    OutlinedTextField(value = doc.meta.wordmark, onValueChange = { v -> state.run("wordmark", "wordmark") { d -> d.copy(meta = d.meta.copy(wordmark = v.take(40))) } }, singleLine = true, modifier = Modifier.fillMaxWidth())
    YtAnimThemeSection(state)
    Spacer(Modifier.height(24.dp))
}

/** The checks: contrast, small controls, layout conflicts; a tap selects the node. */
@Composable
fun YtWarningsPanel(state: YtEditorState, modifier: Modifier = Modifier) {
    val warnings = state.warnings
    val conflicts = state.conflicts.entries.flatMap { (_, list) -> list }
    Column(modifier.verticalScroll(rememberScrollState()).padding(14.dp)) {
        if (warnings.isEmpty() && conflicts.isEmpty()) {
            Text(stringResource(R.string.yt_builder_checks_ok), color = PanelText)
        }
        for (w in warnings) {
            val name = state.node(w.nodeId)?.name?.ifEmpty { null } ?: w.nodeId
            val text = when (w.kind) {
                YtEditorChecks.Kind.CONTRAST -> stringResource(R.string.yt_builder_check_contrast, name, "%.1f".format(Locale.ROOT, w.value))
                YtEditorChecks.Kind.TOUCH -> stringResource(R.string.yt_builder_check_touch, name, w.value.roundToInt())
                YtEditorChecks.Kind.LAYOUT -> name
            }
            Text(text, color = Color(0xFFFFC46B), fontSize = 13.sp, modifier = Modifier.fillMaxWidth().clickable { state.selection = setOf(w.nodeId) }.padding(vertical = 6.dp))
        }
        for (c in conflicts.distinctBy { it.nodeId to it.kind }) {
            val name = state.node(c.nodeId)?.name?.ifEmpty { null } ?: c.nodeId
            Text(stringResource(R.string.yt_builder_check_layout, name, conflictText(c.kind)), color = Color(0xFFFF8A80), fontSize = 13.sp, modifier = Modifier.fillMaxWidth().clickable { state.selection = setOf(c.nodeId) }.padding(vertical = 6.dp))
        }
    }
}

@Composable
private fun conflictText(kind: YtLayoutEngine.ConflictKind): String = stringResource(
    when (kind) {
        YtLayoutEngine.ConflictKind.MIN_ABOVE_MAX -> R.string.yt_builder_conflict_minmax
        YtLayoutEngine.ConflictKind.FILL_IN_HUG -> R.string.yt_builder_conflict_fill
        YtLayoutEngine.ConflictKind.PERCENT_IN_HUG -> R.string.yt_builder_conflict_percent
        YtLayoutEngine.ConflictKind.ASPECT_OVERCONSTRAINED -> R.string.yt_builder_conflict_aspect
        YtLayoutEngine.ConflictKind.ANCHOR_IN_HUG -> R.string.yt_builder_conflict_anchor
        YtLayoutEngine.ConflictKind.GRID_WITHOUT_WIDTH -> R.string.yt_builder_conflict_grid
        YtLayoutEngine.ConflictKind.OVERFLOW -> R.string.yt_builder_conflict_overflow
    },
)
