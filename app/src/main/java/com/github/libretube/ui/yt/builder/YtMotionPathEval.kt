package com.github.libretube.ui.yt.builder

import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

/*
 * YT: motion paths (pure, JVM-tested). A PATH track's progress 0..1 is a share of the path's
 * length (arc length), so the node moves at the speed the track's keyframes and easing give,
 * whatever the spacing of the path's points. Turning with the path uses the tangent, blended
 * between the pieces so the node turns smoothly through a bend.
 */
class YtMotionPathEval(motion: YtMotionPath) {
    private val xs: DoubleArray
    private val ys: DoubleArray
    private val cum: DoubleArray
    private val tangent: DoubleArray
    private val seg: DoubleArray
    private val count: Int
    val orient = motion.orient
    val angle = motion.angle

    /** Length in dp. */
    val length: Double

    init {
        val polys = YtPathGeom.flatten(motion.path, 0.1)
        // one continuous route: the subpaths one after another (a closed one returns to its start)
        val px = ArrayList<Double>()
        val py = ArrayList<Double>()
        for (p in polys) {
            for (i in 0 until p.n) {
                px += p.xs[i]
                py += p.ys[i]
            }
            if (p.closed && p.n > 0) {
                px += p.xs[0]
                py += p.ys[0]
            }
        }
        if (px.isEmpty()) {
            px += 0.0
            py += 0.0
        }
        count = px.size
        xs = DoubleArray(count) { px[it] }
        ys = DoubleArray(count) { py[it] }
        cum = DoubleArray(count)
        for (i in 1 until count) cum[i] = cum[i - 1] + hypot(xs[i] - xs[i - 1], ys[i] - ys[i - 1])
        length = cum[count - 1]
        // per vertex: the mean direction of the pieces around it, unwrapped so blending never spins
        seg = DoubleArray(max(count - 1, 1))
        for (i in 0 until count - 1) seg[i] = atan2(ys[i + 1] - ys[i], xs[i + 1] - xs[i])
        for (i in 1 until count - 1) {
            while (seg[i] - seg[i - 1] > Math.PI) seg[i] -= 2 * Math.PI
            while (seg[i] - seg[i - 1] < -Math.PI) seg[i] += 2 * Math.PI
        }
        tangent = DoubleArray(count) { i ->
            when {
                count < 2 -> 0.0
                i == 0 -> seg[0]
                i == count - 1 -> seg[count - 2]
                else -> (seg[i - 1] + seg[i]) / 2
            }
        }
    }

    /**
     * At [progress] (0..1 of the length): out[0], out[1] the move in dp from the start of the path,
     * out[2] the rotation in degrees (the path's direction when [orient], plus [angle]).
     */
    fun at(progress: Float, out: FloatArray) {
        if (count < 2 || length <= 0.0) {
            out[0] = 0f
            out[1] = 0f
            out[2] = angle
            return
        }
        val s = progress.coerceIn(0f, 1f) * length
        // binary search for the piece holding s
        var lo = 0
        var hi = count - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) ushr 1
            if (cum[mid] <= s) lo = mid else hi = mid
        }
        val span = cum[hi] - cum[lo]
        val f = if (span <= 0.0) 0.0 else (s - cum[lo]) / span
        out[0] = (xs[lo] + (xs[hi] - xs[lo]) * f - xs[0]).toFloat()
        out[1] = (ys[lo] + (ys[hi] - ys[lo]) * f - ys[0]).toFloat()
        if (!orient) {
            out[2] = angle
            return
        }
        // along a piece the node points its way; within BLEND_DP of a corner it turns through the
        // mean of both directions, so a bend never makes it jump
        val dir = seg[lo.coerceAtMost(seg.size - 1)]
        val blend = minOf(BLEND_DP, span / 2)
        val fromStart = s - cum[lo]
        val toEnd = cum[hi] - s
        val a = when {
            blend > 0.0 && fromStart < blend -> tangent[lo] + (dir - tangent[lo]) * (fromStart / blend)
            blend > 0.0 && toEnd < blend -> tangent[hi] + (dir - tangent[hi]) * (toEnd / blend)
            else -> dir
        }
        out[2] = Math.toDegrees(a).toFloat() + angle
    }

    private companion object {
        const val BLEND_DP = 8.0
    }
}

/**
 * YT: a finger drag recorded as a motion path with its timing (pure, JVM-tested). The raw
 * samples stay in the editor, so the simplify and smooth sliders always start from them.
 */
object YtMotionRecord {
    /** A sample: [t] ms since the start, the move ([x], [y]) in dp from where the drag started. */
    data class Sample(val t: Long, val x: Float, val y: Float)

    class Result(val motion: YtMotionPath, val track: YtTrack, val durationMs: Int)

    /**
     * The path of [samples]: jitter evened out by [smooth] (0..1), points closer than [simplify] dp
     * to the line of their neighbours left out (RDP), curves through the rest when [smooth] > 0.
     * The PATH track keeps the finger's speed: keyframes of the share of the way over time, thinned
     * (at most [YtAnimValidate.MAX_KEYS]), linear between them. Null with fewer than two distinct places.
     */
    fun build(samples: List<Sample>, simplify: Float, smooth: Float, orient: Boolean = false, angle: Float = 0f): Result? {
        val clean = ArrayList<Sample>()
        for (s in samples) {
            val last = clean.lastOrNull()
            // a finger at rest adds nothing but time
            if (last == null || hypot(s.x - last.x, s.y - last.y) > 0.05f) clean += s
        }
        if (clean.size < 2) return null
        val t0 = samples.first().t
        val total = (samples.last().t - t0).coerceAtLeast(1L)
        val raw = YtSubpath(clean.map { YtPoint(it.x, it.y) }, closed = false)
        val relaxed = YtPathOps.relax(raw, (smooth * 0.5f).coerceIn(0f, 0.5f), passes = 3)
        val xs = DoubleArray(relaxed.points.size) { relaxed.points[it].x.toDouble() }
        val ys = DoubleArray(relaxed.points.size) { relaxed.points[it].y.toDouble() }
        val kept = YtPathOps.rdp(xs, ys, 0, xs.size - 1, simplify.coerceAtLeast(0.01f).toDouble())
        val anchors = YtSubpath(kept.map { relaxed.points[it] }, closed = false)
        val sub = if (smooth > 0f) YtPathOps.smooth(YtPath(listOf(anchors)), smooth.coerceIn(0f, 1f)).subpaths[0] else anchors
        val path = YtPath(listOf(sub), w = 1f, h = 1f)
        // the timing: how far along (share of the smoothed route) the finger was at each sample,
        // thinned by RDP in (time, progress) so the speed changes stay and the rest goes
        val cum = DoubleArray(xs.size)
        for (i in 1 until xs.size) cum[i] = cum[i - 1] + hypot(xs[i] - xs[i - 1], ys[i] - ys[i - 1])
        val routeLen = cum[xs.size - 1].coerceAtLeast(1e-6)
        val ts = DoubleArray(xs.size) { (clean[it].t - t0).toDouble() / total }
        val ps = DoubleArray(xs.size) { cum[it] / routeLen }
        var tolT = 0.004
        var pick = YtPathOps.rdp(ts, ps, 0, xs.size - 1, tolT)
        while (pick.size > YtAnimValidate.MAX_KEYS && tolT < 0.5) {
            tolT *= 1.6
            pick = YtPathOps.rdp(ts, ps, 0, xs.size - 1, tolT)
        }
        val keys = ArrayList<YtKeyframe>()
        var lastAt = -1f
        var lastValue = 0f
        for (i in pick) {
            val at = ts[i].toFloat().coerceIn(0f, 1f)
            val value = ps[i].toFloat().coerceIn(lastValue, 1f)
            if (at <= lastAt) continue
            keys += YtKeyframe(at, value)
            lastAt = at
            lastValue = value
        }
        if (keys.isEmpty() || keys.first().at > 0f) keys.add(0, YtKeyframe(0f, 0f))
        if (keys.last().at < 1f) keys += YtKeyframe(1f, 1f) else keys[keys.lastIndex] = keys.last().copy(value = 1f)
        return Result(YtMotionPath(path, orient, angle), YtTrack(YtAnimProp.PATH, keys), total.coerceIn(50L, YtAnimValidate.MAX_MS.toLong()).toInt())
    }

    /** Keyframes for an even speed along the path with [curve] as the easing. */
    fun evenSpeed(curve: YtCurve = YtCurve.EASE_IN_OUT): YtTrack =
        YtTrack(YtAnimProp.PATH, listOf(YtKeyframe(0f, 0f, curve = curve), YtKeyframe(1f, 1f)))
}
