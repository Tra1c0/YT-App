package com.github.libretube.ui.yt.builder

import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.libretube.R
import com.github.libretube.api.obj.StreamItem
import com.github.libretube.db.DatabaseHolder
import com.github.libretube.db.obj.SubscriptionsFeedItem
import com.github.libretube.helpers.ImageHelper
import com.github.libretube.helpers.YtDesign
import com.github.libretube.ui.yt.YtDiscovery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * YT: the theme builder's editor (opened from Settings → Appearance → "Eigenes bearbeiten").
 * Its own window with the dark Material look, so a broken theme never breaks the editor that
 * repairs it. Saving: debounced while editing, at once when it goes to the background.
 */
class YtThemeEditorActivity : ComponentActivity() {
    private val state: YtEditorState by viewModels()
    private var metrics: YtEditorMetrics? = null
    private var fontDone: ((String) -> Unit)? = null

    private val pickFont = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) state.importFont(uri, displayName(uri)) { key -> fontDone?.invoke(key) }
    }
    private val exportTheme = registerForActivityResult(ActivityResultContracts.CreateDocument(YtThemeZip.MIME)) { uri ->
        if (uri != null) state.export(uri)
    }
    private val importTheme = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) state.import(uri)
    }

    private fun displayName(uri: Uri): String? = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (state.doc == null) state.open(intent.getStringExtra(EXTRA_ID))
        val m = YtEditorMetrics(window)
        metrics = m
        onBackPressedDispatcher.addCallback(this) {
            if (state.selection.isNotEmpty()) state.selection = emptySet() else finish()
        }
        setContentView(androidx.compose.ui.platform.ComposeView(this).apply { setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFF8AB4FF), surface = Color(0xFF17181C), background = Color(0xFF0F1013))) {
                YtEditorScreen(
                    state = state,
                    metrics = m,
                    onBack = { if (state.selection.isNotEmpty()) state.selection = emptySet() else finish() },
                    onImportFont = { done ->
                        fontDone = done
                        pickFont.launch(arrayOf("font/ttf", "font/otf", "application/x-font-ttf", "application/octet-stream", "*/*"))
                    },
                    onExport = { exportTheme.launch((state.doc?.name ?: "theme").replace(Regex("[^A-Za-z0-9_-]"), "_") + "." + YtThemeZip.EXTENSION) },
                    onImport = { importTheme.launch(arrayOf(YtThemeZip.MIME, "application/octet-stream", "*/*")) },
                )
            }
        } })
    }

    override fun onResume() {
        super.onResume()
        metrics?.resume()
    }

    override fun onPause() {
        metrics?.pause()
        state.flush()
        super.onPause()
    }

    override fun onDestroy() {
        metrics?.release()
        metrics = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_ID = "yt_theme_id"
        const val EXTRA_SAFE = "yt_theme_safe"
    }
}

@Composable
private fun YtEditorScreen(
    state: YtEditorState,
    metrics: YtEditorMetrics,
    onBack: () -> Unit,
    onImportFont: ((String) -> Unit) -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val doc = state.doc
    val hooks = remember(state) { YtEditorHooks(state) }
    state.boundsOf = { id -> hooks.bounds(id) }
    val imageLoader = remember { ImageHelper.loader() }
    val videos by produceState(emptyList<StreamItem>()) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                DatabaseHolder.Database.feedDao().getAll().map(SubscriptionsFeedItem::toStreamItem)
                    .filter { !it.isShort && it.url != null }.sortedByDescending { it.uploaded }.take(12)
            }.getOrNull().orEmpty().ifEmpty { YtDiscovery.current().orEmpty().filter { !it.isShort }.take(12) }
        }
    }
    val palette = remember(doc, state.night) { doc?.let { YtBuilderActive.paletteOf(it, state.night, YtBuilderStore.assets(context, it.id)) } }
    var panel by remember { mutableStateOf(true) }
    var checks by remember { mutableStateOf(false) }
    var themesOpen by remember { mutableStateOf(false) }

    // the checks follow the document, a little after the last change
    LaunchedEffect(doc, state.night, state.size, state.fontScale) {
        val d = doc ?: return@LaunchedEffect
        delay(500)
        val contrast = YtEditorChecks.contrast(d, state.night)
        val touch = YtEditorChecks.touch(d, { k, id -> hooks.bounds(k, id)?.let { YtSnap.Rect(it.left, it.top, it.right, it.bottom) } }, hooks.themeDensity)
        state.warnings = contrast + touch
    }
    LaunchedEffect(state.showStats) {
        while (state.showStats) {
            metrics.refresh()
            delay(500)
        }
    }

    Column(Modifier.fillMaxSize().background(Color(0xFF0F1013)).systemBarsPadding().imePadding()) {
        YtEditorTopBar(state, onBack, onThemes = { themesOpen = true; state.reloadList() })
        if (state.selection.isNotEmpty()) YtEditorSelectionBar(state)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (doc != null && palette != null) {
                YtEditorCanvas(state, hooks, palette, videos, imageLoader, metrics.line, Modifier.fillMaxSize())
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().background(Color(0xFF17181C)).padding(horizontal = 8.dp),
        ) {
            FilterChip(selected = panel && !checks, onClick = { if (panel && !checks) panel = false else { panel = true; checks = false } }, label = { Text(stringResource(R.string.yt_builder_tools)) })
            Spacer(Modifier.padding(4.dp))
            val count = state.warnings.size + state.conflicts.size
            FilterChip(selected = panel && checks, onClick = { if (panel && checks) panel = false else { panel = true; checks = true } }, label = { Text(stringResource(R.string.yt_builder_checks, count)) })
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { YtEditorViewActions.fit() }) { Text(stringResource(R.string.yt_builder_fit)) }
            TextButton(onClick = { YtEditorViewActions.actual() }) { Text("100 %") }
        }
        if (panel) {
            val height = (LocalConfiguration.current.screenHeightDp * 0.38f).dp
            Box(Modifier.fillMaxWidth().height(height).background(Color(0xFF17181C))) {
                if (checks) YtWarningsPanel(state, Modifier.fillMaxSize()) else YtEditorPanel(state, onImportFont, Modifier.fillMaxSize())
            }
        }
    }

    if (themesOpen) YtThemesDialog(state, onDismiss = { themesOpen = false }, onExport = onExport, onImport = onImport)
    state.note?.let { note ->
        AlertDialog(
            onDismissRequest = { state.note = null },
            confirmButton = { TextButton(onClick = { state.note = null }) { Text(stringResource(android.R.string.ok)) } },
            text = { Text(note) },
        )
    }
}

@Composable
private fun YtEditorTopBar(state: YtEditorState, onBack: () -> Unit, onThemes: () -> Unit) {
    var menu by remember { mutableStateOf<String?>(null) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().background(Color(0xFF17181C)).horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp, vertical = 2.dp),
    ) {
        TextButton(onClick = onBack) { Text("←", fontSize = 18.sp) }
        TextButton(onClick = onThemes) {
            Text((state.doc?.name ?: "…") + if (state.isActiveTheme) " ●" else "", maxLines = 1)
        }
        TextButton(onClick = state::undo, enabled = state.canUndo) { Text(stringResource(R.string.yt_builder_undo)) }
        TextButton(onClick = state::redo, enabled = state.canRedo) { Text(stringResource(R.string.yt_builder_redo)) }
        Box {
            TextButton(onClick = { menu = "target" }) { Text(targetLabel(state.target)) }
            DropdownMenu(expanded = menu == "target", onDismissRequest = { menu = null }) {
                for (t in YtEditorTarget.entries) DropdownMenuItem(text = { Text(targetLabel(t)) }, onClick = { state.target = t; state.selection = emptySet(); menu = null })
            }
        }
        Box {
            TextButton(onClick = { menu = "preview" }) { Text(stringResource(R.string.yt_builder_preview)) }
            DropdownMenu(expanded = menu == "preview", onDismissRequest = { menu = null }) {
                for (s in YtPreviewSize.entries) DropdownMenuItem(text = { Text((if (s == state.size) "● " else "") + sizeLabel(s)) }, onClick = { state.size = s; menu = null })
                HorizontalDivider()
                for (f in listOf(1f, 1.3f, 2f)) DropdownMenuItem(text = { Text((if (f == state.fontScale) "● " else "") + stringResource(R.string.yt_builder_font_scale, f.toString())) }, onClick = { state.fontScale = f; menu = null })
                HorizontalDivider()
                DropdownMenuItem(text = { Text((if (state.night) "● " else "") + stringResource(R.string.yt_builder_dark)) }, onClick = { state.night = true; menu = null })
                DropdownMenuItem(text = { Text((if (!state.night) "● " else "") + stringResource(R.string.yt_builder_light)) }, onClick = { state.night = false; menu = null })
            }
        }
        Box {
            TextButton(onClick = { menu = "view" }) { Text(stringResource(R.string.yt_builder_view)) }
            DropdownMenu(expanded = menu == "view", onDismissRequest = { menu = null }) {
                @Composable
                fun toggle(label: Int, on: Boolean, set: (Boolean) -> Unit) = DropdownMenuItem(text = { Text((if (on) "☑ " else "☐ ") + stringResource(label)) }, onClick = { set(!on) })
                toggle(R.string.yt_builder_grid, state.showGrid) { state.showGrid = it }
                toggle(R.string.yt_builder_rulers, state.showRulers) { state.showRulers = it }
                toggle(R.string.yt_builder_guides, state.showGuides) { state.showGuides = it }
                toggle(R.string.yt_builder_bounds, state.showBounds) { state.showBounds = it }
                toggle(R.string.yt_builder_safe_area, state.showSafe) { state.showSafe = it }
                toggle(R.string.yt_builder_stats, state.showStats) { state.showStats = it }
                toggle(R.string.yt_builder_haptics, state.haptics) { state.haptics = it }
                HorizontalDivider()
                for (k in YtSnap.Kind.entries) toggle(snapLabel(k), k in state.snapKinds) { on -> state.snapKinds = if (on) state.snapKinds + k else state.snapKinds - k }
                HorizontalDivider()
                DropdownMenuItem(text = { Text(stringResource(R.string.yt_builder_guide_add_v)) }, onClick = { state.guidesX.add(state.size.widthDp / 2f); menu = null })
                DropdownMenuItem(text = { Text(stringResource(R.string.yt_builder_guide_add_h)) }, onClick = { state.guidesY.add(200f); menu = null })
                if (state.guidesX.isNotEmpty() || state.guidesY.isNotEmpty()) DropdownMenuItem(text = { Text(stringResource(R.string.yt_builder_guide_clear)) }, onClick = { state.guidesX.clear(); state.guidesY.clear(); menu = null })
            }
        }
        FilterChip(selected = state.advanced, onClick = { state.advanced = !state.advanced }, label = { Text(stringResource(if (state.advanced) R.string.yt_builder_advanced else R.string.yt_builder_simple)) })
    }
}

/** What can be done with the selection: up to the parent, order, copy, delete, nudge. */
@Composable
private fun YtEditorSelectionBar(state: YtEditorState) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().background(Color(0xFF1D1F24)).horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        TextButton(onClick = state::selectParent) { Text(stringResource(R.string.yt_builder_parent)) }
        TextButton(onClick = { state.reorderSelection(1) }) { Text(stringResource(R.string.yt_builder_forward)) }
        TextButton(onClick = { state.reorderSelection(-1) }) { Text(stringResource(R.string.yt_builder_backward)) }
        TextButton(onClick = state::duplicateSelection) { Text(stringResource(R.string.yt_builder_duplicate)) }
        TextButton(onClick = state::deleteSelection) { Text(stringResource(R.string.yt_builder_delete)) }
        val free = state.selection.firstOrNull()?.let(state::parentOf)?.layout?.mode == YtLayoutMode.FREE
        if (free) {
            for ((label, dx, dy) in listOf(Triple("◀", -1f, 0f), Triple("▲", 0f, -1f), Triple("▼", 0f, 1f), Triple("▶", 1f, 0f))) {
                TextButton(onClick = { state.edit("nudge", "nudge") { it.copy(x = it.x + if (it.anchorH == YtAnchor.END) -dx else dx, y = it.y + if (it.anchorV == YtAnchor.END) -dy else dy) } }) { Text(label) }
            }
        }
    }
}

@Composable
private fun targetLabel(t: YtEditorTarget): String = stringResource(
    when (t) {
        YtEditorTarget.SCREEN -> R.string.yt_builder_target_screen
        YtEditorTarget.CARD -> R.string.yt_builder_target_card
        YtEditorTarget.HEADER -> R.string.yt_builder_target_header
        YtEditorTarget.CHIP -> R.string.yt_builder_target_chip
        YtEditorTarget.NAV_BAR -> R.string.yt_builder_target_bar
        YtEditorTarget.NAV_TAB -> R.string.yt_builder_target_tab
        YtEditorTarget.MINI -> R.string.yt_builder_target_mini
    },
)

@Composable
private fun sizeLabel(s: YtPreviewSize): String = stringResource(
    when (s) {
        YtPreviewSize.PHONE -> R.string.yt_builder_size_phone
        YtPreviewSize.MEDIUM -> R.string.yt_builder_size_medium
        YtPreviewSize.EXPANDED -> R.string.yt_builder_size_expanded
        YtPreviewSize.LANDSCAPE -> R.string.yt_builder_size_landscape
    },
)

private fun snapLabel(k: YtSnap.Kind): Int = when (k) {
    YtSnap.Kind.CONTAINER -> R.string.yt_builder_snap_container
    YtSnap.Kind.EDGE -> R.string.yt_builder_snap_edges
    YtSnap.Kind.CENTER -> R.string.yt_builder_snap_centers
    YtSnap.Kind.GUIDE -> R.string.yt_builder_snap_guides
    YtSnap.Kind.SPACING -> R.string.yt_builder_snap_spacing
    YtSnap.Kind.GRID -> R.string.yt_builder_snap_grid
}

/** The themes: open, new, duplicate, rename, delete, use, versions, export and import. */
@Composable
private fun YtThemesDialog(state: YtEditorState, onDismiss: () -> Unit, onExport: () -> Unit, onImport: () -> Unit) {
    var page by remember { mutableStateOf("list") }
    var name by remember { mutableStateOf("") }
    var base by remember { mutableStateOf(YtBuilderBase.forNew()) }
    val scope = rememberCoroutineScope()
    var versions by remember { mutableStateOf<List<YtBuilderStore.Version>>(emptyList()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.yt_builder_close)) } },
        title = { Text(stringResource(R.string.yt_builder_themes)) },
        text = {
            when (page) {
                "list" -> Column {
                    LazyColumn(Modifier.height(220.dp)) {
                        items(state.themes, key = { it.id }) { e ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth().clickable { state.switchTo(e.id); onDismiss() }.padding(vertical = 8.dp),
                            ) {
                                Text((if (e.id == state.activeId) "● " else "○ ") + e.name, modifier = Modifier.weight(1f))
                                if (e.id == state.doc?.id) Text(stringResource(R.string.yt_builder_open_now), color = Color(0xFF9EA2AD), fontSize = 12.sp)
                            }
                        }
                    }
                    HorizontalDivider()
                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                        TextButton(onClick = { name = ""; page = "new" }) { Text(stringResource(R.string.yt_builder_new)) }
                        TextButton(onClick = { name = state.doc?.name.orEmpty(); page = "rename" }) { Text(stringResource(R.string.yt_builder_rename)) }
                        TextButton(onClick = { state.duplicate((state.doc?.name ?: "") + " 2"); onDismiss() }) { Text(stringResource(R.string.yt_builder_duplicate)) }
                        TextButton(onClick = { page = "delete" }) { Text(stringResource(R.string.yt_builder_delete)) }
                    }
                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                        TextButton(onClick = { state.activate(); onDismiss() }) { Text(stringResource(R.string.yt_builder_use)) }
                        TextButton(onClick = { state.snapshot() }) { Text(stringResource(R.string.yt_builder_save_version)) }
                        TextButton(onClick = { scope.launch { versions = state.versions(); page = "versions" } }) { Text(stringResource(R.string.yt_builder_versions)) }
                    }
                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                        TextButton(onClick = { onExport(); onDismiss() }) { Text(stringResource(R.string.yt_builder_export)) }
                        TextButton(onClick = { onImport(); onDismiss() }) { Text(stringResource(R.string.yt_builder_import)) }
                    }
                }
                "new" -> Column {
                    OutlinedTextField(value = name, onValueChange = { name = it.take(80) }, label = { Text(stringResource(R.string.yt_builder_name)) }, singleLine = true)
                    Text(stringResource(R.string.yt_builder_start_from), modifier = Modifier.padding(top = 10.dp))
                    LazyColumn(Modifier.height(200.dp)) {
                        items(YtDesign.entries.filter { it != YtDesign.STANDARD && it != YtDesign.CUSTOM }) { d ->
                            Text((if (d == base) "● " else "○ ") + d.key, modifier = Modifier.fillMaxWidth().clickable { base = d }.padding(vertical = 6.dp))
                        }
                    }
                    TextButton(onClick = { state.newTheme(base, name.ifBlank { d(base) }); onDismiss() }) { Text(stringResource(R.string.yt_builder_create)) }
                }
                "rename" -> Column {
                    OutlinedTextField(value = name, onValueChange = { name = it.take(80) }, label = { Text(stringResource(R.string.yt_builder_name)) }, singleLine = true)
                    TextButton(onClick = { state.rename(name); page = "list" }) { Text(stringResource(android.R.string.ok)) }
                }
                "delete" -> Column {
                    Text(stringResource(R.string.yt_builder_delete_ask, state.doc?.name.orEmpty()))
                    Row {
                        TextButton(onClick = { page = "list" }) { Text(stringResource(android.R.string.cancel)) }
                        TextButton(onClick = { state.doc?.id?.let(state::delete); page = "list" }) { Text(stringResource(R.string.yt_builder_delete)) }
                    }
                }
                "versions" -> Column {
                    if (versions.isEmpty()) Text(stringResource(R.string.yt_builder_no_versions))
                    LazyColumn(Modifier.height(240.dp)) {
                        items(versions, key = { it.file }) { v ->
                            Text(
                                com.github.libretube.util.YtCrashLog.formatTime(v.at) + " · " + v.name,
                                modifier = Modifier.fillMaxWidth().clickable { state.restore(v); onDismiss() }.padding(vertical = 8.dp),
                            )
                        }
                    }
                    TextButton(onClick = { page = "list" }) { Text(stringResource(R.string.yt_builder_back)) }
                }
            }
        },
    )
}

private fun d(design: YtDesign) = design.key.replaceFirstChar { it.uppercase() }
