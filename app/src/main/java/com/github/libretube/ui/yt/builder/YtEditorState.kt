package com.github.libretube.ui.yt.builder

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.github.libretube.helpers.YtDesign
import com.github.libretube.helpers.YtThemeRefresh
import com.github.libretube.ui.yt.builder.YtDocOps.withComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the canvas shows: one component, or the whole home screen with all of them. */
enum class YtEditorTarget(val kind: YtComponentKind?) {
    SCREEN(null), CARD(YtComponentKind.CARD), HEADER(YtComponentKind.HEADER), CHIP(YtComponentKind.CHIP),
    NAV_BAR(YtComponentKind.NAV_BAR), NAV_TAB(YtComponentKind.NAV_TAB), MINI(YtComponentKind.MINI),
}

/** The window the preview simulates. */
enum class YtPreviewSize(val widthDp: Float, val heightDp: Float) {
    PHONE(393f, 852f), MEDIUM(700f, 1000f), EXPANDED(1000f, 720f), LANDSCAPE(852f, 393f),
}

/**
 * YT: the theme editor's state. The document lives in [doc] (snapshot state, the canvas renders
 * from it) and in [history] (undo/redo). Every change goes through [run]; saving is debounced
 * ([SAVE_DELAY_MS] after the last change, on IO, atomic) and forced when the editor leaves, never
 * on finger moves (a drag commits once, on release). While the active theme is edited, the app's
 * screens show the edits at once (YtBuilderActive.preview).
 */
class YtEditorState(app: Application) : AndroidViewModel(app) {
    private val context get() = getApplication<Application>()

    var doc by mutableStateOf<YtThemeDoc?>(null)
        private set
    private var history: YtHistory? = null
    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set

    var themes by mutableStateOf<List<YtBuilderStore.Entry>>(emptyList())
        private set
    var activeId by mutableStateOf<String?>(null)
        private set

    var target by mutableStateOf(YtEditorTarget.SCREEN)
    var selection by mutableStateOf<Set<String>>(emptySet())
    var size by mutableStateOf(YtPreviewSize.PHONE)
    var fontScale by mutableFloatStateOf(1f)
    var night by mutableStateOf(true)
    var advanced by mutableStateOf(false)

    /** Edit the "selected" state of chips and tabs instead of their normal look. */
    var editState by mutableStateOf(false)

    // canvas transform (screen px): zoom around the top left, then pan
    var zoom by mutableFloatStateOf(1f)
    var pan by mutableStateOf(Offset.Zero)

    // overlays and snapping
    var showGrid by mutableStateOf(false)
    var showRulers by mutableStateOf(false)
    var showBounds by mutableStateOf(true)
    var showSafe by mutableStateOf(false)
    var showGuides by mutableStateOf(true)
    var showStats by mutableStateOf(false)
    var haptics by mutableStateOf(true)
    var snapKinds by mutableStateOf(YtSnap.Kind.entries.toSet())
    var gridDp by mutableFloatStateOf(8f)
    val guidesX = mutableStateListOf<Float>()
    val guidesY = mutableStateListOf<Float>()

    /** The eyedropper: the next tap on the preview picks a colour for this callback. */
    var pick by mutableStateOf<((Int) -> Unit)?>(null)

    /** Recently used colours, newest first. */
    val recent = mutableStateListOf<Int>()

    /** A short note for the user (import notes, errors); shown once. */
    var note by mutableStateOf<String?>(null)

    /** Counts layout changes the checks should look at again. */
    var layoutVersion by mutableIntStateOf(0)

    val conflicts = HashMap<String, List<YtLayoutEngine.Conflict>>()

    /** Where a node is drawn on the canvas (frame px), set by the canvas. */
    var boundsOf: (String) -> androidx.compose.ui.geometry.Rect? = { null }

    /** The last check's findings (contrast, touch size, layout), drawn on the canvas and listed. */
    var warnings by mutableStateOf<List<YtEditorChecks.Warning>>(emptyList())

    private var saveJob: Job? = null
    private var dirty = false

    /** The active theme's look (light/dark, base design) changed: the activities are rebuilt once saved. */
    private var rebuild = false

    val isActiveTheme: Boolean get() = doc?.id != null && doc?.id == activeId

    fun open(id: String?) {
        viewModelScope.launch {
            val (list, loaded, active) = withContext(Dispatchers.IO) {
                var list = YtBuilderStore.list(context)
                if (list.isEmpty()) {
                    val first = YtBuilderStore.create(context, YtBuilderBase.forNew(), context.getString(com.github.libretube.R.string.yt_builder_default_name))
                    YtBuilderStore.setActive(context, first)
                    list = YtBuilderStore.list(context)
                }
                val wanted = id ?: YtBuilderStore.activeId() ?: list.first().id
                val read = YtBuilderStore.load(context, wanted)
                Triple(list, read, YtBuilderStore.activeId())
            }
            themes = list
            activeId = active
            when (loaded) {
                is YtThemeCodec.Read.Ok -> {
                    show(loaded.doc)
                    if (loaded.issues.isNotEmpty()) note = context.getString(com.github.libretube.R.string.yt_builder_repaired, loaded.issues.size)
                }
                is YtThemeCodec.Read.Failed -> note = context.getString(com.github.libretube.R.string.yt_builder_load_failed, loaded.reason)
            }
        }
    }

    private fun show(d: YtThemeDoc) {
        history = YtHistory(d)
        doc = d
        selection = emptySet()
        night = d.mode == YtThemeMode.DARK || com.github.libretube.helpers.YtDesignDark.active
        refreshFlags()
    }

    private fun refreshFlags() {
        canUndo = history?.canUndo == true
        canRedo = history?.canRedo == true
    }

    /** Applies [command] to the document; [coalesce] merges quick steps of one control. */
    fun run(label: String, coalesce: String? = null, change: (YtThemeDoc) -> YtThemeDoc) {
        val h = history ?: return
        val before = h.current
        val next = h.run(YtCommand(label, coalesce, change))
        if (next === before) return
        if (isActiveTheme && (next.mode != before.mode || next.base != before.base || next.meta.wordmark != before.meta.wordmark)) rebuild = true
        changed(next)
    }

    /** Changes the selected nodes (in the target component, or wherever they are). */
    fun edit(label: String, coalesce: String? = null, change: (YtNode) -> YtNode) {
        val ids = selection
        if (ids.isEmpty()) return
        run(label, coalesce?.let { "$it:${ids.joinToString()}" }) { d ->
            var out = d
            for (kind in YtComponentKind.entries) out = out.withComponent(kind) { YtDocOps.updateAll(it, ids, change) }
            out
        }
    }

    /** Changes the one node [id], wherever it is. */
    fun editNode(id: String, label: String, coalesce: String? = null, change: (YtNode) -> YtNode) {
        run(label, coalesce?.let { "$it:$id" }) { d ->
            var out = d
            for (kind in YtComponentKind.entries) out = out.withComponent(kind) { YtDocOps.update(it, id, change) }
            out
        }
    }

    fun undo() {
        history?.undo()?.let(::changed)
    }

    fun redo() {
        history?.redo()?.let(::changed)
    }

    /** Ends merging, e.g. when a slider is let go. */
    fun seal() = history?.seal()

    private fun changed(next: YtThemeDoc) {
        doc = next
        refreshFlags()
        if (isActiveTheme) YtBuilderActive.preview(next, YtBuilderStore.assets(context, next.id))
        dirty = true
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(SAVE_DELAY_MS)
            saveNow()
        }
    }

    /** Saves at once if anything changed (on leaving the editor, before export). */
    fun flush() {
        saveJob?.cancel()
        saveJob = null
        if (!dirty) return
        val d = doc ?: return
        dirty = false
        val stamped = d.copy(meta = d.meta.copy(modified = System.currentTimeMillis()))
        // a short synchronous write: the process may be about to go
        runCatching { YtBuilderStore.save(context, stamped) }
        if (stamped.id == activeId) published(stamped)
    }

    private suspend fun saveNow() {
        val d = doc ?: return
        if (!dirty) return
        dirty = false
        val stamped = d.copy(meta = d.meta.copy(modified = System.currentTimeMillis()))
        val ok = withContext(Dispatchers.IO) { runCatching { YtBuilderStore.save(context, stamped) }.isSuccess }
        if (!ok) {
            dirty = true
            note = context.getString(com.github.libretube.R.string.yt_builder_save_failed)
        } else if (stamped.id == activeId) {
            published(stamped)
        }
    }

    /** The saved active theme becomes the app's; a changed look rebuilds the screens (YtThemeRefresh). */
    private fun published(doc: YtThemeDoc) {
        YtBuilderActive.set(doc, YtBuilderStore.assets(context, doc.id))
        if (rebuild && YtDesign.current() == YtDesign.CUSTOM) YtThemeRefresh.bump()
        rebuild = false
    }

    override fun onCleared() {
        flush()
        super.onCleared()
    }

    // ---- themes ----

    fun reloadList() {
        viewModelScope.launch {
            themes = withContext(Dispatchers.IO) { YtBuilderStore.list(context) }
            activeId = YtBuilderStore.activeId()
        }
    }

    fun switchTo(id: String) {
        flush()
        open(id)
    }

    fun newTheme(base: YtDesign, name: String) {
        flush()
        viewModelScope.launch {
            val d = withContext(Dispatchers.IO) { YtBuilderStore.create(context, base, name) }
            reloadList()
            show(d)
        }
    }

    fun duplicate(name: String) {
        val d = doc ?: return
        flush()
        viewModelScope.launch {
            val copy = withContext(Dispatchers.IO) { YtBuilderStore.duplicate(context, d.id, name) } ?: return@launch
            reloadList()
            show(copy)
        }
    }

    fun rename(name: String) = run("rename") { it.copy(name = name.take(80).ifBlank { it.name }) }

    fun delete(id: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { YtBuilderStore.delete(context, id) }
            if (doc?.id == id) {
                doc = null
                history = null
                open(null)
            } else {
                reloadList()
            }
        }
    }

    /** Makes the edited theme the one "Eigenes" draws, and picks "Eigenes" as the design. */
    fun activate() {
        val d = doc ?: return
        flush()
        viewModelScope.launch {
            withContext(Dispatchers.IO) { YtBuilderStore.save(context, d) }
            YtBuilderStore.setActive(context, d)
            activeId = d.id
            if (YtDesign.current() != YtDesign.CUSTOM) {
                com.github.libretube.helpers.PreferenceHelper.putString(YtDesign.PREF_KEY, YtDesign.CUSTOM.key)
            }
            YtThemeRefresh.bump()
            note = context.getString(com.github.libretube.R.string.yt_builder_activated)
        }
    }

    fun snapshot() {
        val d = doc ?: return
        viewModelScope.launch {
            withContext(Dispatchers.IO) { YtBuilderStore.snapshot(context, d) }
            note = context.getString(com.github.libretube.R.string.yt_builder_version_saved)
        }
    }

    suspend fun versions(): List<YtBuilderStore.Version> {
        val id = doc?.id ?: return emptyList()
        return withContext(Dispatchers.IO) { YtBuilderStore.versions(context, id) }
    }

    fun restore(version: YtBuilderStore.Version) {
        val id = doc?.id ?: return
        viewModelScope.launch {
            val old = withContext(Dispatchers.IO) { YtBuilderStore.loadVersion(context, id, version.file) } ?: return@launch
            // the state before the restore stays one undo away
            run("restore") { old }
        }
    }

    fun export(uri: Uri) {
        flush()
        val d = doc ?: return
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) { YtBuilderStore.export(context, d, uri) }
            note = context.getString(if (ok) com.github.libretube.R.string.yt_builder_exported else com.github.libretube.R.string.yt_builder_export_failed)
        }
    }

    fun import(uri: Uri) {
        flush()
        viewModelScope.launch {
            when (val r = withContext(Dispatchers.IO) { YtBuilderStore.import(context, uri) }) {
                is YtBuilderStore.ImportResult.Failed -> note = context.getString(com.github.libretube.R.string.yt_builder_import_failed, r.reason)
                is YtBuilderStore.ImportResult.Ok -> {
                    reloadList()
                    show(r.doc)
                    note = if (r.notes.isEmpty()) {
                        context.getString(com.github.libretube.R.string.yt_builder_imported)
                    } else {
                        context.getString(com.github.libretube.R.string.yt_builder_imported_notes, r.notes.take(6).joinToString("\n"))
                    }
                }
            }
        }
    }

    fun importFont(uri: Uri, name: String?, apply: (String) -> Unit) {
        val d = doc ?: return
        viewModelScope.launch {
            when (val r = withContext(Dispatchers.IO) { YtBuilderStore.importFont(context, d, uri, name) }) {
                is YtBuilderStore.AssetResult.Failed -> note = context.getString(com.github.libretube.R.string.yt_builder_font_failed, r.reason)
                is YtBuilderStore.AssetResult.Ok -> {
                    run("font") { it.copy(assets = r.doc.assets) }
                    apply(YtFontKeys.ASSET + r.file)
                }
            }
        }
    }

    fun remember(argb: Int) {
        recent.remove(argb)
        recent.add(0, argb)
        while (recent.size > 12) recent.removeAt(recent.lastIndex)
    }

    // ---- selection helpers ----

    /** The component the selected node is in. */
    fun kindOf(id: String): YtComponentKind? = doc?.let { d -> YtComponentKind.entries.firstOrNull { YtDocOps.find(d.components[it], id) != null } }

    fun node(id: String): YtNode? = doc?.let { d -> kindOf(id)?.let { YtDocOps.find(d.components[it], id) } }

    fun parentOf(id: String): YtNode? = doc?.let { d -> kindOf(id)?.let { YtDocOps.parentOf(d.components[it], id) } }

    val primary: YtNode? get() = selection.firstOrNull()?.let(::node)

    fun selectParent() {
        val p = selection.firstOrNull()?.let(::parentOf) ?: return
        selection = setOf(p.id)
    }

    /** Ids taken anywhere in the document, so new nodes never clash. */
    fun takenIds(): Set<String> = doc?.let { d -> YtComponentKind.entries.flatMap { YtDocOps.all(d.components[it]).map { n -> n.id } }.toSet() }.orEmpty()

    fun addChild(node: YtNode) {
        val parentId = primary?.let { if (it.type == YtNodeType.CONTAINER) it.id else parentOf(it.id)?.id } ?: return
        val kind = kindOf(parentId) ?: return
        val taken = takenIds()
        val fresh = YtDocOps.withFreshIds(node, taken)
        run("add") { d -> d.withComponent(kind) { YtDocOps.add(it, parentId, fresh, taken) } }
        selection = setOf(fresh.id)
    }

    fun deleteSelection() {
        val ids = selection
        run("delete") { d ->
            var out = d
            for (kind in YtComponentKind.entries) out = out.withComponent(kind) { root -> ids.fold(root) { r, id -> if (id == root.id) r else YtDocOps.remove(r, id) } }
            out
        }
        selection = emptySet()
    }

    fun duplicateSelection() {
        val id = selection.firstOrNull() ?: return
        val kind = kindOf(id) ?: return
        var newId: String? = null
        run("duplicate") { d ->
            d.withComponent(kind) { root ->
                val taken = takenIds()
                val node = YtDocOps.find(root, id) ?: return@withComponent root
                val parent = YtDocOps.parentOf(root, id) ?: return@withComponent root
                val copy = YtDocOps.withFreshIds(node, taken).let { if (parent.layout.mode == YtLayoutMode.FREE) it.copy(x = it.x + 8f, y = it.y + 8f) else it }
                newId = copy.id
                YtDocOps.update(root, parent.id) { p ->
                    val at = p.children.indexOfFirst { it.id == id }
                    p.copy(children = p.children.toMutableList().apply { add(at + 1, copy) })
                }
            }
        }
        newId?.let { selection = setOf(it) }
    }

    fun reorderSelection(step: Int) {
        val id = selection.firstOrNull() ?: return
        val kind = kindOf(id) ?: return
        run("order") { d -> d.withComponent(kind) { YtDocOps.reorder(it, id, step) } }
    }

    fun moveInFlow(id: String, index: Int) {
        val kind = kindOf(id) ?: return
        val parent = parentOf(id) ?: return
        run("move") { d -> d.withComponent(kind) { YtDocOps.moveTo(it, id, parent.id, index) } }
    }

    companion object {
        const val SAVE_DELAY_MS = 1200L
    }
}

/** YT: which design a first theme starts from: the one in use, else YouTube neu. */
internal object YtBuilderBase {
    fun forNew(): YtDesign = YtDesign.current().takeIf { it != YtDesign.STANDARD && it != YtDesign.CUSTOM } ?: YtDesign.YOUTUBE
}
