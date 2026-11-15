package com.github.libretube.ui.yt.builder

import kotlin.math.roundToLong

/** A keyframe of an animation: track index and key index (keys sorted by time). */
data class YtKeyRef(val track: Int, val key: Int)

/**
 * YT: the timeline's operations on keyframes (pure): snapping to the frame grid, moving a
 * selection (spacing kept), copy/paste, duplicate, delete, distribute and align, and the
 * time ↔ pixel mapping with zoom and scroll. Times are ms of one play; the document stores
 * them as shares of the duration.
 */
object YtAnimTimeline {
    val FPS = listOf(24, 30, 60, 120)

    fun frameMs(fps: Int): Double = 1000.0 / fps.coerceIn(1, 1000)

    /** [ms] on the nearest frame of a [fps] grid. */
    fun snap(ms: Double, fps: Int): Double {
        val f = frameMs(fps)
        return (ms / f).roundToLong() * f
    }

    /** The frame index of [ms] (for the frame counter and frame steps). */
    fun frameOf(ms: Double, fps: Int): Long = (ms / frameMs(fps)).roundToLong()

    fun msOf(a: YtAnim, k: YtKeyframe): Double = k.at.toDouble() * a.durationMs

    private fun atOf(a: YtAnim, ms: Double): Float = if (a.durationMs <= 0) 0f else (ms / a.durationMs).toFloat().coerceIn(0f, 1f)

    /** Pixels for the time scale: [pxPerMs] at zoom 1, [scrollPx] moved to the left. */
    data class View(val pxPerMs: Float = 0.5f, val zoom: Float = 1f, val scrollPx: Float = 0f) {
        val scale: Float get() = pxPerMs * zoom
        fun x(ms: Double): Float = (ms * scale).toFloat() - scrollPx
        fun ms(x: Float): Double = ((x + scrollPx) / scale).toDouble()

        /** Zoomed by [factor] around the screen point [focusX], which keeps its time. */
        fun zoomed(factor: Float, focusX: Float): View {
            val t = ms(focusX)
            val z = (zoom * factor).coerceIn(0.1f, 40f)
            val next = copy(zoom = z)
            return next.copy(scrollPx = ((t * next.scale) - focusX).toFloat().coerceAtLeast(0f))
        }

        /** A tick spacing (ms) that gives ticks about [minPx] apart. */
        fun tickMs(minPx: Float = 56f): Int {
            for (s in listOf(10, 20, 50, 100, 200, 250, 500, 1000, 2000, 5000, 10000)) if (s * scale >= minPx) return s
            return 20000
        }
    }

    /** Applies [change] to the keys of each track and returns the new selection (identity kept through sorting). */
    private fun rebuild(a: YtAnim, sel: Set<YtKeyRef>, change: (trackIndex: Int, keyIndex: Int, k: YtKeyframe, selected: Boolean) -> YtKeyframe?): Pair<YtAnim, Set<YtKeyRef>> {
        val out = HashSet<YtKeyRef>()
        val tracks = a.tracks.mapIndexed { ti, t ->
            val marked = t.keys.mapIndexedNotNull { ki, k ->
                val s = YtKeyRef(ti, ki) in sel
                change(ti, ki, k, s)?.let { it to s }
            }.sortedBy { it.first.at }
            marked.forEachIndexed { i, (_, s) -> if (s) out += YtKeyRef(ti, i) }
            t.copy(keys = marked.map { it.first })
        }
        return a.copy(tracks = tracks) to out
    }

    /**
     * Moves the selected keys by [deltaMs] (from the state when the drag began). With [snap],
     * the [anchor] key lands on the frame grid and the others keep their distance to it. The
     * selection as a whole stays inside the play.
     */
    fun move(a: YtAnim, sel: Set<YtKeyRef>, anchor: YtKeyRef, deltaMs: Double, fps: Int, snap: Boolean): Pair<YtAnim, Set<YtKeyRef>> {
        if (sel.isEmpty() || a.durationMs <= 0) return a to sel
        val times = sel.mapNotNull { r -> a.tracks.getOrNull(r.track)?.keys?.getOrNull(r.key)?.let { msOf(a, it) } }
        if (times.isEmpty()) return a to sel
        val anchorMs = a.tracks.getOrNull(anchor.track)?.keys?.getOrNull(anchor.key)?.let { msOf(a, it) } ?: times.min()
        var d = deltaMs
        if (snap) d = snap(anchorMs + d, fps) - anchorMs
        d = d.coerceIn(-times.min(), a.durationMs - times.max())
        return rebuild(a, sel) { _, _, k, s -> if (s) k.copy(at = atOf(a, msOf(a, k) + d)) else k }
    }

    fun delete(a: YtAnim, sel: Set<YtKeyRef>): YtAnim {
        val (next, _) = rebuild(a, sel) { _, _, k, s -> if (s) null else k }
        return next.copy(tracks = next.tracks.filter { it.keys.isNotEmpty() })
    }

    /** A copied key: its track's property, its time after the first copied key, the key. */
    data class Clip(val prop: YtAnimProp, val relMs: Double, val key: YtKeyframe)

    fun copy(a: YtAnim, sel: Set<YtKeyRef>): List<Clip> {
        val picked = sel.mapNotNull { r -> a.tracks.getOrNull(r.track)?.let { t -> t.keys.getOrNull(r.key)?.let { t.prop to it } } }
        if (picked.isEmpty()) return emptyList()
        val first = picked.minOf { msOf(a, it.second) }
        return picked.map { (p, k) -> Clip(p, msOf(a, k) - first, k) }.sortedBy { it.relMs }
    }

    /** Pastes [clips] at [atMs] into the tracks of their properties (a missing track is added). */
    fun paste(a: YtAnim, clips: List<Clip>, atMs: Double): Pair<YtAnim, Set<YtKeyRef>> {
        if (clips.isEmpty() || a.durationMs <= 0) return a to emptySet()
        var tracks = a.tracks
        for (p in clips.map { it.prop }.distinct()) if (tracks.none { it.prop == p }) tracks = tracks + YtTrack(p)
        val added = HashMap<Int, MutableList<YtKeyframe>>()
        for (c in clips) {
            val ti = tracks.indexOfFirst { it.prop == c.prop }
            added.getOrPut(ti) { mutableListOf() } += c.key.copy(at = atOf(a, atMs + c.relMs))
        }
        val sel = HashSet<YtKeyRef>()
        val next = tracks.mapIndexed { ti, t ->
            val extra = added[ti] ?: return@mapIndexed t
            val all = (t.keys.map { it to false } + extra.map { it to true }).sortedBy { it.first.at }
            all.forEachIndexed { i, (_, s) -> if (s) sel += YtKeyRef(ti, i) }
            t.copy(keys = all.map { it.first })
        }
        return a.copy(tracks = next) to sel
    }

    /** Copies of the selected keys right after them (one [fps] frame after the last). */
    fun duplicate(a: YtAnim, sel: Set<YtKeyRef>, fps: Int): Pair<YtAnim, Set<YtKeyRef>> {
        val clips = copy(a, sel)
        if (clips.isEmpty()) return a to sel
        val first = sel.minOf { r -> a.tracks[r.track].keys[r.key].let { msOf(a, it) } }
        val span = clips.last().relMs
        return paste(a, clips, first + span + frameMs(fps))
    }

    /** Spreads the selected keys evenly between the earliest and the latest of them. */
    fun distribute(a: YtAnim, sel: Set<YtKeyRef>): Pair<YtAnim, Set<YtKeyRef>> {
        if (sel.size < 3) return a to sel
        val order = sel.sortedWith(compareBy({ msOf(a, a.tracks[it.track].keys[it.key]) }, { it.track }, { it.key }))
        val start = msOf(a, a.tracks[order.first().track].keys[order.first().key])
        val end = msOf(a, a.tracks[order.last().track].keys[order.last().key])
        val at = order.withIndex().associate { (i, r) -> r to start + (end - start) * i / (order.size - 1) }
        return rebuild(a, sel) { ti, ki, k, s -> if (s) k.copy(at = atOf(a, at.getValue(YtKeyRef(ti, ki)))) else k }
    }

    /** Puts every selected key at [ms] (the playhead). */
    fun align(a: YtAnim, sel: Set<YtKeyRef>, ms: Double): Pair<YtAnim, Set<YtKeyRef>> =
        rebuild(a, sel) { _, _, k, s -> if (s) k.copy(at = atOf(a, ms)) else k }

    /** A new key on track [track] at [ms] with the value the track has there (the curve does not change). */
    fun addKey(a: YtAnim, track: Int, ms: Double, own: Int = 0xFF000000.toInt()): Pair<YtAnim, YtKeyRef?> {
        val t = a.tracks.getOrNull(track) ?: return a to null
        val at = atOf(a, ms)
        val key = if (t.prop.colour) YtKeyframe(at, color = YtAnimEval.sampleColor(t, at, own)) else YtKeyframe(at, YtAnimEval.sample(t, at))
        // the new key takes the curve of the segment it splits
        val prev = t.keys.lastOrNull { it.at <= at }
        val added = key.copy(curve = prev?.curve ?: YtCurve.LINEAR)
        val keys = (t.keys + added).sortedBy { it.at }
        val index = keys.indexOfFirst { it === added }
        return a.copy(tracks = a.tracks.toMutableList().also { it[track] = t.copy(keys = keys) }) to YtKeyRef(track, index)
    }

    /** The selected keys' curves set to [curve] (the advanced mode's easing for a whole animation). */
    fun setCurve(a: YtAnim, curve: YtCurve, sel: Set<YtKeyRef>? = null): YtAnim = a.copy(
        tracks = a.tracks.mapIndexed { ti, t -> t.copy(keys = t.keys.mapIndexed { ki, k -> if (sel == null || YtKeyRef(ti, ki) in sel) k.copy(curve = curve) else k }) },
    )
}
