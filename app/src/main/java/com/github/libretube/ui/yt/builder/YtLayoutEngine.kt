package com.github.libretube.ui.yt.builder

/**
 * YT: lays out one container of a theme (pure, px). The renderer calls it from a Compose Layout
 * with the container's constraints; [measure] measures a child once with the box it computed, so
 * every child is measured exactly once (as Compose requires). Size rules: FIXED, HUG, FILL (the
 * room left, by weight), PERCENT of the content box, min/max, aspect lock. Modes: FLOW_H, FLOW_V,
 * GRID, FREE (anchored x/y). Rules that cannot all hold are reported as conflicts, and the
 * layout falls back to something drawable (never throws, never measures twice).
 */
object YtLayoutEngine {
    const val INF = Float.POSITIVE_INFINITY

    data class Box(val minW: Float, val maxW: Float, val minH: Float, val maxH: Float) {
        val exactW: Boolean get() = minW == maxW
        val exactH: Boolean get() = minH == maxH

        companion object {
            fun exact(w: Float, h: Float) = Box(w, w, h, h)
            fun upTo(w: Float, h: Float) = Box(0f, w, 0f, h)
        }
    }

    data class Size(val w: Float, val h: Float)

    data class Place(val x: Float, val y: Float, val w: Float, val h: Float) {
        val right: Float get() = x + w
        val bottom: Float get() = y + h
    }

    enum class ConflictKind {
        /** A minimum above the maximum: the minimum wins. */
        MIN_ABOVE_MAX,
        /** FILL in a container that hugs its content along that side: treated as HUG. */
        FILL_IN_HUG,
        /** A percentage of a container without a size along that side: treated as HUG. */
        PERCENT_IN_HUG,
        /** Width, height and aspect all fixed: the width wins. */
        ASPECT_OVERCONSTRAINED,
        /** A centred or end anchor in a free container without a size: placed from the start. */
        ANCHOR_IN_HUG,
        /** A grid without a width: laid out as a column. */
        GRID_WITHOUT_WIDTH,
        /** The children need more room than the container has. */
        OVERFLOW,
    }

    data class Conflict(val nodeId: String, val kind: ConflictKind)

    /** Own size, one place per child (null: hidden), conflicts. */
    data class Result(val width: Float, val height: Float, val places: List<Place?>, val conflicts: List<Conflict>)

    fun layout(
        container: YtNode,
        children: List<YtNode>,
        box: Box,
        density: Float,
        measure: (index: Int, box: Box) -> Size,
    ): Result {
        val conflicts = mutableListOf<Conflict>()
        val padS = container.padding.start * density
        val padE = container.padding.end * density
        val padT = container.padding.top * density
        val padB = container.padding.bottom * density
        val contentW = if (box.maxW == INF) INF else (box.maxW - padS - padE).coerceAtLeast(0f)
        val contentH = if (box.maxH == INF) INF else (box.maxH - padT - padB).coerceAtLeast(0f)
        // only an exact box gives the content a size to fill; a hugging container offers its maximum
        val fillW = if (box.exactW) contentW else INF
        val fillH = if (box.exactH) contentH else INF
        val ctx = Ctx(density, conflicts)
        val mode = if (container.layout.mode == YtLayoutMode.GRID && fillW == INF && contentW == INF) {
            conflicts += Conflict(container.id, ConflictKind.GRID_WITHOUT_WIDTH)
            YtLayoutMode.FLOW_V
        } else {
            container.layout.mode
        }
        val inner = when (mode) {
            YtLayoutMode.FLOW_H -> flow(container, children, true, if (box.exactW) contentW else contentW, contentH, box.exactW, box.exactH, ctx, measure)
            YtLayoutMode.FLOW_V -> flow(container, children, false, contentW, contentH, box.exactW, box.exactH, ctx, measure)
            YtLayoutMode.GRID -> grid(container, children, if (contentW == INF) fillW else contentW, ctx, measure)
            YtLayoutMode.FREE -> free(container, children, fillW, fillH, contentW, contentH, ctx, measure)
        }
        val w = (if (box.exactW) box.maxW else inner.w + padS + padE).let { clampTo(it, box.minW, box.maxW) }
        val h = (if (box.exactH) box.maxH else inner.h + padT + padB).let { clampTo(it, box.minH, box.maxH) }
        if (inner.w > w - padS - padE + 0.5f || inner.h > h - padT - padB + 0.5f) conflicts += Conflict(container.id, ConflictKind.OVERFLOW)
        // FREE places against the final content box; flows and grids from the content start
        val places = if (mode == YtLayoutMode.FREE) {
            placeFree(children, inner.sizes, w - padS - padE, h - padT - padB, box.exactW, box.exactH, ctx).map { p -> p?.let { it.copy(x = it.x + padS, y = it.y + padT) } }
        } else {
            val justified = justify(container, children, inner, mode, w - padS - padE, h - padT - padB)
            justified.map { p -> p?.let { it.copy(x = it.x + padS, y = it.y + padT) } }
        }
        return Result(w, h, places, conflicts)
    }

    private fun clampTo(v: Float, min: Float, max: Float): Float = if (max < min) min else v.coerceIn(min, max)

    private class Ctx(val density: Float, val conflicts: MutableList<Conflict>)

    /** The laid-out content: its size, each child's size and offset from the content start (null: hidden). */
    private class Inner(val w: Float, val h: Float, val sizes: List<Size?>, val offsets: List<Pair<Float, Float>?>)

    /** Min and max along one side of a child, from its rule against [room] (INF: no size given). */
    private fun axis(node: YtNode, rule: YtSize, room: Float, horizontal: Boolean, ctx: Ctx): Pair<Float, Float> {
        val d = ctx.density
        var lo: Float
        var hi: Float
        when (rule.kind) {
            YtSizeKind.FIXED -> { lo = rule.value * d; hi = lo }
            YtSizeKind.PERCENT -> if (room == INF) {
                ctx.conflicts += Conflict(node.id, ConflictKind.PERCENT_IN_HUG)
                lo = 0f; hi = INF
            } else {
                lo = room * rule.value / 100f; hi = lo
            }
            YtSizeKind.FILL -> if (room == INF) {
                ctx.conflicts += Conflict(node.id, ConflictKind.FILL_IN_HUG)
                lo = 0f; hi = INF
            } else {
                lo = room; hi = room
            }
            YtSizeKind.HUG -> { lo = 0f; hi = room }
        }
        val min = (if (horizontal) node.minW else node.minH)?.times(d)
        val max = (if (horizontal) node.maxW else node.maxH)?.times(d)
        if (min != null && max != null && min > max) ctx.conflicts += Conflict(node.id, ConflictKind.MIN_ABOVE_MAX)
        if (max != null) { hi = minOf(hi, max); lo = minOf(lo, max) }
        if (min != null) { lo = maxOf(lo, min); hi = maxOf(hi, min) }
        return lo to hi
    }

    /** The child's box from its two sides and its aspect lock. */
    private fun childBox(node: YtNode, w: Pair<Float, Float>, h: Pair<Float, Float>, ctx: Ctx): Box {
        val aspect = node.aspect
        var (wl, wh) = w
        var (hl, hh) = h
        if (aspect != null && aspect > 0f) {
            val wExact = wl == wh
            val hExact = hl == hh
            when {
                wExact && hExact -> if (kotlin.math.abs(wl / aspect - hl) > 0.5f) {
                    ctx.conflicts += Conflict(node.id, ConflictKind.ASPECT_OVERCONSTRAINED)
                    hl = wl / aspect; hh = hl
                }
                wExact -> { hl = wl / aspect; hh = hl }
                hExact -> { wl = hl * aspect; wh = wl }
                // both free: as wide as allowed, the height from it
                wh != INF -> { wl = wh; hl = wh / aspect; hh = hl }
                hh != INF -> { hl = hh; wl = hh * aspect; wh = wl }
            }
        }
        return Box(wl, wh, hl, hh)
    }

    private fun flow(
        container: YtNode,
        children: List<YtNode>,
        horizontal: Boolean,
        contentW: Float,
        contentH: Float,
        exactW: Boolean,
        exactH: Boolean,
        ctx: Ctx,
        measure: (Int, Box) -> Size,
    ): Inner {
        val gap = container.layout.gap * ctx.density
        val visible = children.indices.filter { children[it].visible }
        val mainRoom = if (horizontal) (if (exactW) contentW else INF) else (if (exactH) contentH else INF)
        val mainMax = if (horizontal) contentW else contentH
        val crossRoom = if (horizontal) (if (exactH) contentH else INF) else (if (exactW) contentW else INF)
        val crossMax = if (horizontal) contentH else contentW
        val stretch = container.layout.align == YtAlign.STRETCH
        val sizes = arrayOfNulls<Size>(children.size)
        var used = 0f
        val fills = mutableListOf<Int>()

        fun crossOf(node: YtNode): Pair<Float, Float> {
            val rule = if (horizontal) node.height else node.width
            if (rule.kind == YtSizeKind.HUG && stretch && crossRoom != INF) return axis(node, YtSize.FILL, crossRoom, !horizontal, ctx)
            // a hugging child may grow up to the cross maximum even in a hugging container
            val room = if (rule.kind == YtSizeKind.HUG) crossMax else crossRoom
            return axis(node, rule, room, !horizontal, ctx)
        }

        for (i in visible) {
            val node = children[i]
            val mainRule = if (horizontal) node.width else node.height
            if (mainRule.kind == YtSizeKind.FILL && mainRoom != INF) {
                fills += i
                continue
            }
            val left = if (mainMax == INF) INF else (mainMax - used - gap * fills.size).coerceAtLeast(0f)
            val room = if (mainRule.kind == YtSizeKind.HUG) left else mainRoom
            val main = axis(node, mainRule, room, horizontal, ctx)
            val cross = crossOf(node)
            val b = if (horizontal) childBox(node, main, cross, ctx) else childBox(node, cross, main, ctx)
            val s = measure(i, b)
            sizes[i] = s
            used += if (horizontal) s.w else s.h
        }
        if (fills.isNotEmpty()) {
            val free = (mainRoom - used - gap * (visible.size - 1).coerceAtLeast(0)).coerceAtLeast(0f)
            val weights = fills.map { i -> (if (horizontal) children[i].width.value else children[i].height.value).let { if (it <= 0f) 1f else it } }
            val total = weights.sum()
            fills.forEachIndexed { k, i ->
                val node = children[i]
                val share = free * weights[k] / total
                val main = axis(node, YtSize.fixed(share / ctx.density), INF, horizontal, ctx)
                val cross = crossOf(node)
                val b = if (horizontal) childBox(node, main, cross, ctx) else childBox(node, cross, main, ctx)
                val s = measure(i, b)
                sizes[i] = s
                used += if (horizontal) s.w else s.h
            }
        }
        val gaps = gap * (visible.size - 1).coerceAtLeast(0)
        val main = used + gaps
        val cross = visible.maxOfOrNull { i -> sizes[i]?.let { if (horizontal) it.h else it.w } ?: 0f } ?: 0f
        var at = 0f
        val offsets = arrayOfNulls<Pair<Float, Float>>(children.size)
        for (i in visible) {
            val s = sizes[i] ?: continue
            offsets[i] = if (horizontal) at to 0f else 0f to at
            at += (if (horizontal) s.w else s.h) + gap
        }
        return if (horizontal) Inner(main, cross, sizes.toList(), offsets.toList()) else Inner(cross, main, sizes.toList(), offsets.toList())
    }

    private fun grid(container: YtNode, children: List<YtNode>, contentW: Float, ctx: Ctx, measure: (Int, Box) -> Size): Inner {
        val n = container.layout.columns.coerceAtLeast(1)
        val gap = container.layout.gap * ctx.density
        val rowGap = container.layout.crossGap * ctx.density
        val cell = ((contentW - gap * (n - 1)) / n).coerceAtLeast(0f)
        val visible = children.indices.filter { children[it].visible }
        val sizes = arrayOfNulls<Size>(children.size)
        val offsets = arrayOfNulls<Pair<Float, Float>>(children.size)
        var y = 0f
        visible.chunked(n).forEach { row ->
            var rowH = 0f
            row.forEachIndexed { col, i ->
                val node = children[i]
                val wRule = if (node.width.kind == YtSizeKind.HUG) YtSize.FILL else node.width
                val w = axis(node, wRule, cell, true, ctx)
                // a cell has no height of its own: FILL takes the content, without a conflict
                val hRule = if (node.height.kind == YtSizeKind.FILL || node.height.kind == YtSizeKind.PERCENT) YtSize.HUG else node.height
                val h = axis(node, hRule, INF, false, ctx)
                val s = measure(i, childBox(node, w, h, ctx))
                sizes[i] = s
                offsets[i] = col * (cell + gap) to y
                rowH = maxOf(rowH, s.h)
            }
            y += rowH + rowGap
        }
        val h = if (visible.isEmpty()) 0f else y - rowGap
        return Inner(contentW, h, sizes.toList(), offsets.toList())
    }

    private fun free(
        container: YtNode,
        children: List<YtNode>,
        fillW: Float,
        fillH: Float,
        contentW: Float,
        contentH: Float,
        ctx: Ctx,
        measure: (Int, Box) -> Size,
    ): Inner {
        val d = ctx.density
        val sizes = arrayOfNulls<Size>(children.size)
        var w = 0f
        var h = 0f
        for (i in children.indices) {
            val node = children[i]
            if (!node.visible) continue
            val wb = axis(node, node.width, if (node.width.kind == YtSizeKind.HUG) contentW else fillW, true, ctx)
            val hb = axis(node, node.height, if (node.height.kind == YtSizeKind.HUG) contentH else fillH, false, ctx)
            val s = measure(i, childBox(node, wb, hb, ctx))
            sizes[i] = s
            // the hugging extent: from the start edge, a start-anchored child reaches x + its size
            w = maxOf(w, s.w + if (node.anchorH == YtAnchor.START) node.x * d else 0f)
            h = maxOf(h, s.h + if (node.anchorV == YtAnchor.START) node.y * d else 0f)
        }
        return Inner(w, h, sizes.toList(), List(children.size) { null })
    }

    private fun placeFree(children: List<YtNode>, sizes: List<Size?>, cw: Float, ch: Float, exactW: Boolean, exactH: Boolean, ctx: Ctx): List<Place?> {
        val d = ctx.density
        return children.mapIndexed { i, node ->
            val s = sizes[i] ?: return@mapIndexed null
            fun pos(anchor: YtAnchor, offset: Float, size: Float, room: Float, exact: Boolean): Float {
                if (anchor != YtAnchor.START && !exact && room <= 0f) ctx.conflicts += Conflict(node.id, ConflictKind.ANCHOR_IN_HUG)
                return when (anchor) {
                    YtAnchor.START -> offset * d
                    YtAnchor.CENTER -> (room - size) / 2f + offset * d
                    YtAnchor.END -> room - size - offset * d
                }
            }
            Place(pos(node.anchorH, node.x, s.w, cw, exactW), pos(node.anchorV, node.y, s.h, ch, exactH), s.w, s.h)
        }
    }

    /** Places flow and grid children in the final content box: justify along, align across. */
    private fun justify(container: YtNode, children: List<YtNode>, inner: Inner, mode: YtLayoutMode, cw: Float, ch: Float): List<Place?> {
        val horizontal = mode == YtLayoutMode.FLOW_H
        val flow = mode == YtLayoutMode.FLOW_H || mode == YtLayoutMode.FLOW_V
        val visible = children.indices.count { inner.sizes[it] != null }
        val freeMain = ((if (horizontal) cw - inner.w else ch - inner.h)).coerceAtLeast(0f)
        val (lead, extra) = if (!flow) 0f to 0f else when (container.layout.justify) {
            YtJustify.START -> 0f to 0f
            YtJustify.CENTER -> freeMain / 2f to 0f
            YtJustify.END -> freeMain to 0f
            YtJustify.SPACE_BETWEEN -> if (visible > 1) 0f to freeMain / (visible - 1) else 0f to 0f
        }
        var k = 0
        return children.indices.map { i ->
            val s = inner.sizes[i] ?: return@map null
            val (ox, oy) = inner.offsets[i] ?: (0f to 0f)
            val shift = lead + extra * k
            k++
            if (!flow) return@map Place(ox, oy, s.w, s.h)
            val crossRoom = if (horizontal) ch else cw
            val crossSize = if (horizontal) s.h else s.w
            val cross = when (container.layout.align) {
                YtAlign.START, YtAlign.STRETCH -> 0f
                YtAlign.CENTER -> (crossRoom - crossSize) / 2f
                YtAlign.END -> crossRoom - crossSize
            }
            if (horizontal) Place(ox + shift, cross, s.w, s.h) else Place(cross, oy + shift, s.w, s.h)
        }
    }

    /**
     * Lays out a whole tree (for the tests and the editor's checks): [leaf] gives the size of a
     * node without children inside a box. Places are in px from the root's top left.
     */
    fun tree(root: YtNode, box: Box, density: Float, leaf: (YtNode, Box) -> Size): Pair<Map<String, Place>, List<Conflict>> {
        val places = LinkedHashMap<String, Place>()
        val conflicts = mutableListOf<Conflict>()
        fun run(node: YtNode, b: Box, x: Float, y: Float): Size {
            if (node.children.isEmpty()) {
                val s = leaf(node, b)
                val size = Size(clampTo(s.w, b.minW, b.maxW), clampTo(s.h, b.minH, b.maxH))
                places[node.id] = Place(x, y, size.w, size.h)
                return size
            }
            val childOrigin = HashMap<Int, Box>()
            val sizes = HashMap<Int, Size>()
            val r = layout(node, node.children, b, density) { i, cb ->
                childOrigin[i] = cb
                // measured at 0,0 first; moved once the parent has placed it
                run(node.children[i], cb, 0f, 0f).also { sizes[i] = it }
            }
            conflicts += r.conflicts
            places[node.id] = Place(x, y, r.width, r.height)
            r.places.forEachIndexed { i, p ->
                if (p != null) shift(node.children[i], places, x + p.x, y + p.y)
            }
            return Size(r.width, r.height)
        }
        run(root, box, 0f, 0f)
        return places to conflicts
    }

    private fun shift(node: YtNode, places: MutableMap<String, Place>, dx: Float, dy: Float) {
        places[node.id]?.let { places[node.id] = it.copy(x = it.x + dx, y = it.y + dy) }
        node.children.forEach { shift(it, places, dx, dy) }
    }
}
