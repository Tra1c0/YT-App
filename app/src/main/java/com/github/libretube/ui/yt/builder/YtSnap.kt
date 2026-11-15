package com.github.libretube.ui.yt.builder

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * YT: magnetic snapping for the theme editor's drag (pure, px). Each axis snaps on its own to the
 * best line in reach: the container's edges and centre, the edges and centres of the other
 * elements, user guides, equal spacing to the neighbours, the grid. Priorities break ties before
 * distance (container, then elements and guides, then spacing, then grid).
 *
 * Hysteresis: a snapped axis stays on its line until the finger takes it further than [Settings.snapOut]
 * from it (more than [Settings.snapIn], the distance at which it snaps). While it stays, no other
 * line is considered, so it never jumps between two lines that are close together.
 */
object YtSnap {
    enum class Kind(val priority: Int) { CONTAINER(0), EDGE(1), CENTER(1), GUIDE(1), SPACING(2), GRID(3) }

    /** Which part of the moving box snapped: its start edge, centre or end edge. */
    enum class Part { START, CENTER, END }

    data class Rect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        val width: Float get() = right - left
        val height: Float get() = bottom - top
        val cx: Float get() = (left + right) / 2f
        val cy: Float get() = (top + bottom) / 2f
        fun offset(dx: Float, dy: Float) = Rect(left + dx, top + dy, right + dx, bottom + dy)
    }

    data class Settings(
        val kinds: Set<Kind> = Kind.entries.toSet(),
        val snapIn: Float = 8f,
        val snapOut: Float = 16f,
        /** Grid step in px; 0 for no grid. */
        val grid: Float = 0f,
        /** User guides: vertical lines (x) and horizontal lines (y), px. */
        val guidesX: List<Float> = emptyList(),
        val guidesY: List<Float> = emptyList(),
    ) {
        val enabled: Boolean get() = kinds.isNotEmpty()
    }

    /** A line the box is held on. */
    data class Lock(val kind: Kind, val at: Float, val part: Part)

    data class State(val x: Lock? = null, val y: Lock? = null)

    /** A guide to draw: a vertical ([vertical]) or horizontal line at [at] from [from] to [to]. */
    data class Guide(val vertical: Boolean, val at: Float, val from: Float, val to: Float, val kind: Kind)

    data class Result(val dx: Float, val dy: Float, val state: State, val guides: List<Guide>)

    private class Candidate(val kind: Kind, val at: Float, val parts: Set<Part>, val spanFrom: Float, val spanTo: Float)

    private val EDGES = setOf(Part.START, Part.END)
    private val MIDDLE = setOf(Part.CENTER)
    private val ALL = Part.entries.toSet()

    /**
     * Snaps [moving] (where the finger puts it) against [others] inside [container]; [state] is
     * the result's state of the last move (none at the start of a drag).
     */
    fun snap(moving: Rect, others: List<Rect>, container: Rect, settings: Settings, state: State = State()): Result {
        if (!settings.enabled) return Result(0f, 0f, State(), emptyList())
        val xs = candidates(true, moving, others, container, settings)
        val ys = candidates(false, moving, others, container, settings)
        val (dx, lockX) = axis(xs, moving.left, moving.cx, moving.right, settings, state.x)
        val (dy, lockY) = axis(ys, moving.top, moving.cy, moving.bottom, settings, state.y)
        val placed = moving.offset(dx, dy)
        val guides = buildList {
            lockX?.let { l -> xs.firstOrNull { it.kind == l.kind && near(it.at, l.at) }?.let { c -> add(Guide(true, l.at, minOf(c.spanFrom, placed.top), maxOf(c.spanTo, placed.bottom), l.kind)) } }
            lockY?.let { l -> ys.firstOrNull { it.kind == l.kind && near(it.at, l.at) }?.let { c -> add(Guide(false, l.at, minOf(c.spanFrom, placed.left), maxOf(c.spanTo, placed.right), l.kind)) } }
        }
        return Result(dx, dy, State(lockX, lockY), guides)
    }

    private fun near(a: Float, b: Float) = abs(a - b) < 0.5f

    private fun valueOf(part: Part, start: Float, center: Float, end: Float) = when (part) {
        Part.START -> start
        Part.CENTER -> center
        Part.END -> end
    }

    private fun axis(cands: List<Candidate>, start: Float, center: Float, end: Float, s: Settings, held: Lock?): Pair<Float, Lock?> {
        // a held line keeps the box until the finger leaves the wider snap-out distance
        if (held != null && held.kind in s.kinds) {
            val still = cands.any { it.kind == held.kind && near(it.at, held.at) && held.part in it.parts }
            val d = held.at - valueOf(held.part, start, center, end)
            if (still && abs(d) <= s.snapOut) return d to held
        }
        var best: Candidate? = null
        var bestPart = Part.START
        var bestD = 0f
        for (c in cands) {
            if (c.kind !in s.kinds) continue
            for (part in c.parts) {
                val d = c.at - valueOf(part, start, center, end)
                if (abs(d) > s.snapIn) continue
                val b = best
                val better = b == null || c.kind.priority < b.kind.priority ||
                    (c.kind.priority == b.kind.priority && abs(d) < abs(bestD) - 0.01f)
                if (better) {
                    best = c
                    bestPart = part
                    bestD = d
                }
            }
        }
        val b = best ?: return 0f to null
        return bestD to Lock(b.kind, b.at, bestPart)
    }

    private fun candidates(horizontal: Boolean, m: Rect, others: List<Rect>, c: Rect, s: Settings): List<Candidate> {
        fun lo(r: Rect) = if (horizontal) r.left else r.top
        fun hi(r: Rect) = if (horizontal) r.right else r.bottom
        fun mid(r: Rect) = if (horizontal) r.cx else r.cy
        fun spanLo(r: Rect) = if (horizontal) r.top else r.left
        fun spanHi(r: Rect) = if (horizontal) r.bottom else r.right
        val out = ArrayList<Candidate>(others.size * 3 + 8)
        out += Candidate(Kind.CONTAINER, lo(c), EDGES, spanLo(c), spanHi(c))
        out += Candidate(Kind.CONTAINER, mid(c), MIDDLE, spanLo(c), spanHi(c))
        out += Candidate(Kind.CONTAINER, hi(c), EDGES, spanLo(c), spanHi(c))
        for (r in others) {
            out += Candidate(Kind.EDGE, lo(r), EDGES, spanLo(r), spanHi(r))
            out += Candidate(Kind.EDGE, hi(r), EDGES, spanLo(r), spanHi(r))
            out += Candidate(Kind.CENTER, mid(r), MIDDLE, spanLo(r), spanHi(r))
        }
        for (g in if (horizontal) s.guidesX else s.guidesY) out += Candidate(Kind.GUIDE, g, ALL, spanLo(c), spanHi(c))
        if (s.grid > 0.5f) {
            val origin = lo(c)
            val k = ((lo(m) - origin) / s.grid).roundToInt()
            for (j in k - 1..k + 1) out += Candidate(Kind.GRID, origin + j * s.grid, setOf(Part.START), spanLo(c), spanHi(c))
            val e = ((hi(m) - origin) / s.grid).roundToInt()
            out += Candidate(Kind.GRID, origin + e * s.grid, setOf(Part.END), spanLo(c), spanHi(c))
        }
        out += spacing(horizontal, m, others)
        return out
    }

    /**
     * Equal spacing along one axis, among the elements that share the other axis with [m]: the
     * gap to the nearest neighbour on either side the same as a gap already between two others,
     * or the box centred between its two neighbours (the same gap on both sides).
     */
    private fun spacing(horizontal: Boolean, m: Rect, others: List<Rect>): List<Candidate> {
        fun lo(r: Rect) = if (horizontal) r.left else r.top
        fun hi(r: Rect) = if (horizontal) r.right else r.bottom
        fun crossLo(r: Rect) = if (horizontal) r.top else r.left
        fun crossHi(r: Rect) = if (horizontal) r.bottom else r.right
        val size = hi(m) - lo(m)
        val center = (lo(m) + hi(m)) / 2f
        val row = others.filter { crossLo(it) < crossHi(m) && crossHi(it) > crossLo(m) }.sortedBy { lo(it) }
        if (row.isEmpty()) return emptyList()
        val gaps = row.zipWithNext { a, b -> lo(b) - hi(a) }.filter { it > 0.5f }
        val before = row.filter { hi(it) <= center }.maxByOrNull { hi(it) }
        val after = row.filter { lo(it) >= center }.minByOrNull { lo(it) }
        val out = ArrayList<Candidate>()
        val span = { r: Rect -> crossLo(r) to crossHi(r) }
        for (g in gaps.distinctBy { (it * 2).roundToInt() }) {
            before?.let { b -> out += Candidate(Kind.SPACING, hi(b) + g, setOf(Part.START), span(b).first, span(b).second) }
            after?.let { a -> out += Candidate(Kind.SPACING, lo(a) - g, setOf(Part.END), span(a).first, span(a).second) }
        }
        if (before != null && after != null) {
            val room = lo(after) - hi(before)
            if (room > size) out += Candidate(Kind.SPACING, hi(before) + (room - size) / 2f, setOf(Part.START), crossLo(m), crossHi(m))
        }
        return out
    }
}
