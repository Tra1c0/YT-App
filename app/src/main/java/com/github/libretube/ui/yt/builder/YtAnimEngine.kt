package com.github.libretube.ui.yt.builder

import kotlin.math.abs
import kotlin.math.floor

/*
 * YT: the animator's runtime (pure, JVM-tested). One engine per drawn component instance holds a
 * channel per animated (node, property): its current value and velocity and the run that owns it.
 *
 * Time: runs advance by the real frame time (ms since the last frame), never by a frame count, so
 * an animation takes as long at 60 Hz as at 120 Hz. Springs are solved in closed form per step.
 *
 * Several animations on one property: the one started last wins (it takes the channel over). An
 * interrupting run starts from the channel's current value; a spring also keeps its current
 * velocity, so nothing jumps. A run that ends leaves its end value in place.
 *
 * No allocations per frame: runs are built when they start; ticks only do arithmetic on arrays.
 */

/** What the node looks like without animation, for colour keyframes that say "own colour". */
fun interface YtAnimRest {
    fun color(node: String, prop: YtAnimProp): Int?
}

/** Lengths and sampling of animations (pure). */
object YtAnimEval {
    /** ms of one play: the duration, or for a spring the time its tracks need to settle. */
    fun playMs(anim: YtAnim): Int = if (anim.kind == YtAnimKind.SPRING) springMs(anim) else anim.durationMs.coerceAtLeast(0)

    /** The settle time of a fresh spring start of [anim]'s tracks. */
    fun springMs(anim: YtAnim): Int {
        var best = 0
        val buf = FloatArray(8)
        for (t in anim.playing()) {
            val first = t.keys.first()
            val last = t.keys.last()
            val distance = if (t.prop.colour) {
                // colours: the OKLab distance of the two, own colours counted as a full step
                if (first.color == null || last.color == null) {
                    1.0
                } else {
                    YtOklab.fromArgb(first.color, buf, 0)
                    YtOklab.fromArgb(last.color, buf, 4)
                    (0 until 4).maxOf { abs(buf[it] - buf[it + 4]).toDouble() }
                }
            } else {
                (first.value - last.value).toDouble()
            }
            val ms = YtSpring.settleMs(distance, if (t.prop.colour) 0.0 else t.velocity.toDouble(), anim.spring.stiffness.toDouble(), anim.spring.damping.toDouble(), YtSpring.threshold(t.prop))
            if (ms > best) best = ms
        }
        return best
    }

    /** ms from the start to the end of the last play; null when it repeats forever. */
    fun totalMs(anim: YtAnim): Long? =
        if (anim.infinite) null else anim.delayMs.coerceAtLeast(0) + playMs(anim).toLong() * (anim.repeat.coerceAtLeast(0) + 1)

    /** Whether play [i] runs forward. */
    fun forward(direction: YtAnimDirection, i: Int): Boolean = when (direction) {
        YtAnimDirection.NORMAL -> true
        YtAnimDirection.REVERSE -> false
        YtAnimDirection.ALTERNATE -> i % 2 == 0
    }

    /**
     * Where [tMs] (since the trigger) falls: the play's index and the progress 0..1 in playing
     * direction. Before the delay: the start of the first play; after the end: the end of the last.
     */
    fun position(delayMs: Float, playMs: Float, repeat: Int, direction: YtAnimDirection, tMs: Double, out: DoubleArray) {
        val play = tMs - delayMs
        var i: Int
        var frac: Double
        when {
            play <= 0.0 -> {
                i = 0
                frac = 0.0
            }
            playMs <= 0f -> {
                i = if (repeat < 0) 0 else repeat
                frac = 1.0
            }
            else -> {
                val n = floor(play / playMs)
                if (repeat >= 0 && n > repeat) {
                    i = repeat
                    frac = 1.0
                } else {
                    i = n.toInt()
                    frac = (play - n * playMs) / playMs
                }
            }
        }
        out[0] = i.toDouble()
        out[1] = if (forward(direction, i)) frac else 1.0 - frac
    }

    /** The float value of [track] at progress [p] (0..1 of the duration). */
    fun sample(track: YtTrack, p: Float): Float {
        val keys = track.keys
        if (keys.isEmpty()) return track.prop.rest
        if (p <= keys.first().at) return keys.first().value
        if (p >= keys.last().at) return keys.last().value
        for (i in 1 until keys.size) {
            val b = keys[i]
            if (p <= b.at) {
                val a = keys[i - 1]
                val span = b.at - a.at
                if (span <= 0f) return b.value
                val e = YtCurves.eval(a.curve, (p - a.at) / span)
                return a.value + (b.value - a.value) * e
            }
        }
        return keys.last().value
    }

    /** The colour of a colour [track] at [p], interpolated in OKLab; null keys take [own]. */
    fun sampleColor(track: YtTrack, p: Float, own: Int): Int {
        val keys = track.keys
        if (keys.isEmpty()) return own
        fun c(k: YtKeyframe) = k.color ?: own
        if (p <= keys.first().at) return c(keys.first())
        if (p >= keys.last().at) return c(keys.last())
        for (i in 1 until keys.size) {
            val b = keys[i]
            if (p <= b.at) {
                val a = keys[i - 1]
                val span = b.at - a.at
                if (span <= 0f) return c(b)
                return YtOklab.lerp(c(a), c(b), YtCurves.eval(a.curve, (p - a.at) / span))
            }
        }
        return c(keys.last())
    }
}

/** One animated property of a node: its value now (OKLab + alpha for colours) and velocity per second. */
class YtAnimChannel internal constructor(val prop: YtAnimProp) {
    val dims = prop.dims
    internal val value = FloatArray(dims)
    internal val velocity = FloatArray(dims)

    /** False: the node shows its own value. */
    var set = false
        internal set
    internal var run: YtAnimRun? = null

    val running: Boolean get() = run != null

    fun float(): Float = if (set) value[0] else prop.rest

    fun argb(own: Int): Int = if (set) YtOklab.toArgb(value, 0) else own

    fun velocity(dim: Int = 0): Float = velocity[dim]

    internal fun rest() {
        set = false
        run = null
        velocity.fill(0f)
    }
}

/** The channels of one node, by property. */
class YtAnimNode internal constructor(val id: String) {
    internal val channels = arrayOfNulls<YtAnimChannel>(PROPS.size)

    fun channel(p: YtAnimProp): YtAnimChannel? = channels[p.ordinal]

    internal fun take(p: YtAnimProp): YtAnimChannel = channels[p.ordinal] ?: YtAnimChannel(p).also { channels[p.ordinal] = it }

    /** The value of [p] now: animated, or the property's rest. */
    fun float(p: YtAnimProp): Float = channels[p.ordinal]?.float() ?: p.rest

    /** The colour of [p] now, or [own]. */
    fun argb(p: YtAnimProp, own: Int): Int = channels[p.ordinal]?.argb(own) ?: own

    fun animated(p: YtAnimProp): Boolean = channels[p.ordinal]?.set == true

    companion object {
        internal val PROPS = YtAnimProp.entries
    }
}

/**
 * A playing track. Time-based: its keyframes flattened ([times] in ms of one play, [values]
 * [dims] per key, [curves] per segment). Spring: [from] and [to] with the start velocity [v0].
 */
internal class YtAnimRun(
    val channel: YtAnimChannel,
    val animId: String,
    val kind: YtAnimKind,
    val times: FloatArray,
    val values: FloatArray,
    val curves: Array<YtCurve>,
    val delayMs: Float,
    val playMs: Float,
    val repeat: Int,
    val direction: YtAnimDirection,
    val from: FloatArray,
    val to: FloatArray,
    val v0: FloatArray,
    val stiffness: Double,
    val damping: Double,
    val threshold: Double,
    val driven: Boolean,
) {
    val dims = channel.dims
    var elapsed = 0.0

    // spring state: displacement from the target and velocity per dim, the play it is in
    val sx = DoubleArray(dims)
    val sv = DoubleArray(dims)
    var play = -1
    private val pos = DoubleArray(2)
    private val step = DoubleArray(2)

    /** Total length in ms, or -1 forever. */
    val total: Double = if (repeat < 0) -1.0 else delayMs + playMs.toDouble() * (repeat + 1)

    /** The time-based value at [tMs] into [out]. */
    fun timeAt(tMs: Double, out: FloatArray) {
        YtAnimEval.position(delayMs, playMs, repeat, direction, tMs, pos)
        keysAt((pos[1] * playMs).toFloat(), out)
    }

    /** The keyframe value at [ms] of one play into [out]. */
    fun keysAt(ms: Float, out: FloatArray) {
        val n = times.size
        if (n == 0) return
        if (ms <= times[0]) {
            copyKey(0, out)
            return
        }
        if (ms >= times[n - 1]) {
            copyKey(n - 1, out)
            return
        }
        for (i in 1 until n) {
            if (ms <= times[i]) {
                val span = times[i] - times[i - 1]
                if (span <= 0f) {
                    copyKey(i, out)
                    return
                }
                val e = YtCurves.eval(curves[i - 1], (ms - times[i - 1]) / span)
                for (d in 0 until dims) {
                    val a = values[(i - 1) * dims + d]
                    out[d] = a + (values[i * dims + d] - a) * e
                }
                return
            }
        }
        copyKey(n - 1, out)
    }

    private fun copyKey(i: Int, out: FloatArray) {
        for (d in 0 until dims) out[d] = values[i * dims + d]
    }

    /** Sets the spring up for play [i]: forward from→to, backward to→from. */
    fun springPlay(i: Int, startValue: FloatArray?, startVelocity: FloatArray?) {
        play = i
        val fwd = YtAnimEval.forward(direction, i)
        for (d in 0 until dims) {
            val target = if (fwd) to[d] else from[d]
            val start = startValue?.get(d) ?: if (fwd) from[d] else to[d]
            sx[d] = (start - target).toDouble()
            sv[d] = (startVelocity?.get(d) ?: (if (fwd) v0[d] else -v0[d])).toDouble()
        }
    }

    fun springTarget(d: Int): Float = if (YtAnimEval.forward(direction, play)) to[d] else from[d]

    /** Advances a live spring by [dtMs]; returns false when its last play has settled. */
    fun springStep(dtMs: Double, out: FloatArray, vel: FloatArray): Boolean {
        if (elapsed < delayMs) {
            for (d in 0 until dims) {
                out[d] = (springTarget(d) + sx[d]).toFloat()
                vel[d] = 0f
            }
            return true
        }
        val dt = dtMs / 1000.0
        var settled = true
        for (d in 0 until dims) {
            YtSpring.step(sx[d], sv[d], dt, stiffness, damping, step)
            sx[d] = step[0]
            sv[d] = step[1]
            if (!YtSpring.settled(sx[d], sv[d], threshold)) settled = false
        }
        if (settled) {
            for (d in 0 until dims) {
                out[d] = springTarget(d)
                vel[d] = 0f
            }
            if (repeat >= 0 && play >= repeat) return false
            springPlay(play + 1, null, null)
            return true
        }
        for (d in 0 until dims) {
            out[d] = (springTarget(d) + sx[d]).toFloat()
            vel[d] = sv[d].toFloat()
        }
        return true
    }

    /** A spring evaluated at [tMs] from a fresh start (the editor's playhead, scroll): closed form. */
    fun springAt(tMs: Double, out: FloatArray) {
        YtAnimEval.position(delayMs, playMs, repeat, YtAnimDirection.NORMAL, tMs, pos)
        val i = pos[0].toInt()
        val local = pos[1] * playMs
        val fwd = YtAnimEval.forward(direction, i)
        for (d in 0 until dims) {
            val target = if (fwd) to[d] else from[d]
            val start = if (fwd) from[d] else to[d]
            if (pos[1] >= 1.0 && (repeat >= 0 && i >= repeat)) {
                out[d] = target
                continue
            }
            YtSpring.step((start - target).toDouble(), (if (fwd) v0[d] else -v0[d]).toDouble(), local / 1000.0, stiffness, damping, step)
            out[d] = (target + step[0]).toFloat()
        }
    }

    /** The value at [tMs] (driven runs). */
    fun at(tMs: Double, out: FloatArray) = if (kind == YtAnimKind.SPRING) springAt(tMs, out) else timeAt(tMs, out)

    /** The value at the end: the last play's end, or the end of the first for one that repeats forever. */
    fun end(out: FloatArray) {
        val last = if (repeat < 0) 0 else repeat
        val fwd = YtAnimEval.forward(direction, last)
        if (kind == YtAnimKind.SPRING) {
            for (d in 0 until dims) out[d] = if (fwd) to[d] else from[d]
        } else {
            keysAt(if (fwd) playMs else 0f, out)
        }
    }
}

/**
 * The engine of one component instance. [start] plays an animation's tracks (offset by a group's
 * schedule), [tick] moves every running run on by the frame time. With [snap] (motion off or
 * "remove animations") a start sets the end value at once and nothing runs.
 */
class YtAnimEngine(private val rest: YtAnimRest = YtAnimRest { _, _ -> null }) {
    private val nodes = HashMap<String, YtAnimNode>()
    private val runs = ArrayList<YtAnimRun>()
    private val drivenRuns = ArrayList<YtAnimRun>()
    private var lastNanos = -1L
    private val scratch = FloatArray(4)

    var snap = false

    /** Something runs (the frame loop is needed). */
    val running: Boolean get() = runs.isNotEmpty()

    /** Counts value changes, so the drawing knows a new frame is there. */
    var version = 0
        private set

    fun node(id: String): YtAnimNode = nodes.getOrPut(id) { YtAnimNode(id) }

    fun nodeOrNull(id: String): YtAnimNode? = nodes[id]

    /** The next tick takes no time (after a pause, so the time away does not count). */
    fun resetClock() {
        lastNanos = -1L
    }

    /** Every channel back to the node's own look. */
    fun clear() {
        for (r in runs) r.channel.run = null
        runs.clear()
        drivenRuns.clear()
        for (n in nodes.values) for (c in n.channels) c?.rest()
        version++
    }

    /**
     * Plays [anim] from now, [offsetMs] later than its own delay. Returns the runs started.
     * [driven]: the run does not tick; [drive] sets its time (scroll-linked animations).
     */
    fun start(anim: YtAnim, offsetMs: Int = 0, driven: Boolean = false): Int {
        var count = 0
        val n = node(anim.node)
        val play = YtAnimEval.playMs(anim).toFloat()
        for (track in anim.playing()) {
            val ch = n.take(track.prop)
            val interrupting = ch.run != null
            val run = build(anim, track, ch, play, offsetMs, driven, interrupting)
            // the last started wins
            ch.run?.let { old -> runs.remove(old); drivenRuns.remove(old) }
            if (snap && !driven) {
                run.end(scratch)
                write(ch, scratch)
                ch.velocity.fill(0f)
                ch.run = null
                count++
                continue
            }
            ch.run = run
            if (driven) {
                drivenRuns += run
            } else {
                if (run.kind == YtAnimKind.SPRING) run.springPlay(0, if (interrupting) ch.value.copyOf() else null, if (interrupting) ch.velocity.copyOf() else null)
                // the first frame shows the start (before the delay: the first keyframe)
                if (run.kind == YtAnimKind.SPRING) run.springStep(0.0, scratch, ch.velocity) else run.timeAt(0.0, scratch)
                write(ch, scratch)
                runs += run
            }
            count++
        }
        version++
        return count
    }

    private fun build(anim: YtAnim, track: YtTrack, ch: YtAnimChannel, play: Float, offsetMs: Int, driven: Boolean, interrupting: Boolean): YtAnimRun {
        val dims = ch.dims
        val keys = track.keys.sortedBy { it.at }
        val own = if (track.prop.colour) rest.color(anim.node, track.prop) ?: 0xFF000000.toInt() else 0
        val values = FloatArray(keys.size * dims)
        keys.forEachIndexed { i, k ->
            if (track.prop.colour) YtOklab.fromArgb(k.color ?: own, values, i * dims) else values[i] = k.value
        }
        // an interrupting time-based run starts where the channel is now (its first played key)
        if (interrupting && anim.kind == YtAnimKind.TIME && keys.isNotEmpty() && !driven) {
            val first = if (YtAnimEval.forward(anim.direction, 0)) 0 else keys.lastIndex
            for (d in 0 until dims) values[first * dims + d] = currentOf(ch, own, d)
        }
        val from = FloatArray(dims) { values[it] }
        val to = FloatArray(dims) { values[(keys.size - 1).coerceAtLeast(0) * dims + it] }
        val v0 = FloatArray(dims) { if (track.prop.colour) 0f else track.velocity }
        return YtAnimRun(
            channel = ch,
            animId = anim.id,
            kind = anim.kind,
            times = FloatArray(keys.size) { keys[it].at.coerceIn(0f, 1f) * play },
            values = values,
            curves = Array(keys.size) { keys[it].curve },
            delayMs = (anim.delayMs + offsetMs).coerceAtLeast(0).toFloat(),
            playMs = play,
            repeat = anim.repeat,
            direction = anim.direction,
            from = from,
            to = to,
            v0 = v0,
            stiffness = anim.spring.stiffness.toDouble().coerceIn(1.0, 10_000.0),
            damping = anim.spring.damping.toDouble().coerceIn(0.05, 10.0),
            threshold = YtSpring.threshold(track.prop),
            driven = driven,
        )
    }

    private fun currentOf(ch: YtAnimChannel, own: Int, d: Int): Float {
        if (ch.set) return ch.value[d]
        if (ch.prop.colour) {
            YtOklab.fromArgb(own, scratch, 0)
            return scratch[d]
        }
        return ch.prop.rest
    }

    private fun write(ch: YtAnimChannel, v: FloatArray) {
        for (d in 0 until ch.dims) ch.value[d] = v[d]
        ch.set = true
    }

    /** Stops the runs of [animId] where they are. */
    fun stop(animId: String) {
        for (i in runs.indices.reversed()) {
            val r = runs[i]
            if (r.animId == animId) {
                r.channel.run = null
                r.channel.velocity.fill(0f)
                runs.removeAt(i)
            }
        }
        version++
    }

    /** A frame at [frameNanos]: every run moves on by the time since the last frame. */
    fun tick(frameNanos: Long): Boolean {
        val dt = if (lastNanos < 0L) 0.0 else ((frameNanos - lastNanos) / 1_000_000.0).coerceIn(0.0, MAX_STEP_MS)
        lastNanos = frameNanos
        return advance(dt)
    }

    /** Moves every run on by [dtMs]; returns whether any still runs. */
    fun advance(dtMs: Double): Boolean {
        if (runs.isEmpty()) return false
        var i = runs.size - 1
        while (i >= 0) {
            val r = runs[i]
            val ch = r.channel
            r.elapsed += dtMs
            var alive: Boolean
            if (r.kind == YtAnimKind.SPRING) {
                alive = r.springStep(if (r.elapsed - dtMs < r.delayMs) (r.elapsed - r.delayMs).coerceAtLeast(0.0) else dtMs, scratch, ch.velocity)
                write(ch, scratch)
            } else {
                val ended = r.total >= 0 && r.elapsed >= r.total
                r.timeAt(if (ended) r.total else r.elapsed, scratch)
                for (d in 0 until ch.dims) {
                    if (dtMs > 0.0) ch.velocity[d] = ((scratch[d] - ch.value[d]) / (dtMs / 1000.0)).toFloat()
                    ch.value[d] = scratch[d]
                }
                ch.set = true
                alive = !ended
            }
            if (!alive) {
                ch.run = null
                ch.velocity.fill(0f)
                // swap-remove keeps it free of allocations
                val last = runs.size - 1
                runs[i] = runs[last]
                runs.removeAt(last)
            }
            i--
        }
        version++
        return runs.isNotEmpty()
    }

    /** Sets the time of the driven runs of [animId] (scroll position); they take their channels back. */
    fun drive(animId: String, tMs: Double) {
        for (r in drivenRuns) {
            if (r.animId != animId) continue
            val ch = r.channel
            if (ch.run !== r) {
                ch.run?.let { runs.remove(it) }
                ch.run = r
            }
            r.at(tMs, scratch)
            write(ch, scratch)
        }
        version++
    }

    /**
     * The editor's playhead: every channel shows [anims] (with their schedule offsets) at
     * [tMs], as a fresh start, the later in the list winning on a shared property.
     */
    fun preview(anims: List<Pair<YtAnim, Int>>, tMs: Double) {
        for (r in runs) r.channel.run = null
        runs.clear()
        drivenRuns.clear()
        for (n in nodes.values) for (c in n.channels) c?.rest()
        for ((a, offset) in anims) {
            val n = node(a.node)
            val play = YtAnimEval.playMs(a).toFloat()
            for (track in a.playing()) {
                val ch = n.take(track.prop)
                val run = build(a, track, ch, play, offset, driven = true, interrupting = false)
                run.at(tMs, scratch)
                write(ch, scratch)
            }
        }
        version++
    }

    companion object {
        /** A frame longer than this counts as this long (a stall must not fling things away). */
        const val MAX_STEP_MS = 1000.0
    }
}
