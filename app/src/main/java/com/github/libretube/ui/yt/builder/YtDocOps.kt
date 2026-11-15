package com.github.libretube.ui.yt.builder

/**
 * YT: edits of a theme document as pure functions on its immutable trees: find, change, add,
 * remove, duplicate, reorder and move nodes. Untouched branches are shared, so a change makes a
 * new document cheaply and an equality check tells which nodes changed (the renderer recomposes
 * only those).
 */
object YtDocOps {
    fun find(root: YtNode, id: String): YtNode? {
        if (root.id == id) return root
        for (child in root.children) find(child, id)?.let { return it }
        return null
    }

    /** The parent of [id] under [root], or null for the root itself or a missing id. */
    fun parentOf(root: YtNode, id: String): YtNode? {
        for (child in root.children) {
            if (child.id == id) return root
            parentOf(child, id)?.let { return it }
        }
        return null
    }

    /** Ids from the root down to [id] (both included), or empty when it is missing. */
    fun pathTo(root: YtNode, id: String): List<String> {
        if (root.id == id) return listOf(root.id)
        for (child in root.children) {
            val path = pathTo(child, id)
            if (path.isNotEmpty()) return listOf(root.id) + path
        }
        return emptyList()
    }

    fun all(root: YtNode): List<YtNode> = buildList {
        fun walk(n: YtNode) {
            add(n)
            n.children.forEach(::walk)
        }
        walk(root)
    }

    /** [root] with the node [id] replaced by [change] of it; the same object when nothing changed. */
    fun update(root: YtNode, id: String, change: (YtNode) -> YtNode): YtNode {
        if (root.id == id) return change(root)
        var changed = false
        val children = root.children.map { child ->
            val next = update(child, id, change)
            if (next !== child) changed = true
            next
        }
        return if (changed) root.copy(children = children) else root
    }

    fun updateAll(root: YtNode, ids: Set<String>, change: (YtNode) -> YtNode): YtNode {
        val self = if (root.id in ids) change(root) else root
        var changed = self !== root
        val children = self.children.map { child ->
            val next = updateAll(child, ids, change)
            if (next !== child) changed = true
            next
        }
        return if (changed) self.copy(children = children) else root
    }

    fun remove(root: YtNode, id: String): YtNode {
        if (root.children.none { it.id == id }) {
            var changed = false
            val children = root.children.map { child ->
                val next = remove(child, id)
                if (next !== child) changed = true
                next
            }
            return if (changed) root.copy(children = children) else root
        }
        return root.copy(children = root.children.filter { it.id != id })
    }

    /** Adds [node] as the last child of [parentId] (with a fresh id where it is taken). */
    fun add(root: YtNode, parentId: String, node: YtNode, taken: Set<String> = all(root).map { it.id }.toSet()): YtNode {
        val fresh = withFreshIds(node, taken)
        return update(root, parentId) { it.copy(children = it.children + fresh) }
    }

    /** A copy of [id] right after it, every id in it new, a little offset when its parent is free. */
    fun duplicate(root: YtNode, id: String): Pair<YtNode, String?> {
        val node = find(root, id) ?: return root to null
        val parent = parentOf(root, id) ?: return root to null
        val copy = withFreshIds(node, all(root).map { it.id }.toSet()).let {
            if (parent.layout.mode == YtLayoutMode.FREE) it.copy(x = it.x + 8f, y = it.y + 8f) else it
        }
        val next = update(root, parent.id) { p ->
            val at = p.children.indexOfFirst { it.id == id }
            p.copy(children = p.children.toMutableList().apply { add(at + 1, copy) })
        }
        return next to copy.id
    }

    /** Moves [id] one step towards the front (+1) or the back (-1) among its siblings. */
    fun reorder(root: YtNode, id: String, step: Int): YtNode {
        val parent = parentOf(root, id) ?: return root
        val list = parent.children.toMutableList()
        val at = list.indexOfFirst { it.id == id }
        val to = (at + step).coerceIn(0, list.lastIndex)
        if (at < 0 || to == at) return root
        val n = list.removeAt(at)
        list.add(to, n)
        return update(root, parent.id) { it.copy(children = list) }
    }

    /**
     * Moves [id] into [newParentId] at [index] (clamped). Refused (same root back) when the new
     * parent is the node itself or inside it, or is not a container.
     */
    fun moveTo(root: YtNode, id: String, newParentId: String, index: Int): YtNode {
        val node = find(root, id) ?: return root
        if (find(node, newParentId) != null) return root
        val target = find(root, newParentId) ?: return root
        if (target.type != YtNodeType.CONTAINER) return root
        val without = remove(root, id)
        return update(without, newParentId) { p ->
            val list = p.children.toMutableList()
            list.add(index.coerceIn(0, list.size), node)
            p.copy(children = list)
        }
    }

    fun withFreshIds(node: YtNode, taken: Set<String>): YtNode {
        val used = taken.toMutableSet()
        fun fresh(base: String): String {
            val stem = base.trimEnd { it.isDigit() || it == '_' }.ifEmpty { "n" }.take(30)
            var i = 2
            var id = base
            while (id in used) id = stem + "_" + i++
            used += id
            return id
        }
        fun walk(n: YtNode): YtNode = n.copy(id = fresh(n.id), children = n.children.map(::walk))
        return walk(node)
    }

    /** The document with component [kind]'s root changed by [change]. */
    fun YtThemeDoc.withComponent(kind: YtComponentKind, change: (YtNode) -> YtNode): YtThemeDoc {
        val root = components[kind]
        val next = change(root)
        return if (next === root) this else copy(components = components.with(kind, next))
    }

    /** Finds which component holds node [id]. */
    fun YtThemeDoc.componentOf(id: String): YtComponentKind? =
        YtComponentKind.entries.firstOrNull { find(components[it], id) != null }
}
